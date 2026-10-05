#!/usr/bin/env python3
"""Scripted local model for driving the REAL kimi / dsh CLIs without an account or any spend.

Used by the daemon's ACP LiveITs (KimiBackendLiveIT, DshBackendLiveIT): the CLI under test is real — its
ACP server, session store, tools, approvals and sub-agents all run — only the model is scripted, so the
protocol behaviour the daemon depends on can be exercised deterministically.

Serves OpenAI chat-completions (kimi: an `openai` provider with base_url http://127.0.0.1:<port>/v1) and
Anthropic Messages (dsh: DEEPSEEK_BASE_URL=http://127.0.0.1:<port>, any DEEPSEEK_API_KEY), both streamed.

Usage:
    python3 scripts/acp-mock-model.py --port 18931 [--log /tmp/mock-requests.log]

Behaviour is chosen by trigger words in the user text since the last assistant message:
  PONG      -> text "pong"
  SLOW      -> text streamed slowly (~12 s), for queue / cancel tests
  BASHME    -> a shell tool call `echo approved-run` that asks for approval (kimi asks for every Bash call;
               for dsh the call requests a sandbox escalation, the only thing dsh asks about)
  BGBASH    -> a Bash call with run_in_background=true (kimi)
  AGENTME   -> kimi Agent tool call (foreground sub-agent) whose prompt is "SUBTASK"
  BGAGENT   -> kimi Agent tool call with run_in_background=true, prompt "SUBBASH then SUBTASK"
  SUBBASH   -> (inside a sub-agent) a Bash call, so the sub-agent raises a permission request
  SUBTASK   -> (inside a sub-agent) text "sub-result mango"
  "Reply with exactly: X" -> text X
  anything else -> text "ok"
A request whose last message is a tool result answers "done".
"""
import argparse, json, os, re, sys, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOG = None
lock = threading.Lock()
counter = [0]


def log(s):
    if LOG is None:
        return
    with lock:
        LOG.write("%.3f %s\n" % (time.time(), s)); LOG.flush()


def text_of(content):
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        out = []
        for b in content:
            if isinstance(b, dict):
                if b.get("type") == "text":
                    out.append(b.get("text", ""))
                elif b.get("type") == "tool_result":
                    out.append("<<TOOL_RESULT>>")
        return "\n".join(out)
    return ""


