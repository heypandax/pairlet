# CC Pocket

Drive the `claude` CLI on your computer from your phone — from anywhere, not just your LAN. Start/resume sessions, browse working directories, send prompts, and approve or deny Claude's tool-permission requests remotely. Traffic flows through a **zero-knowledge relay** that only ever forwards end-to-end-encrypted ciphertext. Clean-room Kotlin, MIT.

```mermaid
flowchart LR
    phone["📱 CC Pocket<br/>(Compose Multiplatform)"] -- "wss · ciphertext" --> relay["relay<br/>(zero-knowledge broker)"]
    relay -- "wss · ciphertext" --> daemon["daemon<br/>(your computer)"]
    daemon -- "stdio" --> claude["claude CLI"]
```

The relay pairs phone ↔ computer and routes opaque encrypted frames between them; it holds no message content and no private keys. The phone and the daemon run an end-to-end session (P-256 ECDH + HKDF + AES-256-GCM, an X3DH/Noise-style handshake) so plaintext never leaves the two trusted endpoints.

## Modules

| Module | What | Stack |
|---|---|---|
| `:protocol` | Shared wire protocol (`pocket/*` frames) — single source of truth | Kotlin Multiplatform + kotlinx.serialization |
| `:daemon` | Runs on your computer; drives `claude` as a subprocess, dials out to the relay | Kotlin/JVM + Ktor |
| `:relay` | Cloud broker: device-key pairing, ciphertext routing, multi-tenant, rate-limited | Kotlin/JVM + Ktor + SQLite |
| `:mobile` | The CC Pocket app | Compose Multiplatform — Android · iOS · desktop |

## Install

End users only need the **daemon** on their Mac — the relay is hosted for you:

```bash
brew install --cask heypandax/tap/cc-pocket
cc-pocket-daemon service-install --apply   # run on login, auto-reconnect
cc-pocket-daemon pair                       # prints a QR + 6-digit code
```

Then pair your phone and start driving Claude from it — full walkthrough in [`docs/USAGE.md`](docs/USAGE.md). Upgrade with `brew upgrade --cask cc-pocket`.

> Published for **macOS / Apple Silicon**. The daemon is plain Kotlin/JVM and the code is cross-platform; on Linux/Windows, build it yourself (see [Quick start](#quick-start)).

## How pairing works

No accounts, no login. The daemon generates a static keypair on first run (its `account id` is the public fingerprint). To add a phone:

```bash
cc-pocket pair        # on your computer — prints a QR + a 6-digit code
```

On the phone, **scan the QR** (system camera or the in-app scanner) or **type the 6-digit code**. The phone registers its own device key and pairs end-to-end. Scanning the QR carries the daemon's key out-of-band, so even a malicious relay can't MITM that path.

See [`docs/SECURITY.md`](docs/SECURITY.md) for the full threat model and the trust-without-trusting-us argument (open source, self-hostable, zero content logging).

## Quick start

Requires JDK 17 and an installed, logged-in `claude` CLI.

**Local single-machine (no relay), for development:**

```bash
./gradlew :protocol:check                         # protocol contract test
./gradlew :daemon:run --args="run"                # daemon — local WebSocket on 127.0.0.1:8765
./gradlew :daemon:run --args="test-client"        # drive it against the real claude
#   dirs · ls <wd> · open <wd> [resumeId] · say <text> · cd <wd> · mode <m> · allow · deny · quit
```

**Through the relay (off-LAN), the real product path:**

```bash
./gradlew :daemon:installDist                      # build the launcher
daemon/build/install/cc-pocket-daemon/bin/cc-pocket-daemon \
  run --relay wss://<your-relay> --claude-bin ~/.local/bin/claude
# then, in another terminal:
daemon/build/install/cc-pocket-daemon/bin/cc-pocket-daemon pair
```

Build the app: Android via `./gradlew :mobile:composeApp:assembleDebug`; iOS via `iosApp/iosApp.xcodeproj` (Xcode). See [`docs/ios-device.md`](docs/ios-device.md) for on-device install.

## Docs

- User guide (中文使用文档) — [`docs/USAGE.md`](docs/USAGE.md)
- Run / operate the daemon — [`docs/RUN.md`](docs/RUN.md)
- Security model & threat analysis — [`docs/SECURITY.md`](docs/SECURITY.md)
- iOS device build & install — [`docs/ios-device.md`](docs/ios-device.md)
- Relay deployment (Caddy + Cloudflare + systemd) — [`deploy/README.md`](deploy/README.md)
- Requirements — [`docs/REQUIREMENTS.md`](docs/REQUIREMENTS.md)
- Implementation plan — [`docs/cc-connect-cc-connect-sequential-graham.md`](docs/cc-connect-cc-connect-sequential-graham.md)
- UI design (claude.ai/design handoff) — [`docs/design/`](docs/design/)
- Provenance / clean-room statement — [`docs/ANTIPLAGIARISM.md`](docs/ANTIPLAGIARISM.md)

## License

MIT — see [`LICENSE`](LICENSE).
