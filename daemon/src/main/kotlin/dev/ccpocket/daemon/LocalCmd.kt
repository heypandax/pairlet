package dev.ccpocket.daemon

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import dev.ccpocket.daemon.control.LocalActionRes
import dev.ccpocket.daemon.control.LocalControlClient
import dev.ccpocket.daemon.control.LocalIdReq
import dev.ccpocket.protocol.PocketJson
import kotlinx.serialization.KSerializer

/**
 * `pairlet collaborator …` and `pairlet review …` — the CLI half of the
 * ReviewRequest M1 loop (REVIEW-REQUEST.md §4). Everything here is a thin shell over the daemon's
 * token-authenticated local control API: no business rule is re-implemented, so the CLI, a Skill and
 * any future UI cannot disagree about what a command means.
 *
 * Every command talks ONLY to an already-running daemon and fails cleanly when there isn't one. None
 * of them can start a second daemon — that is the failure mode this project pays for most (AGENTS.md).
 *
 * `--json` prints one stable object carrying `ok` and the entity; a failure exits non-zero with a
 * machine-readable `code` in the error. Skills read those fields — the human text is deliberately not
 * a parseable contract.
 */
internal abstract class LocalCmd(name: String, private val helpLine: String) : CliktCommand(name = name) {
    override fun help(context: Context) = helpLine

    protected val pairPort by option("--pair-port", help = "loopback port of the running daemon").int().default(8799)
    protected val json by option("--json", help = "print the machine-readable JSON reply instead of human text").flag()

    protected val client: LocalControlClient get() = LocalControlClient(pairPort, daemonStartHintText(), jsonErrors = json)

    /** Print [value] as JSON — the `--json` contract — and report whether that was all that was asked. */
    protected fun <T> emitJson(serializer: KSerializer<T>, value: T): Boolean {
        if (!json) return false
        echo(PocketJson.encodeToString(serializer, value))
        return true
    }

    /** POST an id-only recipient action and report it. */
    protected suspend fun act(path: String, id: String) {
        val res = client.post(path, LocalIdReq.serializer(), LocalIdReq(id), LocalActionRes.serializer())
        if (emitJson(LocalActionRes.serializer(), res)) return
        report(res)
    }

    /** The honest two-state answer: this daemon has recorded what you want, and it has either reached
     *  them or is queued until it can. Never claim the colleague knows when only your disk does. */
    protected fun report(res: LocalActionRes) {
        if (!res.queued) echo("already ${res.status} — nothing to send.")
        else echo("✓ ${res.status} recorded for ${res.id} — queued for their daemon (it retries until they confirm).")
    }

    /** Peer-supplied bullets, behind the untrusted gutter and flattened to one line each. */
    protected fun bullets(label: String, items: List<String>) {
        if (items.isEmpty()) return
        echo("    │ $label:")
        items.forEach { echo("    │   - ${inline(it)}") }
    }

    /** A block of peer PROSE, every line behind the gutter so it cannot forge the CLI's own layout. */
    protected fun quoted(label: String, text: String) {
        echo("    │ $label:")
        text.lineSequence().forEach { echo("    │   ${inline(it)}") }
    }
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
