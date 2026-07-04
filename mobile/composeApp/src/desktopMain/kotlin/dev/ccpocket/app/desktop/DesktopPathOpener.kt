package dev.ccpocket.app.desktop

import dev.ccpocket.app.ui.PathOpener
import java.io.File

/**
 * Opens transcript paths on THIS machine: folders open in Finder / Explorer, files reveal inside
 * their folder (mac `open -R`; elsewhere the parent directory opens). exists() gates linkification
 * upstream, so paths from a remote machine's session simply never become links here.
 */
object DesktopPathOpener : PathOpener {
    private val mac = System.getProperty("os.name").lowercase().contains("mac")

    private fun resolve(path: String): File =
        File(if (path == "~" || path.startsWith("~/")) System.getProperty("user.home") + path.drop(1) else path)

    // memoized: linkification re-runs the same transcript paths on every streamed chunk and every
    // LazyColumn re-entry, and each miss was a stat(2) on the UI thread. Liveness precision doesn't
    // matter here (a stale link just no-ops in open()); bounded so a pathological transcript can't grow it.
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
