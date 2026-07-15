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
                // Validate: opencode models use "provider/model-name" format (e.g. "opencode/big-pickle",
                // "zhipuai/glm-4.5"). The old broken format "openai/gpt-5.4-mini-fast" (with slash but
                // non-existent provider) causes opencode to hang silently on resume. Strip any "openai/"
                // prefix that leaked from old configs — the model id alone is enough.
                val cleaned = model.removePrefix("openai/")
                add("--model"); add(cleaned)
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
            // No explicit stdin redirect → default PIPE mode. AgentProcess writes prompts to
            // the child's stdin via process.outputStream, so sendPrompt() can deliver follow-up
            // messages without relaunching. For the initial launch the prompt is already in argv.
            // Under launchd the daemon inherits a stripped environment — opencode (Node.js) needs
            // these to locate its state DB and config. Without them it may block on init.
            val env = environment()
            val home = System.getProperty("user.home")
            env.putIfAbsent("XDG_STATE_HOME", "$home/Library/Application Support/ai.opencode.desktop")
            env.putIfAbsent("XDG_DATA_HOME", "$home/Library/Application Support/ai.opencode.desktop")
            env.putIfAbsent("LANG", "C.UTF-8")
        }
    }
}
