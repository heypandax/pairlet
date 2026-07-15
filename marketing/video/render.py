#!/usr/bin/env python3
"""
render.py — cc-pocket promo video pipeline.

Legacy (v2 storyboards, `scenes` list):
  python3 render.py storyboard-v2.json [--only id] [--stills]

v3 storyboards (`segments` of multi-shot scenes) + subcommands:
  python3 render.py lock-facts                     # git facts → output/facts.lock.json
  python3 render.py resolve-timeline storyboard-v3.json   # TTS → output/timeline.lock.json
  python3 render.py validate storyboard-v3.json [--allow-placeholder]
  python3 render.py animatic storyboard-v3.json [--fps 15]
  python3 render.py render storyboard-v3.json [--allow-placeholder] [--only-segment id] [--out name]
  python3 render.py footage-contact-sheet

Scene contract: window.SCENE = { frame(tMs) } — every pixel is a pure function
of t (rig/rig.js freezes CSS animation). Segment duration = the timeline
solver's max(voiceover + lead + tail-hold, sum of shot minimums); extra time
goes to `flex` shots. Footage is ffmpeg-pre-extracted PNG sequences reusing the
stage frame-swap mechanism — never <video> seeks (not frame-accurate headless).
"""
import argparse, asyncio, datetime, hashlib, json, math, shutil, subprocess, sys, tempfile
from pathlib import Path
from urllib.parse import quote

HERE = Path(__file__).parent.resolve()
OUT = HERE / "out"                 # caches: vo-cache, appframes, footage-cache, stills
OUTPUT = HERE / "output"           # deliverables: locks, animatic, finals
CACHE = OUT / "vo-cache"
FPS_DEFAULT = 30
LEAD_MS = 250        # voiceover starts this long after the segment cuts in
TAIL_MS = 400        # default hold after the voiceover ends

# 内容红线（方案第十五节，Seedance-only 版）：命中即 validate 失败
RED_LINES = [
    "不用电脑", "同一进程双端同步", "完全隔离", "安全沙箱", "只有你能看到代码",
    "零日志", "军事级加密", "不可破解", "已通过安全审计", "完全匿名",
    "绕过认证", "绕过官方限制", "Secure Enclave", "硬件级密钥保护", "跨机器直接批准",
    "接管", "入侵",   # 「未授权访问」语感——抖音法规类限流实证
]

def sh(cmd, **kw):
    r = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if r.returncode != 0:
        sys.exit(f"FAILED: {' '.join(map(str, cmd))}\n{r.stderr[-2000:]}")
    return r.stdout

def probe_ms(path: Path) -> int:
    out = sh(["ffprobe", "-v", "error", "-show_entries", "format=duration",
              "-of", "csv=p=0", str(path)])
    return int(float(out.strip()) * 1000)

def tts(text: str, voice: str, rate: str) -> Path:
    CACHE.mkdir(parents=True, exist_ok=True)
    key = hashlib.sha1(f"{voice}|{rate}|{text}".encode()).hexdigest()[:16]
    mp3 = CACHE / f"{key}.mp3"
    if not mp3.exists():
        import edge_tts
        async def go():
            await edge_tts.Communicate(text, voice, rate=rate).save(str(mp3))
        asyncio.run(go())
    return mp3

def vo_for(seg_id: str, text: str | None, voice: str, rate: str) -> Path | None:
    ext = HERE / "assets" / "vo" / f"{seg_id}.mp3"   # external VO (MiniMax / human) wins
    if ext.exists():
        return ext
    return tts(text, voice, rate) if text else None

# ── footage: mp4 → PNG frame sequence (cached), reusing the stage swap mechanism ──

def footage_source(shot: dict) -> Path:
    if shot.get("source"):
        return HERE / shot["source"]
    return HERE / "assets" / "footage" / shot["footage"] / "selected.mp4"

