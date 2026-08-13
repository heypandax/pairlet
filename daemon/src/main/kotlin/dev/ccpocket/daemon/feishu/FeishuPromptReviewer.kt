package dev.ccpocket.daemon.feishu

/**
 * The Guardian Reviewer seam for a REVIEWED or FULL_AUTO chat: an INDEPENDENT classifier that answers
 * one question about one prompt — "does this match the owner's Trust Contract for the group, and is it
 * clearly low-risk within the fixed capability ceiling?" — before that prompt reaches the agent.
 *
 * The reviewer is a CONDITION MATCHER, never an authorizer (design §4): it emits a classification signal
 * only. The daemon decides what that signal is worth ([PromptReviewPolicy.mayAutoRun]), re-validates the
 * chat's policy afterwards (revoke race), and the engine chooses the fixed ceiling that corresponds to the
 * owner's durable mode. No output field can name tools, grants or permission modes — the model can classify
 * one prompt, never widen either ceiling.
 */
interface FeishuPromptReviewer {
    suspend fun review(input: PromptReviewInput): PromptReviewResult
}

data class PromptReviewInput(
    val reviewId: String,
    /** Display name only — never the absolute workdir (context boundary, design §7.4). */
    val projectName: String,
    /** The owner's Trust Contract text (their /review argument, or the default contract). */
    val purpose: String,
    /** The vetted prompt incl. bounded quoted context — UNTRUSTED data, and labeled so for the model. */
    val prompt: String,
    val senderRole: PromptSenderRole = PromptSenderRole.MEMBER,
    /** The fixed description of what an auto-passed request may at most do — so "low-risk" is judged
     *  against the real ceiling, not the model's imagination of it. */
    val capabilityCeiling: String = CAPABILITY_CEILING,
    /** The owner's configured Bash allowlist (command patterns, not secrets). Its authority depends on the
     *  selected ceiling: it is the only shell exception under REVIEWED, but merely contextual under FULL_AUTO. */
    val allowedCommands: List<String> = emptyList(),
) {
    companion object {
        // The legacy REVIEWED ceiling. Existing REVIEWED records retain this restricted authority; widening
        // it would silently upgrade a durable policy the owner established under an older promise.
        const val CAPABILITY_CEILING =
            "Auto-approved requests may only: read/search/edit files INSIDE the bound project directory, " +
                "plus run shell commands the machine owner explicitly whitelisted (see the allowed_commands " +
                "field) — those run with zero clicks and may access the network or run project scripts. " +
                "Everything else — any other shell command, MCP tools, network access, writes to " +
                ".git/.claude/.envrc, anything outside the project — still requires the machine owner's approval."

        // The explicit #233 FULL_AUTO ceiling. Under-pricing any of these capabilities is an authorization
        // bug: the reviewer would classify a prompt as low-risk while assuming a human or sandbox that is absent.
        const val FULL_AUTO_CAPABILITY_CEILING =
            "An auto-approved request receives a one-turn full-auto grant. The coding agent may run arbitrary " +
                "non-blocked Bash commands, use MCP and network tools, spawn sub-agents, and edit project files " +
                "without another owner confirmation. Shell and unknown tools are not confined by the structured " +
                "file workdir wall: they may access data outside the project or send data externally. Structured " +
                "file targets outside the bound project are denied. A persistence hold applies only when the " +
                "daemon recognizes a structured file target that executes for the owner; it does not confine " +
                "shell or unknown tools. Unresolved named-file targets and human-decision tools still ask. Known " +
                "destructive/high-risk Bash DENY patterns are best-effort defense-in-depth, not a complete or " +
                "unbypassable safety boundary. Therefore any credential/secret, external-path, exfiltration, " +
                "persistence, privilege, destructive, approval-bypass, or ambiguous intent is NOT low-risk and " +
                "must ask the owner."
    }
}

data class PromptReviewResult(
    val decision: PromptReviewDecision,
    val risk: PromptReviewRisk,
    val matchesContract: Boolean,
    val confidence: Double,
    val intent: String,
    val reasonCodes: List<String>,
    val explanation: String,
    val assessor: String,
    val assessorVersion: String? = null,
)

/** No model DENY on purpose (design §7.2): "dangerous / can't tell / off-purpose" all mean "ask a human". */
enum class PromptReviewDecision { ALLOW_GUARDED, ASK_OWNER }
enum class PromptReviewRisk { LOW, MEDIUM, HIGH, UNKNOWN }
enum class PromptSenderRole { MEMBER }

/**
 * The daemon's OWN judgement of a reviewer signal — the model proposes, this disposes. Every invalid,
 * uncertain or degraded shape normalizes to "ask the owner"; nothing here can widen anything.
 */
