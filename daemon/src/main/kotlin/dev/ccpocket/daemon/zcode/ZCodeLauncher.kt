package dev.ccpocket.daemon.zcode

import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.agent.ExecutableResolver
import dev.ccpocket.daemon.util.logger
import java.nio.file.Files
import java.nio.file.Path
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
    private val log = logger("ZCodeLauncher")
    private val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("win")
    private val envBin: String? = System.getenv("CC_POCKET_ZCODE_BIN")

    private val exeNames: List<String> =
        if (isWindows) listOf("zcode-agent.exe", "zcode.exe", "zcode.cmd", "zcode.bat", "zcode")
        else listOf("zcode-agent", "zcode")

    /** Official-bundle locations plus the conventional user/global CLI bins for each platform. */
    internal fun fallbackDirs(
        home: String = System.getProperty("user.home"),
        osName: String = System.getProperty("os.name"),
        localAppData: String? = System.getenv("LOCALAPPDATA"),
        programFiles: String? = System.getenv("ProgramFiles"),
    ): List<String> = buildList {
        val windows = osName.lowercase().contains("win")
        if (windows) {
            localAppData?.let {
                add(Path.of(it, "Programs", "ZCode", "bin").toString())
                add(Path.of(it, "Programs", "ZCode", "resources", "app", "bin").toString())
                add(Path.of(it, "Programs", "ZCode", "resources", "glm").toString())
            }
            programFiles?.let {
                add(Path.of(it, "ZCode", "bin").toString())
                add(Path.of(it, "ZCode", "resources", "app", "bin").toString())
                add(Path.of(it, "ZCode", "resources", "glm").toString())
            }
            add(Path.of(home, "AppData", "Local", "Programs", "ZCode", "bin").toString())
            add(Path.of(home, "AppData", "Local", "Programs", "ZCode", "resources", "app", "bin").toString())
            add(Path.of(home, "AppData", "Local", "Programs", "ZCode", "resources", "glm").toString())
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
        runCatching {
            ExecutableResolver.resolve(
                explicit = null,
                envBin = null,
                exeNames = exeNames,
                fallbackDirs = fallbackDirs(),
                notFound = "zcode wrapper not found",
            )
        }.getOrNull()?.let { return it }
        fallbackDirs().asSequence().map { Path.of(it, "zcode.cjs") }
            .firstOrNull { it.isRegularFile() }?.let { return it.toRealPath() }
        error(
            "zcode executable not found. Install the official ZCode desktop app, " +
                "or set CC_POCKET_ZCODE_BIN / pass --zcode-bin.",
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
            listOf(contents.resolve("ZCode.exe"), resources.parent?.parent?.resolve("ZCode.exe"))
        } else {
            listOf(contents.resolve("MacOS").resolve("ZCode"), contents.resolve("zcode"))
        }
        return candidates.filterNotNull().firstOrNull { Files.isExecutable(it) }
    }
}