def decide(last_text, last_is_tool, tool_names):
    if last_is_tool:
        return ("text", "done", 0)
    t = last_text
    for trig in ("BGAGENT", "AGENTME", "BGBASH", "BASHME", "SUBBASH", "SUBTASK", "SLOW", "PONG"):
        if trig in t:
            break
    else:
        trig = None
    shell = next((n for n in tool_names if n.lower() in ("bash", "shell", "exec", "run_shell", "execute")), None) \
        or next((n for n in tool_names if "bash" in n.lower() or "shell" in n.lower() or "exec" in n.lower()), None)
    if trig == "SUBTASK":
        return ("text", "sub-result mango", 0)
    if trig == "SUBBASH":
        return ("tool", (shell or "Bash", {"command": "echo sub-bash-ran", "description": "sub bash"}), 0)
    if trig == "AGENTME":
        return ("tool", ("Agent", {"prompt": "SUBTASK: report the secret word", "description": "probe sub-agent"}), 0)
    if trig == "BGAGENT":
        return ("tool", ("Agent", {"prompt": "SUBBASH then SUBTASK", "description": "probe bg sub-agent",
                                   "run_in_background": True}), 0)
    if trig == "BGBASH":
        return ("tool", (shell or "Bash", {"command": "sleep 3; echo bg-done", "run_in_background": True,
                                           "description": "probe bg task"}), 0)
    if trig == "BASHME":
        args = {"command": "echo approved-run", "description": "probe echo"}
        if shell == "bash":  # dsh: only an escalation asks the user (workspace-write runs plain commands)
            args.update({"sandbox_permissions": "danger-full-access", "justification": "probe approval"})
        return ("tool", (shell or "Bash", args), 0)
    if trig == "SLOW":
        return ("text", "slow " * 12, 1.0)
    if trig == "PONG":
        return ("text", "pong", 0)
    m = re.search(r"Reply with exactly: ([A-Za-z0-9_-]+)", t)
    if m:
        return ("text", m.group(1), 0)
    return ("text", "ok", 0)


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _json(self, code, obj):
        b = json.dumps(obj).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)

    def do_GET(self):
        log("GET " + self.path)
        self._json(200, {"object": "list", "data": [{"id": "mock-1", "object": "model"}]})

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(n) or b"{}")
        counter[0] += 1
        rid = counter[0]
        msgs = body.get("messages", [])
        tools = body.get("tools") or []
        names = [t.get("name") or (t.get("function") or {}).get("name") for t in tools]
        if self.path.endswith("/chat/completions"):
            last = msgs[-1] if msgs else {}
            last_is_tool = last.get("role") == "tool"
            tail = []
            for m in reversed(msgs):
                if m.get("role") == "assistant":
                    break
                if m.get("role") == "user":
                    tail.append(text_of(m.get("content")))
            act = decide("\n".join(tail), last_is_tool, names)
            log("REQ#%d openai model=%s stream=%s nmsg=%d last_role=%s tools=%s -> %s" % (
                rid, body.get("model"), body.get("stream"), len(msgs), last.get("role"), names, json.dumps(act)[:200]))
            return self.openai(act, body)
        if self.path.endswith("/messages"):
            last = msgs[-1] if msgs else {}
            lc = last.get("content")
            last_is_tool = last.get("role") == "user" and isinstance(lc, list) and any(
                isinstance(b, dict) and b.get("type") == "tool_result" for b in lc)
            last_user_text = text_of(lc) if last.get("role") == "user" else ""
            act = decide(last_user_text, last_is_tool, names)
            log("REQ#%d anthropic model=%s effort=%s stream=%s nmsg=%d tools=%s -> %s" % (
                rid, body.get("model"), json.dumps(body.get("output_config") or body.get("thinking")), body.get("stream"), len(msgs), names, json.dumps(act)[:200]))
            return self.anthropic(act, body)
        log("POST unknown " + self.path)
        self._json(404, {"error": {"message": "not found"}})

    # ---- OpenAI ----
    def _sse_start(self):
        self.send_response(200); self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache"); self.send_header("Connection", "close"); self.end_headers()
        self.close_connection = True

    def openai(self, act, body):
        def chunk(delta, finish=None, usage=None):
            c = {"id": "c1", "object": "chat.completion.chunk", "created": int(time.time()), "model": "mock-1",
                 "choices": [{"index": 0, "delta": delta, "finish_reason": finish}]}
            if usage:
                c["usage"] = usage
            return c
        self._sse_start()
        usage = {"prompt_tokens": 1200, "completion_tokens": 5, "total_tokens": 1205}
        try:
            if act[0] == "text":
                words = act[1].split(" ") if act[2] else [act[1]]
                self.wfile.write(("data: " + json.dumps(chunk({"role": "assistant", "content": ""})) + "\n\n").encode())
                for w in words:
                    if not w:
                        continue
                    if act[2]:
                        time.sleep(act[2])
                    self.wfile.write(("data: " + json.dumps(chunk({"content": w + " " if act[2] else w})) + "\n\n").encode())
                    self.wfile.flush()
                self.wfile.write(("data: " + json.dumps(chunk({}, "stop", usage)) + "\n\n").encode())
            else:
                name, args = act[1]
                self.wfile.write(("data: " + json.dumps(chunk({"role": "assistant", "content": None, "tool_calls": [
                    {"index": 0, "id": "call_%d" % counter[0], "type": "function",
                     "function": {"name": name, "arguments": json.dumps(args)}}]})) + "\n\n").encode())
                self.wfile.write(("data: " + json.dumps(chunk({}, "tool_calls", usage)) + "\n\n").encode())
            self.wfile.write(b"data: [DONE]\n\n"); self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            log("client hung up")

    # ---- Anthropic ----
    def anthropic(self, act, body):
        model = body.get("model", "mock")
        stop = "tool_use" if act[0] == "tool" else "end_turn"
        if not body.get("stream"):
            content = [{"type": "text", "text": act[1]}] if act[0] == "text" else \
                [{"type": "tool_use", "id": "toolu_%d" % counter[0], "name": act[1][0], "input": act[1][1]}]
            return self._json(200, {"id": "msg_%d" % counter[0], "type": "message", "role": "assistant",
                                    "model": model, "content": content, "stop_reason": stop,
                                    "stop_sequence": None, "usage": {"input_tokens": 1200, "output_tokens": 5}})
        self._sse_start()

        def ev(t, d):
            self.wfile.write(("event: %s\ndata: %s\n\n" % (t, json.dumps(d))).encode()); self.wfile.flush()
        try:
            ev("message_start", {"type": "message_start", "message": {
                "id": "msg_%d" % counter[0], "type": "message", "role": "assistant", "model": model, "content": [],
                "stop_reason": None, "stop_sequence": None,
                "usage": {"input_tokens": 1200, "output_tokens": 1, "cache_read_input_tokens": 0,
                          "cache_creation_input_tokens": 0}}})
            if act[0] == "text":
                ev("content_block_start", {"type": "content_block_start", "index": 0,
                                           "content_block": {"type": "text", "text": ""}})
                words = act[1].split(" ") if act[2] else [act[1]]
                for w in words:
                    if not w:
                        continue
                    if act[2]:
                        time.sleep(act[2])
                    ev("content_block_delta", {"type": "content_block_delta", "index": 0,
                                               "delta": {"type": "text_delta", "text": w + " " if act[2] else w}})
            else:
                name, args = act[1]
                ev("content_block_start", {"type": "content_block_start", "index": 0, "content_block": {
                    "type": "tool_use", "id": "toolu_%d" % counter[0], "name": name, "input": {}}})
                ev("content_block_delta", {"type": "content_block_delta", "index": 0,
                                           "delta": {"type": "input_json_delta", "partial_json": json.dumps(args)}})
            ev("content_block_stop", {"type": "content_block_stop", "index": 0})
            ev("message_delta", {"type": "message_delta", "delta": {"stop_reason": stop, "stop_sequence": None},
                                 "usage": {"output_tokens": 5}})
            ev("message_stop", {"type": "message_stop"})
        except (BrokenPipeError, ConnectionResetError):
            log("client hung up")


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description="scripted local model for the ACP LiveITs")
    ap.add_argument("--port", type=int, default=18931)
    ap.add_argument("--log", help="append one line per model request here")
    args = ap.parse_args()
    if args.log:
        LOG = open(args.log, "a")
    s = ThreadingHTTPServer(("127.0.0.1", args.port), H)
    print("acp-mock-model listening on 127.0.0.1:%d" % s.server_address[1], flush=True)
    log("listening %d" % s.server_address[1])
    s.serve_forever()
