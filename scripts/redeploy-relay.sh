#!/usr/bin/env bash
# Redeploy the cc-pocket relay (Kotlin dist + Caddyfile) to the production origin.
# Backward-compatible change set: adds the Ping/Pong heartbeat echo (additive) and disables
# HTTP/3 in Caddy (forces TCP h1/h2). Old daemons/devices keep working.
#
# Secrets are read from the environment (never committed):
#   RELAY_HOST_HK=<origin IP>  SSHPASS_HK='<root password>'  bash scripts/redeploy-relay.sh
#
# Prereqs: `sshpass` installed; relay dist built (./gradlew :relay:installDist).
set -euo pipefail
cd "$(dirname "$0")/.."

# Stop before uploading or stopping services if a stale branch would remove website hosts.
python3 scripts/check-production-caddy.py

# auto-load secrets from .env if present (RELAY_HOST_HK / SSHPASS_HK). .env is gitignored.
# Production relay moved to the HK box on 07-08 — the legacy RELAY_HOST/SSHPASS pair points at
# the decommissioned US-East machine, so this script deliberately reads only the *_HK variables.
[ -f .env ] && { set -a; . ./.env; set +a; }

: "${RELAY_HOST_HK:?set RELAY_HOST_HK in .env (HK origin IP)}"
: "${SSHPASS_HK:?set SSHPASS_HK in .env (HK server root password)}"
RELAY_HOST="$RELAY_HOST_HK"
export SSHPASS="$SSHPASS_HK"   # sshpass -e reads SSHPASS
DIST=relay/build/install/cc-pocket-relay
[ -d "$DIST/lib" ] || { echo "build first: JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :relay:installDist"; exit 1; }

# HK box runs SSH anti-bruteforce: an offered-then-rejected pubkey counts as a failed auth and,
# after a couple in quick succession, locks out even the correct password for ~30s. Force
# password-only auth so the multi-step deploy (ssh + scp × several) never trips it.
# RELAY_SSH_JUMP（可选，.env）：ProxyJump 跳板（如 ark-114）。本机 IP 被防暴破封禁时换源 IP 用。
# Bash 3.2 treats expanding an empty array under `set -u` as an unset-variable error. Build the
# command arrays incrementally so a missing optional jump host remains valid on the macOS shell.
SSH=(sshpass -e ssh -o PubkeyAuthentication=no -o PreferredAuthentications=password -o StrictHostKeyChecking=accept-new)
SCP=(sshpass -e scp -o PubkeyAuthentication=no -o PreferredAuthentications=password -o StrictHostKeyChecking=accept-new)
if [ -n "${RELAY_SSH_JUMP:-}" ]; then
  SSH+=(-o "ProxyJump=$RELAY_SSH_JUMP")
  SCP+=(-o "ProxyJump=$RELAY_SSH_JUMP")
fi
SSH+=("root@$RELAY_HOST")

# Keep upload/validation before stop. The swap + health + rollback live in ONE server-owned job:
# a dropped SSH connection must never strand a stopped relay (observability A deployment incident).
LOCAL_STAGE=$(mktemp -d /tmp/relay-deploy.XXXXXX)
DEPLOY_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$"
REMOTE_STAGE="/tmp/cc-pocket-relay-deploy-$DEPLOY_ID"
UNIT="cc-pocket-relay-deploy-$DEPLOY_ID"
trap 'rm -rf "$LOCAL_STAGE"' EXIT
cp -R "$DIST/bin" "$DIST/lib" "$LOCAL_STAGE/"
cp deploy/Caddyfile scripts/relay-apply.sh "$LOCAL_STAGE/"
python3 - "$LOCAL_STAGE" <<'PY'
from pathlib import Path
import hashlib, sys
root = Path(sys.argv[1])
files = sorted(p for p in root.rglob('*') if p.is_file())
with (root / 'dist.sha256').open('w') as out:
    for p in files:
        out.write(hashlib.sha256(p.read_bytes()).hexdigest() + '  ' + str(p.relative_to(root)) + '\n')
PY
tar czf "$LOCAL_STAGE/dist.tgz" -C "$LOCAL_STAGE" bin lib Caddyfile relay-apply.sh dist.sha256
echo "── 1/4 upload to $REMOTE_STAGE (old service remains up) ──"
"${SSH[@]}" "umask 077; mkdir '$REMOTE_STAGE'"
"${SCP[@]}" "$LOCAL_STAGE/dist.tgz" "root@$RELAY_HOST:$REMOTE_STAGE/dist.tgz"
echo "── 2/4 validate and swap in server-owned job $UNIT ──"
if ! "${SSH[@]}" "set -eu; tar xzf '$REMOTE_STAGE/dist.tgz' -C '$REMOTE_STAGE'; cd '$REMOTE_STAGE'; sha256sum -c dist.sha256; systemd-run --unit='$UNIT' --collect --wait --property=Type=oneshot --property=TimeoutStartSec=180 /bin/bash '$REMOTE_STAGE/relay-apply.sh' '$REMOTE_STAGE' >'$REMOTE_STAGE/job.log' 2>&1; cat '$REMOTE_STAGE/result'"; then
  echo "Deployment command failed or its result is unknown; the server job owns rollback."
  echo "Read $REMOTE_STAGE/result and $REMOTE_STAGE/job.log (unit $UNIT) before retrying."
  # A fresh connection is diagnostic only: do not repeat the swap when an earlier result is unknown.
  "${SSH[@]}" "cat '$REMOTE_STAGE/result' 2>/dev/null || true; systemctl is-active cc-pocket-relay" || true
  exit 1
fi
echo "── 3/4 service identity and health ──"
"${SSH[@]}" "test \"\$(cat '$REMOTE_STAGE/result')\" = ok; systemctl show cc-pocket-relay --property=MainPID --property=ActiveState --property=ActiveEnterTimestamp; curl -fsS --max-time 10 http://127.0.0.1:9000/healthz"
echo "── 4/4 public health ──"
curl -fsS --max-time 15 https://pocket.ark-nexus.cc/healthz && echo " public OK"
echo "✅ relay redeploy done"
