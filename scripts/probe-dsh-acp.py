#!/usr/bin/env python3
"""Probe the DeepSeek Harness ACP profile (`dsh --profile acp`) end-to-end and record every frame.

WHY THIS EXISTS: dsh 0.1.2-rc.1 replaced the `web` profile's local API (`dsh-host-apiproxy`:
`POST /api/session.prompt` + downlink WS `/api/events.mux`, no auth beyond the Host/Origin fence) with
the Typert gateway (`POST /api/<ns>/<method>` + bidirectional WS `/api/remote.mux`, and EVERY /api
request now needs the signed browser cookie minted by `GET /?token=<launchToken>`). The daemon's old
`DshApiClient` therefore cannot connect at all — the phone sees "could not reach the dsh local API on
127.0.0.1:<port> after 20 attempts". The same release ships a STANDARD ACP v1 stdio profile, which is
what the daemon speaks to now.

Covers the behaviors the cc-pocket daemon depends on:
  1. `initialize` handshake + advertised agent capabilities,
  2. `session/new` → session id and its configuration-option state (model / reasoning_effort),
  3. a plain turn's `session/update` vocabulary and its `stopReason`,
  4. a tool-using turn: tool_call / tool_call_update shapes and `session/request_permission`,
  5. a SECOND prompt sent mid-turn — dsh allows one in-flight prompt per session, so the exact refusal
     shape decides whether the daemon has to own a FIFO queue (it does for kimi, see KimiBackend),
  6. `session/cancel` → the cancelled stopReason,
  7. `session/set_config_option` → the model/effort switch and its read-back,
  8. `session/list` + `session/resume` across a process restart,
  9. the on-disk transcript of this probe session, so the JSONL replay parsers can be re-verified.

Usage:
    python3 scripts/probe-dsh-acp.py [workdir]

Writes dsh-acp-probe-<ts>.jsonl in the workdir (default: a fresh temp dir) and prints a summary.
Requires dsh >= 0.1.2-rc.1 and DeepSeek credentials ($DEEPSEEK_API_KEY or ~/.dsh/.credentials.yaml).
Run this after EVERY dsh upgrade — protocol drift lands here first.
"""
import json, os, subprocess, sys, tempfile, threading, time

DSH = os.environ.get("CC_POCKET_DSH_BIN") or "dsh"
CWD = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else tempfile.mkdtemp(prefix="dsh-acp-probe-")
os.makedirs(CWD, exist_ok=True)
LOG = os.path.join(CWD, "dsh-acp-probe-%d.jsonl" % time.time())
log = open(LOG, "w", encoding="utf-8")


