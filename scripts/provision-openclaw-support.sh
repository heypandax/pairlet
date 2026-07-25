#!/usr/bin/env bash
set -euo pipefail

# Provision two isolated OpenClaw agents:
#   cc-pocket-support          public question answering
#   cc-pocket-support-review  unbound weekly knowledge reviewer
#
# This script intentionally does not configure or bind a public channel. Channel
# credentials and routing are a separate operator decision.

usage() {
  printf '%s\n' \
    "Usage: $0 [--apply] [--repo PATH]" \
    "" \
    "Environment:" \
    "  CC_POCKET_SUPPORT_MODEL  public answer model (default: deepseek/deepseek-v4-pro)" \
    "  CC_POCKET_REVIEW_MODEL   stronger review model; omit to skip reviewer + cron" \
    "  CC_POCKET_REVIEW_CRON    cron expression (default: 0 3 * * 1)" \
    "  CC_POCKET_REVIEW_TZ      timezone (default: UTC)" \
    "  CC_POCKET_REVIEW_THINKING  provider-supported level (default: off)"
}

APPLY=false
REPO_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
while (($#)); do
  case "$1" in
    --apply)
      APPLY=true
      shift
      ;;
    --repo)
      REPO_PATH="${2:?--repo requires a path}"
      shift 2
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

REPO_PATH="$(cd "$REPO_PATH" && pwd)"
SUPPORT_MODEL="${CC_POCKET_SUPPORT_MODEL:-deepseek/deepseek-v4-pro}"
REVIEW_MODEL="${CC_POCKET_REVIEW_MODEL:-}"
REVIEW_CRON="${CC_POCKET_REVIEW_CRON:-0 3 * * 1}"
REVIEW_TZ="${CC_POCKET_REVIEW_TZ:-UTC}"
REVIEW_THINKING="${CC_POCKET_REVIEW_THINKING:-off}"
STATE_ROOT="${OPENCLAW_STATE_DIR:-$HOME/.openclaw}"
SUPPORT_WORKSPACE="$STATE_ROOT/workspace-cc-pocket-support"
REVIEW_WORKSPACE="$STATE_ROOT/workspace-cc-pocket-support-review"
CANDIDATE_QUEUE_PATH="$STATE_ROOT/cc-pocket-support-kb-inbox"
GOVERNANCE_PATH="$STATE_ROOT/cc-pocket-support-kb-governance"
SOURCE_ROOT="$STATE_ROOT/cc-pocket-support-source"
SUPPORT_TEMPLATE="$REPO_PATH/support/openclaw/support-workspace"
REVIEW_TEMPLATE="$REPO_PATH/support/openclaw/reviewer-workspace"
SUPPORT_GUARD_PLUGIN="$REPO_PATH/support/openclaw/plugins/cc-pocket-support-guard"
command -v git >/dev/null || {
  printf '%s\n' "Missing required command: git" >&2
  exit 2
}
SOURCE_COMMIT="$(git -C "$REPO_PATH" rev-parse HEAD)"
SOURCE_SNAPSHOT="$SOURCE_ROOT/$SOURCE_COMMIT"

test -f "$SUPPORT_TEMPLATE/AGENTS.md"
test -f "$REVIEW_TEMPLATE/AGENTS.md"
test -f "$SUPPORT_GUARD_PLUGIN/openclaw.plugin.json"
test -f "$REPO_PATH/scripts/support-kb.py"

if [[ "$APPLY" != true ]]; then
  printf '%s\n' \
    "Dry run only; no OpenClaw state was changed." \
    "Repository: $REPO_PATH" \
    "Support workspace: $SUPPORT_WORKSPACE" \
    "Tracked-file snapshot: $SOURCE_SNAPSHOT" \
    "Candidate inbox: $CANDIDATE_QUEUE_PATH" \
    "Review governance: $GOVERNANCE_PATH" \
    "Support model: $SUPPORT_MODEL"
  if [[ -n "$REVIEW_MODEL" ]]; then
    printf 'Reviewer: %s on %s (%s), thinking=%s\n' \
      "$REVIEW_MODEL" "$REVIEW_CRON" "$REVIEW_TZ" "$REVIEW_THINKING"
  else
    printf '%s\n' "Reviewer: skipped until CC_POCKET_REVIEW_MODEL is configured"
  fi
  printf '%s\n' "Run again with --apply after reviewing these paths."
  exit 0
fi

