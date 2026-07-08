#!/usr/bin/env python3
"""claude CLI stream-json 行为回归探针 —— 升级 claude 后跑一次，防依赖漂移。

daemon 依赖四条未写进正式契约的 CLI 行为（2026-07-06 在 2.1.201 上实证；task 为 07-08 增）：
  steer  中途写入的 user 消息在下一个工具边界注入当前轮（单 result 收尾）
  queue  当前轮无工具边界时，排队消息在本轮结束后自动作为下一轮处理（两个 result）
  ask    AskUserQuestion 以 control_request/can_use_tool 浮现（requires_user_interaction=true），
         allow + updatedInput.answers{"<问题原文>": "<label>"} 让模型拿到答案。
         default 与 plan 两种 permission-mode 下这条 wire 完全一致（issue #55 排查所得，两模式都测）
  task   子 agent（Task/Agent 工具）：内部事件以根级 parent_tool_use_id 标注混入同一 stdout，
         主链 tool_result 携带最终报告（issue #77 的 Task 卡片分组/展开全依赖这三点）

任一条漂移都会让 App 静默变坏（排队消失 / 提问卡失灵 / Task 卡片永远转圈）。

用法：python3 scripts/probe-claude-wire.py [steer|queue|ask|ask_plan|task|all]（默认 all）
      CLAUDE_BIN=/path/to/claude 可覆盖二进制。失败退出码非 0。
探针在 /tmp/ccprobe 下起真实 claude 进程（bypassPermissions / default），会消耗少量用量。
"""
import json
import os
import shutil
import subprocess
import sys
import threading
import time

CLAUDE = os.environ.get("CLAUDE_BIN") or shutil.which("claude") or os.path.expanduser("~/.local/bin/claude")
WORKDIR = "/tmp/ccprobe"
BASE_ARGS = [
    "-p", "--output-format", "stream-json", "--input-format", "stream-json",
    "--permission-prompt-tool", "stdio", "--replay-user-messages", "--verbose",
    "--model", "sonnet",
]


def user_frame(text: str) -> str:
    return json.dumps({"type": "user", "message": {"role": "user", "content": [{"type": "text", "text": text}]}})


class Probe:
    """一个 claude 进程 + 按类型归档的事件时间线。"""

    def __init__(self, extra_args):
        os.makedirs(WORKDIR, exist_ok=True)
        env = dict(os.environ)
        env.pop("CLAUDECODE", None)  # 本探针常在 claude 会话里跑 —— 去掉嵌套检测
        env.pop("CLAUDE_CODE_ENTRYPOINT", None)
        self.t0 = time.time()
        self.events = []  # (elapsed, kind, payload)
        self.raw = []     # every parsed stdout line, for scenarios that need root-level fields
        self.proc = subprocess.Popen(
            [CLAUDE, *BASE_ARGS, *extra_args],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            cwd=WORKDIR, env=env, text=True, bufsize=1,
        )
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self):
        for line in self.proc.stdout:
            try:
                j = json.loads(line)
            except ValueError:
                continue
            t = j.get("type")
            el = time.time() - self.t0
            self.raw.append(j)
            if t == "assistant":
                for c in j.get("message", {}).get("content", []):
                    if c.get("type") == "text":
                        self.events.append((el, "assistant_text", c.get("text", "")))
                    elif c.get("type") == "tool_use":
                        self.events.append((el, "tool_use", c.get("name", "")))
            elif t == "user":
                for c in j.get("message", {}).get("content", []):
                    if isinstance(c, dict) and c.get("type") == "tool_result":
                        self.events.append((el, "tool_result", json.dumps(c.get("content"))[:200]))
                    elif isinstance(c, dict) and c.get("type") == "text":
                        self.events.append((el, "user_replay", c.get("text", "")))
            elif t == "control_request":
                self.events.append((el, "control_request", j))
            elif t == "result":
                self.events.append((el, "result", j.get("result") or ""))

    def send(self, text: str):
        self.proc.stdin.write(user_frame(text) + "\n")
        self.proc.stdin.flush()

    def send_raw(self, obj):
        self.proc.stdin.write(json.dumps(obj) + "\n")
        self.proc.stdin.flush()

    def wait_for(self, kind: str, count: int = 1, timeout: float = 90.0) -> bool:
        end = time.time() + timeout
        while time.time() < end:
            if len([e for e in self.events if e[1] == kind]) >= count:
                return True
            time.sleep(0.3)
        return False

    def of(self, kind: str):
        return [e for e in self.events if e[1] == kind]

    def kill(self):
        self.proc.kill()