object PromptReviewPolicy {
    /** Only a result clearing EVERY bar may skip the owner's card (design §7.2). */
    fun mayAutoRun(r: PromptReviewResult): Boolean =
        r.decision == PromptReviewDecision.ALLOW_GUARDED &&
            r.risk == PromptReviewRisk.LOW &&
            r.matchesContract &&
            r.confidence.isFinite() && r.confidence >= CONFIDENCE_FLOOR && r.confidence <= 1.0 &&
            // a LOW-risk auto-pass may not carry ANY risk annotation (design §21.3): a code the daemon
            // doesn't recognize is a signal it can't price, so it fails closed the same as a known veto.
            // The explicit force-owner check below is redundant under the empty-set rule — kept anyway as
            // defense in depth against a future edit relaxing the isEmpty bar.
            r.reasonCodes.isEmpty() &&
            r.reasonCodes.none { it in FORCE_OWNER_REASON_CODES }

    /** A degraded outcome (prescreen hit, timeout, invalid output, unavailable adapter) as a well-formed
     *  result, so the audit trail records WHY instead of a bare failure. Always ASK_OWNER + UNKNOWN. */
    fun forcedAskOwner(reasonCodes: List<String>, assessor: String, explanation: String = ""): PromptReviewResult =
        PromptReviewResult(
            decision = PromptReviewDecision.ASK_OWNER,
            risk = PromptReviewRisk.UNKNOWN,
            matchesContract = false,
            confidence = 0.0,
            intent = "",
            reasonCodes = reasonCodes.map { it.take(MAX_FIELD_CHARS) }.take(MAX_REASON_CODES),
            explanation = explanation.take(MAX_FIELD_CHARS),
            assessor = assessor,
        )

    const val CONFIDENCE_FLOOR = 0.90
    const val MAX_FIELD_CHARS = 300
    const val MAX_REASON_CODES = 16
    /** The reviewer's OWN input cap, tighter than the bridge prompt limit (design §7.4). */
    const val MAX_REVIEW_PROMPT_CHARS = 12_000

    // deterministic reason codes (prescreen + degradations). Any of these on a result vetoes auto-run even
    // if the rest of the shape looks clean — they mark "a human must look", never "silently reject".
    const val CREDENTIAL_OR_SECRET_REQUEST = "CREDENTIAL_OR_SECRET_REQUEST"
    const val EXTERNAL_PATH_REQUEST = "EXTERNAL_PATH_REQUEST"
    const val DATA_EXFILTRATION_REQUEST = "DATA_EXFILTRATION_REQUEST"
    const val PRIVILEGE_ESCALATION_REQUEST = "PRIVILEGE_ESCALATION_REQUEST"
    const val PERSISTENCE_REQUEST = "PERSISTENCE_REQUEST"
    const val APPROVAL_BYPASS_REQUEST = "APPROVAL_BYPASS_REQUEST"
    const val DESTRUCTIVE_OR_IRREVERSIBLE_REQUEST = "DESTRUCTIVE_OR_IRREVERSIBLE_REQUEST"
    const val OBFUSCATED_INTENT = "OBFUSCATED_INTENT"
    const val PROMPT_TOO_LARGE = "PROMPT_TOO_LARGE"
    const val REVIEWER_UNAVAILABLE = "REVIEWER_UNAVAILABLE"
    const val REVIEWER_TIMEOUT = "REVIEWER_TIMEOUT"
    const val REVIEWER_INVALID_OUTPUT = "REVIEWER_INVALID_OUTPUT"
    const val POLICY_CHANGED_DURING_REVIEW = "POLICY_CHANGED_DURING_REVIEW"

    val FORCE_OWNER_REASON_CODES: Set<String> = setOf(
        CREDENTIAL_OR_SECRET_REQUEST,
        EXTERNAL_PATH_REQUEST,
        DATA_EXFILTRATION_REQUEST,
        PRIVILEGE_ESCALATION_REQUEST,
        PERSISTENCE_REQUEST,
        APPROVAL_BYPASS_REQUEST,
        DESTRUCTIVE_OR_IRREVERSIBLE_REQUEST,
        OBFUSCATED_INTENT,
        PROMPT_TOO_LARGE,
        REVIEWER_UNAVAILABLE,
        REVIEWER_TIMEOUT,
        REVIEWER_INVALID_OUTPUT,
        POLICY_CHANGED_DURING_REVIEW,
    )
}

