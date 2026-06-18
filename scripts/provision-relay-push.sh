#!/usr/bin/env bash
# Provision the relay's push credentials (APNs .p8 + FCM service-account JSON) onto the production box
# and wire the systemd unit to read them. The secret VALUES never live in this script or the repo —
# only their local paths are passed in; the files are shipped to /etc/cc-pocket-relay/ (root-only).
# Run once, and again whenever a credential rotates. Does NOT restart the relay — run
# scripts/redeploy-relay.sh afterwards, which ships the push-enabled binary and restarts with the env.
#
#   RELAY_HOST=<ip> SSHPASS='<root pw>'   # from .env
#   APNS_P8=iosApp/AuthKey_XXXX.p8 APNS_KEY_ID=XXXX APNS_TEAM_ID=YYYY APNS_TOPIC=com.panda.ccpocket \
#   FCM_JSON=firebase/<service-account>.json \
#   bash scripts/provision-relay-push.sh
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] && { set -a; . ./.env; set +a; }

: "${RELAY_HOST:?set RELAY_HOST in .env}"
: "${SSHPASS:?set SSHPASS in .env}"
APNS_P8="${APNS_P8:?path to the APNs .p8}"
APNS_KEY_ID="${APNS_KEY_ID:?APNs Key ID (the 10-char id in the .p8 filename)}"
APNS_TEAM_ID="${APNS_TEAM_ID:?Apple Team ID}"
APNS_TOPIC="${APNS_TOPIC:-com.panda.ccpocket}"
FCM_JSON="${FCM_JSON:?path to the FCM service-account JSON}"
[ -f "$APNS_P8" ] || { echo "missing $APNS_P8"; exit 1; }
[ -f "$FCM_JSON" ] || { echo "missing $FCM_JSON"; exit 1; }

SSH=(sshpass -e ssh -o StrictHostKeyChecking=accept-new "root@$RELAY_HOST")
SCP=(sshpass -e scp -o StrictHostKeyChecking=accept-new)
DIR=/etc/cc-pocket-relay

echo "── 1/4 create secrets dir ──"
"${SSH[@]}" "mkdir -p $DIR"

echo "── 2/4 ship credential files ──"
"${SCP[@]}" "$APNS_P8" "root@$RELAY_HOST:$DIR/apns.p8"
"${SCP[@]}" "$FCM_JSON" "root@$RELAY_HOST:$DIR/fcm.json"

echo "── 3/4 write push.env + lock down (owner ccpocket, 600) ──"
"${SSH[@]}" "umask 077 && cat > $DIR/push.env <<EOF
CCPOCKET_APNS_KEY_P8=$DIR/apns.p8
CCPOCKET_APNS_KEY_ID=$APNS_KEY_ID
CCPOCKET_APNS_TEAM_ID=$APNS_TEAM_ID
CCPOCKET_APNS_TOPIC=$APNS_TOPIC
CCPOCKET_FCM_CREDENTIALS=$DIR/fcm.json
EOF
chown -R ccpocket:ccpocket $DIR && chmod 700 $DIR && chmod 600 $DIR/apns.p8 $DIR/fcm.json $DIR/push.env"

echo "── 4/4 install updated systemd unit + daemon-reload ──"
"${SCP[@]}" deploy/cc-pocket-relay.service "root@$RELAY_HOST:/etc/systemd/system/cc-pocket-relay.service"
"${SSH[@]}" "systemctl daemon-reload"
echo "✅ push creds provisioned. Next: bash scripts/redeploy-relay.sh  (ships the push-enabled binary + restarts)"
