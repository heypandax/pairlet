#!/usr/bin/env python3
"""Minimal public HTTP boundary for the isolated CC Pocket OpenClaw support agent."""

from __future__ import annotations

import hashlib
import hmac
import ipaddress
import json
import logging
import os
import queue
import re
import secrets
import subprocess
import threading
import time
import urllib.parse
import uuid
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

from abuse import (
    AbuseStore,
    TurnstileUnavailable,
    derive_secret,
    issue_support_pass,
    verify_support_pass,
    verify_turnstile,
)


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
PASS_MINUTE_LIMIT = int(os.environ.get("CC_SUPPORT_PASS_MINUTE_LIMIT", "4"))
PASS_LIFETIME_LIMIT = int(os.environ.get("CC_SUPPORT_PASS_LIFETIME_LIMIT", "10"))
GLOBAL_MINUTE_LIMIT = int(os.environ.get("CC_SUPPORT_GLOBAL_MINUTE_LIMIT", "12"))
GLOBAL_DAY_LIMIT = int(os.environ.get("CC_SUPPORT_GLOBAL_DAY_LIMIT", "100"))
DAILY_TOKEN_BUDGET = int(os.environ.get("CC_SUPPORT_DAILY_TOKEN_BUDGET", "1000000"))
TOKEN_RESERVE = int(os.environ.get("CC_SUPPORT_TOKEN_RESERVE", "128000"))
CHALLENGE_MINUTE_LIMIT = int(os.environ.get("CC_SUPPORT_CHALLENGE_MINUTE_LIMIT", "5"))
CHALLENGE_GLOBAL_MINUTE_LIMIT = int(
    os.environ.get("CC_SUPPORT_CHALLENGE_GLOBAL_MINUTE_LIMIT", "40")
)
MAX_CONCURRENT = int(os.environ.get("CC_SUPPORT_MAX_CONCURRENT", "3"))
MAX_CONNECTIONS = int(os.environ.get("CC_SUPPORT_MAX_CONNECTIONS", "32"))
MAX_TURNSTILE_CONCURRENT = int(os.environ.get("CC_SUPPORT_MAX_TURNSTILE_CONCURRENT", "4"))
AGENT_TIMEOUT_SECONDS = int(os.environ.get("CC_SUPPORT_AGENT_TIMEOUT", "120"))
HEARTBEAT_SECONDS = float(os.environ.get("CC_SUPPORT_HEARTBEAT_SECONDS", "10"))
SOCKET_TIMEOUT_SECONDS = float(os.environ.get("CC_SUPPORT_SOCKET_TIMEOUT", "15"))
ABUSE_DB_PATH = os.environ.get(
    "CC_SUPPORT_ABUSE_DB", "/var/lib/cc-pocket-support-api/abuse.sqlite3"
)
TURNSTILE_SITE_KEY = os.environ.get("CC_SUPPORT_TURNSTILE_SITE_KEY", "").strip()
TURNSTILE_SECRET_KEY = os.environ.get("CC_SUPPORT_TURNSTILE_SECRET_KEY", "").strip()
TURNSTILE_ACTION = "support_chat"
TURNSTILE_ALLOWED_HOSTNAMES = {
    item.strip().lower()
    for item in os.environ.get(
        "CC_SUPPORT_TURNSTILE_HOSTNAMES", "pocket.ark-nexus.cc,heypandax.github.io"
    ).split(",")
    if item.strip()
}
TURNSTILE_REQUIRED = os.environ.get("CC_SUPPORT_REQUIRE_TURNSTILE", "0") == "1"
TURNSTILE_TEST_SITE_KEYS = {
    "1x00000000000000000000AA",
    "2x00000000000000000000AB",
    "1x00000000000000000000BB",
    "2x00000000000000000000BB",
    "3x00000000000000000000FF",
}
TURNSTILE_TEST_SECRET_KEYS = {
    "1x0000000000000000000000000000000AA",
    "2x0000000000000000000000000000000AA",
    "3x0000000000000000000000000000000AA",
}
PASS_TTL_SECONDS = int(os.environ.get("CC_SUPPORT_PASS_TTL", "1800"))
SESSION_RETENTION_DAYS = int(os.environ.get("CC_SUPPORT_SESSION_RETENTION_DAYS", "30"))

