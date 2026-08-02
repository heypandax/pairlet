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
 *  - Bash: the matcher is the granted command's leading token prefix (executable + subcommand + task name,
 *    up to the first flag, max [BASH_PREFIX_MAX]) — `npm run test` does not cover `npm run postinstall` —
 *    plus the metacharacter wall: `;`/`&&`/pipes/backticks/subshells/redirects/NEWLINES fall through to a
 *    human ask (a smuggled second command tokenizes invisibly — crypto review HIGH, 08-02).
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
        /** Bash only: the granted command's leading tokens (exe + subcommand + task name). Empty for file tools. */
        val bashPrefix: List<String>,
        val createdAt: Long,
        val expiresAt: Long,
    )

    private val log = logger("Grants")
    private val grants = ConcurrentHashMap<String, Grant>() // id -> grant

    /**
     * Issue a task grant from a human "允许本任务" verdict. [root] is the DAEMON's canonical session
     * workdir (never client-claimed); [commandText] (Bash asks) derives the structured prefix matcher.
     * Returns null — issuing nothing — when the context can't be bound (no canonical root): an unbound
     * grant would be a wildcard, and the ask path is the safe fallback.
     */
    fun issueTask(convoId: String, taskId: String, tool: String, rule: String, root: String?, commandText: String? = null): Grant? {
        val canonicalRoot = root?.let { PathScope.canonical(it) } ?: return null
        val g = Grant(
            id = "tg-" + UUID.randomUUID(),
            convoId = convoId, taskId = taskId, tool = tool, rule = rule,
            canonicalRoot = canonicalRoot,
            bashPrefix = if (tool == "Bash") bashPrefix(commandText ?: rule) else emptyList(),
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + TASK_TTL_MS,
        )
        grants[g.id] = g
        log.info("issued task grant ${g.id} tool=$tool rule=$rule root=$canonicalRoot task=$taskId convo=$convoId")
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
        val execRoot = root?.let { PathScope.canonical(it) } ?: return null
        val now = System.currentTimeMillis()
        val g = grants.values.firstOrNull {
            it.convoId == convoId && it.taskId == taskId && it.tool == tool && it.rule == rule &&
                it.canonicalRoot == execRoot && it.expiresAt > now
        } ?: return null
        if (tool == "Bash") {
            val cmd = commandText ?: return null
            if (SHELL_METACHARS.containsMatchIn(cmd)) return null
            val candidate = tokens(cmd)
            if (candidate.size < g.bashPrefix.size) return null
            if (g.bashPrefix.isEmpty() || g.bashPrefix.indices.any { candidate[it] != g.bashPrefix[it] }) return null
        } else if (tool in SPECIFIC_FILE_TOOLS) {
            // a file grant only covers targets provably inside its root; unresolvable = no match
            if (targets.isEmpty()) return null
            val roots = listOf(g.canonicalRoot)
            if (!targets.all { t ->
                    if (t.startsWith("~")) return@all false // PathScope never expands tildes — refuse outright
                    val abs = if (File(t).isAbsolute) t else File(g.canonicalRoot, t).path
                    PathScope.contains(roots, abs)
                }
            ) return null
        }
        return g
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

        /** File tools whose grant match must positively verify each target (mirror of the tools the
         *  walls guard — a renamed tool falls to the else-branch and simply never matches a grant). */
        val SPECIFIC_FILE_TOOLS = setOf("Read", "Write", "Edit", "MultiEdit", "NotebookEdit")

        // conservative superset of shell control operators — anything matched falls back to a human ask.
        // Newline/CR are separators exactly like `;` (crypto review HIGH: `git status\nrm -rf ~` tokenizes
        // to the granted two-token rule but runs a second command) — they must be in the class.
        val SHELL_METACHARS = Regex("[;&|`$<>\\n\\r\\\\]|\\$\\(")

        const val BASH_PREFIX_MAX = 3 // executable + subcommand + task name (npm run test / git status)

        private fun tokens(cmd: String) = cmd.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

        /** The granted command's binding prefix: leading non-flag tokens, capped. `npm run test --ci` →
         *  [npm, run, test]; `git status -sb` → [git, status]. */
        fun bashPrefix(cmd: String): List<String> =
            tokens(cmd).takeWhile { !it.startsWith("-") }.take(BASH_PREFIX_MAX)
    }
}
