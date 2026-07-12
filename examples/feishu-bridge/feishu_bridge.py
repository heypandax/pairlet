"""
feishu-bridge — the first cc-pocket bridge adapter (issue #91), reference / PoC quality.

Wiring:  Feishu event long-connection (im.message.receive_v1)
           → filter @mentions of the bot in allowed chats
           → PocketBridge.ask(key=chat+thread, workdir, prompt)   [open + prompt + await turn.done]
           → reply the final text back into the Feishu thread.

This replaces the user's `claude -p --dangerously-skip-permissions` bash loop (issue #91's motivation)
with the SAME UX but: a persistent daemon, real session follow-up, and — the point — dangerous actions
still prompt the OWNER's phone for allow/deny. The bridge cannot approve anything.

It is intentionally small. Slack / Telegram / DingTalk adapters are the same file with a different
event source and reply call; only PocketBridge is shared.

Run:  see README.md. Needs `lark-oapi` for the Feishu side; the cc-pocket side needs no Feishu at all,
so you can smoke-test PocketBridge on its own (see the __main__ block).
"""
from __future__ import annotations

import asyncio
import os
import sys

from pocket_client import Credential, PocketBridge

# ---- config (env) ----
FEISHU_APP_ID = os.environ.get("FEISHU_APP_ID", "")
FEISHU_APP_SECRET = os.environ.get("FEISHU_APP_SECRET", "")
CREDENTIAL_PATH = os.environ.get("POCKET_CREDENTIAL", "bridge-credential.json")
# which workdir a chat maps to. For a real deployment, route by chat_id; here one default from the
# credential's first allow-listed root keeps the example one-liner.
DEFAULT_WORKDIR = os.environ.get("POCKET_WORKDIR", "")


def _pick_workdir(cred: Credential) -> str:
    if DEFAULT_WORKDIR:
        return DEFAULT_WORKDIR
    if cred.workdirs:
        return cred.workdirs[0]
    raise SystemExit("no workdir: set POCKET_WORKDIR or ensure the credential lists one")


async def main():
    cred = Credential.load(CREDENTIAL_PATH)
    workdir = _pick_workdir(cred)
    bridge = PocketBridge(cred)
    print(f"[bridge] connecting to {cred.relay} as \"{cred.name}\" …")
    await bridge.connect()
    print(f"[bridge] connected. sessions will open under: {workdir}")

    # Feishu side — imported lazily so `python feishu_bridge.py --selftest` works without lark-oapi
    import lark_oapi as lark
    from lark_oapi.api.im.v1 import (
        P2ImMessageReceiveV1, CreateMessageRequest, CreateMessageRequestBody,
        ReplyMessageRequest, ReplyMessageRequestBody,
    )
    import json as _json

    client = lark.Client.builder().app_id(FEISHU_APP_ID).app_secret(FEISHU_APP_SECRET).build()
    loop = asyncio.get_running_loop()

    def on_message(data: P2ImMessageReceiveV1):
        msg = data.event.message
        # only group @mentions of the bot (advisory allow-list — the real firewall is daemon-side)
        mentions = msg.mentions or []
        if not any(m.key for m in mentions):
            return
        try:
            content = _json.loads(msg.content)
            text = content.get("text", "").strip()
        except Exception:
            return
        # strip the @bot placeholder tokens Feishu injects (@_user_1 …)
        for m in mentions:
            text = text.replace(m.key, "").strip()
        if not text:
            return
        chat_id = msg.chat_id
        root_id = msg.root_id or msg.message_id  # thread the reply
        key = f"{chat_id}:{root_id}"

        async def handle():
            print(f"[bridge] {key}: {text[:80]}")
            reply = await bridge.ask(key=key, workdir=workdir, prompt=text)
            req = (ReplyMessageRequest.builder().message_id(msg.message_id)
                   .request_body(ReplyMessageRequestBody.builder()
                                 .content(_json.dumps({"text": reply}))
                                 .msg_type("text").build()).build())
            client.im.v1.message.reply(req)

        asyncio.run_coroutine_threadsafe(handle(), loop)

    handler = lark.EventDispatcherHandler.builder("", "").register_p2_im_message_receive_v1(on_message).build()
    ws = lark.ws.Client(FEISHU_APP_ID, FEISHU_APP_SECRET, event_handler=handler, log_level=lark.LogLevel.INFO)
    # lark's ws client is blocking; run it in a thread so our asyncio loop keeps servicing the daemon link
    await loop.run_in_executor(None, ws.start)


async def _selftest():
    """No Feishu: open a session and send one prompt straight through, to prove the cc-pocket link.
    Usage:  python feishu_bridge.py --selftest "run git status"  """
    cred = Credential.load(CREDENTIAL_PATH)
    workdir = _pick_workdir(cred)
    bridge = PocketBridge(cred)
    await bridge.connect()
    prompt = sys.argv[2] if len(sys.argv) > 2 else "Say hello and tell me the current directory."
    print(await bridge.ask(key="selftest", workdir=workdir, prompt=prompt))


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        asyncio.run(_selftest())
    else:
        asyncio.run(main())
