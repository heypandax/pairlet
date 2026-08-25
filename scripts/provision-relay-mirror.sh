#!/usr/bin/env bash
# Provision (idempotently) the release mirror onto the relay box:
#   /opt/cc-pocket-mirror/mirror-sync.sh + systemd service/timer + the Caddyfile /dl route,
# then run the first sync and verify the public endpoint. Re-run any time — every step is a
# plain overwrite + reload. Reads RELAY_HOST_HK / SSHPASS_HK from .env like redeploy-relay.sh.
#
# NOTE: this only ships the FOUR mirror pieces. Relay code redeploys stay in redeploy-relay.sh.
set -euo pipefail
cd "$(dirname "$0")/.."

[ -f .env ] && { set -a; . ./.env; set +a; }
: "${RELAY_HOST_HK:?set RELAY_HOST_HK in .env (HK origin IP)}"
: "${SSHPASS_HK:?set SSHPASS_HK in .env (HK server root password)}"
RELAY_HOST="$RELAY_HOST_HK"
export SSHPASS="$SSHPASS_HK"

# password-only auth + optional jump host — same anti-bruteforce dance as redeploy-relay.sh
SSH=(sshpass -e ssh -o PubkeyAuthentication=no -o PreferredAuthentications=password -o StrictHostKeyChecking=accept-new)
SCP=(sshpass -e scp -o PubkeyAuthentication=no -o PreferredAuthentications=password -o StrictHostKeyChecking=accept-new)
if [ -n "${RELAY_SSH_JUMP:-}" ]; then
  SSH+=(-o "ProxyJump=$RELAY_SSH_JUMP")
  SCP+=(-o "ProxyJump=$RELAY_SSH_JUMP")
fi
SSH+=("root@$RELAY_HOST")

echo "── 1/4 ship mirror script + systemd units ──"
"${SSH[@]}" 'mkdir -p /opt/cc-pocket-mirror'
"${SCP[@]}" deploy/mirror-sync.sh "root@$RELAY_HOST:/opt/cc-pocket-mirror/mirror-sync.sh"
"${SCP[@]}" deploy/cc-pocket-mirror-sync.service deploy/cc-pocket-mirror-sync.timer "root@$RELAY_HOST:/etc/systemd/system/"
"${SSH[@]}" 'chmod +x /opt/cc-pocket-mirror/mirror-sync.sh && systemctl daemon-reload && systemctl enable --now cc-pocket-mirror-sync.timer'

echo "── 2/4 ship Caddyfile (/dl route; back up current first) ──"
"${SSH[@]}" 'cp -a /etc/caddy/Caddyfile "/etc/caddy/Caddyfile.bak.$(date +%s)"'
"${SCP[@]}" deploy/Caddyfile "root@$RELAY_HOST:/etc/caddy/Caddyfile"
"${SSH[@]}" 'caddy validate --config /etc/caddy/Caddyfile && systemctl reload caddy && echo " caddy reloaded"'

echo "── 3/4 first sync (cold run pulls ~400MB from GitHub — started async, polling) ──"
# --no-block + poll instead of one long-held ssh session: the HK box's anti-bruteforce has a
# history of cutting long transfers/sessions mid-flight (see redeploy-relay.sh, 08-04).
"${SSH[@]}" 'systemctl start --no-block cc-pocket-mirror-sync.service'
state=activating
for _ in $(seq 1 30); do
  sleep 20
  state="$("${SSH[@]}" 'systemctl is-active cc-pocket-mirror-sync.service' || true)"
  echo "  sync: $state"
  [ "$state" = "activating" ] || break
done
if [ "$state" != "inactive" ]; then
  "${SSH[@]}" 'journalctl -u cc-pocket-mirror-sync -n 40 --no-pager' || true
  echo "first sync did not finish cleanly (state: $state)"; exit 1
fi
"${SSH[@]}" 'journalctl -u cc-pocket-mirror-sync -n 5 --no-pager' || true

echo "── 4/4 verify the public /dl endpoint ──"
manifest="$(curl -fsS --max-time 15 https://pocket.ark-nexus.cc/dl/latest.json)"
echo "$manifest" | head -c 400; echo
url="$(printf '%s' "$manifest" | jq -r '.assets | to_entries[] | select(.key | test("^cc-pocket-daemon-.*macos-arm64")) | .value' 2>/dev/null \
  || printf '%s' "$manifest" | sed -n 's/.*"\(https:[^"]*macos-arm64[^"]*\)".*/\1/p')"
[ -n "$url" ] || { echo "no macos-arm64 asset in latest.json"; exit 1; }
curl -fsSI --max-time 15 "$url" | awk 'BEGIN{IGNORECASE=1} /^HTTP|^content-length/{print "  " $0}'
echo "✅ mirror provisioned + serving"
