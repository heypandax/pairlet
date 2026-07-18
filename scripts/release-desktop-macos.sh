#!/usr/bin/env bash
# Build the cc-pocket DESKTOP app (Compose Desktop, two-pane client) for macOS:
#   gradle packageDmg → (sign via the Compose plugin) → notarize → staple → stable-named .dmg.
#
# Unlike the daemon (scripts/release-macos.sh), the Compose Desktop Gradle plugin signs the .app
# itself when -PccpocketSignId is passed (see build.gradle.kts `macOS { signing }`), so there is no
# manual nested-Mach-O signing here — we just notarize + staple the finished DMG.
#
# Prereqs:
#   - JDK 17 with jpackage (JAVA_HOME); on a Homebrew JDK we pass checkJdkVendor=false
#   - Xcode Command Line Tools (codesign, xcrun, stapler)
#   - a "Developer ID Application" certificate in the keychain        (only for a signed build)
#   - a real mobile/composeApp/google-services.json — composeApp applies the Android plugin at
#     configure time; the desktop target never touches Firebase, so CI writes a placeholder.
#
# Env (auto-loaded from .env if present, gitignored):
#   DEVELOPER_ID                                   "Developer ID Application: … (TEAMID)".
#                                                  Omit to build an UNSIGNED dmg (local/dev only).
#   APPLE_ID / APPLE_APP_PASSWORD / APPLE_TEAM_ID  notarization creds (else a NOTARY_PROFILE fallback)
#   NOTARY_PROFILE                                 notarytool keychain profile (default: cc-pocket)
#   SKIP_NOTARIZE=1                                sign but skip notarization (fast local test)
#
# Usage: scripts/release-desktop-macos.sh [version]   (version defaults to build.gradle appVersionName)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
[ -f "$ROOT/.env" ] && { set -a; . "$ROOT/.env"; set +a; }

# Version: arg wins, else the single source of truth in build.gradle.kts (keeps the name in lockstep).
VERSION="${1:-$(grep -E 'val appVersionName *= *"' mobile/composeApp/build.gradle.kts | sed -E 's/.*"([^"]+)".*/\1/')}"
[ -n "$VERSION" ] || { echo "ERROR: could not determine version (pass it as the first arg)"; exit 1; }
# uname -m still reports arm64 under Rosetta, so CCP_ARCH lets CI name the dmg for the JDK's arch: an
# x86_64 Temurin JDK under Rosetta makes packageDmg bundle the x64 Skiko + JRE → a native Intel dmg (#68).
# Local builds default to the host arch.
ARCH="${CCP_ARCH:-$(uname -m)}" # arm64 | x86_64
NOTARY_PROFILE="${NOTARY_PROFILE:-cc-pocket}"

# Local convenience: gradle.properties pins a Homebrew JDK but the launcher still needs JAVA_HOME.
# Default it when unset and present; CI sets its own JAVA_HOME (temurin), so this is a no-op there.
if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@17 ]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17
fi

[ -f mobile/composeApp/google-services.json ] || \
  echo "WARN: mobile/composeApp/google-services.json missing — composeApp configure may fail (CI writes a placeholder)."

SIGN_ARGS=()
if [ -n "${DEVELOPER_ID:-}" ]; then
  echo "==> signed build  ($DEVELOPER_ID)"
  SIGN_ARGS=(-PccpocketSignId="$DEVELOPER_ID")
else
  echo "==> WARNING: DEVELOPER_ID unset — building an UNSIGNED dmg (dev only; Gatekeeper warns on other Macs)."
fi

