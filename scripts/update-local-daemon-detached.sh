#!/usr/bin/env bash
# 会话内安全更新本机 daemon。
#
# 背景：cc-pocket 驱动的 claude 会话里直接跑 update-local-daemon.sh，bootout 会连坐杀掉
# claude 自己（exit 137），会话中途暴毙、报告发不出去。本脚本：
#   1) 谱系自检 —— 向上走进程树看本进程是否 daemon 后代
#      （⚠️ macOS pgrep -f 匹配不到超长 java classpath 里的关键字，判断必须走 ps）；
#   2) 预热构建 installDist —— 不碰运行中的 daemon，缩短停机窗口；构建失败就地退出，绝不带病点火；
#   3) daemon 驱动 → python 双 fork + setsid + fd 脱离，延迟 $DELAY 秒点火，让会话来得及交代收尾；
#      普通终端 → 直接前台跑 update-local-daemon.sh。
#
# 会话断开属预期：launchd KeepAlive 拉起新 daemon（数秒），手机自动重连，重新进入会话即可。
# 用法：bash scripts/update-local-daemon-detached.sh   （可选 DELAY=秒 LOG=路径）
set -euo pipefail
cd "$(dirname "$0")/.."

DELAY="${DELAY:-20}"
LOG="${LOG:-/tmp/cc-pocket-update.log}"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"

driven=0
pid=$$
for _ in 1 2 3 4 5 6 7 8; do
  pid="$(ps -o ppid= -p "$pid" 2>/dev/null | tr -d ' ')"
  { [ -z "$pid" ] || [ "$pid" -le 1 ]; } && break
  if ps -o command= -p "$pid" | grep -q "cc-pocket-daemon/lib"; then driven=1; break; fi
done

echo "── 预热构建（installDist）──"
./gradlew :daemon:installDist -q

if [ "$driven" = "0" ]; then
  echo "── 非 daemon 驱动（普通终端）→ 直接前台更新 ──"
  exec bash scripts/update-local-daemon.sh
fi

echo "── 本会话由 daemon 驱动 → ${DELAY}s 后脱离式更新（日志 $LOG）──"
echo "   会话将断开（预期）；daemon 重启后手机自动重连，重新进入会话即可。"
python3 - "$DELAY" "$LOG" "$PWD" <<'PY'
import os, subprocess, sys, time
delay, log_path, repo = int(sys.argv[1]), sys.argv[2], sys.argv[3]
if os.fork() > 0:
    sys.exit(0)
os.setsid()  # macOS 无 setsid 命令、裸 setsid 静默失败 —— 只能在 python 里做
if os.fork() > 0:
    os._exit(0)
devnull = os.open(os.devnull, os.O_RDWR)
for fd in (0, 1, 2):
    os.dup2(devnull, fd)  # 脱离 pty，任何 fd 都不再拴在 daemon 的进程树上
time.sleep(delay)
env = dict(os.environ)
env.setdefault("JAVA_HOME", "/opt/homebrew/opt/openjdk@17")
with open(log_path, "ab") as log:
    log.write(b"\n===== detached update start =====\n"); log.flush()
    subprocess.run(["bash", "scripts/update-local-daemon.sh"], stdout=log, stderr=log, cwd=repo, env=env)
    log.write(b"===== detached update end =====\n"); log.flush()
os._exit(0)
PY
