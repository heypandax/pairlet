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
        add("--permission-mode"); add(spec.permissionMode ?: spec.mode.wireName())
        // GUEST/BRIDGE clean-room (issue #115): strip the owner's private machine-wide context so a scoped
        // guest's — or a chat requester's — agent can't siphon it. Emitted HERE, ahead of every argument
        // whose value is free text (resume id, model, append-system-prompt), because these are authorization
        // flags: if a later value ever mangles the command line (Windows re-parses it, see processBuilder),
        // the damage must fall on the tail, never on the flags that build the sandbox.
        if (spec.cleanRoom) {
            // NO MCP servers: --strict-mcp-config means "use only servers given by --mcp-config", and we
            // give none — so the owner's ~/.claude.json user servers AND the shared root's own .mcp.json
            // (guest-writable, often repo-committed) are both ignored, and the guest can't act through the
            // owner's already-authenticated integrations (the biggest hole). Verified on CLI 2.1.218 with a
            // server planted in the workdir's .mcp.json: the session's init reports mcp_servers=[], exactly
            // as the `--mcp-config {"mcpServers":{}}` pair this replaced did.
            //
            // That JSON literal is gone ON PURPOSE and must not come back: argv quotes do not survive the
            // trip to a Windows npm `claude.cmd` shim (cmd.exe re-parses the line and the C runtime eats
            // the quotes), so claude received `{mcpServers:{}}`, failed to read it as JSON, fell back to
            // treating it as a relative path and died with `MCP config file not found: D:\…\{mcpServers:{}}`
            // — a bridge chat on Windows could not start an agent at all. Reproduced verbatim on macOS by
            // passing the de-quoted string. Keep every clean-room token quote-free and whitespace-free.
            add("--strict-mcp-config")
            // load NO settings sources (empty = none; verified accepted by the installed CLI). NOT the `user`
            // source (~/.claude global CLAUDE.md, user skills / commands / settings) AND — crucially — NOT the
            // shared folder's own `project`/`local` .claude/settings.json either: those files live INSIDE the
            // shared root, so they are guest-writable and often repo-committed, and their permissions.allow
            // rules / hooks would let the CLI AUTO-APPROVE tools WITHOUT routing them through the daemon's
            // --permission-prompt-tool — silently bypassing the path guard + tier clamp (issue #115 crypto
            // review H2). The daemon stays the sole permission authority via --permission-prompt-tool +
            // --permission-mode; the CLI's separate login store remains available for account auth.
            // Written attached rather than as a separate empty argument for the transport reason above: a
            // standalone "" is the other shape a cmd.exe re-parse can drop, and dropping it silently restores
            // EVERY settings source — the H2 bypass, re-opened by a quoting accident. Equivalent on 2.1.218
            // (both forms cut the session's slash commands from 109 to 46, i.e. the user source is gone).
            // It also drops the user settings' `env` block — including the API route a gateway user keeps
            // there — so processBuilder re-imports that one allow-listed slice; see [CleanRoomEnv].
            add("--setting-sources=")
            // keep the owner's auto-memory paths, env vars, git status out of the guest's system prompt
            add("--exclude-dynamic-system-prompt-sections")
            // CLI 2.1.218's credential-scrub hardening (applied in processBuilder below) deliberately forces
            // its native permission mode to `default`. A restricted PLAN session must not thereby regain a
            // shell or mutation tool, so pin its AVAILABLE tools to a closed read/coordination set. This is a
            // capability wall, not a prompt convention: future/renamed tools stay absent until reviewed.
            if (spec.mode == PermissionMode.PLAN) add(CLEAN_ROOM_PLAN_TOOLS)
        }
        spec.resumeId?.let {
            add("--resume"); add(it)
            // fork into a fresh id rather than appending to the resumed transcript — guarded by resumeId
            // so we never emit --fork-session with nothing to fork (see AgentSpec.forkSession)
            if (spec.forkSession) add("--fork-session")
        }
        spec.model?.let { add("--model"); add(it) }
        spec.effort?.let { add("--effort"); add(it) }
        val appendPrompt = if (spec.cleanRoom && spec.mode == PermissionMode.PLAN) {
            listOfNotNull(CLEAN_ROOM_PLAN_PROMPT, spec.appendSystemPrompt).joinToString("\n\n")
        } else {
            // Preserve the ordinary launch exactly, including the (unusual but previously supported) empty
            // string, rather than making the clean-room compensation change owner-session argv semantics.
            spec.appendSystemPrompt
        }
        appendPrompt?.let { add("--append-system-prompt"); add(it) }
    }

    fun processBuilder(exe: Path, spec: AgentSpec, configDir: Path? = null, presetEnv: Map<String, String>? = null): ProcessBuilder {
        val exeStr = exe.toString()
        // Windows can't CreateProcess a .cmd/.bat directly — those must run through cmd.exe, which re-parses
        // the command line. Clean-room controls therefore use quote-free single-token forms (see buildArgs).
        // A native .exe (the installer's claude.exe) runs directly, same as the Unix binary, and the resolver
        // prefers it over an npm shim wherever both are installed.
        val needsShell = isWindows && ExecutableResolver.isBatchShim(exeStr)
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
            // GUEST/BRIDGE clean room: --setting-sources= also drops the user settings' `env` API route, so a
            // gateway machine's restricted launch had no usable credential and every turn came back as the
            // CLI's <synthetic> API failure. Re-import that ONE allow-listed transport slice (and only it),
            // then stop it spreading to Bash/hooks/MCP. Runs AFTER the preset so an active preset still wins.
            if (spec.cleanRoom) CleanRoomEnv.applyTo(environment(), presetActive = presetEnv != null, userConfigDir = configDir)
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

    /** Attached, quote-free tokens so the restrictions survive a Windows `.cmd` re-parse. */
    internal const val CLEAN_ROOM_PLAN_TOOLS = "--tools=Read,Glob,Grep,AskUserQuestion,TodoWrite"
    internal const val CLEAN_ROOM_PLAN_PROMPT =
        "Operate in read-only plan mode. Analyze and propose a plan only; do not modify files or execute commands."
}

/** The CLI flag value for a permission mode (single source of truth = the @SerialName). */
internal fun PermissionMode.wireName(): String = PocketJson.encodeToString(this).trim('"')
