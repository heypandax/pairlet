"""
A minimal cc-pocket BRIDGE client (issue #91) — the reusable half of any adapter.

It does exactly what a headless bridge credential is allowed to do:
  redeem a one-time ticket → open the zero-knowledge E2E channel to the daemon over the relay →
  session.open / prompt → collect the streamed reply → turn.done.

It is a faithful port of the daemon/app wire protocol:
  * relay device login: DeviceHello over WSS /v1/device, then an opaque BINARY data plane.
  * E2E: P-256 ECDH + HKDF(HMAC-SHA256) + AES-256-GCM, Noise-KK-style 4-DH with the pairing
    ticket folded in as the PSK on the first handshake (see protocol/e2e/E2ESession.kt).
  * framing (protocol/e2e/Wire.kt): inner payload = [type:1][body]; transport body =
    [8-byte BE counter][ciphertext‖tag]; nonce = 0x00000000 ‖ counter; AAD = "ccpocket-e2e-v1".

Security note: this client can ONLY open sessions (under the credential's allow-listed workdirs),
send prompts, and read its own sessions' replies. Permission prompts are routed to the OWNER's phone
by the daemon; a bridge cannot see or answer them. The daemon enforces all of that server-side — this
client's good behavior is not what makes it safe.

Deps: `pip install websockets cryptography aiohttp` (see requirements.txt).

NOT production-hardened (issue #91 design review). Two known gaps to port before relying on this:
  * the reaped-session RESUME path is dead code — `_convos` is never evicted, so the `resumeId` branch
    is unreachable and a reaped topic answers `session_gone` forever until restart.
  * NO relay reconnect — `_reader` just ends on a dropped ws and the process stays alive but deaf.
The daemon's built-in engine (daemon/.../feishu/FeishuEngine.kt) handles both; this is the wire demo.
"""
from __future__ import annotations

import asyncio
import base64
import json
import os
import struct
from dataclasses import dataclass, field
from typing import Awaitable, Callable, Optional

import aiohttp
import websockets
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes, serialization

HANDSHAKE = 1
TRANSPORT = 2
AAD = b"ccpocket-e2e-v1"
PROLOGUE = b"ccpocket-e2e-v1"
KEYINFO = b"ccpocket-e2e-keys-v1"


def b64u(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def b64u_dec(s: str) -> bytes:
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


# ---- E2E session (mirror of protocol/e2e/E2ESession.kt, initiator side) ----

class E2ESession:
    def __init__(self, send_key: bytes, recv_key: bytes):
        self._send = AESGCM(send_key)
        self._recv = AESGCM(recv_key)
        self._send_ctr = 0
        self._recv_ctr = -1

    @staticmethod
    def _nonce(ctr: int) -> bytes:
        return b"\x00\x00\x00\x00" + struct.pack(">Q", ctr)

    def seal(self, plaintext: bytes) -> bytes:
        ctr = self._send_ctr
        self._send_ctr += 1
        return struct.pack(">Q", ctr) + self._send.encrypt(self._nonce(ctr), plaintext, AAD)

    def open(self, frame: bytes) -> Optional[bytes]:
        if len(frame) < 8 + 16:
            return None
        ctr = struct.unpack(">Q", frame[:8])[0]
        if ctr <= self._recv_ctr:
            return None
        pt = self._recv.decrypt(self._nonce(ctr), frame[8:], AAD)
        self._recv_ctr = ctr
        return pt


def _raw_pub(pub: ec.EllipticCurvePublicKey) -> bytes:
    return pub.public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)


# ---- the bridge client ----

@dataclass
class Credential:
    """The JSON blob printed by `cc-pocket-daemon pair --headless`."""
    name: str
    accountId: str
    daemonPub: str
    ticket: str
    relay: str            # e.g. wss://pocket.ark-nexus.cc
    workdirs: list

    @staticmethod
    def load(path: str) -> "Credential":
        with open(path) as f:
            d = json.load(f)
        return Credential(d["name"], d["accountId"], d["daemonPub"], d["ticket"], d["relay"], d.get("workdirs", []))


@dataclass
class _Convo:
    convo_id: str
    text_parts: list = field(default_factory=list)
    done: asyncio.Event = field(default_factory=asyncio.Event)
    final_text: Optional[str] = None
    error: Optional[str] = None
    session_id: Optional[str] = None


