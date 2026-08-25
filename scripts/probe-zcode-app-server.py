#!/usr/bin/env python3
"""Reproducible probe for ZCode's bundled CLI/app-server (issue #228).

Pinned evidence target:
  ZCode desktop 3.7.6 (macOS arm64 official download)
  https://cdn-zcode.z.ai/zcode/electron/releases/3.7.6/macos-arm64/ZCode-3.7.6-mac-arm64.dmg

The app-server protocol is not documented as a public/stable API.  This probe
therefore records what the installed bundle actually does instead of treating
reverse-engineered method names as a contract.  It covers:

  Z1  CLI/app-server entry points and --help output
  Z2  newline-delimited JSON envelope (deliberately tests `jsonrpc` rejection)
  Z3  method/schema presence for lifecycle, prompt, events, cancel and model APIs
  Z4  active create/list/resume/read/messages/subscribe flow
  Z5  prompt plus streamed `session/event` notifications
  Z6  server->client runtime-preferences and interaction requests (deny by default)
  Z7  optional in-flight `session/stop` cancellation
  Z8  model projection and local ~/.zcode/cli/db/db.sqlite metadata

Safe/default usage (no session creation, model call, or tool execution):
  python3 scripts/probe-zcode-app-server.py --zcode-bin /path/to/zcode.cjs
  python3 scripts/probe-zcode-app-server.py --self-test

Active usage (creates a disposable-workspace ZCode session and sends a fixed PONG prompt):
  python3 scripts/probe-zcode-app-server.py --zcode-bin /path/to/zcode.cjs --active

Optional destructive-looking behaviours are still safe and bounded: the permission
probe asks to remove a known-nonexistent temp path and always DENIES interaction
requests; the cancel probe
interrupts a text-only turn immediately after it starts.
  ... --active --permission-probe --cancel-probe

Every inbound/outbound frame is written to a JSONL evidence log.  Run this after
each ZCode upgrade: method names and schemas are private implementation details.
"""

from __future__ import annotations

import argparse
import json
import os
import queue
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any


OFFICIAL_APP_VERSION = "3.7.6"
OFFICIAL_DMG_URL = (
    "https://cdn-zcode.z.ai/zcode/electron/releases/3.7.6/"
    "macos-arm64/ZCode-3.7.6-mac-arm64.dmg"
)
DEFAULT_TIMEOUT = 12.0
RUNTIME_PREFERENCES = {
    "nativeSearchEnhancementsEnabled": False,
    "memoryEnabled": False,
    "askUserQuestionAutoResolutionEnabled": False,
    "modelContextBudgetStrategy": "preflight-v1",
}


@dataclass
class Result:
    probe_id: str
    status: str
    note: str


RESULTS: list[Result] = []


def record(probe_id: str, ok: bool | None, note: str) -> None:
    status = "UNKNOWN" if ok is None else ("PASS" if ok else "FAIL")
    RESULTS.append(Result(probe_id, status, note))
    print(f"  [{status}] {probe_id}: {note}")


def compact(value: Any, limit: int = 500) -> str:
    text = json.dumps(value, ensure_ascii=False, sort_keys=True)
    return text if len(text) <= limit else text[: limit - 3] + "..."


def classify(frame: dict[str, Any]) -> str:
    if "method" in frame and "id" in frame:
        return "request"
    if "method" in frame:
        return "notification"
    if "id" in frame and ("result" in frame or "error" in frame):
        return "response"
    return "unknown"


def error_code(frame: dict[str, Any]) -> int | None:
    error = frame.get("error")
    return error.get("code") if isinstance(error, dict) else None


def error_mentions(frame: dict[str, Any], needle: str) -> bool:
    return needle.lower() in compact(frame.get("error", {}), 5000).lower()


def find_session_id(frame: dict[str, Any]) -> str | None:
    result = frame.get("result")
    if not isinstance(result, dict):
        return None
    candidates = [result, result.get("session")]
    for candidate in candidates:
        if isinstance(candidate, dict):
            value = candidate.get("sessionId") or candidate.get("id")
            if isinstance(value, str) and value:
                return value
    return None


