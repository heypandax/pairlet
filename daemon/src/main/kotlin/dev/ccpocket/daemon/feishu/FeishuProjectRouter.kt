package dev.ccpocket.daemon.feishu

/**
 * The project-resolution seam for an AUTO-routing chat (/bind auto): given one message and the bridge's
 * allow-listed projects, answer "which project does this request belong to?" — BEFORE the turn opens a
 * session, so a topic lands in the right workdir from its first message.
 *
 * The router is a CONVENIENCE, never an authority: it can only pick among candidates the machine owner
 * already allow-listed, the pick is re-vetted by BridgeGuard at session open, and it changes NOTHING about
 * approvals — trust rules and the owner's per-request card behave exactly as they would in a chat /bind'ed
 * to that same project. Its worst failure mode is therefore a WRONG (but allow-listed) project, which the
 * routing receipt makes visible and /new + a project-naming message corrects. On any degradation (model
 * unavailable, timeout, low confidence, drifted output) the engine ASKS THE USER to name the project
 * instead of guessing.
 */
interface FeishuProjectRouter {
    /** Null = couldn't classify (unavailable/timeout/invalid output) — the caller falls back to asking. */
    suspend fun route(input: ProjectRouteInput): ProjectRouteResult?
}

/** One allow-listed project as the router sees it: display name + a bounded local summary, never a path. */
data class ProjectCandidate(val name: String, val summary: String)

data class ProjectRouteInput(
    /** The requester's message — UNTRUSTED data, labeled so for the model. */
    val prompt: String,
    val candidates: List<ProjectCandidate>,
    /** The group's display name, if known — weak but real routing signal ("cc-pocket 反馈群"). */
    val chatName: String? = null,
    /** The conversation's CURRENT project (a candidate name), for the unified-inbox direct chat: an
     *  ambiguous or follow-up message continues it; only a clear other-project request switches away. */
    val currentProject: String? = null,
)

data class ProjectRouteResult(
    /** A candidate's exact name, or null when the model judged the request ambiguous. */
    val project: String?,
    val confidence: Double,
    val reason: String,
)

object ProjectRoutePolicy {
    /** Below this the engine treats a pick as "not sure" and asks the user to name the project. Routing is
     *  not a security decision (see [FeishuProjectRouter]), so the bar is usefulness, not safety. */
    const val CONFIDENCE_FLOOR = 0.6

    /** The router's own input cap — same posture as the Guardian reviewer's. */
    const val MAX_ROUTE_PROMPT_CHARS = 12_000

    /** How much of a project's CLAUDE.md/README head rides into the router as its summary. */
    const val MAX_SUMMARY_CHARS = 300

    /**
     * The zero-cost deterministic pass: the message NAMES exactly one allow-listed project → that's the
     * routing, no model needed ("cc-pocket：帮我看下 CI"). Requires length ≥ 3 so a short generic basename
     * ("app") can't hijack unrelated sentences, and a UNIQUE hit so nested names ("app" vs "app-server")
     * fall through to the model rather than picking by list order.
     */
    fun mentionedProject(text: String, workdirs: List<String>): String? {
        val t = text.lowercase()
        return workdirs.filter { wd ->
            val n = FeishuRoutes.projectName(wd).lowercase()
            n.length >= 3 && n in t
        }.singleOrNull()
    }
}
