#!/usr/bin/env python3
"""Minimal public HTTP boundary for the isolated CC Pocket OpenClaw support agent."""

from __future__ import annotations

import hashlib
import hmac
import json
import logging
import os
import queue
import re
import secrets
import subprocess
import threading
import time
import uuid
from collections import defaultdict, deque
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import urlparse


HOST = os.environ.get("CC_SUPPORT_HOST", "127.0.0.1")
PORT = int(os.environ.get("CC_SUPPORT_PORT", "10966"))
OPENCLAW_BIN = os.environ.get("OPENCLAW_BIN", "/usr/local/bin/openclaw")
AGENT_ID = os.environ.get("CC_SUPPORT_AGENT", "cc-pocket-support")
ALLOWED_ORIGINS = {
    item.strip()
    for item in os.environ.get(
        "CC_SUPPORT_ALLOWED_ORIGINS",
        "https://pocket.ark-nexus.cc,https://heypandax.github.io",
    ).split(",")
    if item.strip()
}
MAX_BODY_BYTES = int(os.environ.get("CC_SUPPORT_MAX_BODY_BYTES", "8192"))
MAX_MESSAGE_CHARS = int(os.environ.get("CC_SUPPORT_MAX_MESSAGE_CHARS", "2000"))
MINUTE_LIMIT = int(os.environ.get("CC_SUPPORT_MINUTE_LIMIT", "6"))
DAY_LIMIT = int(os.environ.get("CC_SUPPORT_DAY_LIMIT", "30"))
MAX_CONCURRENT = int(os.environ.get("CC_SUPPORT_MAX_CONCURRENT", "3"))
AGENT_TIMEOUT_SECONDS = int(os.environ.get("CC_SUPPORT_AGENT_TIMEOUT", "120"))
HEARTBEAT_SECONDS = float(os.environ.get("CC_SUPPORT_HEARTBEAT_SECONDS", "10"))

SESSION_RE = re.compile(r"^[A-Za-z0-9_-]{16,64}$")
URL_RE = re.compile(r"https://[^\s<>\])}]+")
INTERNAL_NARRATION_RE = re.compile(
    r"(?:"
    r"看起来(?:手册|文档|结果)|搜索结果|检索结果|"
    r"(?:score|canonical).{0,40}(?:最高|命中|结果)|"
    r"(?:已经|现在).{0,20}足够回答|不需要再(?:查看|检查|搜索)(?:代码|手册)|"
    r"I (?:found|searched|retrieved|now have)|"
    r"(?:search|retrieval) results?|enough evidence|"
    r"tool (?:call|output)|internal (?:file|prompt|policy)"
    r")",
    re.IGNORECASE,
)
LOG = logging.getLogger("cc-pocket-support-web")


