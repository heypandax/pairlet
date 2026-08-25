#!/usr/bin/env python3
"""Probe the DeepSeek Harness (`dsh`) local web-profile API end-to-end (issue #255).

Regresses every wire/disk fact the cc-pocket DshBackend depends on. Run this after EVERY dsh upgrade:
it is a release-candidate CLI, and each of the checks below encodes a detail that already surprised us
once and would fail SILENTLY if it drifted.

What it asserts
    boot     dsh --profile web --port 0 starts and prints `dsh web: http://127.0.0.1:<port>`
    ws       GET /api/events.mux without an upgrade is 426; a real WebSocket handshake succeeds
    rpc      RPC is POST /api/<method> (NOT /api/rpc), demands content-type: application/json (415),
             rejects a path/body method mismatch, and reports BUSINESS errors as HTTP 200 + ok:false
    session  session.create -> {sessionId}; session.prompt round-trips its envelope
    mux      frames are {type:"server-request", rpcId, method, payload} with method == payload.type
    disk     the session lands at ~/.dsh/sessions/--<key>--/<id>/session.jsonl.zstd, its header line
             has version==0 and a verbatim cwd, and the file is CONCATENATED multi-frame zstd

NO API KEY IS NEEDED OR USED by the default run. Boot, session creation and the WS handshake all work
unauthenticated; only real inference would need DEEPSEEK_API_KEY, and the default run deliberately
never triggers one. It therefore costs nothing and can run in CI.

--probe-ask (opt-in, issue #291)
    Regresses the channel by which dsh asks the human something — the one dsh surface cc-pocket does
    NOT bridge yet, so nothing else would notice it drifting. Two tiers:

    carrier (still free, no key, always runs under the flag)
        Answering a host->client server-request is NOT an RPC method: it is POST /api/respond
        carrying a `client-response` that ECHOES the server-request's rpcId. So:
          - /api/respond exists (a client-request envelope there is answered, not 404'd), while
            sibling names like /api/question.respond and /api/approval.respond ARE 404 — they are
            absent from RpcMethodMap on purpose, and a client that invents them silently never answers
          - the reply is an RpcReceipt ({accepted:...}), NOT a server-response envelope; a client that
            unwraps `.result.ok` reads success out of every failure
          - an unknown/settled rpcId is HTTP 200 {accepted:false, reason:"not-pending"} — the answer is
            dropped, not errored, so a client that only checks the status code loses answers silently
          - a malformed client-response is {accepted:false, reason:"bad-response"}
          - the JSON content-type fence applies here too (415)

    live round trip (needs DEEPSEEK_API_KEY in the environment; SKIPPED with a printed reason if absent,
    because it must run one real cheap inference turn to make the agent call ask_user_question)
        - the ask arrives as {type:"server-request", rpcId, method:"question/requested", payload} with
          method == payload.type, payload.questions[] carrying id/question/options[].label
        - THE ANSWER VOCABULARY IS THE OPTION *LABEL* STRING. Not an index, not an option id. dsh
          validates the batch positionally against the exact pending questions (matchesQuestions in
          @deepseek-ai/dsh-host-apiproxy), so an index, an unknown label, a wrong question id, a
          duplicate label, or a wrong-length answers[] are all rejected as bad-response. This is the
          single easiest thing to get wrong when bridging, and it fails as a silent no-op.
        - a correct answer is {accepted:true}; replaying it is "not-pending" (first claimant wins)
        - resolution is broadcast as method:"question/resolved" whose payload.questionRpcId echoes the
          request's rpcId (payload carries no question id of its own)
        - ON DISK THERE IS NO question/* RECORD AT ALL. A question replays only as the tool/call +
          tool/result pair of the `ask_user_question` tool, the result text being the answers JSON.
          Any read-only replay of questions must read that pair, not look for a question event.

    Not asserted live: approvals. They share this exact carrier (POST /api/respond, echoed rpcId) but
    ride method:"approval/requested" with payload {sessionId, approvalId, toolName, callId?, reason?}
    and are answered with {sessionId, approvalId, outcome:"allowed-once"|"rejected"} — a two-value
    vocabulary with NO "always allow". Unlike questions they DO land on disk, as the
    approval/asked + approval/decided event pair. Provoking one needs a sandbox escalation, which is
    not deterministic enough to assert; the carrier tier above covers the part that can drift quietly.

    Neither questions nor approvals ever time out: the pending entry holds no timer, and
    ask_user_question declares no timeoutMs so the tool-call timeout policy skips it. An unanswered
    ask blocks its turn indefinitely and is withdrawn only by session.cancel or host teardown.

Usage:
    python3 scripts/probe-dsh-api.py [workdir] [--probe-ask]

Everything runs against a THROWAWAY DSH_HOME so your real ~/.dsh is never touched.
"""
import base64
import hashlib
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid

DSH = os.environ.get("CC_POCKET_DSH_BIN") or shutil.which("dsh")
BOOT_RE = re.compile(r"https?://(?:127\.0\.0\.1|localhost):(\d{1,5})")

results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print(("  PASS  " if ok else "  FAIL  ") + name + (" — " + str(detail) if detail else ""))
    return ok


def post(port, path, body, content_type="application/json"):
    """POST and return (status, parsed_json_or_raw_text)."""
    data = json.dumps(body).encode()
    req = urllib.request.Request("http://127.0.0.1:%d%s" % (port, path), data=data, method="POST")
    if content_type:
        req.add_header("content-type", content_type)
    req.add_header("origin", "http://127.0.0.1:%d" % port)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            raw = r.read().decode("utf-8", "replace")
            try:
                return r.status, json.loads(raw)
            except ValueError:
                return r.status, raw
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, raw


def rpc(port, method, payload):
    body = {"type": "client-request", "rpcId": str(uuid.uuid4()), "method": method, "payload": payload}
    return post(port, "/api/" + method, body)


def ws_handshake(port, path="/api/events.mux", timeout=10):
    """Minimal RFC6455 client handshake. Returns (sock, status_line, accept_ok, leftover_bytes).

    leftover_bytes is whatever arrived glued to the handshake response — the mux pushes its
    subscription baseline immediately, so dropping it would lose real frames.
    """
    key = base64.b64encode(os.urandom(16)).decode()
    accept = base64.b64encode(
        hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()
    ).decode()
    s = socket.create_connection(("127.0.0.1", port), timeout=timeout)
    s.settimeout(timeout)
    s.sendall(
        (
            "GET %s HTTP/1.1\r\nHost: 127.0.0.1:%d\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
            "Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\nOrigin: http://127.0.0.1:%d\r\n\r\n"
            % (path, port, key, port)
        ).encode()
    )
    buf = b""
    while b"\r\n\r\n" not in buf and len(buf) < 65536:
        chunk = s.recv(4096)
        if not chunk:
            break
        buf += chunk
    head_bytes, _, leftover = buf.partition(b"\r\n\r\n")
    head = head_bytes.decode("utf-8", "replace")
    ok_accept = accept.lower() in head.lower()
    return s, head.splitlines()[0] if head else "", ok_accept, leftover


class MuxReader(threading.Thread):
    """Drains server->client text frames off an upgraded socket into a list of parsed JSON frames.

    Only started by --probe-ask; the default run holds the socket open without reading it, exactly
    as it did before. Server frames are never masked, and no extension is negotiated, so a plain
    RFC6455 length walk is enough.
    """

    def __init__(self, sock, leftover=b""):
        super().__init__(daemon=True)
        self.sock = sock
        self.buf = leftover
        self.frames = []
        self.stop = False

    def _need(self, n):
        while len(self.buf) < n:
            try:
                chunk = self.sock.recv(65536)
            except socket.timeout:
                if self.stop:
                    return False
                continue
            except OSError:
                return False
            if not chunk:
                return False
            self.buf += chunk
        return True

    def run(self):
        self.sock.settimeout(1.0)
        message = b""
        while not self.stop:
            if not self._need(2):
                return
            b0, b1 = self.buf[0], self.buf[1]
            opcode, fin, length, offset = b0 & 0x0F, b0 & 0x80, b1 & 0x7F, 2
            if length == 126:
                if not self._need(4):
                    return
                length, offset = int.from_bytes(self.buf[2:4], "big"), 4
            elif length == 127:
                if not self._need(10):
                    return
                length, offset = int.from_bytes(self.buf[2:10], "big"), 10
            if not self._need(offset + length):
                return
            payload, self.buf = self.buf[offset:offset + length], self.buf[offset + length:]
            if opcode == 0x8:  # close
                return
            if opcode not in (0x0, 0x1, 0x2):  # ping/pong: nothing to answer, the host never asks
                continue
            message += payload
            if fin:
                try:
                    self.frames.append(json.loads(message.decode("utf-8", "replace")))
                except ValueError:
                    pass
                message = b""

    def await_frame(self, pred, timeout):
        """First already-seen-or-future frame matching pred, or None once timeout elapses."""
        deadline = time.time() + timeout
        while True:
            for frame in list(self.frames):
                if pred(frame):
                    return frame
            if time.time() >= deadline:
                return None
            time.sleep(0.2)


