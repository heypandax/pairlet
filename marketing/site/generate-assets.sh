#!/usr/bin/env bash
#
# Regenerate every product asset the website and the README embed.
#
# Everything here comes out of the REAL Compose UI driven by scripted demo data — no hand-drawn
# mock-ups, no real user data, no network, no TTS. See marketing/site/README.md.
#
#   bash marketing/site/generate-assets.sh              # full run
#   bash marketing/site/generate-assets.sh --reuse      # re-encode from the frames already rendered
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="$ROOT/marketing/site/build"          # scratch (gitignored: **/build/)
OUT="$ROOT/site/assets/product"            # checked-in deliverables
FPS=30
REUSE=0
[ "${1:-}" = "--reuse" ] && REUSE=1

die() { printf '\n[generate-assets] %s\n\n' "$*" >&2; exit 1; }
step() { printf '\n[generate-assets] == %s ==\n' "$*"; }

# ── preflight: fail with something the reader can act on ───────────────────────────────────────
command -v ffmpeg  >/dev/null || die "ffmpeg not found. Install it first (macOS: brew install ffmpeg)."
command -v ffprobe >/dev/null || die "ffprobe not found. It ships with ffmpeg (macOS: brew install ffmpeg)."
command -v python3 >/dev/null || die "python3 not found. It writes the asset manifest."

: "${JAVA_HOME:=/opt/homebrew/opt/openjdk@17}"
[ -x "$JAVA_HOME/bin/java" ] || die "JAVA_HOME=$JAVA_HOME has no bin/java. JDK 17 is required to render the real UI.
       macOS: brew install openjdk@17, then re-run with JAVA_HOME=/opt/homebrew/opt/openjdk@17"
export JAVA_HOME

mkdir -p "$OUT" "$WORK"

# ── 1 · real-UI frames: the control loop, one reel per language ─────────────────────────────────
render_loop() {                                  # $1 = en|zh
  local lang="$1" dir="$WORK/loop-$1"
  if [ "$REUSE" = "1" ] && [ -f "$dir/f00000.png" ]; then
    printf '[generate-assets] reusing %s frames in %s\n' "$lang" "$dir"; return
  fi
  step "render control-loop frames · $lang"
  rm -rf "$dir"; mkdir -p "$dir"
  SITE_LOOP_OUT="$dir" SHOWCASE_LANG="$lang" SHOWCASE_FPS="$FPS" CCP_CAPTURE_LOCALE="$lang" \
    "$ROOT/gradlew" -p "$ROOT" :mobile:composeApp:desktopTest \
      --tests dev.ccpocket.app.showcase.ShowcaseRender --rerun --console=plain -q
  [ -f "$dir/f00000.png" ] || die "no frames written to $dir — did renderSiteLoop skip? (SITE_LOOP_OUT unset)"
}

render_loop en
render_loop zh

# ── 2 · encode: muted, web-optimized, deterministic ────────────────────────────────────────────
# No audio track at all (-an): these are ambient product loops, never something to listen to.
encode_loop() {                                  # $1 = en|zh
  local lang="$1" dir="$WORK/loop-$1"
  step "encode control-loop-$lang.mp4"
  ffmpeg -nostdin -y -loglevel error \
    -framerate "$FPS" -i "$dir/f%05d.png" \
    -vf "format=yuv420p" \
    -c:v libx264 -profile:v high -preset slow -crf 30 -pix_fmt yuv420p \
    -x264-params "keyint=60:min-keyint=30:scenecut=0" \
    -movflags +faststart -an \
    "$OUT/control-loop-$lang.mp4"

  # Poster == the frame reduced-motion and failed-load visitors see. Frame 135 (t=4.5s) is the
  # approval decision: the one still that states the product's job without motion.
  step "poster control-loop-$lang-poster.jpg"
  ffmpeg -nostdin -y -loglevel error -i "$dir/f00135.png" -qscale:v 3 \
    "$OUT/control-loop-$lang-poster.jpg"
}

encode_loop en
encode_loop zh

# ── 3 · desktop console screenshot (real desktop Compose UI, offscreen) ─────────────────────────
step "render desktop console screenshot"
CCP_CAPTURE_LOCALE=en "$ROOT/gradlew" -p "$ROOT" :mobile:composeApp:desktopTest \
  --tests dev.ccpocket.app.desktop.DesktopScreenshotTest --rerun --console=plain -q
SHOTS="$ROOT/mobile/composeApp/build/screenshots"
[ -f "$SHOTS/01-shell.png" ] || die "DesktopScreenshotTest produced no 01-shell.png in $SHOTS"
ffmpeg -nostdin -y -loglevel error -i "$SHOTS/01-shell.png" \
  -vf "scale=1600:-2:flags=lanczos" "$OUT/desktop-console.png"

# ── 4 · README overview: one deterministic composition of real frames ───────────────────────────
# Desktop console + two phone stills from the same reel. Composition and crop only — no invented
# UI, no re-drawn chrome.
step "compose README overview image"
ffmpeg -nostdin -y -loglevel error \
  -f lavfi -i "color=c=0x0E0F11:s=1200x630" \
  -i "$OUT/desktop-console.png" \
  -i "$WORK/loop-en/f00040.png" \
  -i "$WORK/loop-en/f00135.png" \
  -filter_complex "\
    [1:v]scale=-2:430,pad=iw+2:ih+2:1:1:0x2A2E33[dk];\
    [2:v]scale=-2:500,pad=iw+2:ih+2:1:1:0x2A2E33[p1];\
    [3:v]scale=-2:500,pad=iw+2:ih+2:1:1:0x2A2E33[p2];\
    [0:v][dk]overlay=40:100[a];\
    [a][p1]overlay=706:65[b];\
    [b][p2]overlay=948:65,format=rgb24" \
  -frames:v 1 "$OUT/overview.png"

# ── 5 · manifest + provenance ──────────────────────────────────────────────────────────────────
step "write manifest.json"
python3 "$ROOT/marketing/site/write-manifest.py" "$OUT" "$FPS"

# ── 6 · verify: nothing empty, dimensions and durations as published ────────────────────────────
step "verify"
for f in control-loop-en.mp4 control-loop-zh.mp4 control-loop-en-poster.jpg \
         control-loop-zh-poster.jpg desktop-console.png overview.png manifest.json; do
  [ -s "$OUT/$f" ] || die "$OUT/$f is missing or empty"
done
for f in control-loop-en.mp4 control-loop-zh.mp4 control-loop-en-poster.jpg \
         control-loop-zh-poster.jpg desktop-console.png overview.png; do
  printf '  %-28s %8s KB  %s\n' "$f" "$(( $(wc -c < "$OUT/$f") / 1024 ))" \
    "$(ffprobe -v error -select_streams v:0 -show_entries stream=width,height \
        -show_entries format=duration -of csv=p=0:s=x "$OUT/$f" | tr '\n' ' ')"
done

printf '\n[generate-assets] done → %s\n' "$OUT"