/**
 * The Guardian request preflight for REVIEWED/FULL_AUTO — the one place the pieces compose, kept free of
 * Feishu/engine types so the whole flow (prescreen → review → policy → revalidation → audit) is unit-testable with fakes:
 *
 *  1. deterministic [PromptThreatSignals] prescreen (force-to-owner only, never auto-pass);
 *  2. the async Guardian review, failure-normalized ([PromptReviewPolicy.forcedAskOwner]);
 *  3. the daemon's own pass bar ([PromptReviewPolicy.mayAutoRun]);
 *  4. post-review [revalidate] — /untrust, rebind or a contract edit that landed while the model was
 *     thinking voids the result (trust commands deliberately don't wait on the turn lock);
 *  5. one structured audit event per request, whatever happened.
 *
 * "Fall back to the owner's card" is this flow's NORMAL degraded path, not an error (design §12).
 */
class ReviewedPreflight(
    private val reviewer: FeishuPromptReviewer,
    private val auditLog: FeishuReviewLog,
    /** shadow rollout switch (design §13): review + audit as usual, but NEVER auto-run. */
    private val shadowOnly: Boolean = false,
    /** The owner's Bash allowlist, forwarded into every review so the model prices risk against the real
     *  capability ceiling (design §21.6). Defaults empty — wiring from the engine config is separate. */
    private val allowedCommands: List<String> = emptyList(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    data class Outcome(val autoRun: Boolean, val reviewId: String, val result: PromptReviewResult)

    suspend fun evaluate(
        snapshot: TrustSnapshot,
        prompt: String,
        projectName: String,
        senderOpenId: String,
        messageId: String,
        capabilityCeiling: String = PromptReviewInput.CAPABILITY_CEILING,
        revalidate: () -> Boolean,
    ): Outcome {
        val reviewId = java.util.UUID.randomUUID().toString()
        val startedMs = nowMs()
        val purpose = snapshot.purpose ?: FeishuTrust.DEFAULT_CONTRACT

        val threat = PromptThreatSignals.scan(prompt)
        val result: PromptReviewResult =
            if (threat.isNotEmpty()) {
                PromptReviewPolicy.forcedAskOwner(threat, assessor = "prescreen")
            } else {
                runCatching {
                    reviewer.review(
                        PromptReviewInput(
                            reviewId = reviewId,
                            projectName = projectName,
                            purpose = purpose,
                            prompt = prompt,
                            capabilityCeiling = capabilityCeiling,
                            allowedCommands = allowedCommands,
                        ),
                    )
                }.getOrElse {
                    PromptReviewPolicy.forcedAskOwner(listOf(PromptReviewPolicy.REVIEWER_UNAVAILABLE), assessor = "none")
                }
            }

        var autoRun = PromptReviewPolicy.mayAutoRun(result) && !shadowOnly
        var reasonCodes = result.reasonCodes
        // the review took seconds — anything the owner changed meanwhile beats anything the model concluded
        if (autoRun && !revalidate()) {
            autoRun = false
            reasonCodes = reasonCodes + PromptReviewPolicy.POLICY_CHANGED_DURING_REVIEW
        }

        auditLog.record(
            FeishuReviewEvent(
                timestampMs = nowMs(),
                eventType = if (shadowOnly) "review_shadow" else "review",
                reviewId = reviewId,
                chatIdHash = FeishuReviewLog.hash(snapshot.chatId),
                senderHash = FeishuReviewLog.hash(senderOpenId),
                messageIdHash = FeishuReviewLog.hash(messageId),
                projectName = projectName,
                mode = snapshot.mode.name,
                contractVersion = snapshot.contractVersion,
                risk = result.risk.name,
                confidence = result.confidence.takeIf { it.isFinite() } ?: -1.0,
                // only the KNOWN constant codes persist — a model-invented "code" is free text that could
                // smuggle prompt content into the durable trail (crypto review Low-2); the in-memory result
                // keeps the full list for the ring log / policy, which never touch disk
                reasonCodes = reasonCodes.filter { it in PromptReviewPolicy.FORCE_OWNER_REASON_CODES },
                decision = result.decision.name,
                finalOutcome = when {
                    autoRun -> "reviewer_auto_allowed"
                    reasonCodes.contains(PromptReviewPolicy.POLICY_CHANGED_DURING_REVIEW) -> "policy_changed_during_review"
                    reasonCodes.contains(PromptReviewPolicy.REVIEWER_TIMEOUT) -> "reviewer_timeout"
                    reasonCodes.contains(PromptReviewPolicy.REVIEWER_UNAVAILABLE) -> "reviewer_unavailable"
                    reasonCodes.contains(PromptReviewPolicy.REVIEWER_INVALID_OUTPUT) -> "reviewer_invalid_output"
                    else -> "escalated_owner"
                },
                assessor = result.assessor,
                assessorVersion = result.assessorVersion,
                latencyMs = nowMs() - startedMs,
            ),
        )
        return Outcome(autoRun, reviewId, result.copy(reasonCodes = reasonCodes))
    }
}
