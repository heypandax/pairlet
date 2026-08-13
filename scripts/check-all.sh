#!/usr/bin/env bash
# 核心测试一把跑：协议 / daemon / relay / 手机端 Desktop。
# 设置 CHECK_IOS=1 可在 Apple Silicon macOS 上追加 iOS Simulator 测试。
# 用法：bash scripts/check-all.sh [额外 gradle 参数]
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
if [[ -z "${ANDROID_HOME:-}" && -d /opt/homebrew/share/android-commandlinetools ]]; then
  export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
fi
python3 scripts/check-firebase-placeholders.py
tasks=(:protocol:allTests :daemon:test :relay:test :mobile:composeApp:desktopTest)
mobile_targets="Desktop"
if [[ "${CHECK_IOS:-0}" == "1" ]]; then
  if [[ "$(uname -s)" != "Darwin" || "$(uname -m)" != "arm64" ]]; then
    echo "ERROR: CHECK_IOS=1 requires Apple Silicon macOS." >&2
    exit 1
  fi
  booted_simulators="$(xcrun simctl list devices booted)"
  if [[ "$booted_simulators" != *" (Booted)"* ]]; then
    echo "ERROR: iOS tests require a booted Simulator (open Simulator, then rerun)." >&2
    exit 1
  fi
  tasks+=(:mobile:composeApp:iosSimulatorArm64Test)
  mobile_targets="Desktop + iOS Simulator"
fi
./gradlew "${tasks[@]}" "$@"
echo "✅ protocol + daemon + relay + mobile ($mobile_targets) 全绿"
