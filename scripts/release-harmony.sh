#!/usr/bin/env bash
# 一键出鸿蒙发布签名包（本机，macOS + DevEco Studio 工具链）。
#
# 用法：bash scripts/release-harmony.sh
# 产物：harmony/release/cc-pocket-harmony-<versionName>-signed.hap（目录已 gitignore）
#
# 秘钥约定（与 iOS fastlane 同思路：凭据只进 .env，不进仓库）：
#   .env 需含以下变量（.env 已 gitignore + guard hook 保护）：
#     Harmony_Keystore_Password=<p12 密钥库密码>
#     Harmony_Key_Alias=<密钥别名>
#     Harmony_Key_Password=<密钥密码，缺省与库密码相同>
#   签名材料默认在 ~/Documents/cc-pocket/（可用下方 HARMONY_* 环境变量覆盖）：
#     cc-pocket-harmony.p12 / cc-pocket-harmony-release.cer / cc-pocket-harmony-releaseRelease.p7b
#
# 流程：build-harmony.sh 出 unsigned HAP → hap-sign-tool 本地签名 → verify-app 校验。
# ⚠️ 本脚本不写 build-profile.json5 的 signingConfigs——签名配置永远不落 tracked 文件。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# ---- 秘钥与材料 ----
[ -f .env ] && { set -a; . ./.env; set +a; }
: "${Harmony_Keystore_Password:?在 .env 里加 Harmony_Keystore_Password=<p12 密钥库密码>}"
: "${Harmony_Key_Alias:?在 .env 里加 Harmony_Key_Alias=<密钥别名>}"
KEY_PWD="${Harmony_Key_Password:-$Harmony_Keystore_Password}"

MATERIAL_DIR="${HARMONY_MATERIAL_DIR:-$HOME/Documents/cc-pocket}"
P12="${HARMONY_P12:-$MATERIAL_DIR/cc-pocket-harmony.p12}"
CER="${HARMONY_CER:-$MATERIAL_DIR/cc-pocket-harmony-release.cer}"
P7B="${HARMONY_P7B:-$MATERIAL_DIR/cc-pocket-harmony-releaseRelease.p7b}"
for f in "$P12" "$CER" "$P7B"; do
  [ -f "$f" ] || { echo "缺少签名材料：$f"; exit 1; }
done

DEVECO="/Applications/DevEco-Studio.app/Contents"
SIGN_TOOL="$DEVECO/sdk/default/openharmony/toolchains/lib/hap-sign-tool.jar"
[ -f "$SIGN_TOOL" ] || { echo "未找到 hap-sign-tool：$SIGN_TOOL"; exit 1; }
JAVA="$DEVECO/jbr/Contents/Home/bin/java"
[ -x "$JAVA" ] || JAVA="java"

# ---- 1/3 构建 unsigned HAP ----
echo "── 1/3 构建 unsigned HAP ──"
bash scripts/build-harmony.sh
UNSIGNED="$ROOT/harmony/entry/build/default/outputs/default/entry-default-unsigned.hap"

# ---- 2/3 签名 ----
VERSION=$("$JAVA" -version >/dev/null 2>&1; grep -o '"versionName": *"[^"]*"' harmony/AppScope/app.json5 | sed 's/.*"versionName": *"\([^"]*\)".*/\1/')
OUT_DIR="$ROOT/harmony/release"
mkdir -p "$OUT_DIR"
SIGNED="$OUT_DIR/cc-pocket-harmony-${VERSION}-signed.hap"
echo "── 2/3 签名（$VERSION）──"
"$JAVA" -jar "$SIGN_TOOL" sign-app \
  -mode localSign \
  -keystoreFile "$P12" \
  -keystorePwd "$Harmony_Keystore_Password" \
  -keyAlias "$Harmony_Key_Alias" \
  -keyPwd "$KEY_PWD" \
  -appCertFile "$CER" \
  -profileFile "$P7B" \
  -profileSigned 1 \
  -signAlg SHA256withECDSA \
  -signCode 1 \
  -inFile "$UNSIGNED" \
  -outFile "$SIGNED"

# ---- 3/3 校验 ----
echo "── 3/3 校验签名 ──"
VERIFY_DIR=$(mktemp -d /tmp/hap-verify.XXXXXX)
trap 'rm -rf "$VERIFY_DIR"' EXIT
"$JAVA" -jar "$SIGN_TOOL" verify-app \
  -inFile "$SIGNED" \
  -outCertChain "$VERIFY_DIR/cert.cer" \
  -outProfile "$VERIFY_DIR/profile.p7b"

echo "==> 发布签名包：$SIGNED ($(du -h "$SIGNED" | cut -f1))"
