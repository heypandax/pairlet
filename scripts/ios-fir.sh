#!/usr/bin/env bash
#
# Build a signed iOS IPA and publish it to fir.im for on-device testing — one command.
#
#   scripts/ios-fir.sh [development|release-testing]
#
# METHOD (default: development):
#   development     Signs with the local "Apple Development" cert; installs on the devices already in the
#                   team's Xcode-managed provisioning profile. Needs NO distribution cert — this is what
#                   works on a machine without an "Apple Distribution" cert for the team.
#   release-testing Proper ad-hoc. Needs an "Apple Distribution" cert for team SC9S2SJ42G in the keychain
#                   AND a valid Xcode account login (so -allowProvisioningUpdates can mint the ad-hoc profile).
#
# fir.im token: taken from $FIR_TOKEN, else from scripts/.fir-token (gitignored — put the token there once).
#
# Prereqs: Xcode, fir-cli (`gem install fir-cli`), a JDK 17 (JAVA_HOME), and an Apple ID signed into Xcode
#   that's a member of team SC9S2SJ42G. The target device's UDID must already be registered: connect it to
#   this Mac once (Xcode auto-registers), or add it in the Apple Developer portal AND re-login the Xcode
#   account so it syncs into the managed profile — otherwise the IPA won't install on it ("unable to install").
#
set -euo pipefail

METHOD="${1:-development}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# --- JDK for the Kotlin/Native framework build (Xcode's PATH usually lacks java) ---
: "${JAVA_HOME:=/opt/homebrew/opt/openjdk@17}"
export JAVA_HOME

# --- fir.im token (never committed) ---
if [[ -z "${FIR_TOKEN:-}" && -f scripts/.fir-token ]]; then FIR_TOKEN="$(tr -d ' \n' < scripts/.fir-token)"; fi
: "${FIR_TOKEN:?set FIR_TOKEN=<fir.im token>, or put it in scripts/.fir-token}"

command -v fir >/dev/null || { echo "fir-cli not found — run: gem install fir-cli" >&2; exit 1; }

case "$METHOD" in
  development)     OPTS="iosApp/ExportOptions-dev.plist" ;;
  release-testing) OPTS="iosApp/ExportOptions-adhoc.plist" ;;
  *) echo "unknown method '$METHOD' (use development | release-testing)" >&2; exit 2 ;;
esac

ARCHIVE="build/ccpocket-ios.xcarchive"
EXPORT_DIR="build/ccpocket-ios-ipa"

echo "▸ archiving iosApp (Release) — the Kotlin/Native framework build is the slow part…"
rm -rf "$ARCHIVE"
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Release \
  -destination 'generic/platform=iOS' -archivePath "$ARCHIVE" \
  -allowProvisioningUpdates archive

echo "▸ exporting IPA ($METHOD) via $OPTS…"
rm -rf "$EXPORT_DIR"
xcodebuild -exportArchive -archivePath "$ARCHIVE" -exportPath "$EXPORT_DIR" \
  -exportOptionsPlist "$OPTS" -allowProvisioningUpdates

IPA="$(ls "$EXPORT_DIR"/*.ipa 2>/dev/null | head -1)"
[[ -n "$IPA" ]] || { echo "export produced no .ipa" >&2; exit 3; }
echo "▸ built: $IPA"

echo "▸ publishing to fir.im…"
fir publish "$IPA" -T "$FIR_TOKEN"
