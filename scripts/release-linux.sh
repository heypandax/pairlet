#!/usr/bin/env bash
# Build → jpackage (bundled JRE) → tarball for Linux.
# Produces a self-contained cc-pocket-daemon app-image (runs with no system Java).
#
# Unlike macOS there is no codesigning / notarization on Linux, so this is the whole pipeline.
#
# Prereqs:
#   - JDK 17 with jpackage on PATH (JAVA_HOME set)
#   - Run on the target arch (x86_64 or aarch64): jpackage bundles the host JRE, no cross-build.
#
# Usage: scripts/release-linux.sh [version]
set -euo pipefail

VERSION="${1:-1.0.0}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# Normalize to the release asset spelling: install.sh, UpdateService.assetNameFor and the macOS
# artifacts all say "arm64", while uname on Linux reports "aarch64".
case "$(uname -m)" in
  aarch64|arm64) ARCH="arm64" ;;
  *)             ARCH="$(uname -m)" ;;
esac

echo "==> gradle build + jpackage (bundled JRE)"
# The repo's gradle.properties pins org.gradle.java.home to the Mac dev box's Homebrew JDK,
# which doesn't exist on Linux. Override it from JAVA_HOME when set (CI / a Linux user's JDK);
# fall back to the committed pin when JAVA_HOME is unset (the Mac dev's local convenience).
./gradlew :daemon:packageDaemon -q -PappVersion="$VERSION" ${JAVA_HOME:+-Dorg.gradle.java.home="$JAVA_HOME"}
APP="$ROOT/daemon/build/jpackage/cc-pocket-daemon"
[ -d "$APP" ] || { echo "ERROR: jpackage output not found at $APP"; exit 1; }

echo "==> tarball + checksum"
OUT="cc-pocket-daemon-${VERSION}-linux-${ARCH}.tar.gz"
tar -C "$(dirname "$APP")" -czf "$OUT" "$(basename "$APP")"
echo ""
echo "    artifact : $OUT"
echo "    sha256   : $(sha256sum "$OUT" | awk '{print $1}')"
echo ""
echo "Install on the target machine:"
echo "  tar -xzf $OUT -C ~/.local/opt/"
echo "  ln -sf ~/.local/opt/cc-pocket-daemon/bin/cc-pocket-daemon ~/.local/bin/cc-pocket-daemon"
echo "  cc-pocket-daemon service-install --apply   # writes a systemd --user unit"
