#!/usr/bin/env python3
"""Seedance 正式候选批量生成（Ark）。3 镜头 × 3 候选 @1080p/5s，
下载到 assets/footage/<scene>/seedance-0N.mp4 并写 generation.json。
用法：python3 seedance_gen.py [submit|poll|all]（默认 all）"""
import json, sys, time, urllib.request, urllib.error, pathlib

ROOT = pathlib.Path(__file__).resolve().parents[3]
FOOTAGE = pathlib.Path(__file__).resolve().parents[1] / "assets" / "footage"
KEY = next(l.split("=", 1)[1].strip().strip('"') for l in (ROOT / ".env").read_text().splitlines()
           if l.startswith("SEEDANCE_KEY="))
BASE = "https://ark.cn-beijing.volces.com/api/v3"
STATE = pathlib.Path(__file__).with_name("seedance-tasks.json")
MODELS = ["doubao-seedance-2-0-260128", "doubao-seedance-1-0-pro-250528"]  # 2.0 优先，未开通回落
PARAMS = " --ratio 9:16 --resolution 1080p --duration 5 --watermark false"

STYLE = ("竖屏9:16真实纪录片摄影感，低饱和，深蓝墨绿高级灰色调，少量暖橙灯光，自然室内光，"
         "普通开发者日常，不露正脸，画面无字幕无文字无水印无品牌Logo，动作自然平静，镜头轻微运动。")
NEG = "所有屏幕暗淡模糊、绝无可读文字或界面；不要网红摆拍、赛博朋克、代码雨、豪华办公室、夸张未来感。"

SHOTS = {
    "01-leave-desk": [
        "夜晚，一位开发者从书桌前站起身离开，暖橙台灯亮着，笔记本留在桌上屏幕微光模糊，过肩视角拍背影。",
        "夜晚书房，开发者合上外套起身离开书桌走出画面，台灯与笔记本留在原位，侧后方中景。",
        "从房间门口方向拍：开发者起身离开书桌向门口走来又走出画框，书桌台灯暖光，景深浅。",
    ],
    "02-elevator-realize": [
        "公寓楼道里，男子边走边从口袋掏出手机看了一眼，脚步略停顿像想起什么，肩后视角，冷色走廊灯。",
        "电梯轿厢内，男子低头掏出手机看一眼后抬头，若有所思，电梯金属质感冷光，背影与手部特写。",
        "等电梯的人掏出手机瞥了一眼屏幕又收起，电梯门金属反光，侧后方近景，手部动作自然。",
    ],
    "03-ending-life": [
        "傍晚客厅，人放松地靠在沙发上伸展手臂，窗外城市灯光初亮，手机随意放在沙发扶手上，惬意松弛。",
        "夜晚，人端着水杯站在窗边看城市夜景，背影剪影，室内暖光与窗外冷色对比，平静收尾氛围。",
        "人合上笔记本电脑、关掉台灯起身离开房间，只留窗外微光，一天结束的松弛感，中景背影。",
    ],
}

def call(method, url, body=None):
    req = urllib.request.Request(url, method=method,
        data=json.dumps(body).encode() if body else None,
        headers={"Authorization": f"Bearer {KEY}", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        raw = e.read().decode(errors="replace")
        try: return e.code, json.loads(raw)
        except Exception: return e.code, {"raw": raw[:300]}

def submit():
    tasks = json.loads(STATE.read_text()) if STATE.exists() else {}
    for scene, variants in SHOTS.items():
        for i, v in enumerate(variants, 1):
            tag = f"{scene}/seedance-{i:02d}"
            if tasks.get(tag, {}).get("id"):
                continue
            prompt = STYLE + v + NEG + PARAMS
            for model in MODELS:
                for attempt in range(6):
                    st, resp = call("POST", f"{BASE}/contents/generations/tasks",
                                    {"model": model, "content": [{"type": "text", "text": prompt}]})
                    if st == 200 and resp.get("id"):
                        tasks[tag] = {"id": resp["id"], "model": model, "prompt": v}
                        print(f"[{tag}] submitted {resp['id']} ({model})", flush=True)
                        break
                    msg = json.dumps(resp, ensure_ascii=False)
                    if "ModelNotOpen" in msg or "NotFound" in msg:
                        print(f"[{tag}] {model} 不可用，换下一档", flush=True); break
                    if st == 429 or "Throttling" in msg or "concurrent" in msg.lower():
                        print(f"[{tag}] 限流，等 30s（{attempt+1}/6）", flush=True); time.sleep(30); continue
                    print(f"[{tag}] HTTP {st}: {msg[:200]}", flush=True); break
                if tasks.get(tag):
                    break
            STATE.write_text(json.dumps(tasks, ensure_ascii=False, indent=1))
            time.sleep(2)
    return tasks

def poll():
    tasks = json.loads(STATE.read_text())
    pending = {k: v for k, v in tasks.items() if v.get("id") and not v.get("file")}
    for _ in range(180):          # ~45 min 上限
        for tag, t in list(pending.items()):
            st, resp = call("GET", f"{BASE}/contents/generations/tasks/{t['id']}")
            status = resp.get("status", "?")
            if status == "succeeded":
                url = resp.get("content", {}).get("video_url")
                scene, name = tag.split("/")
                d = FOOTAGE / scene; d.mkdir(parents=True, exist_ok=True)
                dst = d / f"{name}.mp4"
                urllib.request.urlretrieve(url, dst)
                t["file"] = str(dst.relative_to(ROOT)); t["usage"] = resp.get("usage", {})
                print(f"[{tag}] ✅ {dst.name}  tokens={t['usage'].get('total_tokens')}", flush=True)
                gj = d / "generation.json"
                g = json.loads(gj.read_text()) if gj.exists() else {"sceneId": scene, "primaryProvider": "seedance", "candidates": {}}
                g["candidates"][name] = {"model": t["model"], "prompt": t["prompt"], "taskId": t["id"]}
                gj.write_text(json.dumps(g, ensure_ascii=False, indent=1))
                pending.pop(tag)
            elif status in ("failed", "cancelled", "expired"):
                t["error"] = json.dumps(resp, ensure_ascii=False)[:300]
                print(f"[{tag}] ❌ {status}: {t['error'][:150]}", flush=True)
                pending.pop(tag)
        STATE.write_text(json.dumps(tasks, ensure_ascii=False, indent=1))
        if not pending:
            print("ALL DONE", flush=True); return
        time.sleep(15)
    print(f"TIMEOUT，剩余 {list(pending)}", flush=True)

if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else "all"
    if mode in ("submit", "all"): submit()
    if mode in ("poll", "all"): poll()
