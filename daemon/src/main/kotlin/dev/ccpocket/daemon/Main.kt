package dev.ccpocket.daemon

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import dev.ccpocket.daemon.agent.AgentBackendFactory
import dev.ccpocket.daemon.claude.ClaudeBackend
import dev.ccpocket.daemon.claude.ClaudeLauncher
import dev.ccpocket.daemon.codex.CodexBackend
import dev.ccpocket.daemon.codex.CodexLauncher
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.daemon.relay.LoopbackBridge
import dev.ccpocket.daemon.relay.LoopbackHeadlessCred
import dev.ccpocket.daemon.relay.LoopbackHeadlessReq
import dev.ccpocket.daemon.relay.LoopbackPair
import dev.ccpocket.daemon.relay.LoopbackShareMinted
import dev.ccpocket.daemon.relay.LoopbackShareReq
import dev.ccpocket.daemon.relay.LoopbackShareRevokeReq
import dev.ccpocket.daemon.relay.LoopbackStatus
import dev.ccpocket.daemon.relay.PairLoopback
import dev.ccpocket.daemon.relay.RelayClient
import dev.ccpocket.protocol.ShareListing
import dev.ccpocket.protocol.ShareRevoked
import dev.ccpocket.daemon.server.DaemonServer
import dev.ccpocket.daemon.server.LanE2E
import dev.ccpocket.daemon.service.ServiceInstaller
import dev.ccpocket.daemon.util.QrTerminal
import dev.ccpocket.protocol.PocketJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.net.Inet4Address
import java.net.NetworkInterface

/** Production relay; users connect here by default so `run`/`service-install` need no URL. */
const val DEFAULT_RELAY = "wss://pocket.ark-nexus.cc"

/** Pick the most likely LAN IPv4 address: a physical, non-virtual site-local (RFC1918) address. */
fun lanIp(): String? {
    // Virtual / overlay / tunnel NICs look like ordinary interfaces to the JVM. On Linux/macOS the
    // hint is in the device name (zt*, utun*, docker0, veth*); on Windows the JVM hands out synthetic
    // names (eth7, net3) and the only hint is the friendly displayName — so we check both.
    val virtualHints = listOf("zt", "tun", "tap", "docker", "veth", "br-", "gif", "stf", "anpi",
        "awdl", "bridge", "llw", "utun", "vmnet", "vmware", "vbox", "virtualbox",
        "zerotier", "tailscale", "wireguard", "hyper-v", "loopback")
    fun looksVirtual(ni: NetworkInterface): Boolean {
        val name = ni.name.lowercase()
        val display = (ni.displayName ?: "").lowercase()
        return virtualHints.any { name.startsWith(it) || display.contains(it) }
    }
    return NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .sortedBy { if (looksVirtual(it)) 1 else 0 } // de-prioritize overlay/tunnel NICs
        .flatMap { it.interfaceAddresses.asSequence() }
        .mapNotNull { it.address as? Inet4Address }
        .filter { it.isSiteLocalAddress } // RFC1918 only: 10/8, 172.16/12, 192.168/16
        .map { it.hostAddress }
        .firstOrNull()
}

/** The host's OS computer name, advertised in [dev.ccpocket.protocol.DaemonInfo] so a paired client can
 *  show it as the binding's default name (issue #62). COMPUTERNAME on Windows; the resolved local host
 *  name elsewhere; HOSTNAME as a last resort. The domain suffix (.local / FQDN) is stripped for a clean
 *  short name, and "localhost" is treated as no-name (fall through to the account-id fallback). */
fun daemonHostName(): String? {
    val raw = System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }
        ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: System.getenv("HOSTNAME")?.takeIf { it.isNotBlank() }
    return raw?.substringBefore('.')?.takeIf { it.isNotBlank() && !it.equals("localhost", ignoreCase = true) }
}

/**
 * Startup gate for `run` (issue #130): each agent CLI is optional on its own — a codex-only machine must
 * boot with claude missing (and vice versa) — but with NEITHER resolvable the daemon has nothing to drive,
 * so refuse to start with an actionable message. Null = at least one agent found, start normally.
 */
internal fun missingAgentsMessage(claudeExe: java.nio.file.Path?, codexExe: java.nio.file.Path?): String? =
    if (claudeExe == null && codexExe == null) {
        "neither claude nor codex was found — install Claude Code or the Codex CLI, " +
            "or point the daemon at one with --claude-bin / --codex-bin (or CC_POCKET_CLAUDE_BIN / CC_POCKET_CODEX_BIN)."
    } else {
        null
    }

private class Root : CliktCommand(name = "cc-pocket-daemon") {
    override fun run() = Unit
}

