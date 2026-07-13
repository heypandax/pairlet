#!/usr/bin/env python3
"""Seedance 2.0 重生成三条 selected 位：锁人设 prompt，胜出构图。"""
import json, sys, time, urllib.request, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).parent))
from seedance_gen import call, BASE, FOOTAGE, ROOT

MODEL = "doubao-seedance-2-0-260128"
PARAMS = " --ratio 9:16 --resolution 1080p --duration 5 --watermark false"
PERSONA = ("主角人设全片统一：一位短发亚洲男性开发者，约30岁，穿深墨绿色圆领针织衫、深色长裤，"
           "体型中等。")
STYLE = ("竖屏9:16真实纪录片摄影感，低饱和，深蓝墨绿高级灰色调，少量暖橙灯光，自然室内光，"
         "不露正脸（背影/侧身/过肩视角），画面无字幕无文字无水印无Logo，动作自然平静，镜头轻微运动。")
NEG = "所有屏幕暗淡模糊、绝无可读文字或界面；不要网红摆拍、赛博朋克、代码雨、豪华办公室。"

SHOTS = {
    "01-leave-desk": "夜晚书房侧视角：他从书桌前站起身、整理一下衣角离开画面，暖橙台灯亮着，笔记本留在桌上屏幕暗淡模糊。",
    "02-elevator-realize": "电梯门旁侧身近景：他从裤兜掏出手机看了一眼（手机屏幕暗不可读），若有所思地停顿，金属门反光，手部动作自然。",
    "03-ending-life": "夜晚他端着水杯站在落地窗边看城市夜景，背影剪影，室内暖光与窗外冷色对比，一天结束的松弛感。",
}

state = pathlib.Path(__file__).with_name("seedance20-tasks.json")
tasks = json.loads(state.read_text()) if state.exists() else {}
for scene, shot in SHOTS.items():
    if tasks.get(scene, {}).get("id"): continue
    st, resp = call("POST", f"{BASE}/contents/generations/tasks",
                    {"model": MODEL, "content": [{"type": "text", "text": PERSONA + STYLE + shot + NEG + PARAMS}]})
    print(f"[{scene}] HTTP {st}: {json.dumps(resp, ensure_ascii=False)[:180]}", flush=True)
    if st == 200 and resp.get("id"):
        tasks[scene] = {"id": resp["id"], "prompt": shot}
    state.write_text(json.dumps(tasks, ensure_ascii=False, indent=1))
    time.sleep(2)

pending = {k: v for k, v in tasks.items() if v.get("id") and not v.get("file")}
for _ in range(120):
    for scene, t in list(pending.items()):
        st, resp = call("GET", f"{BASE}/contents/generations/tasks/{t['id']}")
        status = resp.get("status", "?")
        if status == "succeeded":
            url = resp["content"]["video_url"]
            dst = FOOTAGE / scene / "seedance20-01.mp4"
            urllib.request.urlretrieve(url, dst)
            t["file"] = str(dst); t["usage"] = resp.get("usage", {})
            print(f"[{scene}] ✅ tokens={t['usage'].get('total_tokens')}", flush=True)
            pending.pop(scene)
        elif status in ("failed", "cancelled", "expired"):
            t["error"] = json.dumps(resp, ensure_ascii=False)[:300]
            print(f"[{scene}] ❌ {t['error'][:160]}", flush=True); pending.pop(scene)
    state.write_text(json.dumps(tasks, ensure_ascii=False, indent=1))
    if not pending: print("ALL DONE", flush=True); break
    time.sleep(15)