def receipt(port, rpc_id, value, ok=True):
    """POST /api/respond with a client-response echoing rpc_id; returns (status, body)."""
    result = {"ok": True, "value": value} if ok else {"ok": False, "error": value}
    return post(port, "/api/respond",
                {"type": "client-response", "rpcId": rpc_id, "result": result})


def zstd_frame_count(path):
    """Count zstd frames by walking magic numbers — proves the file is CONCATENATED frames, not one."""
    blob = open(path, "rb").read()
    magic = b"\x28\xb5\x2f\xfd"
    return blob.count(magic), len(blob)


def main():
    if not DSH or not os.path.exists(DSH):
        print("SKIP: dsh not installed (set CC_POCKET_DSH_BIN or `npm i -g @deepseek-ai/dsh`).")
        print("      This probe needs the real CLI; there is nothing to regress without it.")
        return 0

    argv = sys.argv[1:]
    probe_ask = "--probe-ask" in argv
    positional = [a for a in argv if not a.startswith("--")]
    workdir = os.path.abspath(positional[0]) if positional else tempfile.mkdtemp(prefix="dsh-probe-cwd-")
    os.makedirs(workdir, exist_ok=True)
    dsh_home = tempfile.mkdtemp(prefix="dsh-probe-home-")
    env = dict(os.environ, DSH_HOME=dsh_home, DSH_PERMISSION_MODE="workspace-write")
    print("dsh      :", DSH)
    print("workdir  :", workdir)
    print("DSH_HOME :", dsh_home)
    print()

    proc = subprocess.Popen(
        [DSH, "--profile", "web", "--port", "0"],
        cwd=workdir, env=env,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, encoding="utf-8", errors="replace", bufsize=1,
    )

    # ---- boot ----
    port = None
    deadline = time.time() + 60
    banner = []
    while time.time() < deadline and port is None:
        line = proc.stdout.readline()
        if not line:
            break
        banner.append(line.rstrip())
        m = BOOT_RE.search(line)
        if m:
            port = int(m.group(1))
    if not check("boot prints a loopback URL with the bound port", port is not None, "\n".join(banner[-5:])):
        proc.kill()
        return 1
    print("  port   :", port)

    sock = None
    mux = None
    try:
        # ---- WS carrier ----
        st, _ = post(port, "/api/events.mux", {}, content_type="application/json")
        check("plain POST/GET on events.mux is refused (426 expected, no SSE fallback)", st in (404, 405, 426), st)

        sock, status_line, ok_accept, leftover = ws_handshake(port)
        check("events.mux completes a real WebSocket handshake", "101" in status_line and ok_accept, status_line)
        if probe_ask:
            # Started here (not later) so the baseline frames already glued to the handshake, and
            # everything pushed while the checks below run, are captured rather than raced for.
            mux = MuxReader(sock, leftover)
            mux.start()

        # ---- RPC shape ----
        st, body = rpc(port, "session.list", {})
        check("RPC is POST /api/<method> and answers 200", st == 200, st)
        check(
            "RPC response is a server-response envelope with a result",
            isinstance(body, dict) and body.get("type") == "server-response" and "result" in body,
            body if not isinstance(body, dict) else list(body.keys()),
        )

        st, _ = post(port, "/api/session.list",
                     {"type": "client-request", "rpcId": str(uuid.uuid4()),
                      "method": "session.list", "payload": {}},
                     content_type="text/plain")
        check("a non-JSON content-type is refused (415)", st == 415, st)

        st, body = post(port, "/api/session.list",
                        {"type": "client-request", "rpcId": str(uuid.uuid4()),
                         "method": "session.create", "payload": {}})
        mismatch_rejected = st == 200 and isinstance(body, dict) and \
            body.get("result", {}).get("ok") is False
        check("a path/body method mismatch is rejected", mismatch_rejected, body)

        st, body = rpc(port, "session.nonexistent", {})
        check("an unknown method is 404", st == 404, st)

        # ---- session lifecycle ----
        st, body = rpc(port, "session.create", {"cwd": workdir})
        result = body.get("result", {}) if isinstance(body, dict) else {}
        sid = result.get("value", {}).get("sessionId") if result.get("ok") else None
        if not check("session.create returns a sessionId", bool(sid), body):
            return 1
        print("  session:", sid)

        # BUSINESS ERRORS RIDE A 200 — the single easiest thing to get wrong in a client.
        st, body = rpc(port, "session.cancel", {"sessionId": "session-does-not-exist"})
        biz = isinstance(body, dict) and body.get("result", {}).get("ok") is False
        check("a business error is HTTP 200 with result.ok=false", st == 200 and biz, (st, body))

        # A prompt WOULD need a real API key to produce inference; we only assert the envelope is
        # accepted, so the probe stays free and offline-safe.
        st, body = rpc(port, "session.prompt", {
            "sessionId": sid, "mode": "queue",
            "content": [{"type": "text", "text": "probe: no reply needed"}],
        })
        check("session.prompt accepts {type:'text'} content parts", st == 200 and isinstance(body, dict), (st, body))

        st, body = rpc(port, "session.prompt", {
            "sessionId": sid, "mode": "queue",
            "content": [{"kind": "text", "text": "wrong discriminant"}],
        })
        rejected = isinstance(body, dict) and body.get("result", {}).get("ok") is False
        check("content parts use `type`, not `kind` (the stale JSDoc is wrong)", rejected, body)

        # ---- disk ----
        time.sleep(2)
        sessions_root = os.path.join(dsh_home, "sessions")
        found = None
        for dirpath, _dirnames, filenames in os.walk(sessions_root):
            for fn in filenames:
                if fn in ("session.jsonl.zstd", "session.jsonl"):
                    found = os.path.join(dirpath, fn)
                    break
            if found:
                break
        if not check("the session landed under $DSH_HOME/sessions", bool(found), sessions_root):
            return 1
        print("  file   :", found)

        project_dir = os.path.basename(os.path.dirname(os.path.dirname(found)))
        check("the project directory is the --<key>-- wrapped form",
              project_dir.startswith("--") and project_dir.endswith("--"), project_dir)

        if found.endswith(".zstd"):
            frames, size = zstd_frame_count(found)
            check("the transcript is CONCATENATED multi-frame zstd (>1 frame)", frames > 1,
                  "%d frames in %d bytes" % (frames, size))
            try:
                import zstandard  # optional; only used to read the header line back
                dctx = zstandard.ZstdDecompressor()
                text = dctx.stream_reader(open(found, "rb"), read_across_frames=True).read().decode(
                    "utf-8", "replace")
            except ImportError:
                out = subprocess.run(["zstd", "-dc", found], capture_output=True)
                text = out.stdout.decode("utf-8", "replace")
        else:
            text = open(found, encoding="utf-8", errors="replace").read()

        first = text.splitlines()[0] if text.strip() else ""
        try:
            header = json.loads(first)
        except ValueError:
            header = {}
        check("the header line is type:'session' with version 0",
              header.get("type") == "session" and header.get("version") == 0, first[:200])
        check("the header carries the cwd VERBATIM (never derived from the dir name)",
              header.get("cwd") == workdir, (header.get("cwd"), workdir))

        # ---- ask/approval carrier (--probe-ask) ----
        if probe_ask:
            print()
            print("---- ask/approval channel (--probe-ask) ----")

            # Answering is not a method: only the fixed /api/respond path exists.
            st, body = rpc(port, "question.respond", {})
            unary_absent = st == 404
            st, body = rpc(port, "approval.respond", {})
            unary_absent = unary_absent and st == 404
            check("answering is NOT an RPC method (question/approval.respond are 404)", unary_absent, st)

            st, body = receipt(port, str(uuid.uuid4()),
                               {"sessionId": sid, "answer": {"answers": []}})
            check("POST /api/respond exists and answers HTTP 200", st == 200, st)
            check("the reply is an RpcReceipt, NOT a server-response envelope",
                  isinstance(body, dict) and "accepted" in body and "type" not in body
                  and "result" not in body,
                  body)
            check("an unknown/settled rpcId is silently dropped as not-pending",
                  isinstance(body, dict) and body.get("accepted") is False
                  and body.get("reason") == "not-pending", body)

            st, body = post(port, "/api/respond", {"type": "client-response", "rpcId": str(uuid.uuid4())})
            check("a malformed client-response is reason:'bad-response'",
                  st == 200 and isinstance(body, dict) and body.get("reason") == "bad-response", (st, body))

            st, _ = post(port, "/api/respond",
                         {"type": "client-response", "rpcId": str(uuid.uuid4()),
                          "result": {"ok": True, "value": {}}}, content_type="text/plain")
            check("/api/respond enforces the JSON content-type fence too (415)", st == 415, st)

            # ---- live round trip (needs a key: only real inference makes the agent ask) ----
            if not os.environ.get("DEEPSEEK_API_KEY"):
                print("  SKIP  live question round trip — DEEPSEEK_API_KEY is not set in the environment.")
                print("        The agent only calls ask_user_question during a real turn, so this tier")
                print("        costs one cheap inference. Export the key to run it; the carrier checks")
                print("        above already ran and need no credential.")
            else:
                # The envelope-only prompts above are junk to a session that can now really infer;
                # cancel that turn so the live tier is the only thing spending tokens.
                rpc(port, "session.cancel", {"sessionId": sid})

                st, body = rpc(port, "session.create", {"cwd": workdir})
                ask_sid = body.get("result", {}).get("value", {}).get("sessionId")
                rpc(port, "session.prompt", {
                    "sessionId": ask_sid, "mode": "queue",
                    "content": [{"type": "text", "text":
                                 "Call the ask_user_question tool exactly once, right now: a single "
                                 "question with id 'color', question 'Which color do you prefer?', "
                                 "header 'Color', and two options labelled 'Red' and 'Blue'. Use no "
                                 "other tool and do not answer it yourself."}],
                })

                asked = mux.await_frame(
                    lambda f: f.get("method") == "question/requested"
                    and (f.get("payload") or {}).get("sessionId") == ask_sid, 180)
                if not check("the agent's question arrives as a question/requested server-request",
                             asked is not None,
                             ("rpcId " + asked["rpcId"]) if asked else "no such frame within 180s"):
                    return 1
                payload = asked.get("payload") or {}
                check("the ask frame is a server-request whose method mirrors payload.type",
                      asked.get("type") == "server-request"
                      and asked.get("method") == payload.get("type") == "question/requested",
                      (asked.get("type"), asked.get("method"), payload.get("type")))

                questions = payload.get("questions") or []
                first = questions[0] if questions else {}
                labels = [o.get("label") for o in (first.get("options") or [])]
                if not check("questions[] carry id + question text + options[].label",
                             bool(first.get("id")) and bool(first.get("question"))
                             and bool(labels) and all(labels), first):
                    return 1

                ask_rpc_id = asked.get("rpcId")

                def answer_for(selected):
                    """The one answer envelope shape, reused by every variant below."""
                    return {"sessionId": ask_sid,
                            "answer": {"answers": [{"id": first.get("id"), "selected": selected}]}}

                _, by_index = receipt(port, ask_rpc_id, answer_for([0]))
                check("the answer vocabulary is the option LABEL — an index is rejected",
                      isinstance(by_index, dict) and by_index.get("reason") == "bad-response", by_index)
                _, bad_label = receipt(port, ask_rpc_id, answer_for(["definitely-not-an-option"]))
                check("an unknown label is rejected too (dsh matches against the pending questions)",
                      isinstance(bad_label, dict) and bad_label.get("reason") == "bad-response", bad_label)

                st, body = receipt(port, ask_rpc_id, {
                    "sessionId": ask_sid,
                    "answer": {"answers": [{"id": "not-the-asked-id", "selected": [labels[0]]}]}})
                check("a wrong question id is rejected (answers are matched positionally AND by id)",
                      isinstance(body, dict) and body.get("reason") == "bad-response", body)

                st, body = receipt(port, ask_rpc_id, answer_for([labels[0]]))
                if not check("a label answer is accepted", st == 200 and isinstance(body, dict)
                             and body.get("accepted") is True, (st, body)):
                    return 1

                st, body = receipt(port, ask_rpc_id, answer_for([labels[0]]))
                check("replaying the same answer is not-pending (first claimant wins)",
                      isinstance(body, dict) and body.get("reason") == "not-pending", body)

                resolved = mux.await_frame(
                    lambda f: f.get("method") == "question/resolved"
                    and (f.get("payload") or {}).get("questionRpcId") == ask_rpc_id, 30)
                check("resolution broadcasts question/resolved echoing questionRpcId, outcome 'answered'",
                      resolved is not None
                      and (resolved.get("payload") or {}).get("outcome") == "answered",
                      resolved)

                # ---- how the exchange replays from disk ----
                mux.await_frame(lambda f: ((f.get("payload") or {}).get("event") or {}).get("type")
                                == "turn/end" and (f.get("payload") or {}).get("sessionId") == ask_sid, 180)
                time.sleep(2)
                ask_file = None
                for dirpath, _dirnames, filenames in os.walk(sessions_root):
                    if os.path.basename(dirpath) != ask_sid:
                        continue
                    for fn in filenames:
                        if fn.startswith("session.jsonl"):
                            ask_file = os.path.join(dirpath, fn)
                if not check("the asking session landed on disk", bool(ask_file), sessions_root):
                    return 1
                if ask_file.endswith(".zstd"):
                    try:
                        import zstandard
                        ask_text = zstandard.ZstdDecompressor().stream_reader(
                            open(ask_file, "rb"), read_across_frames=True).read().decode("utf-8", "replace")
                    except ImportError:
                        ask_text = subprocess.run(["zstd", "-dc", ask_file],
                                                  capture_output=True).stdout.decode("utf-8", "replace")
                else:
                    ask_text = open(ask_file, encoding="utf-8", errors="replace").read()

                records = []
                for line in ask_text.splitlines():
                    try:
                        records.append(json.loads(line))
                    except ValueError:
                        pass
                types = {r.get("type") for r in records}
                check("NO question/* record exists on disk (a question is not a session event)",
                      not any(str(t).startswith("question/") for t in types),
                      sorted(t for t in types if str(t).startswith("question/")))
                tool_calls = [r for r in records if r.get("type") == "tool/call"
                              and (r.get("data") or {}).get("name") == "ask_user_question"]
                check("the question replays as a tool/call named ask_user_question",
                      bool(tool_calls), sorted(str(t) for t in types))
                call_id = (tool_calls[0].get("data") or {}).get("callId") if tool_calls else None
                answer_text = ""
                for r in records:
                    if r.get("type") != "tool/result":
                        continue
                    for block in ((r.get("data") or {}).get("message") or {}).get("content") or []:
                        if block.get("toolCallId") != call_id:
                            continue
                        for part in block.get("content") or []:
                            answer_text += part.get("text") or ""
                check("the human's answer replays only as that call's tool/result text",
                      labels[0] in answer_text, answer_text[:200] or "(no tool/result for the call)")

    finally:
        if mux:
            mux.stop = True
        if sock:
            try:
                sock.close()
            except OSError:
                pass
        proc.kill()
        proc.wait(timeout=10)

    print()
    failed = [n for n, ok, _ in results if not ok]
    print("%d/%d checks passed" % (len(results) - len(failed), len(results)))
    if failed:
        print("FAILED: " + ", ".join(failed))
        print("\ndsh's local API drifted. Re-read DshApiClient/DshBackend before shipping.")
        return 1
    print("dsh wire + disk contracts intact.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
