# OpenClaw deployment

This package creates two dedicated agents rather than reusing the personal
default agent:

- `cc-pocket-support`: routine public answers, manual retrieval, read-only code
  inspection, and candidate capture.
- `cc-pocket-support-review`: unbound scheduled routine reviewer. It can reject
  or provisionally verify a candidate, but cannot authorize manual promotion.

Both agents run every tool turn inside Docker with no network. Provisioning
first creates a `git archive` snapshot containing tracked files only, so ignored
files such as `.env` can never enter the sandbox. That snapshot is mounted
read-only at `/repo`. The public agent can write only candidate files under
`/queue` and reads `/governance`; the reviewer sees `/queue` read-only and can
write only `/governance`. Review verdicts are bound to the complete candidate
SHA-256, so the public agent cannot self-verify or change a reviewed answer
without invalidating that verdict. File mutation outside those mounts,
browser, messaging, gateway, cron, elevated, node, and agent-spawn tools are
denied.

The `cc-pocket-support-guard` plugin applies only to the public support agent.
Its pre-finalize hook asks the model to revise any answer that exposes internal
retrieval/policy narration or adds prose around the fixed escalation response.
Its outbound hook drops tool diagnostic payloads before a configured public
channel can deliver them.

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
