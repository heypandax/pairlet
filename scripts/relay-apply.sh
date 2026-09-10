#!/usr/bin/env bash
# Runs as a transient systemd SERVICE, independent of the uploading SSH connection.
# Stage contains verified bin/lib, Caddyfile and dist.sha256. Tests use isolated path arguments.
set -euo pipefail
umask 077
STAGE="${1:?staging directory required}"
ROOT="${2:-/opt/cc-pocket-relay}"
CADDY="${3:-/etc/caddy/Caddyfile}"
SERVICE="${4:-cc-pocket-relay}"
HEALTH="${5:-http://127.0.0.1:9000/healthz}"
[[ "$STAGE" = /* && "$ROOT" = /* && "$ROOT" != / && "$STAGE" != "$ROOT" ]] || exit 64
[[ -d "$ROOT/bin" && -d "$ROOT/lib" && -f "$CADDY" ]] || exit 65
exec 9>"$ROOT/.deploy.lock"
flock -n 9 || { echo 'another deployment is active'; exit 75; }

BACKUP="$ROOT/.deploy-backups/$(date -u +%Y%m%dT%H%M%SZ)-$$"
switched=0
healthy() {
    local attempt
    for attempt in 1 2 3 4 5 6 7 8; do
        if systemctl is-active --quiet "$SERVICE" && curl -fsS --max-time 3 "$HEALTH" >/dev/null; then return 0; fi
        sleep 1
    done
    return 1
}
finish() {
    local status=$? result=failed restored=1
    trap - EXIT HUP INT TERM
    if [[ "$status" = 0 ]]; then
        result=ok
    elif [[ "$switched" = 1 ]]; then
        # Keep an intact backup even if recovery itself fails. Never require another SSH login to recover.
        set +e
        systemctl stop "$SERVICE" || restored=0
        if [[ "$restored" = 1 ]]; then
            rm -rf "$ROOT/bin" "$ROOT/lib" || restored=0
            cp -a "$BACKUP/bin" "$BACKUP/lib" "$ROOT/" || restored=0
            cp -a "$BACKUP/Caddyfile" "$CADDY" || restored=0
        fi
        if [[ "$restored" = 1 ]] && systemctl reset-failed "$SERVICE" &&
            systemctl start "$SERVICE" && healthy && systemctl reload caddy; then
            result=rolled_back
        else
            result=rollback_failed
        fi
    fi
    printf '%s\n' "$result" >"$STAGE/result"
    echo "relay deployment result: $result"
    exit "$status"
}
trap finish EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

# All fallible preparation happens while the old service is running.
(cd "$STAGE" && sha256sum -c dist.sha256)
test -f "$STAGE/bin/cc-pocket-relay"
caddy validate --config "$STAGE/Caddyfile"
mkdir -p "$BACKUP"
cp -a "$ROOT/bin" "$ROOT/lib" "$BACKUP/"
cp -a "$CADDY" "$BACKUP/Caddyfile"
systemctl cat "$SERVICE" >"$BACKUP/service.txt"

switched=1
systemctl stop "$SERVICE"
rm -rf "$ROOT/bin" "$ROOT/lib"
cp -a "$STAGE/bin" "$STAGE/lib" "$ROOT/"
cp -a "$STAGE/Caddyfile" "$CADDY"
chown -R root:root "$ROOT/bin" "$ROOT/lib"
chmod +x "$ROOT/bin/cc-pocket-relay"
systemctl reset-failed "$SERVICE"
systemctl start "$SERVICE"
healthy
systemctl reload caddy
echo "relay active; backup: $BACKUP"
