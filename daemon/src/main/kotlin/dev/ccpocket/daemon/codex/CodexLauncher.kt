package dev.ccpocket.daemon.codex

import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.agent.ExecutableResolver
import java.io.File
import java.nio.file.Path

/**
 * Resolves the real `codex` binary and builds the `codex app-server` launch command. Mirrors
 * [dev.ccpocket.daemon.claude.ClaudeLauncher]: never goes through a shell (a PATH shim could corrupt the
 * JSON-RPC stream), prefers native binaries over `#!` script shims, and probes well-known install dirs
 * because login services / GUI launchers often start with a sanitized PATH.
 */
object CodexLauncher {
    private val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("win")
    private val envBin: String? = System.getenv("CC_POCKET_CODEX_BIN")

    private val exeNames: List<String> =
        if (isWindows) listOf("codex.exe", "codex.cmd", "codex.bat", "codex") else listOf("codex")

    private val fallbackDirs: List<String> = buildList {
        val home = System.getProperty("user.home")
        add(home + File.separator + ".local" + File.separator + "bin")
        // npm/volta/bun/deno global bins where `@openai/codex` commonly lands
        add(home + File.separator + ".npm-global" + File.separator + "bin")
        add(home + File.separator + ".volta" + File.separator + "bin")
        add(home + File.separator + ".bun" + File.separator + "bin")
        add(home + File.separator + ".deno" + File.separator + "bin")
        if (!isWindows) {
            add("/opt/homebrew/bin"); add("/usr/local/bin"); add("/usr/bin")
        }
    }

    fun resolveExecutable(explicit: String? = null): Path =
        ExecutableResolver.resolve(explicit, envBin, exeNames, fallbackDirs, "codex executable not found. Install the Codex CLI, or set CC_POCKET_CODEX_BIN / pass --codex-bin.")

    /** argv for the persistent JSON-RPC server. cwd / model / approval / sandbox are set per thread+turn, not here. */
    fun buildArgs(): List<String> = listOf("app-server")

    fun processBuilder(exe: Path, spec: AgentSpec): ProcessBuilder {
        val exeStr = exe.toString()
        val needsShell = isWindows && exeStr.lowercase().let { it.endsWith(".cmd") || it.endsWith(".bat") }
        val argv = buildList {
            if (needsShell) { add(System.getenv("ComSpec") ?: "cmd.exe"); add("/c") }
            add(exeStr)
            addAll(buildArgs())
        }
        return ProcessBuilder(argv).apply {
            directory(spec.workdir.toFile())
            redirectErrorStream(false) // keep stderr off the stdout JSON-RPC stream
        }
    }
}
