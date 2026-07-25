# OpenClaw deployment

This package creates two dedicated agents rather than reusing the personal
default agent:

- `cc-pocket-support`: routine public answers, manual retrieval, read-only code
  inspection, and candidate capture.
- `cc-pocket-support-review`: unbound scheduled reviewer using a separately
  configured stronger model.

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

## Provision

On the OpenClaw host, clone or update the public CC Pocket repository, then:

```bash
bash scripts/provision-openclaw-support.sh

CC_POCKET_REVIEW_MODEL=openai/gpt-5.6-sol \
  bash scripts/provision-openclaw-support.sh --apply
```

The first command is a dry run. `--apply` is required for any OpenClaw state
change. If a stronger model is not configured, omit
`CC_POCKET_REVIEW_MODEL`; the public support agent is provisioned, while the
reviewer and weekly cron stay disabled.

The script deliberately does not bind a channel. After provisioning:

1. Test the agent in Control UI with canonical, code-only, unknown, and
   prompt-injection questions.
2. Configure a dedicated bot account for Feishu, DingTalk, WeCom, or another
   supported channel.
3. Set channel allowlists, group mention rules, rate limits, and a short privacy
   notice.
4. Bind only that bot account:

   ```bash
   openclaw agents bind --agent cc-pocket-support --bind feishu:<account-id>
   ```

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

The reviewer job must use an explicit strong model, `--tools exec,read`, an
isolated session, and no fallback delivery.
