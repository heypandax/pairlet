## What & why

<!-- One or two sentences; link the issue if there is one (Fixes #123). 中文亦可。 -->

## Checklist

- [ ] `bash scripts/check-all.sh` passes locally (protocol + daemon + relay + mobile desktop tests — CI only runs the JVM suites)
- [ ] If this touches `:protocol`: the wire format stays backward-compatible — the daemon and the apps update on independent schedules
- [ ] If this touches user-facing docs: **both** `README.md` and `README.zh-CN.md` updated
