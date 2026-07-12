#!/usr/bin/env python3
"""
render.py — cc-pocket promo video pipeline.

storyboard JSON → edge-tts voiceover (per scene, cached) → deterministic
frame-by-frame Playwright render of scenes/*.html → ffmpeg segments → final mp4.

Usage:
  python3 render.py storyboard-v1.json            # full render
  python3 render.py storyboard-v1.json --only takeover   # one scene, fast iterate
  python3 render.py storyboard-v1.json --stills   # 3 stills per scene, no video

Scene contract:  window.SCENE = { frame(tMs) }  — everything on screen is a pure
function of t (rig/rig.js freezes CSS animation). Scene duration follows the
voiceover audio; minMs in the storyboard is the floor.
"""
import argparse, asyncio, hashlib, json, math, shutil, subprocess, sys, tempfile
from pathlib import Path
from urllib.parse import quote

HERE = Path(__file__).parent.resolve()
OUT = HERE / "out"
CACHE = OUT / "vo-cache"
FPS_DEFAULT = 30
LEAD_MS = 250        # voiceover starts this long after the scene cuts in

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

def encode_segment(frames_dir: Path, fps: int, dur_ms: int, vo: Path | None,
                   out_mp4: Path, fade_in=True, fade_out=True):
    dur = dur_ms / 1000
    # Video Stage V4: scene-to-scene = 8-frame dip-to-black (4f out + 4f in @30fps)
    dip = 4 / fps
    vf = ",".join(filter(None, [
        f"fade=t=in:st=0:d={dip:.4f}" if fade_in else None,
        f"fade=t=out:st={dur - dip:.4f}:d={dip:.4f}" if fade_out else None,
    ])) or "null"
    cmd = ["ffmpeg", "-y", "-framerate", str(fps), "-i", str(frames_dir / "f%05d.png")]
    if vo:
        cmd += ["-i", str(vo),
                "-af", f"adelay={LEAD_MS}|{LEAD_MS},apad",
                "-c:a", "aac", "-b:a", "160k"]
    else:
        cmd += ["-f", "lavfi", "-i", "anullsrc=r=44100:cl=stereo",
                "-c:a", "aac", "-b:a", "160k"]
    cmd += ["-vf", vf, "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "18",
            "-r", str(fps), "-t", f"{dur:.3f}", str(out_mp4)]
    sh(cmd)

def concat(segments: list[Path], out_mp4: Path):
    lst = out_mp4.with_suffix(".txt")
    lst.write_text("".join(f"file '{s.resolve()}'\n" for s in segments))
    sh(["ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", str(lst),
        "-c", "copy", str(out_mp4)])
    lst.unlink()

def mix_bgm(video: Path, bgm: Path, out_mp4: Path, gain_db=-14):
    sh(["ffmpeg", "-y", "-i", str(video), "-stream_loop", "-1", "-i", str(bgm),
        "-filter_complex",
        f"[1:a]volume={gain_db}dB[b];[0:a][b]amix=inputs=2:duration=first:normalize=0[a]",
        "-map", "0:v", "-map", "[a]", "-c:v", "copy", "-c:a", "aac", "-b:a", "192k",
        str(out_mp4)])

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("storyboard")
    ap.add_argument("--only", help="render just this scene id (segment mp4 only)")
    ap.add_argument("--stills", action="store_true",
                    help="3 stills per scene instead of video (fast visual check)")
    args = ap.parse_args()

    sb = json.loads((HERE / args.storyboard).read_text())
    fps = sb.get("fps", FPS_DEFAULT)
    voice, rate = sb["voice"], sb.get("rate", "+0%")
    lang = sb.get("lang", "zh")
    OUT.mkdir(exist_ok=True)

    # 1) voiceover first — audio decides each scene's duration
    plan = []
    for sc in sb["scenes"]:
        if args.only and sc["id"] != args.only:
            continue
        # external voiceover wins: drop assets/vo/<sceneId>.mp3 (MiniMax / human read)
        # and the pipeline uses it verbatim — durations adapt automatically
        ext = HERE / "assets" / "vo" / f"{sc['id']}.mp3"
        vo = ext if ext.exists() else (tts(sc["vo"], voice, rate) if sc.get("vo") else None)
        vo_ms = probe_ms(vo) if vo else 0
        dur = max(sc.get("minMs", 0), vo_ms + LEAD_MS + sc.get("tailMs", 400))
        plan.append((sc, vo, dur))
        print(f"  {sc['id']:<10} vo={vo_ms/1000:5.2f}s  scene={dur/1000:5.2f}s")
    total = sum(d for _, _, d in plan)
    print(f"  total ≈ {total/1000:.1f}s")

    # 2) frames + segments
    from playwright.sync_api import sync_playwright
    segs = []
    with sync_playwright() as p, tempfile.TemporaryDirectory() as tmp:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 540, "height": 960},
                                device_scale_factor=2)
        for sc, vo, dur in plan:
            qs = "&".join(f"{k}={quote(str(sc[k]))}" for k in ("caption", "sub") if sc.get(k))
            file = sc.get("file", "stage.html")
            if sc.get("frames"):                       # real-app frame sequence via stage.html
                fdir_app = HERE / "out" / "appframes" / sc["frames"]
                n_app = len(list(fdir_app.glob("f*.png")))
                if n_app == 0:
                    sys.exit(f"no app frames for '{sc['frames']}' — run the ShowcaseRender gradle test first")
                qs += f"&frames={quote(str(fdir_app))}&n={n_app}&sfps={fps}"
                if sc.get("push"):
                    qs += "&push=1"
            url = (HERE / "scenes" / file).as_uri() + f"?lang={lang}&dur={dur}" + (f"&{qs}" if qs else "")
            fdir = Path(tmp) / sc["id"]
            print(f"  rendering {sc['id']} …")
            n = render_scene_frames(page, url, dur, fps, fdir, stills_only=args.stills)
            if args.stills:
                dst = OUT / "stills" / sc["id"]
                dst.mkdir(parents=True, exist_ok=True)
                for f in fdir.glob("*.png"):
                    shutil.copy(f, dst / f.name)
                continue
            seg = OUT / f"seg-{sc['id']}.mp4"
            first = sc is plan[0][0]
            last = sc is plan[-1][0]
            encode_segment(fdir, fps, dur, vo, seg, fade_in=not first, fade_out=not last)
            segs.append(seg)
        browser.close()

    if args.stills:
        print(f"stills → {OUT/'stills'}"); return
    if args.only:
        print(f"segment → {segs[0]}"); return

    # 3) concat + optional bgm
    final = OUT / f"{sb['id']}-{lang}.mp4"
    concat(segs, final)
    bgms = sorted((HERE / "assets" / "bgm").glob("*.mp3"))
    if bgms:
        mixed = OUT / f"{sb['id']}-{lang}-bgm.mp4"
        mix_bgm(final, bgms[0], mixed)
        print(f"final (bgm mixed) → {mixed}")
    print(f"final → {final}  ({total/1000:.1f}s)")

if __name__ == "__main__":
    main()
