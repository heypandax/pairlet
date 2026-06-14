# cc-pocket relay — deploy runbook

Infra deployment for the **cc-pocket relay** (Kotlin/JVM Ktor app). The relay forwards an
opaque, end-to-end-encrypted binary data plane and stores only fingerprints / pubkeys / hashes
(zero-knowledge). It binds **loopback only**; **Caddy** terminates TLS in front of it.

> Status: **LIVE**. The end-to-end-encryption layer is deployed and verified through the full
> production path (daemon → Cloudflare → Caddy → relay → device), see `scripts/relay-smoke-prod.sh`.
> The relay forwards only ciphertext and stores only fingerprints/pubkeys/hashes. Threat model: `docs/SECURITY.md`.

## Topology

```
client ──HTTPS──> Cloudflare (proxy, edge cert for ark-nexus.cc)
                      │  (orange-cloud; A record proxied, origin = $RELAY_HOST)
                      ▼
        Caddy :80/:443 (origin Let's Encrypt cert for pocket.ark-nexus.cc)
                      │  reverse_proxy (also upgrades WebSocket)
                      ▼
        cc-pocket-relay 127.0.0.1:9000  (SQLite at /var/lib/cc-pocket-relay/relay.db)
```

Public URL: `https://pocket.ark-nexus.cc/healthz` → `ok`
WebSocket endpoints (proxied automatically): `/v1/daemon`, `/v1/device`. REST: `/v1/pair/redeem`, `/v1/pair/code`.

## Server facts

> Commands below use `$RELAY_HOST` — export it first (`export RELAY_HOST=<your origin IP>`). The origin is kept out of git because it sits behind Cloudflare.

| Item | Value |
| --- | --- |
| Host | `$RELAY_HOST` (user `root`) |
| OS | Alibaba Cloud Linux 3 (Anolis 8, RHEL8-compatible) · x86_64 · systemd 239 |
| RAM | ~1.8 GB (small — relay heap capped at `-Xmx256m`) |
| JRE | `java-17-openjdk-headless` (system PATH `/usr/bin/java`) |
| Caddy | `2.6.4` from the `epel` repo (RHEL8 build), shipped systemd unit + `caddy` user |
| Relay user | `ccpocket` (system, `nologin`) |

### Remote paths

| Path | What |
| --- | --- |
| `/opt/cc-pocket-relay/` | relay dist (`bin/cc-pocket-relay` launcher + `lib/*.jar`), owned `root:root` |
| `/var/lib/cc-pocket-relay/relay.db` | SQLite store, owned `ccpocket:ccpocket` (dir `0750`) |
| `/etc/systemd/system/cc-pocket-relay.service` | relay unit (mirrors `deploy/cc-pocket-relay.service`) |
| `/etc/caddy/Caddyfile` | Caddy config (mirrors `deploy/Caddyfile`); `.orig` = package default backup |
| `/var/lib/caddy/.local/share/caddy/certificates/.../pocket.ark-nexus.cc/` | LE cert + key (auto-managed) |

## Files in this directory

- `cc-pocket-relay.service` — systemd unit (hardened: `NoNewPrivileges`, `ProtectSystem=full`,
  `ProtectHome`, runs as `ccpocket`, only `/var/lib/cc-pocket-relay` writable, `JAVA_TOOL_OPTIONS=-Xmx256m`).
- `Caddyfile` — one site, `reverse_proxy 127.0.0.1:9000`; Caddy auto-provisions Let's Encrypt
  and auto-upgrades WebSocket.

## SSH (non-interactive)

```bash
export SSHPASS='<root password>'
sshpass -e ssh -o StrictHostKeyChecking=accept-new root@$RELAY_HOST '<cmd>'
```

## First-time provision (what was done)

```bash
# 1. JRE
dnf install -y java-17-openjdk-headless

# 2. Caddy (epel build on this box; the @caddy/caddy COPR has no epel-3 repo, so use epel pkg)
dnf install -y 'dnf-command(copr)'
dnf install -y caddy            # pulls caddy-2.6.4 from epel; ships unit + caddy user

# 3. user + dirs
useradd --system --no-create-home --shell /usr/sbin/nologin ccpocket
mkdir -p /opt/cc-pocket-relay /var/lib/cc-pocket-relay
chown ccpocket:ccpocket /var/lib/cc-pocket-relay && chmod 750 /var/lib/cc-pocket-relay

# 4. ship dist + units (from local repo) — see "Redeploy" below for the scp
install -m 0644 cc-pocket-relay.service /etc/systemd/system/
cp -a /etc/caddy/Caddyfile /etc/caddy/Caddyfile.orig   # backup once
install -m 0644 Caddyfile /etc/caddy/Caddyfile

# 5. start
systemctl daemon-reload
systemctl enable --now cc-pocket-relay
caddy validate --config /etc/caddy/Caddyfile
systemctl enable --now caddy
```