class Conn:
    """One `dsh --profile acp` process speaking newline-delimited JSON-RPC on stdio."""

    def __init__(self, tag):
        self.tag = tag
        self.next_id = 0
        self.results = {}            # request id → result/error payload
        self.events = []             # every inbound frame, in arrival order
        self.updates = []            # session/update `update` objects only
        self.settled = threading.Event()
        self.stop_reason = None
        self.errfile = open(os.path.join(CWD, "stderr-%s.log" % tag), "w", encoding="utf-8")
        self.proc = subprocess.Popen(
            [DSH, "--profile", "acp"], cwd=CWD,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.errfile,
            text=True, encoding="utf-8", errors="replace", bufsize=1,
        )
        threading.Thread(target=self._reader, daemon=True).start()

    # ---- wire ----
    def _send(self, obj):
        line = json.dumps(obj, ensure_ascii=False)
        log.write(">>> [%s] %s\n" % (self.tag, line)); log.flush()
        self.proc.stdin.write(line + "\n"); self.proc.stdin.flush()

    def request(self, method, params):
        self.next_id += 1
        self._send({"jsonrpc": "2.0", "id": self.next_id, "method": method, "params": params})
        return self.next_id

    def notify(self, method, params):
        self._send({"jsonrpc": "2.0", "method": method, "params": params})

    def call(self, method, params, timeout=60):
        """Request + wait for its response. Returns the whole JSON-RPC message (result OR error)."""
        rid = self.request(method, params)
        deadline = time.time() + timeout
        while time.time() < deadline:
            if rid in self.results:
                return self.results[rid]
            time.sleep(0.05)
        return {"error": {"message": "TIMEOUT after %ss" % timeout}}

    def _reader(self):
        for line in self.proc.stdout:
            line = line.strip()
            if not line:
                continue
            log.write("<<< [%s] %s\n" % (self.tag, line)); log.flush()
            try:
                msg = json.loads(line)
            except Exception:
                continue
            self.events.append(msg)
            method = msg.get("method")
            # server→client request: auto-answer permission prompts with the first allow option
            if method == "session/request_permission" and "id" in msg:
                opts = (msg.get("params") or {}).get("options") or []
                pick = next((o.get("optionId") for o in opts if str(o.get("kind", "")).startswith("allow")), None)
                outcome = {"outcome": "selected", "optionId": pick} if pick else {"outcome": "cancelled"}
                self._send({"jsonrpc": "2.0", "id": msg["id"], "result": {"outcome": outcome}})
                continue
            if method == "session/update":
                self.updates.append((msg.get("params") or {}).get("update") or {})
                continue
            if "id" in msg and method is None:
                self.results[msg["id"]] = msg
                result = msg.get("result")
                if isinstance(result, dict) and "stopReason" in result:
                    self.stop_reason = result["stopReason"]
                    self.settled.set()

    def kill(self):
        try:
            self.proc.stdin.close()
        except Exception:
            pass
        try:
            self.proc.wait(timeout=10)
        except Exception:
            self.proc.kill()
        self.errfile.close()

    # ---- helpers ----
    def update_kinds(self):
        seen = []
        for u in self.updates:
            kind = u.get("sessionUpdate")
            if kind not in seen:
                seen.append(kind)
        return seen


def show(title, obj):
    print("\n=== %s\n%s" % (title, json.dumps(obj, ensure_ascii=False, indent=2)[:4000]))


findings = {}

# ── phase 1: handshake ─────────────────────────────────────────────────────
a = Conn("main")
init = a.call("initialize", {
    "protocolVersion": 1,
    "clientCapabilities": {"fs": {"readTextFile": False, "writeTextFile": False}},
}, timeout=120)
show("1. initialize", init)
findings["initialize"] = init.get("result")
if init.get("result") is None:
    print("FATAL: no initialize response — is dsh >= 0.1.2-rc.1 installed and on PATH?")
    a.kill(); log.close(); sys.exit(1)

# ── phase 2: session/new ───────────────────────────────────────────────────
new = a.call("session/new", {"cwd": CWD, "mcpServers": []}, timeout=120)
show("2. session/new", new)
sid = (new.get("result") or {}).get("sessionId")
findings["session/new"] = new.get("result")
if not sid:
    print("FATAL: no sessionId (credentials missing? check ~/.dsh/.credentials.yaml)")
    a.kill(); log.close(); sys.exit(1)

# ── phase 3: a plain turn ──────────────────────────────────────────────────
a.settled.clear(); a.updates.clear()
r = a.call("session/prompt", {
    "sessionId": sid,
    "prompt": [{"type": "text", "text": "Reply with exactly: hello-from-probe. Do not use any tool."}],
}, timeout=180)
show("3. plain turn result", r)
findings["plain_turn"] = {"stopReason": (r.get("result") or {}).get("stopReason"),
                          "update_kinds": a.update_kinds(),
                          "first_updates": a.updates[:6]}
show("3b. plain turn update kinds", findings["plain_turn"])

# ── phase 4: a tool-using turn (tool_call shapes + permission prompt) ──────
a.updates.clear()
r = a.call("session/prompt", {
    "sessionId": sid,
    "prompt": [{"type": "text", "text":
                "Do exactly two things with tools: 1) run the shell command `echo hello-from-probe`; "
                "2) write a file probe.txt containing the word ok. Then say done."}],
}, timeout=300)
tool_updates = [u for u in a.updates if str(u.get("sessionUpdate", "")).startswith("tool_call")]
findings["tool_turn"] = {"stopReason": (r.get("result") or {}).get("stopReason"),
                         "update_kinds": a.update_kinds(),
                         "tool_updates_sample": tool_updates[:4],
                         "permission_requests": [e for e in a.events
                                                 if e.get("method") == "session/request_permission"][:2]}
