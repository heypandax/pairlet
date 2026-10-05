package dev.ccpocket.daemon

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import dev.ccpocket.daemon.control.LocalControlClient

/**
 * Base of the commands that talk to the daemon's token-authenticated local control API (`pairlet agent …`).
 * Everything here is a thin shell over that API: no business rule is re-implemented, so the CLI, a Skill
 * and any future UI cannot disagree about what a command means.
 *
 * Every command talks ONLY to an already-running daemon and fails cleanly when there isn't one. None
 * of them can start a second daemon — that is the failure mode this project pays for most (AGENTS.md).
 */
internal abstract class LocalCmd(name: String, private val helpLine: String) : CliktCommand(name = name) {
    override fun help(context: Context) = helpLine

    protected val pairPort by option("--pair-port", help = "loopback port of the running daemon").int().default(8799)
    protected val json by option("--json", help = "print the machine-readable JSON reply instead of human text").flag()

    protected val client: LocalControlClient get() = LocalControlClient(pairPort, daemonStartHintText(), jsonErrors = json)
}

/** Mirrors Main's `daemonStartHint()` — the per-OS way to bring the daemon back up. */
internal fun daemonStartHintText(): String {
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.contains("win") -> "start it:  schtasks /Run /TN ${dev.ccpocket.daemon.service.ServiceInstaller.WINDOWS_TASK}    (or: pairlet run)"
        os.contains("mac") -> "start it:  launchctl kickstart -k gui/\$(id -u)/dev.ccpocket.daemon    (or: pairlet run)"
        else -> "start it:  systemctl --user start cc-pocket-daemon    (or: pairlet run)"
    }
}
