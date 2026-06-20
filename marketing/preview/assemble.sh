#!/bin/bash
# Assemble the final App Preview: speed scenes 1-5, build scene 6 (banner slide-in + logo card),
# overlay the 6 captions, and scale to the App Store size. Pure ffmpeg — no re-recording.
#
# Usage:  ./assemble.sh <en|zh> <build_dir> <assets_dir> <final.mov>
#   build_dir  : has main.mov + home.png (from record.sh)
#   assets_dir : has cap1..6.png, logo.png, banner.png (from render_assets.py)
set -e
LANG_CODE="${1:-en}"; B="${2:?build dir}"; A="${3:?assets dir}"; FINAL="${4:?out file}"
SIZE="886:1920"          # App Store 6.9" preview (or 1320:2868)
SPEED=1.12               # tightens scenes 1-5 so total stays < 30s
# caption windows (seconds, on the final timeline) — aligned to the deterministic driver pacing
CW=("0.4,3.8" "4.2,7.8" "8.2,12.6" "13.0,16.0" "18.0,24.3" "25.0,29.2")

# --- scene 6 (5s): home backdrop + banner slide-in (2.8s) then logo card (2.2s) ---
ffmpeg -nostdin -y -loglevel error -loop 1 -t 2.8 -i "$B/home.png" -i "$A/banner.png" \
  -filter_complex "[0:v]scale=1320:2868,format=yuv420p[bg];[bg][1:v]overlay=x=40:y='if(lt(t,0.45),-260+726*t,67)'[v]" \
  -map "[v]" -t 2.8 -r 60 -pix_fmt yuv420p "$B/s6a.mov"
ffmpeg -nostdin -y -loglevel error -loop 1 -t 2.2 -i "$A/logo.png" \
  -filter_complex "[0:v]fade=t=in:st=0:d=0.4,format=yuv420p[v]" -map "[v]" -t 2.2 -r 60 -pix_fmt yuv420p "$B/s6b.mov"
printf "file 's6a.mov'\nfile 's6b.mov'\n" > "$B/s6.txt"
ffmpeg -nostdin -y -loglevel error -f concat -safe 0 -i "$B/s6.txt" -c copy "$B/scene6.mov"

# --- final: [scenes1-5 sped] + scene6, overlay captions, scale ---
# App Store Connect requires: H.264 High@4.0, ~10-12 Mbps, 30fps, AND a stereo AAC audio track
# (256 kbps, 48 kHz) — uploads are rejected without audio. anullsrc supplies a silent stereo track.
ffmpeg -nostdin -y -loglevel error \
  -i "$B/main.mov" -i "$B/scene6.mov" \
  -i "$A/cap1.png" -i "$A/cap2.png" -i "$A/cap3.png" -i "$A/cap4.png" -i "$A/cap5.png" -i "$A/cap6.png" \
  -f lavfi -i anullsrc=channel_layout=stereo:sample_rate=48000 \
  -filter_complex "\
[0:v]setpts=PTS/$SPEED,fps=60,setsar=1[m];[1:v]fps=60,setsar=1[s6];[m][s6]concat=n=2:v=1[b];\
[b][2:v]overlay=0:0:enable='between(t,${CW[0]})'[v1];\
[v1][3:v]overlay=0:0:enable='between(t,${CW[1]})'[v2];\
[v2][4:v]overlay=0:0:enable='between(t,${CW[2]})'[v3];\
[v3][5:v]overlay=0:0:enable='between(t,${CW[3]})'[v4];\
[v4][6:v]overlay=0:0:enable='between(t,${CW[4]})'[v5];\
[v5][7:v]overlay=0:0:enable='between(t,${CW[5]})',scale=$SIZE:flags=lanczos,setsar=1[v]" \
  -map "[v]" -map 8:a -t 29.9 -r 30 \
  -c:v libx264 -profile:v high -level 4.0 -pix_fmt yuv420p -b:v 11M -maxrate 12M -bufsize 24M \
  -c:a aac -b:a 256k -ar 48000 -ac 2 \
  -movflags +faststart "$FINAL"
echo "DONE -> $FINAL"
ffprobe -v error -select_streams v:0 -show_entries stream=width,height,r_frame_rate -show_entries format=duration -of default=noprint_wrappers=1 "$FINAL"
