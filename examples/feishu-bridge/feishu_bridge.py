"""
feishu-bridge — the first cc-pocket bridge adapter (issue #91), reference / PoC quality.

Wiring:  Feishu event long-connection (im.message.receive_v1)
           → filter @mentions of the bot in BOUND chats
           → PocketBridge.ask(key=chat+thread, workdir=the chat's project, prompt)
           → reply the final text back into the Feishu thread.

This replaces the user's `claude -p --dangerously-skip-permissions` bash loop (issue #91's motivation)
with the SAME UX but: a persistent daemon, real session follow-up, and — the point — dangerous actions
still prompt the OWNER's phone for allow/deny. The bridge cannot approve anything.

Many chats, many projects: each chat is bound to ONE project (`/bind <project>`, owner only), and the
binding happens in the chat itself — nobody has to discover a chat_id or edit a mapping file. See
routes.py. Unbound chats are ignored, so being dragged into an unrelated group is harmless.

It is intentionally small. Slack / Telegram / DingTalk adapters are the same file with a different
event source and reply call; only PocketBridge and Routes are shared.

Run:  see README.md. Needs `lark-oapi` for the Feishu side; the cc-pocket side needs no Feishu at all,
so you can smoke-test PocketBridge on its own (see the __main__ block).

SUPERSEDED for Feishu: the daemon now ships a BUILT-IN feishu engine (no python) — that is the
production path (create it from the desktop/phone Bridges page). Keep this as a wire reference and a
starting point for other IMs. PoC gap beyond pocket_client's resume/reconnect: reply() below does not
check `resp.success()` or retry, so a transient Feishu API error silently drops the answer.
"""
from __future__ import annotations

import asyncio
import os
import sys

from pocket_client import Credential, PocketBridge
from routes import Routes, project_name, resolve_project

# ---- config (env) ----
FEISHU_APP_ID = os.environ.get("FEISHU_APP_ID", "")
FEISHU_APP_SECRET = os.environ.get("FEISHU_APP_SECRET", "")
CREDENTIAL_PATH = os.environ.get("POCKET_CREDENTIAL", "bridge-credential.json")
ROUTES_PATH = os.environ.get("POCKET_ROUTES", ".pocket-routes.json")
# Only this Feishu user may /bind or /unbind. Everyone else in the chat can still talk to the bot in a
# chat the owner already bound — binding is what's privileged, not using.
ADMIN_OPEN_ID = os.environ.get("FEISHU_ADMIN_OPEN_ID", "").strip()
# --selftest only: which workdir to use when there is no chat to route by.
DEFAULT_WORKDIR = os.environ.get("POCKET_WORKDIR", "")

HELP = (
    "用法：\n"
    "  @机器人 <你的需求>      在本群绑定的项目下干活\n"
    "  @机器人 /projects       列出可绑定的项目\n"
    "  @机器人 /bind <项目>    把本群绑到某个项目（仅管理员）\n"
    "  @机器人 /unbind         解绑本群（仅管理员）"
)


def _pick_workdir(cred: Credential) -> str:
    """--selftest's workdir. The live bot routes per chat instead (see _handle_command)."""
    if DEFAULT_WORKDIR:
        return DEFAULT_WORKDIR
    if cred.workdirs:
        return cred.workdirs[0]
    raise SystemExit("no workdir: set POCKET_WORKDIR or ensure the credential lists one")


def _projects_text(cred: Credential, routes: Routes) -> str:
    lines = ["可绑定的项目："]
    for w in cred.workdirs:
        bound = len(routes.chats_for(w))
        suffix = f"（已绑 {bound} 个群）" if bound else ""
        lines.append(f"  • {project_name(w)}{suffix}")
    lines.append("\n绑定：@机器人 /bind <项目名>")
    return "\n".join(lines)


