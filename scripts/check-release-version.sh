#!/usr/bin/env bash
# Fail-fast release guard: every shipped client/daemon version source must agree.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

EXPECTED="${1:-}"
fail() { echo "Release version check failed: $*" >&2; exit 1; }

DAEMON_VERSION=$(sed -nE 's/.*findProperty\("appVersion"\).*\?: "([^"]+)".*/\1/p' daemon/build.gradle.kts | head -1)
MOBILE_VERSION=$(sed -nE 's/.*val appVersionName = "([^"]+)".*/\1/p' mobile/composeApp/build.gradle.kts | head -1)
DESKTOP_VERSION=$(sed -nE 's/.*packageVersion = "([^"]+)".*/\1/p' mobile/composeApp/build.gradle.kts | head -1)
SEED_VERSION=$(sed -nE 's/.*override val appVersion = "([^"]+)".*/\1/p' mobile/composeApp/src/desktopMain/kotlin/dev/ccpocket/app/desktop/SeedDesktopModel.kt | head -1)
CASK_VERSION=$(sed -nE 's/^[[:space:]]*version "([^"]+)".*/\1/p' packaging/homebrew/Casks/cc-pocket.rb | head -1)
IOS_VERSION=$(python3 - <<'PY'
import plistlib

with open("iosApp/iosApp/Info.plist", "rb") as handle:
    print(plistlib.load(handle)["CFBundleShortVersionString"])
PY
)

[ -n "$MOBILE_VERSION" ] || fail "could not read mobile appVersionName"
[[ "$MOBILE_VERSION" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] || \
  fail "version must be strict x.y.z without leading zeroes, got $MOBILE_VERSION"

for entry in \
  "daemon:$DAEMON_VERSION" \
  "desktop-package:$DESKTOP_VERSION" \
  "desktop-seed:$SEED_VERSION" \
  "ios:$IOS_VERSION" \
  "homebrew-template:$CASK_VERSION"
do
  label=${entry%%:*}
  value=${entry#*:}
  [ "$value" = "$MOBILE_VERSION" ] || \
    fail "$label is $value but mobile is $MOBILE_VERSION"
done

if [ -n "$EXPECTED" ]; then
  [ "$EXPECTED" = "$MOBILE_VERSION" ] || \
    fail "workflow requested $EXPECTED but repository is $MOBILE_VERSION"
fi

grep -Fq "What's new in $MOBILE_VERSION" fastlane/metadata/en-US/release_notes.txt || \
  fail "English release notes do not name $MOBILE_VERSION"
grep -Fq "本次更新（${MOBILE_VERSION}）" fastlane/metadata/zh-Hans/release_notes.txt || \
  fail "Chinese release notes do not name $MOBILE_VERSION"

echo "Release version lockstep OK: $MOBILE_VERSION"