SESSION_RE = re.compile(r"^[A-Za-z0-9_-]{16,64}$")
URL_RE = re.compile(r"https://[^\s<>\])}]+")
CONTEXT_TOKEN_RE = re.compile(r"^[A-Za-z0-9 ._()·/-]+$")
CONTEXT_MODEL_RE = re.compile(r"^[A-Za-z0-9._:+/@-]+$")
CONTEXT_VERSION_RE = re.compile(r"^[A-Za-z0-9._+-]+$")
CONTEXT_SCREENS = {"chat", "projects", "sessions", "settings"}
CONTEXT_AGENTS = {"claude", "codex", "opencode"}
CONTEXT_STATES = {"idle", "generating", "observing", "disconnected"}
CONTEXT_CONTROLS = {
    "composer": "message composer",
    "quick_actions": "quick actions menu",
    "changed_files": "changed files",
    "terminal": "terminal",
    "model_picker": "model picker",
}
INTERNAL_NARRATION_RE = re.compile(
    r"(?:"
    r"看起来(?:手册|文档|结果)|搜索结果|检索结果|"
    r"候选.{0,40}(?:捕获|记录|检索)|现在给出(?:用户)?答案|"
    r"(?:score|canonical).{0,40}(?:最高|命中|结果)|"
    r"(?:已经|现在).{0,20}足够回答|不需要再(?:查看|检查|搜索)(?:代码|手册)|"
    r"I (?:found|searched|retrieved|now have)|"
    r"(?:search|retrieval) results?|enough evidence|"
    r"tool (?:call|output)|internal (?:file|prompt|policy)"
    r")",
    re.IGNORECASE,
)
LOG = logging.getLogger("cc-pocket-support-web")


def load_secret() -> bytes:
    value = os.environ.get("CC_SUPPORT_WEB_SECRET", "")
    if len(value) < 32:
        raise RuntimeError("CC_SUPPORT_WEB_SECRET must contain at least 32 characters")
    return value.encode("utf-8")


def validate_runtime_config() -> None:
    if bool(TURNSTILE_SITE_KEY) != bool(TURNSTILE_SECRET_KEY):
        raise RuntimeError("Turnstile site and secret keys must be configured together")
    if TURNSTILE_REQUIRED and not TURNSTILE_SITE_KEY:
        raise RuntimeError("CC_SUPPORT_REQUIRE_TURNSTILE=1 requires Turnstile keys")
    if not TURNSTILE_ALLOWED_HOSTNAMES:
        raise RuntimeError("at least one Turnstile hostname is required")
    if TURNSTILE_SITE_KEY and not 3 <= len(TURNSTILE_SITE_KEY) <= 256:
        raise RuntimeError("Turnstile site key length is invalid")
    if TURNSTILE_SECRET_KEY and not 8 <= len(TURNSTILE_SECRET_KEY) <= 256:
        raise RuntimeError("Turnstile secret key length is invalid")
    if TURNSTILE_SITE_KEY in TURNSTILE_TEST_SITE_KEYS:
        raise RuntimeError("Cloudflare Turnstile test site keys are forbidden in production")
    if TURNSTILE_SECRET_KEY in TURNSTILE_TEST_SECRET_KEYS:
        raise RuntimeError("Cloudflare Turnstile test secret keys are forbidden in production")
    if not 60 <= PASS_TTL_SECONDS <= 3_600:
        raise RuntimeError("CC_SUPPORT_PASS_TTL must be between 60 and 3600 seconds")
    if not 1 <= MAX_CONCURRENT <= 16:
        raise RuntimeError("CC_SUPPORT_MAX_CONCURRENT must be between 1 and 16")
    if not MAX_CONCURRENT <= MAX_CONNECTIONS <= 128:
        raise RuntimeError("CC_SUPPORT_MAX_CONNECTIONS is outside the allowed range")
    if not 1 <= MAX_TURNSTILE_CONCURRENT <= 16:
        raise RuntimeError("CC_SUPPORT_MAX_TURNSTILE_CONCURRENT is outside the allowed range")
    if not 1_024 <= MAX_BODY_BYTES <= 65_536:
        raise RuntimeError("CC_SUPPORT_MAX_BODY_BYTES is outside the allowed range")
    if not 1 <= MAX_MESSAGE_CHARS <= 8_000:
        raise RuntimeError("CC_SUPPORT_MAX_MESSAGE_CHARS is outside the allowed range")
    if not 10 <= AGENT_TIMEOUT_SECONDS <= 300:
        raise RuntimeError("CC_SUPPORT_AGENT_TIMEOUT is outside the allowed range")
    if not 1 <= HEARTBEAT_SECONDS <= 30:
        raise RuntimeError("CC_SUPPORT_HEARTBEAT_SECONDS is outside the allowed range")
    if not 2 <= SOCKET_TIMEOUT_SECONDS <= 60:
        raise RuntimeError("CC_SUPPORT_SOCKET_TIMEOUT is outside the allowed range")
    if not 1 <= SESSION_RETENTION_DAYS <= 30:
        raise RuntimeError("CC_SUPPORT_SESSION_RETENTION_DAYS must be between 1 and 30")


