#!/usr/bin/env bash
# One-time: read Apple ID + app-specific password from .env and store a notarytool
# credential profile in the macOS Keychain. After this, the password lives in the
# Keychain (not in .env), and scripts/release-macos.sh can notarize.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[ -f "$ROOT/.env" ] || { echo "ERROR: $ROOT/.env not found. Copy the template and fill it in."; exit 1; }
set -a; . "$ROOT/.env"; set +a

: "${APPLE_ID:?set APPLE_ID in .env}"
: "${APPLE_APP_PASSWORD:?set APPLE_APP_PASSWORD in .env}"
: "${APPLE_TEAM_ID:?set APPLE_TEAM_ID in .env}"
NOTARY_PROFILE="${NOTARY_PROFILE:-cc-pocket}"

case "$APPLE_ID" in *填*|"") echo "ERROR: APPLE_ID still a placeholder — edit .env"; exit 1;; esac
case "$APPLE_APP_PASSWORD" in *填*|"") echo "ERROR: APPLE_APP_PASSWORD still a placeholder — edit .env"; exit 1;; esac

echo "Storing notarytool credentials into Keychain profile '$NOTARY_PROFILE'…"
xcrun notarytool store-credentials "$NOTARY_PROFILE" \
  --apple-id "$APPLE_ID" \
  --team-id "$APPLE_TEAM_ID" \
  --password "$APPLE_APP_PASSWORD"

echo
echo "✓ Done. Credentials are in the Keychain now."
echo "  You can delete .env if you like — release-macos.sh uses the Keychain profile, not the password."
echo "  Next: scripts/release-macos.sh 1.0.0   (it also auto-loads DEVELOPER_ID from .env)"
