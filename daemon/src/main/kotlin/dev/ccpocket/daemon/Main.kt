package dev.ccpocket.daemon

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import dev.ccpocket.daemon.claude.ClaudeLauncher
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.relay.LoopbackPair
import dev.ccpocket.daemon.relay.PairLoopback
import dev.ccpocket.daemon.relay.RelayClient
import dev.ccpocket.daemon.server.DaemonServer
import dev.ccpocket.daemon.service.ServiceInstaller
import dev.ccpocket.daemon.util.QrTerminal
import dev.ccpocket.protocol.PocketJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
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

private class Root : CliktCommand(name = "cc-pocket-daemon") {
    override fun run() = Unit
}

private class RunCmd : CliktCommand(name = "run") {
    private val host by option().default("127.0.0.1")
    private val port by option().int().default(8765)
    private val claudeBin by option("--claude-bin", help = "claude executable (default: auto-detect the installed Claude Code)")
    private val relay by option("--relay", help = "relay wss base").default(DEFAULT_RELAY)
    private val local by option("--local", help = "run a LAN-only WebSocket server instead of dialing the relay").flag()
    private val pairPort by option("--pair-port", help = "loopback port for the `pair` command").int().default(8799)

    override fun run() {
        val exe = ClaudeLauncher.resolveExecutable(claudeBin)
        val core = DaemonCore(exe)
        if (!local) {
            val identity = Identity.loadOrCreate()
            val relayClient = RelayClient(relay, identity, core)
            echo("cc-pocket daemon — claude=$exe — relay=$relay")
            echo("account id: ${identity.accountId}")
            echo("(run `cc-pocket-daemon pair` in another terminal to add a phone)")
            PairLoopback(relayClient, relay, identity.e2ePubB64, pairPort).start()
            Runtime.getRuntime().addShutdownHook(Thread { runBlocking { core.shutdown() } })
            runBlocking { relayClient.run() }
        } else {
            // Advertise a LAN URL + QR for phone pairing, but NEVER silently widen the bind:
            // we listen on `host` as-is. The direct-LAN path has no handshake / auth / E2E, so a
            // phone can only reach us once the user explicitly binds beyond loopback — show the
            // pairing URL/QR only then, never for a loopback bind the phone can't connect to.
            val lan = lanIp()
            echo("cc-pocket daemon — claude=$exe")
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

private class PairCmd : CliktCommand(name = "pair") {
    private val pairPort by option("--pair-port", help = "loopback port of the running daemon").int().default(8799)

    override fun run() = runBlocking {
        val client = HttpClient(CIO)
        try {
            val body = runCatching { client.post("http://127.0.0.1:$pairPort/pair").bodyAsText() }.getOrElse {
                echo("could not reach the running daemon on 127.0.0.1:$pairPort — is `run --relay` up?"); return@runBlocking
            }
            val info = runCatching { PocketJson.decodeFromString<LoopbackPair>(body) }.getOrNull()
            if (info == null) {
                echo("pairing failed: $body")
            } else {
                echo("")
                echo("  Open CC Pocket on your phone and scan this — or type the code (valid ${info.ttlSec}s):")
                echo("")
                echo(QrTerminal.render("ccpocket://pair?code=${info.code}"))
                echo("        code:  ${info.code.chunked(3).joinToString(" ")}")
                echo("")
            }
        } finally {
            client.close()
        }
    }
}

private class ServiceInstallCmd : CliktCommand(name = "service-install") {
    private val exec by option("--exec", help = "path to the cc-pocket-daemon launcher (default: auto-detect)")
    private val relay by option("--relay").default(DEFAULT_RELAY)
    private val claudeBin by option("--claude-bin")
    private val apply by option("--apply", help = "actually write + load the service (default: print only)").flag()

    override fun run() {
        val launcher = exec ?: resolveLauncher()
        val runArgs = buildList {
            add("run")
            add("--relay"); add(relay)
            claudeBin?.let { add("--claude-bin"); add(it) }
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

fun main(args: Array<String>) =
    Root().subcommands(RunCmd(), TestClientCmd(), PairCmd(), ServiceInstallCmd()).main(args)
