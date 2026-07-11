package dev.ccpocket.daemon.agent

import dev.ccpocket.daemon.bridge.PathScope
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
import java.io.File
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
    // issue #91: force EVERY ask on this conversation to be a one-off decision (never remembered). Set for
    // bridge-origin sessions so a single owner "always allow" can't be replayed by later attacker-supplied
    // prompts — the whole session is externally driven, so a remembered rule is a standing blank cheque.
    private val forceNeverRemember: Boolean = false,
    // issue #115: a GUEST folder-share's canonical shared roots. Non-null → a file tool (Read/Write/Edit/…)
    // whose target lands OUTSIDE the roots is HARD-DENIED here, before any ask and regardless of mode — so
    // the guest can't reach the owner's other files even under acceptEdits, and can't be tricked into
    // approving an out-of-scope read (it never sees the ask). [workdir] resolves relative tool paths (the
    // agent runs with the session cwd, which is itself inside the scope).
    private val pathScope: List<String>? = null,
    private val workdir: String? = null,
) {
    // [ask] is the exact PermissionAsk frame we emitted for this request — kept so a phone that reattaches
    // after missing the live frame (backgrounded during plan mode's long post-`result` phase, issue #55)
    // can be re-shown the card verbatim via [resurfacePending].
    private class Pending(val ask: PermissionAsk, val input: JsonObject?, val rule: String, val neverRemember: Boolean, val isQuestion: Boolean, val timeoutJob: Job)

    private val pending = ConcurrentHashMap<String, Pending>()
    private val autoAllow = mode == PermissionMode.BYPASS_PERMISSIONS

    suspend fun onControlRequest(ev: AgentEvent.ControlRequest) {
        // GUEST folder-share path guard (issue #115): a built-in file tool whose target escapes the shared
        // root is denied HERE — before the auto-allow / remembered-rule / ask paths below — so it is refused
        // under EVERY mode (even acceptEdits/bypass) and the guest is never shown an ask for it. Bash is not
        // guarded (its targets aren't statically knowable), which the owner's boundary card states plainly.
        outOfScopeTarget(ev.toolName, ev.input)?.let { escaped ->
            respond(ev.requestId, false, false, ev.input, null, "denied — $escaped is outside the shared folder")
            return
        }
        // AskUserQuestion carries its ANSWERS in the verdict — an auto-allow would answer nothing
        // ("the user did not answer"), so questions reach the phone even under bypassPermissions.
        val isQuestion = ev.toolName == AskQuestions.TOOL
        if (autoAllow && !isQuestion) {
            respond(ev.requestId, true, false, ev.input, null, null)
            return
        }
        val meta = ToolMetadata.of(ev.toolName, ev.input)
        // neverRemember tools (ExitPlanMode, AskUserQuestion) are a human-decision gate: never satisfy them
        // from a remembered rule. [forceNeverRemember] extends that to EVERY ask on a bridge-origin session
        // (issue #91), so an owner's earlier "always allow" can't auto-clear a new attacker-supplied prompt.
        val neverRemember = meta.neverRemember || forceNeverRemember
        if (!neverRemember && meta.rule in allowRules) { // remembered earlier this session → auto-allow without prompting
            respond(ev.requestId, true, false, ev.input, null, null)
            return
        }
        val askId = ev.requestId
        val timeout = scope.launch {
            delay(if (isQuestion) questionTimeoutMs else verdictTimeoutMs)
            if (pending.remove(askId) != null) respond(askId, false, false, null, null, "timed out")
        }
        val ask = PermissionAsk(
            convoId, askId, ev.toolName, meta.preview, mode, meta.title, meta.rule, meta.danger, meta.dangerNote, ev.diff,
            questions = if (isQuestion) AskQuestions.parse(ev.input) else null,
            neverRemember = neverRemember,
        )
        pending[askId] = Pending(ask, ev.input, meta.rule, neverRemember, isQuestion, timeout)
        emit(ask)
    }

    /** True while any ask (approval sheet / AskUserQuestion card) is still awaiting the user's verdict. The
     *  idle reaper treats such a conversation like one with running background work — a turn blocked on a
     *  question the phone hasn't answered is NOT idle, and reaping it mid-wait would silently discard the ask.
     *  This is the daemon half of issue #55: plan mode can emit a premature `result` and only surface the real
     *  AskUserQuestion minutes later — well past the 90s idle window — so without this the pending question is
     *  reaped while the phone is backgrounded. Self-bounds: [questionTimeoutMs] eventually clears the entry. */
    fun hasPending(): Boolean = pending.isNotEmpty()

    /** Re-emit every still-open ask to [to]. A reattaching phone (foregrounded after an iOS background suspend,
     *  or any reconnect / session re-entry) never received the live PermissionAsk that fired while it was away —
     *  without this its card never reappears and the turn wedges forever on a verdict the user was never shown
     *  (issue #55). Only asks STILL in [pending] are re-emitted (answered / withdrawn / timed-out ones were
     *  already removed, so a stale card never returns), and only to the reattaching sink, so a device already
     *  showing the card doesn't churn. */
    suspend fun resurfacePending(to: suspend (Frame) -> Unit) {
        pending.values.forEach { to(it.ask) }
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

    /**
     * For a GUEST session, the first tool target that escapes the shared [pathScope] (or null when the tool
     * is in-scope / not a guarded file tool / this is an unrestricted owner session). A relative target is
     * resolved against the session [workdir] (itself inside the scope); [PathScope.contains] then
     * canonicalizes — collapsing `..` and following symlinks — so a `../../etc/passwd` or a symlink pointing
     * out of the tree is caught, mirroring the DirList/@-completion containment (#90/#67).
     */
    private fun outOfScopeTarget(tool: String, input: JsonObject?): String? {
        val roots = pathScope ?: return null
        return ToolMetadata.pathTargets(tool, input).firstOrNull { target ->
            val abs = if (File(target).isAbsolute || workdir == null) target else File(workdir, target).path
            !PathScope.contains(roots, abs)
        }
    }
}
