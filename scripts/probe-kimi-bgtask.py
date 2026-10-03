#!/usr/bin/env python3
"""Probe how the Kimi Code CLI reports a BACKGROUND shell task over ACP (issue #391).

No Kimi account needed: the probe points a throwaway KIMI_CODE_HOME at a local mock model that scripts
one `Bash` call with `run_in_background=true`, so the CLI's own task machinery runs for real.

What the daemon depends on (KimiBackend, verified on 2.1.1):
  1. the launch settles at once with `task_id: bash-xxxxxxxx … status: running` in the tool output;
  2. ACP sends NO frame when the task later ends (no update kind exists for it);
  3. `<sessionDir>/agents/main/tasks/<taskId>.json` flips `status` from `running` to a terminal status
     (completed / failed / timed_out / killed / lost) — the only completion signal.

Usage:
    python3 scripts/probe-kimi-bgtask.py
Run it after every kimi CLI upgrade, next to probe-kimi-acp.py. Exit code 0 = all three still hold.
"""
import json, os, re, subprocess, sys, tempfile, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

KIMI = os.environ.get("CC_POCKET_KIMI_BIN") or os.path.expanduser("~/.kimi-code/bin/kimi")
if os.name == "nt" and not KIMI.endswith(".exe"):
    KIMI += ".exe"
TASK_SECONDS = 4
ROOT = tempfile.mkdtemp(prefix="kimi-bgtask-probe-")
HOME, WORK = os.path.join(ROOT, "home"), os.path.join(ROOT, "work")
os.makedirs(HOME); os.makedirs(WORK)


def chunk(delta, finish=None):
    return {"id": "c1", "object": "chat.completion.chunk", "created": int(time.time()), "model": "mock-1",
            "choices": [{"index": 0, "delta": delta, "finish_reason": finish}]}


class Mock(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        self.send_response(200); self.send_header("Content-Type", "application/json"); self.end_headers()
        self.wfile.write(json.dumps({"object": "list", "data": [{"id": "mock-1", "object": "model"}]}).encode())

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        if any(m.get("role") == "tool" for m in body.get("messages", [])):
            chunks = [chunk({"role": "assistant", "content": "started"}), chunk({}, "stop")]
        else:
            args = json.dumps({"command": "sleep %d; echo done-bg" % TASK_SECONDS, "run_in_background": True,
                               "description": "probe bg task"})
            chunks = [chunk({"role": "assistant", "content": None, "tool_calls": [
                {"index": 0, "id": "call_probe1", "type": "function", "function": {"name": "Bash", "arguments": args}}]}),
                chunk({}, "tool_calls")]
        self.send_response(200); self.send_header("Content-Type", "text/event-stream"); self.end_headers()
        for c in chunks:
            self.wfile.write(("data: " + json.dumps(c) + "\n\n").encode())
        self.wfile.write(b"data: [DONE]\n\n"); self.wfile.flush()


server = ThreadingHTTPServer(("127.0.0.1", 0), Mock)
threading.Thread(target=server.serve_forever, daemon=True).start()
with open(os.path.join(HOME, "config.toml"), "w") as f:
    f.write('default_model = "mock"\n\n[providers.mock]\ntype = "openai"\napi_key = "sk-mock"\n'
            'base_url = "http://127.0.0.1:%d/v1"\n\n[models.mock]\nprovider = "mock"\nmodel = "mock-1"\n'
            'max_context_size = 100000\n' % server.server_address[1])

proc = subprocess.Popen([KIMI, "acp"], cwd=WORK, env=dict(os.environ, KIMI_CODE_HOME=HOME),
                        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                        text=True, encoding="utf-8", errors="replace", bufsize=1)
LOG = os.path.join(ROOT, "acp.jsonl")
log = open(LOG, "w", encoding="utf-8")
t0 = time.time()
state = {"sid": None, "done": threading.Event(), "launch": None, "frames_after_turn": []}
next_id = [0]


def send(obj):
    log.write("%.1f >>> %s\n" % (time.time() - t0, json.dumps(obj, ensure_ascii=False))); log.flush()
    proc.stdin.write(json.dumps(obj) + "\n"); proc.stdin.flush()


def request(method, params):
    next_id[0] += 1
    send({"jsonrpc": "2.0", "id": next_id[0], "method": method, "params": params})


def reader():
    for line in proc.stdout:
        line = line.strip()
        if not line:
            continue
        log.write("%.1f <<< %s\n" % (time.time() - t0, line)); log.flush()
        try:
            msg = json.loads(line)
        except Exception:
            continue
        if msg.get("method") == "session/request_permission" and "id" in msg:
            opts = (msg.get("params") or {}).get("options") or []
            pick = next((o.get("optionId") for o in opts if str(o.get("kind", "")).startswith("allow")), None)
            send({"jsonrpc": "2.0", "id": msg["id"], "result": {"outcome": {"outcome": "selected", "optionId": pick}}})
        result = msg.get("result")
        if isinstance(result, dict) and "sessionId" in result:
            state["sid"] = result["sessionId"]
        update = (msg.get("params") or {}).get("update") or {}
        if update.get("sessionUpdate") == "tool_call_update" and update.get("status") == "completed":
            state["launch"] = update.get("rawOutput")
        if state["done"].is_set() and update.get("sessionUpdate") not in (None, "usage_update"):
            state["frames_after_turn"].append(update.get("sessionUpdate"))
        if isinstance(result, dict) and "stopReason" in result:
            state["done"].set()


threading.Thread(target=reader, daemon=True).start()
version = subprocess.run([KIMI, "--version"], capture_output=True, text=True).stdout.strip()
print("kimi", version)
request("initialize", {"protocolVersion": 1, "clientCapabilities": {"fs": {"readTextFile": False, "writeTextFile": False}}})
time.sleep(2)
request("session/new", {"cwd": WORK, "mcpServers": []})
time.sleep(3)
if not state["sid"]:
    print("FATAL: no session"); proc.kill(); sys.exit(1)
request("session/prompt", {"sessionId": state["sid"], "prompt": [{"type": "text", "text": "start the background task"}]})
state["done"].wait(60)

m = re.search(r"(?m)^task_id:[ \t]*(\S+)", state["launch"] or "")
task_id = m.group(1) if m else None
session_dir = None
try:
    for line in open(os.path.join(HOME, "session_index.jsonl"), encoding="utf-8"):
        entry = json.loads(line)
        if entry.get("sessionId") == state["sid"]:
            session_dir = entry["sessionDir"]
except FileNotFoundError:
    pass
record = os.path.join(session_dir or "", "agents", "main", "tasks", "%s.json" % task_id)


def status():
    try:
        return json.load(open(record)).get("status")
    except Exception as e:
        return "unreadable (%s)" % type(e).__name__


before = status()
time.sleep(TASK_SECONDS + 6)
after = status()
log.close(); proc.kill()

checks = [
    ("launch output carries task_id", task_id is not None, repr((state["launch"] or "")[:60])),
    ("task record says running right after launch", before == "running", "%s -> %s" % (record, before)),
    ("task record goes terminal when the task ends", after in ("completed", "failed", "timed_out", "killed", "lost"), after),
    ("ACP stays silent about the completion", not state["frames_after_turn"], state["frames_after_turn"]),
]
for name, ok, detail in checks:
    print("%s  %s  [%s]" % ("PASS" if ok else "FAIL", name, detail))
print("frames:", LOG)
sys.exit(0 if all(ok for _, ok, _ in checks) else 1)
