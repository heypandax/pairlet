package dev.ccpocket.daemon.agent

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
) {
    private class Pending(val input: JsonObject?, val rule: String, val neverRemember: Boolean, val timeoutJob: Job)

    private val pending = ConcurrentHashMap<String, Pending>()
    private val autoAllow = mode == PermissionMode.BYPASS_PERMISSIONS

    suspend fun onControlRequest(ev: AgentEvent.ControlRequest) {
        if (autoAllow) {
            respond(ev.requestId, true, false, ev.input, null, null)
            return
        }
        val meta = ToolMetadata.of(ev.toolName, ev.input)
        // neverRemember tools (ExitPlanMode) are a human-decision gate: never satisfy them from a remembered
        // rule — every plan must be approved explicitly, never auto-confirmed (issue #10).
        if (!meta.neverRemember && meta.rule in allowRules) { // remembered earlier this session → auto-allow without prompting
            respond(ev.requestId, true, false, ev.input, null, null)
            return
        }
        val askId = ev.requestId
        val timeout = scope.launch {
            delay(verdictTimeoutMs)
            if (pending.remove(askId) != null) respond(askId, false, false, null, null, "timed out")
        }
        pending[askId] = Pending(ev.input, meta.rule, meta.neverRemember, timeout)
        emit(PermissionAsk(convoId, askId, ev.toolName, meta.preview, mode, meta.title, meta.rule, meta.danger, meta.dangerNote, ev.diff))
    }

    suspend fun onVerdict(v: PermissionVerdict) {
        val p = pending.remove(v.askId) ?: return
        p.timeoutJob.cancel()
        when (v.decision) {
            Decision.ALLOW -> {
                val remember = v.remember && !p.neverRemember // a plan-approval gate is never remembered (issue #10)
                if (remember) allowRules.add(p.rule) // future matching requests auto-allow this session
                respond(v.askId, true, remember, p.input, v.updatedInput, null)
            }
            Decision.DENY -> respond(v.askId, false, false, p.input, null, v.message ?: "denied")
        }
    }

    fun onCancel(ev: AgentEvent.ControlCancel) {
        pending.remove(ev.requestId)?.timeoutJob?.cancel()
    }

    fun cancelAll() {
        pending.values.forEach { it.timeoutJob.cancel() }
        pending.clear()
    }
}
