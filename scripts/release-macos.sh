#!/usr/bin/env bash
# Build → jpackage (bundled JRE) → codesign (Developer ID) → notarize → staple → tarball.
# Produces a self-contained, notarized cc-pocket-daemon for the current Mac arch.
#
# Prereqs:
#   - JDK 17 with jpackage on PATH (JAVA_HOME set)
#   - Xcode Command Line Tools (codesign, xcrun, ditto, stapler)
#   - A "Developer ID Application" certificate in your login keychain
#   - A notarytool keychain profile, created once:
#       xcrun notarytool store-credentials cc-pocket \
#         --apple-id you@example.com --team-id TEAMID --password <app-specific-password>
#
# Env:
#   DEVELOPER_ID    e.g. "Developer ID Application: Your Name (TEAMID)"
#   NOTARY_PROFILE  the keychain profile name (default: cc-pocket)
#
# Usage: scripts/release-macos.sh [version]
set -euo pipefail

VERSION="${1:-1.0.0}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# auto-load DEVELOPER_ID / NOTARY_PROFILE from .env if present (gitignored)
[ -f "$ROOT/.env" ] && { set -a; . "$ROOT/.env"; set +a; }

NOTARY_PROFILE="${NOTARY_PROFILE:-cc-pocket}"
: "${DEVELOPER_ID:?set DEVELOPER_ID (in .env or the environment) to your 'Developer ID Application: …' identity}"

ARCH="$(uname -m)" # arm64 | x86_64

echo "==> gradle build + jpackage (bundled JRE)"
# gradle.properties pins org.gradle.java.home to the Mac dev box's Homebrew JDK, which doesn't
# exist on a CI runner. Override it from JAVA_HOME when set (CI / setup-java); fall back to the
# committed pin when JAVA_HOME is unset (the local dev convenience). jpackage bundles whichever
# JDK runs gradle, so on the macos-13 (Intel) runner this yields an x86_64 image, and on
# macos-14 (Apple Silicon) an arm64 image — that's how we cross-cover both arches.
./gradlew :daemon:packageDaemon -q -PappVersion="$VERSION" ${JAVA_HOME:+-Dorg.gradle.java.home="$JAVA_HOME"}
APP="$ROOT/daemon/build/jpackage/cc-pocket-daemon.app"
[ -d "$APP" ] || { echo "ERROR: jpackage output not found at $APP"; exit 1; }

# Native libs bundled INSIDE jars (JNA, Jansi, …) are Mach-O too; codesign --deep can't reach
# them, so notarization rejects the unsigned ones. Extract → sign → repack each Mac native lib.
echo "==> sign Mac native libs inside JARs"
find "$APP/Contents/app" -name '*.jar' | while IFS= read -r jar; do
  libs="$(unzip -Z1 "$jar" 2>/dev/null | grep -E '\.(jnilib|dylib)$' || true)"
  [ -z "$libs" ] && continue
  echo "    $(basename "$jar"): $(echo "$libs" | tr '\n' ' ')"
  tmp="$(mktemp -d)"
  ( cd "$tmp" && unzip -qo "$jar" $libs
    while IFS= read -r lib; do
      file "$lib" | grep -q "Mach-O" && \
        codesign --force --options runtime --timestamp --sign "$DEVELOPER_ID" "$lib"
    done <<< "$libs"
    zip -qX "$jar" $libs )
  rm -rf "$tmp"
done

echo "==> codesign with hardened runtime (JVM needs JIT entitlements)"
ENT="$(mktemp -t ccp-entitlements).plist"
cat > "$ENT" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>com.apple.security.cs.allow-jit</key><true/>
  <key>com.apple.security.cs.allow-unsigned-executable-memory</key><true/>
  <key>com.apple.security.cs.disable-library-validation</key><true/>
</dict></plist>
PLIST
# Inside-out signing: --deep is unreliable for the bundled JRE's dylibs, so sign every nested
# Mach-O individually (hardened runtime + secure timestamp), then the launcher + bundle last.
echo "    collecting nested Mach-O binaries…"
MACHOS=()
while IFS= read -r f; do
  case "$f" in
    *.dylib | *.jnilib) MACHOS+=("$f") ;;
    *) file "$f" 2>/dev/null | grep -q "Mach-O" && MACHOS+=("$f") ;;
  esac
done < <(find "$APP" -type f)
echo "    signing ${#MACHOS[@]} nested binaries (hardened runtime + timestamp)…"
printf '%s\0' "${MACHOS[@]}" | xargs -0 codesign --force --options runtime --timestamp --sign "$DEVELOPER_ID"

echo "    signing launcher + bundle (with JIT entitlements)…"
codesign --force --options runtime --timestamp --entitlements "$ENT" --sign "$DEVELOPER_ID" "$APP/Contents/MacOS/cc-pocket-daemon"
codesign --force --options runtime --timestamp --entitlements "$ENT" --sign "$DEVELOPER_ID" "$APP"
codesign --verify --strict --verbose=2 "$APP"

if [ -n "${SKIP_NOTARIZE:-}" ]; then
  echo "==> SKIP_NOTARIZE set — producing a signed-but-NOT-notarized build (test only; users would see a Gatekeeper warning)"
else
  echo "==> notarize (this can take a few minutes)"
  ZIP="$(mktemp -t ccp-notarize).zip"
  ditto -c -k --keepParent "$APP" "$ZIP"
  # Two auth paths: a keychain profile created with `notarytool store-credentials` (local dev),
  # or raw Apple-ID creds from env (CI, where importing a keychain profile is awkward). Prefer
  # the explicit creds when all three are present, else fall back to the keychain profile.
  if [ -n "${APPLE_ID:-}" ] && [ -n "${APPLE_APP_PASSWORD:-}" ] && [ -n "${APPLE_TEAM_ID:-}" ]; then
    xcrun notarytool submit "$ZIP" \
      --apple-id "$APPLE_ID" --team-id "$APPLE_TEAM_ID" --password "$APPLE_APP_PASSWORD" --wait
  else
    xcrun notarytool submit "$ZIP" --keychain-profile "$NOTARY_PROFILE" --wait
  fi
  xcrun stapler staple "$APP"
  xcrun stapler validate "$APP"
fi

echo "==> tarball + checksum"
OUT="cc-pocket-daemon-${VERSION}-macos-${ARCH}.tar.gz"
tar -C "$(dirname "$APP")" -czf "$OUT" "$(basename "$APP")"
echo ""
echo "    artifact : $OUT"
echo "    sha256   : $(shasum -a 256 "$OUT" | awk '{print $1}')"
echo ""
echo "Next: attach $OUT to the GitHub release v$VERSION and paste the sha256 into the Homebrew formula."
