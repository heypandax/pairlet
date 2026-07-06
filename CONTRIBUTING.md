# Contributing to cc-pocket

Issues and PRs are welcome, in English or Chinese. This file collects everything a fresh clone needs that isn't obvious from the code.

## Modules at a glance

| Module | What | Runs where |
|---|---|---|
| `:protocol` | Shared wire protocol + E2E crypto (`pocket/*` frames) — single source of truth | KMP: JVM · Android · iOS |
| `:daemon` | Drives `claude` / `codex` as subprocesses, dials out to the relay | Your computer (JVM) |
| `:relay` | Zero-knowledge broker: pairing, ciphertext routing, rate limiting | Cloud (JVM) |
| `:mobile:composeApp` | The app (phone + desktop), Compose Multiplatform | Android · iOS · desktop |
| `iosApp/` | Xcode shell around the KMP framework (xcodegen from `project.yml`) | iOS |

## Build prerequisites

1. **JDK 17** — any distribution. The build resolves the exact toolchain itself (foojay resolver in `settings.gradle.kts`); you just need *a* JDK on `PATH`/`JAVA_HOME` to launch `./gradlew`.
2. **Android SDK** — set `ANDROID_HOME` or `local.properties` with `sdk.dir=...`. Needed even for JVM-only tasks: the Android modules are configured at Gradle configuration time.
3. **Firebase placeholder** — the real client configs are gitignored; copy the committed template once:
   ```bash
   cp mobile/composeApp/google-services.json.template mobile/composeApp/google-services.json
   ```
   For iOS additionally: `cp iosApp/iosApp/GoogleService-Info.plist.template iosApp/iosApp/GoogleService-Info.plist` (see `docs/ios-device.md`, step 0).
4. For daemon end-to-end runs: an installed, logged-in `claude` CLI ([Claude Code](https://claude.com/claude-code)).

## Running tests

```bash
bash scripts/check-all.sh      # protocol + daemon + relay + mobile desktop suites — run before a PR
bash scripts/relay-smoke.sh    # optional: in-memory relay E2E smoke (fake claude, no network)
./gradlew :protocol:jvmTest --tests "dev.ccpocket.protocol.e2e.*"   # crypto channel proofs
```

CI (`.github/workflows/ci.yml`) runs the protocol/daemon/relay suites and compiles the desktop target on every PR. The mobile UI/screenshot tests only run locally — they need a real Skia renderer.

Manual smoke of the daemon against a real `claude`: `./gradlew :daemon:run --args="test-client"` (see `docs/RUN.md`).

## Things that bite

- **Wire compatibility**: the daemon and the apps ship and update on independent schedules. Anything serialized in `:protocol` (frames, models, routes) must stay backward-compatible — additive changes with defaults, no renames/retypes. Say in your PR how an old peer handles the change.
- **Two READMEs**: `README.md` and `README.zh-CN.md` are maintained in lockstep. Touch one → touch both.
- **claude CLI drift**: the daemon depends on three subtle stream-json behaviors (mid-turn message queueing/injection, `AskUserQuestion` answer shape). After bumping your local claude, `python3 scripts/probe-claude-wire.py` regression-checks them (uses real quota).
- **xcodegen**: `iosApp/iosApp.xcodeproj` is generated from `iosApp/project.yml`. Persist project changes in the yml, not in Xcode.

## Scripts: contributor-relevant vs maintainer-only

Useful to everyone: `check-all.sh`, `relay-smoke.sh`, `install.sh` / `install.ps1` (end-user installers), `probe-claude-wire.py`.

Maintainer-only (need release credentials, signing identities, the production relay, or a specific paired device — they will just fail elsewhere): `release-*.sh`, `release-windows.ps1`, `notary-setup.sh`, `redeploy-relay.sh`, `provision-relay-push.sh`, `relay-smoke-prod.sh` (defaults to the production relay; `--relay` your own), `ios-fir.sh`, `install-pandaa.sh`, `update-local-daemon*.sh`. Details in `scripts/README.md`.

## Reporting

- Bugs / features: use the issue templates — daemon version (`cc-pocket-daemon status`), computer OS, and client platform make most reports diagnosable in one round.
- Security: **privately** via [GitHub security advisories](https://github.com/heypandax/cc-pocket/security/advisories/new) — see `docs/SECURITY.md` for the threat model and scope.

## License

MIT. By contributing you agree your contributions are licensed under it.
