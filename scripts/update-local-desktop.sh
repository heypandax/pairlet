#!/usr/bin/env bash
# 一键更新本机桌面 App（与 update-local-daemon.sh 相对应）：
#   构建 createDistributable → 退出运行中的 App → rsync --delete 替换 /Applications → 重新启动。
# rsync --delete 很重要：jpackage 的 app 目录按内容 hash 命名 jar，增量覆盖会把历代旧 jar 越堆越多。
set -euo pipefail
cd "$(dirname "$0")/.."

APP_SRC="mobile/composeApp/build/compose/binaries/main/app/CC Pocket.app"
APP_DST="/Applications/CC Pocket.app"
LEGACY_DST="/Applications/cc-pocket.app"   # 改名前（≤1.2.2 dev）的安装位置，避免留下两个 App

echo "── 1/4 构建桌面 App（createDistributable）──"
# 本机只有 Homebrew JDK：跳过 compose 的 vendor 检查（仅本地 dev 安装，不做分发签名）
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :mobile:composeApp:createDistributable \
  -Pcompose.desktop.packaging.checkJdkVendor=false --quiet
[ -d "$APP_SRC" ] || { echo "构建产物不存在：$APP_SRC"; exit 1; }

echo "── 2/4 退出运行中的 App ──"
osascript -e 'quit app "CC Pocket"' >/dev/null 2>&1 || true
osascript -e 'quit app "cc-pocket"' >/dev/null 2>&1 || true
for _ in $(seq 1 10); do pgrep -qf '/Applications/(CC Pocket|cc-pocket)\.app/Contents/MacOS' || break; sleep 0.5; done
pkill -f '/Applications/CC Pocket.app/Contents/MacOS' 2>/dev/null || true
pkill -f '/Applications/cc-pocket.app/Contents/MacOS' 2>/dev/null || true
[ -d "$LEGACY_DST" ] && { echo "移除旧安装 $LEGACY_DST"; rm -rf "$LEGACY_DST"; }

echo "── 3/4 同步到 $APP_DST ──"
rsync -a --delete "$APP_SRC/" "$APP_DST/"

echo "── 4/4 启动 ──"
open "$APP_DST"
jar=$(basename "$APP_DST"/Contents/app/composeApp-desktop-*.jar)
echo "✅ 完成：已装载 $jar 并重新启动"