def ensure_footage_frames(shot: dict, fps: int) -> tuple[Path, int] | None:
    """Extract [trimStart, trimEnd] of the source into a cached PNG sequence.
    Returns (dir, n) or None when the source is missing."""
    src = footage_source(shot)
    if not src.exists():
        return None
    t0, t1 = float(shot.get("trimStart", 0)), shot.get("trimEnd")
    key = hashlib.sha1(f"{src}|{src.stat().st_mtime_ns}|{t0}|{t1}|{fps}".encode()).hexdigest()[:12]
    fdir = OUT / "footage-cache" / f"{shot.get('footage', src.stem)}-{key}"
    if not fdir.exists() or not any(fdir.glob("f*.png")):
        fdir.mkdir(parents=True, exist_ok=True)
        cmd = ["ffmpeg", "-y", "-ss", str(t0)]
        if t1 is not None:
            cmd += ["-to", str(t1)]
        cmd += ["-i", str(src), "-vf", f"fps={fps},scale=-2:1920",
                str(fdir / "f%05d_raw.png")]
        sh(cmd)
        for i, f in enumerate(sorted(fdir.glob("f*_raw.png"))):   # ffmpeg is 1-based
            f.rename(fdir / f"f{i:05d}.png")
    return fdir, len(list(fdir.glob("f*.png")))

# ── timeline solver（方案第十一节）──

def resolve_timeline(sb: dict, write_lock=True) -> dict:
    voice, rate = sb["voice"], sb.get("rate", "+0%")
    t = 0
    segments = []
    for seg in sb["segments"]:
        vo = vo_for(seg["id"], seg.get("vo"), voice, rate)
        vo_ms = probe_ms(vo) if vo else 0
        lead = int(seg.get("voiceOffsetMs", LEAD_MS))
        base = vo_ms + lead + int(seg.get("tailHoldMs", TAIL_MS))
        shots = [dict(s) for s in seg["shots"]]
        min_sum = sum(int(s.get("minMs", 1000)) for s in shots)
        seg_ms = max(base, min_sum)
        extra = seg_ms - min_sum
        flex = [s for s in shots if s.get("flex")] or [shots[-1]]
        for s in shots:
            s["ms"] = int(s.get("minMs", 1000))
        for i, s in enumerate(flex):
            s["ms"] += extra // len(flex) + (extra % len(flex) if i == 0 else 0)
        segments.append({
            "id": seg["id"], "voMs": vo_ms, "voiceOffsetMs": lead,
            "durMs": seg_ms, "tStartMs": t, "tEndMs": t + seg_ms,
            "voExternal": (HERE / "assets" / "vo" / f"{seg['id']}.mp3").exists(),
            "shots": [{k: v for k, v in s.items()} for s in shots],
        })
        t += seg_ms
    lock = {"storyboard": sb["id"], "voice": voice, "rate": rate,
            "totalMs": t, "segments": segments}
    if write_lock:
        OUTPUT.mkdir(exist_ok=True)
        (OUTPUT / f"timeline-{sb['id']}.lock.json").write_text(
            json.dumps(lock, ensure_ascii=False, indent=2))
    return lock

# ── shot rendering ──

