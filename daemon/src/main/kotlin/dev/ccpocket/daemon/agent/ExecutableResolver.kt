package dev.ccpocket.daemon.agent

import java.io.File
import java.nio.file.Path
import kotlin.io.path.inputStream
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile

/**
 * Resolves a CLI's real executable — shared by ClaudeLauncher and CodexLauncher. NEVER goes through a
 * shell: a PATH entry may be a shell function / shim that prints to stdout and corrupts the JSON stream,
 * so we resolve to a real file and prefer native binaries over `#!`-script shims. Search order: an
 * explicit path, then `$envBin`, then PATH + well-known [fallbackDirs] (login services / GUI launchers
 * often start with a sanitized PATH).
 */
object ExecutableResolver {
    fun resolve(explicit: String?, envBin: String?, exeNames: List<String>, fallbackDirs: List<String>, notFound: String): Path {
        explicit?.let { return Path.of(it).toRealPath() }
        val candidates = LinkedHashSet<Path>()
        envBin?.let { candidates.add(Path.of(it)) }
        val dirs = buildList {
            System.getenv("PATH")?.split(File.pathSeparator)?.forEach { if (it.isNotBlank()) add(it) }
            addAll(fallbackDirs)
        }
        dirs.forEach { dir -> exeNames.forEach { name -> candidates.add(Path.of(dir, name)) } }
        val valid = candidates.filter { runCatching { it.isRegularFile() && it.isExecutable() }.getOrDefault(false) }
        // native binaries (sort key 0) before script shims (1)
        return valid.sortedBy { if (looksLikeScript(it)) 1 else 0 }.firstOrNull()?.toRealPath() ?: error(notFound)
    }

    private fun looksLikeScript(p: Path): Boolean = runCatching {
        p.inputStream().use { it.readNBytes(2).contentEquals(byteArrayOf('#'.code.toByte(), '!'.code.toByte())) }
    }.getOrDefault(false)
}
