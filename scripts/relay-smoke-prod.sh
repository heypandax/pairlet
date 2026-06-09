#!/usr/bin/env bash
# End-to-end smoke against the DEPLOYED relay (through Cloudflare + Caddy + wss).
# Proves the full production path: local daemon + device, E2E encrypted, no local relay.
#
# Usage:  JAVA_HOME=/opt/homebrew/opt/openjdk@17 bash scripts/relay-smoke-prod.sh [wss://host]
set -euo pipefail
cd "$(dirname "$0")/.."

RELAY="${1:-wss://pocket.ark-nexus.cc}"
D=daemon/build/install/cc-pocket-daemon/bin/cc-pocket-daemon
[ -x "$D" ] || { echo "build first: ./gradlew :daemon:installDist"; exit 1; }

WORK=$(mktemp -d)
ID_FILE="$WORK/identity.json"   # throwaway identity so we don't collide with anyone
PAIRPORT=8897
cleanup() { kill "${DAEMON_PID:-}" 2>/dev/null || true; rm -rf "$WORK"; }
trap cleanup EXIT

echo "── public health ──"; curl -fsS --max-time 15 "${RELAY/wss:/https:}/healthz" && echo " ok"

echo "── local daemon -> $RELAY ──"
CC_POCKET_IDENTITY="$ID_FILE" \
  "$D" run --relay "$RELAY" --claude-bin /bin/echo --pair-port "$PAIRPORT" >"$WORK/daemon.log" 2>&1 &
DAEMON_PID=$!
for _ in $(seq 1 60); do grep -q "attached to relay" "$WORK/daemon.log" 2>/dev/null && break; sleep 0.5; done
grep -q "attached to relay" "$WORK/daemon.log" || { echo "FAIL: daemon not attached"; cat "$WORK/daemon.log"; exit 1; }
echo "  $(grep 'account id:' "$WORK/daemon.log")  attached ✓"

echo "── mint ticket + device E2E 'dirs' through the cloud ──"
PAIR=$(curl -s -X POST "http://127.0.0.1:$PAIRPORT/pair")
TICKET=$(echo "$PAIR" | sed -E 's/.*"ticket":"([^"]+)".*/\1/')
DPUB=$(echo "$PAIR" | sed -E 's/.*"daemonPub":"([^"]+)".*/\1/')
[ -n "$TICKET" ] && [ "$TICKET" != "$PAIR" ] || { echo "FAIL: mint: $PAIR"; exit 1; }

( printf 'dirs\n'; sleep 6; printf 'quit\n' ) \
  | CC_POCKET_IDENTITY="$WORK/dev.json" "$D" test-client --relay "$RELAY" --daemon-pub "$DPUB" --ticket "$TICKET" >"$WORK/tc.log" 2>&1 || true

grep -q "E2E channel up" "$WORK/tc.log" || { echo "FAIL: E2E handshake"; cat "$WORK/tc.log"; echo "--- daemon ---"; tail "$WORK/daemon.log"; exit 1; }
grep -q "\[dirs\]"      "$WORK/tc.log" || { echo "FAIL: no encrypted round trip"; cat "$WORK/tc.log"; exit 1; }
echo "  E2E handshake ✓   encrypted round trip ✓"
echo
echo "PASS — full production path (daemon -> Cloudflare -> Caddy -> relay -> device) works, end-to-end encrypted."