def shot_asset(shot: dict, fps: int, allow_placeholder: bool):
    """Resolve a shot to (scene_file, extra_query) or a placeholder; exit if hard-missing."""
    kind = shot.get("type", "html")
    label = shot.get("footage") or shot.get("frames") or shot.get("file") or shot.get("id", "?")
    if kind == "placeholder":
        return "placeholder.html", f"pid={quote(str(label))}"
    if kind == "footage":
        got = ensure_footage_frames(shot, fps)
        if got is None:
            if not allow_placeholder:
                sys.exit(f"missing footage: {footage_source(shot)} （--allow-placeholder 可占位）")
            return "placeholder.html", f"pid={quote(str(label))}"
        fdir, n = got
        need = math.ceil(shot["ms"] / 1000 * fps)
        if shot.get("shortagePolicy", "freeze") == "error" and n < need:
            sys.exit(f"footage too short for shot {label}: {n} frames < {need} needed")
        q = (f"frames={quote(str(fdir))}&n={n}&sfps={fps}"
             f"&fit={shot.get('fit', 'cover')}&short={shot.get('shortagePolicy', 'freeze')}")
        return "footage.html", q
    if kind == "frames":
        fdir = OUT / "appframes" / shot["frames"]
        n = len(list(fdir.glob("f*.png")))
        if n == 0:
            if not allow_placeholder:
                sys.exit(f"no app frames for '{shot['frames']}' — run ShowcaseRender first")
            return "placeholder.html", f"pid={quote(str(label))}"
        q = f"frames={quote(str(fdir))}&n={n}&sfps={fps}"
        if shot.get("push"):
            q += "&push=1"
        return "stage.html", q
    return shot.get("file", "stage.html"), ""

