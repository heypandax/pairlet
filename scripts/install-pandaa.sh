#!/usr/bin/env bash
# 构建 iOS App 并安装到 iPhone（默认 Pandaa = iPhone 14 Pro Max）。
#
# 坑位知识（都踩过）：
#  - devicectl 的 Identifier 是 CoreDevice UUID ≠ xcodebuild 要的硬件 UDID —— 构建用 generic 目的地，
#    安装才用 UUID；
#  - 管道接 tail 会吞掉 xcodebuild 非零退出码 → 日志落文件再 grep BUILD SUCCEEDED；
#  - DerivedData 可能残留旧产物造成"装了但没更新"的假成功 → 装前校验二进制 5 分钟内新鲜。
#
# 用法：bash scripts/install-pandaa.sh [CoreDevice-UUID]
set -euo pipefail
cd "$(dirname "$0")/.."

DEVICE="${1:-24645275-94DF-5933-A570-22A9C5FE9AF9}" # Pandaa
BUNDLE_ID="com.panda.ccpocket"
LOG=/tmp/ios-device-build.log

echo "── 0/4 设备在线检查 ──"
if ! xcrun devicectl list devices | grep "$DEVICE" | grep -q "available"; then
  echo "❌ 设备不在线：$DEVICE"
  xcrun devicectl list devices
  exit 1
fi

echo "── 1/4 构建（generic/platform=iOS，日志 $LOG）──"
(cd iosApp && xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -destination 'generic/platform=iOS' -allowProvisioningUpdates build > "$LOG" 2>&1) || true
if ! grep -q "BUILD SUCCEEDED" "$LOG"; then
  echo "❌ 构建失败，日志尾部："
  tail -30 "$LOG"
  exit 1
fi

APP="$(ls -dt "$HOME"/Library/Developer/Xcode/DerivedData/iosApp-*/Build/Products/Debug-iphoneos/cc-pocket.app | head -1)"
if ! find "$APP/cc-pocket" -mmin -5 | grep -q .; then
  echo "❌ 二进制不新鲜（>5 分钟），疑似旧 DerivedData：$APP"
  exit 1
fi

echo "── 2/4 安装 → $DEVICE ──"
xcrun devicectl device install app --device "$DEVICE" "$APP"

echo "── 3/4 拉起 $BUNDLE_ID ──"
xcrun devicectl device process launch --device "$DEVICE" "$BUNDLE_ID"

echo "✅ 4/4 完成：$APP"
