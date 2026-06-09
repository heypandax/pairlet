#!/usr/bin/env bash
# End-to-end smoke test for the hardened relay path (Phase B1).
#
# Proves, with no real `claude` needed (uses /bin/echo; `dirs` never spawns claude):
#   1. daemon authenticates to the relay by Ed25519 signed challenge
#   2. an authenticated daemon mints a single-use pairing ticket via loopback
#   3. a device redeems the ticket, registering its key + receiving a credential
#   4. the device logs in with that credential and a request round-trips:
#        device → relay → daemon → relay → device   (opaque binary data plane)
#
# Usage:  JAVA_HOME=/opt/homebrew/opt/openjdk@17 bash scripts/relay-smoke.sh
set -euo pipefail
cd "$(dirname "$0")/.."

R=relay/build/install/cc-pocket-relay/bin/cc-pocket-relay
D=daemon/build/install/cc-pocket-daemon/bin/cc-pocket-daemon
[ -x "$R" ] || { echo "build first: ./gradlew :relay:installDist :daemon:installDist"; exit 1; }
[ -x "$D" ] || { echo "build first: ./gradlew :relay:installDist :daemon:installDist"; exit 1; }

WORK=$(mktemp -d)
ID_FILE="$WORK/identity.json"   # isolated identity so the test never touches ~/.cc-pocket
PORT=9099
PAIRPORT=8899
cleanup() { kill "${RELAY_PID:-}" "${DAEMON_PID:-}" 2>/dev/null || true; rm -rf "$WORK"; }
trap cleanup EXIT

echo "── relay (in-memory, :$PORT) ──"
"$R" --in-memory --port "$PORT" >"$WORK/relay.log" 2>&1 &
RELAY_PID=$!

echo "── daemon (run --relay) ──"
CC_POCKET_IDENTITY="$ID_FILE" \
  "$D" run --relay "ws://127.0.0.1:$PORT" --claude-bin /bin/echo --pair-port "$PAIRPORT" >"$WORK/daemon.log" 2>&1 &
DAEMON_PID=$!

for _ in $(seq 1 40); do curl -sf "http://127.0.0.1:$PORT/healthz" >/dev/null 2>&1 && break; sleep 0.5; done
for _ in $(seq 1 40); do grep -q "attached to relay" "$WORK/daemon.log" 2>/dev/null && break; sleep 0.5; done
grep -q "attached to relay" "$WORK/daemon.log" || { echo "FAIL: daemon not attached"; cat "$WORK/daemon.log"; exit 1; }
echo "  $(grep 'account id:' "$WORK/daemon.log")"
echo "  daemon attached ✓"

echo "── mint pairing ticket (daemon, via loopback) ──"
PAIR=$(curl -s -X POST "http://127.0.0.1:$PAIRPORT/pair")
TICKET=$(echo "$PAIR" | sed -E 's/.*"ticket":"([^"]+)".*/\1/')
DPUB=$(echo "$PAIR" | sed -E 's/.*"daemonPub":"([^"]+)".*/\1/')
[ -n "$TICKET" ] && [ "$TICKET" != "$PAIR" ] || { echo "FAIL: mint: $PAIR"; exit 1; }
echo "  ticket minted ✓ (daemon E2E pub ${#DPUB} chars)"

echo "── device: redeem + E2E handshake + 'dirs' over the relay ──"
# the test-client generates its key, redeems, runs the Noise handshake, then talks encrypted
( printf 'dirs\n'; sleep 5; printf 'quit\n' ) \
  | "$D" test-client --relay "ws://127.0.0.1:$PORT" --daemon-pub "$DPUB" --ticket "$TICKET" >"$WORK/tc.log" 2>&1 || true

grep -q "\[relay\] attached" "$WORK/tc.log" || { echo "FAIL: device auth"; cat "$WORK/tc.log"; exit 1; }
grep -q "E2E channel up"     "$WORK/tc.log" || { echo "FAIL: E2E handshake"; cat "$WORK/tc.log"; exit 1; }
grep -q "\[dirs\]"          "$WORK/tc.log" || { echo "FAIL: no encrypted round trip"; cat "$WORK/tc.log"; exit 1; }
echo "  device auth ✓   E2E handshake ✓   encrypted round trip ✓"
echo
echo "PASS — daemon auth, pairing, device auth, and END-TO-END ENCRYPTED data plane all work."
