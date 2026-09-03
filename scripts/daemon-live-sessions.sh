#!/usr/bin/env bash
# 列出本机 daemon 正在驱动的会话进程 —— daemon 的每个直接子进程就是一个 live 会话的 CLI
# （claude --repl / codex app-server …；MCP server 等是 CLI 的孙进程，不会被算进来）。
#
# 用法：daemon-live-sessions.sh [要排除的pid ...]
#   调用方在会话内跑更新时，把自身谱系 pid 传进来排除掉「自己这条会话」。
#   输出每行 "pid<TAB>command（截断）"；没有活会话则无输出。
#   ⚠️ 查 daemon 必须走 ps（macOS pgrep -f 匹配不到超长 java classpath，见 CLAUDE.md）。
set -euo pipefail

exclude=" $* "
ps aux | grep 'cc-pocket-daemon/lib' | grep -v grep | awk '{print $2}' | while read -r d; do
  ps -axo pid=,ppid=,command= | while read -r pid ppid cmd; do
    [ "$ppid" = "$d" ] || continue
    case "$exclude" in *" $pid "*) continue ;; esac
    printf '%s\t%.140s\n' "$pid" "$cmd"
  done
done
