#!/usr/bin/env python3
"""Probe the Kimi Code CLI's ACP interface (`kimi acp`) end-to-end and record every frame.

Covers the behaviors the cc-pocket daemon depends on (see docs/design/kimi-backend-design.md §9,
2026-08-08 results): initialize handshake, session/new, tool_call / tool_call_update streaming shape,
permission requests (auto-approved), mid-turn prompt rejection (-32600 turn.agent_busy), session/cancel.

Usage:
    py -3 scripts/probe-kimi-acp.py [workdir]          # Windows
    python3 scripts/probe-kimi-acp.py [workdir]        # macOS/Linux

Writes acp-probe-<ts>.jsonl next to itself's CWD workdir (default: a new temp dir) and prints the
probe session's on-disk wire.jsonl path at the end (replay-format evidence). Requires a logged-in CLI.
Run this after EVERY kimi CLI upgrade (auto-update is on by default) — protocol drift lands here first.
"""
import json, os, subprocess, sys, tempfile, threading, time

KIMI = os.environ.get("CC_POCKET_KIMI_BIN") or os.path.expanduser("~/.kimi-code/bin/kimi")
if os.name == "nt" and not KIMI.endswith(".exe"):
    KIMI += ".exe"
CWD = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else tempfile.mkdtemp(prefix="kimi-acp-probe-")
LOG = os.path.join(CWD, "acp-probe-%d.jsonl" % time.time())

proc = subprocess.Popen(
    [KIMI, "acp"], cwd=CWD,
    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
    text=True, encoding="utf-8", errors="replace", bufsize=1,
)

next_id = 0
log = open(LOG, "w", encoding="utf-8")
state = {"prompt_done": threading.Event(), "first_update_seen": threading.Event()}


def send(obj):
    line = json.dumps(obj, ensure_ascii=False)
    log.write(">>> " + line + "\n"); log.flush()
    proc.stdin.write(line + "\n"); proc.stdin.flush()


def request(method, params):
    global next_id
    next_id += 1
    send({"jsonrpc": "2.0", "id": next_id, "method": method, "params": params})
    return next_id


def notify(method, params):
    send({"jsonrpc": "2.0", "method": method, "params": params})


def reader():
    for line in proc.stdout:
        line = line.strip()
        if not line:
            continue
        log.write("<<< " + line + "\n"); log.flush()
        try:
            msg = json.loads(line)
        except Exception:
            continue
        # auto-approve permission requests with the first allow* option, so tool calls proceed
        if msg.get("method") == "session/request_permission" and "id" in msg:
            opts = (msg.get("params") or {}).get("options") or []
            pick = next((o.get("optionId") for o in opts if str(o.get("kind", "")).startswith("allow")), None)
            outcome = {"outcome": "selected", "optionId": pick} if pick else {"outcome": "cancelled"}
            send({"jsonrpc": "2.0", "id": msg["id"], "result": {"outcome": outcome}})
        if msg.get("method") == "session/update":
            state["first_update_seen"].set()
        if "id" in msg and isinstance(msg.get("result"), dict) and "stopReason" in msg["result"]:
            state["prompt_done"].set()


threading.Thread(target=reader, daemon=True).start()

request("initialize", {"protocolVersion": 1, "clientCapabilities": {"fs": {"readTextFile": False, "writeTextFile": False}}})
time.sleep(2)
request("session/new", {"cwd": CWD, "mcpServers": []})
time.sleep(3)

sid = None
log.flush()
for l in open(LOG, encoding="utf-8"):
    if l.startswith("<<<") and '"sessionId"' in l:
        try:
            sid = json.loads(l[4:])["result"]["sessionId"]
        except Exception:
            pass
print("sessionId:", sid)
if not sid:
    print("FATAL: no session (not logged in? run `kimi` and /login first)")
    log.close(); proc.kill(); sys.exit(1)

# phase 1: a tool-using turn (Bash + Read) — captures tool_call/tool_call_update shapes
state["prompt_done"].clear()
request("session/prompt", {"sessionId": sid, "prompt": [{"type": "text", "text":
    "Please do exactly two tool calls in order: 1) use Bash to run: echo hello-from-probe ; "
    "2) use Read to read the first 3 lines of README.md in the current directory (if missing, just say so). "
    "Tell me what you did at each step."}]})
print("phase1 (tools) settled:", state["prompt_done"].wait(timeout=180))

# phase 2: long turn + mid-turn prompt (expect -32600) + session/cancel (expect stopReason=cancelled)
state["prompt_done"].clear(); state["first_update_seen"].clear()
request("session/prompt", {"sessionId": sid, "prompt": [{"type": "text", "text":
    "Write a file long.md in the current directory: 400 lines of markdown, each line a different "
    "sentence. Do not stop to summarize until finished."}]})
state["first_update_seen"].wait(timeout=60)
time.sleep(3)
request("session/prompt", {"sessionId": sid, "prompt": [{"type": "text", "text": "mid-turn probe message"}]})
time.sleep(3)
notify("session/cancel", {"sessionId": sid})
print("phase2 (cancel) settled:", state["prompt_done"].wait(timeout=60))
time.sleep(2)
log.close()
proc.kill()
print("probe log:", LOG)

# locate the on-disk transcript of THIS probe session for replay-format checks
index = os.path.expanduser("~/.kimi-code/session_index.jsonl")
try:
    for l in open(index, encoding="utf-8"):
        e = json.loads(l)
        if e.get("sessionId") == sid:
            print("wire.jsonl:", os.path.join(e["sessionDir"], "agents", "main", "wire.jsonl"))
            break
except FileNotFoundError:
    pass
