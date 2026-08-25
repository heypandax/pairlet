#!/usr/bin/env python3
"""codex app-server 行为回归探针 —— 升级 codex CLI 后跑一次，防依赖漂移。

`codex app-server` 是**官方标注 experimental** 的 JSON-RPC 接口，版本间会漂移。daemon 的
CodexBackend（daemon/src/main/kotlin/dev/ccpocket/daemon/codex/CodexBackend.kt）押了四条未写进
正式契约的行为，任一条漂移都会让 App 静默变坏：

  handshake   initialize 应答 → `initialized` 通知 → thread/start，响应 result.thread.id 非空。
              这条是所有 Codex 会话的地基：onThreadReady 拿不到 id，buffered 的第一条 prompt
              就永远停在 pendingPrompt 里（会话「开着但不动」）

  steer_stale turn/steer 带 expectedTurnId；当它慢了一步、turn 已经结束时，服务端必须回一条
              **带同一 JSON-RPC id 的 error response**。CodexBackend.handleErrorResponse 靠
              pendingSteers[id] 把这条 error 认回成「用户那句话没送到」，改投 turn/start 重发
              （PR #296 评审修的「已 ack 的提示词被吞」类，issue #84/#104 同源）。
              若漂成静默、或漂成不带 id 的 `error` 通知 → 用户的话直接消失，且没有任何报错

  fork        thread/fork 存在且铸出**新的** thread id（result.thread.forkedFromId 指回原 thread）。
              这是接管防双写的唯一手段：手机接管一个桌面还开着的会话时走 fork，绝不让两个 writer
              同时写一份 rollout。它一消失，openThread 会被 error 打回（daemon 故意不静默退回
              thread/resume），接管功能须整体重估。
              ⚠️ fork 认的是**盘上的 rollout**，而 rollout 随第一个 turn 懒创建 —— 所以这条 check
              必须排在 steer_stale 的 turn 之后，否则只会拿到 "no rollout found"（非漂移）

  unknown     未知方法必须回**带 id 的 error response**，而不是断连或静默。handleErrorResponse 的
              全部关联逻辑（pendingSteers / pendingStarts / pendingControls / threadOpenId /
              initializeId 五张表）都建立在「错误按 id 回到发起方」这个前提上

说的是 CodexBackend 那套方言：裸 {id, method, params}，**不带 `jsonrpc` 字段**（与
probe-codex-concurrent.py 一致）。

用法：python3 scripts/probe-codex-wire.py [handshake|steer_stale|fork|unknown|all]（默认 all）
      CC_POCKET_CODEX_BIN=/path/to/codex 可覆盖二进制。
退出码：0 全绿 / 1 有 FAIL（行为漂移）/ 2 缺依赖（找不到 codex）。
探针在临时目录起真实 codex app-server（approvalPolicy=never + sandbox=read-only，只碰自己新建的
thread），总共只跑 **1 个**真实 turn（steer_stale 那个，prompt 极小），消耗微量用量。
"""
import json
import os
import queue
import shutil
import subprocess
import sys
import tempfile
import threading
import time

CODEX = os.environ.get("CC_POCKET_CODEX_BIN") or shutil.which("codex")
RPC_TIMEOUT = 60.0    # 每个 check 的独立超时：wire 层往返，60s 还没回就是漂移不是慢
TURN_TIMEOUT = 120.0  # 唯一一个真实 turn 的模型延迟，与 wire 超时分开算，避免模型慢 = 假红

results = []   # (name, ok, detail)


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print(("  PASS  " if ok else "  FAIL  ") + name + (" — " + str(detail) if detail else ""))
    return bool(ok)


def excerpt(obj, limit=360):
    """原始帧摘录 —— 漂移时要能直接看见服务端到底回了什么形状。"""
    s = obj if isinstance(obj, str) else json.dumps(obj, ensure_ascii=False)
    return s if len(s) <= limit else s[:limit] + " …(+%d chars)" % (len(s) - limit)


def show(tag, obj):
    print("        %s %s" % (tag, excerpt(obj)))


