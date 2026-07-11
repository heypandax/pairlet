package dev.ccpocket.daemon.claude

import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.agent.ExecutableResolver
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.PresetEnv
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Path

/** Resolves the real `claude` binary and builds the launch command — pure, no side effects. */
object ClaudeLauncher {

    private val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("win")

    private val envBin: String? = System.getenv("CC_POCKET_CLAUDE_BIN")

    // Executable basenames to probe in each PATH / fallback dir. On Windows a bare `claude` is not
    // directly runnable: the native installer drops `claude.exe` under ~\.local\bin (not on PATH by
    // default) and npm installs `claude.cmd`, so we must try the suffixed names — preferring .exe.
    private val exeNames: List<String> =
        if (isWindows) listOf("claude.exe", "claude.cmd", "claude.bat", "claude") else listOf("claude")

    // Well-known install dirs to search when PATH lacks claude (login services / GUI launchers often
    // start with a sanitized PATH). ~/.local/bin is the native installer's target on every OS.
    private val fallbackDirs: List<String> = buildList {
        add(System.getProperty("user.home") + File.separator + ".local" + File.separator + "bin")
        if (!isWindows) {
            add("/opt/homebrew/bin"); add("/usr/local/bin"); add("/usr/bin")
        }
    }

    /** Resolve the real `claude` executable (shared resolver: explicit → $CC_POCKET_CLAUDE_BIN → PATH → fallback dirs). */
    fun resolveExecutable(explicit: String? = null): Path =
        ExecutableResolver.resolve(explicit, envBin, exeNames, fallbackDirs, "claude executable not found. Set CC_POCKET_CLAUDE_BIN or pass --claude-bin.")

    /** Build the argv. `-p` is mandatory for headless stream-json (else claude ignores stdin turns). */
    fun buildArgs(spec: AgentSpec): List<String> = buildList {
        add("-p")
        add("--output-format"); add("stream-json")
        add("--input-format"); add("stream-json")
        add("--permission-prompt-tool"); add("stdio")
        add("--replay-user-messages")
        add("--verbose")
        // ALWAYS pass the mode: omitting it lets claude fall back to the user's global
        // `permissions.defaultMode` (e.g. "auto"), silently breaking the phone's "Ask each step"
        add("--permission-mode"); add(spec.mode.wireName())
        spec.resumeId?.let {
            add("--resume"); add(it)
            // fork into a fresh id rather than appending to the resumed transcript — guarded by resumeId
            // so we never emit --fork-session with nothing to fork (see AgentSpec.forkSession)
            if (spec.forkSession) add("--fork-session")
        }
        spec.model?.let { add("--model"); add(it) }
        spec.effort?.let { add("--effort"); add(it) }
        spec.appendSystemPrompt?.let { add("--append-system-prompt"); add(it) }
    }

    fun processBuilder(exe: Path, spec: AgentSpec, configDir: Path? = null, presetEnv: Map<String, String>? = null): ProcessBuilder {
        val exeStr = exe.toString()
        // Windows can't CreateProcess a .cmd/.bat directly — those must run through cmd.exe. A native
        // .exe (the installer's claude.exe) runs directly, same as the Unix binary.
        val needsShell = isWindows && exeStr.lowercase().let { it.endsWith(".cmd") || it.endsWith(".bat") }
        val argv = buildList {
            if (needsShell) { add(System.getenv("ComSpec") ?: "cmd.exe"); add("/c") }
            add(exeStr)
            addAll(buildArgs(spec))
        }
        return ProcessBuilder(argv).apply {
            directory(spec.workdir.toFile())
            redirectErrorStream(false) // keep stderr off the stdout JSON stream
            environment().remove("CLAUDECODE") // avoid nested-session detection
            // credential isolation (issue #69): the daemon's claude gets its own login store so its
            // OAuth refreshes can't rotate the terminal claude's token out from under it
            configDir?.let { environment()["CLAUDE_CONFIG_DIR"] = it.toString() }
            // API preset (issue #113): the active preset's endpoint/token/model routing for THIS launch —
            // read per launch, so a switch applies to new sessions while running ones keep their env
            presetEnv?.let { applyPresetEnv(environment(), it) }
        }
    }

    /**
     * Inject the active preset's env: scrub EVERY var a preset may own first, then apply. Scrubbing
     * matters — the daemon's own environment may carry e.g. a stale ANTHROPIC_API_KEY, and with the
     * preset setting ANTHROPIC_AUTH_TOKEN both would reach the CLI and fight over precedence. With
     * no preset active the environment passes through untouched (existing API-key users unaffected).
     */
    internal fun applyPresetEnv(env: MutableMap<String, String>, preset: Map<String, String>) {
        PresetEnv.SCRUBBED.forEach(env::remove)
        env.putAll(preset)
    }
}

/** The CLI flag value for a permission mode (single source of truth = the @SerialName). */
internal fun PermissionMode.wireName(): String = PocketJson.encodeToString(this).trim('"')
