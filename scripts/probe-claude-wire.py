#!/usr/bin/env python3
"""claude CLI stream-json 行为回归探针 —— 升级 claude 后跑一次，防依赖漂移。

daemon 依赖八条未写进正式契约的 CLI 行为（2026-07-06 在 2.1.201 上实证；task 为 07-08 增，
lock 为 07-10 增，workflow/fgtask/bgcontinue 为 07-11 增、在 2.1.206 上实证）：
  steer  中途写入的 user 消息在下一个工具边界注入当前轮（单 result 收尾）
  queue  当前轮无工具边界时，排队消息在本轮结束后自动作为下一轮处理（两个 result）
  ask    AskUserQuestion 以 control_request/can_use_tool 浮现（requires_user_interaction=true），
         allow + updatedInput.answers{"<问题原文>": "<label>"} 让模型拿到答案。
         default 与 plan 两种 permission-mode 下这条 wire 完全一致（issue #55 排查所得，两模式都测）
  task   子 agent（Task/Agent 工具）：内部事件以根级 parent_tool_use_id 标注混入同一 stdout，
         主链 tool_result 携带最终报告（issue #77 的 Task 卡片分组/展开全依赖这三点）
  workflow  Workflow 编排工具（issue #106）：async 启动（tool_result 根级 tool_use_result 带 runId）、
         task_progress 携带 workflow_progress 累积数组（phase 种子 + agent state 机）、终态
         manifest/journal/agent 转录落盘 —— WorkflowTracker/WorkflowFiles 的 live 与回放都吃这套
  lock   被活进程注册（~/.claude/sessions/<pid>.json）的 session，裸 --resume 启动即 exit 1、
         stdout 全空、stderr 一行含 "is currently running as a background agent"；--fork-session
         逃生成功并铸新 id（Conversation.healSessionLock 的自动分叉自愈全靠这行文案匹配）
  fgtask     前台（非 run_in_background）Bash 也走 task 机制：task_started/task_notification
             （task_type=local_bash）在命令完成时到达、带着前台 tool_use 的 id。daemon 靠这个
             tool_use_id 相关性识破它们不入册（BackgroundJobRegistry.foregroundBash，#105 残留）
             —— id 相关性一断，每条前台命令都会在手机任务面板闪出幻影 job
  bgcontinue 后台任务完成（task_notification/task_updated 报 completed）后，CLI 不需要任何输入
             就自动开下一轮完成剩余步骤（新 init + 第二个 result）。#105 的 reaper 让位
             （reapStaleJobs 对活进程弃用时钟、reapIdle 只看 isBusy）安全性全押在这条上：
             它一漂移，「无人值守的多步计划」就会死在后台步骤后面，需要重估整个让位策略

任一条漂移都会让 App 静默变坏（排队消失 / 提问卡失灵 / Task 卡片永远转圈 / 占用会话裸报错 /
任务面板幻影 job / 无人值守任务只做半截）。

  scope   folder-share guest scope（issue #115 followup-h1）：guest 适用的每个 mode（default / acceptEdits，
          guest 永不 bypass）下，对共享根外的绝对路径做 Read/Write 都必须经 --permission-prompt-tool 浮现
          can_use_tool control_request —— 只有这样 daemon 的 PermissionBridge.outOfScopeTarget 才拦得到并硬
          deny。某 mode 下 CLI 越界自动放行（无 control_request 就吐内容/落盘）= #115「碰不到你其他文件夹」
          保证被绕过（settingsources 验 --setting-sources "" 挡住共享目录 settings.json 的 allow 自动放行）

用法：python3 scripts/probe-claude-wire.py [steer|queue|ask|ask_plan|task|workflow|lock|fgtask|bgcontinue|
      scope|scope_acceptedits|settingsources|all]（默认 all）
      CLAUDE_BIN=/path/to/claude 可覆盖二进制。失败退出码非 0。
探针在 /tmp/ccprobe 下起真实 claude 进程（bypassPermissions / default / acceptEdits），会消耗少量用量。
"""
import json
import os
import re
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

    def __init__(self, extra_args, cwd=None):
        cwd = cwd or WORKDIR
        os.makedirs(cwd, exist_ok=True)
        env = dict(os.environ)
        env.pop("CLAUDECODE", None)  # 本探针常在 claude 会话里跑 —— 去掉嵌套检测
        env.pop("CLAUDE_CODE_ENTRYPOINT", None)
        self.cwd = cwd
        self.t0 = time.time()
        self.events = []  # (elapsed, kind, payload)
        self.raw = []     # every parsed stdout line, for scenarios that need root-level fields
        self.proc = subprocess.Popen(
            [CLAUDE, *BASE_ARGS, *extra_args],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            cwd=cwd, env=env, text=True, bufsize=1,
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


def scenario_workflow() -> bool:
    print("── workflow：Workflow 工具的 wire 形态 + 落盘 schema（issue #106）──")
    # daemon 依赖四点：① 主链 tool_result 的根级 tool_use_result {taskType:"local_workflow", runId,
    #   taskId, workflowName} —— run id 在 live 流上唯一的载体；② system/task_started 带 task_type
    #   local_workflow + workflow_name；③ system/task_progress 携带 workflow_progress 累积数组
    #   （workflow_phase{index,title} 开跑即全量种子 + workflow_agent{index,label,phaseIndex,state,…}，
    #   state ∈ start/progress/done/error）；④ 终态落盘 <sess>/workflows/<runId>.json（终态一次写）
    #   与 subagents/workflows/<runId>/{journal.jsonl, agent-<id>.jsonl}。任一漂移会让 Workflow 卡
    #   永远空转 / 进度树空白 / 历史回放消失（WorkflowTracker / WorkflowFiles / TranscriptReplay）。
    script = (
        "export const meta = { name: 'probe-wf', description: 'probe wf', phases: ["
        "{ title: 'Alpha', detail: 'a' }, { title: 'Beta', detail: 'b' } ] }\n"
        "phase('Alpha')\n"
        "const r = await parallel([\n"
        "  () => agent('Reply with exactly the single word: apple. Do not use any tools.', { label: 'say-apple', phase: 'Alpha' }),\n"
        "  () => agent('Reply with exactly the single word: banana. Do not use any tools.', { label: 'say-banana', phase: 'Alpha' }),\n"
        "])\n"
        "phase('Beta')\n"
        "const c = await agent('Reply with exactly the single word: cherry. Do not use any tools.', { label: 'say-cherry', phase: 'Beta' })\n"
        "return { fruits: r, last: c }\n"
    )
    p = Probe(["--permission-mode", "bypassPermissions"])
    try:
        p.send(
            "Call the Workflow tool exactly once with input {script: <the script below>, description: 'probe'}, "
            "passing the script VERBATIM. After it launches reply 'launched'; when the completion "
            "notification arrives reply with exactly WFDONE.\n\nScript:\n" + script,
        )
        # 两个 result：launch 回合 + 完成通知唤起的收尾回合
        deadline = time.time() + 240
        while time.time() < deadline and sum(1 for j in p.raw if j.get("type") == "result") < 2:
            time.sleep(1)
        time.sleep(1)
        n_results = sum(1 for j in p.raw if j.get("type") == "result")
        ok = check("workflow ran to completion (2 results)", n_results >= 2, f"{n_results} result(s)")

        acks = [j.get("tool_use_result") for j in p.raw
                if j.get("type") == "user" and isinstance(j.get("tool_use_result"), dict)
                and j["tool_use_result"].get("taskType") == "local_workflow"]
        ok &= check("launch ack: root tool_use_result {taskType, runId wf_…}",
                    bool(acks) and str(acks[0].get("runId", "")).startswith("wf_"),
                    json.dumps(acks[0], ensure_ascii=False)[:110] if acks else "none")
        started = [j for j in p.raw if j.get("type") == "system" and j.get("subtype") == "task_started"
                   and j.get("task_type") == "local_workflow"]
        ok &= check("task_started carries workflow_name", bool(started) and started[0].get("workflow_name") == "probe-wf",
                    started[0].get("workflow_name") if started else "none")
        prog = [j for j in p.raw if j.get("type") == "system" and j.get("subtype") == "task_progress"
                and isinstance(j.get("workflow_progress"), list)]
        ok &= check("task_progress carries workflow_progress arrays", bool(prog), f"{len(prog)} event(s)")
        items = [it for j in prog for it in j["workflow_progress"] if isinstance(it, dict)]
        phases = {it.get("title") for it in items if it.get("type") == "workflow_phase"}
        ok &= check("meta.phases seeded on the wire up front", {"Alpha", "Beta"} <= phases, str(sorted(phases)))
        dones = [it for it in items if it.get("type") == "workflow_agent" and it.get("state") == "done"]
        ok &= check("agents reach state=done with resultPreview",
                    len({it.get("index") for it in dones}) >= 3 and all("resultPreview" in it for it in dones),
                    f"{len(dones)} done item(s)")
        settled = [j for j in p.raw if j.get("type") == "system" and j.get("subtype") in ("task_updated", "task_notification")]
        ok &= check(
            "run settles via task_updated/notification status=completed",
            any((j.get("patch") or {}).get("status") == "completed" or j.get("status") == "completed" for j in settled),
            f"{len(settled)} event(s)",
        )

        # 落盘 schema —— WorkflowFiles/TranscriptReplay 的回放路径全靠这三样
        sid = next((j.get("session_id") for j in p.raw if j.get("type") == "system" and j.get("subtype") == "init"), None)
        run_id = acks[0].get("runId") if acks else None
        if sid and run_id:
            proj = os.path.expanduser(
                "~/.claude/projects/" + re.sub(r"[^A-Za-z0-9-]", "-", os.path.realpath(WORKDIR)),
            )
            sess = os.path.join(proj, sid)
            mf = os.path.join(sess, "workflows", run_id + ".json")
            ok &= check("manifest <sess>/workflows/<runId>.json written at completion", os.path.isfile(mf), mf)
            if os.path.isfile(mf):
                with open(mf) as fh:
                    m = json.load(fh)
                need = {"runId", "workflowName", "status", "startTime", "phases", "workflowProgress"}
                missing = sorted(need - set(m))
                ok &= check("manifest schema keys", not missing, ", ".join(missing) or "all present")
            rd = os.path.join(sess, "subagents", "workflows", run_id)
            jl = os.path.join(rd, "journal.jsonl")
            j_lines = sum(1 for _ in open(jl)) if os.path.isfile(jl) else 0
            ok &= check("journal.jsonl started/result records", j_lines >= 6, f"{j_lines} line(s)")
            agents = [f for f in os.listdir(rd) if f.startswith("agent-") and f.endswith(".jsonl")] if os.path.isdir(rd) else []
            ok &= check("agent-<id>.jsonl transcripts", len(agents) >= 3, f"{len(agents)} file(s)")
        else:
            ok = check("session/run ids resolvable for disk checks", False, f"sid={sid} runId={run_id}")
        return ok
    finally:
        p.kill()


def scenario_lock() -> bool:
    print("── lock：被活进程持有的 session 裸 resume 被拒，--fork-session 逃生（issue: zhou 07-10）──")
    # claude ≥2.1 给 --resume 加了硬锁：每个活进程把自己的 session 写进 ~/.claude/sessions/<pid>.json，
    # 裸 resume 启动时查表，被任何活进程（interactive / bg / 僵尸）持有即 exit 1 + stdout 全空 +
    # stderr 一行提示。daemon 的 Conversation.healSessionLock 靠匹配这行 stderr 的 marker 自动改
    # --fork-session 重试 —— 文案或退出行为漂移，自愈就静默失效、手机重新看到裸 process_exited。
    marker = "is currently running as a background agent"  # 与 Conversation.SESSION_LOCK_MARKER 保持一致
    env = dict(os.environ)
    env.pop("CLAUDECODE", None)
    env.pop("CLAUDE_CODE_ENTRYPOINT", None)
    os.makedirs(WORKDIR, exist_ok=True)
    workdir_real = os.path.realpath(WORKDIR)

    # 持锁者：一个卡在 Bash 审批上的 --bg agent（blocked 状态会一直持有 session，直到被 stop）
    bg = subprocess.run(
        [CLAUDE, "--bg", "Use the Bash tool to run exactly: sleep 300. Do not reply until it finishes."],
        capture_output=True, text=True, cwd=WORKDIR, env=env, timeout=120,
    )
    if not check("bg agent started", bg.returncode == 0, (bg.stderr or bg.stdout).strip()[:80]):
        return False
    held = short_id = None
    end = time.time() + 60
    while time.time() < end and not held:
        try:
            agents = json.loads(subprocess.run(
                [CLAUDE, "agents", "--json"], capture_output=True, text=True, env=env, timeout=30,
            ).stdout or "[]")
        except ValueError:
            agents = []
        for a in agents:
            if a.get("kind") in ("background", "bg") and os.path.realpath(a.get("cwd") or "") == workdir_real:
                held, short_id = a.get("sessionId"), a.get("id")
        if not held:
            time.sleep(2)
    if not check("bg agent registered (claude agents --json)", bool(held), f"sessionId={held}"):
        return False

    try:
        # ① 裸 resume（daemon 同款旗子）：应当启动即拒，stdout 一字不吐
        bare = subprocess.run(
            [CLAUDE, *BASE_ARGS, "--resume", held],
            input="", capture_output=True, text=True, cwd=WORKDIR, env=env, timeout=60,
        )
        ok = check("bare resume refused (exit 1)", bare.returncode == 1, f"exit={bare.returncode}")
        ok &= check("stderr carries the daemon's marker", marker in bare.stderr, repr(bare.stderr.strip())[:120])
        ok &= check("stdout empty (refusal precedes any stream)", not bare.stdout.strip(), f"{len(bare.stdout)} byte(s)")
        # ② --fork-session 逃生：正常 init（全新 id）+ turn 正常收尾
        fork = subprocess.run(
            [CLAUDE, *BASE_ARGS, "--resume", held, "--fork-session"],
            input=user_frame("Do not use any tools. Reply with exactly: ok") + "\n",
            capture_output=True, text=True, cwd=WORKDIR, env=env, timeout=120,
        )
        init_id, result_ok = None, False
        for line in fork.stdout.splitlines():
            try:
                j = json.loads(line)
            except ValueError:
                continue
            if j.get("type") == "system" and j.get("subtype") == "init":
                init_id = j.get("session_id")
            if j.get("type") == "result":
                result_ok = not j.get("is_error")
        ok &= check("--fork-session escapes the lock", fork.returncode == 0 and result_ok,
                    f"exit={fork.returncode}, stderr={repr(fork.stderr.strip())[:80]}")
        ok &= check("fork minted a fresh session id", bool(init_id) and init_id != held, f"{init_id}")
        return ok
    finally:
        if short_id:  # 释放持锁者，别把 blocked 的 bg agent 留在用户机器上
            subprocess.run([CLAUDE, "stop", short_id], capture_output=True, text=True, env=env, timeout=30)


def scenario_ask_plan() -> bool:
    # issue #55: users reported the phone couldn't pick an AskUserQuestion option in PLAN mode. Root cause
    # was NOT the wire — plan-mode's control_request is identical to default's (proven), so StreamParser and
    # PermissionBridge handle it unchanged. This scenario locks that invariant: if a future CLI ever gates
    # plan-mode questions differently (drops can_use_tool / changes subtype / stops sending it), it goes red
    # HERE first, instead of silently breaking the phone's question card.
    return scenario_ask("plan")


def scenario_fgtask() -> bool:
    print("── fgtask：前台 Bash 的 task 事件带前台 tool_use_id（#105 残留的幻影 job 抑制前提）──")
    # 2.1.206 实证：前台（非 run_in_background）Bash 与后台共用 task 机制，task_started 字段级一致
    # （task_id/tool_use_id/description/task_type=local_bash），只是它在命令完成时才到、且没有
    # background_tasks_changed/task_updated 相伴。daemon 无法凭形状区分前后台 —— 唯一可靠判据是
    # tool_use_id 指回一次 daemon 见过的、未带 run_in_background 的 tool_use
    # （BackgroundJobRegistry.foregroundBash）。这条相关性一断，抑制失效、幻影 job 回归。
    # 注意：秒回的命令（裸 echo）不走 task 机制 —— 必须带几秒执行时间才会触发（实证：sleep 3 触发、
    # 裸 echo 不触发），所以探针命令里有 sleep。
    p = Probe(["--permission-mode", "bypassPermissions"])
    try:
        p.send("Use the Bash tool to run exactly: echo fg_probe_marker && sleep 4 && echo fg_probe_end . "
               "Run it in the FOREGROUND (do not set run_in_background; no timeout tricks). "
               "Then reply with exactly DONE.")
        if not p.wait_for("result", timeout=120):
            return check("turn finished", False, "no result within 120s")
        time.sleep(2)  # 收尾事件
        bash_uses = [c for j in p.raw if j.get("type") == "assistant"
                     for c in j.get("message", {}).get("content", [])
                     if c.get("type") == "tool_use" and c.get("name") == "Bash"]
        fg = [c for c in bash_uses if not (c.get("input") or {}).get("run_in_background")]
        ok = check("foreground Bash tool_use present", bool(fg),
                   json.dumps((bash_uses[0].get("input") if bash_uses else {}) or {})[:80])
        if not fg:
            return False
        tid = fg[0].get("id")
        started = [j for j in p.raw if j.get("type") == "system" and j.get("subtype") == "task_started"]
        if not started:
            # 前台不再发 task 事件 = 回到老行为：抑制无事可抑、也不会误伤 —— 良性漂移，PASS 并留痕
            return check("fg emits no task events anymore (benign drift — suppression idles)", True, "0 task_started")
        ok &= check("fg task_started carries the fg tool_use_id",
                    any(j.get("tool_use_id") == tid for j in started),
                    f"ids={[j.get('tool_use_id') for j in started]} want={tid}")
        ok &= check("fg task_type is local_bash", all(j.get("task_type") == "local_bash" for j in started),
                    f"{[j.get('task_type') for j in started]}")
        return ok
    finally:
        p.kill()


def scenario_bgcontinue() -> bool:
    print("── bgcontinue：bg 任务完成后 CLI 无输入自动续 turn（#105 reaper 让位的安全性前提）──")
    # #105 的修复让 reaper 对活着的 agent 全面让位（活进程的 task 事件为准、忙会话不看时钟），赌的是
    # CLI 自己会把无人值守的计划走完：后台任务一完成（task_notification/task_updated 报 completed），
    # CLI 不需要任何新输入就开一个续命 turn（2.1.206 实证：新 init 在完成事件后 0.1s 内出现）执行
    # 剩余步骤。这条一漂移，让位就变成放任 —— 多步计划死在后台步骤后面，必须重估策略。
    artifact = os.path.join(WORKDIR, "bgcontinue_ok.txt")
    try:
        os.remove(artifact)  # 上次运行的残留会让本次误判
    except OSError:
        pass
    p = Probe(["--permission-mode", "bypassPermissions"])
    try:
        p.send("Step 1: use the Bash tool with run_in_background set to true to start exactly: "
               "sleep 15 && echo probe_bg_done . Step 2: AFTER that background command completes, "
               "create a file named bgcontinue_ok.txt containing the word ok. Do both steps.")
        if not p.wait_for("result", timeout=120):
            return check("first result", False, "no result within 120s")
        ok = True
        # 此后不再发送任何输入：第二个 result 只能来自 CLI 的自动续 turn
        ok &= check("unprompted follow-up turn (2nd result)", p.wait_for("result", count=2, timeout=150),
                    f"{len(p.of('result'))} result(s)")
        time.sleep(1)
        done = [j for j in p.raw if j.get("type") == "system" and (
            (j.get("subtype") == "task_notification" and j.get("status") == "completed") or
            (j.get("subtype") == "task_updated" and (j.get("patch") or {}).get("status") == "completed"))]
        ok &= check("completion task_* event arrived", bool(done),
                    f"{len(done)} completed event(s)")
        ok &= check("follow-up side effect landed", os.path.exists(artifact), artifact)
        return ok
    finally:
        p.kill()
        try:
            os.remove(artifact)
        except OSError:
            pass


def _allow_control(p: Probe, j: dict):
    """Answer a can_use_tool control_request with allow, echoing its input (simulates the daemon saying
    'permitted' — the daemon's REAL guest guard denies out-of-scope BEFORE this, but here we allow so the
    turn proceeds and we can observe which calls the CLI actually routed to --permission-prompt-tool)."""
    rid = j.get("request_id")
    inner = j.get("request", {})
    p.send_raw({"type": "control_response", "response": {"subtype": "success", "request_id": rid,
                "response": {"behavior": "allow", "updatedInput": inner.get("input") or {}}}})


def _control_reqs_for(p: Probe):
    """All can_use_tool control_requests seen so far, as (tool_name, input_dict)."""
    out = []
    for _, _, j in p.of("control_request"):
        inner = j.get("request", {})
        if inner.get("subtype") == "can_use_tool":
            out.append((inner.get("tool_name"), inner.get("input") or {}))
    return out


def scenario_scope(mode: str = "default") -> bool:
    print(f"── scope（--permission-mode {mode}）：越界文件工具是否路由到 --permission-prompt-tool（#115 越界 deny 前提）──")
    # #115 的整份「guest 碰不到你其他文件夹」保证挂在一条 CLI 行为上：guest 会话适用的每个 mode 下，
    # 内置文件工具（Read/Write/Edit/…）的每次调用都必须经 --permission-prompt-tool 浮现一个
    # can_use_tool control_request —— 只有这样 daemon 的 PermissionBridge.outOfScopeTarget 才拦得到并硬 deny。
    # 若某 mode 下 CLI 对越界路径自动放行（无 control_request 就把 Read 内容吐出 / 把 Write 落盘），
    # 守卫根本不被调用 = 保证被绕过。此探针在共享根外埋一个 secret，让裸 claude（default / acceptEdits，
    # guest 永不 bypass）去读/写越界绝对路径，断言每次越界都先来 control_request。
    base = os.path.join(WORKDIR, "scope")
    shared = os.path.join(base, "shared")   # the guest session cwd == a shared root (in scope)
    outside = os.path.join(base, "outside")  # the owner's OTHER folder (out of scope)
    shutil.rmtree(base, ignore_errors=True)
    os.makedirs(shared); os.makedirs(outside)
    secret = os.path.join(outside, "secret.txt")
    marker = "TOPSECRET_OOB_MARKER"
    with open(secret, "w") as fh:
        fh.write(marker + "\n")
    write_target = os.path.join(outside, "planted.txt")
    try:
        os.remove(write_target)
    except OSError:
        pass
    # mirror the REAL guest launch (ClaudeLauncher clean-room) so tool availability matches production and
    # the owner's private settings/hooks don't color the result
    clean = ["--setting-sources", "", "--strict-mcp-config", "--mcp-config", '{"mcpServers":{}}']
    p = Probe(["--permission-mode", mode, *clean], cwd=shared)
    try:
        p.send(
            f"Do exactly two tool calls, then reply. 1) Use the Read tool to read the file at the absolute "
            f"path {secret}. 2) Use the Write tool to create a file at the absolute path {write_target} with "
            f"the exact contents PLANTED_BY_PROBE. Then reply with exactly SCOPE_DONE.",
        )
        # answer any control_request with allow so the turn proceeds; we only care WHICH calls were routed here
        deadline = time.time() + 150
        answered = set()
        while time.time() < deadline and not p.of("result"):
            for _, _, j in p.of("control_request"):
                rid = j.get("request_id")
                if rid not in answered and j.get("request", {}).get("subtype") == "can_use_tool":
                    answered.add(rid); _allow_control(p, j)
            time.sleep(0.4)
        time.sleep(2)
        reqs = _control_reqs_for(p)
        read_routed = any(t == "Read" and os.path.realpath(str(i.get("file_path", ""))) == os.path.realpath(secret) for t, i in reqs)
        write_routed = any(t == "Write" and os.path.realpath(str(i.get("file_path", ""))) == os.path.realpath(write_target) for t, i in reqs)
        planted = os.path.exists(write_target)
        # THE decisive signal for a read leak: the out-of-scope file's content reached the model's context via
        # ANY tool_result (Read/Bash/…). Checking the model's REPLY text is too weak — it may read then not echo.
        read_leaked = any(marker in e[2] for e in p.of("tool_result"))
        ok = True
        # A tool is "guarded" only if it ROUTES to --permission-prompt-tool (fires a control_request) — that is
        # the ONLY hook PermissionBridge.outOfScopeTarget has. A file effect that happens with NO control_request
        # means the CLI auto-applied it and the daemon guard was NEVER consulted → the #115 deny is bypassed.
        write_ok = check(f"[{mode}] out-of-scope Write routed to prompt-tool (guard can deny)",
                         write_routed, f"routed={write_routed}, planted={planted}")
        read_ok = check(f"[{mode}] out-of-scope Read routed to prompt-tool (guard can deny)",
                        read_routed, f"routed={read_routed}, content_leaked_to_context={read_leaked}")
        no_silent_read = check(f"[{mode}] no SILENT out-of-scope read (no leak without an ask)",
                               read_routed or not read_leaked,
                               f"leaked={read_leaked} routed={read_routed}")
        no_silent_write = check(f"[{mode}] no SILENT out-of-scope write (nothing planted without an ask)",
                                write_routed or not planted, f"planted={planted} routed={write_routed}")
        ok = write_ok and read_ok and no_silent_read and no_silent_write
        if not (read_routed) and read_leaked:
            print(f"     ⚠️  [{mode}] 越界 READ 未经 --permission-prompt-tool 就把内容读入模型上下文 = #115 守卫被绕过"
                  "（read-only 工具在 default/acceptEdits 下自动放行，PermissionBridge.outOfScopeTarget 根本不被调用）。")
            print("     堵法：daemon 侧对 guest 会话注入一个 CLI 内 PreToolUse 钩子（在工具执行前按 pathScope 判越界即"
                  "block Read/Glob/Grep），而非依赖 --permission-prompt-tool 路由；或为 guest 走文件系统受限沙箱。"
                  "仅靠 PermissionBridge 无法拦 —— 它只在 control_request 上触发，而 read-only 工具不发 control_request。")
        return ok
    finally:
        p.kill()
        try:
            os.remove(write_target)
        except OSError:
            pass


def scenario_scope_acceptedits() -> bool:
    # acceptEdits is the mode of concern: it auto-APPLIES in-workspace edits without an ask. The invariant
    # this locks: an out-of-WORKSPACE edit is NOT auto-applied — it still routes to the prompt tool so the
    # guest guard can deny it. If a future CLI ever auto-applies edits outside cwd under acceptEdits, it goes
    # red HERE before it silently breaks the folder-share boundary.
    return scenario_scope("acceptEdits")


def scenario_settingsources() -> bool:
    print("── settingsources：共享目录内 .claude/settings.json 的 allow 规则不得自动放行（#115 H2）──")
    # H2: the shared folder's own .claude/settings.json lives INSIDE the guest's cwd, is guest-writable and
    # often repo-committed. Its permissions.allow rules would let the CLI AUTO-APPROVE tools WITHOUT routing
    # them through --permission-prompt-tool — silently bypassing the daemon's path guard + tier clamp. The
    # fix is launching the guest with `--setting-sources ""` (load NO settings sources). This scenario proves
    # the flag actually closes the hole: same allow rule, control_request SUPPRESSED without the flag,
    # RESTORED with it — so the daemon stays the sole permission authority.
    base = os.path.join(WORKDIR, "ss")
    shared = os.path.join(base, "shared")

    def run(extra, tag):
        shutil.rmtree(base, ignore_errors=True)
        os.makedirs(os.path.join(shared, ".claude"))
        # a Write allow rule that, IF loaded, pre-approves the Write → no prompt-tool call. Write (not Bash)
        # because we've PROVEN Write routes to the prompt-tool in clean-room default — so the ONLY thing that
        # can suppress its control_request is this settings.json rule being honored. Deterministic vehicle.
        with open(os.path.join(shared, ".claude", "settings.json"), "w") as fh:
            json.dump({"permissions": {"allow": ["Write"]}}, fh)
        out = os.path.join(shared, "ss_probe_out.txt")
        p = Probe(["--permission-mode", "default", *extra], cwd=shared)
        try:
            p.send(f"Use the Write tool to create a file at the absolute path {out} with the exact contents "
                   f"SS_MARKER. Then reply with exactly SS_DONE.")
            deadline = time.time() + 100
            while time.time() < deadline and not p.of("result"):
                for _, _, j in p.of("control_request"):
                    if j.get("request", {}).get("subtype") == "can_use_tool":
                        _allow_control(p, j)  # allow so the write lands → confirms the model DID attempt it
                time.sleep(0.4)
            time.sleep(1.5)
            asked = any(t == "Write" for t, _ in _control_reqs_for(p))
            attempted = os.path.exists(out) or asked  # file created (auto-applied) OR routed = model did try
            return asked, attempted
        finally:
            p.kill()

    # A) settings loaded (project source, the default) — INFORMATIONAL: whether the CLI's trust model already
    #    ignores an untrusted shared folder's allow rules in headless -p (a second barrier), or honors them.
    asked_loaded, tried_loaded = run([], "loaded")
    # B) guest clean-room (--setting-sources "") — THE load-bearing requirement: the shared settings.json allow
    #    rule must NOT auto-approve, so the Write still routes to the prompt-tool and the guard stays in the loop.
    asked_clean, tried_clean = run(["--setting-sources", ""], "clean")

    ok = True
    ok &= check("model attempted the Write in both runs (signal is meaningful)", tried_loaded and tried_clean,
                f"tried_loaded={tried_loaded} tried_clean={tried_clean}")
    ok &= check("with --setting-sources \"\" the shared settings.json allow rule does NOT auto-approve "
                "(guard stays in the loop)", asked_clean,
                f"Write control_request fired={asked_clean} (required True)")
    # annotate the second barrier so the verdict is honest, not a bare pass/fail
    if asked_loaded:
        print("     ℹ️  即便不加 flag，headless -p 也不认「未受信任的共享目录」的 project allow 规则（trust 门槛做第二道"
              "屏障）——所以共享目录 settings.json 这条向量本就难自动放行；--setting-sources \"\" 是显式关死它，"
              "且它真正 load-bearing 的是剥掉 OWNER 的 ~/.claude/settings.json 里受信任的 allow（如裸 'Read'，见 scope 场景）。")
    else:
        print("     ℹ️  不加 flag 时共享目录 allow 规则会自动放行（本机 CLI 认了 project allow）——此时 "
              "--setting-sources \"\" 是关死该洞的唯一屏障。")
    return ok


def main():
    which = sys.argv[1] if len(sys.argv) > 1 else "all"
    version = subprocess.run([CLAUDE, "--version"], capture_output=True, text=True).stdout.strip()
    print(f"claude: {CLAUDE} ({version})\n")
    scenarios = {
        "steer": scenario_steer, "queue": scenario_queue, "ask": scenario_ask, "ask_plan": scenario_ask_plan,
        "task": scenario_task, "workflow": scenario_workflow, "lock": scenario_lock,
        "fgtask": scenario_fgtask, "bgcontinue": scenario_bgcontinue,
        "scope": scenario_scope, "scope_acceptedits": scenario_scope_acceptedits,
        "settingsources": scenario_settingsources,
    }
    run = scenarios.values() if which == "all" else [scenarios[which]]
    results = [fn() for fn in run]
    print()
    if all(results):
        print("✅ wire behavior unchanged — App 依赖的八条 CLI 行为全部成立")
        sys.exit(0)
    print("❌ CLI 行为漂移！排查 daemon 的 StreamParser/PermissionBridge/AskQuestions 是否需要适配")
    sys.exit(1)


if __name__ == "__main__":
    main()
