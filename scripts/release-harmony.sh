#!/usr/bin/env bash
# 一键出鸿蒙发布签名包（本机，macOS + DevEco Studio 工具链）。
#
# 用法：bash scripts/release-harmony.sh [release-version]
# 产物：harmony/release/cc-pocket-harmony-<versionName>-signed.hap（目录已 gitignore）
#
# 可选 release-version 供 CI 使用（严格 x.y.z）：脚本只在本次构建期间把 app.json5 的
# versionName/versionCode 改成该版本，退出时恢复 tracked 文件。versionCode 按
# major*1,000,000 + minor*1,000 + patch 推导，同一 release 输入可重复得到同一包版本。
#
# 秘钥约定（与 iOS fastlane 同思路：凭据只进 .env，不进仓库）：
#   .env 需含以下变量（.env 已 gitignore + guard hook 保护）：
#     Harmony_Keystore_Password=<p12 密钥库密码>
#     Harmony_Key_Alias=<密钥别名>
#     Harmony_Key_Password=<密钥密码，缺省与库密码相同>
#   签名材料默认在 ~/Documents/cc-pocket/（可用下方 HARMONY_* 环境变量覆盖）：
#     cc-pocket-harmony.p12 / cc-pocket-harmony-release.cer / cc-pocket-harmony-releaseRelease.p7b
#
# 流程：build-harmony.sh 以 release buildMode 出 unsigned HAP → 校验 pack.info 身份/版本 →
# hap-sign-tool 本地签名 → verify-app + 输入 CER fingerprint 校验；AGC App Pack 也执行同样门禁。
# ⚠️ 本脚本不写 build-profile.json5 的 signingConfigs——签名配置永远不落 tracked 文件。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
readonly RELEASE_BUILD_PROPERTY="buildMode=release"
readonly EXPECTED_BUNDLE_NAME="com.ccpocket.app"

# ---- 发版版本（CI 可覆盖，tracked 配置退出时原样恢复）----
APP_CONFIG="$ROOT/harmony/AppScope/app.json5"
REQUESTED_VERSION="${1:-}"
APP_CONFIG_BACKUP=""
VERIFY_DIR=""
RELEASE_SUCCEEDED=0
cleanup() {
  local status=$?
  # Do not let successful cleanup mask the build/signing failure that triggered EXIT.
  trap - EXIT
  if [ "$RELEASE_SUCCEEDED" -ne 1 ] && [ "$status" -eq 0 ]; then
    status=1
  fi
  if [ -n "$APP_CONFIG_BACKUP" ] && [ -f "$APP_CONFIG_BACKUP" ]; then
    if cp "$APP_CONFIG_BACKUP" "$APP_CONFIG"; then
      rm -f "$APP_CONFIG_BACKUP" || status=1
    else
      echo "恢复 $APP_CONFIG 失败；原始备份保留在 $APP_CONFIG_BACKUP" >&2
      status=1
    fi
  fi
  if [ -n "$VERIFY_DIR" ] && [ -d "$VERIFY_DIR" ]; then
    rm -rf "$VERIFY_DIR" || status=1
  fi
  exit "$status"
}
trap cleanup EXIT

