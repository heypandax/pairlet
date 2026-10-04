#!/usr/bin/env bash
# 重新渲染官网的两张分享图（og:image，1200×630）：site/og-image.png 与 site/manual/og-manual.png。
# 来源是 share-cards/ 下的两份 HTML，用本机 Chrome 无头截图；字体走官网同一份 Google Fonts，需要联网。
# 用法：bash marketing/site/render-share-cards.sh
set -euo pipefail
cd "$(dirname "$0")/../.."

CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
[ -x "$CHROME" ] || { echo "找不到 Chrome：$CHROME（可用 CHROME=路径 指定）"; exit 1; }
render() { # <html> <png>
  local out="$PWD/$2" profile pid
  profile="$(mktemp -d)"
  rm -f "$out.new.png"
  # 无头 Chrome 截完图后偶尔不退出：后台跑，等文件落盘就结束它，最多等 60 秒
  "$CHROME" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 \
    --window-size=1200,630 --user-data-dir="$profile" --virtual-time-budget=15000 \
    --screenshot="$out.new.png" "file://$PWD/$1" >/dev/null 2>&1 &
  pid=$!
  for _ in $(seq 1 120); do [ -s "$out.new.png" ] && break; sleep 0.5; done
  sleep 1
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
  rm -rf "$profile"
  [ -s "$out.new.png" ] || { echo "渲染失败：$1"; exit 1; }
  size="$(sips -g pixelWidth -g pixelHeight "$out.new.png" | awk '/pixel/{printf "%s ", $2}')"
  [ "$size" = "1200 630 " ] || { echo "尺寸不对：$2 是 $size"; rm -f "$out.new.png"; exit 1; }
  mv -f "$out.new.png" "$out"
  echo "✅ $2"
}

render marketing/site/share-cards/og-image.html site/og-image.png
render marketing/site/share-cards/og-manual.html site/manual/og-manual.png
