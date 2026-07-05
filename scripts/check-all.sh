#!/usr/bin/env bash
# 三套测试一把跑：协议 / daemon / 手机端。改协议或跨端功能后的标准验证。
# 用法：bash scripts/check-all.sh [额外 gradle 参数]
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
./gradlew :protocol:allTests :daemon:test :mobile:composeApp:desktopTest "$@"
echo "✅ protocol + daemon + mobile 全绿"
