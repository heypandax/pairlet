package dev.ccpocket.daemon

import dev.ccpocket.daemon.util.logger
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.system.exitProcess

/**
 * The daemon owns singleton resources: the pair-loopback port (127.0.0.1:<pairPort>) AND one relay
 * identity. A second `run` would (a) crash on BindException binding the port, and (b) attach to the
 * relay under the SAME account as the first — the relay can't tell them apart, so the phone's routing
 * flaps between two backends ("can't fetch list", turns half-work). This happens whenever the Homebrew
 * cask's KeepAlive LaunchAgent is up and you also start a dev build (or two agents got registered).
 *
 * So before we bind/attach: if the port is already held, either take over (stop the other) or exit
 * cleanly — never run a duplicate. Checked by a loopback connect probe (cheap, no bind, no race window
 * of our own); the rare simultaneous-start case is otherwise prevented by keeping a single LaunchAgent.
 */
object SingleInstance {
    private val log = logger("SingleInstance")

    fun ensureSolo(pairPort: Int, takeover: Boolean, echo: (String) -> Unit) {
        if (!portInUse(pairPort)) return
        if (takeover) {
            echo("another cc-pocket daemon already holds 127.0.0.1:$pairPort — stopping it and taking over")
            if (stopRunning(pairPort)) return
            echo("could not free 127.0.0.1:$pairPort — the running daemon didn't exit; aborting")
            exitProcess(69) // EX_UNAVAILABLE
        }
        echo("another cc-pocket daemon is already running (holds 127.0.0.1:$pairPort) — leaving it alone, exiting.")
        echo("  two daemons would fight over the port and connect to the relay under one account.")
        echo("  to run THIS build instead: stop the other one first, or re-run with --takeover.")
        exitProcess(0) // not a failure: a daemon IS running, just not this instance
    }

    /** True iff something accepts a loopback TCP connection on [port] — i.e. a daemon is already listening. */
    internal fun portInUse(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 400) }
        true
    }.getOrDefault(false)

    /** Stop the current daemon and wait for its singleton port to be released. Used by both `--takeover`
     *  and the Windows updater: the Windows Scheduled Task launches through a detached WScript, so ending
     *  the task itself does not end the daemon process that WScript already spawned. */
    internal fun stopRunning(port: Int, attempts: Int = 50, waitMs: Long = 100): Boolean {
        if (!portInUse(port)) return true
        killHolders(port)
        repeat(attempts) {
            if (!portInUse(port)) return true
            if (waitMs > 0) Thread.sleep(waitMs)
        }
        return !portInUse(port)
    }

    /** Wait for a newly started daemon to claim its loopback singleton port. */
    internal fun waitUntilRunning(port: Int, attempts: Int = 200, waitMs: Long = 100): Boolean {
        repeat(attempts) {
            if (portInUse(port)) return true
            if (waitMs > 0) Thread.sleep(waitMs)
        }
        return portInUse(port)
    }

    /** Best-effort stop of whatever holds [port] (the other daemon): lsof on macOS/Linux, netstat+taskkill on Windows. */
    private fun killHolders(port: Int) {
        val win = System.getProperty("os.name").lowercase().contains("win")
        runCatching {
            val pids = if (win) {
                val out = ProcessBuilder("cmd", "/c", "netstat -ano -p tcp | findstr :$port")
                    .start().inputStream.bufferedReader().readText()
                windowsListeningPids(out, port)
            } else {
                ProcessBuilder("lsof", "-ti", "tcp:$port")
                    .start().inputStream.bufferedReader().readText().trim().split("\n").filter { it.isNotBlank() }.toSet()
            }
            pids.forEach { pid ->
                if (win) ProcessBuilder("taskkill", "/PID", pid, "/T", "/F").start().waitFor()
                else ProcessBuilder("kill", pid).start().waitFor()
            }
        }.onFailure { log.warn("takeover: couldn't enumerate/stop the port holder: ${it.message}") }
    }

    /** Parse only LISTENING rows whose LOCAL endpoint is exactly [port]. `findstr :8799` can also return
     *  a connection whose remote endpoint happens to use that port; killing that row's PID would stop an
     *  unrelated process during an update. Handles both IPv4 (`127.0.0.1:8799`) and IPv6 (`[::1]:8799`). */
    internal fun windowsListeningPids(netstat: String, port: Int): Set<String> = netstat.lineSequence()
        .map { it.trim().split(Regex("\\s+")) }
        .filter { it.size >= 5 && it[0].equals("TCP", ignoreCase = true) }
        .filter { it[3].equals("LISTENING", ignoreCase = true) }
        .filter { it[1].substringAfterLast(':').toIntOrNull() == port }
        .mapNotNull { it[4].takeIf { pid -> pid.all(Char::isDigit) } }
        .toSet()
}