def render_scene_frames(page, scene_url: str, dur_ms: int, fps: int, frames_dir: Path,
                        stills_only=False):
    frames_dir.mkdir(parents=True, exist_ok=True)
    page.goto(scene_url, wait_until="networkidle")
    page.wait_for_function("() => window.SCENE && typeof window.SCENE.frame === 'function'")
    page.wait_for_function("() => window.STAGE_READY === undefined || window.STAGE_READY === true",
                           timeout=60000)
    n = max(1, math.ceil(dur_ms / 1000 * fps))
    ticks = ([0, n // 2, n - 1] if stills_only else range(n))
    for i in ticks:
        t = round(i * 1000 / fps)
        page.evaluate(f"SCENE.frame({t})")
        page.screenshot(path=str(frames_dir / f"f{i:05d}.png"))
    return n

def encode_shot(frames_dir: Path, fps: int, dur_ms: int, out_mp4: Path,
                fade_in=False, fade_out=False):
    """Silent shot clip. Fades only at segment boundaries (8-frame dip-to-black)."""
    dur = dur_ms / 1000
    dip = 4 / fps
    vf = ",".join(filter(None, [
        f"fade=t=in:st=0:d={dip:.4f}" if fade_in else None,
        f"fade=t=out:st={max(0.0, dur - dip):.4f}:d={dip:.4f}" if fade_out else None,
    ])) or "null"
    sh(["ffmpeg", "-y", "-framerate", str(fps), "-i", str(frames_dir / "f%05d.png"),
        "-vf", vf, "-an", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "18",
        "-r", str(fps), "-t", f"{dur:.3f}", str(out_mp4)])

def mux_segment(silent: Path, vo: Path | None, lead_ms: int, dur_ms: int, out_mp4: Path):
    cmd = ["ffmpeg", "-y", "-i", str(silent)]
    if vo:
        cmd += ["-i", str(vo), "-af", f"adelay={lead_ms}|{lead_ms},apad"]
    else:
        cmd += ["-f", "lavfi", "-i", "anullsrc=r=44100:cl=stereo"]
    cmd += ["-map", "0:v", "-map", "1:a" if vo else "1:a", "-c:v", "copy",
            "-c:a", "aac", "-b:a", "160k", "-t", f"{dur_ms/1000:.3f}", str(out_mp4)]
    sh(cmd)

def concat(paths: list[Path], out_mp4: Path):
    lst = out_mp4.with_suffix(".txt")
    lst.write_text("".join(f"file '{p.resolve()}'\n" for p in paths))
    sh(["ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", str(lst), "-c", "copy", str(out_mp4)])
    lst.unlink()

def mix_bgm(video: Path, bgm: Path, out_mp4: Path, gain_db=-14):
    sh(["ffmpeg", "-y", "-i", str(video), "-stream_loop", "-1", "-i", str(bgm),
        "-filter_complex",
        f"[1:a]volume={gain_db}dB[b];[0:a][b]amix=inputs=2:duration=first:normalize=0[a]",
        "-map", "0:v", "-map", "[a]", "-c:v", "copy", "-c:a", "aac", "-b:a", "192k",
        str(out_mp4)])

def render_v3(sb: dict, fps: int, allow_placeholder: bool, out_name: str,
              only_segment: str | None = None):
    lock = resolve_timeline(sb)
    lang = sb.get("lang", "zh")
    print(f"timeline: {lock['totalMs']/1000:.1f}s across {len(lock['segments'])} segments")
    from playwright.sync_api import sync_playwright
    seg_files = []
    with sync_playwright() as p, tempfile.TemporaryDirectory() as tmp:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 540, "height": 960}, device_scale_factor=2)
        n_segs = len(lock["segments"])
        for si, seg in enumerate(lock["segments"]):
            if only_segment and seg["id"] != only_segment:
                continue
            shot_files = []
            for hi, shot in enumerate(seg["shots"]):
                file, extra = shot_asset(shot, fps, allow_placeholder)
                qs = f"lang={lang}&dur={shot['ms']}"
                for k in ("caption", "sub"):
                    if shot.get(k):
                        qs += f"&{k}={quote(str(shot[k]))}"
                for k, v in (shot.get("params") or {}).items():   # 任意场景参数（如 facts 数字）
                    qs += f"&{quote(str(k))}={quote(str(v))}"
                if extra:
                    qs += f"&{extra}"
                url = (HERE / "scenes" / file).as_uri() + "?" + qs
                fdir = Path(tmp) / f"{seg['id']}-{hi}"
                print(f"  {seg['id']}/{hi} [{shot.get('type','html')}] {shot['ms']}ms …")
                render_scene_frames(page, url, shot["ms"], fps, fdir)
                clip = Path(tmp) / f"clip-{seg['id']}-{hi}.mp4"
                encode_shot(fdir, fps, shot["ms"], clip,
                            fade_in=(hi == 0 and si != 0),
                            fade_out=(hi == len(seg["shots"]) - 1 and si != n_segs - 1))
                shot_files.append(clip)
            silent = Path(tmp) / f"seg-{seg['id']}-silent.mp4"
            concat(shot_files, silent) if len(shot_files) > 1 else shutil.copy(shot_files[0], silent)
            voice, rate = sb["voice"], sb.get("rate", "+0%")
            src = next(s for s in sb["segments"] if s["id"] == seg["id"])
            vo = vo_for(seg["id"], src.get("vo"), voice, rate)
            seg_mp4 = OUT / f"seg3-{seg['id']}.mp4"
            mux_segment(silent, vo, seg["voiceOffsetMs"], seg["durMs"], seg_mp4)
            seg_files.append(seg_mp4)
        browser.close()
    if only_segment:
        print(f"segment → {seg_files[0]}")
        return seg_files[0]
    OUTPUT.mkdir(exist_ok=True)
    final = OUTPUT / out_name
    concat(seg_files, final)
    bgms = sorted((HERE / "assets" / "bgm").glob("*.mp3"))
    if bgms and not out_name.startswith("animatic"):
        mixed = OUTPUT / out_name.replace(".mp4", "-bgm.mp4")
        mix_bgm(final, bgms[0], mixed)
        print(f"final (bgm) → {mixed}")
    print(f"final → {final}  ({lock['totalMs']/1000:.1f}s)")
    return final

# ── subcommands ──

