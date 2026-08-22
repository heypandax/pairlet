package dev.ccpocket.daemon.dsh

import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AskQuestions
import dev.ccpocket.daemon.util.logger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The daemon's pending table for dsh's two host→client server-requests (issue #291), and the only place
 * that turns a phone verdict back into a `POST /api/respond`.
 *
 * ## Why this exists as its own object
 *
 * `question/requested` and `approval/requested` carry their correlation token on the ENVELOPE (`rpcId`),
 * not in the payload, and the answer must echo it. The rest of the daemon speaks `askId`, and the phone
 * answers a question by its TEXT, never by dsh's question id — which never goes on the wire. So something
 * has to hold the round trip's private state: rpcId, sessionId, approvalId, and the ordered
 * question-text ↔ question-id map. That is this ledger, and keeping it free of transport lets every rule
 * be tested against real frames.
 *
 * ## Idempotency
 *
 * dsh re-delivers a still-undecided server-request to a (re)subscribing mux under the SAME rpcId, so the
 * pending check below is what keeps a re-delivery from dealing a SECOND card for a question already on
 * the user's screen — answering either would leave the other stuck forever, because dsh retires the
 * request on the first claim (`not-pending` for the rest). First frame wins; duplicates are dropped.
 *
 * ⚠️ CALIBRATION: this is a guard, not a load-bearing recovery path, and the `--probe-ask` tier does NOT
 * assert re-delivery — do not cite it as verified. In THIS client a reconnect cannot even happen:
 * [DshApiClient] treats a close after a successful connect as terminal and the conversation relaunches
 * (which resets this table). The check costs nothing and stays for the day either of those changes.
 *
 * ## Fail-closed, not fail-hang
 *
 * dsh has NO timeout of its own — an unanswered request hangs the turn permanently (probe: 120s with zero
 * host-side resolution, and the source has no `setTimeout`). The card therefore runs on the daemon's
 * [dev.ccpocket.daemon.approval.ApprovalCoordinator] budget like every other backend's, and an expired
 * approval is answered `rejected`. A QUESTION has no reject in dsh's vocabulary, so an expired one is
 * answered as the all-skipped batch dsh's own web UI sends — the turn continues with nothing chosen
 * instead of wedging. The owner's "wait for my decision" preference (#201) still applies upstream: that
 * chain simply keeps the card alive longer, which is exactly what dsh does natively anyway.
 */
internal class DshAskLedger(
    /**
     * OUR session id, read live. An ask is only claimed once it is known AND the frame names it, because
     * the mux is multiplexed across every session the host holds (sub-agents included) and the backend's
     * general filter fails OPEN in the window between the socket connecting and `session.create`
     * returning. Fail-closed here instead: dealing a card for a foreign session would let one tap send
     * `allowed-once` for a `(sessionId, approvalId)` pair the user never saw.
     */
    private val ourSession: () -> String?,
    /** Sends one `/api/respond`. Split out so tests exercise the whole ledger with no local HTTP server. */
    private val send: suspend (rpcId: String, value: JsonObject) -> DshRespond,
    /** Surfaces a message in the chat. Only used when a response we built was REFUSED — never for the
     *  benign `not-pending` race. */
    private val report: suspend (String) -> Unit,
) {
    private val log = logger("DshAsk")

    private sealed interface Entry {
        val rpcId: String

        /** The asking session, echoed back in the response because dsh validates it alongside the
         *  approvalId. Provably OUR session: [requested] refuses to claim any other (fail-closed), so this
         *  can never carry a foreign id into an `allowed-once`. */
        val sessionId: String
    }

    private data class QuestionEntry(
        override val rpcId: String,
        override val sessionId: String,
        val questions: List<DshAsk.Question>,
    ) : Entry

    private data class ApprovalEntry(
        override val rpcId: String,
        override val sessionId: String,
        val approvalId: String,
    ) : Entry

    private val lock = Any()
    private val pending = LinkedHashMap<String, Entry>() // rpcId -> entry
    // approval/resolved keys by approvalId, NOT by rpcId (the two frames disagree on which id they carry),
    // so the reverse index is what lets a host-side cancellation retire the right card.
    private val byApproval = HashMap<String, String>() // approvalId -> rpcId

    /** Drop everything — a relaunch gets a fresh process, a fresh mux and fresh rpcIds. */
    fun reset() = synchronized(lock) { pending.clear(); byApproval.clear() }

    /**
     * A `…/requested` frame → the [AgentEvent.ControlRequest] the permission bridge turns into a card, or
     * null when there is nothing new to show (a replay of a card already pending, or a frame we cannot
     * answer and must therefore not pretend to).
     */
    fun requested(method: String, rpcId: String?, payload: JsonObject): AgentEvent.ControlRequest? {
        if (rpcId.isNullOrEmpty()) {
            log.warn("dsh $method with no rpcId — unanswerable, ignored")
            return null
        }
        // Fail-closed session check (see [ourSession]). A dropped ask means a HUNG dsh turn and dsh never
        // reports one, so this is logged loudly rather than silently discarded.
        val ours = ourSession()
        val sessionId = payload.str("sessionId")
        if (ours == null || sessionId == null || sessionId != ours) {
            log.warn(
                "dsh $method for session=$sessionId is not ours (${ours ?: "session not open yet"}) — " +
                    "dropped, and dsh will block that turn until it is cancelled",
            )
            return null
        }
        return when (method) {
            QUESTION_REQUESTED -> {
                val questions = DshAsk.questionsOf(payload)
                if (questions.isEmpty()) {
                    log.warn("dsh question/requested carried no answerable question — ignored")
                    return null
                }
                if (!claim(rpcId, QuestionEntry(rpcId, sessionId, questions))) return null
                AgentEvent.ControlRequest(
                    requestId = DshAsk.askIdOf(rpcId),
                    toolName = AskQuestions.TOOL,
                    input = DshAsk.askQuestionInput(questions),
                )
            }
            APPROVAL_REQUESTED -> {
                val approvalId = payload.str("approvalId")
                val toolName = payload.str("toolName")?.takeIf { it.isNotBlank() }
                if (approvalId == null || toolName == null) {
                    log.warn("dsh approval/requested missing approvalId/toolName — ignored")
                    return null
                }
                if (!claim(rpcId, ApprovalEntry(rpcId, sessionId, approvalId))) return null
                AgentEvent.ControlRequest(
                    requestId = DshAsk.askIdOf(rpcId),
                    toolName = toolName,
                    input = DshAsk.approvalInput(payload.str("reason"), payload.str("callId")),
                )
            }
            else -> null
        }
    }

    /**
     * False = this rpcId is already on screen (a duplicate frame).
     *
     * The reverse approval index is written under the SAME lock as the pending entry, not after it: an
     * `approval/resolved` arriving in between would miss the index, fail to withdraw the card, and strand
     * the entry until its budget expired.
     */
    private fun claim(rpcId: String, entry: Entry): Boolean = synchronized(lock) {
        if (pending.containsKey(rpcId)) {
            log.info("dsh ask $rpcId is already pending — duplicate frame, no second card")
            return@synchronized false
        }
        if (entry is ApprovalEntry) {
            // A re-issued approvalId would otherwise leave its predecessor unreachable in `pending`
            // forever, since only answer/resolved release entries.
            byApproval.put(entry.approvalId, rpcId)?.let { stale ->
                if (stale != rpcId) {
                    pending.remove(stale)
                    log.warn("dsh approval ${entry.approvalId} re-issued on a new rpcId — dropped stale $stale")
                }
            }
        }
        pending[rpcId] = entry
        true
    }

    /**
     * A `…/resolved` broadcast → the [AgentEvent.ControlCancel] that retires our card, or null when the
     * request is not (or no longer) ours. Fires for EVERY outcome — including the `answered` that our own
     * verdict caused, which by then has already left the table, so a self-inflicted cancel is impossible.
     */
    fun resolved(method: String, payload: JsonObject): AgentEvent.ControlCancel? {
        val rpcId = when (method) {
            // question/resolved echoes the RPC id; approval/resolved echoes the APPROVAL id.
            QUESTION_RESOLVED -> payload.str("questionRpcId")
            APPROVAL_RESOLVED -> payload.str("approvalId")?.let { synchronized(lock) { byApproval[it] } }
            else -> null
        } ?: return null
        release(rpcId) ?: return null
        log.info("dsh ask $rpcId resolved elsewhere (${payload.str("outcome")}) — withdrawing the card")
        return AgentEvent.ControlCancel(DshAsk.askIdOf(rpcId))
    }

    private fun release(rpcId: String): Entry? = synchronized(lock) {
        pending.remove(rpcId)?.also { if (it is ApprovalEntry) byApproval.remove(it.approvalId) }
    }

    /**
     * Answer one card. [updatedInput] is the permission bridge's merged question input (original input +
     * the phone's `answers` map + any freeform `response`); it is null for a deny and for a timeout.
     *
     * Returns false when there was nothing pending to answer — a duplicate/late verdict, which is normal
     * and silent.
     */
    suspend fun answer(askId: String, allow: Boolean, updatedInput: String?): Boolean {
        val rpcId = DshAsk.rpcIdOf(askId) ?: return false
        val entry = release(rpcId) ?: return false
        val value = when (entry) {
            is ApprovalEntry -> DshAsk.approvalValue(entry.sessionId, entry.approvalId, allow)
            is QuestionEntry -> {
                // A DENY or a timeout on a QUESTION is dsh's "skipped" batch: it has no reject outcome for
                // one, and leaving it unanswered is the fail-HANG this bridge exists to remove.
                val merged = if (allow) updatedInput?.let { DshTranscript.parseLine(it) } else null
                DshAsk.answerValue(
                    entry.sessionId,
                    entry.questions,
                    answers = merged?.obj("answers")?.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull.orEmpty() },
                    response = merged?.str("response"),
                )
            }
        }
        val result = runCatching { send(rpcId, value) }.getOrElse { DshRespond.UNREACHABLE }
        if (!result.benign) {
            // Never silent (design §3.1): a refused response means the turn is still hanging on dsh's side
            // and nothing else will ever say so.
            val what = if (entry is QuestionEntry) "answer the question" else "send the approval decision"
            log.warn("dsh respond $rpcId refused: ${result.reason}")
            // The two failures read very differently to a person, so they are not phrased the same: a
            // carrier fault is "we could not reach it", a refusal is "it said no to what we sent".
            report(
                if (result.reason == DshRespond.UNREACHABLE.reason) {
                    "could not $what — the DeepSeek Harness local API is unreachable. That turn is still waiting."
                } else {
                    "could not $what — the DeepSeek Harness refused the reply (${result.reason ?: "no reason given"})."
                },
            )
        }
        return true
    }

    companion object {
        const val QUESTION_REQUESTED = "question/requested"
        const val QUESTION_RESOLVED = "question/resolved"
        const val APPROVAL_REQUESTED = "approval/requested"
        const val APPROVAL_RESOLVED = "approval/resolved"

        /** The four methods this ledger claims off the mux. */
        val METHODS = setOf(QUESTION_REQUESTED, QUESTION_RESOLVED, APPROVAL_REQUESTED, APPROVAL_RESOLVED)
    }
}