class Server:
    """一个 `codex app-server` 子进程，读线程把「按 id 的应答」和「通知」拆开。"""

    def __init__(self, cwd, log):
        self.log = log
        self.proc = subprocess.Popen(
            [CODEX, "app-server"], cwd=cwd,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            text=True, encoding="utf-8", errors="replace", bufsize=1,
        )
        self._id = 0
        self._replies = {}           # id -> queue，一个请求只等自己那条应答
        self._lock = threading.Lock()
        self.notes = queue.Queue()   # 服务端 → 客户端的通知，按到达顺序
        self.orphans = []            # 带 id 的应答但没人等 —— id 关联漂移的证据
        self.stderr_tail = []
        threading.Thread(target=self._read, daemon=True).start()
        threading.Thread(target=self._read_err, daemon=True).start()

    def _record(self, tag, line):
        self.log.write("%s %s\n" % (tag, line))
        self.log.flush()

    def _read(self):
        for line in self.proc.stdout:
            line = line.strip()
            if not line:
                continue
            self._record("<<<", line)
            try:
                msg = json.loads(line)
            except ValueError:
                continue
            if "id" in msg and ("result" in msg or "error" in msg):
                with self._lock:
                    q = self._replies.pop(msg["id"], None)
                if q:
                    q.put(msg)
                else:
                    self.orphans.append(msg)
            elif "method" in msg:
                self.notes.put(msg)

    def _read_err(self):
        for line in self.proc.stderr:
            self.stderr_tail.append(line.rstrip())
            del self.stderr_tail[:-20]
            self._record("!!!", line.rstrip())

    def _write(self, obj):
        line = json.dumps(obj, ensure_ascii=False)
        self._record(">>>", line)
        self.proc.stdin.write(line + "\n")
        self.proc.stdin.flush()

    def request(self, method, params=None, timeout=RPC_TIMEOUT):
        """发一个请求并阻塞等**它自己**那条应答。返回 (rid, envelope|None)；None = 静默/超时。"""
        with self._lock:
            self._id += 1
            rid = self._id
            q = queue.Queue()
            self._replies[rid] = q
        obj = {"id": rid, "method": method}
        if params is not None:
            obj["params"] = params
        self._write(obj)
        try:
            return rid, q.get(timeout=timeout)
        except queue.Empty:
            with self._lock:
                self._replies.pop(rid, None)
            return rid, None

    def notify(self, method, params=None):
        obj = {"method": method}
        if params is not None:
            obj["params"] = params
        self._write(obj)

    def wait_note(self, methods, timeout):
        """排干通知直到出现 [methods] 之一。返回它，超时返回 None。"""
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                msg = self.notes.get(timeout=min(1.0, max(0.05, deadline - time.time())))
            except queue.Empty:
                continue
            if msg.get("method") in methods:
                return msg
        return None

    def drain_notes(self):
        out = []
        while True:
            try:
                out.append(self.notes.get_nowait())
            except queue.Empty:
                return out

    def alive(self):
        return self.proc.poll() is None

    def kill(self):
        if self.alive():
            self.proc.kill()


# ── checks ────────────────────────────────────────────────────────────────────────────────────────

def check_handshake(srv, cwd):
    """① initialize → initialized → thread/start，result.thread.id 非空。返回 thread id。"""
    print("── handshake：initialize → initialized → thread/start ──")
    rid, env = srv.request("initialize", {
        "clientInfo": {"name": "cc-pocket-probe", "version": "0"},
        "capabilities": {"experimentalApi": False},   # 与 CodexBackend 逐字一致
    })
    if env is None:
        check("initialize 回应答", False, "%.0fs 内无任何应答（app-server 起不来？）" % RPC_TIMEOUT)
        if srv.stderr_tail:
            show("stderr:", "\n".join(srv.stderr_tail[-3:]))
        return None
    show("initialize <<<", env)
    if not check("initialize 回 result（非 error）", "result" in env,
                 excerpt((env.get("result") or {}).get("userAgent") or env.get("error"), 160)):
        return None
    check("应答 id 与请求 id 一致", env.get("id") == rid, "req id=%s, resp id=%s" % (rid, env.get("id")))

    srv.notify("initialized")   # 通知，无应答；漏发它 thread/start 会被拒
    rid, env = srv.request("thread/start", {
        "cwd": cwd,
        "approvalPolicy": "never",   # 非交互：探针永远不会阻塞在审批上
        "sandbox": "read-only",      # 也永远写不到临时目录之外
    })
    if env is None:
        check("thread/start 回应答", False, "%.0fs 内静默 —— initialized 的时序或形状变了？" % RPC_TIMEOUT)
        return None
    show("thread/start <<<", env)
    if "error" in env:
        check("thread/start 回 result", False, excerpt(env["error"], 200))
        return None
    tid = ((env.get("result") or {}).get("thread") or {}).get("id")
    check("result.thread.id 非空（onThreadReady 认这条）", bool(tid), tid or "缺失：%s" % excerpt(env.get("result"), 160))
    return tid


