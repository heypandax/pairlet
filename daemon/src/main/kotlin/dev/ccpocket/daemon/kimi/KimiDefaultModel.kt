package dev.ccpocket.daemon.kimi

import java.nio.file.Path

/**
 * Best-effort resolve of the model `kimi` uses when a session starts with NO explicit model, so a
 * brand-new session's header shows the real model before the first turn (issue #96 contract). Reads
 * `$KIMI_CODE_HOME/config.toml` ONLY (never launches kimi — claude ≥1.3.1 crash-loops on eager resolve
 * failures, same discipline here) and NEVER throws: a failed resolve degrades to null.
 *
 * Reads the TOP-LEVEL `default_model = "…"` key with a minimal line scan (no TOML dependency): top-level
 * keys sit above the first `[table]` header, so we stop at the first `[`.
 */
object KimiDefaultModel {
    private val TOP_MODEL = Regex("""default_model\s*=\s*["']([^"']+)["']""")

    fun resolve(configPath: Path = KimiPaths.configToml()): String? = runCatching {
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
