package dev.ccpocket.daemon.update

import dev.ccpocket.daemon.DEFAULT_RELAY
import dev.ccpocket.daemon.service.ServiceInstaller
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads back the arguments of the installed Windows logon task so an update can re-register it on a new
 * executable WITHOUT dropping the user's service-install choices (#415: --port/--pair-port for a second
 * OS user, plus --relay and --*-bin).
 *
 * Lives outside ServiceInstaller and UpdateService on purpose: both are frozen by
 * packaging/brand-compatibility.json, so the `update` command wires this in via restartWindowsService's
 * `register` seam instead.
 * The format parsed here is the hidden-launcher VBS ServiceInstaller.windows writes, where every token is
 * wrapped as `""token""` and joined by single spaces inside `cmd /c ""… >> ""log"" 2>&1""`.
 */
internal object WindowsServiceArgs {
    private const val CMD_PREFIX = "cmd /c \"\""
    private const val LOG_REDIRECT = " >> \"\""
    private const val QUOTE = "\"\""

    /** `[exec, args…]` tokens of a VBS this installer wrote, or null for anything else. */
    fun parseVbsTokens(vbsBody: String): List<String>? {
        val start = vbsBody.indexOf(CMD_PREFIX).takeIf { it >= 0 } ?: return null
        val end = vbsBody.indexOf(LOG_REDIRECT, start).takeIf { it >= 0 } ?: return null
        val cmd = vbsBody.substring(start + CMD_PREFIX.length, end)
        if (cmd.length < 4 || !cmd.startsWith(QUOTE) || !cmd.endsWith(QUOTE)) return null
        return cmd.substring(2, cmd.length - 2).split("$QUOTE $QUOTE")
    }

    /** The run args (exec stripped) of the currently installed task, if readable. */
    fun installedRunArgs(
        vbs: Path = Path.of(System.getProperty("user.home"), ".cc-pocket", "cc-pocket-daemon-service.vbs"),
    ): List<String>? {
        val text = runCatching { Files.readString(vbs) }.getOrNull() ?: return null
        return parseVbsTokens(text)?.drop(1)?.takeIf { it.firstOrNull() == "run" }
    }

    /** Installed task args when readable; otherwise the historical defaults UpdateService used. */
    fun forUpdate(installed: List<String>?, pairPort: Int): List<String> =
        installed?.takeIf { it.firstOrNull() == "run" }
            ?: listOf("run", "--relay", DEFAULT_RELAY, "--pair-port", pairPort.toString())

    /** The loopback port the re-registered daemon will claim (stop/await must watch the same one). */
    fun pairPortOf(runArgs: List<String>, fallback: Int): Int =
        runArgs.indexOf("--pair-port").takeIf { it >= 0 }?.let { runArgs.getOrNull(it + 1)?.toIntOrNull() } ?: fallback

    /** Windows `update` handoff that keeps the installed arguments; ordering stays in UpdateService. */
    fun restartKeepingArgs(newLauncher: Path, pairPort: Int, installed: List<String>? = installedRunArgs()) {
        val runArgs = forUpdate(installed, pairPort)
        UpdateService.restartWindowsService(
            newLauncher,
            pairPortOf(runArgs, pairPort),
            register = { launcher, _ ->
                val result = ServiceInstaller.windows(launcher.toString(), runArgs, apply = true, startNow = false)
                check(!result.startsWith("could not register")) { result }
            },
        )
    }
}
