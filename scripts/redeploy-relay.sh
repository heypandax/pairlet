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
JUMP=(); [ -n "${RELAY_SSH_JUMP:-}" ] && JUMP=(-o "ProxyJump=$RELAY_SSH_JUMP")
SSH=(sshpass -e ssh "${JUMP[@]}" -o PubkeyAuthentication=no -o PreferredAuthentications=password -o StrictHostKeyChecking=accept-new "root@$RELAY_HOST")
SCP=(sshpass -e scp "${JUMP[@]}" -o PubkeyAuthentication=no -o PreferredAuthentications=password -o StrictHostKeyChecking=accept-new)

# 先传后切：dist 打成单个 tarball 上传（多文件 scp 的每个文件都是一次翻车机会，且该机防暴破
# 会掐长会话），服务全程在线；最后一步才 stop→untar→start，停机窗口从「整个传输期」缩到秒级。
# 08-04 实战教训：老流程先 stop 再多文件 scp，传输中途被防暴破掐断 = 线上直接停机。
echo "── 1/5 ship relay dist (tarball, service stays up) ──"
TARBALL=$(mktemp /tmp/relay-dist.XXXXXX.tgz)
trap 'rm -f "$TARBALL"' EXIT
tar czf "$TARBALL" -C "$DIST" bin lib
"${SCP[@]}" "$TARBALL" "root@$RELAY_HOST:/tmp/relay-dist.tgz"

echo "── 2/5 swap dist（4/5 重新拉起）──"
"${SSH[@]}" '
  systemctl stop cc-pocket-relay &&
  rm -rf /opt/cc-pocket-relay/bin /opt/cc-pocket-relay/lib &&
  tar xzf /tmp/relay-dist.tgz -C /opt/cc-pocket-relay/ 2>/dev/null &&
  rm -f /tmp/relay-dist.tgz
'

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

echo "── 5/5 public health ──"
curl -fsS --max-time 15 https://pocket.ark-nexus.cc/healthz && echo " public OK"
echo "✅ relay redeploy done"