class PocketBridge:
    """One E2E link to the daemon. Redeem once, then open/prompt as many sessions as the caps allow."""

    def __init__(self, cred: Credential, device_state_path: str = ".pocket-device.json"):
        self.cred = cred
        self._state_path = device_state_path
        self._ws = None
        self._session: Optional[E2ESession] = None
        self._next_id = 0
        self._convos: dict[str, _Convo] = {}      # convoId -> state
        self._by_key: dict[str, str] = {}         # caller key (e.g. chat/thread) -> convoId
        self._session_of: dict[str, str] = {}     # convoId -> sessionId (for resume)
        self._hooks: list = []                    # transient SessionLive watchers used by _open
        self._open_locks: dict[str, asyncio.Lock] = {}   # workdir -> lock (see _open)

    def _http_base(self) -> str:
        return self.cred.relay.replace("wss://", "https://").replace("ws://", "http://")

    async def _redeem(self) -> tuple[str, str]:
        """POST /v1/pair/redeem with headless=true. Cached to disk — the ticket is single-use."""
        if os.path.exists(self._state_path):
            with open(self._state_path) as f:
                d = json.load(f)
            return d["deviceId"], d["credential"]
        # generate the E2E static keypair for THIS device and register its pub at redeem
        self._device_static = ec.generate_private_key(ec.SECP256R1())
        dev_pub = _raw_pub(self._device_static.public_key())
        async with aiohttp.ClientSession() as s:
            async with s.post(
                f"{self._http_base()}/v1/pair/redeem",
                json={"ticket": self.cred.ticket, "devicePubKey": b64u(dev_pub), "headless": True},
            ) as r:
                body = await r.json()
                if "credential" not in body:
                    raise RuntimeError(f"redeem failed: {body}")
        # atomic 0600 create (no world-readable window): the file holds the device private key + bearer
        # credential — same sensitivity as the daemon's identity.json
        fd = os.open(self._state_path, os.O_CREAT | os.O_WRONLY | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            json.dump({"deviceId": body["deviceId"], "credential": body["credential"],
                       "devicePriv": b64u(self._device_static.private_numbers().private_value.to_bytes(32, "big"))}, f)
        return body["deviceId"], body["credential"]

    def _load_device_static(self):
        with open(self._state_path) as f:
            d = json.load(f)
        priv_int = int.from_bytes(b64u_dec(d["devicePriv"]), "big")
        return ec.derive_private_key(priv_int, ec.SECP256R1())

    async def connect(self):
        device_id, credential = await self._redeem()
        static = self._load_device_static()
        self._ws = await websockets.connect(f"{self.cred.relay}/v1/device", max_size=4 * 1024 * 1024)
        await self._ws.send(json.dumps({"id": "h", "ts": 0, "to": "RELAY",
                                        "body": {"t": "pocket/device.hello", "deviceId": device_id, "secret": credential}}))
        # await Attached
        while True:
            msg = await self._ws.recv()
            if isinstance(msg, bytes):
                continue
            body = json.loads(msg).get("body", {})
            if body.get("t") == "pocket/attached":
                break
            if body.get("t") == "pocket/auth.error":
                raise RuntimeError(f"relay auth failed: {body}")
        # E2E handshake (ticket as PSK on this first one)
        daemon_pub = b64u_dec(self.cred.daemonPub)
        eph_pub, finish = _static_initiator(static, daemon_pub, self.cred.ticket.encode())
        await self._ws.send(_wire(HANDSHAKE, eph_pub))
        while True:
            msg = await self._ws.recv()
            if isinstance(msg, (bytes, bytearray)) and msg and msg[0] == HANDSHAKE:
                self._session = finish(msg[1:])
                break
        asyncio.create_task(self._reader())

    async def _reader(self):
        async for msg in self._ws:
            if not isinstance(msg, (bytes, bytearray)) or not msg or msg[0] != TRANSPORT:
                continue
            plain = self._session.open(bytes(msg[1:]))
            if plain is None:
                continue
            self._on_frame(json.loads(plain.decode())["body"])

    def _on_frame(self, body: dict):
        # transient watchers (e.g. _open waiting for its SessionLive) see every frame first
        for h in list(self._hooks):
            try:
                h(body)
            except Exception:
                pass
        t = body.get("t")
        cid = body.get("convoId")
        c = self._convos.get(cid) if cid else None
        if t == "pocket/session.live" and c:
            if body.get("sessionId"):
                c.session_id = body["sessionId"]
                self._session_of[cid] = body["sessionId"]
        elif t == "pocket/chunk" and c:
            piece = body.get("piece", {})
            if piece.get("t") == "text":
                c.text_parts.append(piece.get("text", ""))
        elif t == "pocket/turn.done" and c:
            c.final_text = body.get("finalText") or "".join(c.text_parts)
            c.error = body.get("error")
            c.done.set()
        elif t == "pocket/error":
            # a bridge_* code means a cap refused us — surface it on the waiting convo if attributable
            if c:
                c.error = f"{body.get('code')}: {body.get('message')}"
                c.done.set()
            else:
                print(f"[pocket] error {body.get('code')}: {body.get('message')}")
        elif t == "pocket/session.gone" and c:
            c.error = "session_gone"
            c.done.set()

    async def _send(self, body: dict):
        self._next_id += 1
        env = json.dumps({"id": str(self._next_id), "ts": 0, "body": body}).encode()
        await self._ws.send(_wire(TRANSPORT, self._session.seal(env)))

    async def ask(self, key: str, workdir: str, prompt: str, timeout: float = 300.0) -> str:
        """Open (or resume) the session mapped to [key], send [prompt], return the final reply text.

        [key] is the adapter's conversation identity — e.g. f"{chat_id}:{thread_id}" — so a follow-up
        in the same thread continues the same cc-pocket session (issue #3's "no session follow-up" fix).
        """
        convo_id = await self._open(key, workdir)
        c = self._convos[convo_id]
        c.text_parts.clear(); c.done.clear(); c.final_text = None; c.error = None
        prompt_id = f"{convo_id}-{self._next_id}"
        await self._send({"t": "pocket/prompt", "convoId": convo_id, "text": prompt, "promptId": prompt_id})
        try:
            await asyncio.wait_for(c.done.wait(), timeout)
        except asyncio.TimeoutError:
            return "⏱️ timed out waiting for the reply — the session is kept; try again."
        if c.error:
            return f"⚠️ {c.error}"
        return c.final_text or "(no reply)"

    async def _open(self, key: str, workdir: str) -> str:
        # already have a live convo for this key? reuse it (follow-up in the same thread)
        existing = self._by_key.get(key)
        if existing and existing in self._convos:
            return existing
        # SessionLive is correlated by workdir alone, so two concurrent opens on the SAME workdir would
        # both match the first one back and collide onto one convoId — two chats bound to one project
        # (or two threads in one chat) would silently share a session. Serialize per workdir; the
        # daemon mints one convo per open, so one waiter per workdir is enough to keep them distinct.
        lock = self._open_locks.setdefault(workdir, asyncio.Lock())
        async with lock:
            existing = self._by_key.get(key)  # may have been opened while we waited for the lock
            if existing and existing in self._convos:
                return existing
            # resume our own prior session if we learned its id, else a fresh open
            resume = self._session_of.get(existing) if existing else None
            live = asyncio.Event()
            got = {"id": None}

            def hook(body):
                if body.get("t") == "pocket/session.live" and body.get("workdir") == workdir and got["id"] is None:
                    got["id"] = body["convoId"]
                    live.set()

            self._hooks.append(hook)
            try:
                await self._send({"t": "pocket/session.open", "workdir": workdir, **({"resumeId": resume} if resume else {})})
                # a refused open (bridge_busy / bad workdir) never yields SessionLive — time out rather
                # than wait forever, and drop the hook either way so it can't match a later open's reply.
                await asyncio.wait_for(live.wait(), 30.0)
            finally:
                self._hooks.remove(hook)
            cid = got["id"]
            self._convos[cid] = _Convo(convo_id=cid)
            self._by_key[key] = cid
            return cid


def _wire(kind: int, body: bytes) -> bytes:
    return bytes([kind]) + body


def _static_initiator(static_priv: ec.EllipticCurvePrivateKey, daemon_pub_raw: bytes, psk: bytes):
    """Like initiator_handshake but reuses the device's PERSISTED static key (reconnects authenticate
    by static keys; the PSK only binds the first handshake)."""
    static_pub = _raw_pub(static_priv.public_key())
    eph = ec.generate_private_key(ec.SECP256R1())
    eph_pub = _raw_pub(eph.public_key())
    daemon_pub = ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), daemon_pub_raw)

    def finish(responder_eph_raw: bytes) -> E2ESession:
        resp_eph = ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), responder_eph_raw)
        es = eph.exchange(ec.ECDH(), daemon_pub)
        ss = static_priv.exchange(ec.ECDH(), daemon_pub)
        ee = eph.exchange(ec.ECDH(), resp_eph)
        se = static_priv.exchange(ec.ECDH(), resp_eph)
        transcript = PROLOGUE + static_pub + daemon_pub_raw + eph_pub + responder_eph_raw
        okm = HKDF(algorithm=hashes.SHA256(), length=64, salt=transcript, info=KEYINFO).derive(es + ss + ee + se + psk)
        return E2ESession(send_key=okm[:32], recv_key=okm[32:])

    return eph_pub, finish
