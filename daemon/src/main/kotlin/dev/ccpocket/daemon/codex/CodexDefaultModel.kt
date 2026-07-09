package dev.ccpocket.daemon.codex

import java.nio.file.Path

/**
 * Best-effort resolve of the model `codex` uses when a session starts with NO explicit model — so a
 * brand-new session's header can show the real model BEFORE the first turn instead of a blank segment
 * (issue #96). Reads `$CODEX_HOME/config.toml` ONLY (never launches codex) and NEVER throws: a failed
 * resolve degrades to null and the phone shows an "account default" placeholder until the first turn's
 * init names it.
 *
 * We read the TOP-LEVEL `model = "..."` key with a minimal line scan (no TOML dependency): top-level keys
 * sit above the first `[table]` header, so we stop at the first `[`. A profile-scoped model (advanced
 * config) is intentionally not chased — it degrades to null, which is the placeholder path.
 */
object CodexDefaultModel {
    private val TOP_MODEL = Regex("""model\s*=\s*["']([^"']+)["']""")

    fun resolve(
        configPath: Path = CodexPaths.codexHome().resolve("config.toml"),
    ): String? = runCatching {
        val file = configPath.toFile()
        if (!file.isFile) return@runCatching null
        for (raw in file.readLines()) {
            val line = raw.substringBefore('#').trim() // drop trailing comments
            if (line.startsWith("[")) break // a [table] header — top-level keys are all above it
            TOP_MODEL.matchEntire(line)?.let { return@runCatching it.groupValues[1].takeIf { m -> m.isNotBlank() } }
        }
        null
    }.getOrNull()
}