class RateLimiter:
    """Privacy-preserving in-memory rate limits keyed by an HMAC of the client IP."""

    def __init__(self, secret: bytes, minute_limit: int, day_limit: int) -> None:
        self._secret = secret
        self._minute_limit = minute_limit
        self._day_limit = day_limit
        self._minute: dict[str, deque[float]] = defaultdict(deque)
        self._day: dict[str, tuple[int, int]] = {}
        self._lock = threading.Lock()

    def visitor_hash(self, address: str) -> str:
        return hmac.new(self._secret, address.encode("utf-8"), hashlib.sha256).hexdigest()

    def allow(self, address: str, now: float | None = None) -> tuple[bool, int]:
        now = time.time() if now is None else now
        visitor = self.visitor_hash(address)
        day = int(now // 86_400)
        with self._lock:
            minute = self._minute[visitor]
            while minute and minute[0] <= now - 60:
                minute.popleft()
            if len(minute) >= self._minute_limit:
                return False, max(1, int(60 - (now - minute[0])))
            stored_day, count = self._day.get(visitor, (day, 0))
            if stored_day != day:
                stored_day, count = day, 0
            if count >= self._day_limit:
                return False, max(1, int((day + 1) * 86_400 - now))
            minute.append(now)
            self._day[visitor] = (stored_day, count + 1)
            return True, 0


def load_secret() -> bytes:
    value = os.environ.get("CC_SUPPORT_WEB_SECRET", "")
    if len(value) < 32:
        raise RuntimeError("CC_SUPPORT_WEB_SECRET must contain at least 32 characters")
    return value.encode("utf-8")


def derive_session_key(secret: bytes, visitor_hash: str, browser_session: str) -> str:
    digest = hmac.new(
        secret,
        f"{visitor_hash}:{browser_session}".encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()[:40]
    return f"agent:{AGENT_ID}:web-{digest}"


def strip_internal_narration(answer: str) -> str:
    """Remove leading retrieval commentary as a final public-boundary safeguard."""

    paragraphs = re.split(r"\n\s*\n", answer.strip())
    while len(paragraphs) > 1 and INTERNAL_NARRATION_RE.search(paragraphs[0]):
        paragraphs.pop(0)
    sanitized = "\n\n".join(paragraphs).strip()
    if not sanitized or INTERNAL_NARRATION_RE.search(sanitized.split("\n\n", 1)[0]):
        raise ValueError("agent answer contains internal narration")
    return sanitized


def extract_answer(payload: dict[str, Any]) -> str:
    result = payload.get("result")
    if not isinstance(result, dict):
        raise ValueError("agent result is missing")
    meta = result.get("meta")
    answer = meta.get("finalAssistantVisibleText") if isinstance(meta, dict) else None
    if not isinstance(answer, str) or not answer.strip():
        for item in result.get("payloads") or []:
            text = item.get("text") if isinstance(item, dict) else None
            if isinstance(text, str) and text.strip() and not text.lstrip().startswith("⚠️"):
                answer = text
                break
    if not isinstance(answer, str) or not answer.strip():
        raise ValueError("agent answer is missing")
    return strip_internal_narration(answer)[:12_000]


def agent_environment() -> dict[str, str]:
    environment = dict(os.environ)
    environment.pop("CC_SUPPORT_WEB_SECRET", None)
    environment["HOME"] = "/home/admin"
    return environment


def run_agent(message: str, session_key: str) -> tuple[str, list[str]]:
    completed = subprocess.run(
        [
            OPENCLAW_BIN,
            "agent",
            "--agent",
            AGENT_ID,
            "--session-key",
            session_key,
            "--message",
            message,
            "--json",
            "--timeout",
            str(AGENT_TIMEOUT_SECONDS),
        ],
        check=False,
        capture_output=True,
        text=True,
        timeout=AGENT_TIMEOUT_SECONDS + 15,
        env=agent_environment(),
    )
    if completed.returncode != 0:
        raise RuntimeError("support agent failed")
    payload = json.loads(completed.stdout)
    answer = extract_answer(payload)
    sources = list(dict.fromkeys(URL_RE.findall(answer)))[:8]
    return answer, sources


class SupportServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address: tuple[str, int], secret: bytes) -> None:
        super().__init__(address, SupportHandler)
        self.secret = secret
        self.rate_limiter = RateLimiter(secret, MINUTE_LIMIT, DAY_LIMIT)
        self.capacity = threading.BoundedSemaphore(MAX_CONCURRENT)
        self._session_locks: dict[str, threading.Lock] = {}
        self._session_locks_guard = threading.Lock()

    def session_lock(self, key: str) -> threading.Lock:
        with self._session_locks_guard:
            if len(self._session_locks) > 2_000:
                self._session_locks.clear()
            return self._session_locks.setdefault(key, threading.Lock())


class SupportHandler(BaseHTTPRequestHandler):
    server: SupportServer
    protocol_version = "HTTP/1.1"

    def log_message(self, _format: str, *_args: object) -> None:
        return

    def _origin(self) -> str | None:
        origin = self.headers.get("Origin")
        return origin if origin in ALLOWED_ORIGINS else None

    def _client_address(self) -> str:
        forwarded = self.headers.get("X-Forwarded-For", "").split(",", 1)[0].strip()
        return forwarded or self.client_address[0]

    def _json(self, status: int, payload: dict[str, Any], *, retry_after: int = 0) -> None:
        raw = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        origin = self._origin()
        if origin:
            self.send_header("Access-Control-Allow-Origin", origin)
            self.send_header("Vary", "Origin")
        if retry_after:
            self.send_header("Retry-After", str(retry_after))
        self.end_headers()
        self.wfile.write(raw)

    def _start_json_stream(self) -> None:
        """Start a chunked JSON response so slow agent calls can send heartbeats."""

        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Transfer-Encoding", "chunked")
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        origin = self._origin()
        if origin:
            self.send_header("Access-Control-Allow-Origin", origin)
            self.send_header("Vary", "Origin")
        self.end_headers()

    def _stream_chunk(self, raw: bytes) -> None:
        self.wfile.write(f"{len(raw):X}\r\n".encode("ascii"))
        self.wfile.write(raw)
        self.wfile.write(b"\r\n")
        self.wfile.flush()

    def _finish_json_stream(self, payload: dict[str, Any]) -> None:
        raw = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self._stream_chunk(raw)
        self.wfile.write(b"0\r\n\r\n")
        self.wfile.flush()

    def do_OPTIONS(self) -> None:  # noqa: N802
        if self.path != "/chat" or not self._origin():
            self._json(HTTPStatus.FORBIDDEN, {"error": "origin_not_allowed"})
            return
        self.send_response(HTTPStatus.NO_CONTENT)
        self.send_header("Access-Control-Allow-Origin", self._origin() or "")
        self.send_header("Access-Control-Allow-Methods", "POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Access-Control-Max-Age", "600")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self) -> None:  # noqa: N802
        if self.path == "/healthz":
            self._json(HTTPStatus.OK, {"ok": True})
        else:
            self._json(HTTPStatus.NOT_FOUND, {"error": "not_found"})

    def do_POST(self) -> None:  # noqa: N802
        request_id = uuid.uuid4().hex[:12]
        started = time.monotonic()
        if self.path != "/chat":
            self._json(HTTPStatus.NOT_FOUND, {"error": "not_found"})
            return
        origin = self.headers.get("Origin")
        if origin and origin not in ALLOWED_ORIGINS:
            self._json(HTTPStatus.FORBIDDEN, {"error": "origin_not_allowed"})
            return
        if not self.headers.get("Content-Type", "").lower().startswith("application/json"):
            self._json(HTTPStatus.UNSUPPORTED_MEDIA_TYPE, {"error": "json_required"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = 0
        if length <= 0 or length > MAX_BODY_BYTES:
            self._json(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, {"error": "request_too_large"})
            return
        try:
            data = json.loads(self.rfile.read(length))
        except (json.JSONDecodeError, UnicodeDecodeError):
            self._json(HTTPStatus.BAD_REQUEST, {"error": "invalid_json"})
            return
        message = data.get("message") if isinstance(data, dict) else None
        browser_session = data.get("sessionId") if isinstance(data, dict) else None
        if not isinstance(message, str) or not message.strip():
            self._json(HTTPStatus.BAD_REQUEST, {"error": "message_required"})
            return
        message = message.strip()
        if len(message) > MAX_MESSAGE_CHARS:
            self._json(HTTPStatus.BAD_REQUEST, {"error": "message_too_long"})
            return
        if not isinstance(browser_session, str) or not SESSION_RE.fullmatch(browser_session):
            browser_session = secrets.token_urlsafe(24)

        address = self._client_address()
        allowed, retry_after = self.server.rate_limiter.allow(address)
        visitor = self.server.rate_limiter.visitor_hash(address)
        if not allowed:
            self._json(
                HTTPStatus.TOO_MANY_REQUESTS,
                {"error": "rate_limited", "retryAfter": retry_after},
                retry_after=retry_after,
            )
            return
        if not self.server.capacity.acquire(blocking=False):
            self._json(
                HTTPStatus.SERVICE_UNAVAILABLE,
                {"error": "busy", "retryAfter": 10},
                retry_after=10,
            )
            return

        session_key = derive_session_key(self.server.secret, visitor, browser_session)
        result: queue.Queue[dict[str, Any]] = queue.Queue(maxsize=1)

        def run_request() -> None:
            try:
                with self.server.session_lock(session_key):
                    answer, sources = run_agent(message, session_key)
                result.put({"answer": answer, "sources": sources, "sessionId": browser_session})
                LOG.info(
                    "request=%s visitor=%s status=ok duration_ms=%d",
                    request_id,
                    visitor[:12],
                    int((time.monotonic() - started) * 1000),
                )
            except subprocess.TimeoutExpired:
                result.put({"error": "timeout"})
                LOG.warning("request=%s visitor=%s status=timeout", request_id, visitor[:12])
            except Exception:
                result.put({"error": "support_unavailable"})
                LOG.exception("request=%s visitor=%s status=failed", request_id, visitor[:12])
            finally:
                self.server.capacity.release()

        threading.Thread(target=run_request, daemon=True).start()
        try:
            self._start_json_stream()
            self._stream_chunk(b"\n")
            while True:
                try:
                    payload = result.get(timeout=HEARTBEAT_SECONDS)
                    break
                except queue.Empty:
                    self._stream_chunk(b"\n")
            self._finish_json_stream(payload)
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            LOG.info("request=%s visitor=%s status=client_disconnected", request_id, visitor[:12])


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    secret = load_secret()
    server = SupportServer((HOST, PORT), secret)
    LOG.info("listening host=%s port=%d agent=%s", HOST, PORT, AGENT_ID)
    server.serve_forever()


if __name__ == "__main__":
    main()
