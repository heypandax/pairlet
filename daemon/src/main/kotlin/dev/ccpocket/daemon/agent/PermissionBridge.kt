package dev.ccpocket.daemon.agent

import dev.ccpocket.protocol.AskWithdrawn
import dev.ccpocket.protocol.Decision
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PermissionVerdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Provider-neutral permission firewall: turns an [AgentEvent.ControlRequest] into a protocol
 * [PermissionAsk], awaits a [PermissionVerdict] (or times out -> deny), and routes the decision to the
 * backend via [respond] (Claude writes a control_response; Codex a JSON-RPC result). The translation
 * of [ToolMetadata] + the session "Always allow" rules live here; the wire format lives in the backend.
 * `askId` == the [AgentEvent.ControlRequest.requestId].
 */
class PermissionBridge(
    private val convoId: String,
    private val mode: PermissionMode,
    private val scope: CoroutineScope,
    private val emit: suspend (Frame) -> Unit,
    private val allowRules: MutableSet<String>, // session "Always allow" scopes, owned by the Conversation
    private val respond: suspend (askId: String, allow: Boolean, remember: Boolean, originalInput: JsonObject?, updatedInput: String?, denyMessage: String?) -> Unit,
    private val verdictTimeoutMs: Long = 30_000,
    // AskUserQuestion asks wait much longer: claude itself waits indefinitely for answers, and a person
    // reading 1–4 questions needs more than the 30s tool-approval window. Withdrawals still clean up
    // via onCancel (control_cancel_request), and closing the session cancels everything.
    private val questionTimeoutMs: Long = 600_000,
) {
    private class Pending(val input: JsonObject?, val rule: String, val neverRemember: Boolean, val isQuestion: Boolean, val timeoutJob: Job)

    private val pending = ConcurrentHashMap<String, Pending>()
    private val autoAllow = mode == PermissionMode.BYPASS_PERMISSIONS

    suspend fun onControlRequest(ev: AgentEvent.ControlRequest) {
        // AskUserQuestion carries its ANSWERS in the verdict — an auto-allow would answer nothing
        // ("the user did not answer"), so questions reach the phone even under bypassPermissions.
        val isQuestion = ev.toolName == AskQuestions.TOOL
        if (autoAllow && !isQuestion) {
            respond(ev.requestId, true, false, ev.input, null, null)
            return
        }
        val meta = ToolMetadata.of(ev.toolName, ev.input)
        // neverRemember tools (ExitPlanMode, AskUserQuestion) are a human-decision gate: never satisfy them
        // from a remembered rule — every plan/question must be answered explicitly (issue #10).
        if (!meta.neverRemember && meta.rule in allowRules) { // remembered earlier this session → auto-allow without prompting
            respond(ev.requestId, true, false, ev.input, null, null)
            return
        }
        val askId = ev.requestId
        val timeout = scope.launch {
            delay(if (isQuestion) questionTimeoutMs else verdictTimeoutMs)
            if (pending.remove(askId) != null) respond(askId, false, false, null, null, "timed out")
        }
        pending[askId] = Pending(ev.input, meta.rule, meta.neverRemember, isQuestion, timeout)
        emit(
            PermissionAsk(
                convoId, askId, ev.toolName, meta.preview, mode, meta.title, meta.rule, meta.danger, meta.dangerNote, ev.diff,
                questions = if (isQuestion) AskQuestions.parse(ev.input) else null,
                neverRemember = meta.neverRemember,
            ),
        )
    }

    suspend fun onVerdict(v: PermissionVerdict) {
        val p = pending.remove(v.askId) ?: return
        p.timeoutJob.cancel()
        when (v.decision) {
            Decision.ALLOW -> {
                val remember = v.remember && !p.neverRemember // a plan-approval gate is never remembered (issue #10)
                if (remember) allowRules.add(p.rule) // future matching requests auto-allow this session
                // a question verdict carries the picks — merge them into the tool input (claude reads
                // updatedInput.answers/response); other tools pass the phone's updatedInput through as-is
                val updated =
                    if (p.isQuestion) AskQuestions.answeredInput(p.input, v.answers, v.response) ?: v.updatedInput
                    else v.updatedInput
                respond(v.askId, true, remember, p.input, updated, null)
            }
            Decision.DENY -> respond(v.askId, false, false, p.input, null, v.message ?: "denied")
        }
    }

    suspend fun onCancel(ev: AgentEvent.ControlCancel) {
        val p = pending.remove(ev.requestId) ?: return
        p.timeoutJob.cancel()
        emit(AskWithdrawn(convoId, ev.requestId)) // dismiss the phone's card (old phones drop the frame)
    }

    fun cancelAll() {
        pending.values.forEach { it.timeoutJob.cancel() }
        pending.clear()
    }
}
