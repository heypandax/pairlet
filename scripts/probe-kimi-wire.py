#!/usr/bin/env python3
"""probe-kimi-wire.py — Kimi Code CLI wire 协议行为探针（issue #206 KIMI 后端前置验证）。

对标 probe-claude-wire.py：在接入/升级 kimi CLI 前，回归 daemon KIMI 后端将依赖的
wire 协议行为。文档（wire 1.10）答不到的点在这里逐项实测，编号与设计文档
docs/design/kimi-backend-design.md 的「probe 待验证项」一一对应：

  V1  握手：initialize(protocol_version=1.10) 返回 server 信息与 capabilities；
      不支持时 -32601 可回退无握手模式。
  V2  会话落盘：首个 prompt 后 ~/.kimi-code/sessions/<workDirKey>/<sessionId>/
      出现 state.json + agents/main/wire.jsonl；记录 workDirKey 的真实编码
      （含 _ 与 . 的 cwd —— dirKey 编码 bug 前科）；session_index.jsonl 记录形状。
  V3  普通轮次：TurnBegin/StepBegin/ContentPart(text)/TurnEnd 事件序 +
      prompt 响应 status=finished；StatusUpdate 是否带 context_tokens/token_usage。
  V4  审批：默认权限模式下 Shell 工具触发 server→client 的 request
      {type:"ApprovalRequest"}；回 {"request_id":…,"response":"approve"} 后
      ToolCall/ToolResult 落地。记录 ApprovalRequest 的 id / tool_call_id /
      JSON-RPC id 三者关系（askId 复合键设计依据）。
  V5  steer：轮内注入消息返回 status=steered，稍后收到 SteerInput 事件（排队语义）。
  V6  cancel：在途轮次取消后 prompt 响应 status=cancelled（中断语义）。
  V7  resume 组合：`kimi --wire --session <id>` 可复用旧会话；replay 方法返回
      events>0 且事件形状与 live 一致（TranscriptReplay 可复用同一解析器的前提）。
  V8  模型面：`kimi provider catalog list` 输出形状；config.toml default_model；
      `--model` 与 `--wire` 是否可组合；wire 内是否存在换模型方法（预期没有）。
  V9  权限旁路：`--yolo` / `--auto` 与 `--wire` 组合后 ApprovalRequest 是否消失
      （预案 B 的开关验证）；plan 模式 set_plan_mode 往返。
  V10 ToolCallPart 流式参数：ToolCall 事件的 arguments 是完整串还是要拼
      ToolCallPart 片段（决定解析器要不要攒流）。

用法：
  python3 scripts/probe-kimi-wire.py               # 全量真跑（需已安装并登录 kimi）
  python3 scripts/probe-kimi-wire.py --dry-check   # 无 kimi 环境下只做脚本自检
  python3 scripts/probe-kimi-wire.py --only V4 V7  # 只跑指定验证项
  KIMI_BIN=/path/to/kimi python3 scripts/probe-kimi-wire.py

未安装 kimi 时退出码 2，并打印安装指引。
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import uuid
from pathlib import Path
from queue import Empty, Queue

PROTOCOL_VERSION = "1.10"
DEFAULT_TIMEOUT = 60.0  # 单轮上限；kimi 首 token 可能偏慢，放宽
KIMI_HOME = Path(os.environ.get("KIMI_CODE_HOME", Path.home() / ".kimi-code"))

RESULTS: dict[str, tuple[str, str]] = {}  # id -> (PASS/FAIL/SKIP, note)


def record(vid: str, ok: bool | None, note: str) -> None:
    status = "SKIP" if ok is None else ("PASS" if ok else "FAIL")
    RESULTS[vid] = (status, note)
    print(f"  [{status}] {vid}: {note}")


def find_kimi() -> str | None:
    env = os.environ.get("KIMI_BIN")
    if env and Path(env).exists():
        return env
    return shutil.which("kimi")


def install_hint() -> str:
    return (
        "kimi 未安装。安装 Kimi Code CLI（注意：目标是新版 kimi-code，不是 legacy Python kimi-cli）：\n"
        "  macOS/Linux : curl -fsSL https://code.kimi.com/kimi-code/install.sh | bash\n"
        "  Windows     : irm https://code.kimi.com/kimi-code/install.ps1 | iex\n"
        "  （legacy 备选：uv tool install --python 3.13 kimi-cli，仅当新版不可用时）\n"
        "安装后先 `kimi` 进入 TUI 用 /login 完成登录，再重跑本探针。\n"
        "也可 KIMI_BIN=/path/to/kimi 指定二进制。"
    )


# ---------------------------------------------------------------- wire client


class WireClient:
    """单进程 kimi --wire 的 JSON-RPC 客户端：行式收发 + server→client request 分发。"""

    def __init__(self, argv: list[str], cwd: Path):
        self.proc = subprocess.Popen(
            argv,
            cwd=str(cwd),
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1,
        )
        self.events: list[dict] = []            # event 通知的 params（{"type","payload"}）
        self.server_requests: list[dict] = []   # server→client request 全帧（含 id）
        self.responses: Queue = Queue()         # 对我们 request 的响应帧
        self.raw_lines: list[str] = []
        self.on_server_request = None           # callable(frame) -> result dict | None
        self._reader = threading.Thread(target=self._read_loop, daemon=True)
        self._reader.start()
        self._stderr_drain = threading.Thread(
            target=lambda: self.proc.stderr.read(), daemon=True
        )
        self._stderr_drain.start()

    def _read_loop(self) -> None:
        for line in self.proc.stdout:
            line = line.strip()
            if not line:
                continue
            self.raw_lines.append(line)
            try:
                frame = json.loads(line)
            except json.JSONDecodeError:
                continue
            if frame.get("method") == "event":
                self.events.append(frame.get("params", {}))
            elif frame.get("method") and "id" in frame:
                # server→client request（ApprovalRequest / QuestionRequest / ToolCallRequest / HookRequest）
                self.server_requests.append(frame)
                handler = self.on_server_request
                if handler:
                    result = handler(frame)
                    if result is not None:
                        self.send_raw({"jsonrpc": "2.0", "id": frame["id"], "result": result})
            elif "id" in frame and ("result" in frame or "error" in frame):
                self.responses.put(frame)

    def send_raw(self, obj: dict) -> None:
        self.proc.stdin.write(json.dumps(obj, ensure_ascii=False) + "\n")
        self.proc.stdin.flush()

    def request(self, method: str, params: dict | None = None, timeout: float = DEFAULT_TIMEOUT) -> dict:
        rid = str(uuid.uuid4())
        frame = {"jsonrpc": "2.0", "method": method, "id": rid}
        if params is not None:
            frame["params"] = params
        self.send_raw(frame)
        deadline = time.monotonic() + timeout
        stash = []
        while time.monotonic() < deadline:
            try:
                resp = self.responses.get(timeout=0.5)
            except Empty:
                if self.proc.poll() is not None:
                    raise RuntimeError(f"kimi 进程已退出（code={self.proc.returncode}）")
                continue
            if resp.get("id") == rid:
                for s in stash:
                    self.responses.put(s)
                return resp
            stash.append(resp)
        raise TimeoutError(f"{method} 响应超时（{timeout}s）")

    def events_of(self, etype: str) -> list[dict]:
        return [e for e in self.events if e.get("type") == etype]

    def close(self) -> None:
        try:
            self.proc.stdin.close()
        except Exception:
            pass
        try:
            self.proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.proc.kill()


# ---------------------------------------------------------------- verifications


def auto_approve(frame: dict) -> dict | None:
    """默认 server→client request 处理：审批一律 approve，问题选第一个选项。"""
    params = frame.get("params", {})
    ptype = params.get("type")
    payload = params.get("payload", {})
    if ptype == "ApprovalRequest":
        return {"request_id": payload.get("id"), "response": "approve"}
    if ptype == "QuestionRequest":
        # 形状待 V-question 实测；先按文档回第一个 option
        opts = payload.get("options") or []
        return {"request_id": payload.get("id"), "answer": opts[0] if opts else ""}
    return None


def v1_handshake(client: WireClient) -> dict:
    resp = client.request(
        "initialize",
        {
            "protocol_version": PROTOCOL_VERSION,
            "client": {"name": "cc-pocket-probe", "version": "0"},
            "capabilities": {"supports_question": True, "supports_plan_mode": True},
        },
        timeout=20,
    )
    if "error" in resp:
        code = resp["error"].get("code")
        record("V1", code == -32601, f"initialize 报错 code={code}（-32601=可回退无握手模式）")
        return {}
    result = resp.get("result", {})
    server = result.get("server", {})
    record(
        "V1",
        bool(result.get("protocol_version")),
        f"protocol={result.get('protocol_version')} server={server.get('name')}/{server.get('version')} "
        f"caps={result.get('capabilities')} slash_commands={len(result.get('slash_commands', []))}个",
    )
    return result


def v3_plain_turn(client: WireClient) -> None:
    resp = client.request("prompt", {"user_input": "Reply with exactly the word PONG and nothing else."})
    status = resp.get("result", {}).get("status")
    turn_begin = client.events_of("TurnBegin")
    turn_end = client.events_of("TurnEnd")
    texts = [
        e["payload"].get("text", "")
        for e in client.events_of("ContentPart")
        if e.get("payload", {}).get("type") == "text"
    ]
    status_updates = client.events_of("StatusUpdate")
    ctx = next(
        (u["payload"].get("context_tokens") for u in reversed(status_updates)
         if u.get("payload", {}).get("context_tokens") is not None),
        None,
    )
    ok = status == "finished" and turn_begin and turn_end and "PONG" in "".join(texts)
    record(
        "V3",
        bool(ok),
        f"status={status} TurnBegin={len(turn_begin)} TurnEnd={len(turn_end)} "
        f"text={''.join(texts)[:40]!r} StatusUpdate={len(status_updates)}个 context_tokens={ctx}",
    )


def v2_disk_layout(workdir: Path) -> str | None:
    """扫 sessions 目录找刚创建的会话；返回 sessionId。"""
    sessions_root = KIMI_HOME / "sessions"
    if not sessions_root.is_dir():
        record("V2", False, f"{sessions_root} 不存在")
        return None
    hits = []
    for key_dir in sessions_root.iterdir():
        for sid_dir in key_dir.iterdir() if key_dir.is_dir() else []:
            state = sid_dir / "state.json"
            wire = sid_dir / "agents" / "main" / "wire.jsonl"
            if state.exists() and state.stat().st_mtime > time.time() - 300:
                hits.append((key_dir.name, sid_dir.name, wire.exists(), state))
    if not hits:
        record("V2", False, "近 5 分钟内无新会话落盘（wire 模式可能不落盘？重大发现，记入文档）")
        return None
    key, sid, has_wire, state = hits[-1]
    try:
        state_keys = sorted(json.loads(state.read_text()).keys())
    except Exception:
        state_keys = ["<unreadable>"]
    idx = KIMI_HOME / "session_index.jsonl"
    idx_shape = "缺失"
    if idx.exists():
        lines = [l for l in idx.read_text().splitlines() if l.strip()]
        if lines:
            try:
                idx_shape = "keys=" + ",".join(sorted(json.loads(lines[-1]).keys()))
            except Exception:
                idx_shape = "尾行不可解析"
    record(
        "V2",
        has_wire,
        f"workDirKey={key!r}（cwd={workdir}）sessionId={sid} wire.jsonl={'有' if has_wire else '无'} "
        f"state.json keys={state_keys} session_index.jsonl {idx_shape}",
    )
    return sid


def v4_approval(client: WireClient) -> None:
    seen: list[dict] = []

    def handler(frame: dict):
        params = frame.get("params", {})
        if params.get("type") == "ApprovalRequest":
            seen.append({"rpc_id": frame.get("id"), "payload": params.get("payload", {})})
            return {"request_id": params["payload"].get("id"), "response": "approve"}
        return auto_approve(frame)

    client.on_server_request = handler
    resp = client.request(
        "prompt",
        {"user_input": "Use your shell tool to run exactly: echo kimi-probe-approval-ok . Do not do anything else."},
    )
    client.on_server_request = auto_approve
    status = resp.get("result", {}).get("status")
    tool_results = client.events_of("ToolResult")
    hit_output = any("kimi-probe-approval-ok" in json.dumps(e.get("payload", {})) for e in tool_results)
    if seen:
        p = seen[0]["payload"]
        record(
            "V4",
            status == "finished" and hit_output,
            f"ApprovalRequest 到达：id={p.get('id')} tool_call_id={p.get('tool_call_id')} "
            f"rpc_id={seen[0]['rpc_id']} sender={p.get('sender')}；approve 后 ToolResult 含输出={hit_output}",
        )
    else:
        record(
            "V4",
            False,
            f"未收到 ApprovalRequest（status={status}，ToolResult含输出={hit_output}）——"
            "默认模式可能自动放行 Shell？若是则审批面按预案 B 落地并记入文档",
        )


def v5_steer(client: WireClient) -> None:
    result_holder: dict = {}

    def run_prompt():
        try:
            result_holder["resp"] = client.request(
                "prompt",
                {"user_input": "Count from 1 to 5 slowly, one number per line, thinking briefly between each."},
            )
        except Exception as exc:  # noqa: BLE001
            result_holder["exc"] = exc

    t = threading.Thread(target=run_prompt, daemon=True)
    t.start()
    time.sleep(3)  # 等轮次进入在途
    try:
        steer_resp = client.request("steer", {"user_input": "Also say STEERED at the end."}, timeout=15)
        steered = steer_resp.get("result", {}).get("status") == "steered"
        err = steer_resp.get("error")
    except (TimeoutError, RuntimeError) as exc:
        steered, err = False, str(exc)
    t.join(timeout=DEFAULT_TIMEOUT)
    steer_events = client.events_of("SteerInput")
    record(
        "V5",
        steered and bool(steer_events),
        f"steer 响应 steered={steered} err={err} SteerInput 事件={len(steer_events)}个"
        + ("（轮已结束时 steer 应回 -32000，属预期分支）" if err else ""),
    )


def v6_cancel(client: WireClient) -> None:
    result_holder: dict = {}

    def run_prompt():
        try:
            result_holder["resp"] = client.request(
                "prompt", {"user_input": "Write a 500-word essay about turtles."}
            )
        except Exception as exc:  # noqa: BLE001
            result_holder["exc"] = exc

    t = threading.Thread(target=run_prompt, daemon=True)
    t.start()
    time.sleep(2)
    client.request("cancel", timeout=15)
    t.join(timeout=30)
    status = result_holder.get("resp", {}).get("result", {}).get("status")
    record("V6", status == "cancelled", f"cancel 后 prompt 响应 status={status}")


def v7_resume_replay(kimi: str, workdir: Path, session_id: str | None) -> None:
    if not session_id:
        record("V7", None, "V2 未拿到 sessionId，跳过")
        return
    client = WireClient([kimi, "--wire", "--session", session_id], workdir)
    client.on_server_request = auto_approve
    try:
        v1 = client.request(
            "initialize",
            {"protocol_version": PROTOCOL_VERSION, "client": {"name": "cc-pocket-probe", "version": "0"}},
            timeout=20,
        )
        replay = client.request("replay", timeout=30)
        r = replay.get("result", {})
        types = sorted({e.get("type") for e in client.events})
        record(
            "V7",
            r.get("status") == "finished" and (r.get("events", 0) > 0),
            f"--wire --session 组合可用（init={'ok' if 'result' in v1 else v1.get('error')}）；"
            f"replay events={r.get('events')} requests={r.get('requests')} 事件类型={types}",
        )
    except Exception as exc:  # noqa: BLE001
        record("V7", False, f"resume/replay 失败：{exc}")
    finally:
        client.close()


def v8_models(kimi: str) -> None:
    notes = []
    ok = False
    try:
        out = subprocess.run(
            [kimi, "provider", "catalog", "list"], capture_output=True, text=True, timeout=30
        )
        ok = out.returncode == 0
        notes.append(f"catalog list rc={out.returncode} 首行={out.stdout.splitlines()[:1]}")
    except Exception as exc:  # noqa: BLE001
        notes.append(f"catalog list 异常：{exc}")
    cfg = KIMI_HOME / "config.toml"
    if cfg.exists():
        dm = [l.strip() for l in cfg.read_text().splitlines() if l.strip().startswith("default_model")]
        notes.append(f"config.toml default_model 行={dm or '未设置'}")
    else:
        notes.append("config.toml 不存在")
    record("V8", ok, "；".join(notes) + "；--model 与 --wire 组合、wire 内换模型方法需人工补测")


def v9_yolo(kimi: str, workdir: Path) -> None:
    client = WireClient([kimi, "--wire", "--yolo"], workdir)
    approvals: list[dict] = []

    def handler(frame: dict):
        params = frame.get("params", {})
        if params.get("type") == "ApprovalRequest":
            approvals.append(params)
            return {"request_id": params["payload"].get("id"), "response": "approve"}
        return auto_approve(frame)

    client.on_server_request = handler
    try:
        client.request(
            "initialize",
            {"protocol_version": PROTOCOL_VERSION, "client": {"name": "cc-pocket-probe", "version": "0"}},
            timeout=20,
        )
        client.request(
            "prompt",
            {"user_input": "Use your shell tool to run exactly: echo yolo-probe . Nothing else."},
        )
        # plan 模式往返
        plan = client.request("set_plan_mode", {"enabled": True}, timeout=15)
        plan_ok = plan.get("result", {}).get("plan_mode") is True or "error" in plan
        record(
            "V9",
            len(approvals) == 0,
            f"--yolo 下 ApprovalRequest={len(approvals)}个（预期 0）；"
            f"set_plan_mode 返回={plan.get('result') or plan.get('error')}（plan_ok={plan_ok}）",
        )
    except Exception as exc:  # noqa: BLE001
        record("V9", False, f"yolo 探测失败：{exc}")
    finally:
        client.close()


def v10_toolcallparts(client: WireClient) -> None:
    calls = client.events_of("ToolCall")
    parts = client.events_of("ToolCallPart")
    complete = [
        c for c in calls
        if (c.get("payload", {}).get("function") or {}).get("arguments") not in (None, "")
    ]
    record(
        "V10",
        None if not calls else bool(complete),
        f"ToolCall={len(calls)}个（arguments 完整的 {len(complete)}个）ToolCallPart={len(parts)}个 → "
        + ("ToolCall 自带完整参数，解析器无需攒流" if complete else "需要拼 ToolCallPart 片段（解析器要攒流）"),
    )


# ---------------------------------------------------------------- main


def dry_check() -> int:
    """无 kimi 环境的脚本自检：确保各验证函数存在且 JSON 组帧合法。"""
    frame = {"jsonrpc": "2.0", "method": "initialize", "id": str(uuid.uuid4()),
             "params": {"protocol_version": PROTOCOL_VERSION}}
    json.loads(json.dumps(frame))
    vids = ["V1", "V2", "V3", "V4", "V5", "V6", "V7", "V8", "V9", "V10"]
    fns = [v1_handshake, v2_disk_layout, v3_plain_turn, v4_approval, v5_steer,
           v6_cancel, v7_resume_replay, v8_models, v9_yolo, v10_toolcallparts]
    assert len(vids) == len(fns)
    print(f"dry-check OK：{len(vids)} 个验证项已定义（{' '.join(vids)}），JSON-RPC 组帧合法。")
    print("真跑需要已安装并登录的 kimi（见 --help 或安装指引）。")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dry-check", action="store_true", help="无 kimi 环境下只做脚本自检")
    ap.add_argument("--only", nargs="*", default=None, help="只跑指定验证项，如 V4 V7")
    args = ap.parse_args()

    if args.dry_check:
        return dry_check()

    kimi = find_kimi()
    if not kimi:
        print(install_hint(), file=sys.stderr)
        return 2
    ver = subprocess.run([kimi, "--version"], capture_output=True, text=True, timeout=15)
    print(f"kimi = {kimi} ({ver.stdout.strip() or ver.stderr.strip()})")

    def want(vid: str) -> bool:
        return args.only is None or vid in args.only

    # cwd 故意含 _ 与 .（dirKey 编码 bug 前科），观察 workDirKey 编码
    workdir = Path(tempfile.mkdtemp(prefix="kimi_probe.v1_"))
    print(f"workdir = {workdir}")
    session_id: str | None = None

    client = WireClient([kimi, "--wire"], workdir)
    client.on_server_request = auto_approve
    try:
        if want("V1"):
            v1_handshake(client)
        if want("V3"):
            v3_plain_turn(client)
        if want("V2"):
            session_id = v2_disk_layout(workdir)
        if want("V4"):
            v4_approval(client)
        if want("V10"):
            v10_toolcallparts(client)
        if want("V5"):
            v5_steer(client)
        if want("V6"):
            v6_cancel(client)
    finally:
        client.close()

    if want("V7"):
        v7_resume_replay(kimi, workdir, session_id)
    if want("V8"):
        v8_models(kimi)
    if want("V9"):
        v9_yolo(kimi, workdir)

    print("\n==== 汇总 ====")
    failed = 0
    for vid in sorted(RESULTS):
        status, note = RESULTS[vid]
        if status == "FAIL":
            failed += 1
        print(f"  {status:4s} {vid}  {note}")
    print(f"\n{'全部通过' if failed == 0 else f'{failed} 项 FAIL'} —— FAIL/意外形状请回填 docs/design/kimi-backend-design.md 的对应小节。")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
