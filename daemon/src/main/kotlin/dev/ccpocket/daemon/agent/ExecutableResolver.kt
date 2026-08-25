package dev.ccpocket.daemon.agent

import java.io.File
import java.nio.file.Path
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

/**
 * Resolves a CLI's real executable — shared by the agent launchers. Resolution never invokes a shell: a
 * PATH entry may be a shell function / shim that prints to stdout and corrupts the JSON stream, so we
 * resolve to a real file and prefer native binaries over shims (`#!` scripts on Unix, `.cmd`/`.bat` on
 * Windows). Search order: an explicit path, then `$envBin`, then PATH + well-known [fallbackDirs] (login
 * services / GUI launchers often start with a sanitized PATH).
 */
object ExecutableResolver {
    fun resolve(explicit: String?, envBin: String?, exeNames: List<String>, fallbackDirs: List<String>, notFound: String): Path {
        explicit?.let { return Path.of(it).toRealPath() }
        // A configured binary ($CC_POCKET_*_BIN) is AUTHORITATIVE, exactly like the --*-bin flag it mirrors:
        // it is how a machine with a broken or ambiguous PATH pins the ONE CLI that works there, so it is
        // never ranked against — and never demoted below — whatever else happens to be installed. Only a
        // value that isn't a runnable file falls through to the search below.
        envBin?.let { bin -> Path.of(bin).takeIf { it.isRunnableFile() }?.let { return it.toRealPath() } }
        val candidates = LinkedHashSet<Path>()
        val dirs = buildList {
            System.getenv("PATH")?.split(File.pathSeparator)?.forEach { if (it.isNotBlank()) add(it) }
            addAll(fallbackDirs)
            addAll(nvmVersionBins()) // issue #287: `npm i -g` under nvm is invisible to a service PATH
        }
        dirs.forEach { dir -> exeNames.forEach { name -> candidates.add(Path.of(dir, name)) } }
        val valid = candidates.filter { it.isRunnableFile() }
        // native binaries (sort key 0) before shims (1) — ACROSS directories, not just within one. The name
        // order alone (claude.exe before claude.cmd) only breaks ties inside a single dir, so an npm
        // `claude.cmd` sitting in an earlier PATH entry used to beat `%USERPROFILE%\.local\bin\claude.exe`.
        // That matters beyond tidiness: a batch shim can only be started through cmd.exe, which re-parses
        // the command line and eats argv quoting (see ClaudeLauncher.processBuilder).
        return valid.sortedBy { if (isShim(it)) 1 else 0 }.firstOrNull()?.toRealPath() ?: error(notFound)
    }

    /**
     * nvm keeps each Node under `~/.nvm/versions/node/vX.Y.Z/bin` — per-version directories that no
     * service PATH ever contains, so a `npm i -g` on an nvm machine is invisible to a launchd /
     * scheduled-task daemon (issue #287). Newest version first: after a runtime upgrade the fresh
     * install's globals must beat a stale copy left under the old version.
     */
    internal fun nvmVersionBins(home: Path = Path.of(System.getProperty("user.home"))): List<String> =
        runCatching {
            val versions = home.resolve(".nvm").resolve("versions").resolve("node")
            if (!versions.isDirectory()) return@runCatching emptyList<String>()
            versions.listDirectoryEntries()
                .filter { it.isDirectory() }
                .sortedByDescending { versionKey(it.fileName.toString()) }
                .map { it.resolve("bin").toString() }
        }.getOrDefault(emptyList())

    /** `v24.3.0` → 24_003_000. Malformed segments read as 0 so odd directory names sort last, never throw. */
    private fun versionKey(name: String): Long {
        val parts = name.removePrefix("v").split('.')
        fun seg(i: Int) = parts.getOrNull(i)?.toIntOrNull() ?: 0
        return seg(0) * 1_000_000L + seg(1) * 1_000L + seg(2)
    }

    /**
     * True for a Windows batch shim (`.cmd` / `.bat`) — the shape npm installs. Windows cannot start one
     * directly: CreateProcess spawns cmd.exe for it (explicitly by us, implicitly by the OS otherwise), and
     * that re-parse mangles arguments carrying quotes, newlines or `&<>|^`. Callers use this both to wrap
     * the launch and to refuse launches whose argv can't survive the trip.
     */
    fun isBatchShim(exe: String): Boolean =
        exe.lowercase().let { it.endsWith(".cmd") || it.endsWith(".bat") }

    private fun isShim(p: Path): Boolean = isBatchShim(p.toString()) || looksLikeScript(p)

    private fun Path.isRunnableFile(): Boolean =
        runCatching { isRegularFile() && isExecutable() }.getOrDefault(false)

    private fun looksLikeScript(p: Path): Boolean = runCatching {
        p.inputStream().use { it.readNBytes(2).contentEquals(byteArrayOf('#'.code.toByte(), '!'.code.toByte())) }
    }.getOrDefault(false)
}
