#!/usr/bin/env bash
# Redeploy the cc-pocket relay (Kotlin dist + Caddyfile) to the production origin.
# Backward-compatible change set: adds the Ping/Pong heartbeat echo (additive) and disables
# HTTP/3 in Caddy (forces TCP h1/h2). Old daemons/devices keep working.
#
# Secrets are read from the environment (never committed):
#   RELAY_HOST=<origin IP>  SSHPASS='<root password>'  bash scripts/redeploy-relay.sh
#
# Prereqs: `sshpass` installed; relay dist built (./gradlew :relay:installDist).
set -euo pipefail
cd "$(dirname "$0")/.."

# auto-load secrets from .env if present (RELAY_HOST / SSHPASS). .env is gitignored.
[ -f .env ] && { set -a; . ./.env; set +a; }

: "${RELAY_HOST:?set RELAY_HOST in .env (origin IP, behind Cloudflare)}"
: "${SSHPASS:?set SSHPASS in .env (server root password)}"
DIST=relay/build/install/cc-pocket-relay
[ -d "$DIST/lib" ] || { echo "build first: JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :relay:installDist"; exit 1; }

SSH=(sshpass -e ssh -o StrictHostKeyChecking=accept-new "root@$RELAY_HOST")
SCP=(sshpass -e scp -o StrictHostKeyChecking=accept-new)

echo "── 1/5 stop relay + clear old dist ──"
"${SSH[@]}" 'systemctl stop cc-pocket-relay && rm -rf /opt/cc-pocket-relay/bin /opt/cc-pocket-relay/lib'

echo "── 2/5 ship relay dist ──"
"${SCP[@]}" -r "$DIST/bin" "$DIST/lib" "root@$RELAY_HOST:/opt/cc-pocket-relay/"

echo "── 3/5 ship Caddyfile (back up current first) ──"
"${SSH[@]}" 'cp -a /etc/caddy/Caddyfile "/etc/caddy/Caddyfile.bak.$(date +%s)"'
"${SCP[@]}" deploy/Caddyfile "root@$RELAY_HOST:/etc/caddy/Caddyfile"

echo "── 4/5 start relay + health + reload caddy (h3 off) ──"
"${SSH[@]}" '
  chown -R root:root /opt/cc-pocket-relay && chmod +x /opt/cc-pocket-relay/bin/cc-pocket-relay &&
  systemctl start cc-pocket-relay && sleep 4 &&
  curl -fsS http://127.0.0.1:9000/healthz && echo " relay healthz OK" &&
  caddy validate --config /etc/caddy/Caddyfile && systemctl reload caddy && echo " caddy reloaded (HTTP/3 disabled)"
'

echo "── 5/5 public health (through Cloudflare) ──"
curl -fsS --max-time 15 https://pocket.ark-nexus.cc/healthz && echo " public OK"
echo "✅ relay redeploy done"
