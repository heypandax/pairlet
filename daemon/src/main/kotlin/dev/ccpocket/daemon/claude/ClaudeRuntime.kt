package dev.ccpocket.daemon.claude

import java.nio.file.Path

/**
 * The launch context every AUXILIARY claude process must share with the main [ClaudeBackend] (reviewed-trust
 * §21.2 / P1-1): the owner's `--claude-bin` override, the isolated credential store (issue #69's
 * CLAUDE_CONFIG_DIR — when isolation is on, the DEFAULT store belongs to the terminal claude and must not be
 * touched), and the active API preset env (issue #113 — endpoint/token/model routing). A helper process that
 * resolves its own binary off PATH and inherits the raw environment silently diverges on all three: it dies
 * on --claude-bin-only machines, burns the terminal's personal credentials, and routes prompts around the
 * owner's chosen gateway. One value, minted beside the backend factory, keeps the three in lockstep.
 */
class ClaudeRuntime(
    private val binOverride: String?,
    val configDir: Path?,
    /** Read PER LAUNCH, like ClaudeBackend does — a preset switch applies to the next process, not a restart. */
    val presetEnv: () -> Map<String, String>?,
) {
    /** Same resolution chain as the main backend (explicit → env → PATH → fallback dirs); null = not found. */
    fun resolveExecutable(): Path? = runCatching { ClaudeLauncher.resolveExecutable(binOverride) }.getOrNull()

    /** Apply the credential store + preset env to a helper process, exactly as the main launcher would. */
    fun applyTo(env: MutableMap<String, String>) {
        env.remove("CLAUDECODE") // never look like a nested agent session
        configDir?.let { env["CLAUDE_CONFIG_DIR"] = it.toString() }
        presetEnv()?.let { ClaudeLauncher.applyPresetEnv(env, it) }
    }
}