def zcode_base_argv(path: Path, explicit_node: str | None = None) -> list[str]:
    if path.suffix == ".cjs" or path.suffix == ".js":
        candidate = explicit_node or os.environ.get("ZCODE_NODE_BIN") or shutil.which("node")
        node = shutil.which(candidate) if candidate else None
        if not node and candidate and Path(candidate).expanduser().is_file():
            node = str(Path(candidate).expanduser().resolve())
        if not node:
            raise RuntimeError(
                "zcode.cjs requires Node.js; pass --node-bin or set ZCODE_NODE_BIN"
            )
        return [node, str(path)]
    return [str(path)]


def discover_zcode(explicit: str | None) -> Path | None:
    candidates: list[Path] = []
    if explicit:
        candidates.append(Path(explicit).expanduser())
    if os.environ.get("ZCODE_BIN"):
        candidates.append(Path(os.environ["ZCODE_BIN"]).expanduser())
    on_path = shutil.which("zcode")
    if on_path:
        candidates.append(Path(on_path))
    candidates.extend(
        [
            Path("/Applications/ZCode.app/Contents/Resources/glm/zcode.cjs"),
            Path.home() / "Applications/ZCode.app/Contents/Resources/glm/zcode.cjs",
            Path("/opt/ZCode/app/resources/glm/zcode.cjs"),
        ]
    )
    return next((p.resolve() for p in candidates if p.is_file()), None)


def run_help(argv: list[str], suffix: list[str]) -> tuple[int, str]:
    try:
        completed = subprocess.run(
            argv + suffix,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=20,
            check=False,
        )
        return completed.returncode, completed.stdout
    except subprocess.TimeoutExpired as exc:
        output = exc.stdout or ""
        if isinstance(output, bytes):
            output = output.decode("utf-8", "replace")
        return 124, output


class EvidenceLog:
    def __init__(self, path: Path):
        self.path = path
        self._file = path.open("w", encoding="utf-8")
        self._lock = threading.Lock()

    def write(self, direction: str, value: Any) -> None:
        row = {"time": time.time(), "direction": direction, "value": value}
        with self._lock:
            self._file.write(json.dumps(row, ensure_ascii=False) + "\n")
            self._file.flush()

    def close(self) -> None:
        self._file.close()


