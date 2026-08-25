#!/usr/bin/env bash
# 起一个单任务 JIT self-hosted runner，专门承接 release.yml 的 Harmony job。
#
# 用法：bash scripts/harmony-jit-runner.sh
# 典型发版序列（顺序无所谓，job 会排队等 runner，runner 也会等 job）：
#   1. gh workflow run release.yml --ref v<X.Y.Z> -f version=<X.Y.Z> -f include_harmony=true
#      （对已发过的版本补发 HAP 用 -f only_harmony=true，其余平台 job 全跳过，
#        已发资产与 cask/scoop sha 保持逐字节不变）
#   2. bash scripts/harmony-jit-runner.sh   # 本脚本：注册 JIT runner 并等待 job
#   3. 在 GitHub UI（或 pending_deployments API）批准 harmony-release environment
#
# JIT 语义：注册凭据一次性、恰好跑一个 job，job 结束进程退出、注册记录自动移除，
# 不留常驻 runner。工作目录每次新建、退出时删除，不在本机残留 checkout 或签名材料
# （签名 secrets 本来只进 $RUNNER_TEMP，job 的 always() 步骤已负责擦除）。
#
# 前置：本机 DevEco Studio（Harmony job 会自检）、gh 已登录且对仓库有 admin 权限。
set -euo pipefail

REPO="${HARMONY_RUNNER_REPO:-heypandax/cc-pocket}"
LABELS='["self-hosted","macOS","ARM64","harmony"]'
CACHE_DIR="${HOME}/Library/Caches/cc-pocket/actions-runner"

[ "$(uname -s)" = "Darwin" ] || { echo "本脚本只在 macOS 上跑"; exit 1; }
[ "$(uname -m)" = "arm64" ] || { echo "Harmony job 要求 ARM64 主机"; exit 1; }
DEVECO="${DEVECO_HOME:-/Applications/DevEco-Studio.app/Contents}"
[ -d "$DEVECO" ] || { echo "未找到 DevEco Studio（${DEVECO}），Harmony job 起来也会失败"; exit 1; }
command -v gh >/dev/null || { echo "需要 GitHub CLI（gh）"; exit 1; }

# ---- 取 runner 发行包（按版本缓存，幂等）----
RUNNER_VERSION="$(gh api repos/actions/runner/releases/latest --jq '.tag_name' | sed 's/^v//')"
[ -n "$RUNNER_VERSION" ] || { echo "无法解析 actions/runner 最新版本"; exit 1; }
RUNNER_DIR="$CACHE_DIR/$RUNNER_VERSION"
if [ ! -x "$RUNNER_DIR/run.sh" ]; then
  echo "==> 下载 actions-runner v${RUNNER_VERSION}（osx-arm64）"
  mkdir -p "$RUNNER_DIR"
  TARBALL="actions-runner-osx-arm64-${RUNNER_VERSION}.tar.gz"
  gh release download "v${RUNNER_VERSION}" --repo actions/runner \
    --pattern "$TARBALL" --output "$RUNNER_DIR/$TARBALL" --clobber
  tar xzf "$RUNNER_DIR/$TARBALL" -C "$RUNNER_DIR"
  rm -f "$RUNNER_DIR/$TARBALL"
fi

# ---- 每次新建一次性工作目录，退出即删 ----
WORK_DIR="$(mktemp -d /tmp/cc-pocket-harmony-runner.XXXXXX)"
cleanup() { rm -rf "$WORK_DIR"; }
trap cleanup EXIT

RUNNER_NAME="harmony-jit-$(date +%Y%m%d-%H%M%S)"
echo "==> 申请 JIT 配置：${RUNNER_NAME}（labels: self-hosted/macOS/ARM64/harmony）"
JIT_CONFIG="$(gh api -X POST "repos/${REPO}/actions/runners/generate-jitconfig" \
  --input - <<EOF | jq -r '.encoded_jit_config'
{"name":"${RUNNER_NAME}","runner_group_id":1,"labels":${LABELS},"work_folder":"${WORK_DIR}"}
EOF
)"
[ -n "$JIT_CONFIG" ] && [ "$JIT_CONFIG" != "null" ] || { echo "generate-jitconfig 失败（需要仓库 admin 权限）"; exit 1; }

echo "==> runner 启动，等待恰好一个 Harmony job；job 结束后自动注销退出"
echo "    （若 job 还没 dispatch，现在去跑：gh workflow run release.yml --ref v<X.Y.Z> ...）"
"$RUNNER_DIR/run.sh" --jitconfig "$JIT_CONFIG"
echo "==> JIT runner 已退出（单 job 生命周期结束，注册记录已自动移除）"
