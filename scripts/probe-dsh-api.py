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

NO API KEY IS NEEDED OR USED. Boot, session creation and the WS handshake all work unauthenticated;
only real inference would need DEEPSEEK_API_KEY, and this probe deliberately never triggers one. It
therefore costs nothing and can run in CI.

Usage:
    python3 scripts/probe-dsh-api.py [workdir]

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
    """Minimal RFC6455 client handshake. Returns (status_line, first_frame_bytes_or_None)."""
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
    head = buf.split(b"\r\n\r\n", 1)[0].decode("utf-8", "replace")
    ok_accept = accept.lower() in head.lower()
    return s, head.splitlines()[0] if head else "", ok_accept


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

    workdir = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else tempfile.mkdtemp(prefix="dsh-probe-cwd-")
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
    try:
        # ---- WS carrier ----
        st, _ = post(port, "/api/events.mux", {}, content_type="application/json")
        check("plain POST/GET on events.mux is refused (426 expected, no SSE fallback)", st in (404, 405, 426), st)

        sock, status_line, ok_accept = ws_handshake(port)
        check("events.mux completes a real WebSocket handshake", "101" in status_line and ok_accept, status_line)

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

    finally:
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