## Rebuild (local)

```bash
cd /Users/lidapeng/Desktop/Project/app/cc-pocket
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :relay:installDist
# → relay/build/install/cc-pocket-relay/  (bin/ + lib/)
```

## Redeploy / update the relay (local → server)

```bash
export SSHPASS='<root password>'
REPO=/Users/lidapeng/Desktop/Project/app/cc-pocket
DIST="$REPO/relay/build/install/cc-pocket-relay"

# rebuild first (see above), then:
sshpass -e ssh -o StrictHostKeyChecking=accept-new root@$RELAY_HOST \
  'systemctl stop cc-pocket-relay && rm -rf /opt/cc-pocket-relay/bin /opt/cc-pocket-relay/lib'
sshpass -e scp -o StrictHostKeyChecking=accept-new -r "$DIST/bin" "$DIST/lib" \
  root@$RELAY_HOST:/opt/cc-pocket-relay/
sshpass -e ssh -o StrictHostKeyChecking=accept-new root@$RELAY_HOST \
  'chown -R root:root /opt/cc-pocket-relay && chmod +x /opt/cc-pocket-relay/bin/cc-pocket-relay && systemctl start cc-pocket-relay && sleep 4 && curl -fsS http://127.0.0.1:9000/healthz && echo " OK"'
```

> The SQLite db in `/var/lib/cc-pocket-relay/` is **not** touched by a redeploy.

If you change `cc-pocket-relay.service` or `Caddyfile`, re-copy them and:

```bash
# unit changed
systemctl daemon-reload && systemctl restart cc-pocket-relay
# Caddyfile changed (validate first; reload is zero-downtime)
caddy validate --config /etc/caddy/Caddyfile && systemctl reload caddy
```

## Operate

```bash
# status
systemctl status cc-pocket-relay
systemctl status caddy

# logs (follow)
journalctl -u cc-pocket-relay -f
journalctl -u caddy -f
journalctl -u caddy | grep -iE 'obtain|acme|cert|error'   # ACME / cert status

# restart
systemctl restart cc-pocket-relay
systemctl reload caddy        # zero-downtime config reload

# health — on server
curl -s http://127.0.0.1:9000/healthz                     # -> ok

# health — public (through Cloudflare)
curl -sS https://pocket.ark-nexus.cc/healthz              # -> ok

# health — origin directly (bypass Cloudflare; validates Caddy's own LE cert)
curl -sS --resolve pocket.ark-nexus.cc:443:$RELAY_HOST https://pocket.ark-nexus.cc/healthz
```

## TLS / networking notes

- The domain `pocket.ark-nexus.cc` is **proxied through Cloudflare** (DNS resolves to a
  Cloudflare IP, not the origin). Two cert layers exist: Cloudflare's edge cert (client side)
  and Caddy's **origin** Let's Encrypt cert for `pocket.ark-nexus.cc`. Both are valid; the
  ACME `http-01` challenge completed via Cloudflare passing it through to the origin on :80.
- Renewal is automatic (Caddy). Cert + key persist under
  `/var/lib/caddy/.local/share/caddy/certificates/...`.
- A benign log line `no OCSP stapling ... no OCSP server specified` is expected for current
  Let's Encrypt certs — ignore.

## Aliyun security group

Inbound **TCP 80 and 443 are already reachable** from the public internet (verified — the ACME
challenge succeeded and public HTTPS returns `ok`). **No user action required.**

If 80/443 ever become unreachable (e.g. security-group change) the symptom is Caddy looping in
`journalctl -u caddy` on the ACME challenge. Fix: open inbound **TCP 80 and 443** for this ECS
instance in the **Aliyun ECS security group** console (cannot be done over SSH — host firewall is
already open; inbound is gated by the security group). Caddy will obtain/renew the cert
automatically once the ports are reachable.

## Do-not-disturb (co-located services on this host)

- `searxng` on `127.0.0.1:8080`
- `openclaw-gateway` on `:12739`
- SSH config — untouched.

These were verified still listening after this deploy.