def derive_session_key(secret: bytes, visitor_hash: str, browser_session: str) -> str:
    digest = hmac.new(
        secret,
        f"{visitor_hash}:{browser_session}".encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()[:40]
    return f"agent:{AGENT_ID}:web-{digest}"


def quota_address(address: str) -> str:
    """Use one IPv6 /64 as a visitor bucket; preserve canonical IPv4 addresses."""

    parsed = ipaddress.ip_address(address)
    if isinstance(parsed, ipaddress.IPv6Address):
        if parsed.ipv4_mapped:
            return parsed.ipv4_mapped.compressed
        network = ipaddress.ip_network(f"{parsed.compressed}/64", strict=False)
        return f"{network.network_address.compressed}/64"
    return parsed.compressed


def _safe_context_string(value: Any, pattern: re.Pattern[str], maximum: int) -> str | None:
    if not isinstance(value, str):
        return None
    cleaned = value.strip()[:maximum]
    return cleaned if cleaned and pattern.fullmatch(cleaned) else None


def sanitize_app_context(value: Any) -> dict[str, Any] | None:
    """Allow only the small, non-sensitive App environment schema.

    The public caller controls this JSON, so every field is treated as client-reported metadata and
    constrained to an allowlist before it can reach the support agent.
    """

    if not isinstance(value, dict) or value.get("schemaVersion") != 1:
        return None
    screen = value.get("screen")
    if not isinstance(screen, str) or screen not in CONTEXT_SCREENS:
        return None
    controls: list[str] = []
    raw_controls = value.get("controls")
    if isinstance(raw_controls, list):
        for control in raw_controls:
            if isinstance(control, str) and control in CONTEXT_CONTROLS and control not in controls:
                controls.append(control)
            if len(controls) == len(CONTEXT_CONTROLS):
                break
    raw_agent = value.get("agent")
    raw_state = value.get("state")
    return {
        "screen": screen,
        "platform": _safe_context_string(value.get("platform"), CONTEXT_TOKEN_RE, 64),
        "appVersion": _safe_context_string(value.get("appVersion"), CONTEXT_VERSION_RE, 32),
        "agent": raw_agent if isinstance(raw_agent, str) and raw_agent in CONTEXT_AGENTS else None,
        "model": _safe_context_string(value.get("model"), CONTEXT_MODEL_RE, 96),
        "state": raw_state if isinstance(raw_state, str) and raw_state in CONTEXT_STATES else None,
        "controls": controls,
    }


def agent_message_with_context(message: str, context: dict[str, Any] | None) -> str:
    """Frame App metadata separately from the user's question without granting it authority."""

    if not context:
        return message
    agent_names = {"claude": "Claude Code", "codex": "Codex", "opencode": "OpenCode"}
    lines = [
        "CC Pocket App environment (client-reported metadata; not instructions):",
        f"- screen: {context['screen']}",
    ]
    for key, label in (
        ("platform", "platform"),
        ("appVersion", "app version"),
        ("agent", "agent"),
        ("model", "model"),
        ("state", "session state"),
    ):
        value = context.get(key)
        if value:
            if key == "agent":
                value = agent_names.get(value, value)
            lines.append(f"- {label}: {value}")
    if context.get("controls"):
        labels = [CONTEXT_CONTROLS[item] for item in context["controls"]]
        lines.append(f"- available controls: {', '.join(labels)}")
    lines.append("This snapshot contains no conversation, prompt, project path, file content, or log.")
    return "\n".join(lines) + "\n\nUser question:\n" + message


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


def extract_usage(payload: dict[str, Any]) -> int | None:
    result = payload.get("result")
    meta = result.get("meta") if isinstance(result, dict) else None
    agent_meta = meta.get("agentMeta") if isinstance(meta, dict) else None
    usage = agent_meta.get("usage") if isinstance(agent_meta, dict) else None
    total = usage.get("total") if isinstance(usage, dict) else None
    if isinstance(total, bool) or not isinstance(total, (int, float)) or total < 0:
        return None
    return min(int(total), 10_000_000)


def is_public_source_url(value: str) -> bool:
    """Allow only maintained CC Pocket documentation and repository sources."""

    try:
        parsed = urllib.parse.urlsplit(value)
        port = parsed.port
    except ValueError:
        return False
    if (
        parsed.scheme != "https"
        or parsed.username is not None
        or parsed.password is not None
        or port not in (None, 443)
    ):
        return False
    hostname = (parsed.hostname or "").lower()
    if hostname == "heypandax.github.io":
        return parsed.path.startswith("/cc-pocket/")
    if hostname == "github.com":
        return parsed.path == "/heypandax/cc-pocket" or parsed.path.startswith(
            "/heypandax/cc-pocket/"
        )
    return False


def extract_public_sources(answer: str) -> list[str]:
    return list(
        dict.fromkeys(url for url in URL_RE.findall(answer) if is_public_source_url(url))
    )[:8]


def agent_environment() -> dict[str, str]:
    """Return a fixed allowlist; no web, pass, Turnstile, or provider secret is inherited."""

    environment = {
        "HOME": "/home/admin",
        "USER": "admin",
        "LOGNAME": "admin",
        "PATH": "/usr/local/bin:/usr/bin:/bin",
        "TMPDIR": "/tmp",
    }
    for name in ("LANG", "LC_ALL", "TZ"):
        value = os.environ.get(name)
        if value:
            environment[name] = value
    return environment


def run_agent(message: str, session_key: str) -> tuple[str, list[str], int | None]:
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
    sources = extract_public_sources(answer)
    return answer, sources, extract_usage(payload)


class SupportServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(
        self,
        address: tuple[str, int],
        master_secret: bytes,
        *,
        abuse_store: AbuseStore | None = None,
        turnstile_site_key: str | None = None,
        turnstile_secret_key: str | None = None,
    ) -> None:
        self.session_secret = derive_secret(master_secret, "session-key")
        self.pass_secret = derive_secret(master_secret, "anonymous-pass")
        self.turnstile_site_key = (
            TURNSTILE_SITE_KEY if turnstile_site_key is None else turnstile_site_key
        )
        self.turnstile_secret_key = (
            TURNSTILE_SECRET_KEY if turnstile_secret_key is None else turnstile_secret_key
        )
        if bool(self.turnstile_site_key) != bool(self.turnstile_secret_key):
            raise RuntimeError("Turnstile site and secret keys must be configured together")
        self.turnstile_enabled = bool(self.turnstile_site_key and self.turnstile_secret_key)
        self._owns_abuse_store = abuse_store is None
        self._abuse_store_closed = False
        if abuse_store is None:
            self.abuse_store = AbuseStore(
                ABUSE_DB_PATH,
                derive_secret(master_secret, "abuse-store"),
                minute_limit=MINUTE_LIMIT,
                day_limit=DAY_LIMIT,
                pass_minute_limit=PASS_MINUTE_LIMIT,
                pass_lifetime_limit=PASS_LIFETIME_LIMIT,
                challenge_minute_limit=CHALLENGE_MINUTE_LIMIT,
                challenge_global_minute_limit=CHALLENGE_GLOBAL_MINUTE_LIMIT,
                global_minute_limit=GLOBAL_MINUTE_LIMIT,
                global_day_limit=GLOBAL_DAY_LIMIT,
                daily_token_budget=DAILY_TOKEN_BUDGET,
                token_reserve=TOKEN_RESERVE,
            )
        else:
            self.abuse_store = abuse_store

        try:
            self.capacity = threading.BoundedSemaphore(MAX_CONCURRENT)
            self.turnstile_capacity = threading.BoundedSemaphore(MAX_TURNSTILE_CONCURRENT)
            self.connection_capacity = threading.BoundedSemaphore(MAX_CONNECTIONS)
            self._session_locks: dict[str, threading.Lock] = {}
            self._session_locks_guard = threading.Lock()

            # TCPServer creates its socket outside the bind/activate cleanup block, and
            # that block calls our public server_close(), whose cleanup could mask the
            # original error.  Own the whole construction boundary here instead.
            super().__init__(address, SupportHandler, bind_and_activate=False)
            self.server_bind()
            self.server_activate()
        except BaseException:
            self._cleanup_failed_construction()
            raise

    def session_lock(self, key: str) -> threading.Lock:
        with self._session_locks_guard:
            if len(self._session_locks) > 2_000:
                self._session_locks.clear()
            return self._session_locks.setdefault(key, threading.Lock())

    def process_request(self, request: Any, client_address: Any) -> None:
        if not self.connection_capacity.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            self.connection_capacity.release()
            raise

    def process_request_thread(self, request: Any, client_address: Any) -> None:
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.connection_capacity.release()

    def _close_owned_abuse_store(self, *, suppress_errors: bool = False) -> None:
        if not self._owns_abuse_store or self._abuse_store_closed:
            return
        self._abuse_store_closed = True
        try:
            self.abuse_store.close()
        except BaseException:
            if not suppress_errors:
                raise

    def _cleanup_failed_construction(self) -> None:
        if hasattr(self, "socket"):
            try:
                super().server_close()
            except BaseException:
                pass
        self._close_owned_abuse_store(suppress_errors=True)

    def server_close(self) -> None:
        try:
            super().server_close()
        except BaseException:
            self._close_owned_abuse_store(suppress_errors=True)
            raise
        self._close_owned_abuse_store()


class SupportHandler(BaseHTTPRequestHandler):
    server: SupportServer
    protocol_version = "HTTP/1.1"

    def setup(self) -> None:
        super().setup()
        self.connection.settimeout(SOCKET_TIMEOUT_SECONDS)

    def log_message(self, _format: str, *_args: object) -> None:
        return

    def _origin(self) -> str | None:
        origin = self.headers.get("Origin")
        return origin if origin in ALLOWED_ORIGINS else None

    def _client_address(self) -> str:
        try:
            peer = ipaddress.ip_address(self.client_address[0])
        except ValueError:
            return "invalid-peer"
        forwarded = self.headers.get("X-Forwarded-For", "").strip()
        if peer.is_loopback and forwarded and "," not in forwarded and len(forwarded) <= 64:
            try:
                parsed = ipaddress.ip_address(forwarded)
                if isinstance(parsed, ipaddress.IPv6Address) and parsed.ipv4_mapped:
                    return parsed.ipv4_mapped.compressed
                return parsed.compressed
            except ValueError:
                pass
        if isinstance(peer, ipaddress.IPv6Address) and peer.ipv4_mapped:
            return peer.ipv4_mapped.compressed
        return peer.compressed

    def _json(self, status: int, payload: dict[str, Any], *, retry_after: int = 0) -> None:
        raw = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
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
        self.send_header("Referrer-Policy", "no-referrer")
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
        elif self.path == "/config":
            turnstile: dict[str, Any] = {"enabled": self.server.turnstile_enabled}
            if self.server.turnstile_enabled:
                turnstile.update(
                    {"siteKey": self.server.turnstile_site_key, "action": TURNSTILE_ACTION}
                )
            self._json(
                HTTPStatus.OK,
                {
                    "turnstile": turnstile,
                    "sessionRetentionDays": SESSION_RETENTION_DAYS,
                },
            )
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
        except (TimeoutError, OSError):
            self._json(HTTPStatus.REQUEST_TIMEOUT, {"error": "request_timeout"})
            return
        except (json.JSONDecodeError, UnicodeDecodeError):
            self._json(HTTPStatus.BAD_REQUEST, {"error": "invalid_json"})
            return
        message = data.get("message") if isinstance(data, dict) else None
        browser_session = data.get("sessionId") if isinstance(data, dict) else None
        app_context = sanitize_app_context(data.get("context")) if isinstance(data, dict) else None
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
        visitor = self.server.abuse_store.visitor_hash(quota_address(address))
        issued_pass: str | None = None
        pass_id: str | None = None

        if self.server.turnstile_enabled:
            claims = verify_support_pass(
                data.get("supportPass"),
                self.server.pass_secret,
                max_ttl_seconds=PASS_TTL_SECONDS,
            )
            if claims:
                try:
                    if self.server.abuse_store.is_registered_pass(
                        claims.pass_id, visitor, browser_session
                    ):
                        pass_id = claims.pass_id
                except Exception:
                    LOG.exception("request=%s status=abuse_store_failed phase=pass_lookup", request_id)
                    self._json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": "support_unavailable"})
                    return

            if not pass_id:
                challenge_token = data.get("turnstileToken")
                if not isinstance(challenge_token, str):
                    self._json(
                        HTTPStatus.FORBIDDEN,
                        {"error": "human_verification_required"},
                    )
                    return
                try:
                    challenge_quota = self.server.abuse_store.reserve_challenge(visitor)
                except Exception:
                    LOG.exception("request=%s status=abuse_store_failed phase=challenge", request_id)
                    self._json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": "support_unavailable"})
                    return
                if not challenge_quota.allowed:
                    self._json(
                        HTTPStatus.TOO_MANY_REQUESTS,
                        {
                            "error": "verification_rate_limited",
                            "retryAfter": challenge_quota.retry_after,
                        },
                        retry_after=challenge_quota.retry_after,
                    )
                    return
                if not self.server.turnstile_capacity.acquire(blocking=False):
                    self._json(
                        HTTPStatus.SERVICE_UNAVAILABLE,
                        {"error": "verification_unavailable", "retryAfter": 10},
                        retry_after=10,
                    )
                    return
                try:
                    verified = verify_turnstile(
                        challenge_token,
                        self.server.turnstile_secret_key,
                        address,
                        allowed_hostnames=TURNSTILE_ALLOWED_HOSTNAMES,
                        expected_action=TURNSTILE_ACTION,
                    )
                except TurnstileUnavailable:
                    self._json(
                        HTTPStatus.SERVICE_UNAVAILABLE,
                        {"error": "verification_unavailable", "retryAfter": 10},
                        retry_after=10,
                    )
                    return
                finally:
                    self.server.turnstile_capacity.release()
                if not verified:
                    self._json(HTTPStatus.FORBIDDEN, {"error": "human_verification_failed"})
                    return
                issued_pass, new_claims = issue_support_pass(
                    self.server.pass_secret,
                    ttl_seconds=PASS_TTL_SECONDS,
                )
                try:
                    registered = self.server.abuse_store.register_verified_pass(
                        challenge_token,
                        new_claims.pass_id,
                        visitor,
                        browser_session,
                        new_claims.expires_at,
                    )
                except Exception:
                    LOG.exception("request=%s status=abuse_store_failed phase=pass_issue", request_id)
                    self._json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": "support_unavailable"})
                    return
                if not registered:
                    self._json(HTTPStatus.FORBIDDEN, {"error": "human_verification_failed"})
                    return
                pass_id = new_claims.pass_id

        if not self.server.capacity.acquire(blocking=False):
            payload: dict[str, Any] = {"error": "busy", "retryAfter": 10}
            if issued_pass:
                payload["supportPass"] = issued_pass
            self._json(
                HTTPStatus.SERVICE_UNAVAILABLE,
                payload,
                retry_after=10,
            )
            return

        try:
            quota = self.server.abuse_store.reserve(
                visitor,
                pass_id=pass_id,
                session_id=browser_session if pass_id else None,
            )
        except Exception:
            self.server.capacity.release()
            LOG.exception("request=%s status=abuse_store_failed phase=reserve", request_id)
            self._json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": "support_unavailable"})
            return
        if not quota.allowed:
            self.server.capacity.release()
            if quota.reason == "invalid_pass":
                self._json(HTTPStatus.FORBIDDEN, {"error": "human_verification_required"})
                return
            error = (
                "daily_budget_exhausted"
                if quota.reason in {"global_day", "global_tokens"}
                else "rate_limited"
            )
            payload = {"error": error, "retryAfter": quota.retry_after}
            if issued_pass:
                payload["supportPass"] = issued_pass
            LOG.warning("request=%s status=limited reason=%s", request_id, quota.reason)
            self._json(
                HTTPStatus.TOO_MANY_REQUESTS,
                payload,
                retry_after=quota.retry_after,
            )
            return

        if not quota.reservation_id:
            self.server.capacity.release()
            self._json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": "support_unavailable"})
            return

        session_key = derive_session_key(self.server.session_secret, visitor, browser_session)
        agent_message = agent_message_with_context(message, app_context)
        result: queue.Queue[dict[str, Any]] = queue.Queue(maxsize=1)

        def run_request() -> None:
            actual_tokens: int | None = None
            try:
                with self.server.session_lock(session_key):
                    answer, sources, actual_tokens = run_agent(agent_message, session_key)
                payload = {"answer": answer, "sources": sources, "sessionId": browser_session}
                if issued_pass:
                    payload["supportPass"] = issued_pass
                result.put(payload)
                LOG.info(
                    "request=%s status=ok duration_ms=%d tokens=%s",
                    request_id,
                    int((time.monotonic() - started) * 1000),
                    actual_tokens if actual_tokens is not None else "reserved",
                )
            except subprocess.TimeoutExpired:
                payload = {"error": "timeout"}
                if issued_pass:
                    payload["supportPass"] = issued_pass
                result.put(payload)
                LOG.warning("request=%s status=timeout", request_id)
            except Exception:
                payload = {"error": "support_unavailable"}
                if issued_pass:
                    payload["supportPass"] = issued_pass
                result.put(payload)
                LOG.exception("request=%s status=failed", request_id)
            finally:
                try:
                    self.server.abuse_store.complete(quota.reservation_id, actual_tokens)
                except Exception:
                    LOG.exception("request=%s status=abuse_store_failed phase=complete", request_id)
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
            LOG.info("request=%s status=client_disconnected", request_id)


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    validate_runtime_config()
    secret = load_secret()
    server = SupportServer((HOST, PORT), secret)
    LOG.info(
        "listening host=%s port=%d agent=%s turnstile=%s",
        HOST,
        PORT,
        AGENT_ID,
        "enabled" if server.turnstile_enabled else "disabled",
    )
    server.serve_forever()


if __name__ == "__main__":
    main()
