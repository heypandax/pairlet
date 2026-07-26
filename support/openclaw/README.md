# OpenClaw deployment

This package creates two dedicated agents rather than reusing the personal
default agent:

- `cc-pocket-support`: routine public answers, manual retrieval, and read-only
  code inspection. Anonymous traffic cannot capture knowledge candidates.
- `cc-pocket-support-review`: unbound scheduled routine reviewer. It can reject
  or provisionally verify a candidate, but cannot authorize manual promotion.

Both agents run every tool turn inside per-session Docker sandboxes with no
network, a read-only root, dropped capabilities, and CPU, memory, PID, file
descriptor, and process limits. Provisioning
first creates a `git archive` snapshot containing tracked files only, so ignored
files such as `.env` can never enter the sandbox. That snapshot is mounted
read-only at `/repo`. The public agent has no writable host bind and cannot see
the candidate or governance stores; the reviewer sees `/queue` read-only and
can write only `/governance`. Review verdicts are bound to the complete
candidate SHA-256. File mutation outside those mounts,
browser, messaging, gateway, cron, elevated, node, and agent-spawn tools are
denied.

The `cc-pocket-support-guard` plugin applies only to the public support agent.
Its pre-finalize hook asks the model to revise any answer that exposes internal
retrieval/policy narration or adds prose around the fixed escalation response.
Its outbound hook drops tool diagnostic payloads before a configured public
channel can deliver them.

## Public web support

The public entry is `https://pocket.ark-nexus.cc/support/`. It does not use a
messaging app and does not require an account or organization membership.
`support/web/server.py` is the narrow HTTP boundary. A Cloudflare Turnstile
check issues a registered, IP/session-bound, short-lived anonymous pass; no
account is required. Before an Agent starts, one SQLite transaction reserves
pass, visitor, global-minute, global-day, and daily token-budget quota. Limits
survive restarts and database errors fail closed. The boundary also caps open
connections, concurrent Turnstile checks, concurrent Agent runs, request size,
socket time, and model output. It returns only the final visible answer and
public source URLs plus the browser's short-lived anonymous pass; the OpenClaw
Gateway, gateway token, tool output, quota state, and agent metadata are never
exposed.

The API listens on loopback on the OpenClaw host. The relay host reaches that
single port through the restricted SSH forward in
`deploy/cc-pocket-support-tunnel.service`, and Caddy publishes only
`/support-api/*`. Keep the administrative Gateway on its existing private
boundary.

The API service requires `CC_SUPPORT_WEB_SECRET`,
`CC_SUPPORT_TURNSTILE_SITE_KEY`, and `CC_SUPPORT_TURNSTILE_SECRET_KEY` in the
root-owned environment file. The site key is public; the other two values must
not enter the static site, logs, or OpenClaw subprocess environment. The unit
stores abuse state under `/var/lib/cc-pocket-support-api/` with mode `0700`.
`cc-pocket-support-sessions-cleanup.timer` runs the official agent-scoped
cleanup daily. Its 28-day pruning threshold leaves enough timer jitter for the
publicly disclosed 30-day maximum.

## Provision

On the OpenClaw host, clone or update the public CC Pocket repository, then:

```bash
bash scripts/provision-openclaw-support.sh

CC_POCKET_REVIEW_MODEL=deepseek/deepseek-v4-pro \
  bash scripts/provision-openclaw-support.sh --apply
```

The first command is a dry run. `--apply` is required for any OpenClaw state
change. If a separate routine review model is not configured, omit
`CC_POCKET_REVIEW_MODEL`; the public support agent is provisioned, while the
reviewer and weekly cron stay disabled.

The default public model is `deepseek/deepseek-v4-pro`. It is the smallest
model currently configured on the production host that passed the canonical,
code-only, unknown-answer, language, and prompt-injection contract tests.
Override `CC_POCKET_SUPPORT_MODEL` only after the replacement passes the same
suite. The routine review model is configured separately. Manual promotion is
gated by the weekly Codex workflow in `docs/STRONG-SUPPORT-REVIEW.md`, which
records a distinct `promotion` review and opens a PR without merging it.

The script deliberately does not bind a channel. After provisioning:

1. Test the agent in Control UI with canonical, code-only, unknown, and
   prompt-injection questions.
2. Configure a dedicated bot account for Feishu, DingTalk, WeCom, or another
   supported channel.
3. Set channel allowlists, group mention rules, rate limits, and a short privacy
   notice.
4. Bind only that bot account with the guarded activation command:

   ```bash
   bash scripts/activate-openclaw-support-channel.sh \
     --channel feishu \
     --account <account-id> \
     --apply \
     --allowlist-confirmed \
     --rate-limit-confirmed
   ```

The activation command safely restarts the gateway and verifies that its PID
changed before reporting success. This matters because a binding can be present
in `openclaw agents list --bindings` while the still-running gateway continues
to route messages with its old configuration. If active reply delivery blocks
the safe restart, the command stops without claiming success. Use
`--force-restart-confirmed` only when interrupting those pending deliveries is
acceptable.

Never expose the Control UI gateway token as a customer chat token, and never
bind the public channel to the default personal agent.

Re-run provisioning after updating the checkout so the agents receive a fresh
tracked-file snapshot. Old snapshots contain no ignored files or secrets and
can be archived later by the operator.

## Verification

```bash
openclaw agents list --bindings
openclaw sandbox explain --agent cc-pocket-support
openclaw skills list
openclaw cron list --all
```

The routine reviewer job must use an explicit model, `--tools exec,read`, an
isolated session, and no fallback delivery. It must record only `routine`
reviews. A `promotion` review is reserved for the separate Codex automation.