def check_steer_stale(srv, thread_id, cwd):
    """② 陈旧 steer 必须被「带同一 id 的 error response」拒绝（daemon 靠它重投 turn/start）。"""
    print("── steer_stale：已完结 turn 的 turn/steer → 带 id 的 error response ──")
    if not thread_id:
        return check("前置 thread 可用", False, "handshake 没拿到 thread id，跳过")

    # 唯一一个真实 turn：prompt 尽量小，只为拿一个「已完结」的 turn id
    rid, env = srv.request("turn/start", {
        "threadId": thread_id,
        "input": [{"type": "text", "text": "Reply with exactly: ok"}],
        "cwd": cwd,
        "approvalPolicy": "never",
        # 两处 sandbox 拼写不同且都会被服务端强校验（CodexBackend.sandbox() 的 flat/tag 双拼写就为这个）：
        # thread/start 吃扁平串 "read-only"，turn/start 吃对象形 {"type": "readOnly"}。写串了直接 -32600。
        "sandboxPolicy": {"type": "readOnly"},
    })
    if env is None or "error" in env:
        show("turn/start <<<", env if env else "<silence>")
        return check("turn/start 被接受", False, excerpt((env or {}).get("error"), 200) if env else "静默")

    note = srv.wait_note({"turn/completed", "turn/failed"}, TURN_TIMEOUT)
    if note is None:
        return check("turn 跑到 turn/completed", False, "%.0fs 内没有终态通知" % TURN_TIMEOUT)
    show("turn 终态 <<<", note)
    if not check("turn 跑到 turn/completed", note.get("method") == "turn/completed", note.get("method")):
        return False
    turn_id = ((note.get("params") or {}).get("turn") or {}).get("id")
    if not check("turn/completed 带 turn.id（steer 的 expectedTurnId 来源）", bool(turn_id),
                 turn_id or excerpt(note.get("params"), 200)):
        return False

    srv.drain_notes()   # 清干净，好判断「拒绝」是不是漂成了通知
    rid, env = srv.request("turn/steer", {
        "threadId": thread_id,
        "input": [{"type": "text", "text": "stale steer probe — this turn is already over"}],
        "expectedTurnId": turn_id,   # 已完结的 turn id = 故意制造 stale
    })
    if env is None:
        stray = srv.drain_notes()
        show("超时窗内的通知", [n.get("method") for n in stray] or "<无>")
        for n in stray:
            if n.get("method") in ("error", "turn/failed"):
                show("疑似漂成通知 <<<", n)
        return check("陈旧 steer 回带 id 的 error response", False,
                     "静默：%.0fs 内没有任何带 id 的应答 —— 用户那句话会被永久吞掉" % RPC_TIMEOUT)
    show("turn/steer <<<", env)
    ok = check("陈旧 steer 被 error response 拒绝（非 result、非静默）", "error" in env,
               "回了 result：%s" % excerpt(env.get("result"), 160) if "error" not in env else
               "code=%s message=%s" % ((env.get("error") or {}).get("code"),
                                       excerpt((env.get("error") or {}).get("message"), 200)))
    ok &= check("error 带着与 turn/steer 请求同一个 id（pendingSteers[id] 的关联前提）",
                env.get("id") == rid, "req id=%s, resp id=%s" % (rid, env.get("id")))
    if srv.orphans:
        show("！无人认领的带 id 应答", srv.orphans[-3:])
    return ok


def check_fork(srv, thread_id):
    """③ thread/fork 存在，且铸出与原 id 不同的新 thread（接管防双写）。"""
    print("── fork：thread/fork 铸新 thread id ──")
    if not thread_id:
        return check("前置 thread 可用", False, "handshake 没拿到 thread id，跳过")
    # fork 认的是**盘上的 rollout**，而 rollout 是随第一个 turn 懒创建的（probe-codex-concurrent.py 已实证）。
    # steer_stale 那个 turn 刚跑完，落盘可能还差一点 —— "no rollout found" 是时序不是漂移，短暂重试。
    env = None
    deadline = time.time() + 12
    while True:
        rid, env = srv.request("thread/fork", {"threadId": thread_id})
        msg = ((env or {}).get("error") or {}).get("message") or ""
        if env is None or "no rollout" not in msg or time.time() > deadline:
            break
        time.sleep(1.5)
    if env is None:
        return check("thread/fork 回应答", False, "%.0fs 内静默" % RPC_TIMEOUT)
    show("thread/fork <<<", env)
    if "error" in env:
        msg = (env.get("error") or {}).get("message") or ""
        gone = "unknown variant" in msg   # 方法从 enum 里消失 = 真漂移；其余是别的拒绝理由
        return check("thread/fork 方法存在", False,
                     "code=%s message=%s%s"
                     % ((env.get("error") or {}).get("code"), excerpt(msg, 200),
                        "（方法已从 app-server 的 method enum 消失 → 接管防双写须整体重估）" if gone
                        else "（方法在，但这次调用被拒 —— 看 message 判是不是前置条件问题）"))
    new_id = ((env.get("result") or {}).get("thread") or {}).get("id")
    ok = check("fork 的 result.thread.id 非空", bool(new_id), new_id or excerpt(env.get("result"), 160))
    ok &= check("fork 铸的是新 id（≠ 原 thread，才不会双写同一份 rollout）",
                bool(new_id) and new_id != thread_id, "原 %s → 新 %s" % (thread_id, new_id))
    return ok


