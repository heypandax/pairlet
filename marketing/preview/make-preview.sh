#!/bin/bash
# One-shot: render overlay assets, record the demo run, and assemble the final App Preview for a language.
#
# Usage:  ./make-preview.sh <en|zh>
# Prereqs: booted iPhone 16 Pro Max simulator with a PREVIEW-capable build installed (see README),
#          cliclick + ffmpeg + Python PIL, Simulator window visible on screen.
set -e
LANG_CODE="${1:-en}"
HERE="$(cd "$(dirname "$0")" && pwd)"
OUTDIR="$HERE/out"; mkdir -p "$OUTDIR"
ASSETS="/tmp/ccp-assets-$LANG_CODE"
BUILD="/tmp/ccp-build-$LANG_CODE"
FINAL="$OUTDIR/cc-pocket-app-preview-$LANG_CODE-886x1920.mov"

echo "▶ 1/3 render assets ($LANG_CODE)"; python3 "$HERE/render_assets.py" "$LANG_CODE" "$ASSETS"
echo "▶ 2/3 record demo run ($LANG_CODE)"; bash "$HERE/record.sh" "$LANG_CODE" "$BUILD"
echo "▶ 3/3 assemble ($LANG_CODE)"; bash "$HERE/assemble.sh" "$LANG_CODE" "$BUILD" "$ASSETS" "$FINAL"
echo "✅ $FINAL"