def cmd_lock_facts():
    root = HERE.parents[1]
    g = lambda *a: sh(["git", "-C", str(root), *a]).strip()
    facts = {
        "cutoffDate": datetime.date.today().isoformat(),
        "firstCommitDate": g("log", "--reverse", "--format=%cs").splitlines()[0],
        "commitCount": int(g("rev-list", "--count", "HEAD")),
        "releaseCount": len(g("tag").splitlines()),
    }
    facts["developmentDays"] = (datetime.date.fromisoformat(facts["cutoffDate"])
                                - datetime.date.fromisoformat(facts["firstCommitDate"])).days
    try:
        issues = json.loads(subprocess.run(
            ["gh", "issue", "list", "-R", "heypandax/cc-pocket", "--state", "all",
             "--limit", "500", "--json", "state"],
            capture_output=True, text=True, timeout=30).stdout)
        facts["issueTotal"] = len(issues)
        facts["issueClosed"] = sum(1 for i in issues if i["state"] == "CLOSED")
    except Exception:
        pass
    facts["sources"] = {"commits": "git rev-list --count HEAD",
                        "firstCommit": "git log --reverse --format=%cs | head -1",
                        "releases": "git tag | wc -l"}
    OUTPUT.mkdir(exist_ok=True)
    f = OUTPUT / "facts.lock.json"
    if f.exists():
        print(f"replacing existing lock:\n  old: {f.read_text().strip()[:200]}")
    f.write_text(json.dumps(facts, ensure_ascii=False, indent=2))
    print(json.dumps(facts, ensure_ascii=False, indent=2))
    print("提醒：数字已用于配音/画面时，刷新后须重生成对应 TTS 与数字卡。")

def cmd_validate(sb: dict, allow_placeholder: bool):
    bad, missing = [], []
    def scan(txt, where):
        for w in RED_LINES:
            if w in txt:
                bad.append(f"{where}: 「{w}」")
    for seg in sb["segments"]:
        scan(seg.get("vo", ""), f"vo:{seg['id']}")
        for i, s in enumerate(seg["shots"]):
            for k in ("caption", "sub"):
                if s.get(k):
                    scan(s[k], f"{k}:{seg['id']}/{i}")
            kind = s.get("type", "html")
            if kind == "footage" and not footage_source(s).exists():
                missing.append(f"footage {seg['id']}/{i}: {footage_source(s)}")
            if kind == "frames" and not list((OUT / "appframes" / s["frames"]).glob("f*.png")):
                missing.append(f"appframes {seg['id']}/{i}: {s['frames']}")
            if kind == "html" and not (HERE / "scenes" / s.get("file", "stage.html")).exists():
                missing.append(f"scene html {seg['id']}/{i}: {s.get('file')}")
    if bad:
        print("❌ 红线命中：\n  " + "\n  ".join(bad))
    if missing:
        print(("⚠️" if allow_placeholder else "❌") + " 缺素材：\n  " + "\n  ".join(missing))
    if bad or (missing and not allow_placeholder):
        sys.exit(1)
    print("validate OK" + (f"（{len(missing)} 项将占位）" if missing else ""))

def cmd_contact_sheet():
    root = HERE / "assets" / "footage"
    outdir = OUTPUT / "contact"
    outdir.mkdir(parents=True, exist_ok=True)
    rows = []
    for scene_dir in sorted(d for d in root.glob("*") if d.is_dir()):
        for mp4 in sorted(scene_dir.glob("*.mp4")):
            jpg = outdir / f"{scene_dir.name}-{mp4.stem}.jpg"
            sh(["ffmpeg", "-y", "-i", str(mp4),
                "-vf", "select='not(mod(n,15))',scale=270:-2,tile=6x1", "-frames:v", "1", str(jpg)])
            sel = " ✅selected" if mp4.name == "selected.mp4" else ""
            rows.append(f"<h3>{scene_dir.name} / {mp4.name}{sel}</h3><img src='contact/{jpg.name}'>")
    html = OUTPUT / "footage-contact-sheet.html"
    html.write_text("<meta charset=utf-8><body style='background:#111;color:#eee;font-family:sans-serif'>"
                    + ("".join(rows) or "<p>assets/footage/ 下暂无素材</p>"))
    print(f"contact sheet → {html}  ({len(rows)} clips)")

