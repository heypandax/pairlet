package dev.ccpocket.daemon.approval

import dev.ccpocket.daemon.bridge.PathScope
import dev.ccpocket.daemon.util.logger
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * TASK-scoped grants (approval design M2 §5): "允许本任务" issues a limited, self-expiring permission —
 * the biggest repeat-approval reducer in the design. Deliberately NOT persisted: a task grant dies with
 * the task, the session, the mode switch, or the 2h TTL, whichever comes first. SESSION scope stays on
 * the conversation's legacy allowRules; ONCE is the plain allow it always was; SAVED_POLICY is out of
 * scope for v1 (design §17.4).
 *
 * §18.1 P1-1 — a grant BINDS ITS EXECUTION CONTEXT, not just a tool name:
 *  - [Grant.canonicalRoot]: the session's canonicalized project root at issue time. A match requires the
 *    SAME root — a grant approved in project A never covers project B's quick terminal, whatever workdir
 *    a client claims.
 *  - File tools: every resolved target must land canonically INSIDE the root (relative targets resolve
 *    against it; `..`/symlinks collapse via [PathScope.contains]). A specific-file tool with NO resolvable
 *    target never matches — falling back to a human ask, never a guess.
 *  - Bash: the matcher requires the exact trimmed command that was approved. Shell syntax is not safely
 *    comparable by whitespace-token prefixes (`git -C`, `bash -c`, `python -c`, env wrappers and quoted
 *    arguments all change semantics), so a different argument or flag falls back to a human ask. Commands
 *    carrying shell metacharacters never form or ride a task grant.
 *
 * Shared engine: the agent's Bash tool and the phone's Quick Terminal both match here (design §13.5) —
 * under the same root binding.
 */
class ApprovalGrantStore {
    data class Grant(
        val id: String,
        val convoId: String,
        val taskId: String,
        val tool: String,
        val rule: String,
        val canonicalRoot: String,
        /** Bash only: the exact trimmed command approved by the human. Empty for non-Bash tools. */
        val bashCommand: String,
        val createdAt: Long,
        val expiresAt: Long,
    )

    private val log = logger("Grants")
    private val grants = ConcurrentHashMap<String, Grant>() // id -> grant

    /**
     * Issue a task grant from a human "允许本任务" verdict. [root] is the DAEMON's canonical session
     * workdir (never client-claimed); [commandText] (Bash asks) is retained only as an exact matcher.
     * Returns null — issuing nothing — when the context can't be bound (no canonical root): an unbound
     * grant would be a wildcard, and the ask path is the safe fallback.
     */
    fun issueTask(convoId: String, taskId: String, tool: String, rule: String, root: String?, commandText: String? = null): Grant? {
        // Unknown/parameterized tools need a typed resource matcher before they can safely receive task
        // authority. A tool-name-only grant would turn one WebFetch URL or MCP action into a wildcard.
        if (!supportsTaskGrant(tool)) return null
        val canonicalRoot = root?.let { PathScope.canonical(it) } ?: return null
        val bashCommand = if (tool == "Bash") normalizeBash(commandText ?: return null) else ""
        if (tool == "Bash" && (bashCommand.isEmpty() || SHELL_METACHARS.containsMatchIn(bashCommand))) return null
        val g = Grant(
            id = "tg-" + UUID.randomUUID(),
            convoId = convoId, taskId = taskId, tool = tool, rule = rule,
            canonicalRoot = canonicalRoot,
            bashCommand = bashCommand,
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + TASK_TTL_MS,
        )
        grants[g.id] = g
        log.info(
            "issued task grant ${g.id} tool=$tool summary=${ApprovalHistoryStore.safeSummary(tool, rule)} " +
                "root=$canonicalRoot task=$taskId convo=$convoId",
        )
        return g
    }

