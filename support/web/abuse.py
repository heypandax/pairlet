#!/usr/bin/env python3
"""Persistent abuse controls and short-lived anonymous support passes."""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import sqlite3
import threading
import time
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable


PASS_TOKEN_RE = re.compile(r"^[A-Za-z0-9_-]{20,900}\.[A-Za-z0-9_-]{40,100}$")
TURNSTILE_VERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify"


def _b64url_encode(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def _b64url_decode(value: str) -> bytes:
    padding = "=" * ((4 - len(value) % 4) % 4)
    return base64.urlsafe_b64decode(value + padding)


def _session_digest(session_id: str) -> str:
    return hashlib.sha256(b"cc-pocket-support-session-v1\0" + session_id.encode("utf-8")).hexdigest()


def derive_secret(master_secret: bytes, purpose: str) -> bytes:
    """Derive a domain-separated key without exposing the deployment master secret."""

    if len(master_secret) < 32:
        raise ValueError("master_secret must contain at least 32 bytes")
    if not purpose or not purpose.isascii():
        raise ValueError("purpose must be non-empty ASCII")
    return hmac.new(
        master_secret,
        b"cc-pocket-support-derived-v1\0" + purpose.encode("ascii"),
        hashlib.sha256,
    ).digest()


@dataclass(frozen=True)
class QuotaDecision:
    allowed: bool
    retry_after: int = 0
    reason: str = "ok"
    reservation_id: str | None = None


@dataclass(frozen=True)
class SupportPassClaims:
    pass_id: str
    expires_at: int


class AbuseStore:
    """SQLite-backed IP/pass quotas plus a global request and token budget.

    Only HMAC visitor identifiers and aggregate usage are stored. Raw IP addresses,
    questions, App context, and model answers never enter this database.
    """

    def __init__(
        self,
        path: str,
        secret: bytes,
        *,
        minute_limit: int,
        day_limit: int,
        pass_minute_limit: int,
        pass_lifetime_limit: int,
        challenge_minute_limit: int,
        challenge_global_minute_limit: int,
        global_minute_limit: int,
        global_day_limit: int,
        daily_token_budget: int,
        token_reserve: int,
    ) -> None:
        if len(secret) < 32:
            raise ValueError("abuse-store secret must contain at least 32 bytes")
        limits = (
            ("minute_limit", minute_limit),
            ("day_limit", day_limit),
            ("pass_minute_limit", pass_minute_limit),
            ("pass_lifetime_limit", pass_lifetime_limit),
            ("challenge_minute_limit", challenge_minute_limit),
            ("challenge_global_minute_limit", challenge_global_minute_limit),
            ("global_minute_limit", global_minute_limit),
            ("global_day_limit", global_day_limit),
            ("daily_token_budget", daily_token_budget),
            ("token_reserve", token_reserve),
        )
        maxima = {
            "minute_limit": 1_000,
            "day_limit": 10_000,
            "pass_minute_limit": 100,
            "pass_lifetime_limit": 1_000,
            "challenge_minute_limit": 1_000,
            "challenge_global_minute_limit": 10_000,
            "global_minute_limit": 10_000,
            "global_day_limit": 100_000,
            "daily_token_budget": 100_000_000,
            "token_reserve": 1_000_000,
        }
        for name, value in limits:
            if value <= 0:
                raise ValueError(f"{name} must be positive")
            if value > maxima[name]:
                raise ValueError(f"{name} is unreasonably large")
        if token_reserve > daily_token_budget:
            raise ValueError("token_reserve cannot exceed daily_token_budget")
        self._secret = secret
        self._minute_limit = minute_limit
        self._day_limit = day_limit
        self._pass_minute_limit = pass_minute_limit
        self._pass_lifetime_limit = pass_lifetime_limit
        self._challenge_minute_limit = challenge_minute_limit
        self._challenge_global_minute_limit = challenge_global_minute_limit
        self._global_minute_limit = global_minute_limit
        self._global_day_limit = global_day_limit
        self._daily_token_budget = daily_token_budget
        self._token_reserve = token_reserve
        self._lock = threading.Lock()
        if path != ":memory:":
            parent = Path(path).expanduser().resolve().parent
            parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            os.chmod(parent, 0o700)
        self._db = sqlite3.connect(path, timeout=5, check_same_thread=False, isolation_level=None)
        self._db.execute("PRAGMA busy_timeout=5000")
        self._db.execute("PRAGMA journal_mode=WAL")
        self._db.execute("PRAGMA synchronous=NORMAL")
        self._create_schema()
        if path != ":memory:":
            try:
                os.chmod(path, 0o600)
            except FileNotFoundError:
                pass

    def _create_schema(self) -> None:
        self._db.executescript(
            """
            CREATE TABLE IF NOT EXISTS rate_events (
                visitor_hash TEXT NOT NULL,
                occurred_at INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS rate_events_visitor_time
                ON rate_events(visitor_hash, occurred_at);
            CREATE TABLE IF NOT EXISTS visitor_days (
                day INTEGER NOT NULL,
                visitor_hash TEXT NOT NULL,
                requests INTEGER NOT NULL,
                PRIMARY KEY(day, visitor_hash)
            );
            CREATE TABLE IF NOT EXISTS passes (
                pass_id TEXT PRIMARY KEY,
                visitor_hash TEXT NOT NULL,
                session_digest TEXT NOT NULL,
                expires_at INTEGER NOT NULL,
                requests INTEGER NOT NULL DEFAULT 0
            );
            CREATE INDEX IF NOT EXISTS passes_expiry ON passes(expires_at);
            CREATE TABLE IF NOT EXISTS pass_events (
                pass_id TEXT NOT NULL,
                occurred_at INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS pass_events_pass_time
                ON pass_events(pass_id, occurred_at);
            CREATE TABLE IF NOT EXISTS challenge_events (
                visitor_hash TEXT NOT NULL,
                occurred_at INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS challenge_events_time
                ON challenge_events(occurred_at);
            CREATE TABLE IF NOT EXISTS turnstile_tokens (
                token_hash TEXT PRIMARY KEY,
                expires_at INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS global_days (
                day INTEGER PRIMARY KEY,
                requests INTEGER NOT NULL,
                reserved_tokens INTEGER NOT NULL,
                used_tokens INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS reservations (
                reservation_id TEXT PRIMARY KEY,
                day INTEGER NOT NULL,
                reserved_tokens INTEGER NOT NULL
            );
            """
        )

    def visitor_hash(self, address: str, *, now: float | None = None) -> str:
        day = int(time.time() if now is None else now) // 86_400
        return hmac.new(
            self._secret,
            b"cc-pocket-support-visitor-v1\0"
            + str(day).encode("ascii")
            + b"\0"
            + address.encode("utf-8"),
            hashlib.sha256,
        ).hexdigest()

    def reserve(
        self,
        visitor_hash: str,
        *,
        pass_id: str | None = None,
        session_id: str | None = None,
        now: float | None = None,
    ) -> QuotaDecision:
        current = int(time.time() if now is None else now)
        day = current // 86_400
        day_retry = max(1, (day + 1) * 86_400 - current)
        with self._lock:
            self._db.execute("BEGIN IMMEDIATE")
            try:
                self._db.execute("DELETE FROM rate_events WHERE occurred_at <= ?", (current - 60,))
                self._db.execute("DELETE FROM visitor_days WHERE day < ?", (day - 2,))
                self._db.execute("DELETE FROM pass_events WHERE occurred_at <= ?", (current - 60,))
                self._db.execute("DELETE FROM passes WHERE expires_at <= ?", (current,))
                self._db.execute("DELETE FROM global_days WHERE day < ?", (day - 2,))
                self._db.execute("DELETE FROM reservations WHERE day < ?", (day - 2,))

                minute_row = self._db.execute(
                    "SELECT COUNT(*), MIN(occurred_at) FROM rate_events "
                    "WHERE visitor_hash = ? AND occurred_at > ?",
                    (visitor_hash, current - 60),
                ).fetchone()
                minute_count = int(minute_row[0])
                if minute_count >= self._minute_limit:
                    oldest = int(minute_row[1] or current)
                    self._db.execute("ROLLBACK")
                    return QuotaDecision(False, max(1, 60 - (current - oldest)), "visitor_minute")
                global_minute_count = int(
                    self._db.execute(
                        "SELECT COUNT(*) FROM rate_events WHERE occurred_at > ?",
                        (current - 60,),
                    ).fetchone()[0]
                )
                if global_minute_count >= self._global_minute_limit:
                    self._db.execute("ROLLBACK")
                    return QuotaDecision(False, 60, "global_minute")

                visitor_row = self._db.execute(
                    "SELECT requests FROM visitor_days WHERE day = ? AND visitor_hash = ?",
                    (day, visitor_hash),
                ).fetchone()
                visitor_requests = int(visitor_row[0]) if visitor_row else 0
                if visitor_requests >= self._day_limit:
                    self._db.execute("ROLLBACK")
                    return QuotaDecision(False, day_retry, "visitor_day")

                global_row = self._db.execute(
                    "SELECT requests, reserved_tokens, used_tokens FROM global_days WHERE day = ?",
                    (day,),
                ).fetchone() or (0, 0, 0)
                global_requests, reserved_tokens, used_tokens = map(int, global_row)
                if global_requests >= self._global_day_limit:
                    self._db.execute("ROLLBACK")
                    return QuotaDecision(False, day_retry, "global_day")
                if reserved_tokens + used_tokens + self._token_reserve > self._daily_token_budget:
                    self._db.execute("ROLLBACK")
                    return QuotaDecision(False, day_retry, "global_tokens")

                if pass_id:
                    if not session_id:
                        self._db.execute("ROLLBACK")
                        return QuotaDecision(False, 0, "invalid_pass")
                    pass_row = self._db.execute(
                        "SELECT requests, expires_at FROM passes WHERE pass_id = ? AND visitor_hash = ? "
                        "AND session_digest = ? AND expires_at > ?",
                        (pass_id, visitor_hash, _session_digest(session_id), current),
                    ).fetchone()
                    if not pass_row:
                        self._db.execute("ROLLBACK")
                        return QuotaDecision(False, 0, "invalid_pass")
                    if int(pass_row[0]) >= self._pass_lifetime_limit:
                        self._db.execute("ROLLBACK")
                        return QuotaDecision(
                            False, max(1, int(pass_row[1]) - current), "pass_lifetime"
                        )
                    pass_minute = int(
                        self._db.execute(
                            "SELECT COUNT(*) FROM pass_events WHERE pass_id = ? AND occurred_at > ?",
                            (pass_id, current - 60),
                        ).fetchone()[0]
                    )
                    if pass_minute >= self._pass_minute_limit:
                        self._db.execute("ROLLBACK")
                        return QuotaDecision(False, 60, "pass_minute")

                reservation_id = secrets.token_urlsafe(18)
                self._db.execute(
                    "INSERT INTO rate_events(visitor_hash, occurred_at) VALUES (?, ?)",
                    (visitor_hash, current),
                )
                self._db.execute(
                    "INSERT INTO visitor_days(day, visitor_hash, requests) VALUES (?, ?, 1) "
                    "ON CONFLICT(day, visitor_hash) DO UPDATE SET requests = requests + 1",
                    (day, visitor_hash),
                )
                if pass_id:
                    self._db.execute(
                        "INSERT INTO pass_events(pass_id, occurred_at) VALUES (?, ?)",
                        (pass_id, current),
                    )
                    self._db.execute(
                        "UPDATE passes SET requests = requests + 1 WHERE pass_id = ?",
                        (pass_id,),
                    )
                self._db.execute(
                    "INSERT INTO global_days(day, requests, reserved_tokens, used_tokens) "
                    "VALUES (?, 1, ?, 0) ON CONFLICT(day) DO UPDATE SET "
                    "requests = requests + 1, reserved_tokens = reserved_tokens + excluded.reserved_tokens",
                    (day, self._token_reserve),
                )
                self._db.execute(
                    "INSERT INTO reservations(reservation_id, day, reserved_tokens) VALUES (?, ?, ?)",
                    (reservation_id, day, self._token_reserve),
                )
                self._db.execute("COMMIT")
                return QuotaDecision(True, reservation_id=reservation_id)
            except Exception:
                self._db.execute("ROLLBACK")
                raise

    def reserve_challenge(
        self,
        visitor_hash: str,
        *,
        now: float | None = None,
    ) -> QuotaDecision:
        current = int(time.time() if now is None else now)
        with self._lock:
            self._db.execute("BEGIN IMMEDIATE")
            try:
                self._db.execute("DELETE FROM challenge_events WHERE occurred_at <= ?", (current - 60,))
                visitor_count = int(
                    self._db.execute(
                        "SELECT COUNT(*) FROM challenge_events WHERE visitor_hash = ? AND occurred_at > ?",
                        (visitor_hash, current - 60),
                    ).fetchone()[0]
                )
                global_count = int(
                    self._db.execute(
                        "SELECT COUNT(*) FROM challenge_events WHERE occurred_at > ?",
                        (current - 60,),
                    ).fetchone()[0]
                )
                if visitor_count >= self._challenge_minute_limit:
                    self._db.execute("ROLLBACK")
                    return QuotaDecision(False, 60, "challenge_visitor_minute")
                if global_count >= self._challenge_global_minute_limit:
                    self._db.execute("ROLLBACK")
                    return QuotaDecision(False, 60, "challenge_global_minute")
                self._db.execute(
                    "INSERT INTO challenge_events(visitor_hash, occurred_at) VALUES (?, ?)",
                    (visitor_hash, current),
                )
                self._db.execute("COMMIT")
                return QuotaDecision(True)
            except Exception:
                self._db.execute("ROLLBACK")
                raise

    def register_verified_pass(
        self,
        turnstile_token: str,
        pass_id: str,
        visitor_hash: str,
        session_id: str,
        expires_at: int,
        *,
        now: float | None = None,
    ) -> bool:
        current = int(time.time() if now is None else now)
        if expires_at <= current:
            raise ValueError("cannot register an expired support pass")
        with self._lock:
            self._db.execute("BEGIN IMMEDIATE")
            try:
                self._db.execute("DELETE FROM passes WHERE expires_at <= ?", (current,))
                self._db.execute("DELETE FROM turnstile_tokens WHERE expires_at <= ?", (current,))
                token_hash = hmac.new(
                    self._secret,
                    b"cc-pocket-support-turnstile-token-v1\0" + turnstile_token.encode("utf-8"),
                    hashlib.sha256,
                ).hexdigest()
                if self._db.execute(
                    "SELECT 1 FROM turnstile_tokens WHERE token_hash = ?", (token_hash,)
                ).fetchone():
                    self._db.execute("ROLLBACK")
                    return False
                self._db.execute(
                    "INSERT INTO turnstile_tokens(token_hash, expires_at) VALUES (?, ?)",
                    (token_hash, current + 600),
                )
                self._db.execute(
                    "INSERT INTO passes(pass_id, visitor_hash, session_digest, expires_at, requests) "
                    "VALUES (?, ?, ?, ?, 0)",
                    (pass_id, visitor_hash, _session_digest(session_id), expires_at),
                )
                self._db.execute("COMMIT")
                return True
            except Exception:
                self._db.execute("ROLLBACK")
                raise

    def is_registered_pass(
        self,
        pass_id: str,
        visitor_hash: str,
        session_id: str,
        *,
        now: float | None = None,
    ) -> bool:
        current = int(time.time() if now is None else now)
        with self._lock:
            row = self._db.execute(
                "SELECT 1 FROM passes WHERE pass_id = ? AND visitor_hash = ? "
                "AND session_digest = ? AND expires_at > ?",
                (pass_id, visitor_hash, _session_digest(session_id), current),
            ).fetchone()
        return row is not None

    def complete(self, reservation_id: str, actual_tokens: int | None) -> None:
        with self._lock:
            self._db.execute("BEGIN IMMEDIATE")
            try:
                row = self._db.execute(
                    "SELECT day, reserved_tokens FROM reservations WHERE reservation_id = ?",
                    (reservation_id,),
                ).fetchone()
                if not row:
                    self._db.execute("ROLLBACK")
                    return
                day, reserved = map(int, row)
                charged = reserved if actual_tokens is None else max(0, min(int(actual_tokens), 10_000_000))
                self._db.execute(
                    "UPDATE global_days SET reserved_tokens = MAX(0, reserved_tokens - ?), "
                    "used_tokens = used_tokens + ? WHERE day = ?",
                    (reserved, charged, day),
                )
                self._db.execute(
                    "DELETE FROM reservations WHERE reservation_id = ?",
                    (reservation_id,),
                )
                self._db.execute("COMMIT")
            except Exception:
                self._db.execute("ROLLBACK")
                raise

    def snapshot(self, *, now: float | None = None) -> dict[str, int]:
        day = int(time.time() if now is None else now) // 86_400
        with self._lock:
            row = self._db.execute(
                "SELECT requests, reserved_tokens, used_tokens FROM global_days WHERE day = ?",
                (day,),
            ).fetchone() or (0, 0, 0)
        return {
            "day": day,
            "requests": int(row[0]),
            "requestLimit": self._global_day_limit,
            "reservedTokens": int(row[1]),
            "usedTokens": int(row[2]),
            "tokenBudget": self._daily_token_budget,
        }

    def close(self) -> None:
        with self._lock:
            self._db.close()


def issue_support_pass(
    secret: bytes,
    *,
    ttl_seconds: int,
    now: float | None = None,
) -> tuple[str, SupportPassClaims]:
    if not 1 <= ttl_seconds <= 86_400:
        raise ValueError("ttl_seconds is outside the allowed range")
    current = int(time.time() if now is None else now)
    pass_id = secrets.token_urlsafe(18)
    payload = {
        "v": 1,
        "jti": pass_id,
        "kid": "v1",
        "iat": current,
        "exp": current + ttl_seconds,
    }
    encoded = _b64url_encode(
        json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
    )
    signature = hmac.new(
        secret,
        b"cc-pocket-support-pass-v1\0" + encoded.encode("ascii"),
        hashlib.sha256,
    ).digest()
    return f"{encoded}.{_b64url_encode(signature)}", SupportPassClaims(pass_id, payload["exp"])


def verify_support_pass(
    token: Any,
    secret: bytes,
    *,
    max_ttl_seconds: int,
    now: float | None = None,
) -> SupportPassClaims | None:
    if not isinstance(token, str) or not PASS_TOKEN_RE.fullmatch(token):
        return None
    encoded, supplied_signature = token.split(".", 1)
    expected_signature = hmac.new(
        secret,
        b"cc-pocket-support-pass-v1\0" + encoded.encode("ascii"),
        hashlib.sha256,
    ).digest()
    try:
        signature = _b64url_decode(supplied_signature)
        payload = json.loads(_b64url_decode(encoded))
    except (ValueError, UnicodeDecodeError, json.JSONDecodeError):
        return None
    if not hmac.compare_digest(signature, expected_signature) or not isinstance(payload, dict):
        return None
    current = int(time.time() if now is None else now)
    issued_at = payload.get("iat")
    expires_at = payload.get("exp")
    pass_id = payload.get("jti")
    if (
        payload.get("v") != 1
        or set(payload) != {"v", "kid", "jti", "iat", "exp"}
        or payload.get("kid") != "v1"
        or type(issued_at) is not int
        or type(expires_at) is not int
        or not isinstance(pass_id, str)
        or not 16 <= len(pass_id) <= 64
        or issued_at > current + 60
        or expires_at <= current
        or expires_at <= issued_at
        or expires_at - issued_at > max_ttl_seconds
    ):
        return None
    return SupportPassClaims(pass_id, expires_at)


class TurnstileUnavailable(RuntimeError):
    pass


def verify_turnstile(
    token: Any,
    secret_key: str,
    remote_ip: str,
    *,
    allowed_hostnames: set[str],
    expected_action: str,
    timeout_seconds: float = 8,
    now: float | None = None,
    opener: Callable[..., Any] = urllib.request.urlopen,
) -> bool:
    if not isinstance(token, str) or not 20 <= len(token) <= 2048:
        return False
    body = urllib.parse.urlencode(
        {"secret": secret_key, "response": token, "remoteip": remote_ip}
    ).encode("ascii")
    request = urllib.request.Request(
        TURNSTILE_VERIFY_URL,
        data=body,
        headers={"Content-Type": "application/x-www-form-urlencoded"},
        method="POST",
    )
    try:
        with opener(request, timeout=timeout_seconds) as response:
            raw = response.read(16_385)
    except Exception as exc:
        raise TurnstileUnavailable("Turnstile verification failed") from exc
    if len(raw) > 16_384:
        raise TurnstileUnavailable("Turnstile response is too large")
    try:
        result = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise TurnstileUnavailable("Turnstile returned invalid JSON") from exc
    if not isinstance(result, dict):
        return False
    challenge_ts = result.get("challenge_ts")
    if not isinstance(challenge_ts, str) or len(challenge_ts) > 64:
        return False
    try:
        challenged_at = datetime.fromisoformat(challenge_ts.replace("Z", "+00:00"))
        if challenged_at.tzinfo is None:
            return False
        challenged_at = challenged_at.astimezone(timezone.utc).timestamp()
    except (ValueError, OverflowError):
        return False
    current = time.time() if now is None else now
    return bool(
        result.get("success") is True
        and result.get("hostname") in allowed_hostnames
        and result.get("action") == expected_action
        and current - 360 <= challenged_at <= current + 60
    )