private class RunCmd : CliktCommand(name = "run") {
    private val host by option().default("127.0.0.1")
    private val port by option().int().default(8765)
    private val claudeBin by option("--claude-bin", help = "claude executable (default: auto-detect the installed Claude Code)")
    private val codexBin by option("--codex-bin", help = "codex executable (default: auto-detect the installed Codex CLI)")
    private val relay by option("--relay", help = "relay wss base").default(DEFAULT_RELAY)
    private val local by option("--local", help = "run a LAN-only WebSocket server instead of dialing the relay").flag()
    private val directBind by option(
        "--direct-bind",
        help = "relay mode: also listen for E2E direct connections on this interface (paired devices skip the relay). " +
            "Default 127.0.0.1 = same-machine apps only; pass 0.0.0.0 to open it to your LAN (still Noise-gated), or 'none' to disable",
    ).default("127.0.0.1")
    private val pairPort by option("--pair-port", help = "loopback port for the `pair` command").int().default(8799)
    private val takeover by option("--takeover", help = "if another cc-pocket daemon is already running, stop it and run this one instead (default: exit and leave it running)").flag()
    private val autoUpdate by option("--auto-update", help = "apply daemon updates automatically (installer-managed macOS/Linux installs; others get a notification)").flag()

    override fun run() {
        // Both agent CLIs are optional individually (issue #130): probe each for the banner, but resolve
        // lazily per session so a missing one never blocks startup — codex-only machines used to crash-loop
        // under launchd because claude was a hard dependency. An open on a missing backend fails with a
        // clear PocketError instead. Only BOTH missing refuses to start (nothing this daemon could drive).
        val exe = runCatching { ClaudeLauncher.resolveExecutable(claudeBin) }.getOrNull()
        val codexExe = runCatching { CodexLauncher.resolveExecutable(codexBin) }.getOrNull()
        missingAgentsMessage(exe, codexExe)?.let { throw com.github.ajalt.clikt.core.CliktError(it) }
        // credential isolation (issue #69, opt-in via `config --isolated-claude-auth on` or the env
        // toggle): the daemon's claude gets its own CLAUDE_CONFIG_DIR — its OAuth token refreshes can't
        // log out a terminal claude sharing the machine. History/settings stay shared (symlinks).
        val prefs = DaemonPrefs.load()
        val wantIsolation = prefs.isolatedClaudeAuth || System.getenv("CC_POCKET_ISOLATED_CLAUDE_AUTH") == "1"
        val claudeHome = if (wantIsolation) dev.ccpocket.daemon.claude.ClaudeHome.prepare() else null
        if (wantIsolation && claudeHome == null) {
            echo("⚠ claude credential isolation requested but claude-home setup failed — running WITHOUT isolation (see daemon log)")
        }
        // API presets (issue #113): ONE store shared by the service (CRUD/activate over the wire) and
        // every claude backend (per-launch env injection) — two instances would let them diverge
        val presetStore = dev.ccpocket.daemon.presets.PresetStore.load()
        val core = DaemonCore(
            mapOf(
                AgentKind.CLAUDE to AgentBackendFactory { ClaudeBackend(claudeBin, claudeHome, presetStore::activeEnv) }, // resolves the binary lazily on first launch
                AgentKind.CODEX to AgentBackendFactory { CodexBackend(codexBin) }, // resolves the binary lazily on first launch
            ),
            prefs = prefs,
            claudeConfigDir = claudeHome,
            presetStore = presetStore,
        )
        if (claudeHome != null) {
            echo("claude credential isolation: ON — daemon login store: $claudeHome")
            echo("(the daemon signs in separately: if sessions report auth errors, sign in from the app's Settings → Account)")
        }
        if (!local) {
            val identity = Identity.loadOrCreate()
            // What DaemonInfo advertises after each handshake: where paired devices can reach us without
            // the relay. Bind-specific IP → advertise it; 0.0.0.0 → advertise the current LAN IP (recomputed
            // per handshake, so a DHCP move heals itself); none/no usable interface → null (devices clear
            // any stored address and stay on the relay).
            val directUrl: () -> String? = {
                when {
                    directBind == "none" -> null
                    directBind == "0.0.0.0" -> lanIp()?.let { "ws://$it:$port/v1/ws" }
                    else -> "ws://$directBind:$port/v1/ws"
                }
            }
            // the OS computer name — advertised in DaemonInfo so a paired client shows "Pandas-MacBook-Pro"
            // as the default binding name instead of a truncated account-id hash (issue #62). A provider
            // like [directUrl], resolved lazily at the first handshake: getLocalHost() can stall seconds
            // behind fake-IP/TUN DNS setups, which must not delay startup. A user-set nickname still wins
            // client-side.
            val hostNameLazy = lazy { daemonHostName() }
            val hostName: () -> String? = { hostNameLazy.value }
            val relayClient = RelayClient(relay, identity, core, lanUrl = directUrl, hostname = hostName)
            echo("cc-pocket daemon — claude=${exe ?: "(not found)"} — codex=${codexExe ?: "(not found)"} — relay=$relay")
            echo("account id: ${identity.accountId}")
            echo("(run `cc-pocket-daemon pair` in another terminal to add a phone)")
            // E2E-gated direct listener beside the relay: paired devices on this machine/LAN connect
            // straight to us (no proxy/relay leg — the fix for flaky-uplink send/receive). Unlike the
            // plaintext --local path this REQUIRES the Noise handshake, so a wide bind stays safe. A bind
            // failure (port taken) degrades to relay-only instead of killing the daemon.
            if (directBind != "none") {
                val gate = LanE2E(identity, directUrl, hostName, firstContactPending = relayClient::deviceFirstContactPending)
                runCatching { DaemonServer(core, directBind, port, gate).run(wait = false) }
                    .onSuccess { echo("direct listener on ws://$directBind:$port/v1/ws (E2E, paired devices only)") }
                    .onFailure { echo("direct listener failed to bind $directBind:$port (${it.message}) — relay only") }
            }
            // Windows: if we're not yet registered as a logon background service, self-install so closing this
            // window no longer takes the daemon offline (issue #16). No-op on macOS/Linux and when already set up.
            ServiceInstaller.selfInstallIfMissingWindows(
                ProcessHandle.current().info().command().orElse(""),
                buildList {
                    add("run"); add("--relay"); add(relay)
                    claudeBin?.let { add("--claude-bin"); add(it) }
                    codexBin?.let { add("--codex-bin"); add(it) }
                },
            )?.let { echo(it) }
            // A daemon is a singleton (owns the pair port + one relay identity). If another instance is
            // already up — the cask's KeepAlive LaunchAgent, or a stray dev run — don't bind/attach a
            // duplicate that fights it on the relay; exit cleanly (or --takeover to replace it).
            SingleInstance.ensureSolo(pairPort, takeover) { echo(it) }
            PairLoopback(relayClient, relay, identity.e2ePubB64, pairPort).start()
            // daily new-version check: log + one phone push per version; --auto-update (or the env
            // toggle, so the flag survives service reinstalls) hot-swaps installer-managed installs
            val auto = autoUpdate || System.getenv("CC_POCKET_AUTO_UPDATE") == "1"
            dev.ccpocket.daemon.update.UpdateChecker.start(relayClient, auto)
            Runtime.getRuntime().addShutdownHook(Thread { runBlocking { core.shutdown() } })
            runBlocking { relayClient.run() }
        } else {
            // Advertise a LAN URL + QR for phone pairing, but NEVER silently widen the bind:
            // we listen on `host` as-is. The direct-LAN path has no handshake / auth / E2E, so a
            // phone can only reach us once the user explicitly binds beyond loopback — show the
            // pairing URL/QR only then, never for a loopback bind the phone can't connect to.
            val lan = lanIp()
            echo("cc-pocket daemon — claude=${exe ?: "(not found)"} — codex=${codexExe ?: "(not found)"}")
            echo("")
            if (host == "127.0.0.1") {
                echo("  Bound to 127.0.0.1 (loopback only) — not reachable from your phone.")
                if (lan != null) {
                    echo("  To pair over your LAN, re-run with:  --host 0.0.0.0")
                    echo("  It would then be reachable at ws://$lan:$port/v1/ws")
                }
                echo("")
            } else {
                val advertiseIp = if (host == "0.0.0.0") (lan ?: host) else host
                val url = "ws://$advertiseIp:$port/v1/ws"
                if (host == "0.0.0.0") {
                    echo("  !! UNGUARDED — bound to 0.0.0.0 (all interfaces) !!")
                    echo("  Anyone on your network can open sessions, browse files and approve tools.")
                    echo("")
                }
                echo("  LAN server on $url")
                echo("")
                echo("  On your phone, open CC Pocket and tap:")
                echo("    Advanced: Direct LAN")
                echo("  Then enter: $url")
                echo("")
                if (advertiseIp != "0.0.0.0") {
                    echo(QrTerminal.render(url))
                    echo("")
                }
            }
            DaemonServer(core, host, port).run()
        }
    }
}