class AppServer:
    """Small custom-NDJSON client with server-request handling."""

    def __init__(self, argv: list[str], cwd: Path, log: EvidenceLog):
        self.log = log
        self.responses: queue.Queue[dict[str, Any]] = queue.Queue()
        self.notifications: list[dict[str, Any]] = []
        self.server_requests: list[dict[str, Any]] = []
        self.non_json_stdout: list[str] = []
        self.stderr_lines: list[str] = []
        self._next_id = 0
        self._send_lock = threading.Lock()
        self.proc = subprocess.Popen(
            argv + ["app-server", "--stdio"],
            cwd=str(cwd),
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
        )
        threading.Thread(target=self._read_stdout, daemon=True).start()
        threading.Thread(target=self._read_stderr, daemon=True).start()

    def send(self, frame: dict[str, Any]) -> None:
        self.log.write("send", frame)
        line = json.dumps(frame, ensure_ascii=False, separators=(",", ":"))
        with self._send_lock:
            if not self.proc.stdin:
                raise RuntimeError("app-server stdin unavailable")
            self.proc.stdin.write(line + "\n")
            self.proc.stdin.flush()

    def send_raw(self, line: str) -> None:
        self.log.write("send-raw", line)
        with self._send_lock:
            if not self.proc.stdin:
                raise RuntimeError("app-server stdin unavailable")
            self.proc.stdin.write(line + "\n")
            self.proc.stdin.flush()

    def reply(self, request_id: Any, result: dict[str, Any]) -> None:
        self.send({"id": request_id, "result": result})

    def request(
        self,
        method: str,
        params: dict[str, Any],
        timeout: float = DEFAULT_TIMEOUT,
        extra: dict[str, Any] | None = None,
        accept_any_response: bool = False,
    ) -> dict[str, Any]:
        self._next_id += 1
        request_id = f"probe-{self._next_id}"
        frame: dict[str, Any] = {"id": request_id, "method": method, "params": params}
        if extra:
            frame.update(extra)
        self.send(frame)
        deadline = time.monotonic() + timeout
        stash: list[dict[str, Any]] = []
        while time.monotonic() < deadline:
            try:
                response = self.responses.get(
                    timeout=max(0.001, min(0.25, deadline - time.monotonic()))
                )
            except queue.Empty:
                if self.proc.poll() is not None:
                    raise RuntimeError(f"app-server exited with code {self.proc.returncode}")
                continue
            if accept_any_response:
                for item in stash:
                    self.responses.put(item)
                return response
            if response.get("id") == request_id:
                for item in stash:
                    self.responses.put(item)
                return response
            stash.append(response)
        for item in stash:
            self.responses.put(item)
        raise TimeoutError(f"{method} timed out after {timeout:.1f}s")

    def notify(self, method: str, params: dict[str, Any]) -> None:
        self.send({"method": method, "params": params})

    def wait_for_event(self, types: set[str], after: int, timeout: float) -> dict[str, Any] | None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            for frame in self.notifications[after:]:
                if frame.get("method") != "session/event":
                    continue
                params = frame.get("params") or {}
                if params.get("type") in types:
                    return frame
            if self.proc.poll() is not None:
                return None
            time.sleep(0.1)
        return None

    def _read_stdout(self) -> None:
        assert self.proc.stdout
        for raw in self.proc.stdout:
            line = raw.strip()
            if not line:
                continue
            try:
                frame = json.loads(line)
            except json.JSONDecodeError:
                self.non_json_stdout.append(line)
                self.log.write("recv-non-json", line)
                continue
            self.log.write("recv", frame)
            kind = classify(frame)
            if kind == "response":
                self.responses.put(frame)
            elif kind == "request":
                self.server_requests.append(frame)
                self._handle_server_request(frame)
            elif kind == "notification":
                self.notifications.append(frame)

    def _read_stderr(self) -> None:
        assert self.proc.stderr
        for raw in self.proc.stderr:
            line = raw.rstrip("\r\n")
            self.stderr_lines.append(line)
            self.log.write("stderr", line)

    def _handle_server_request(self, frame: dict[str, Any]) -> None:
        method = frame.get("method")
        request_id = frame.get("id")
        if method == "session/requestRuntimePreferences":
            self.reply(request_id, RUNTIME_PREFERENCES)
        elif method == "interaction/requestPermission":
            self.reply(request_id, {"decision": "deny", "reason": "cc-pocket probe denies tools"})
        elif method == "interaction/requestUserInput":
            self.reply(
                request_id,
                {"action": "decline", "reason": "cc-pocket probe is noninteractive"},
            )
        elif method == "interaction/requestProviderRuntimeHeaders":
            # Start Plan subscription providers require their desktop host to refresh private runtime
            # headers by side effect. A standalone probe cannot honestly acknowledge that mutation.
            self.reply(
                request_id,
                {
                    "headersApplied": False,
                    "errorMessage": (
                        "standalone probe cannot access ZCode Desktop Start Plan credentials"
                    ),
                },
            )
        else:
            self.send(
                {
                    "id": request_id,
                    "error": {"code": -32601, "message": f"probe unsupported: {method}"},
                }
            )

    def close(self) -> None:
        try:
            if self.proc.stdin:
                self.proc.stdin.close()
        except (BrokenPipeError, OSError):
            pass
        try:
            self.proc.wait(timeout=3)
        except subprocess.TimeoutExpired:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=2)
            except subprocess.TimeoutExpired:
                self.proc.kill()


def storage_snapshot(root: Path) -> dict[str, Any]:
    db = root / "cli" / "db" / "db.sqlite"
    result: dict[str, Any] = {"path": str(db), "exists": db.is_file()}
    if not db.is_file():
        return result
    stat = db.stat()
    result.update({"size": stat.st_size, "mtime_ns": stat.st_mtime_ns})
    for suffix in ("-wal", "-shm"):
        companion = Path(str(db) + suffix)
        if companion.is_file():
            companion_stat = companion.stat()
            result[suffix[1:]] = {
                "size": companion_stat.st_size,
                "mtime_ns": companion_stat.st_mtime_ns,
            }
    try:
        uri = f"file:{db}?mode=ro"
        with sqlite3.connect(uri, uri=True, timeout=1) as connection:
            rows = connection.execute(
                "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"
            ).fetchall()
            session_count = connection.execute("SELECT count(*) FROM session").fetchone()[0]
        result["tables"] = [row[0] for row in rows]
        result["session_count"] = session_count
    except sqlite3.Error as exc:
        result["sqlite_error"] = str(exc)
    return result


