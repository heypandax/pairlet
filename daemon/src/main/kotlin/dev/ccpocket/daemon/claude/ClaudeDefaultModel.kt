package dev.ccpocket.daemon.claude

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Path

/**
 * Best-effort resolve of the model `claude` uses when a session starts with NO explicit `--model` — so a
 * brand-new session's header can show the real model BEFORE the first turn instead of a blank segment
 * (issue #96; lazy start #61 spawns no process pre-first-prompt, so there is no init to name it yet).
 *
 * Reads config files ONLY (never launches claude) and NEVER throws — every path is wrapped so a failed
 * eager resolve degrades to null, never crashing or blocking the session open (claude ≥1.3.1 crash-loops
 * on eager-resolve failures). null = nothing configured → the account default decides, which only the
 * first turn's init can name; the phone then shows an "account default" placeholder.
 *
 * Precedence approximates claude's own (highest first): `$ANTHROPIC_MODEL` → project
 * `.claude/settings.local.json` → project `.claude/settings.json` → user `settings.json` (under the
 * daemon's [userConfigDir] when credential isolation is on, else `$CLAUDE_CONFIG_DIR` / `~/.claude`). For
 * each settings file the settings-scoped `env.ANTHROPIC_MODEL` wins over the top-level `model` field.
 */
object ClaudeDefaultModel {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun resolve(
        workdir: String,
        userConfigDir: Path?,
        env: (String) -> String? = System::getenv,
        home: File = File(System.getProperty("user.home")),
    ): String? = runCatching {
        val projectDir = File(workdir, ".claude")
        val userRoot = userConfigDir?.toFile()
            ?: env("CLAUDE_CONFIG_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(home, ".claude")
        // first non-blank hit wins; each supplier is independently guarded so one bad file can't hide the rest
        val candidates: List<() -> String?> = listOf(
            { env("ANTHROPIC_MODEL")?.takeIf { it.isNotBlank() } },
            { modelFromSettings(File(projectDir, "settings.local.json")) },
            { modelFromSettings(File(projectDir, "settings.json")) },
            { modelFromSettings(File(userRoot, "settings.json")) },
        )
        candidates.firstNotNullOfOrNull { runCatching(it).getOrNull() }
    }.getOrNull()

    /** A settings file's effective model: `env.ANTHROPIC_MODEL` (a settings-scoped override) then the
     *  top-level `model` field. Null when the file is absent / unreadable / has neither. */
    private fun modelFromSettings(file: File): String? {
        if (!file.isFile) return null
        val obj = runCatching { json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() ?: return null
        val envModel = runCatching {
            (obj["env"] as? JsonObject)?.get("ANTHROPIC_MODEL")?.jsonPrimitive?.contentOrNull
        }.getOrNull()?.takeIf { it.isNotBlank() }
        if (envModel != null) return envModel
        return runCatching { obj["model"]?.jsonPrimitive?.contentOrNull }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
