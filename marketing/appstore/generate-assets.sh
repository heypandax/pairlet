#!/usr/bin/env bash
#
# Regenerate the App Store screenshot set from the real Compose UI.
#
#   bash marketing/appstore/generate-assets.sh
#   bash marketing/appstore/generate-assets.sh --reuse  # reuse current site/fleet frames
#
# Phone pixels are rendered by ShowcaseRender with scripted demo data. AppStoreScreenshotRender
# only adds the 1290x2796 marketing canvas and localized copy; no product UI is drawn by hand.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="$ROOT/marketing/appstore/build"
SITE_BUILD="$ROOT/marketing/site/build"
OUT="$ROOT/fastlane/screenshots"
REUSE=0
[ "${1:-}" = "--reuse" ] && REUSE=1

die() { printf '\n[appstore-assets] %s\n\n' "$*" >&2; exit 1; }
step() { printf '\n[appstore-assets] == %s ==\n' "$*"; }

: "${JAVA_HOME:=/opt/homebrew/opt/openjdk@17}"
[ -x "$JAVA_HOME/bin/java" ] || die "JAVA_HOME=$JAVA_HOME has no bin/java; JDK 17 is required."
export JAVA_HOME

mkdir -p "$WORK" "$OUT"

if [ "$REUSE" = "0" ]; then
  step "render current website control-loop frames"
  bash "$ROOT/marketing/site/generate-assets.sh"
else
  [ -f "$SITE_BUILD/loop-en/f00305.png" ] || die "--reuse requested but English site frames are missing"
  [ -f "$SITE_BUILD/loop-zh/f00305.png" ] || die "--reuse requested but Chinese site frames are missing"
fi

render_fleet() {
  local lang="$1" dir="$WORK/fleet-$1"
  if [ "$REUSE" = "1" ] && [ -f "$dir/fleet/f00001.png" ]; then
    printf '[appstore-assets] reusing fleet frame · %s\n' "$lang"
    return
  fi
  step "render real fleet UI · $lang"
  rm -rf "$dir"
  SHOWCASE_OUT="$dir" SHOWCASE_ONLY=fleet SHOWCASE_FPS=1 SHOWCASE_LANG="$lang" CCP_CAPTURE_LOCALE="$lang" \
    "$ROOT/gradlew" -p "$ROOT" :mobile:composeApp:desktopTest \
      --tests dev.ccpocket.app.showcase.ShowcaseRender --rerun --console=plain -q
  [ -f "$dir/fleet/f00001.png" ] || die "fleet renderer produced no frame for $lang"
}

render_fleet en
render_fleet zh

step "compose localized 1290x2796 screenshots"
rm -rf "$OUT/en-US" "$OUT/zh-Hans"
APPSTORE_SCREENSHOT_OUT="$OUT" \
APPSTORE_SITE_BUILD="$SITE_BUILD" \
APPSTORE_FLEET_BUILD="$WORK" \
  "$ROOT/gradlew" -p "$ROOT" :mobile:composeApp:desktopTest \
    --tests dev.ccpocket.app.showcase.AppStoreScreenshotRender --rerun --console=plain -q

step "validate"
python3 "$ROOT/scripts/check-appstore-content.py"
printf '\n[appstore-assets] done → %s\n' "$OUT"