if [ -n "$REQUESTED_VERSION" ]; then
  if ! [[ "$REQUESTED_VERSION" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; then
    echo "Harmony release version 必须是 x.y.z：$REQUESTED_VERSION"
    exit 1
  fi
  MAJOR="${BASH_REMATCH[1]}"
  MINOR="${BASH_REMATCH[2]}"
  PATCH="${BASH_REMATCH[3]}"
  if [ "${#MAJOR}" -gt 4 ] || [ "$MINOR" -ge 1000 ] || [ "$PATCH" -ge 1000 ]; then
    echo "Harmony release version 超出 versionCode 映射范围：$REQUESTED_VERSION"
    exit 1
  fi
  VERSION_CODE=$((MAJOR * 1000000 + MINOR * 1000 + PATCH))
  if [ "$VERSION_CODE" -le 0 ] || [ "$VERSION_CODE" -gt 2147483647 ]; then
    echo "Harmony versionCode 超出正 32-bit 整数范围：$VERSION_CODE"
    exit 1
  fi
  APP_CONFIG_BACKUP_CANDIDATE="$(mktemp /tmp/cc-pocket-harmony-app.XXXXXX)"
  if ! cp "$APP_CONFIG" "$APP_CONFIG_BACKUP_CANDIDATE"; then
    rm -f "$APP_CONFIG_BACKUP_CANDIDATE"
    echo "无法备份 Harmony app 配置：$APP_CONFIG" >&2
    exit 1
  fi
  # Only arm EXIT restoration after a complete backup exists. A failed copy must never turn an empty
  # mktemp placeholder into the source of truth for the tracked configuration.
  APP_CONFIG_BACKUP="$APP_CONFIG_BACKUP_CANDIDATE"
  APP_CONFIG_NEXT="$(mktemp /tmp/cc-pocket-harmony-version.XXXXXX)"
  sed -E \
    -e "s/(\"versionCode\"[[:space:]]*:[[:space:]]*)[0-9]+/\\1${VERSION_CODE}/" \
    -e "s/(\"versionName\"[[:space:]]*:[[:space:]]*)\"[^\"]*\"/\\1\"${REQUESTED_VERSION}\"/" \
    "$APP_CONFIG" > "$APP_CONFIG_NEXT"
  cp "$APP_CONFIG_NEXT" "$APP_CONFIG"
  rm -f "$APP_CONFIG_NEXT"
  grep -q "\"versionCode\": ${VERSION_CODE}" "$APP_CONFIG" || { echo "写入 Harmony versionCode 失败"; exit 1; }
  grep -q "\"versionName\": \"${REQUESTED_VERSION}\"" "$APP_CONFIG" || { echo "写入 Harmony versionName 失败"; exit 1; }
fi

VERSION=$(grep -o '"versionName": *"[^"]*"' "$APP_CONFIG" | sed 's/.*"versionName": *"\([^"]*\)".*/\1/')
[ -n "$VERSION" ] || { echo "无法从 $APP_CONFIG 读取 versionName"; exit 1; }
EXPECTED_VERSION_CODE=$(grep -o '"versionCode": *[0-9]*' "$APP_CONFIG" | sed 's/.*: *//')
[ -n "$EXPECTED_VERSION_CODE" ] || { echo "无法从 $APP_CONFIG 读取 versionCode"; exit 1; }

package_metadata_value() {
  local archive="$1"
  local key_path="$2"
  unzip -p "$archive" pack.info | /usr/bin/plutil -extract "$key_path" raw -o - -
}

assert_package_metadata() {
  local archive="$1"
  local label="$2"
  local actual_bundle actual_version actual_code

  [ -f "$archive" ] || { echo "$label 不存在：$archive"; exit 1; }
  actual_bundle=$(package_metadata_value "$archive" summary.app.bundleName) || {
    echo "$label 无法读取 pack.info bundleName"
    exit 1
  }
  actual_version=$(package_metadata_value "$archive" summary.app.version.name) || {
    echo "$label 无法读取 pack.info versionName"
    exit 1
  }
  actual_code=$(package_metadata_value "$archive" summary.app.version.code) || {
    echo "$label 无法读取 pack.info versionCode"
    exit 1
  }
  [ "$actual_bundle" = "$EXPECTED_BUNDLE_NAME" ] || {
    echo "$label bundleName 错误：期望 ${EXPECTED_BUNDLE_NAME}，实际 $actual_bundle"
    exit 1
  }
  [ "$actual_version" = "$VERSION" ] || {
    echo "$label versionName 错误：期望 ${VERSION}，实际 $actual_version"
    exit 1
  }
  [ "$actual_code" = "$EXPECTED_VERSION_CODE" ] || {
    echo "$label versionCode 错误：期望 ${EXPECTED_VERSION_CODE}，实际 $actual_code"
    exit 1
  }
  echo "==> $label metadata 已校验：$actual_bundle $actual_version ($actual_code)"
}

certificate_fingerprint() {
  local certificate="$1"
  local output
  if output=$(openssl x509 -in "$certificate" -noout -sha256 -fingerprint 2>/dev/null); then
    :
  elif output=$(openssl x509 -inform DER -in "$certificate" -noout -sha256 -fingerprint 2>/dev/null); then
    :
  else
    return 1
  fi
  output="${output#*=}"
  printf '%s' "$output" | tr -d ':' | tr '[:lower:]' '[:upper:]'
}

# ⚠️ 签名身份只认叶证书。verify-app 导出的链和输入 CER 都是多证书 PEM 束，且都以同一张
# `Huawei CBG Root CA G2` 开头——`openssl x509 -in <束>` 只读第一张证书，于是「比对」比的是两边
# 共有的华为根：任何一张华为签发的证书（含 debug 证书）都能打出「fingerprint 一致」。叶证书在两个
# 束里的下标还不同，所以必须按 basicConstraints 定位，绝不能按第 N 张取。
split_pem_bundle() {
  local source="$1"
  local dest_dir="$2"
  awk -v dir="$dest_dir" '
    /-----BEGIN CERTIFICATE-----/ { n += 1; out = sprintf("%s/cert-%03d.pem", dir, n) }
    out != "" { print > out }
    /-----END CERTIFICATE-----/ { if (out != "") { close(out); out = "" } }
  ' "$source"
}

# 0 = 该证书是 CA（链上的签发者，不可能是签名身份）；1 = end-entity 候选。
certificate_is_ca() {
  local cert="$1"
  local subject issuer
  openssl x509 -in "$cert" -noout -text 2>/dev/null | awk '
    /X509v3 Basic Constraints/ { in_bc = 1; next }
    in_bc && /CA:TRUE/ { is_ca = 1; exit }
    in_bc && /CA:FALSE/ { exit }
    in_bc && /X509v3 / { exit }
    END { exit(is_ca ? 0 : 1) }
  ' && return 0
  # 没有 basicConstraints 扩展的老式根证书兜底：自签（subject == issuer）一律当 CA 排除。
  subject=$(openssl x509 -in "$cert" -noout -subject 2>/dev/null) || return 1
  issuer=$(openssl x509 -in "$cert" -noout -issuer 2>/dev/null) || return 1
  [ "${subject#*=}" = "${issuer#*=}" ]
}

leaf_certificate_fingerprint() {
  local source="$1"
  local work bundle cert leaf count status
  work=$(mktemp -d /tmp/hap-leaf-cert.XXXXXX) || return 1
  bundle="$work/bundle.pem"
  if grep -q -- '-----BEGIN CERTIFICATE-----' "$source" 2>/dev/null; then
    cat "$source" > "$bundle" || { rm -rf "$work"; return 1; }
  elif ! openssl x509 -inform DER -in "$source" -out "$bundle" 2>/dev/null; then
    rm -rf "$work"
    return 1
  fi
  split_pem_bundle "$bundle" "$work"

  leaf=""
  count=0
  for cert in "$work"/cert-*.pem; do
    [ -f "$cert" ] || continue
    if certificate_is_ca "$cert"; then
      continue
    fi
    leaf="$cert"
    count=$((count + 1))
  done
  # 定位不到唯一叶证书时必须报错退出：退回「取第一张」正是这个门禁空转的根因。
  if [ "$count" -ne 1 ]; then
    rm -rf "$work"
    return 2
  fi

  certificate_fingerprint "$leaf"
  status=$?
  rm -rf "$work"
  return "$status"
}

assert_certificate_matches() {
  local verified_certificate="$1"
  local label="$2"
  local expected_fingerprint actual_fingerprint

  [ -s "$verified_certificate" ] || { echo "$label 未导出验证证书：$verified_certificate"; exit 1; }
  expected_fingerprint=$(leaf_certificate_fingerprint "$CER") || {
    echo "无法在输入 CER 中定位唯一的 end-entity 证书并取 SHA-256 fingerprint：$CER"
    exit 1
  }
  actual_fingerprint=$(leaf_certificate_fingerprint "$verified_certificate") || {
    echo "无法在 $label 导出证书链中定位唯一的 end-entity 证书并取 SHA-256 fingerprint：$verified_certificate"
    exit 1
  }
  [ "$actual_fingerprint" = "$expected_fingerprint" ] || {
    echo "$label 叶证书与输入 CER 的叶证书不一致（签名身份不匹配）"
    exit 1
  }
  echo "==> $label 叶证书 fingerprint 与输入 CER 一致：$actual_fingerprint"
}

# ---- 秘钥与材料 ----
[ "${HARMONY_LOAD_ENV:-1}" = "0" ] || { [ ! -f .env ] || { set -a; . ./.env; set +a; }; }
[ -n "${Harmony_Keystore_Password:-}" ] || { echo "在 .env 里加 Harmony_Keystore_Password=<p12 密钥库密码>"; exit 1; }
[ -n "${Harmony_Key_Alias:-}" ] || { echo "在 .env 里加 Harmony_Key_Alias=<密钥别名>"; exit 1; }
KEY_PWD="${Harmony_Key_Password:-$Harmony_Keystore_Password}"

MATERIAL_DIR="${HARMONY_MATERIAL_DIR:-$HOME/Documents/cc-pocket}"
P12="${HARMONY_P12:-$MATERIAL_DIR/cc-pocket-harmony.p12}"
CER="${HARMONY_CER:-$MATERIAL_DIR/cc-pocket-harmony-release.cer}"
P7B="${HARMONY_P7B:-$MATERIAL_DIR/cc-pocket-harmony-releaseRelease.p7b}"
for f in "$P12" "$CER" "$P7B"; do
  [ -f "$f" ] || { echo "缺少签名材料：$f"; exit 1; }
done

DEVECO="${DEVECO_HOME:-/Applications/DevEco-Studio.app/Contents}"
SIGN_TOOL="$DEVECO/sdk/default/openharmony/toolchains/lib/hap-sign-tool.jar"
[ -f "$SIGN_TOOL" ] || { echo "未找到 hap-sign-tool：$SIGN_TOOL"; exit 1; }
export DEVECO_SDK_HOME="$DEVECO/sdk"
export JAVA_HOME="$DEVECO/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$DEVECO/tools/node/bin:$DEVECO/tools/ohpm/bin:$PATH"
JAVA="$JAVA_HOME/bin/java"
[ -x "$JAVA" ] || JAVA="java"

# A signed debug/default build is still not a release build. Keep the mode explicit at BOTH hvigor
# entrypoints below, and fail before compiling if the project ever removes that named build option.
grep -q '"name"[[:space:]]*:[[:space:]]*"release"' harmony/entry/build-profile.json5 || {
  echo "harmony/entry/build-profile.json5 未定义 release buildMode"
  exit 1
}

# ---- 1/3 构建 unsigned HAP ----
echo "── 1/3 构建 release HAP ──"
bash scripts/build-harmony.sh -p "$RELEASE_BUILD_PROPERTY"
UNSIGNED="$ROOT/harmony/entry/build/default/outputs/default/entry-default-unsigned.hap"
VERIFY_DIR=$(mktemp -d /tmp/hap-verify.XXXXXX)
assert_package_metadata "$UNSIGNED" "unsigned HAP"

# ---- 2/3 签名 ----
OUT_DIR="$ROOT/harmony/release"
mkdir -p "$OUT_DIR"
SIGNED="$OUT_DIR/cc-pocket-harmony-${VERSION}-signed.hap"
echo "── 2/3 签名（${VERSION}）──"
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

# ---- 3/4 校验 HAP ----
echo "── 3/4 校验 HAP 签名 ──"
"$JAVA" -jar "$SIGN_TOOL" verify-app \
  -inFile "$SIGNED" \
  -outCertChain "$VERIFY_DIR/cert.cer" \
  -outProfile "$VERIFY_DIR/profile.p7b"
assert_certificate_matches "$VERIFY_DIR/cert.cer" "signed HAP"

# ---- 4/4 AGC 上传包（App Pack）----
# 顺序铁律：先 assembleApp 出 unsigned .app，再对整包 sign-app；签名落在 .app 层，
# 解包验内层 HAP 会失败属预期。绝不可先签 HAP 再 app_packing_tool 打包——它重压缩必破签。
echo "── 4/4 打包并签名 App Pack（AGC 上传用）──"
( cd harmony && ./tools/hvigor/bin/hvigorw assembleApp --mode project -p product=default -p "$RELEASE_BUILD_PROPERTY" --no-daemon >/dev/null )
UNSIGNED_APP="$ROOT/harmony/build/outputs/default/harmony-default-unsigned.app"
[ -f "$UNSIGNED_APP" ] || { echo "assembleApp 无产物：$UNSIGNED_APP"; exit 1; }
assert_package_metadata "$UNSIGNED_APP" "unsigned App Pack"
SIGNED_APP="$OUT_DIR/cc-pocket-harmony-${VERSION}-signed.app"
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
  -inFile "$UNSIGNED_APP" \
  -outFile "$SIGNED_APP"
"$JAVA" -jar "$SIGN_TOOL" verify-app \
  -inFile "$SIGNED_APP" \
  -outCertChain "$VERIFY_DIR/app-cert.cer" \
  -outProfile "$VERIFY_DIR/app-profile.p7b"
assert_certificate_matches "$VERIFY_DIR/app-cert.cer" "signed App Pack"

echo "==> 调试/装机 HAP：$SIGNED ($(du -h "$SIGNED" | cut -f1))"
echo "==> AGC 上传包：   $SIGNED_APP ($(du -h "$SIGNED_APP" | cut -f1))"
RELEASE_SUCCEEDED=1
