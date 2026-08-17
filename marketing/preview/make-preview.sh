#!/bin/bash
# One-shot: render overlay assets, record the demo run, and assemble the final App Preview for a language.
#
# Usage:  ./make-preview.sh <en|zh> [--compose]
# Prereqs: booted iPhone 16 Pro Max simulator with a PREVIEW-capable build installed (see README),
#          cliclick + ffmpeg + Python PIL, Simulator window visible on screen.
set -e
LANG_CODE="${1:-en}"
MODE="${2:-simulator}"
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
OUTDIR="$HERE/out"; mkdir -p "$OUTDIR"
ASSETS="/tmp/ccp-assets-$LANG_CODE"
BUILD="/tmp/ccp-build-$LANG_CODE"
FINAL="$OUTDIR/cc-pocket-app-preview-$LANG_CODE-886x1920.mov"

echo "▶ 1/3 render assets ($LANG_CODE)"; python3 "$HERE/render_assets.py" "$LANG_CODE" "$ASSETS"
if [ "$MODE" = "--compose" ]; then
  FRAMES="$ROOT/marketing/site/build/loop-$LANG_CODE"
  [ -f "$FRAMES/f00305.png" ] || { echo "Missing current Compose frames. Run: bash marketing/site/generate-assets.sh" >&2; exit 1; }
  echo "▶ 2/3 use deterministic real Compose frames ($LANG_CODE)"
  echo "▶ 3/3 assemble ($LANG_CODE)"; bash "$HERE/assemble-compose.sh" "$LANG_CODE" "$FRAMES" "$ASSETS" "$FINAL"
  echo "✅ $FINAL"
  exit 0
fi
echo "▶ 2/3 record demo run ($LANG_CODE)"; bash "$HERE/record.sh" "$LANG_CODE" "$BUILD"
echo "▶ 3/3 assemble ($LANG_CODE)"; bash "$HERE/assemble.sh" "$LANG_CODE" "$BUILD" "$ASSETS" "$FINAL"
echo "✅ $FINAL"
