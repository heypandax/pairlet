#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  scripts/activate-openclaw-support-channel.sh \
    --channel CHANNEL \
    --account ACCOUNT_ID \
    [--privacy-url URL] \
    [--apply --allowlist-confirmed --rate-limit-confirmed]

Without --apply this is a read-only preflight. The script never accepts or
prints channel credentials; configure the dedicated bot account in OpenClaw
first.
EOF
}

CHANNEL=""
ACCOUNT_ID=""
PRIVACY_URL="https://heypandax.github.io/cc-pocket/privacy.html"
APPLY=false
ALLOWLIST_CONFIRMED=false
RATE_LIMIT_CONFIRMED=false

while (($#)); do
  case "$1" in
    --channel)
      CHANNEL="${2:?--channel requires a value}"
      shift 2
      ;;
    --account)
      ACCOUNT_ID="${2:?--account requires a value}"
      shift 2
      ;;
    --privacy-url)
      PRIVACY_URL="${2:?--privacy-url requires a value}"
      shift 2
      ;;
    --apply)
      APPLY=true
      shift
      ;;
    --allowlist-confirmed)
      ALLOWLIST_CONFIRMED=true
      shift
      ;;
    --rate-limit-confirmed)
      RATE_LIMIT_CONFIRMED=true
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      printf 'Unknown argument: %s\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

[[ "$CHANNEL" =~ ^[a-z0-9][a-z0-9-]*$ ]] || {
  printf '%s\n' "A safe --channel is required." >&2
  exit 2
}
[[ "$ACCOUNT_ID" =~ ^[A-Za-z0-9][A-Za-z0-9._@-]*$ ]] || {
  printf '%s\n' "A safe explicit --account is required." >&2
  exit 2
}
[[ "$PRIVACY_URL" =~ ^https:// ]] || {
  printf '%s\n' "--privacy-url must use HTTPS." >&2
  exit 2
}

for command in openclaw python3 curl; do
  command -v "$command" >/dev/null || {
    printf 'Missing required command: %s\n' "$command" >&2
    exit 2
  }
done

privacy_status="$(curl --location --silent --show-error --output /dev/null --write-out '%{http_code}' "$PRIVACY_URL")"
[[ "$privacy_status" == "200" ]] || {
  printf 'Privacy notice is not reachable (HTTP %s): %s\n' "$privacy_status" "$PRIVACY_URL" >&2
  exit 2
}

status_json="$(openclaw channels status --probe --json)"
CC_POCKET_CHANNEL_STATUS_JSON="$status_json" python3 - "$CHANNEL" "$ACCOUNT_ID" <<'PY'
import json
import os
import sys

channel, account_id = sys.argv[1:3]
data = json.loads(os.environ["CC_POCKET_CHANNEL_STATUS_JSON"])
installed = data.get("channelOrder", [])
if channel not in installed:
    raise SystemExit(f"channel is not installed: {channel}")

channel_state = data.get("channels", {}).get(channel, {})
accounts = data.get("channelAccounts", {}).get(channel, [])
account = next(
    (item for item in accounts if isinstance(item, dict) and item.get("accountId") == account_id),
    None,
)

configured = bool(channel_state.get("configured"))
if account is not None:
    configured = configured or bool(account.get("configured"))
elif accounts:
    available = ", ".join(str(item.get("accountId")) for item in accounts if isinstance(item, dict))
    raise SystemExit(f"account {account_id!r} not found; configured accounts: {available or '(none)'}")

if not configured:
    raise SystemExit(
        f"{channel}:{account_id} is not configured; add credentials in OpenClaw Settings → Channels first"
    )

errors = [
    str(value)
    for value in (
        channel_state.get("lastError"),
        (account or {}).get("lastError"),
    )
    if value not in (None, "", "disabled", "not configured")
]
if errors:
    raise SystemExit(f"channel probe reported an error: {'; '.join(errors)}")

print(f"Channel preflight passed: {channel}:{account_id}")
PY

agents_json="$(openclaw agents list --bindings --json)"
CC_POCKET_AGENTS_JSON="$agents_json" python3 - "$CHANNEL" "$ACCOUNT_ID" <<'PY'
import json
import os
import sys

binding = f"{sys.argv[1]}:{sys.argv[2]}"
agents = json.loads(os.environ["CC_POCKET_AGENTS_JSON"])
by_id = {item.get("id"): item for item in agents if isinstance(item, dict)}
if "cc-pocket-support" not in by_id:
    raise SystemExit("cc-pocket-support agent is missing")
if "cc-pocket-support-review" not in by_id:
    raise SystemExit("cc-pocket-support-review agent is missing")
if int(by_id["cc-pocket-support-review"].get("bindings", 0)) != 0:
    raise SystemExit("reviewer must remain unbound")
for agent_id, item in by_id.items():
    if agent_id == "cc-pocket-support":
        continue
    if any(binding in str(route) for route in item.get("routes", [])):
        raise SystemExit(f"binding already routes to another agent: {agent_id}")
PY

printf '%s\n' \
  "Target agent: cc-pocket-support" \
  "Exact binding: $CHANNEL:$ACCOUNT_ID" \
  "Privacy notice: $PRIVACY_URL"

if [[ "$APPLY" != true ]]; then
  printf '%s\n' \
    "Dry run only; no routing changed." \
    "Re-run with --apply --allowlist-confirmed --rate-limit-confirmed after reviewing the bot policy."
  exit 0
fi

[[ "$ALLOWLIST_CONFIRMED" == true ]] || {
  printf '%s\n' "--apply requires --allowlist-confirmed." >&2
  exit 2
}
[[ "$RATE_LIMIT_CONFIRMED" == true ]] || {
  printf '%s\n' "--apply requires --rate-limit-confirmed." >&2
  exit 2
}

openclaw agents bind \
  --agent cc-pocket-support \
  --bind "$CHANNEL:$ACCOUNT_ID" \
  --json

for attempt in 1 2 3 4 5; do
  if openclaw agents list --bindings --json | python3 -c '
import json, sys
agents = json.load(sys.stdin)
support = next((item for item in agents if item.get("id") == "cc-pocket-support"), {})
reviewer = next((item for item in agents if item.get("id") == "cc-pocket-support-review"), {})
raise SystemExit(0 if int(support.get("bindings", 0)) >= 1 and int(reviewer.get("bindings", 0)) == 0 else 1)
'; then
    printf 'Support channel is active: %s:%s\n' "$CHANNEL" "$ACCOUNT_ID"
    exit 0
  fi
  sleep 2
done

printf '%s\n' "Binding command returned, but routing verification failed." >&2
exit 1
