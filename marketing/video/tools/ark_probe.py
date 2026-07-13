#!/usr/bin/env python3
"""Volcengine Ark Seedance probe: submit text-to-video task + poll. Key: .env SEEDANCE_KEY."""
import json, sys, time, urllib.request, urllib.error, pathlib

ROOT = pathlib.Path(__file__).resolve().parents[3]
KEY = next(l.split("=",1)[1].strip().strip('"') for l in (ROOT/".env").read_text().splitlines()
           if l.startswith("SEEDANCE_KEY="))
BASE = "https://ark.cn-beijing.volces.com/api/v3"
STATE = pathlib.Path(__file__).with_name("ark-task.json")

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

PROMPT = ("竖屏9:16纪录片质感：夜晚，一位开发者从书桌前站起身离开，暖橙色台灯，深蓝灰色调房间，"
          "笔记本屏幕微亮但模糊无可读文字，低饱和，自然室内光，过肩视角不露正脸，动作自然平静。"
          "无字幕无文字无水印。 --ratio 9:16 --resolution 480p --duration 5 --watermark false")

MODELS = ["doubao-seedance-1-0-lite-t2v-250428", "doubao-seedance-1-5-pro-251215",
          "doubao-seedance-2-0-fast-260128", "doubao-seedance-2-0-260128",
          "doubao-seedance-1-0-pro-250528"]

def submit():
    for m in MODELS:
        st, resp = call("POST", f"{BASE}/contents/generations/tasks",
                        {"model": m, "content": [{"type": "text", "text": PROMPT}]})
        print(f"[{m}] HTTP {st}: {json.dumps(resp, ensure_ascii=False)[:220]}")
        if st == 200 and resp.get("id"):
            STATE.write_text(json.dumps({"model": m, "id": resp["id"]}))
            print(f"SUBMITTED model={m} id={resp['id']}")
            return True
    return False

def poll():
    task = json.loads(STATE.read_text())
    out = pathlib.Path(__file__).resolve().parents[1] / "out" / "eval"
    out.mkdir(parents=True, exist_ok=True)
    for _ in range(90):
        st, resp = call("GET", f"{BASE}/contents/generations/tasks/{task['id']}")
        status = resp.get("status", "?")
        print(f"{status}", flush=True)
        if status == "succeeded":
            url = resp.get("content", {}).get("video_url")
            print("video_url:", url)
            if url:
                dst = out / "probe-seedance-ark.mp4"
                urllib.request.urlretrieve(url, dst)
                print("saved ->", dst)
            print("usage:", json.dumps(resp.get("usage", {})))
            return
        if status in ("failed", "cancelled", "expired"):
            print("FAILED:", json.dumps(resp, ensure_ascii=False)[:400]); return
        time.sleep(10)

if __name__ == "__main__":
    (poll if len(sys.argv) > 1 and sys.argv[1] == "poll" else submit)()