show("4. tool turn", findings["tool_turn"])

# ── phase 5: mid-turn prompt + 6: cancel ───────────────────────────────────
a.updates.clear(); a.settled.clear()
long_id = a.request("session/prompt", {
    "sessionId": sid,
    "prompt": [{"type": "text", "text":
                "Write a file long.md with 300 lines of markdown, one distinct sentence per line. "
                "Do not stop to summarize until it is finished."}],
})
time.sleep(8)  # let the turn actually start
mid = a.call("session/prompt", {
    "sessionId": sid,
    "prompt": [{"type": "text", "text": "mid-turn probe message"}],
}, timeout=30)
show("5. mid-turn second prompt (expect a refusal)", mid)
findings["mid_turn_prompt"] = mid

a.notify("session/cancel", {"sessionId": sid})
deadline = time.time() + 90
while time.time() < deadline and long_id not in a.results:
    time.sleep(0.1)
show("6. cancelled turn result", a.results.get(long_id, {"error": "TIMEOUT"}))
findings["cancel"] = a.results.get(long_id)

# ── phase 7: configuration options (model / reasoning effort) ─────────────
opts = (new.get("result") or {}).get("configOptions") or (new.get("result") or {}).get("config") or []
findings["config_options_at_new"] = opts
option_id, option_value = None, None
for opt in opts if isinstance(opts, list) else []:
    if opt.get("id") in ("model", "reasoning_effort") and opt.get("availableValues"):
        values = [v.get("id") if isinstance(v, dict) else v for v in opt["availableValues"]]
        current = opt.get("currentValue")
        pick = next((v for v in values if v != current), None)
        if pick:
            option_id, option_value = opt["id"], pick
            break
if option_id:
    r = a.call("session/set_config_option",
               {"sessionId": sid, "optionId": option_id, "value": option_value}, timeout=60)
    show("7. set_config_option(%s=%s)" % (option_id, option_value), r)
    findings["set_config_option"] = r
else:
    print("\n=== 7. set_config_option SKIPPED — no switchable option advertised at session/new")

# ── phase 8: list + resume across a process restart ───────────────────────
listed = a.call("session/list", {"cwd": CWD}, timeout=60)
show("8. session/list", listed)
findings["session/list"] = listed.get("result")
a.call("session/close", {"sessionId": sid}, timeout=30)
a.kill()

b = Conn("resume")
b.call("initialize", {"protocolVersion": 1,
                      "clientCapabilities": {"fs": {"readTextFile": False, "writeTextFile": False}}},
       timeout=120)
res = b.call("session/resume", {"sessionId": sid, "cwd": CWD, "mcpServers": []}, timeout=120)
show("8b. session/resume (fresh process)", res)
findings["session/resume"] = res
findings["resume_replayed_updates"] = len(b.updates)
print("updates emitted during resume (expect 0 — no history replay):", len(b.updates))
b.kill()

# ── phase 9: the on-disk transcript of this session ───────────────────────
home = os.environ.get("DSH_HOME") or os.path.expanduser("~/.dsh")
store = os.path.join(home, "sessions")
hit = None
for root, _dirs, files in os.walk(store):
    for f in files:
        if not (f.startswith("session.jsonl") or f.endswith(".jsonl") or f.endswith(".zstd")):
            continue
        path = os.path.join(root, f)
        if os.path.getmtime(path) > time.time() - 3600 and sid.replace("/", "-")[:8] in root:
            hit = path
if hit is None:  # fall back to the newest file under the store
    candidates = [os.path.join(r, f) for r, _d, fs in os.walk(store) for f in fs]
    hit = max(candidates, key=os.path.getmtime) if candidates else None
print("\n=== 9. on-disk transcript:", hit)
findings["transcript_path"] = hit

summary = os.path.join(CWD, "findings.json")
with open(summary, "w", encoding="utf-8") as f:
    json.dump(findings, f, ensure_ascii=False, indent=2)
log.close()
print("\nprobe log:", LOG)
print("findings :", summary)
print("workdir  :", CWD)