def _handle_command(text: str, chat_id: str, sender: str, cred: Credential, routes: Routes) -> str | None:
    """Returns a reply for a /command, or None if [text] isn't one (i.e. it's a prompt).

    Binding is gated on ADMIN_OPEN_ID. When that's unset we refuse and echo the caller's own open_id,
    because that id is otherwise undiscoverable — this is the one-step bootstrap for a fresh install.
    """
    if not text.startswith("/"):
        return None
    parts = text[1:].split(maxsplit=1)
    cmd = parts[0].lower() if parts else ""
    arg = parts[1].strip() if len(parts) > 1 else ""

    if cmd in ("help", "?"):
        return HELP
    if cmd == "projects":
        return _projects_text(cred, routes)
    if cmd in ("bind", "unbind"):
        if not ADMIN_OPEN_ID:
            return (f"未设置管理员，拒绝绑定。\n你的 open_id 是：{sender}\n"
                    f"把它设为 FEISHU_ADMIN_OPEN_ID 后重启机器人，即可用 /bind。")
        if sender != ADMIN_OPEN_ID:
            return "只有管理员可以绑定/解绑本群。"
        if cmd == "unbind":
            return "已解绑，本群不再响应。" if routes.unbind(chat_id) else "本群本来就没有绑定项目。"
        workdir = resolve_project(arg, cred.workdirs)
        if not workdir:
            return f"找不到项目「{arg}」。\n\n{_projects_text(cred, routes)}"
        routes.bind(chat_id, workdir)
        return f"✅ 本群已绑定项目「{project_name(workdir)}」。\n@我说话就会在该项目下执行；危险操作仍会弹到主人手机审批。"
    return f"未知命令 /{cmd}\n\n{HELP}"


async def main():
    cred = Credential.load(CREDENTIAL_PATH)
    routes = Routes(ROUTES_PATH)
    bridge = PocketBridge(cred)
    print(f"[bridge] connecting to {cred.relay} as \"{cred.name}\" …")
    await bridge.connect()
    print(f"[bridge] connected. projects: {[project_name(w) for w in cred.workdirs]}")
    print(f"[bridge] {len(routes)} bound chat(s) from {ROUTES_PATH}")
    if not ADMIN_OPEN_ID:
        print("[bridge] FEISHU_ADMIN_OPEN_ID unset — /bind is disabled until you set it "
              "(send the bot /bind in a chat; it will tell you your open_id).")

    # Feishu side — imported lazily so `python feishu_bridge.py --selftest` works without lark-oapi
    import lark_oapi as lark
    from lark_oapi.api.im.v1 import (
        P2ImMessageReceiveV1, ReplyMessageRequest, ReplyMessageRequestBody,
    )
    import json as _json

    client = lark.Client.builder().app_id(FEISHU_APP_ID).app_secret(FEISHU_APP_SECRET).build()
    loop = asyncio.get_running_loop()

    def reply(message_id: str, text: str):
        req = (ReplyMessageRequest.builder().message_id(message_id)
               .request_body(ReplyMessageRequestBody.builder()
                             .content(_json.dumps({"text": text}))
                             .msg_type("text").build()).build())
        client.im.v1.message.reply(req)

    def on_message(data: P2ImMessageReceiveV1):
        msg = data.event.message
        # only @mentions of the bot (advisory filter — the real firewall is daemon-side)
        mentions = msg.mentions or []
        if not any(m.key for m in mentions):
            return
        try:
            content = _json.loads(msg.content)
            text = content.get("text", "").strip()
        except (ValueError, AttributeError):
            return
        # strip the @bot placeholder tokens Feishu injects (@_user_1 …)
        for m in mentions:
            text = text.replace(m.key, "").strip()
        if not text:
            return
        chat_id = msg.chat_id
        try:
            sender = data.event.sender.sender_id.open_id or ""
        except AttributeError:
            sender = ""

        cmd_reply = _handle_command(text, chat_id, sender, cred, routes)
        if cmd_reply is not None:
            reply(msg.message_id, cmd_reply)
            return

        workdir = routes.workdir_for(chat_id)
        if not workdir:
            reply(msg.message_id, f"本群还没有绑定项目。\n\n{HELP}")
            return

        root_id = msg.root_id or msg.message_id  # thread the reply
        key = f"{chat_id}:{root_id}"

        async def handle():
            print(f"[bridge] {project_name(workdir)} ← {key}: {text[:80]}")
            try:
                answer = await bridge.ask(key=key, workdir=workdir, prompt=text)
            except Exception as e:  # never let one bad turn kill the event loop
                print(f"[bridge] {key} failed: {e!r}")
                answer = f"⚠️ 出错了：{e}"
            reply(msg.message_id, answer)

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
