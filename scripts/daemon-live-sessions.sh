#!/usr/bin/env bash
# 列出本机 daemon 正在驱动的会话进程 —— daemon 的每个直接子进程就是一个 live 会话的 CLI
# （claude --repl / codex app-server …；MCP server 等是 CLI 的孙进程，不会被算进来）。
#
# 用法：daemon-live-sessions.sh [要排除的pid ...]
#   调用方在会话内跑更新时，把自身谱系 pid 传进来排除掉「自己这条会话」。
#   输出每行 "pid<TAB>command（截断）"；没有活会话则无输出，退出码 0。
#   ⚠️ 查 daemon 必须走 ps（macOS pgrep -f 匹配不到超长 java classpath，见 CLAUDE.md）。
set -euo pipefail

exclude=" $* "
# 先把 daemon pid 列表兜住再遍历：没有 daemon 在跑时 `grep -v grep` 以 1 退出，pipefail 会把整条
# 管道判为失败；调用方（两个 update 脚本）是 set -e 的，会在会话门这一步被静默中止（2026-09-04 实测，
# 恰好是 daemon 已死、最需要更新的场景）。没有 daemon ⇒ 没有活会话 ⇒ 空输出 + 退出 0。
daemons="$(ps aux | grep 'cc-pocket-daemon/lib' | grep -v grep | awk '{print $2}' || true)"
for d in $daemons; do
  ps -axo pid=,ppid=,command= | while read -r pid ppid cmd; do
    [ "$ppid" = "$d" ] || continue
    case "$exclude" in *" $pid "*) continue ;; esac
    printf '%s\t%.140s\n' "$pid" "$cmd"
  done
done