def self_test() -> int:
    assert classify({"id": 1, "method": "m", "params": {}}) == "request"
    assert classify({"method": "session/event", "params": {}}) == "notification"
    assert classify({"id": 1, "result": {}}) == "response"
    assert find_session_id({"result": {"sessionId": "s1"}}) == "s1"
    assert find_session_id({"result": {"session": {"sessionId": "s2"}}}) == "s2"
    assert error_code({"error": {"code": -32601}}) == -32601
    assert zcode_base_argv(Path("/tmp/zcode.cjs"), shutil.which("node"))[-1] == "/tmp/zcode.cjs"
    assert RUNTIME_PREFERENCES["modelContextBudgetStrategy"] == "preflight-v1"
    print("self-test: PASS")
    return 0


def probe_schema(client: AppServer) -> None:
    print("\nZ2/Z3 protocol envelope and method surface")
    response = client.request("initialize", {})
    record("Z2.initialize", error_code(response) == -32601, compact(response))

    # Invalid envelopes use the fixed response id "invalid-message", so do not
    # wait for our request id to be echoed on this deliberately malformed case.
    response = client.request(
        "initialize", {}, extra={"jsonrpc": "2.0"}, accept_any_response=True
    )
    rejected = error_code(response) == -32600 and error_mentions(response, "jsonrpc")
    record("Z2.no-jsonrpc", rejected, compact(response))

    methods = [
        "session/create",
        "session/list",
        "session/resume",
        "session/read",
        "session/messages",
        "session/subscribe",
        "session/send",
        "session/stop",
        "session/setModel",
        "session/setMode",
        "session/setThoughtLevel",
    ]
    for method in methods:
        try:
            response = client.request(method, {}, timeout=5)
        except TimeoutError:
            record(f"Z3.{method}", None, "no response to empty-schema request")
            continue
        code = error_code(response)
        present = code != -32601
        record(f"Z3.{method}", present, compact(response))