    /**
     * The grant covering this action for the conversation's CURRENT task, or null → normal ask.
     * [root] is the daemon-derived canonical root of the EXECUTION context (session workdir / validated
     * quick-terminal workdir); [targets] are the file tool's resolved targets; [commandText] the Bash line.
     * Any binding that cannot be POSITIVELY verified fails the match (ask, never guess — P1-1 §5).
     */
    fun match(
        convoId: String,
        taskId: String?,
        tool: String,
        rule: String?,
        commandText: String? = null,
        root: String? = null,
        targets: List<String> = emptyList(),
    ): Grant? {
        if (taskId == null || rule == null) return null
        if (!supportsTaskGrant(tool)) return null
        val execRoot = root?.let { PathScope.canonical(it) } ?: return null
        val now = System.currentTimeMillis()
        val bashCommand = if (tool == "Bash") commandText?.let(::normalizeBash) ?: return null else null
        if (bashCommand != null && SHELL_METACHARS.containsMatchIn(bashCommand)) return null
        return grants.values.firstOrNull { g ->
            val sameAuthority =
                g.convoId == convoId && g.taskId == taskId && g.tool == tool && g.rule == rule &&
                    g.canonicalRoot == execRoot && g.expiresAt > now
            if (!sameAuthority) return@firstOrNull false
            when {
                tool == "Bash" -> bashCommand == g.bashCommand
                // Named-file tools must resolve at least one target. Search tools legitimately use the
                // session cwd when no path is supplied, but any explicit target still goes through the
                // same canonical wall. Keying on `targets` also protects future/renamed path-bearing tools.
                tool in SPECIFIC_FILE_TOOLS && targets.isEmpty() -> false
                targets.isNotEmpty() -> targets.all { targetContained(g.canonicalRoot, it) }
                else -> true
            }
        }
    }

    /** "收紧后续授权": drop one grant. Validated against [convoId] so a verdict-capable client can only
     *  tighten grants of a conversation it reaches. Only affects actions not yet started. */
    fun revoke(convoId: String, grantId: String): Boolean {
        val g = grants[grantId] ?: return false
        if (g.convoId != convoId) return false
        grants.remove(grantId)
        log.info("revoked task grant $grantId (user tightened)")
        return true
    }

    /** Task reached a terminal state (turn settled with nothing pending / new prompt / TTL). Idempotent. */
    fun endTask(convoId: String, taskId: String) {
        grants.values.removeAll { it.convoId == convoId && it.taskId == taskId }
    }

    /** Session close / mode switch / relaunch under new rules: every grant of the conversation dies. Idempotent. */
    fun endSession(convoId: String) {
        grants.values.removeAll { it.convoId == convoId }
    }

    companion object {
        const val TASK_TTL_MS = 2 * 60 * 60 * 1000L // design §5.4: a task grant never outlives 2h

        /** File tools whose grant match must positively verify each target. */
        val SPECIFIC_FILE_TOOLS = setOf("Read", "Write", "Edit", "MultiEdit", "NotebookEdit")

        /** Closed set with explicit match semantics above. Everything else remains allow-once/session
         *  until a canonical typed action/resource binding is implemented for that tool family. */
        private val TASK_GRANT_TOOLS = SPECIFIC_FILE_TOOLS + setOf("Bash", "Glob", "Grep")

        fun supportsTaskGrant(tool: String): Boolean = tool in TASK_GRANT_TOOLS

        // conservative superset of shell control operators — anything matched falls back to a human ask.
        // Newline/CR are separators exactly like `;` (crypto review HIGH: `git status\nrm -rf ~` tokenizes
        // to the granted two-token rule but runs a second command) — they must be in the class.
        val SHELL_METACHARS = Regex("[;&|`$<>\\n\\r\\\\]|\\$\\(")

        private fun normalizeBash(cmd: String): String = cmd.trim()

        private fun targetContained(root: String, target: String): Boolean {
            if (target.startsWith("~")) return false // PathScope never expands tildes — refuse outright
            val absolute = if (File(target).isAbsolute) target else File(root, target).path
            return PathScope.contains(listOf(root), absolute)
        }
    }
}
