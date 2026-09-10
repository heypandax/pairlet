package dev.ccpocket.daemon.zcode

import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.agent.ExecutableResolver
import dev.ccpocket.daemon.util.logger
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile

/**
 * Resolves the CLI shipped inside the official ZCode desktop bundle and builds its persistent stdio
 * app-server command (issue #228). ZCode is desktop-first, so PATH is only one source: a daemon started
 * by launchd commonly cannot see an app bundle's `Contents/Resources/app/bin` directory at all.
 *
 * Resolution is deliberately lazy in [ZCodeBackend]. Installing ZCode is optional and a missing bundle
 * must fail only when a ZCode conversation actually starts, never while the daemon boots or lists other
 * agents' sessions.
 */
object ZCodeLauncher {
    private const val REG_TIMEOUT_SECONDS = 5L

    private val log = logger("ZCodeLauncher")
    private val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("win")
    private val envBin: String? = System.getenv("CC_POCKET_ZCODE_BIN")

    private val exeNames: List<String> =
        if (isWindows) listOf("zcode-agent.exe", "zcode.exe", "zcode.cmd", "zcode.bat", "zcode")
        else listOf("zcode-agent", "zcode")

    /** electron-builder's NSIS uninstall entries — the only non-guessing source of a custom install dir. */
    private val uninstallKeys: List<String> = listOf(
        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
        "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
        "HKLM\\Software\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
    )

    private val regValueLine =
        Regex("^(InstallLocation|DisplayIcon)\\s+REG_(?:SZ|EXPAND_SZ)\\s+(.+)$", RegexOption.IGNORE_CASE)

    /** Official-bundle locations plus the conventional user/global CLI bins for each platform. */
    internal fun fallbackDirs(
        home: String = System.getProperty("user.home"),
        osName: String = System.getProperty("os.name"),
        localAppData: String? = System.getenv("LOCALAPPDATA"),
        programFiles: String? = System.getenv("ProgramFiles"),
        programFilesX86: String? = System.getenv("ProgramFiles(x86)"),
        appData: String? = System.getenv("APPDATA"),
        registryDirs: List<String> = registryInstallLocations(osName = osName),
    ): List<String> = buildList {
        val windows = osName.lowercase().contains("win")
        if (windows) {
            // Evidence before guesses: the installer records where it actually landed, so a custom
            // directory such as D:\Apps\ZCode becomes discoverable instead of unguessable (issue #353).
            registryDirs.forEach { addAll(windowsBundleDirs(it)) }
            localAppData?.let {
                addAll(windowsBundleDirs(winJoin(it, "Programs", "ZCode")))
                addAll(scannedProgramsBundles(Path.of(it, "Programs")))
            }
            programFiles?.let { addAll(windowsBundleDirs(winJoin(it, "ZCode"))) }
            programFilesX86?.let { addAll(windowsBundleDirs(winJoin(it, "ZCode"))) }
            addAll(windowsBundleDirs(winJoin(home, "AppData", "Local", "Programs", "ZCode")))
            addAll(scannedProgramsBundles(Path.of(home, "AppData", "Local", "Programs")))
            // npm's global bin on Windows, matching DshLauncher: a service-started daemon inherits a
            // sanitized PATH and would otherwise never see a user-global CLI install.
            appData?.let { add(winJoin(it, "npm")) }
        } else {
            // macOS official DMG (system- and user-local installs). Keep both layouts: ZCode 3.x ships
            // the VS Code-style app/bin entry, while earlier bundles exposed a Resources/bin wrapper.
            add("/Applications/ZCode.app/Contents/Resources/app/bin")
            add("/Applications/ZCode.app/Contents/Resources/bin")
            add("/Applications/ZCode.app/Contents/Resources/glm")
            add(Path.of(home, "Applications", "ZCode.app", "Contents", "Resources", "app", "bin").toString())
            add(Path.of(home, "Applications", "ZCode.app", "Contents", "Resources", "bin").toString())
            add(Path.of(home, "Applications", "ZCode.app", "Contents", "Resources", "glm").toString())
            // Linux .deb/AppImage layouts plus ordinary CLI install locations.
            add("/usr/share/zcode/bin")
            add("/usr/share/ZCode/bin")
            add("/usr/share/zcode/resources/glm")
            add("/usr/share/ZCode/resources/glm")
            add("/opt/ZCode/resources/app/bin")
            add("/opt/zcode/resources/app/bin")
            add("/opt/ZCode/resources/glm")
            add("/opt/zcode/resources/glm")
            // Official Linux standalone agent installed by ZCode Server (feedback #195).
            add(Path.of(home, ".zcode", "server", "agents", "glm").toString())
            add(Path.of(home, ".local", "bin").toString())
            add("/opt/homebrew/bin")
            add("/usr/local/bin")
            add("/usr/bin")
        }
    }

