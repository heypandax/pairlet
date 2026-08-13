#!/usr/bin/env bash
# 构建 HarmonyOS 客户端 HAP（本机 DevEco Studio 工具链，macOS）。
#
# 用法：bash scripts/build-harmony.sh [额外 hvigor 参数]
# 示例：bash scripts/build-harmony.sh -p buildMode=release
# 产物：harmony/entry/build/default/outputs/default/entry-default-unsigned.hap
#
# 说明：
# - hvigor wrapper（~270MB）按 README 约定放 harmony/tools/hvigor（已 gitignore），
#   缺失时自动从本机 DevEco 拷贝；DevEco 升级后删掉该目录重跑即可刷新。
# - 产物为 unsigned：HarmonyOS NEXT 无开放侧载，真机安装需 DevEco 签名
#   （Signing Configs → 自动化签名），面向用户分发需上架 AppGallery。
#   本脚本的核心价值是 ArkTS 编译回归门 + 发版时产出可归档的构建产物。
set -euo pipefail

DEVECO="${DEVECO_HOME:-/Applications/DevEco-Studio.app/Contents}"
[ -d "$DEVECO" ] || { echo "未找到 DevEco Studio（$DEVECO）"; exit 1; }

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HV="$ROOT/harmony/tools/hvigor"

if [ ! -x "$HV/bin/hvigorw" ]; then
  echo "==> 拷贝 hvigor wrapper（首次/刷新）"
  for component in bin hvigor hvigor-ohos-plugin; do
    [ -d "$DEVECO/tools/hvigor/$component" ] || {
      echo "DevEco Studio 缺少 hvigor 组件：$DEVECO/tools/hvigor/$component"
      exit 1
    }
  done
  mkdir -p "$HV"
  cp -R "$DEVECO/tools/hvigor/bin" "$DEVECO/tools/hvigor/hvigor" "$DEVECO/tools/hvigor/hvigor-ohos-plugin" "$HV/"
  chmod +x "$HV/bin/hvigorw"
fi
[ -x "$HV/bin/hvigorw" ] || { echo "hvigor wrapper 准备失败：$HV/bin/hvigorw"; exit 1; }

export DEVECO_SDK_HOME="$DEVECO/sdk"
export JAVA_HOME="$DEVECO/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$DEVECO/tools/node/bin:$DEVECO/tools/ohpm/bin:$PATH"

cd "$ROOT/harmony"
./tools/hvigor/bin/hvigorw assembleHap --mode module -p product=default --no-daemon "$@"

HAP="$ROOT/harmony/entry/build/default/outputs/default/entry-default-unsigned.hap"
[ -f "$HAP" ] || { echo "构建完成但未找到产物：$HAP"; exit 1; }
echo "==> 产物：$HAP ($(du -h "$HAP" | cut -f1))"
