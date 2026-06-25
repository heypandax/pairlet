#!/usr/bin/env bash
# Orchestrate a release build for the CURRENT host. jpackage can't cross-compile, so each OS builds
# its own native artifacts: this chains the per-platform scripts that can run here, so "build
# everything" and "build only the desktop app" share one entry point. Windows / Linux / Intel-mac
# artifacts come from CI (.github/workflows/release.yml) or a build on that OS.
#
# Per-platform workers (the real work lives here, this just sequences them):
#   daemon  : release-macos.sh · release-linux.sh · release-windows.ps1
#   desktop : release-desktop-macos.sh         (windows/linux desktop: CI only, for now)
#
# Usage:
#   scripts/release-all.sh [version]               # daemon + desktop app for this host
#   scripts/release-all.sh --desktop-only [ver]    # just the desktop app
#   SKIP_NOTARIZE=1 scripts/release-all.sh         # fast, un-notarized (local smoke)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"

DESKTOP_ONLY="${DESKTOP_ONLY:-}"
[ "${1:-}" = "--desktop-only" ] && { DESKTOP_ONLY=1; shift; }
VERSION="${1:-$(grep -E 'val appVersionName *= *"' mobile/composeApp/build.gradle.kts | sed -E 's/.*"([^"]+)".*/\1/')}"

OS="$(uname -s)"
echo "=== cc-pocket release build · v$VERSION · $OS $(uname -m)${DESKTOP_ONLY:+ · desktop-only} ==="

case "$OS" in
  Darwin)
    if [ -z "$DESKTOP_ONLY" ]; then
      echo; echo "### daemon (macOS) ###"; scripts/release-macos.sh "$VERSION"
    fi
    echo; echo "### desktop app (macOS) ###"; scripts/release-desktop-macos.sh "$VERSION"
    ;;
  Linux)
    if [ -z "$DESKTOP_ONLY" ]; then
      echo; echo "### daemon (Linux) ###"; scripts/release-linux.sh "$VERSION"
    fi
    echo "NOTE: Linux desktop app (.deb/AppImage) is not configured yet (build.gradle targetFormats = Dmg, Msi)."
    ;;
  *)
    echo "NOTE: on Windows run the PowerShell scripts: scripts/release-windows.ps1 (daemon) and the"
    echo "      desktop windows-app job in .github/workflows/build-windows.yml." ;;
esac

echo; echo "=== done · artifacts produced on this host ==="
ls -1 "$ROOT"/cc-pocket-*"$VERSION"*.{dmg,tar.gz} 2>/dev/null || echo "(none at repo root)"
echo "Cross-platform (windows/linux/intel-mac) come from CI:  gh workflow run release.yml -f version=$VERSION"