    /** The three layouts an electron-builder ZCode install can expose under one install directory. */
    private fun windowsBundleDirs(base: String): List<String> = listOf(
        winJoin(base, "resources", "glm"),
        winJoin(base, "resources", "app", "bin"),
        winJoin(base, "bin"),
    )

    /** `%LOCALAPPDATA%\Programs` holds one directory per per-user install; its name is release-flavoured. */
    private fun scannedProgramsBundles(programs: Path): List<String> = runCatching {
        if (!Files.isDirectory(programs)) return emptyList()
        val bundles = Files.list(programs).use { stream ->
            stream.filter { Files.isDirectory(it) }
                .filter { it.fileName.toString().lowercase().startsWith("zcode") }
                .toList()
        }
        bundles.flatMap { dir ->
            listOf(
                dir.resolve("resources").resolve("glm").toString(),
                dir.resolve("resources").resolve("app").resolve("bin").toString(),
                dir.resolve("bin").toString(),
            )
        }
    }.getOrElse { emptyList() }

    /** Windows paths stay backslash-joined even when this code is exercised on a POSIX host (tests). */
    private fun winJoin(base: String, vararg parts: String): String =
        (listOf(base.trimEnd('\\', '/')) + parts).joinToString("\\")

    /**
     * Install directories recorded by the NSIS uninstall entries, in two passes. Pass 1 —
     * `reg query <hive> /s /f ZCode` — matches "ZCode" in key names, value names AND data, so the entry is
     * found through its DisplayName even when the bundle sits somewhere without "ZCode" in the path
     * (`D:\Apps\Zai`). It only prints the matching lines, though, so pass 2 re-queries each matched
     * uninstall subkey in full and reads InstallLocation / DisplayIcon from that. Non-Windows hosts
     * short-circuit: there is no registry to ask.
     */
    internal fun registryInstallLocations(
        query: (List<String>) -> String? = ::runReg,
        osName: String = System.getProperty("os.name"),
    ): List<String> {
        if (!osName.lowercase().contains("win")) return emptyList()
        val found = LinkedHashMap<String, String>()
        for (hive in uninstallKeys) {
            val hits = query(listOf("reg.exe", "query", hive, "/s", "/f", "ZCode")) ?: continue
            for (subkey in parseRegistrySubkeys(hits, hive)) {
                val full = query(listOf("reg.exe", "query", subkey)) ?: continue
                for (dir in parseRegistryInstallLocations(full)) found.putIfAbsent(dir.lowercase(), dir)
            }
        }
        return found.values.toList()
    }

    /** The uninstall SUBKEYS named in a `/s /f` listing: the lines that spell out a full key path one level
     *  below [hive] (reg.exe prints `HKEY_CURRENT_USER\...` long-form names; [hive] uses the short alias). */
    internal fun parseRegistrySubkeys(regOutput: String, hive: String): List<String> {
        val longHive = hive
            .replace(Regex("^HKCU", RegexOption.IGNORE_CASE), "HKEY_CURRENT_USER")
            .replace(Regex("^HKLM", RegexOption.IGNORE_CASE), "HKEY_LOCAL_MACHINE")
        return regOutput.lineSequence().map { it.trim() }
            .filter { it.startsWith("HKEY_", ignoreCase = true) }
            .filter { it.length > longHive.length && it.startsWith(longHive + "\\", ignoreCase = true) }
            .filter { !it.substring(longHive.length + 1).contains('\\') } // one level down = the app's own key
            .distinctBy { it.lowercase() }
            .toList()
    }

    /**
     * Pulls install directories out of `reg query` output. `InstallLocation` is taken as-is; `DisplayIcon`
     * contributes the directory of its executable (electron-builder writes it as `<dir>\ZCode.exe,0`).
     */
    internal fun parseRegistryInstallLocations(regOutput: String): List<String> {
        val found = LinkedHashMap<String, String>()
        for (raw in regOutput.lineSequence()) {
            val match = regValueLine.matchEntire(raw.trim()) ?: continue
            val name = match.groupValues[1]
            val value = match.groupValues[2].trim().trim('"')
            if (value.isEmpty() || value.equals("(value not set)", ignoreCase = true)) continue
            val dir = if (name.equals("InstallLocation", ignoreCase = true)) {
                value.trimEnd('\\', '/')
            } else {
                val exe = value.replace(Regex(",\\s*-?\\d+$"), "").trim().trim('"')
                exe.substringBeforeLast('\\', "")
                    .ifEmpty { exe.substringBeforeLast('/', "") }
                    .trimEnd('\\', '/')
            }
            if (dir.isNotEmpty()) found.putIfAbsent(dir.lowercase(), dir)
        }
        return found.values.toList()
    }