def check(name: str, cond: bool, detail: str) -> bool:
    print(f"  {'✅' if cond else '❌'} {name}: {detail}")
    return cond


def scenario_steer() -> bool:
    print("── steer：中途消息在工具边界注入当前轮 ──")
    p = Probe(["--permission-mode", "bypassPermissions"])
    try:
        p.send("Run the shell command `sleep 6` using the Bash tool, then reply with exactly DONE-A.")
        if not p.wait_for("tool_use", timeout=60):
            return check("turn started", False, "no tool_use within 60s")
        p.send("Additional instruction while you work: also print DONE-B on its own line in your final reply.")
        if not p.wait_for("result", timeout=90):
            return check("turn finished", False, "no result within 90s")
        time.sleep(2)  # 收尾事件
        ok = True
        replays = [e for e in p.of("user_replay") if "DONE-B" in e[2]]
        results = p.of("result")
        ok &= check("mid-turn message replayed (consumed)", bool(replays), f"{len(replays)} replay(s)")
        if replays and p.of("tool_result"):
            ok &= check("injected AT the tool boundary", replays[0][0] >= p.of("tool_result")[0][0], "replay after tool_result")
        ok &= check("single result covers both", len(results) == 1 and "DONE-A" in results[0][2] and "DONE-B" in results[0][2],
                    repr(results[-1][2])[:80] if results else "none")
        return ok
    finally:
        p.kill()


def scenario_queue() -> bool:
    print("── queue：无工具边界时排队到下一轮 ──")
    p = Probe(["--permission-mode", "bypassPermissions"])
    try:
        p.send("Without using any tools, write a numbered list of 20 short facts about foxes.")
        time.sleep(4)
        p.send("New instruction: end your NEXT reply with the word INTERJECTED.")
        if not p.wait_for("result", count=2, timeout=120):
            return check("two results", False, f"got {len(p.of('result'))} result(s) in 120s")
        results = p.of("result")
        return check("queued message processed as follow-up turn", "INTERJECTED" in results[1][2],
                     repr(results[1][2])[:80])
    finally:
        p.kill()


def scenario_ask(mode: str = "default") -> bool:
    print(f"── ask（--permission-mode {mode}）：AskUserQuestion 走 can_use_tool + answers map ──")
    # WIRE probe. The explicit 'use AskUserQuestion' prompt fires the tool in ~15s under BOTH default and
    # plan mode, and the control_request is byte-identical across modes (verified CLI 2.1.201, issue #55).
    # The ORGANIC plan flow ('add i18n, help me plan') emits the SAME wire too, but only after a long
    # multi-subagent research phase — so we keep the explicit prompt here for a fast, deterministic check.
    p = Probe(["--permission-mode", mode])
    try:
        p.send("Use the AskUserQuestion tool to ask me which color I prefer, options Red and Blue. "
               "After I answer, reply with exactly 'CHOSE: ' followed by my answer.")
        if not p.wait_for("control_request", timeout=120):
            return check("control_request arrived", False, "none within 120s")
        req = p.of("control_request")[0][2]
        inner = req.get("request", {})
        ok = True
        ok &= check("subtype can_use_tool + AskUserQuestion",
                    inner.get("subtype") == "can_use_tool" and inner.get("tool_name") == "AskUserQuestion",
                    f"{inner.get('subtype')}/{inner.get('tool_name')}")
        ok &= check("requires_user_interaction=true", inner.get("requires_user_interaction") is True,
                    str(inner.get("requires_user_interaction")))
        qs = (inner.get("input") or {}).get("questions") or []
        ok &= check("questions schema", bool(qs) and "question" in qs[0] and "options" in qs[0], f"{len(qs)} question(s)")
        if not ok:
            return False
        updated = dict(inner["input"])
        updated["answers"] = {qs[0]["question"]: "Red"}
        p.send_raw({"type": "control_response", "response": {"subtype": "success", "request_id": req.get("request_id"),
                    "response": {"behavior": "allow", "updatedInput": updated}}})
        if not p.wait_for("result", timeout=90):
            return check("turn finished", False, "no result after answering")
        ok &= check("tool_result acknowledges answers",
                    any("answered" in e[2].lower() for e in p.of("tool_result")),
                    (p.of("tool_result") or [(0, "", "none")])[0][2][:80])
        ok &= check("model used the answer", "Red" in p.of("result")[0][2], repr(p.of("result")[0][2])[:60])
        return ok
    finally:
        p.kill()


