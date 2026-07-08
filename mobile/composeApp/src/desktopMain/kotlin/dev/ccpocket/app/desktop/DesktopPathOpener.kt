package dev.ccpocket.app.desktop

import dev.ccpocket.app.ui.PathOpener
import java.io.File

/**
 * Opens transcript paths on THIS machine: folders open in Finder / Explorer, files reveal inside
 * their folder (mac `open -R`; elsewhere the parent directory opens). exists() gates linkification
 * upstream, so paths from a remote machine's session simply never become links here.
 *
 * [baseDir] is the session's working directory (issue #74): a RELATIVE transcript path like
 * "10_Notes/会议/材料.md" resolves against it, exactly like the CLI does. When baseDir is null (the
 * app-root opener, before any session is open) relative paths fall back to the process cwd, which
 * won't match transcript files — so they stay inert rather than opening the wrong file. Absolute and
 * `~/` paths ignore baseDir entirely. A REMOTE session's cwd doesn't exist locally, so a relative
 * path resolved under it fails exists() and never becomes a clickable link — the "file lives on the
 * other machine" case degrades to plain text (no dead-clicking), matching [TerminalLauncher]'s
 * locality contract.
 */
class DesktopPathOpener(private val baseDir: String? = null) : PathOpener {
    private val mac = System.getProperty("os.name").lowercase().contains("mac")

    private fun expandTilde(path: String): String =
        if (path == "~" || path.startsWith("~/")) System.getProperty("user.home") + path.drop(1) else path

    // absolute / ~ paths stand alone; a relative path is anchored at the session cwd (baseDir) so it
    // opens the same file the CLI wrote about, not one under the app's own process directory.
    private fun resolve(path: String): File {
        val f = File(expandTilde(path))
        if (f.isAbsolute) return f
        val base = baseDir?.let { File(expandTilde(it)) }
        return if (base != null && base.isAbsolute) File(base, path) else f
    }

    // memoized per-opener: linkification re-runs the same transcript paths on every streamed chunk and
    // every LazyColumn re-entry, and each miss was a stat(2) on the UI thread. The cache lives on the
    // instance because the same relative string resolves differently under a different baseDir, so it
    // must not leak across sessions. Liveness precision doesn't matter (a stale link just no-ops in
    // open()); bounded so a pathological transcript can't grow it.
    private val existsCache = object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) = size > 4096
    }

    override fun exists(path: String): Boolean = synchronized(existsCache) {
        existsCache.getOrPut(path) { resolve(path).exists() }
    }

    override fun open(path: String) {
        val f = resolve(path)
        runCatching {
            when {
                mac && f.isFile -> ProcessBuilder("open", "-R", f.absolutePath).start()
                mac -> ProcessBuilder("open", f.absolutePath).start()
                f.isFile -> java.awt.Desktop.getDesktop().open(f.parentFile ?: f)
                else -> java.awt.Desktop.getDesktop().open(f)
            }
        }
    }
}
