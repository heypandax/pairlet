package dev.ccpocket.daemon.approval

import dev.ccpocket.daemon.util.logger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * TASK-scoped grants (approval design M2 §5): "允许本任务" issues a limited, self-expiring permission
 * for one (conversation, task, rule) — the biggest repeat-approval reducer in the design. Deliberately
 * NOT persisted: a task grant dies with the task, the session, the mode switch, or the 2h TTL,
 * whichever comes first. SESSION scope stays on the conversation's legacy allowRules (one memory, one
 * revoke path — [dev.ccpocket.protocol.ClearAllowRule]); ONCE is the plain allow it always was; and
 * SAVED_POLICY is explicitly out of scope for the first version (design §17.4).
 *
 * Shared engine: the agent's Bash tool and the phone's Quick Terminal both match here, so approving
 * `./gradlew test` for the task covers the same command from either surface (design §13.5).
 *
 * Bash honesty rule: a Bash grant matches only a command whose first two tokens equal the granted rule
 * AND that carries no shell metacharacters — `git status; rm -rf ~` must not ride a `git status` grant.
 * Matching failure falls through to a normal ask, never a deny.
 */
class ApprovalGrantStore {
    data class Grant(
        val id: String,
        val convoId: String,
        val taskId: String,
        val tool: String,
        val rule: String,
        val createdAt: Long,
        val expiresAt: Long,
    )

    private val log = logger("Grants")
    private val grants = ConcurrentHashMap<String, Grant>() // id -> grant

    /** Issue a task grant from a human "允许本任务" verdict. Returns the grant (id rides the autorun chip). */
    fun issueTask(convoId: String, taskId: String, tool: String, rule: String): Grant {
        val g = Grant(
            id = "tg-" + UUID.randomUUID(),
            convoId = convoId, taskId = taskId, tool = tool, rule = rule,
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + TASK_TTL_MS,
        )
        grants[g.id] = g
        log.info("issued task grant ${g.id} tool=$tool rule=$rule task=$taskId convo=$convoId")
        return g
    }

    /**
     * The grant covering [tool]+[rule] for the conversation's CURRENT task, or null → normal ask.
     * [commandText] (Bash/shell only) enforces the metacharacter wall at MATCH time — the grant stores a
     * two-token rule, so a matching prefix with `;`/`&&`/`|`/subshell smuggled behind it must not ride.
     */
    fun match(convoId: String, taskId: String?, tool: String, rule: String?, commandText: String? = null): Grant? {
        if (taskId == null || rule == null) return null
        val now = System.currentTimeMillis()
        val g = grants.values.firstOrNull {
            it.convoId == convoId && it.taskId == taskId && it.tool == tool && it.rule == rule && it.expiresAt > now
        } ?: return null
        if (tool == "Bash" && commandText != null && SHELL_METACHARS.containsMatchIn(commandText)) return null
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

    /** Task reached a terminal state (turn settled with nothing pending / new prompt / 2h TTL). */
    fun endTask(convoId: String, taskId: String) {
        grants.values.removeAll { it.convoId == convoId && it.taskId == taskId }
    }

    /** Session close / mode switch / relaunch under new rules: every grant of the conversation dies. */
    fun endSession(convoId: String) {
        grants.values.removeAll { it.convoId == convoId }
    }

    private companion object {
        const val TASK_TTL_MS = 2 * 60 * 60 * 1000L // design §5.4: a task grant never outlives 2h
        // conservative superset of shell control operators — anything matched falls back to a human ask.
        // Newline/CR are separators exactly like `;` (crypto review HIGH: `git status\nrm -rf ~` tokenizes
        // to the granted two-token rule but runs a second command) — they must be in the class.
        val SHELL_METACHARS = Regex("[;&|`$<>\\n\\r\\\\]|\\$\\(")
    }
}