private class TestClientCmd : CliktCommand(name = "test-client") {
    private val host by option().default("127.0.0.1")
    private val port by option().int().default(8765)
    private val relay by option("--relay", help = "relay ws base for device mode, e.g. ws://host:9000")
    private val daemonPub by option("--daemon-pub", help = "daemon E2E public key from `pair` (relay mode)")
    private val ticket by option("--ticket", help = "pairing ticket from `pair` (relay mode)")

    override fun run() {
        val r = relay
        if (r != null) {
            TestClient.relay(
                r,
                daemonPub ?: error("--daemon-pub is required with --relay"),
                ticket ?: error("--ticket is required with --relay"),
            ).run()
        } else {
            TestClient.direct("ws://$host:$port/v1/ws").run()
        }
    }
}

/** The per-OS way to (re)start the background daemon — shown whenever the loopback port is unreachable. */
private fun daemonStartHint(): String {
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.contains("win") -> "start it:  schtasks /Run /TN ${ServiceInstaller.WINDOWS_TASK}    (or run it by hand: cc-pocket-daemon run)"
        os.contains("mac") -> "start it:  launchctl kickstart -k gui/$(id -u)/dev.ccpocket.daemon    (or run it by hand: cc-pocket-daemon run)"
        else -> "start it:  systemctl --user start cc-pocket-daemon    (or run it by hand: cc-pocket-daemon run)"
    }
}

