#!/usr/bin/env bash
# video.sh — 宣传视频管线统一入口（薄转发；全部逻辑在 marketing/video/render.py）
set -euo pipefail
cd "$(dirname "$0")/marketing/video"

cmd="${1:-help}"; shift || true
case "$cmd" in
  validate|resolve-timeline|animatic|render|render-scene|lock-facts|footage-contact-sheet)
    if [ "$cmd" = "render-scene" ]; then
      sb="$1"; seg="$2"; shift 2
      exec python3 render.py render "$sb" --only-segment "$seg" --allow-placeholder "$@"
    fi
    exec python3 render.py "$cmd" "$@" ;;
  probe-video) exec python3 tools/gptproto_probe.py "${1:-both}" ;;
  *)
    echo "usage: ./video.sh <command>"
    echo "  lock-facts                          刷新并锁定数字事实（显式执行才更新）"
    echo "  resolve-timeline <sb.json>          分段 TTS → output/timeline.lock.json"
    echo "  validate <sb.json> [--allow-placeholder]   红线词 + 素材存在性检查"
    echo "  animatic <sb.json> [--fps 15]       灰卡动态分镜稿 → output/animatic.mp4"
    echo "  render <sb.json> [--allow-placeholder] [--out name.mp4]"
    echo "  render-scene <sb.json> <segmentId>  单段快速迭代"
    echo "  footage-contact-sheet               生活素材候选缩略图"
    exit 1 ;;
esac
