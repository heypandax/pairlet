#!/usr/bin/env bash
# Install the feishu bridge as a launchd agent, so it survives logout/reboot and restarts on crash —
# the same job the cc-pocket daemon already does for itself. Without this you're back to `nohup python
# … &`, which is the part of the setup nobody remembers to redo after a reboot.
#
# macOS only. On Linux write the equivalent systemd --user unit by hand (ExecStart = the PYTHON/SCRIPT
# below, WorkingDirectory = this dir, Environment = the same four vars, Restart=always).
#
#   ./install-service.sh              install + start
#   ./install-service.sh --uninstall  stop + remove
#
# Reads FEISHU_APP_ID / FEISHU_APP_SECRET / FEISHU_ADMIN_OPEN_ID from the environment and BAKES them
# into the plist (chmod 0600 — it holds your app secret).
set -euo pipefail

# NOT "dev.ccpocket.daemon*" — the daemon's own ServiceInstaller unloads and DELETES every plist
# matching that glob to enforce its single-instance rule, and would take this agent with it on every
# `service-install`. See ServiceInstaller.removeSiblingAgents.
LABEL="dev.ccpocket.feishu-bridge"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
LOG_DIR="$HOME/Library/Logs/cc-pocket"

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "✗ macOS only — see the header comment for the systemd equivalent." >&2
  exit 1
fi

if [[ "${1:-}" == "--uninstall" ]]; then
  launchctl unload "$PLIST" 2>/dev/null || true
  rm -f "$PLIST"
  echo "✓ uninstalled $LABEL"
  exit 0
fi

PYTHON="${PYTHON:-$(command -v python3 || true)}"
[[ -n "$PYTHON" ]] || { echo "✗ no python3 on PATH — set PYTHON=/path/to/python3" >&2; exit 1; }
# A venv/pyenv python is a moving target: if it's a shim or gets rebuilt, launchd's baked path breaks
# at next boot with no visible error. Resolve to the real interpreter now and fail loudly if it's gone.
PYTHON="$($PYTHON -c 'import sys; print(sys.executable)')"

: "${FEISHU_APP_ID:?set FEISHU_APP_ID}"
: "${FEISHU_APP_SECRET:?set FEISHU_APP_SECRET}"
ADMIN="${FEISHU_ADMIN_OPEN_ID:-}"

"$PYTHON" -c 'import lark_oapi' 2>/dev/null || {
  echo "✗ $PYTHON cannot import lark_oapi — run: $PYTHON -m pip install -r $DIR/requirements.txt" >&2
  exit 1
}
[[ -f "$DIR/bridge-credential.json" || -f "$DIR/.pocket-device.json" ]] || {
  echo "✗ no credential in $DIR — mint one first:" >&2
  echo "    cc-pocket-daemon pair --headless --name feishu-bot --workdir /abs/proj --out $DIR/bridge-credential.json" >&2
  exit 1
}

mkdir -p "$LOG_DIR" "$(dirname "$PLIST")"
cat > "$PLIST" <<PLIST_EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
    <key>Label</key><string>$LABEL</string>
    <key>ProgramArguments</key><array>
        <string>$PYTHON</string>
        <string>$DIR/feishu_bridge.py</string>
    </array>
    <key>WorkingDirectory</key><string>$DIR</string>
    <key>EnvironmentVariables</key><dict>
        <key>HOME</key><string>$HOME</string>
        <key>FEISHU_APP_ID</key><string>$FEISHU_APP_ID</string>
        <key>FEISHU_APP_SECRET</key><string>$FEISHU_APP_SECRET</string>
        <key>FEISHU_ADMIN_OPEN_ID</key><string>$ADMIN</string>
    </dict>
    <key>RunAtLoad</key><true/>
    <key>KeepAlive</key><true/>
    <key>ThrottleInterval</key><integer>10</integer>
    <key>StandardOutPath</key><string>$LOG_DIR/feishu-bridge.out.log</string>
    <key>StandardErrorPath</key><string>$LOG_DIR/feishu-bridge.err.log</string>
</dict></plist>
PLIST_EOF
chmod 600 "$PLIST"   # holds FEISHU_APP_SECRET

launchctl unload "$PLIST" 2>/dev/null || true
launchctl load "$PLIST"
echo "✓ installed + loaded $LABEL"
echo "  logs:  tail -f $LOG_DIR/feishu-bridge.err.log"
[[ -n "$ADMIN" ]] || echo "  note: FEISHU_ADMIN_OPEN_ID unset — /bind stays disabled. Send the bot /bind in a chat; it replies with your open_id, then re-run this script with it set."
