#!/usr/bin/env bash
# 平滑更新本机 cc-pocket daemon（改完代码后一键跑这个）。
#
# 解决今天踩过的所有坑：
#  - cask app-image 在本机 TUN 代理下连不上 relay → 用 java 的 installDist 构建。
#  - daemon/build/install 在 ~/Desktop 下，launchd 无权执行（TCC，Operation not permitted）
#    → 装到可执行位置 ~/Library/Application Support/cc-pocket/。
#  - 两个 daemon 抢 relay 账号 + 8799 互 kill → 先杀干净所有实例，再只留一个。
#  - service-install 自带单实例逻辑（removeSiblingAgents）→ 复用它写 plist + 加载。
#
# 用法：JAVA_HOME 可选（默认 openjdk@17）；直接 bash scripts/update-local-daemon.sh
#       FORCE=1 跳过「正在进行的会话」确认门（仅限已经和用户确认过）。
set -euo pipefail
cd "$(dirname "$0")/.."

JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"; export JAVA_HOME
RELAY="${RELAY:-wss://pocket.ark-nexus.cc}"
DEST="$HOME/Library/Application Support/cc-pocket/cc-pocket-daemon"
DIST="daemon/build/install/cc-pocket-daemon"
BIN="$DEST/bin/cc-pocket-daemon"
UID_N="$(id -u)"

# ── 0/6 会话检查：daemon 有正在进行的会话时不许静默点火（用户规则，2026-09-03）──
# 重启 daemon 会中断它驱动的所有会话；有活会话必须先经用户确认（FORCE=1 表示已确认）。
# 自身谱系要排除 —— 会话内跑更新时「自己这条会话」断开属预期，不构成拦截理由。
if [ "${FORCE:-0}" != "1" ]; then
  self_line=""; p=$$
  for _ in 1 2 3 4 5 6 7 8 9 10 11 12; do
    p="$(ps -o ppid= -p "$p" 2>/dev/null | tr -d ' ')"
    { [ -z "$p" ] || [ "$p" -le 1 ]; } && break
    self_line="$self_line $p"
  done
  LIVE="$(bash scripts/daemon-live-sessions.sh $self_line)"
  if [ -n "$LIVE" ]; then
    echo "⛔ daemon 还有正在进行的会话（重启会中断它们）："
    echo "$LIVE" | sed 's/^/   /'
    if [ -t 0 ]; then
      read -r -p "   仍要继续更新并中断以上会话？[y/N] " ans
      [ "$ans" = "y" ] || [ "$ans" = "Y" ] || { echo "   已取消。"; exit 1; }
    else
      echo "   请先和用户确认；确认后 FORCE=1 bash scripts/update-local-daemon.sh 重跑。"
      exit 1
    fi
  fi
fi

echo "── 1/6 构建 daemon（installDist）──"
./gradlew :daemon:installDist -q

echo "── 2/6 安装到可执行位置：$DEST ──"
rm -rf "$DEST"; mkdir -p "$DEST"
cp -R "$DIST/bin" "$DIST/lib" "$DEST/"

echo "── 3/6 停掉所有现存 daemon（保证单实例）──"
launchctl bootout "gui/$UID_N/dev.ccpocket.daemon" 2>/dev/null || true
pkill -f "cc-pocket-daemon run" 2>/dev/null || true
pkill -f "Application Support/cc-pocket.*MainKt" 2>/dev/null || true
pkill -f "build/install/cc-pocket.*MainKt" 2>/dev/null || true
sleep 2
lsof -ti :8799 2>/dev/null | xargs -r kill -9 2>/dev/null || true   # 清掉占 8799 的僵尸
sleep 1

echo "── 4/6 注册单实例 launchd 服务（指向 java 构建）──"
# service-install 会写 plist(ProgramArguments=此 exec + run --relay)、删除其它 dev.ccpocket.daemon*.plist、load
"$BIN" service-install --apply --exec "$BIN" --relay "$RELAY"

echo "── 5/6 等待起来 ──"; sleep 7

echo "── 6/6 校验：单实例 + relay 已连 ──"
PID="$(pgrep -f 'Application Support/cc-pocket.*MainKt' | head -1 || true)"
COUNT="$(pgrep -f 'cc-pocket-daemon run|Application Support/cc-pocket.*MainKt|build/install/cc-pocket.*MainKt' | wc -l | tr -d ' ')"
SOCK=0; [ -n "$PID" ] && SOCK="$(lsof -nP -p "$PID" 2>/dev/null | grep -c ':443.*ESTABLISHED' || true)"
echo "   daemon 进程数=$COUNT  pid=$PID  relay-socket=$SOCK"
echo "   服务: $(launchctl list | grep -i ccpocket || echo '未加载')"
if [ "$COUNT" = "1" ] && [ "$SOCK" -ge 1 ]; then
  echo "✅ 完成：单实例已连上 relay，手机可用"
else
  echo "⚠️  未达预期（进程数应=1、relay-socket≥1）。看日志：~/Library/Logs/cc-pocket/daemon.err.log"
  echo "   常见：relay-socket=0 多半是 TUN 代理没放行——把 pocket.ark-nexus.cc 加进代理直连/规则再重跑。"
  exit 1
fi