def write_timeline_html(lock: dict, name="animatic-timeline.html"):
    rows = []
    for s in lock["segments"]:
        tc = lambda ms: f"{ms//60000}:{ms%60000/1000:04.1f}"
        shots = " · ".join(f"{sh.get('type','html')}:{sh.get('footage') or sh.get('frames') or sh.get('file') or sh.get('id','?')}({sh['ms']}ms)"
                           for sh in s["shots"])
        rows.append(f"<tr><td>{s['id']}</td><td>{tc(s['tStartMs'])}–{tc(s['tEndMs'])}</td>"
                    f"<td>{s['durMs']/1000:.1f}s</td><td>{s['voMs']/1000:.1f}s"
                    f"{'（外部VO）' if s['voExternal'] else ''}</td><td>{shots}</td></tr>")
    (OUTPUT / name).write_text(
        "<meta charset=utf-8><style>body{background:#111;color:#eee;font-family:sans-serif}"
        "table{border-collapse:collapse}td,th{border:1px solid #444;padding:6px 10px;font-size:14px}</style>"
        f"<h2>{lock['storyboard']} — {lock['totalMs']/1000:.1f}s</h2>"
        "<table><tr><th>段</th><th>时间码</th><th>时长</th><th>口播</th><th>镜头</th></tr>"
        + "".join(rows) + "</table>")
    print(f"timeline html → {OUTPUT / name}")

# ── legacy v2 path (unchanged behavior) ──

def encode_segment_v2(frames_dir, fps, dur_ms, vo, out_mp4, fade_in=True, fade_out=True):
    dur = dur_ms / 1000
    dip = 4 / fps
    vf = ",".join(filter(None, [
        f"fade=t=in:st=0:d={dip:.4f}" if fade_in else None,
        f"fade=t=out:st={dur - dip:.4f}:d={dip:.4f}" if fade_out else None,
    ])) or "null"
    cmd = ["ffmpeg", "-y", "-framerate", str(fps), "-i", str(frames_dir / "f%05d.png")]
    if vo:
        cmd += ["-i", str(vo), "-af", f"adelay={LEAD_MS}|{LEAD_MS},apad", "-c:a", "aac", "-b:a", "160k"]
    else:
        cmd += ["-f", "lavfi", "-i", "anullsrc=r=44100:cl=stereo", "-c:a", "aac", "-b:a", "160k"]
    cmd += ["-vf", vf, "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "18",
            "-r", str(fps), "-t", f"{dur:.3f}", str(out_mp4)]
    sh(cmd)