def scenario_task() -> bool:
    print("── task：子 agent 事件带 parent_tool_use_id、主链 tool_result 携带报告（issue #77）──")
    # daemon 依赖三点：① 子 agent 调用是主链上的一个 Task/Agent tool_use（2.1.x 叫 Task，现名 Agent，
    # 两名都接受）；② 子 agent 内部事件混入同一 stdout、根级 parent_tool_use_id 指回该 tool_use；
    # ③ 主链 tool_result（同 id）内容就是子 agent 的最终报告。漂移会让手机的 Task 卡片永远转圈/看不到产出。
    p = Probe(["--permission-mode", "bypassPermissions"])
    try:
        p.send("Use the Task tool (subagent_type: general-purpose, description: 'add two numbers') "
               "with the prompt: 'Use the Bash tool to run: expr 2 + 3 - then reply with the number only.' "
               "After the Task completes, reply with exactly TASKDONE.")
        if not p.wait_for("result", timeout=180):
            return check("turn finished", False, "no result within 180s")
        time.sleep(1)  # 收尾事件
        agent_uses = [c for j in p.raw
                      if j.get("type") == "assistant" and j.get("parent_tool_use_id") is None
                      for c in j.get("message", {}).get("content", [])
                      if c.get("type") == "tool_use" and c.get("name") in ("Task", "Agent")]
        ok = check("main-chain Task/Agent tool_use", bool(agent_uses),
                   agent_uses[0].get("name") if agent_uses else "none")
        if not agent_uses:
            return False
        tid = agent_uses[0].get("id")
        inner = [j for j in p.raw if j.get("parent_tool_use_id") == tid]
        ok &= check("inner events tagged with parent_tool_use_id", bool(inner), f"{len(inner)} line(s)")
        finals = [c for j in p.raw
                  if j.get("type") == "user" and j.get("parent_tool_use_id") is None
                  for c in j.get("message", {}).get("content", [])
                  if isinstance(c, dict) and c.get("type") == "tool_result" and c.get("tool_use_id") == tid]
        ok &= check("main-chain tool_result carries the report", bool(finals) and "5" in json.dumps(finals[0].get("content")),
                    json.dumps(finals[0].get("content"))[:80] if finals else "none")
        return ok
    finally:
        p.kill()


def scenario_ask_plan() -> bool:
    # issue #55: users reported the phone couldn't pick an AskUserQuestion option in PLAN mode. Root cause
    # was NOT the wire — plan-mode's control_request is identical to default's (proven), so StreamParser and
    # PermissionBridge handle it unchanged. This scenario locks that invariant: if a future CLI ever gates
    # plan-mode questions differently (drops can_use_tool / changes subtype / stops sending it), it goes red
    # HERE first, instead of silently breaking the phone's question card.
    return scenario_ask("plan")


def main():
    which = sys.argv[1] if len(sys.argv) > 1 else "all"
    version = subprocess.run([CLAUDE, "--version"], capture_output=True, text=True).stdout.strip()
    print(f"claude: {CLAUDE} ({version})\n")
    scenarios = {"steer": scenario_steer, "queue": scenario_queue, "ask": scenario_ask, "ask_plan": scenario_ask_plan, "task": scenario_task}
    run = scenarios.values() if which == "all" else [scenarios[which]]
    results = [fn() for fn in run]
    print()
    if all(results):
        print("✅ wire behavior unchanged — App 依赖的四条 CLI 行为全部成立")
        sys.exit(0)
    print("❌ CLI 行为漂移！排查 daemon 的 StreamParser/PermissionBridge/AskQuestions 是否需要适配")
    sys.exit(1)


if __name__ == "__main__":
    main()