private class PairCmd : CliktCommand(name = "pair") {
    private val pairPort by option("--pair-port", help = "loopback port of the running daemon").int().default(8799)

    // ---- headless bridge issuance (issue #91) ----
    private val headless by option("--headless", help = "mint a RESTRICTED bridge credential for an external automation (IM bot, webhook) instead of a phone pairing QR").flag()
    private val name by option("--name", help = "headless only: a unique name for this bridge, e.g. feishu-bot — shown as \"via <name>\" in the app")
    private val workdir by option("--workdir", help = "headless only (repeatable, required): absolute directory the bridge may open sessions under").multiple()
    private val maxSessions by option("--max-sessions", help = "headless only: concurrent live sessions cap (default ${dev.ccpocket.daemon.bridge.BridgeSpec.DEFAULT_MAX_SESSIONS}, max 8)").int()
    private val opensPerMin by option("--open-per-min", help = "headless only: session.open rate cap (default ${dev.ccpocket.daemon.bridge.BridgeSpec.DEFAULT_OPENS_PER_MIN}/min)").int()
    private val promptsPerMin by option("--prompt-per-min", help = "headless only: prompt rate cap (default ${dev.ccpocket.daemon.bridge.BridgeSpec.DEFAULT_PROMPTS_PER_MIN}/min)").int()

    override fun run() = runBlocking {
        val client = HttpClient(CIO)
        try {
            if (headless) {
                pairHeadless(client)
                return@runBlocking
            }
            // The daemon's relay link may legitimately be mid-reconnect (backoff reaches 30s) while the mint
            // window is only 10s — a one-shot pair failed spuriously on a HEALTHY daemon. Ride it out: retry
            // until the link comes back or the window closes, and only then diagnose.
            val deadline = System.currentTimeMillis() + PAIR_RETRY_WINDOW_MS
            var lastBody = ""
            var waiting = false
            while (true) {
                val body = runCatching { client.post("http://127.0.0.1:$pairPort/pair").bodyAsText() }.getOrElse {
                    echo("✗ no daemon on 127.0.0.1:$pairPort — ${daemonStartHint()}")
                    return@runBlocking
                }
                val info = runCatching { PocketJson.decodeFromString<LoopbackPair>(body) }.getOrNull()
                if (info != null) {
                    echo("")
                    echo("  Open CC Pocket on your phone and scan this — or type the code (valid ${info.ttlSec}s):")
                    echo("")
                    echo(QrTerminal.render("ccpocket://pair?code=${info.code}"))
                    echo("        code:  ${info.code.chunked(3).joinToString(" ")}")
                    echo("")
                    return@runBlocking
                }
                lastBody = body
                if (System.currentTimeMillis() > deadline) break
                if (!waiting) {
                    waiting = true
                    echo("daemon is up but its relay link is down — waiting for it to reconnect (up to ${PAIR_RETRY_WINDOW_MS / 1000}s)…")
                }
                kotlinx.coroutines.delay(3_000)
            }
            echo("✗ pairing failed — the daemon can't reach the relay ($lastBody)")
            echo("  likely: no internet, or a proxy/firewall blocking $DEFAULT_RELAY, or the relay is down.")
            echo("  inspect: cc-pocket-daemon status")
        } finally {
            client.close()
        }
    }

