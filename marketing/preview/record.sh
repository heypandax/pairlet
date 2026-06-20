#!/bin/bash
# Record the raw App-Preview run (scenes 1-5) by driving the app's no-pairing demo in PREVIEW mode.
# Drives the Compose canvas with cliclick (no accessibility tree), captures with `simctl recordVideo`
# (clean device framebuffer — no cursor, occlusion-independent).
#
# Usage:  ./record.sh <en|zh> [out_dir]
# Requires: a booted iPhone simulator with the app installed, cliclick, the Simulator window visible.
set -e
LANG_CODE="${1:-en}"
OUT="${2:-/tmp/ccp-preview-build}"; mkdir -p "$OUT"
SIM="${SIM:-$(xcrun simctl list devices booted | grep -oE '[0-9A-Fa-f-]{36}' | head -1)}"
BID="${BID:-com.panda.ccpocket}"
PROMPT_EN="Clean the build cache"
PROMPT_ZH="清理构建缓存"
[ "$LANG_CODE" = "zh" ] && PROMPT="$PROMPT_ZH" || PROMPT="$PROMPT_EN"

# --- tap mapping: device-fraction (fx,fy of the screen) -> Mac screen point. ---
# Auto-detect the Simulator window; assume it sits at the default scale (titlebar ~28pt, side pad ~8pt).
read WX WY WW WH < <(osascript -e 'tell application "System Events" to tell process "Simulator" to get {item 1 of (get position of window 1), item 2 of (get position of window 1), item 1 of (get size of window 1), item 2 of (get size of window 1)}' | tr ',' ' ')
TB=28; PADX=8
tapfrac() {
  local x=$(echo "$WX + $PADX + $1*($WW - 2*$PADX)" | bc -l)
  local y=$(echo "$WY + $TB + $2*($WH - $TB)" | bc -l)
  osascript -e 'tell application "Simulator" to activate' >/dev/null 2>&1
  cliclick c:$(printf '%.0f' "$x"),$(printf '%.0f' "$y")
}
paste_text() { printf '%s' "$1" | pbcopy; osascript -e 'tell application "System Events" to keystroke "v" using {command down}' >/dev/null 2>&1; }
home() { osascript -e 'tell application "System Events" to keystroke "h" using {command down, shift down}' >/dev/null 2>&1; }

# --- fresh launch in PREVIEW mode + chosen language ---
xcrun simctl terminate "$SIM" "$BID" 2>/dev/null || true
xcrun simctl launch "$SIM" "$BID" -ccpPreview YES -AppleLanguages "($LANG_CODE)" -AppleLocale "$LANG_CODE" >/dev/null
sleep 4
osascript -e 'tell application "Simulator" to activate' >/dev/null 2>&1
echo "window: $WX,$WY ${WW}x${WH}  sim=$SIM lang=$LANG_CODE"

# --- record scenes 1-5 ---
xcrun simctl io "$SIM" recordVideo --codec h264 --force "$OUT/main.mov" >/tmp/ccp-rec.log 2>&1 & REC=$!
sleep 1.3
tapfrac 0.5 0.895; sleep 4.3                 # S1  Try Demo -> connecting -> encrypted -> list
sleep 1.6; tapfrac 0.5 0.306; sleep 2.8      # S2  open running session
tapfrac 0.485 0.937; sleep 0.7; paste_text "$PROMPT"; sleep 1.0
tapfrac 0.924 0.937; sleep 2.2               # S3  send -> thinking -> tool
sleep 3.6; tapfrac 0.462 0.910; sleep 2.4    # S4  hold danger card -> Allow once -> reply
tapfrac 0.485 0.937; sleep 0.6; printf '/' | pbcopy; osascript -e 'tell application "System Events" to keystroke "v" using {command down}' >/dev/null 2>&1
sleep 2.3; osascript -e 'tell application "System Events" to key code 51' >/dev/null 2>&1; sleep 0.5  # S5 slash menu, then clear
tapfrac 0.924 0.937; sleep 2.6; tapfrac 0.06 0.908; sleep 0.8  # mic -> voice bar -> cancel
kill -INT "$REC" 2>/dev/null; wait "$REC" 2>/dev/null || true

# --- scene-6 backdrop: home screen (banner is composited on top in assemble.sh) ---
home; sleep 1.5
xcrun simctl io "$SIM" screenshot "$OUT/home.png" >/dev/null 2>&1
xcrun simctl launch "$SIM" "$BID" -ccpPreview YES -AppleLanguages "($LANG_CODE)" >/dev/null 2>&1 || true
echo "DONE -> $OUT/main.mov  +  $OUT/home.png"
