package dev.ccpocket.daemon.opencode

import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.agent.ExecutableResolver
import dev.ccpocket.daemon.util.logger
import java.io.File
import java.nio.file.Path

/**
 * Resolves the real `opencode` binary and builds the `opencode run --format json` launch command.
 * Mirrors [dev.ccpocket.daemon.codex.CodexLauncher]: never goes through a shell (a PATH shim could corrupt
 * the JSON stream), prefers native binaries over `#!` script shims, and probes well-known install dirs
 * because login services / GUI launchers often start with a sanitized PATH.
 */
object OpenCodeLauncher {
    private val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("win")
    private val envBin: String? = System.getenv("CC_POCKET_OPENCODE_BIN")
    private val log = logger("OpenCodeLauncher")

    private val exeNames: List<String> =
        if (isWindows) listOf("opencode.exe", "opencode.cmd", "opencode.bat", "opencode") else listOf("opencode")

    private val fallbackDirs: List<String> = buildList {
        val home = System.getProperty("user.home")
        add(home + File.separator + ".local" + File.separator + "bin")
        add(home + File.separator + ".npm-global" + File.separator + "bin")
        add(home + File.separator + ".volta" + File.separator + "bin")
        add(home + File.separator + ".bun" + File.separator + "bin")
        if (!isWindows) {
            add("/opt/homebrew/bin"); add("/usr/local/bin"); add("/usr/bin")
        }
    }

    fun resolveExecutable(explicit: String? = null): Path =
        ExecutableResolver.resolve(explicit, envBin, exeNames, fallbackDirs,
            "opencode executable not found. Install OpenCode, or set CC_POCKET_OPENCODE_BIN / pass --opencode-bin.")

    fun processBuilder(exe: Path, spec: AgentSpec): ProcessBuilder {
        val argv = buildList {
            add(exe.toString())
            add("run")
            add("--format"); add("json")
            spec.model?.let { model ->
                // opencode models are "provider/model-name" (e.g. "opencode/big-pickle", "zhipuai/glm-4.5");
                // a bare id makes `opencode run` hang silently on a resumed session, so refuse it here —
                // the failure surfaces as a clear agent_unavailable instead of a 45s watchdog kill.
                // No --model at all (model == null) is valid: opencode falls back to its own configured default.
                require("/" in model) { "OpenCode model must use provider/model format, got '$model'" }
                add("--model"); add(model)
            }
            spec.resumeId?.let { add("--session"); add(it) }
            spec.effort?.let { add("--variant"); add(it) }
            add("--auto") // auto-approve all tool calls (no interactive permission protocol)
            // Pass the initial prompt as a positional CLI arg (opencode run [message])
            spec.initialPrompt?.let { add(it) }
        }
        log.info("launch argv: ${argv.joinToString(" ") { a -> if (a.length > 40) a.take(20) + "…" else a }}")
        return ProcessBuilder(argv).apply {
            directory(spec.workdir.toFile())
            redirectErrorStream(false) // keep stderr off the stdout JSON stream
            redirectInput(ProcessBuilder.Redirect.from(File(if (isWindows) "NUL" else "/dev/null")))
            // Under launchd the daemon inherits a stripped environment — opencode (Node.js) needs
            // XDG_DATA_HOME to locate its state DB (same resolution as OpenCodePaths.dataRoot(), the
            // spec default ~/.local/share). ONLY the data dir, and only to its SPEC DEFAULT: forcing
            // XDG_STATE_HOME to a non-default value made the daemon-launched opencode resolve state
            // somewhere the user's terminal opencode doesn't → split session DBs → "Session not found"
            // on every cross-launch resume.
            val env = environment()
            val home = System.getProperty("user.home")
            env.putIfAbsent("XDG_DATA_HOME", "$home/.local/share")
            env.putIfAbsent("LANG", "C.UTF-8")
        }
    }
}
