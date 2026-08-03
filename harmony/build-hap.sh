#!/bin/bash
# 鸿蒙 HAP 构建（DevEco 工具链环境注入）。用法: bash harmony/build-hap.sh
set -e
cd "$(dirname "$0")"
export DEVECO_SDK_HOME="E:\DevEco Studio\sdk"
export PATH="/e/DevEco Studio/jbr/bin:/e/DevEco Studio/tools/node:/e/DevEco Studio/tools/ohpm/bin:$PATH"
./tools/hvigor/bin/hvigorw.bat assembleHap --mode module -p product=default --no-daemon "$@"
