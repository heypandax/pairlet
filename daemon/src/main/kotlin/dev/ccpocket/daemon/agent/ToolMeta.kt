package dev.ccpocket.daemon.agent

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
    // A human-decision gate that must NEVER be remembered or auto-allowed (e.g. ExitPlanMode): every occurrence
    // re-prompts even if "Always allow" was tapped before or the mode would otherwise skip the ask.
    val neverRemember: Boolean = false,
)

/** Derives [ToolMeta] from a tool name + its input. The allow-rule is granular: Bash → first two tokens
 *  ("git status"), edits → the tool family ("Edit"). A future request that produces the same rule auto-allows.
 *  Codex backends reuse this by synthesizing Claude-shaped tool names ("Bash"/"Edit") + inputs. */
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
            // The plan-approval gate. The proposed plan lives in input["plan"] (NOT command/path/…), so the
            // generic else branch showed only the literal "ExitPlanMode" — the plan was invisible on the phone.
            // Surface the full plan as the preview, and mark it neverRemember so approving a plan is always an
            // explicit, per-plan human decision (never auto-confirmed by a remembered rule). See PermissionBridge.
            "ExitPlanMode", "exit_plan_mode" -> ToolMeta(
                title = "Review plan",
                preview = str("plan")?.takeIf(String::isNotBlank) ?: "(empty plan)",
                rule = "ExitPlanMode",
                danger = false,
                dangerNote = null,
                neverRemember = true,
            )
            // Claude asking the user multiple-choice questions. The answers ride in the verdict, so a
            // remembered/auto allow would answer NOTHING ("the user did not answer") — every occurrence
            // must reach a human, even in bypass mode (see PermissionBridge). Preview = the first question,
            // so even an old phone's generic card shows something meaningful.
            AskQuestions.TOOL -> ToolMeta(
                title = "Answer questions",
                preview = AskQuestions.parse(input)?.firstOrNull()?.question ?: "Claude has a question",
                rule = AskQuestions.TOOL,
                danger = false,
                dangerNote = null,
                neverRemember = true,
            )
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

    /** The filesystem-path keys the built-in file tools carry — `file_path` (Read/Write/Edit/MultiEdit),
     *  `path` (Glob/Grep), `notebook_path` (NotebookEdit). The GUEST guard reads these by KEY, independent of
     *  tool NAME, so a file tool the CLI adds or renames is still confined (deny-by-default containment)
     *  instead of silently escaping a hard-coded tool whitelist and falling through to an ask the guest
     *  self-approves (issue #115 crypto review M1). A non-file tool carries none of these keys (Bash →
     *  `command`, WebFetch → `url`, …) → empty → correctly unguarded (Bash is the acknowledged v1 gap). */
    private val PATH_KEYS = listOf("file_path", "path", "notebook_path")

    /**
     * The filesystem path(s) a tool call targets, for the guest path guard — empty for a tool that carries
     * none of the [PATH_KEYS] (so a non-file tool is never falsely denied) and for a file tool that named no
     * path (it then operates on the session cwd, already inside the scope). Paths may be relative (resolved
     * against the session workdir by the caller) or absolute. [tool] is kept for call-site clarity/telemetry;
     * confinement is by key so an unlisted file tool can't slip the guard.
     */
    @Suppress("UNUSED_PARAMETER")
    fun pathTargets(tool: String, input: JsonObject?): List<String> {
        fun str(k: String) = (input?.get(k) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        return PATH_KEYS.mapNotNull { str(it) }
    }
}