    /** Any failure is answered with null: a missing/blocked reg.exe must never break resolution. */
    private fun runReg(argv: List<String>): String? = runCatching {
        val proc = ProcessBuilder(argv).redirectErrorStream(true).start()
        proc.outputStream.close()
        val reader = CompletableFuture.supplyAsync { proc.inputStream.bufferedReader().use { it.readText() } }
        if (!proc.waitFor(REG_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return@runCatching null
        }
        reader.get(REG_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }.getOrNull()

    fun resolveExecutable(explicit: String? = null): Path {
        explicit?.let { return Path.of(it).toRealPath() }
        // The official 3.7.6 bundle's Resources/glm/zcode.cjs has a /usr/bin/env node shebang. Never
        // direct-exec it: launchd's PATH may select an older Node without node:sqlite. We launch it through
        // the bundle-matched Electron/Node runtime below. A real PATH wrapper still wins when one exists.
        envBin?.let { raw ->
            val path = Path.of(raw)
            if (path.isRegularFile() && (path.isExecutable() || path.fileName.toString().equals("zcode.cjs", true))) {
                return path.toRealPath()
            }
        }
        val registryDirs = registryInstallLocations()
        val dirs = fallbackDirs(registryDirs = registryDirs)
        runCatching {
            ExecutableResolver.resolve(
                explicit = null,
                envBin = null,
                exeNames = exeNames,
                fallbackDirs = dirs,
                notFound = "zcode wrapper not found",
            )
        }.getOrNull()?.let { return it }
        dirs.asSequence().map { Path.of(it, "zcode.cjs") }
            .firstOrNull { it.isRegularFile() }?.let { return it.toRealPath() }
        // Ship the evidence with the failure: a pasted-back error then states exactly where we looked.
        val registryNote =
            if (isWindows) " registry: ${registryDirs.ifEmpty { listOf("none") }.joinToString(", ")}" else ""
        error(
            "zcode executable not found. Install the official ZCode desktop app, " +
                "or set CC_POCKET_ZCODE_BIN / pass --zcode-bin. " +
                "Probed: ${dirs.joinToString(", ")}" + registryNote,
        )
    }

    /** Probe-verified ZCode 3.7.6 machine entry: a persistent newline-delimited JSON server on stdio. */
    fun buildArgs(@Suppress("UNUSED_PARAMETER") spec: AgentSpec): List<String> = listOf("app-server", "--stdio")

    fun processBuilder(exe: Path, spec: AgentSpec): ProcessBuilder {
        val exeStr = exe.toString()
        val needsShell = isWindows && ExecutableResolver.isBatchShim(exeStr)
        val packagedCjs = exe.fileName.toString().equals("zcode.cjs", ignoreCase = true)
        val electron = if (packagedCjs) packagedElectron(exe) else null
        check(!packagedCjs || electron != null) {
            "ZCode's bundled Electron runtime was not found next to $exeStr. " +
                "Point --zcode-bin / CC_POCKET_ZCODE_BIN at the complete official bundle or a native zcode-agent wrapper."
        }
        val argv = buildList {
            if (needsShell) {
                add(System.getenv("ComSpec") ?: "cmd.exe")
                add("/c")
            }
            add(electron?.toString() ?: exeStr)
            if (electron != null) add(exeStr)
            addAll(buildArgs(spec))
        }
        log.info("launch argv: ${argv.joinToString(" ")}")
        return ProcessBuilder(argv).apply {
            directory(spec.workdir.toFile())
            redirectErrorStream(false) // stdout is exclusively the NDJSON protocol
            // The official bundle exposes Resources/glm/zcode.cjs rather than a shell wrapper. Its own
            // Electron binary is the matching embedded Node runtime; this flag makes it execute the CJS
            // entry without depending on launchd's PATH or a separately-installed node.
            if (electron != null) environment()["ELECTRON_RUN_AS_NODE"] = "1"
        }
    }

    private fun packagedElectron(cjs: Path): Path? {
        val resources = cjs.parent?.parent ?: return null
        val contents = resources.parent ?: return null
        val candidates = if (isWindows) {
            // NTFS is case-insensitive but Files.isExecutable matches the exact name handed to it, so list
            // both spellings; the sole-exe scan then covers renamed/rebranded installers (issue #353).
            listOfNotNull(
                contents.resolve("ZCode.exe"),
                contents.resolve("zcode.exe"),
                contents.parent?.resolve("ZCode.exe"),
                contents.parent?.resolve("zcode.exe"),
                soleWindowsExe(contents),
            )
        } else {
            listOf(contents.resolve("MacOS").resolve("ZCode"), contents.resolve("zcode"))
        }
        return candidates.firstOrNull { Files.isExecutable(it) }
    }

    /** Only adopted when the install directory holds exactly one plausible launcher — never a guess. */
    private fun soleWindowsExe(dir: Path): Path? = runCatching {
        if (!Files.isDirectory(dir)) return null
        val exes = Files.list(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .filter { path ->
                    val name = path.fileName.toString().lowercase()
                    name.endsWith(".exe") && !name.startsWith("uninstall") && name != "elevate.exe"
                }
                .toList()
        }
        exes.singleOrNull()
    }.getOrNull()
}
