package dev.ccpocket.daemon.claude

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Human-facing metadata for a tool permission request: a verb title, a preview, the allow-rule scope, and danger flags. */
data class ToolMeta(
    val title: String,
    val preview: String,
    val rule: String, // doubles as the display string AND the match key for "Always allow"
    val danger: Boolean,
    val dangerNote: String?,
)

/** Derives [ToolMeta] from a tool name + its input. The allow-rule is granular: Bash → first two tokens
 *  ("git status"), edits → the tool family ("Edit"). A future request that produces the same rule auto-allows. */
object ToolMetadata {
    private val DANGER = Regex(
        """rm\s+-[rf]|--force|force-with-lease|\bsudo\b|\bdd\b|mkfs|>\s*/dev/|chmod\s+-R|chown\s+-R|:\(\)\s*\{|git\s+reset\s+--hard|git\s+clean\s+-[a-z]*f""",
        RegexOption.IGNORE_CASE,
    )

    fun of(tool: String, input: JsonObject?): ToolMeta {
        fun str(k: String) = (input?.get(k) as? JsonPrimitive)?.contentOrNull
        return when (tool) {
            "Bash" -> {
                val cmd = str("command")?.trim().orEmpty()
                val twoTokens = cmd.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString(" ")
                val danger = DANGER.containsMatchIn(cmd)
                ToolMeta(
                    title = "Run command",
                    preview = cmd.ifEmpty { tool },
                    rule = twoTokens.ifEmpty { "Bash" },
                    danger = danger,
                    dangerNote = if (danger) "run destructive commands" else null,
                )
            }
            "Write", "Edit", "MultiEdit", "NotebookEdit" -> ToolMeta(
                title = if (tool == "Write") "Write file" else "Edit file",
                preview = (str("file_path") ?: str("path"))?.let(::tilde) ?: tool,
                rule = if (tool == "Write") "Write" else "Edit",
                danger = false,
                dangerNote = null,
            )
            "Read", "Glob", "Grep" -> ToolMeta("Read", (str("file_path") ?: str("path") ?: str("pattern"))?.let(::tilde) ?: tool, tool, false, null)
            else -> {
                val p = listOf("command", "file_path", "path", "pattern", "url", "description", "content")
                    .firstNotNullOfOrNull { str(it)?.takeIf(String::isNotBlank) } ?: tool
                ToolMeta(tool, p.take(280), tool, false, null)
            }
        }
    }

    private fun tilde(p: String): String {
        val home = System.getProperty("user.home") ?: return p
        return if (p.startsWith(home)) "~" + p.removePrefix(home) else p
    }
}
