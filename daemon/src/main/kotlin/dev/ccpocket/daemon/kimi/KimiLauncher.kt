package dev.ccpocket.daemon.kimi

import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.agent.ExecutableResolver
import dev.ccpocket.daemon.util.logger
import java.io.File
import java.nio.file.Path

/**
 * Resolves the real `kimi` binary and builds the `kimi acp` launch command (issue #206). Mirrors
 * [dev.ccpocket.daemon.codex.CodexLauncher]: never goes through a shell (a PATH shim could corrupt the
 * JSON-RPC stream), and probes well-known install dirs because login services / GUI launchers often start
 * with a sanitized PATH. The official installer drops the binary under `$KIMI_CODE_HOME/bin` (default
 * `~/.kimi-code/bin`), so that dir is probed too.
 *
 * PROTOCOL: `kimi acp` = an ACP (Agent Client Protocol v1) server over stdio. The design assumed a
 * `kimi --wire` mode, but the shipped CLI (0.33.0) has no such flag; `acp` is its complete machine
 * interface (session new/load/resume/fork + permission requests — confirmed by the initialize handshake).
 * Session id, model and permission behavior are negotiated over ACP per-session, NOT via launch flags, so
 * the argv is just `acp` (cwd comes from the process directory).
 *
 * The target is the NEW Kimi Code CLI (TypeScript single binary), not the legacy Python `kimi-cli` which
 * shares the same `kimi` name — a machine may have the old one; the open-failure message points that out.
 */
object KimiLauncher {
    private val log = logger("KimiLauncher")
    private val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("win")
    private val envBin: String? = System.getenv("CC_POCKET_KIMI_BIN")

    // V-win: the Windows exe suffix (.exe vs .cmd) isn't confirmed yet — probe the common ones.
    private val exeNames: List<String> =
        if (isWindows) listOf("kimi.exe", "kimi.cmd", "kimi.bat", "kimi") else listOf("kimi")

    private val fallbackDirs: List<String> = buildList {
        val home = System.getProperty("user.home")
        // official install.sh default: $KIMI_CODE_HOME/bin
        val kimiHome = System.getenv("KIMI_CODE_HOME")?.let { it } ?: (home + File.separator + ".kimi-code")
        add(kimiHome + File.separator + "bin")
        add(home + File.separator + ".local" + File.separator + "bin")
        // npm/volta/bun/deno global bins (an npm package is also published)
        add(home + File.separator + ".npm-global" + File.separator + "bin")
        add(home + File.separator + ".volta" + File.separator + "bin")
        add(home + File.separator + ".bun" + File.separator + "bin")
        add(home + File.separator + ".deno" + File.separator + "bin")
        if (!isWindows) {
            add("/opt/homebrew/bin"); add("/usr/local/bin"); add("/usr/bin")
        }
    }

    fun resolveExecutable(explicit: String? = null): Path =
        ExecutableResolver.resolve(
            explicit, envBin, exeNames, fallbackDirs,
            "kimi executable not found. Install the NEW Kimi Code CLI " +
                "(curl -fsSL https://code.kimi.com/kimi-code/install.sh | bash), " +
                "or set CC_POCKET_KIMI_BIN / pass --kimi-bin.",
        )

    /** argv for the persistent ACP server. Resume/model/permission-mode are ACP per-session params, not
     *  launch flags — the backend sends them in session/new / session/load. */
    fun buildArgs(@Suppress("UNUSED_PARAMETER") spec: AgentSpec): List<String> = listOf("acp")

    fun processBuilder(exe: Path, spec: AgentSpec): ProcessBuilder {
        val exeStr = exe.toString()
        val needsShell = isWindows && exeStr.lowercase().let { it.endsWith(".cmd") || it.endsWith(".bat") }
        val argv = buildList {
            if (needsShell) { add(System.getenv("ComSpec") ?: "cmd.exe"); add("/c") }
            add(exeStr)
            addAll(buildArgs(spec))
        }
        log.info("launch argv: ${argv.joinToString(" ") { a -> if (a.length > 40) a.take(20) + "…" else a }}")
        return ProcessBuilder(argv).apply {
            directory(spec.workdir.toFile())
            redirectErrorStream(false) // keep stderr off the stdout JSON-RPC stream
        }
    }
}