    /** Mint a headless bridge credential and print it as ONE JSON blob for the adapter. */
    private suspend fun pairHeadless(client: HttpClient) {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) { echo("✗ --headless requires --name <bridge-name> (e.g. --name feishu-bot)"); return }
        if (workdir.isEmpty()) {
            echo("✗ --headless requires at least one --workdir <absolute dir> — the ONLY directories this bridge may open sessions in.")
            echo("  (This is the blast-radius limiter: prompts arriving from the IM side can read whatever the agent")
            echo("  can read under these roots in its default mode. Point it at a dedicated, low-sensitivity checkout.)")
            return
        }
        val req = LoopbackHeadlessReq(n, workdir.toList(), maxSessions, opensPerMin, promptsPerMin)
        val body = runCatching {
            client.post("http://127.0.0.1:$pairPort/pair/headless") { setBody(PocketJson.encodeToString(req)) }.bodyAsText()
        }.getOrElse {
            echo("✗ no daemon on 127.0.0.1:$pairPort — ${daemonStartHint()}")
            return
        }
        val cred = runCatching { PocketJson.decodeFromString<LoopbackHeadlessCred>(body) }.getOrNull()
        if (cred == null) {
            echo("✗ headless pairing failed: $body")
            return
        }
        echo("")
        echo("  Bridge credential for \"${cred.name}\" — single-use, expires in ${cred.ttlSec}s.")
        echo("  Hand this JSON to the bridge adapter NOW (it redeems the ticket on first start, e.g.")
        echo("  save it as bridge-credential.json for examples/feishu-bridge):")
        echo("")
        echo(PocketJson.encodeToString(cred))
        echo("")
        echo("  The bridge can ONLY: open sessions under ${cred.workdirs} / send prompts / read its own")
        echo("  sessions' replies. Permission prompts still go to YOUR phone; it cannot approve anything.")
        echo("  Manage it later:  cc-pocket-daemon bridges   |   cc-pocket-daemon bridges --revoke ${cred.name}")
    }

    private companion object { const val PAIR_RETRY_WINDOW_MS = 60_000L }
}

/** List / revoke headless bridge credentials (issue #91). */
private class BridgesCmd : CliktCommand(name = "bridges") {
    private val pairPort by option("--pair-port", help = "loopback port of the running daemon").int().default(8799)
    private val revoke by option("--revoke", help = "revoke a bridge by name or deviceId (cuts its live link and deletes its key)")

    override fun run() = runBlocking {
        val client = HttpClient(CIO)
        try {
            val toRevoke = revoke
            if (toRevoke != null) {
                val body = runCatching {
                    client.post("http://127.0.0.1:$pairPort/bridge/revoke") { setBody("""{"idOrName":"$toRevoke"}""") }.bodyAsText()
                }.getOrElse { echo("✗ no daemon on 127.0.0.1:$pairPort — ${daemonStartHint()}"); return@runBlocking }
                if ("\"revoked\"" in body) echo("✓ revoked: $body") else echo("✗ revoke failed: $body")
                return@runBlocking
            }
            val body = runCatching { client.get("http://127.0.0.1:$pairPort/bridges").bodyAsText() }
                .getOrElse { echo("✗ no daemon on 127.0.0.1:$pairPort — ${daemonStartHint()}"); return@runBlocking }
            val rows = runCatching { PocketJson.decodeFromString<List<LoopbackBridge>>(body) }.getOrNull()
            when {
                rows == null -> echo("✗ unexpected reply: $body")
                rows.isEmpty() -> echo("no bridge credentials — mint one with: cc-pocket-daemon pair --headless --name <n> --workdir <dir>")
                else -> {
                    echo("")
                    rows.forEach { b ->
                        echo("  ${b.name}")
                        echo("    deviceId:  ${b.deviceId}")
                        echo("    workdirs:  ${b.workdirs.joinToString(", ")}")
                        echo("    limits:    ${b.maxSessions} concurrent · ${b.opensPerMin} opens/min · ${b.promptsPerMin} prompts/min")
                    }
                    echo("")
                    echo("  revoke one:  cc-pocket-daemon bridges --revoke <name|deviceId>")
                }
            }
        } finally {
            client.close()
        }
    }
}

/**
 * Mint / list / revoke folder-share invites headlessly (issue #115) — the `pair --headless` sibling for
 * granting a guest a scoped, expiring folder credential without opening the app. The minted code is
 * byte-identical to the app's "Create invite": the guest pastes it into CC Pocket ▸ Connect.
 */
