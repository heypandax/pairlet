#!/usr/bin/env bash
#
# Regenerate the App Store screenshot set from the real Compose UI.
#
#   bash marketing/appstore/generate-assets.sh
#   bash marketing/appstore/generate-assets.sh --reuse  # reuse current site/fleet frames
#
# TWO device sets come out of this, both from the real Compose UI and never drawn by hand:
#
#   fastlane/screenshots/<locale>/*.png              6 x 1242x2688  APP_IPHONE_65
#   fastlane/screenshots/<locale>/ipadPro129/*.png   6 x 2048x2732  APP_IPAD_PRO_3GEN_129  (issue #334)
#
# Phone pixels are rendered by ShowcaseRender with scripted demo data; AppStoreScreenshotRender only
# adds the marketing canvas and localized copy, and the final resize targets the 6.5-inch slot.
# The iPad set is plain full-bleed two-pane frames from AppStoreIpadScreenshotRender — no canvas and
# no resize, because the tablet story IS the layout and the scene already renders at the exact size.
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

step "resize screenshots for the App Store 6.5-inch slot"
for shot in "$OUT"/en-US/*.png "$OUT"/zh-Hans/*.png; do
  tmp="$shot.tmp.png"
  ffmpeg -nostdin -y -loglevel error -i "$shot" -vf "scale=1242:2688:flags=lanczos" "$tmp"
  mv "$tmp" "$shot"
done

# iPad set (issue #334). Runs AFTER the phone set on purpose: the compose step above wipes
# "$OUT/<locale>" wholesale, subfolders included.
#
# No ffmpeg pass here — the renderer composes 1024x1366 pt at Density(2f), so the bitmap already IS
# 2048x2732 and never gets resampled. Not covered by --reuse either: there is no expensive
# intermediate to reuse (6 frames, ~40s), and the whole point of these frames is that they are the
# CURRENT two-pane UI.
render_ipad() {
  local lang="$1" locale="$2" dir="$OUT/$2/ipadPro129"
  step "render iPad Pro 12.9 screenshots · $lang"
  rm -rf "$dir"
  APPSTORE_IPAD_OUT="$OUT" SHOWCASE_LANG="$lang" CCP_CAPTURE_LOCALE="$lang" \
    "$ROOT/gradlew" -p "$ROOT" :mobile:composeApp:desktopTest \
      --tests dev.ccpocket.app.showcase.AppStoreIpadScreenshotRender --rerun --console=plain -q
  local count
  count="$(find "$dir" -name '*.png' 2>/dev/null | wc -l | tr -d ' ')"
  [ "$count" = "6" ] || die "iPad renderer produced $count frames for $locale (want 6)"
}

render_ipad en en-US
render_ipad zh zh-Hans

step "validate"
python3 "$ROOT/scripts/check-appstore-content.py"
printf '\n[appstore-assets] done → %s\n' "$OUT"