for command in openclaw docker git python3 tar; do
  command -v "$command" >/dev/null || {
    printf 'Missing required command: %s\n' "$command" >&2
    exit 2
  }
done

docker image inspect openclaw-sandbox:bookworm-slim >/dev/null 2>&1 || {
  printf '%s\n' \
    "Missing Docker image openclaw-sandbox:bookworm-slim." \
    "Build the official OpenClaw sandbox image before provisioning." >&2
  exit 2
}

agent_exists() {
  local agent_id="$1"
  openclaw agents list --json | python3 -c '
import json, sys
target = sys.argv[1]
data = json.load(sys.stdin)
if isinstance(data, dict):
    data = data.get("agents", data.get("value", []))
print("yes" if any(isinstance(item, dict) and item.get("id") == target for item in data) else "no")
' "$agent_id"
}

agent_index() {
  local agent_id="$1"
  openclaw config get agents.list --json | python3 -c '
import json, sys
target = sys.argv[1]
data = json.load(sys.stdin)
if isinstance(data, dict):
    data = data.get("value", data.get("agents", data))
if not isinstance(data, list):
    raise SystemExit("agents.list is not an array")
for index, item in enumerate(data):
    if isinstance(item, dict) and item.get("id") == target:
        print(index)
        raise SystemExit(0)
raise SystemExit(f"agent not found in config: {target}")
' "$agent_id"
}

copy_workspace() {
  local source="$1"
  local destination="$2"
  install -d -m 700 "$destination"
  cp -R "$source"/. "$destination"/
}

create_tracked_snapshot() {
  if [[ -f "$SOURCE_SNAPSHOT/.support-commit" ]]; then
    return
  fi
  install -d -m 700 "$SOURCE_ROOT"
  local staging
  staging="$(mktemp -d "$SOURCE_ROOT/.staging.XXXXXX")"
  git -C "$REPO_PATH" archive --format=tar "$SOURCE_COMMIT" | tar -xf - -C "$staging"
  printf '%s\n' "$SOURCE_COMMIT" > "$staging/.support-commit"
  chmod -R u=rwX,go= "$staging"
  mv "$staging" "$SOURCE_SNAPSHOT"
}

add_agent_if_missing() {
  local agent_id="$1"
  local workspace="$2"
  local model="$3"
  if [[ "$(agent_exists "$agent_id")" == "no" ]]; then
    openclaw agents add "$agent_id" \
      --workspace "$workspace" \
      --model "$model" \
      --non-interactive \
      --json
  fi
}

configure_agent() {
  local agent_id="$1"
  local model="$2"
  local skill="$3"
  local role="$4"
  local index
  index="$(agent_index "$agent_id")"
  local base="agents.list[$index]"
  local sandbox
  sandbox="$(
    python3 -c '
import json, sys
repo, candidates, governance, role = sys.argv[1:5]
if role == "support":
    binds = [f"{repo}:/repo:ro", f"{candidates}:/queue:rw", f"{governance}:/governance:ro"]
elif role == "reviewer":
    binds = [f"{repo}:/repo:ro", f"{candidates}:/queue:ro", f"{governance}:/governance:rw"]
else:
    raise SystemExit(f"unknown agent role: {role}")
print(json.dumps({
    "mode": "all",
    "backend": "docker",
    "scope": "agent",
    "workspaceAccess": "none",
    "docker": {
        "network": "none",
        "binds": binds,
        "dangerouslyAllowExternalBindSources": True,
    },
}))
' "$SOURCE_SNAPSHOT" "$CANDIDATE_QUEUE_PATH" "$GOVERNANCE_PATH" "$role"
  )"
  local tools
  tools='{
    "allow": ["read", "exec", "session_status"],
    "deny": [
      "write", "edit", "apply_patch", "process", "browser", "canvas",
      "message", "cron", "gateway", "nodes", "computer", "skill_workshop",
      "sessions_spawn", "agents_list"
    ],
    "sandbox": {
      "tools": {
        "allow": ["read", "exec", "session_status"],
        "deny": [
          "write", "edit", "apply_patch", "process", "browser", "canvas",
          "message", "cron", "gateway", "nodes", "computer", "skill_workshop",
          "sessions_spawn", "agents_list"
        ]
      }
    },
    "exec": {"host": "sandbox", "security": "full", "ask": "off"},
    "elevated": {"enabled": false}
  }'
  local batch
  batch="$(
    python3 -c '