private class ShareCmd : CliktCommand(name = "share") {
    private val pairPort by option("--pair-port", help = "loopback port of the running daemon").int().default(8799)
    private val workdir by option("--workdir", help = "absolute directory to share — the guest can only open sessions under it")
    private val tier by option("--tier", help = "autonomy ceiling the guest runs under (default review)")
        .choice("review", "collaborate", "autonomous").default("review")
    private val expires by option("--expires", help = "share lifetime, e.g. 1h / 90m / 2d / 3600s (default 7d; clamped to 5min–90d)")
    private val label by option("--label", help = "optional nickname for the guest, shown on your Shared page")
    private val list by option("--list", help = "list active folder shares + who is using them").flag()
    private val revoke by option("--revoke", help = "revoke a share by its guest deviceId (from --list) — cuts the live link, deletes the key")

    override fun run() = runBlocking {
        val client = HttpClient(CIO)
        try {
            when {
                list -> shareList(client)
                revoke != null -> shareRevoke(client, revoke!!)
                else -> shareMint(client)
            }
        } finally {
            client.close()
        }
    }

    private suspend fun shareMint(client: HttpClient) {
        val wd = workdir?.trim().orEmpty()
        if (wd.isEmpty()) {
            echo("✗ share requires --workdir <absolute dir> (or use --list, or --revoke <deviceId>)")
            echo("  the guest can open sessions ONLY under this folder; approvals still go to YOUR phone.")
            return
        }
        val expSec = expires?.let {
            parseDuration(it) ?: run { echo("✗ bad --expires \"$it\" — use e.g. 1h, 90m, 2d, or 3600s"); return }
        }
        val req = LoopbackShareReq(workdir = wd, tier = tier, expiresInSec = expSec, label = label?.trim()?.takeIf { it.isNotEmpty() })
        val body = runCatching {
            client.post("http://127.0.0.1:$pairPort/share") { setBody(PocketJson.encodeToString(req)) }.bodyAsText()
        }.getOrElse {
            echo("✗ no daemon on 127.0.0.1:$pairPort — ${daemonStartHint()}")
            return
        }
        val minted = runCatching { PocketJson.decodeFromString<LoopbackShareMinted>(body) }.getOrNull()
        if (minted == null || !minted.ok || minted.code == null) {
            echo("✗ share failed: ${minted?.error ?: body}")
            return
        }
        val leftMs = (minted.expiresAt ?: 0L) - System.currentTimeMillis()
        echo("")
        echo("  Folder share ready — paste into CC Pocket ▸ Connect. Works once.")
        echo("")
        echo(minted.code)
        echo("")
        echo("  folder:   ${minted.folderName}")
        echo("  tier:     ${minted.tier}")
        echo("  expires:  in ${humanDuration(leftMs)} (accept within ${minted.ttlSec}s; the share itself lasts the full window)")
        echo("")
        echo("  manage it:  cc-pocket-daemon share --list   |   cc-pocket-daemon share --revoke <deviceId>")
    }

    private suspend fun shareList(client: HttpClient) {
        val body = runCatching { client.get("http://127.0.0.1:$pairPort/shares").bodyAsText() }
            .getOrElse { echo("✗ no daemon on 127.0.0.1:$pairPort — ${daemonStartHint()}"); return }
        val listing = runCatching { PocketJson.decodeFromString<ShareListing>(body) }.getOrNull()
        when {
            listing == null -> echo("✗ unexpected reply: $body")
            listing.items.isEmpty() -> echo("no active folder shares — mint one with: cc-pocket-daemon share --workdir <dir>")
            else -> {
                echo("")
                listing.items.forEach { s ->
                    val state = when {
                        s.revoked -> "revoked"
                        s.expired -> "expired"
                        s.online -> "active now (${s.activeSessions})"
                        else -> "idle"
                    }
                    echo("  ${s.path}")
                    echo("    guest:     ${s.guestLabel ?: "someone"} · ${s.tier.name.lowercase()} · $state")
                    echo("    deviceId:  ${s.deviceId}")
                    echo("    expires:   in ${humanDuration(s.expiresAt - System.currentTimeMillis())}")
                }
                echo("")
                echo("  revoke one:  cc-pocket-daemon share --revoke <deviceId>")
            }
        }
    }

    private suspend fun shareRevoke(client: HttpClient, deviceId: String) {
        val body = runCatching {
            client.post("http://127.0.0.1:$pairPort/share/revoke") {
                setBody(PocketJson.encodeToString(LoopbackShareRevokeReq(deviceId.trim())))
            }.bodyAsText()
        }.getOrElse { echo("✗ no daemon on 127.0.0.1:$pairPort — ${daemonStartHint()}"); return }
        val res = runCatching { PocketJson.decodeFromString<ShareRevoked>(body) }.getOrNull()
        when {
            res == null -> echo("✗ unexpected reply: $body")
            res.ok -> echo("✓ revoked share ${res.deviceId} — its live link is cut and its key deleted")
            else -> echo("✗ revoke failed: ${res.error ?: "unknown"}")
        }
    }