# pty4j ships Mach-O binaries INSIDE its jar (resources/com/pty4j/native/darwin/*). The Compose
# plugin signs every binary it can SEE in the .app, but never looks inside jars — and Apple's notary
# service does: v1.4.0 was rejected with "the executable does not have the hardened runtime enabled"
# on pty4j-unix-spawn-helper (#153 embedded terminal). Fix: resolve the dependency first, then
# re-sign the darwin natives in the CACHED jar (Developer ID + hardened runtime + timestamp) and zip
# them back, so packageDmg packs an already-clean jar. Signatures travel with the files, so the
# helper pty4j extracts at runtime stays valid. Signed builds only — an unsigned dev dmg needs none.
if [ -n "${DEVELOPER_ID:-}" ]; then
  echo "==> pre-resolve desktop classpath (populates the dependency cache)"
  ./gradlew :mobile:composeApp:desktopJar --no-daemon -q \
    ${JAVA_HOME:+-Dorg.gradle.java.home="$JAVA_HOME"}
  echo "==> sign darwin natives inside cached pty4j jar(s)"
  find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2" -name 'pty4j-*.jar' ! -name '*sources*' | while read -r JAR; do
    WORK="$(mktemp -d)"
    unzip -q "$JAR" 'resources/com/pty4j/native/darwin/*' -d "$WORK" 2>/dev/null || { rm -rf "$WORK"; continue; }
    find "$WORK" -type f | while read -r BIN; do
      codesign --force --options runtime --timestamp --sign "$DEVELOPER_ID" "$BIN"
      echo "    signed $(basename "$BIN") in $(basename "$JAR")"
    done
    (cd "$WORK" && zip -q "$JAR" resources/com/pty4j/native/darwin/*)
    rm -rf "$WORK"
  done
fi

echo "==> gradle :mobile:composeApp:packageDmg  (v$VERSION · $ARCH)"
./gradlew :mobile:composeApp:packageDmg --no-daemon -q \
  -Pcompose.desktop.packaging.checkJdkVendor=false \
  ${JAVA_HOME:+-Dorg.gradle.java.home="$JAVA_HOME"} \
  "${SIGN_ARGS[@]}"

DMG="$(ls -t mobile/composeApp/build/compose/binaries/main/dmg/*.dmg 2>/dev/null | head -1)"
[ -n "$DMG" ] && [ -f "$DMG" ] || { echo "ERROR: packageDmg produced no .dmg"; exit 1; }

# Sanity: the .app inside should carry the Developer ID signature (the dmg itself isn't codesigned).
if [ -n "${DEVELOPER_ID:-}" ]; then
  APP="$(ls -dt mobile/composeApp/build/compose/binaries/main/app/*.app 2>/dev/null | head -1)"
  [ -n "$APP" ] && codesign --verify --strict "$APP" && echo "    signed app verified: $(basename "$APP")"
fi

if [ -z "${DEVELOPER_ID:-}" ] || [ -n "${SKIP_NOTARIZE:-}" ]; then
  echo "==> skipping notarization (unsigned, or SKIP_NOTARIZE set)"
else
  echo "==> notarize (can take a few minutes)…"
  if [ -n "${APPLE_ID:-}" ] && [ -n "${APPLE_APP_PASSWORD:-}" ] && [ -n "${APPLE_TEAM_ID:-}" ]; then
    xcrun notarytool submit "$DMG" --apple-id "$APPLE_ID" --team-id "$APPLE_TEAM_ID" --password "$APPLE_APP_PASSWORD" --wait
  else
    xcrun notarytool submit "$DMG" --keychain-profile "$NOTARY_PROFILE" --wait
  fi
  echo "==> staple"
  xcrun stapler staple "$DMG"
  xcrun stapler validate "$DMG"
fi

OUT="cc-pocket-desktop-${VERSION}-macos-${ARCH}.dmg"
cp -f "$DMG" "$ROOT/$OUT"
echo ""
echo "    artifact : $OUT"
echo "    sha256   : $(shasum -a 256 "$ROOT/$OUT" | awk '{print $1}')"
[ -z "${DEVELOPER_ID:-}" ] && echo "    NOTE: UNSIGNED — for distribution set DEVELOPER_ID (+ APPLE_* creds) and re-run."
echo ""
echo "Next: attach to the GitHub release →  gh release upload v$VERSION $OUT --clobber"