import json, sys
base, model, skill, tools, sandbox = sys.argv[1:6]
print(json.dumps([
    {"path": f"{base}.model", "value": model},
    {"path": f"{base}.skills", "value": [skill]},
    {"path": f"{base}.tools", "value": json.loads(tools)},
    {"path": f"{base}.sandbox", "value": json.loads(sandbox)},
]))
' "$base" "$model" "$skill" "$tools" "$sandbox"
  )"
  openclaw config set --batch-json "$batch"
}

retry_openclaw() {
  local attempt
  for attempt in 1 2 3 4 5 6 7 8 9 10; do
    if "$@"; then
      return 0
    fi
    sleep 2
  done
  return 1
}

openclaw plugins install --force "$SUPPORT_GUARD_PLUGIN"
retry_openclaw openclaw config set \
  plugins.entries.cc-pocket-support-guard.hooks.allowConversationAccess \
  true --strict-json

create_tracked_snapshot
install -d -m 700 \
  "$CANDIDATE_QUEUE_PATH/candidates" \
  "$GOVERNANCE_PATH/review-input" \
  "$GOVERNANCE_PATH/reviews" \
  "$GOVERNANCE_PATH/promotions"
copy_workspace "$SUPPORT_TEMPLATE" "$SUPPORT_WORKSPACE"
add_agent_if_missing "cc-pocket-support" "$SUPPORT_WORKSPACE" "$SUPPORT_MODEL"
configure_agent "cc-pocket-support" "$SUPPORT_MODEL" "cc-pocket-support" "support"
openclaw agents set-identity --workspace "$SUPPORT_WORKSPACE" --from-identity --json

if [[ -n "$REVIEW_MODEL" ]]; then
  openclaw models list --json | python3 -c '
import json, sys
needle = sys.argv[1]
data = json.dumps(json.load(sys.stdin), ensure_ascii=False)
raise SystemExit(0 if needle in data else f"review model is not configured: {needle}")
' "$REVIEW_MODEL"
  copy_workspace "$REVIEW_TEMPLATE" "$REVIEW_WORKSPACE"
  add_agent_if_missing "cc-pocket-support-review" "$REVIEW_WORKSPACE" "$REVIEW_MODEL"
  configure_agent "cc-pocket-support-review" "$REVIEW_MODEL" "cc-pocket-support-review" "reviewer"
  openclaw agents set-identity --workspace "$REVIEW_WORKSPACE" --from-identity --json

  existing_review_job="$(
    retry_openclaw openclaw cron list --all --json | python3 -c '
import json, sys
data = json.load(sys.stdin)
if isinstance(data, dict):
    data = data.get("jobs", data.get("value", []))
for item in data:
    if isinstance(item, dict) and item.get("name") == "CC Pocket support knowledge review":
        print(item.get("id", ""))
        break
'
  )"
  review_message="Audit and review CC Pocket support knowledge. Follow AGENTS.md. Respond in English; keep URLs, code, and product names unchanged."
  if [[ -n "$existing_review_job" ]]; then
    retry_openclaw openclaw cron edit "$existing_review_job" \
      --name "CC Pocket support knowledge review" \
      --cron "$REVIEW_CRON" \
      --tz "$REVIEW_TZ" \
      --session isolated \
      --agent cc-pocket-support-review \
      --model "$REVIEW_MODEL" \
      --thinking "$REVIEW_THINKING" \
      --tools exec,read \
      --message "$review_message" \
      --no-deliver \
      --enable
  else
    openclaw cron create "$REVIEW_CRON" \
      "$review_message" \
      --name "CC Pocket support knowledge review" \
      --tz "$REVIEW_TZ" \
      --session isolated \
      --agent cc-pocket-support-review \
      --model "$REVIEW_MODEL" \
      --thinking "$REVIEW_THINKING" \
      --tools exec,read \
      --no-deliver
  fi
fi

retry_openclaw openclaw config validate
retry_openclaw openclaw agents list --bindings
retry_openclaw openclaw sandbox explain --agent cc-pocket-support
retry_openclaw openclaw sandbox recreate --agent cc-pocket-support --force
if [[ -n "$REVIEW_MODEL" ]]; then
  retry_openclaw openclaw sandbox explain --agent cc-pocket-support-review
  retry_openclaw openclaw sandbox recreate --agent cc-pocket-support-review --force
fi

printf '%s\n' \
  "OpenClaw support agents are configured." \
  "No public channel was bound. Bind only the chosen bot account after its allowlist and privacy policy are ready."