    /** Parse a duration like `1h`, `90m`, `2d`, `3600s`, or a bare `3600` (seconds) into seconds; null if unparseable. */
    private fun parseDuration(raw: String): Long? {
        val m = Regex("^(\\d+)([smhdw]?)$").matchEntire(raw.trim().lowercase()) ?: return null
        val n = m.groupValues[1].toLongOrNull() ?: return null
        val mult = when (m.groupValues[2]) {
            "", "s" -> 1L
            "m" -> 60L
            "h" -> 3600L
            "d" -> 86_400L
            "w" -> 604_800L
            else -> return null
        }
        return n * mult
    }

    /** Human "6d" / "3h" / "45m" for a remaining-milliseconds span (coarse; the largest one or two units). */
    private fun humanDuration(ms: Long): String {
        if (ms <= 0) return "expired"
        val s = ms / 1000
        val d = s / 86_400
        val h = (s % 86_400) / 3600
        val m = (s % 3600) / 60
        return when {
            d > 0 -> if (h > 0) "${d}d ${h}h" else "${d}d"
            h > 0 -> if (m > 0) "${h}h ${m}m" else "${h}h"
            else -> "${m}m"
        }
    }
}

private class UpdateCmd : CliktCommand(name = "update") {
    private val check by option("--check", help = "only report whether a newer release exists").flag()

    override fun run() {
        val current = dev.ccpocket.daemon.update.UpdateService.currentVersion()
        echo("current: $current")
        val latest = dev.ccpocket.daemon.update.UpdateService.latestRelease()
            ?: throw com.github.ajalt.clikt.core.CliktError("could not reach GitHub releases — check network/proxy")
        if (!dev.ccpocket.daemon.update.UpdateService.isNewer(latest.version, current)) {
            echo("already up to date (latest: ${latest.version})")
            return
        }
        echo("newer release available: ${latest.version}")
        if (check) return
        val exe = dev.ccpocket.daemon.update.UpdateService.selfExe()
        val install = dev.ccpocket.daemon.update.UpdateService.managedInstallOf(exe)
            ?: throw com.github.ajalt.clikt.core.CliktError(dev.ccpocket.daemon.update.UpdateService.ownerHint(exe))
        val newLauncher = dev.ccpocket.daemon.update.UpdateService.apply(latest, install)
        echo("installed ${latest.version} → ${install.versionsDir.resolve(latest.version)}")
        echo("restarting the background service onto it…")
        dev.ccpocket.daemon.update.UpdateService.restartService(newLauncher)
        echo("✅ updated to ${latest.version}")
    }
}

private class StatusCmd : CliktCommand(name = "status") {
    private val pairPort by option("--pair-port", help = "loopback port of the running daemon").int().default(8799)

    override fun run() = runBlocking {
        val client = HttpClient(CIO)
        var healthy = true
        echo("  version:  ${dev.ccpocket.daemon.update.UpdateService.currentVersion()}")
        try {
            // 1. daemon process + relay link (via the loopback /status the running daemon serves)
            val body = runCatching { client.get("http://127.0.0.1:$pairPort/status").bodyAsText() }.getOrNull()
            val st = body?.let { runCatching { PocketJson.decodeFromString<LoopbackStatus>(it) }.getOrNull() }
            if (st == null) {
                healthy = false
                echo("  daemon:   ✗ not reachable on 127.0.0.1:$pairPort — ${daemonStartHint()}")
            } else {
                echo("  daemon:   ✓ running (account ${st.accountId.take(8)}…, relay ${st.relay})")
                if (st.attached) {
                    echo("  relay:    ✓ attached${st.lastPongAgeMs?.let { " — liveness ${it / 1000}s ago" } ?: ""}")
                } else {
                    healthy = false
                    echo("  relay:    ✗ link down (reconnecting — backoff reaches 30s; check network/proxy to $DEFAULT_RELAY)")
                }
            }
            // 2. background service registered?
            val os = System.getProperty("os.name").lowercase()
            val service = when {
                os.contains("win") -> if (ServiceInstaller.isWindowsTaskInstalled()) "✓ logon Scheduled Task '${ServiceInstaller.WINDOWS_TASK}'" else "✗ no Scheduled Task — cc-pocket-daemon service-install --apply"
                os.contains("mac") -> {
                    val plist = java.io.File(System.getProperty("user.home"), "Library/LaunchAgents/dev.ccpocket.daemon.plist")
                    if (plist.exists()) "✓ launchd agent (${plist.path})" else "✗ no launchd agent — cc-pocket-daemon service-install --apply"
                }
                else -> {
                    val unit = java.io.File(System.getProperty("user.home"), ".config/systemd/user/cc-pocket-daemon.service")
                    if (unit.exists()) "✓ systemd user unit (${unit.path})" else "✗ no systemd unit — cc-pocket-daemon service-install --apply"
                }
            }
            echo("  service:  $service")
            // 3. agent CLIs resolvable?
            val claude = runCatching { ClaudeLauncher.resolveExecutable(null) }.getOrNull()
            echo("  claude:   ${claude?.let { "✓ $it" } ?: "✗ not found — install Claude Code first"}")
            if (claude == null) healthy = false
            val codex = runCatching { CodexLauncher.resolveExecutable(null) }.getOrNull()
            echo("  codex:    ${codex?.let { "✓ $it" } ?: "– not found (optional; Codex sessions unavailable)"}")
        } finally {
            client.close()
        }
        if (!healthy) throw com.github.ajalt.clikt.core.ProgramResult(1)
    }
}

