#!/usr/bin/env bash
# 核心测试一把跑：协议 / daemon / relay / 手机端 Desktop。
# 设置 CHECK_IOS=1 可在 Apple Silicon macOS 上追加 iOS Simulator 测试。
#
# 用法：
#   bash scripts/check-all.sh                    # 全量（合并/发版门禁）
#   bash scripts/check-all.sh --affected         # 按 git 改动只跑受影响模块（迭代收尾用）
#   bash scripts/check-all.sh --affected main    # 同上，但 diff 基线用 main（分支收尾用）
#   bash scripts/check-all.sh [额外 gradle 参数]
#
# 分档惯例（谁在什么时候跑什么）：
#   迭代中   → 直接 ./gradlew :模块:test --tests '<TestClass>'（秒级，不走本脚本）
#   单件收尾 → --affected（改哪跑哪；改了 protocol 视为全影响）
#   合并门禁 → 无参数全量（集成分支合并、发版前，各跑一次）
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
if [[ -z "${ANDROID_HOME:-}" && -d /opt/homebrew/share/android-commandlinetools ]]; then
  export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
fi
python3 scripts/check-firebase-placeholders.py
# :protocol:allTests 含 iOS Simulator target，本机 Xcode 缺对应 SDK 时在**配置期**即失败
# （Xcode does not support simulator tests for ios_simulator_arm64）——与代码无关。默认只跑
# JVM 侧；iOS 侧统一归 CHECK_IOS=1 开关（下面会一并追加 protocol 的模拟器测试）。
tasks=(:protocol:jvmTest :daemon:test :relay:test :mobile:composeApp:desktopTest)

# --affected [基线]：按 git 改动（工作区+暂存+基线以来的提交）圈定受影响模块。
# protocol 是所有模块的依赖，动它 = 全量；脚本/文档等模块外改动不触发任何测试模块。
if [[ "${1:-}" == "--affected" ]]; then
  shift                                  # 先吃掉参数，剩余 "$@" 原样透传 gradle
  base="HEAD"
  if [[ $# -gt 0 && "$1" != -* && "$1" != :* ]]; then base="$1"; shift; fi
  changed="$( { git diff --name-only "$base" 2>/dev/null; git diff --name-only --cached; git status --porcelain | awk '{print $2}'; } | sort -u )"
  if echo "$changed" | grep -q '^protocol/'; then
    echo "── --affected：protocol 有改动 → 全量 ──"
  else
    tasks=()
    echo "$changed" | grep -q '^daemon/'  && tasks+=(:daemon:test)
    echo "$changed" | grep -q '^relay/'   && tasks+=(:relay:test)
    echo "$changed" | grep -q '^mobile/'  && tasks+=(:mobile:composeApp:desktopTest)
    if [[ ${#tasks[@]} -eq 0 ]]; then
      echo "✅ --affected：改动不涉及测试模块（$(echo "$changed" | head -3 | tr '\n' ' ')…），无需跑测试"
      exit 0
    fi
    echo "── --affected（基线 $base）→ ${tasks[*]} ──"
  fi
fi
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
  tasks+=(:protocol:iosSimulatorArm64Test :mobile:composeApp:iosSimulatorArm64Test)
  mobile_targets="Desktop + iOS Simulator"
fi
./gradlew "${tasks[@]}" "$@"
echo "✅ protocol + daemon + relay + mobile ($mobile_targets) 全绿"
