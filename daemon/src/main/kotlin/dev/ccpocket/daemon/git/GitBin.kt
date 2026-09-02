package dev.ccpocket.daemon.git

import dev.ccpocket.daemon.agent.ExecutableResolver
import java.nio.file.Path

/**
 * Where `git` lives on this machine — ONE answer for every git caller in the daemon ([GitService]'s panel
 * verbs and [GitIgnoreProbe]'s browser filter). Shared because the interesting part is not the lookup but
 * the fallback directories: a launchd / scheduled-task daemon starts with a sanitized PATH that often has
 * neither Homebrew nor Git-for-Windows in it, and a second copy of that list would drift the day someone
 * fixes it in one place.
 *
 * Null, never an exception: every caller's correct answer to "there is no git here" is a readable refusal
 * (the panel) or a silent degradation (the filter), not a failed frame.
 */
internal object GitBin {

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    /** The machine-wide answer only. An [explicit] override is resolved fresh — it is per-caller
     *  configuration, and caching it here would let one caller's `--git-bin` answer for everyone. */
    @Volatile private var cached: Path? = null

    fun resolve(explicit: String? = null): Path? {
        if (explicit != null) return search(explicit)
        return cached ?: search(null)?.also { cached = it }
    }

    private fun search(explicit: String?): Path? = runCatching {
        ExecutableResolver.resolve(
            explicit, System.getenv("CC_POCKET_GIT_BIN"),
            if (isWindows) listOf("git.exe", "git.cmd", "git") else listOf("git"),
            fallbackDirs, "git executable not found",
        )
    }.getOrNull()

    private val fallbackDirs: List<String> = buildList {
        if (isWindows) {
            add("C:\\Program Files\\Git\\cmd")
            add("C:\\Program Files\\Git\\bin")
            add("C:\\Program Files (x86)\\Git\\cmd")
        } else {
            add("/opt/homebrew/bin"); add("/usr/local/bin"); add("/usr/bin"); add("/bin")
            add("/Library/Developer/CommandLineTools/usr/bin")
        }
    }
}
