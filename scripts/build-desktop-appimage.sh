#!/usr/bin/env bash
# BEST-EFFORT AppImage for the Pairlet desktop app (issue #379).
#
# .deb and .rpm are the SUPPORTED Linux packages — they are produced by scripts/release-desktop-linux.sh
# and their failure fails the release job. This script is the distro-independent extra for users who
# cannot or will not install a system package, and the release workflow runs it with
# `continue-on-error: true`: appimagetool is a rolling "continuous" upstream release with no stable
# pinned tag, so a bad upstream day must never block the deb/rpm/dmg/msi artifacts.
#
# jpackage has no AppImage target, so we assemble the AppDir by hand from the app image jpackage
# already produced, then hand it to appimagetool.
#
# GitHub-hosted runners have no FUSE, so:
#   - appimagetool itself is run with --appimage-extract-and-run
#   - the produced AppImage is verified with --appimage-extract (the type-2 runtime supports both
#     without FUSE), and the EXTRACTED launcher must pass the same --package-smoke gate the
#     deb/rpm/dmg/msi images pass. An AppImage that fails that gate is deleted, never uploaded.
#
# Usage: scripts/build-desktop-appimage.sh [version]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

VERSION="${1:-$(grep -E 'val appVersionName *= *"' mobile/composeApp/build.gradle.kts | sed -E 's/.*"([^"]+)".*/\1/')}"
[ -n "$VERSION" ] || { echo "ERROR: could not determine version (pass it as the first arg)"; exit 1; }

# appimagetool needs the machine spelling (x86_64 / aarch64); the release asset keeps the repo's
# spelling (x86_64 / arm64), matching the dmg and the daemon tarballs.
MACHINE="$(uname -m)"
case "$MACHINE" in
  aarch64|arm64) MACHINE="aarch64"; ARCH="arm64" ;;
  x86_64)        MACHINE="x86_64";  ARCH="x86_64" ;;
  *) echo "ERROR: unsupported architecture for AppImage: $MACHINE"; exit 1 ;;
esac

APP_IMAGE="mobile/composeApp/build/compose/binaries/main/app/CC Pocket"
[ -d "$APP_IMAGE" ] || { echo "ERROR: run scripts/release-desktop-linux.sh first — no app image at $APP_IMAGE"; exit 1; }

WORK="mobile/composeApp/build/appimage"
APPDIR="$WORK/Pairlet.AppDir"
rm -rf "$WORK"
mkdir -p "$APPDIR/usr" "$APPDIR/usr/share/icons/hicolor/256x256/apps" "$APPDIR/usr/share/applications"
cp -a "$APP_IMAGE/." "$APPDIR/usr/"

# AppRun is what the AppImage runtime executes. `readlink -f $0` resolves the mount point, so the
# launcher finds its own bin/lib layout regardless of where the AppImage was placed. The launcher
# name keeps the space — it is the frozen packageName (docs/PAIRLET-COMPATIBILITY.md), so quote it.
cat > "$APPDIR/AppRun" <<'APPRUN'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
exec "$HERE/usr/bin/CC Pocket" "$@"
APPRUN
chmod +x "$APPDIR/AppRun"

# appimagetool requires a .desktop file AND a matching icon at the AppDir root. Exec points at AppRun
# rather than the launcher: desktop-file-validate treats the space in "CC Pocket" as an argument
# separator, and the AppImage runtime executes AppRun anyway.
ICON_NAME="cc-pocket"
cat > "$APPDIR/$ICON_NAME.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=CC Pairlet
GenericName=AI coding agent companion
Comment=Drive Claude Code and Codex on your computer from another device
Exec=AppRun
Icon=$ICON_NAME
Categories=Development;
Terminal=false
StartupWMClass=CC Pocket
X-AppImage-Version=$VERSION
DESKTOP
cp "$APPDIR/$ICON_NAME.desktop" "$APPDIR/usr/share/applications/$ICON_NAME.desktop"

SOURCE_ICON="mobile/composeApp/src/desktopMain/resources/app-icon.png"
[ -f "$SOURCE_ICON" ] || { echo "ERROR: launcher icon not found at $SOURCE_ICON"; exit 1; }
cp "$SOURCE_ICON" "$APPDIR/$ICON_NAME.png"
cp "$SOURCE_ICON" "$APPDIR/usr/share/icons/hicolor/256x256/apps/$ICON_NAME.png"
cp "$SOURCE_ICON" "$APPDIR/.DirIcon"

TOOL="$WORK/appimagetool"
TOOL_URL="https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-${MACHINE}.AppImage"
echo "==> fetch appimagetool ($MACHINE)"
curl -fsSL --retry 3 --retry-delay 5 -o "$TOOL" "$TOOL_URL"
chmod +x "$TOOL"

OUT="cc-pocket-desktop-${VERSION}-linux-${ARCH}.AppImage"
rm -f "$ROOT/$OUT"
echo "==> appimagetool → $OUT"
# ARCH is appimagetool's own env contract (it refuses to guess); --appimage-extract-and-run avoids FUSE.
ARCH="$MACHINE" "$TOOL" --appimage-extract-and-run "$APPDIR" "$ROOT/$OUT"
[ -f "$ROOT/$OUT" ] || { echo "ERROR: appimagetool produced no output"; exit 1; }

echo "==> verify the produced AppImage by extracting it and smoking the bundled JVM"
VERIFY="$WORK/verify"
rm -rf "$VERIFY"; mkdir -p "$VERIFY"
( cd "$VERIFY" && "$ROOT/$OUT" --appimage-extract >/dev/null )
EXTRACTED="$VERIFY/squashfs-root/usr/bin/CC Pocket"
[ -x "$EXTRACTED" ] || { echo "ERROR: extracted AppImage has no launcher at usr/bin/CC Pocket"; rm -f "$ROOT/$OUT"; exit 1; }
MARKER="$(mktemp -t ccpocket-appimage-smoke.XXXXXX)"
if "$EXTRACTED" --package-smoke "$MARKER" && grep -Fqx 'CCP_PACKAGE_SMOKE_OK' "$MARKER"; then
  rm -f "$MARKER"
else
  rm -f "$MARKER" "$ROOT/$OUT"
  echo "ERROR: AppImage failed the packaged-image smoke — artifact deleted, not publishable"
  exit 1
fi

echo ""
echo "    artifact : $OUT"
echo "    sha256   : $(sha256sum "$ROOT/$OUT" | awk '{print $1}')"