def check_unknown(srv):
    """④ 未知方法 → 带 id 的 error response，连接不断、不静默。"""
    print("── unknown：未知方法 → 带 id 的 error response ──")
    rid, env = srv.request("thread/nonexistent/probe", {})
    if env is None:
        return check("未知方法回带 id 的 error response", False,
                     "静默：%.0fs 内无应答（进程存活=%s）—— handleErrorResponse 的五张 id 表全失效"
                     % (RPC_TIMEOUT, srv.alive()))
    show("unknown method <<<", env)
    err = env.get("error") or {}
    ok = check("回的是 error 而不是 result", "error" in env,
               "error response" if "error" in env else "回了 result：%s" % excerpt(env.get("result"), 160))
    ok &= check("error 带着与请求同一个 id", env.get("id") == rid,
                "req id=%s, resp id=%s" % (rid, env.get("id")))
    ok &= check("error 有 code/message 形状", bool(err) and "message" in err,
                "code=%s message=%s" % (err.get("code"), excerpt(err.get("message"), 160)))
    ok &= check("连接没断（同一进程还能继续说话）", srv.alive(), "app-server pid %d 存活=%s"
                % (srv.proc.pid, srv.alive()))
    return ok


def main():
    global CODEX
    which = sys.argv[1] if len(sys.argv) > 1 else "all"
    if which not in ("all", "handshake", "steer_stale", "fork", "unknown"):
        print("用法：python3 scripts/probe-codex-wire.py [handshake|steer_stale|fork|unknown|all]",
              file=sys.stderr)
        return 2
    resolved = shutil.which(CODEX) if CODEX else None
    if not resolved:
        print("找不到 codex CLI —— 本探针要的就是真实 app-server，没有它没什么可回归的。", file=sys.stderr)
        print("  装：npm i -g @openai/codex（或 brew install codex）", file=sys.stderr)
        print("  或：CC_POCKET_CODEX_BIN=/path/to/codex python3 scripts/probe-codex-wire.py", file=sys.stderr)
        return 2
    CODEX = resolved

    version = subprocess.run([CODEX, "--version"], capture_output=True, text=True).stdout.strip()
    cwd = tempfile.mkdtemp(prefix="codex-wire-probe-")
    log_path = os.path.join(cwd, "wire.jsonl")
    log = open(log_path, "w", encoding="utf-8")
    print("codex   : %s (%s)" % (CODEX, version or "版本未知"))
    print("workdir : %s" % cwd)
    print("wire log: %s\n" % log_path)

    srv = Server(cwd, log)
    try:
        thread_id = check_handshake(srv, cwd)
        if which in ("all", "handshake") and thread_id:
            print()
        # steer_stale / fork 都要一个真 thread；单跑它们时也得先握手（握手的 check 结果照常计入）
        if which in ("all", "steer_stale"):
            check_steer_stale(srv, thread_id, cwd)
            print()
        if which in ("all", "fork"):
            check_fork(srv, thread_id)
            print()
        if which in ("all", "unknown"):
            check_unknown(srv)
    finally:
        srv.kill()
        log.close()

    print("\n" + "=" * 78)
    width = max(len(n) for n, _, _ in results) if results else 0
    for name, ok, detail in results:
        print("  %s  %-*s  %s" % ("PASS" if ok else "FAIL", width, name, excerpt(detail, 90)))
    print("=" * 78)
    failed = [n for n, ok, _ in results if not ok]
    print("%d/%d checks passed" % (len(results) - len(failed), len(results)))
    if failed:
        print("FAILED: " + ", ".join(failed))
        print("\ncodex app-server 行为漂移 —— 发版前重读 daemon 的 CodexBackend"
              "（handleErrorResponse / openThread / writeTurnSteer）确认是否要适配。")
        print("完整 wire：%s" % log_path)
        return 1
    print("codex app-server wire 契约完好 —— CodexBackend 依赖的四条行为全部成立。")
    print("完整 wire：%s" % log_path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
