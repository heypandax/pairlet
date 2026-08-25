#!/usr/bin/env bash
# 构建 iOS App 并安装到 iPhone（真机）。
#
# 坑位知识（都踩过）：
#  - devicectl 的 Identifier 是 CoreDevice UUID ≠ xcodebuild 要的硬件 UDID —— 构建用 generic 目的地，
#    安装才用 UUID；
#  - 管道接 tail 会吞掉 xcodebuild 非零退出码 → 日志落文件再 grep BUILD SUCCEEDED；
#  - DerivedData 可能残留旧产物造成"装了但没更新"的假成功 → 装前校验二进制 5 分钟内新鲜。
#
# 用法：bash scripts/install-pandaa.sh [CoreDevice-UUID]（缺省读 .env 的 IOS_DEVICE_UUID）
#
# 设备连不上/装不上时自动转投 F2.im（fir.im）OTA：走 scripts/ios-fir.sh development 出签名 IPA 并
# 发布，手机上开 fir 短链自装。构建失败不转投（那是代码问题不是设备问题）。PANDAA_NO_FIR=1 关闭。
set -euo pipefail
cd "$(dirname "$0")/.."

# USB/Wi-Fi 装不进去时的 OTA 兜底。成功后以 0 退出——「用户能装到手机」这个目标已达成，只是换了条路。
publish_fir() {
  echo "── 设备不可达/安装失败 → 转投 F2.im OTA（scripts/ios-fir.sh development）──"
  if [[ "${PANDAA_NO_FIR:-}" == "1" ]]; then
    echo "❌ PANDAA_NO_FIR=1：不转投，按失败退出"
    exit 1
  fi
  bash scripts/ios-fir.sh development
  echo "✅ 已发布 F2.im：在手机浏览器打开上方 fir 输出的短链安装（Debug 直装恢复后重跑本脚本即可）"
  exit 0
}

# 设备 UUID 是个人环境值，不进仓库：从 $1 或 .env 的 IOS_DEVICE_UUID 取
[[ -f .env ]] && source .env
DEVICE="${1:-${IOS_DEVICE_UUID:-}}"
if [[ -z "$DEVICE" ]]; then
  echo "❌ 未指定设备：bash scripts/install-pandaa.sh <CoreDevice-UUID>，或在 .env 写 IOS_DEVICE_UUID=<UUID>"
  echo "   已配对设备列表：xcrun devicectl list devices"
  exit 1
fi
BUNDLE_ID="com.panda.ccpocket"
LOG=/tmp/ios-device-build.log

echo "── 0/4 设备在线检查 ──"
if ! xcrun devicectl list devices | grep "$DEVICE" | grep -Eq '(^|[[:space:]])(available|connected)([[:space:]]|$)'; then
  echo "⚠️ 设备不在线：$DEVICE"
  xcrun devicectl list devices
  publish_fir
fi

echo "── 1/4 构建（generic/platform=iOS，日志 ${LOG}）──"
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
xcrun devicectl device install app --device "$DEVICE" "$APP" || publish_fir

echo "── 3/4 拉起 $BUNDLE_ID ──"
# 装上了只是拉起失败（比如手机中途锁屏）不值得转 OTA——手点图标就行
xcrun devicectl device process launch --device "$DEVICE" "$BUNDLE_ID" || echo "⚠️ 拉起失败（已安装，手动打开即可）"

echo "✅ 4/4 完成：$APP"
