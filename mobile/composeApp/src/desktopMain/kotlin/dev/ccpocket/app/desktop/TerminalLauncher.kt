package dev.ccpocket.app.desktop

import java.io.File

/** A terminal we can hand a working directory to. Adding support for another one = a new entry here
 *  plus a launch arm in [TerminalLauncher.open] (issue #44). */
enum class TerminalApp(val id: String, val label: String) {
    SYSTEM("system", "System terminal"),
    GHOSTTY("ghostty", "Ghostty"),
    ;

    companion object {
        fun fromId(id: String?): TerminalApp = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/**
 * Opens a terminal window at a session's working directory on THIS machine. Same locality contract as
 * [DesktopPathOpener]: canOpen() checks the directory exists locally, so a remote machine's session
 * simply never shows the button — no fleet-awareness needed here.
 */
object TerminalLauncher {
    private val os = System.getProperty("os.name").lowercase()
    private val mac = os.contains("mac")
    private val win = os.contains("win")

    // internal: the embedded terminal engine (issue #153) resolves the same "~" the same way
    internal fun resolve(path: String): File =
        File(if (path == "~" || path.startsWith("~/")) System.getProperty("user.home") + path.drop(1) else path)

    fun canOpen(dir: String?): Boolean = dir != null && resolve(dir).isDirectory

    fun installed(app: TerminalApp): Boolean = when (app) {
        TerminalApp.SYSTEM -> true
        TerminalApp.GHOSTTY -> when {
            mac -> File("/Applications/Ghostty.app").isDirectory ||
                File(System.getProperty("user.home"), "Applications/Ghostty.app").isDirectory
            else -> onPath("ghostty")
        }
    }

    fun open(app: TerminalApp, dir: String) {
        val d = resolve(dir).takeIf { it.isDirectory } ?: return
        // a picked terminal that was uninstalled since falls back to the system one instead of a silent no-op
        val target = if (installed(app)) app else TerminalApp.SYSTEM
        runCatching {
            when {
                mac && target == TerminalApp.GHOSTTY ->
                    // -n: Ghostty is single-instance; without it a running Ghostty gets focused but no new window
                    ProcessBuilder("open", "-na", "Ghostty", "--args", "--working-directory=${d.absolutePath}").start()
                mac -> ProcessBuilder("open", "-a", "Terminal", d.absolutePath).start()
                win -> ProcessBuilder("cmd", "/c", "start", "cmd", "/K", "cd /d ${d.absolutePath}").directory(d).start()
                target == TerminalApp.GHOSTTY -> ProcessBuilder("ghostty", "--working-directory=${d.absolutePath}").start()
                else -> linuxTerminal()?.let { ProcessBuilder(it).directory(d).start() }
            }
        }
    }

    private fun linuxTerminal(): String? =
        listOf("x-terminal-emulator", "gnome-terminal", "konsole", "xterm").firstOrNull(::onPath)

    private fun onPath(bin: String): Boolean =
        (System.getenv("PATH") ?: "").split(File.pathSeparator).any { File(it, bin).canExecute() }
}