def active_probe(
    client: AppServer,
    workspace: Path,
    permission_probe: bool,
    cancel_probe: bool,
) -> None:
    print("\nZ4-Z8 active session probe")
    workspace_obj = {"workspacePath": str(workspace), "workspaceKey": str(workspace)}
    try:
        created = client.request(
            "session/create", {"workspace": workspace_obj, "mode": "build"}, timeout=30
        )
    except (RuntimeError, TimeoutError) as exc:
        record("Z4.create", False, str(exc))
        return
    session_id = find_session_id(created)
    runtime_calls = [
        r for r in client.server_requests if r.get("method") == "session/requestRuntimePreferences"
    ]
    record(
        "Z6.runtime-preferences",
        bool(runtime_calls),
        f"observed={len(runtime_calls)} "
        f"scopes={[((r.get('params') or {}).get('scope')) for r in runtime_calls]} "
        f"reply={compact(RUNTIME_PREFERENCES)}",
    )
    if error_mentions(created, "model config is missing") or error_mentions(created, "login"):
        record("Z4.create", None, f"credentials/model config unavailable: {compact(created)}")
    else:
        record("Z4.create", bool(session_id), compact(created))
    if not session_id:
        return

    lifecycle_calls = [
        ("list", "session/list", {"workspace": workspace_obj}),
        ("resume", "session/resume", {"sessionId": session_id, "workspace": workspace_obj}),
        ("read", "session/read", {"sessionId": session_id}),
        ("messages", "session/messages", {"sessionId": session_id}),
    ]
    lifecycle_responses: dict[str, dict[str, Any]] = {}
    for label, method, params in lifecycle_calls:
        try:
            response = client.request(method, params, timeout=20)
        except (RuntimeError, TimeoutError) as exc:
            record(f"Z4.{label}", None, str(exc))
            continue
        lifecycle_responses[label] = response
        record(f"Z4.{label}", "result" in response, compact(response))

    model_evidence = lifecycle_responses.get("read", {}).get("result", {})
    record(
        "Z8.model-projection",
        bool(model_evidence),
        compact(model_evidence, 1000),
    )

    try:
        subscribed = client.request(
            "session/subscribe",
            {
                "sessionId": session_id,
                "deliveryKind": "desktop-continuous",
                "includeSnapshot": True,
                "afterSeq": 0,
            },
            timeout=20,
        )
    except (RuntimeError, TimeoutError) as exc:
        record("Z5.subscribe", False, str(exc))
        return
    record("Z5.subscribe", "result" in subscribed, compact(subscribed))
    if "error" in subscribed:
        return

    before = len(client.notifications)
    try:
        sent = client.request(
            "session/send",
            {"sessionId": session_id, "content": "Reply with exactly PONG and nothing else."},
            timeout=30,
        )
    except (RuntimeError, TimeoutError) as exc:
        record("Z5.send", False, str(exc))
        return
    accepted = bool((sent.get("result") or {}).get("accepted"))
    record("Z5.send", accepted, compact(sent))
    terminal = client.wait_for_event(
        {"turn.completed", "turn.failed", "turn.terminal"}, before, timeout=120
    )
    turn_events = [
        frame
        for frame in client.notifications[before:]
        if frame.get("method") == "session/event"
    ]
    types = [((frame.get("params") or {}).get("type")) for frame in turn_events]
    deltas = [
        ((frame.get("params") or {}).get("payload") or {}).get("delta", "")
        for frame in turn_events
        if ((frame.get("params") or {}).get("type")) == "model.streaming"
    ]
    record(
        "Z5.events",
        terminal is not None and "turn.started" in types,
        f"types={types} text={''.join(deltas)!r}",
    )

    if permission_probe:
        before = len(client.notifications)
        previous_request_count = len(client.server_requests)
        no_op_target = workspace / "cc-pocket-zcode-permission-probe-nonexistent"
        try:
            client.request(
                "session/send",
                {
                    "sessionId": session_id,
                    "content": (
                        "Use the shell tool exactly once to run this exact command, then stop: "
                        f"rm -f {no_op_target}"
                    ),
                },
                timeout=30,
            )
            client.wait_for_event(
                {"turn.completed", "turn.failed", "turn.terminal"}, before, timeout=120
            )
        except (RuntimeError, TimeoutError) as exc:
            record("Z6.permission", None, str(exc))
        permission_calls = [
            r
            for r in client.server_requests[previous_request_count:]
            if r.get("method") == "interaction/requestPermission"
        ]
        record(
            "Z6.permission",
            True if permission_calls else None,
            f"observed={len(permission_calls)}; interaction requests are denied by the probe; "
            f"target_exists={no_op_target.exists()}",
        )

    if cancel_probe:
        before = len(client.notifications)
        try:
            sent = client.request(
                "session/send",
                {
                    "sessionId": session_id,
                    "content": (
                        "Write a long, detailed essay with at least 1500 words "
                        "about software testing."
                    ),
                },
                timeout=30,
            )
            started = client.wait_for_event({"turn.started"}, before, timeout=30)
            stop_response = None
            if started:
                # 0.16.3 implements stop as a request returning {}; a notification
                # is accepted by the framing parser but does not abort the turn.
                stop_response = client.request(
                    "session/stop", {"sessionId": session_id}, timeout=10
                )
            terminal = client.wait_for_event(
                {"turn.completed", "turn.failed", "turn.terminal"}, before, timeout=20
            )
            terminal_params = (terminal or {}).get("params") or {}
            terminal_payload = terminal_params.get("payload") or {}
            cancelled = terminal_payload.get("resultType") == "cancelled" or (
                terminal_params.get("type") == "turn.failed"
                and "cancel" in compact(terminal_payload).lower()
            )
            record(
                "Z7.cancel",
                started is not None and "result" in (stop_response or {}) and cancelled,
                f"send={compact(sent)} stop={compact(stop_response)} terminal={compact(terminal)}",
            )
        except (RuntimeError, TimeoutError) as exc:
            record("Z7.cancel", None, str(exc))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--zcode-bin", help="zcode executable or bundled zcode.cjs")
    parser.add_argument(
        "--node-bin", help="Node executable for zcode.cjs (or set ZCODE_NODE_BIN)"
    )
    parser.add_argument("--workspace", type=Path, help="active probe workspace (default: temp dir)")
    parser.add_argument("--log", type=Path, help="evidence JSONL path")
    parser.add_argument(
        "--active", action="store_true", help="create/resume a session and send PONG"
    )
    parser.add_argument(
        "--permission-probe",
        action="store_true",
        help="ask for a no-op high-risk tool call; all permission requests are denied",
    )
    parser.add_argument(
        "--cancel-probe", action="store_true", help="start a text-only turn then send session/stop"
    )
    parser.add_argument("--self-test", action="store_true", help="run offline parser checks only")
    parser.add_argument(
        "--zcode-home", type=Path, default=Path.home() / ".zcode", help="storage metadata root"
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.self_test:
        return self_test()
    if (args.permission_probe or args.cancel_probe) and not args.active:
        print("error: --permission-probe/--cancel-probe require --active", file=sys.stderr)
        return 2

    zcode = discover_zcode(args.zcode_bin)
    if not zcode:
        print(
            "ZCode CLI not found. Pass --zcode-bin "
            "/path/to/ZCode.app/Contents/Resources/glm/zcode.cjs\n"
            f"Official app {OFFICIAL_APP_VERSION}: {OFFICIAL_DMG_URL}",
            file=sys.stderr,
        )
        return 2
    argv = zcode_base_argv(zcode, args.node_bin)
    print(f"official app target: {OFFICIAL_APP_VERSION}")
    print(f"official download: {OFFICIAL_DMG_URL}")
    print(f"zcode entry: {' '.join(argv)}")

    print("\nZ1 CLI entry points")
    if zcode.suffix in {".cjs", ".js"}:
        code, output = run_help([argv[0]], ["--version"])
        record("Z1.node-version", code == 0, f"exit={code} {output.strip()}")
    for probe_id, suffix in [
        ("version", ["--version"]),
        ("cli-help", ["--help"]),
        ("app-server-help", ["app-server", "--help"]),
    ]:
        code, output = run_help(argv, suffix)
        normalized = output.strip().replace("\n", " | ")
        record(f"Z1.{probe_id}", code == 0, f"exit={code} {normalized[:1000]}")

    workspace = args.workspace.resolve() if args.workspace else Path(
        tempfile.mkdtemp(prefix="zcode-app-server-probe-")
    )
    workspace.mkdir(parents=True, exist_ok=True)
    log_path = args.log.resolve() if args.log else workspace / "zcode-app-server-probe.jsonl"
    log = EvidenceLog(log_path)
    before_storage = storage_snapshot(args.zcode_home.expanduser())
    log.write(
        "meta",
        {
            "officialAppVersion": OFFICIAL_APP_VERSION,
            "officialDmgUrl": OFFICIAL_DMG_URL,
            "zcode": str(zcode),
            "argv": argv + ["app-server", "--stdio"],
            "workspace": str(workspace),
            "active": args.active,
            "storageBefore": before_storage,
        },
    )
    client: AppServer | None = None
    try:
        client = AppServer(argv, workspace, log)
        probe_schema(client)
        if args.active:
            active_probe(client, workspace, args.permission_probe, args.cancel_probe)
    except (OSError, RuntimeError, TimeoutError) as exc:
        record("app-server", False, str(exc))
    finally:
        if client:
            client.close()
        after_storage = storage_snapshot(args.zcode_home.expanduser())
        log.write("meta", {"storageAfter": after_storage})
        log.close()

    changed = before_storage != after_storage
    record(
        "Z8.local-storage",
        after_storage.get("exists") if args.active else None,
        f"changed={changed} metadata={compact(after_storage, 1500)}",
    )
    print(f"\nevidence log: {log_path}")
    print("\nSummary")
    for result in RESULTS:
        print(f"  {result.status:7} {result.probe_id}")
    return 1 if any(result.status == "FAIL" for result in RESULTS) else 0


if __name__ == "__main__":
    raise SystemExit(main())