def main_v2(args):
    sb = json.loads((HERE / args.storyboard).read_text())
    fps = sb.get("fps", FPS_DEFAULT)
    voice, rate = sb["voice"], sb.get("rate", "+0%")
    lang = sb.get("lang", "zh")
    OUT.mkdir(exist_ok=True)
    plan = []
    for sc in sb["scenes"]:
        if args.only and sc["id"] != args.only:
            continue
        vo = vo_for(sc["id"], sc.get("vo"), voice, rate)
        vo_ms = probe_ms(vo) if vo else 0
        dur = max(sc.get("minMs", 0), vo_ms + LEAD_MS + sc.get("tailMs", 400))
        plan.append((sc, vo, dur))
        print(f"  {sc['id']:<10} vo={vo_ms/1000:5.2f}s  scene={dur/1000:5.2f}s")
    total = sum(d for _, _, d in plan)
    print(f"  total ≈ {total/1000:.1f}s")
    from playwright.sync_api import sync_playwright
    segs = []
    with sync_playwright() as p, tempfile.TemporaryDirectory() as tmp:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 540, "height": 960}, device_scale_factor=2)
        for sc, vo, dur in plan:
            qs = "&".join(f"{k}={quote(str(sc[k]))}" for k in ("caption", "sub") if sc.get(k))
            file = sc.get("file", "stage.html")
            if sc.get("frames"):
                fdir_app = OUT / "appframes" / sc["frames"]
                n_app = len(list(fdir_app.glob("f*.png")))
                if n_app == 0:
                    sys.exit(f"no app frames for '{sc['frames']}' — run the ShowcaseRender gradle test first")
                qs += f"&frames={quote(str(fdir_app))}&n={n_app}&sfps={fps}"
                if sc.get("push"):
                    qs += "&push=1"
            url = (HERE / "scenes" / file).as_uri() + f"?lang={lang}&dur={dur}" + (f"&{qs}" if qs else "")
            fdir = Path(tmp) / sc["id"]
            print(f"  rendering {sc['id']} …")
            render_scene_frames(page, url, dur, fps, fdir, stills_only=args.stills)
            if args.stills:
                dst = OUT / "stills" / sc["id"]
                dst.mkdir(parents=True, exist_ok=True)
                for f in fdir.glob("*.png"):
                    shutil.copy(f, dst / f.name)
                continue
            seg = OUT / f"seg-{sc['id']}.mp4"
            first = sc is plan[0][0]
            last = sc is plan[-1][0]
            encode_segment_v2(fdir, fps, dur, vo, seg, fade_in=not first, fade_out=not last)
            segs.append(seg)
        browser.close()
    if args.stills:
        print(f"stills → {OUT/'stills'}"); return
    if args.only:
        print(f"segment → {segs[0]}"); return
    final = OUT / f"{sb['id']}-{lang}.mp4"
    concat(segs, final)
    bgms = sorted((HERE / "assets" / "bgm").glob("*.mp3"))
    if bgms:
        mixed = OUT / f"{sb['id']}-{lang}-bgm.mp4"
        mix_bgm(final, bgms[0], mixed)
        print(f"final (bgm mixed) → {mixed}")
    print(f"final → {final}  ({total/1000:.1f}s)")

def main():
    argv = sys.argv[1:]
    if argv and argv[0].endswith(".json"):        # legacy v2 CLI
        ap = argparse.ArgumentParser()
        ap.add_argument("storyboard"); ap.add_argument("--only"); ap.add_argument("--stills", action="store_true")
        main_v2(ap.parse_args(argv)); return

    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("lock-facts")
    for name in ("resolve-timeline", "validate", "animatic", "render"):
        s = sub.add_parser(name); s.add_argument("storyboard")
        s.add_argument("--allow-placeholder", action="store_true")
        s.add_argument("--fps", type=int)
        s.add_argument("--only-segment")
        s.add_argument("--out")
    sub.add_parser("footage-contact-sheet")
    args = ap.parse_args(argv)

    if args.cmd == "lock-facts":
        cmd_lock_facts(); return
    if args.cmd == "footage-contact-sheet":
        cmd_contact_sheet(); return
    sb = json.loads((HERE / args.storyboard).read_text())
    if args.cmd == "resolve-timeline":
        lock = resolve_timeline(sb)
        write_timeline_html(lock, "timeline.html")
        for s in lock["segments"]:
            print(f"  {s['id']:<10} vo={s['voMs']/1000:5.2f}s  seg={s['durMs']/1000:5.2f}s  @{s['tStartMs']/1000:6.1f}s")
        print(f"  total = {lock['totalMs']/1000:.1f}s → {OUTPUT/'timeline.lock.json'}")
    elif args.cmd == "validate":
        cmd_validate(sb, args.allow_placeholder)
    elif args.cmd == "animatic":
        lock = render_v3(sb, args.fps or 15, True, "animatic.mp4", args.only_segment)
        write_timeline_html(resolve_timeline(sb, write_lock=False))
    elif args.cmd == "render":
        render_v3(sb, args.fps or sb.get("fps", FPS_DEFAULT), args.allow_placeholder,
                  args.out or f"{sb['id']}-{sb.get('lang','zh')}.mp4", args.only_segment)

if __name__ == "__main__":
    main()
