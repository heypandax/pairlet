#!/usr/bin/env python3
"""Smoke-probe GPTProto video models (Seedance / Kling). Reads GPTPROTOAPIKEY from repo .env."""
import json, sys, time, urllib.request, pathlib

ROOT = pathlib.Path(__file__).resolve().parents[3]
KEY = next(l.split("=",1)[1].strip().strip('"') for l in (ROOT/".env").read_text().splitlines()
           if l.startswith("GPTPROTOAPIKEY="))
BASE = "https://gptproto.com/api/v3"

def call(method, url, body=None):
    req = urllib.request.Request(url, method=method,
        data=json.dumps(body).encode() if body else None,
        headers={"Authorization": f"Bearer {KEY}", "Content-Type": "application/json", "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        raw = e.read().decode(errors="replace")
        try: return e.code, json.loads(raw)
        except Exception: return e.code, {"raw": raw[:200]}

PROMPT = ("Vertical 9:16 documentary-style footage, over-the-shoulder view: a developer stands up "
          "from a desk at night and walks away, desk lamp warm orange glow, dark blue-grey room, "
          "laptop screen visible but dim and blurry with no readable text, low saturation, natural "
          "indoor light, realistic, calm. No text overlays, no captions, face not visible.")
NEG = "readable text, UI, subtitles, logo, watermark, face closeup, cartoon, anime, oversaturated"

def submit(tag, vendor, model, extra):
    for kind in ("text-to-video", "image-to-video"):
        body = {"prompt": PROMPT, "negative_prompt": NEG, "aspect_ratio": "9:16", **extra}
        if kind == "image-to-video":
            body["image"] = "https://tos.gptproto.com/resource/cat.png"  # doc sample, probe only
        st, resp = call("POST", f"{BASE}/{vendor}/{model}/{kind}", body)
        print(f"[{tag}] {kind} -> HTTP {st}: {json.dumps(resp, ensure_ascii=False)[:300]}")
        if st == 200 and resp.get("data", {}).get("id"):
            return resp["data"]["id"]
    return None

if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "poll":
        poll(); sys.exit(0)
    which = sys.argv[1] if len(sys.argv) > 1 else "both"
    ids = {}
    if which in ("both", "seedance"):
        ids["seedance"] = submit("seedance", "bytedance", "dreamina-seedance-2-0-mini-260615",
                                 {"duration": 4, "resolution": "480p", "seed": -1})
    if which in ("both", "kling"):
        ids["kling"] = submit("kling", "kwaivgi", "kling-v2.5-turbo-std",
                              {"duration": 5, "sound": False})
    print("TASK_IDS:", json.dumps(ids))
    pathlib.Path(__file__).with_name("probe-ids.json").write_text(json.dumps(ids))

def poll():
    ids = json.loads((pathlib.Path(__file__).with_name("probe-ids.json")).read_text())
    pending = {k: v for k, v in ids.items() if v}
    out = pathlib.Path(__file__).resolve().parents[1] / "out" / "eval"
    out.mkdir(parents=True, exist_ok=True)
    for _ in range(120):  # up to ~20 min
        for tag, pid in list(pending.items()):
            st, resp = call("GET", f"{BASE}/predictions/{pid}/result")
            d = resp.get("data", resp)
            status = d.get("status", "?")
            print(f"[{tag}] {status}")
            if status in ("succeeded", "completed"):
                url = (d.get("outputs") or [None])[0]
                if url:
                    dst = out / f"probe-{tag}.mp4"
                    urllib.request.urlretrieve(url, dst)
                    print(f"[{tag}] saved -> {dst}")
                pending.pop(tag)
            elif status in ("failed", "expired"):
                print(f"[{tag}] FAILED: {json.dumps(d, ensure_ascii=False)[:300]}")
                pending.pop(tag)
        if not pending:
            return
        time.sleep(10)