private class ConfigCmd : CliktCommand(name = "config") {
    private val isolatedClaudeAuth by option(
        "--isolated-claude-auth",
        help = "on|off — give the daemon's claude its own login (separate CLAUDE_CONFIG_DIR; history and " +
            "settings stay shared). Fixes the terminal claude being logged out by token-refresh races " +
            "while the phone drives sessions (issue #69). Takes effect on daemon restart; sign in once " +
            "from the app afterwards (macOS — file-based credentials elsewhere migrate automatically).",
    )

    override fun run() {
        val prefs = DaemonPrefs.load()
        when (isolatedClaudeAuth?.lowercase()) {
            null -> {}
            "on", "true", "1" -> prefs.setIsolatedClaudeAuth(true)
            "off", "false", "0" -> prefs.setIsolatedClaudeAuth(false)
            else -> throw com.github.ajalt.clikt.core.CliktError("--isolated-claude-auth takes on|off")
        }
        echo("isolated-claude-auth: ${if (prefs.isolatedClaudeAuth) "on" else "off"}")
        if (isolatedClaudeAuth != null) {
            echo("restart the daemon for this to take effect — e.g.:")
            echo("  ${daemonStartHint().substringAfter("start it:  ")}")
        }
    }
}

private class ServiceInstallCmd : CliktCommand(name = "service-install") {
    private val exec by option("--exec", help = "path to the cc-pocket-daemon launcher (default: auto-detect)")
    private val relay by option("--relay").default(DEFAULT_RELAY)
    private val claudeBin by option("--claude-bin")
    private val codexBin by option("--codex-bin")
    private val apply by option("--apply", help = "actually write + load the service (default: print only)").flag()

    override fun run() {
        val launcher = exec ?: resolveLauncher()
        val runArgs = buildList {
            add("run")
            add("--relay"); add(relay)
            claudeBin?.let { add("--claude-bin"); add(it) }
            codexBin?.let { add("--codex-bin"); add(it) }
        }
        echo(ServiceInstaller.install(launcher, runArgs, apply))
    }

    /**
     * Find the launcher to put in the service's ExecStart. Prefer a stable on-PATH symlink (survives
     * `brew upgrade`); the Homebrew prefix differs by arch (/opt/homebrew on arm64, /usr/local on Intel).
     * Fall back to THIS process's own launcher (the jpackage native binary inside the .app).
     */
    private fun resolveLauncher(): String {
        listOf(
            "/opt/homebrew/bin/cc-pocket-daemon",            // Homebrew on Apple Silicon
            "/home/linuxbrew/.linuxbrew/bin/cc-pocket-daemon", // Homebrew on Linux
            "/usr/local/bin/cc-pocket-daemon",               // Homebrew on Intel / manual installs
            "/usr/bin/cc-pocket-daemon",                     // distro package / manual install on Linux
        ).firstOrNull { java.io.File(it).canExecute() }?.let { return it }
        return ProcessHandle.current().info().command().orElse("/usr/local/bin/cc-pocket-daemon")
    }
}

fun main(args: Array<String>) {
    // BEFORE the first logger materializes: slf4j-simple freezes its config at init, and bare
    // timestamp-less lines made the 07-04 observe/fork incident unreconstructable from the logs
    System.setProperty("org.slf4j.simpleLogger.showDateTime", "true")
    System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "MM-dd HH:mm:ss.SSS")
    Root().subcommands(RunCmd(), TestClientCmd(), PairCmd(), BridgesCmd(), ShareCmd(), StatusCmd(), UpdateCmd(), ConfigCmd(), ServiceInstallCmd()).main(args)
}
