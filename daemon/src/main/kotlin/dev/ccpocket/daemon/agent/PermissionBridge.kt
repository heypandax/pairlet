package dev.ccpocket.daemon.agent

import dev.ccpocket.daemon.approval.ApprovalCoordinator
import dev.ccpocket.daemon.approval.ApprovalGrantStore
import dev.ccpocket.daemon.approval.ApprovalOutcome
import dev.ccpocket.daemon.approval.ApprovalSource
import dev.ccpocket.daemon.bridge.BridgeGrant
import dev.ccpocket.daemon.bridge.PathScope
import dev.ccpocket.protocol.AuthorizedActionRecorded
import dev.ccpocket.protocol.Decision
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PendingApproval
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.UUID

/**
 * Provider-neutral permission firewall — the AGENT source adapter of [ApprovalCoordinator] (approval
 * design M1): turns an [AgentEvent.ControlRequest] into a protocol [PermissionAsk], registers it with
 * the coordinator (which owns pending/timeout/withdraw/verdict-idempotency), and routes the terminal
 * outcome to the backend via [respond] (Claude writes a control_response; Codex a JSON-RPC result).
 * The translation of [ToolMetadata], the session "Always allow" rules and every auto-allow policy stay
 * HERE; the wire format lives in the backend. `askId` == the [AgentEvent.ControlRequest.requestId].
 */
class PermissionBridge(
    private val convoId: String,
    private val mode: PermissionMode,
    private val coordinator: ApprovalCoordinator,
    private val emit: suspend (Frame) -> Unit,
    private val allowRules: MutableSet<String>, // session "Always allow" scopes, owned by the Conversation
    private val respond: suspend (askId: String, allow: Boolean, remember: Boolean, originalInput: JsonObject?, updatedInput: String?, denyMessage: String?) -> Unit,
    // How long to wait for the phone's verdict before auto-denying + withdrawing the card. Unified for
    // tool approvals AND AskUserQuestion (issue #100): claude blocks indefinitely on BOTH, and a person
    // reading a Write diff on a locked phone is in the exact same spot as one reading 1–4 questions — the
    // old 30s tool window silently auto-denied them. Both now default to the generous, env-configurable
    // [ApprovalTimeout.ms]; kept as two params so a test can drive a short timeout. On timeout the card is
    // retired via [AskWithdrawn]; onCancel (control_cancel_request) and cancelAll clean up the rest.
    private val verdictTimeoutMs: Long = ApprovalTimeout.ms,
    private val questionTimeoutMs: Long = ApprovalTimeout.ms,
    // issue #201: read PER ASK (not captured at construction) so flipping the setting bites the next card
    // without relaunching the agent. Conversation wires this to "owner's own session AND the preference is
    // on"; bridge and guest constructions leave the default, keeping their bounded windows.
    private val noAutoDeny: () -> Boolean = { false },
    // The single lease length of a no-auto-deny chain — injectable only so a test can exercise a renewal
    // without waiting a day.
    private val noAutoDenyWindowMs: Long = ApprovalTimeout.NO_AUTO_DENY_WINDOW_MS,
    // issue #91: force EVERY ask on this conversation to be a one-off decision (never remembered). Set for
    // bridge-origin sessions so a single owner "always allow" can't be replayed by later attacker-supplied
    // prompts — the whole session is externally driven, so a remembered rule is a standing blank cheque.
    private val forceNeverRemember: Boolean = false,
    // BRIDGE (issue #91) defense-in-depth, driven by anyone in a chat. Two effects, both bridge-only:
    //  1. Bash gated by [BridgeCommandPolicy] before any ask (destructive→deny, provably-safe→allow).
    //  2. structured file tools (Read/Write/Edit/Glob/Grep) confined to the bound [workdir] — a bridge has
    //     no pathScope, so without this a Read of ~/.ssh/id_rsa would exfiltrate it to the chat.
    private val bridgeSession: Boolean = false,
    // issue #91 OWNER BYPASS: this is the CONFIGURED OWNER's OWN dedicated session (only ever driven by the
    // owner — the built-in engine routes non-owner messages to a SEPARATE session). The standing flag only
    // identifies who may MINT [BridgeGrant.OWNER_BYPASS]; the per-turn grant below is what actually allows
    // tools, so cancel/revoke closes buffered post-cancel asks. Set at OPEN by trusted in-process code only,
    // never the wire. Bridge-only. neverRemember gates still reach a human.
    private val ownerBypassSession: Boolean = false,
    // issue #190 / #198: the authority ONE externally submitted bridge request carries into its turn —
    // [BridgeGrant.OWNER_APPROVED] when the owner read and approved it, [BridgeGrant.AUTO_TRUSTED] when the
    // owner pre-trusted the chat it came from. Unlike a remembered allow-rule the grant is revoked at
    // TurnResult/process end, so the next request starts locked. Supplied dynamically because the
    // PermissionBridge lives for the whole agent process while the grant is per-turn.
    private val bridgeGrant: () -> BridgeGrant = { BridgeGrant.NONE },
    // Conversation overrides this with a synchronized decision so cancel/revoke cannot interleave between
    // reading a grant and sending the allow response. Tests/standalone bridges keep an equivalent re-check.
    private val useBridgeGrant: suspend (BridgeGrant, suspend () -> Unit) -> Boolean = { expected, allow ->
        if (bridgeGrant() == expected) { allow(); true } else false
    },
    // issue #91 "一次授权跑完全程": the owner-configured Bash allow-list for this bridge. A command that
    // matches (and is neither DANGEROUS nor carrying shell metacharacters) is auto-run with NO phone prompt,
    // so a whitelisted multi-step task isn't chopped up by per-command approvals in an async IM channel.
    // Empty for owner/guest sessions and for a bridge whose owner whitelisted nothing (→ prior behaviour).
    private val bridgeAllowedCommands: List<String> = emptyList(),
    // issue #115: a GUEST folder-share's canonical shared roots. Non-null → a file tool (Read/Write/Edit/…)
    // whose target lands OUTSIDE the roots is HARD-DENIED here, before any ask and regardless of mode — so
    // the guest can't reach the owner's other files even under acceptEdits, and can't be tricked into
    // approving an out-of-scope read (it never sees the ask). [workdir] resolves relative tool paths (the
    // agent runs with the session cwd, which is itself inside the scope).
    private val pathScope: List<String>? = null,
    private val workdir: String? = null,
    // SESSION-HANDOFF §8.3: the Handoff Grant's operation ceiling for a COLLABORATOR-opened
    // conversation; null for every other kind. Under REVIEW_READ_ONLY (and any access that isn't
    // explicitly CONTINUE_SCOPED — UNKNOWN clamps to the safest) the write tools are HARD-DENIED
    // before any ask exists: the recipient is the session's lease controller and answers its own
    // asks, so "every write surfaces as a PermissionAsk" would let it approve its own writes.
    // Bash still routes to the normal ask (command policy is a separate concern); Read/Glob/Grep
    // are untouched (the pathScope wall above already confines their targets).
    private val handoffAccess: dev.ccpocket.protocol.HandoffAccess? = null,
    // ── approval design M2 ──
    /** TASK-scoped grants ("允许本任务") shared with the quick terminal. Defaulted for tests. */
    private val grants: ApprovalGrantStore = ApprovalGrantStore(),
    /** The conversation's CURRENT task id (rotates per top-level prompt); stamped on asks + grant matches. */
    private val taskId: () -> String? = { null },
    /** M3 advisory risk radar — null keeps the pre-M3 behavior (no badges). Its verdict NEVER changes an
     *  approval outcome; it only rides a [dev.ccpocket.protocol.PermissionRiskUpdated] badge. */
    private val risk: dev.ccpocket.daemon.approval.ApprovalRiskEngine? = null,
    /** §18.1 P1-6: the conversation's LIVE mode — read per decision, never cached, so a Full Control
     *  expiry (or any mode switch) bites the very next tool call even mid-turn. Defaults to the launch
     *  mode for tests/legacy constructions. */
    private val currentMode: () -> PermissionMode = { mode },
) {
    // P1-6: a getter, deliberately NOT a cached val — the bypass authority must die the instant the
    // daemon's effective mode leaves BYPASS_PERMISSIONS (Full Control 1h expiry, user switch).
    private val autoAllow get() = currentMode() == PermissionMode.BYPASS_PERMISSIONS

    suspend fun onControlRequest(ev: AgentEvent.ControlRequest) {
        val meta = ToolMetadata.of(ev.toolName, ev.input)
        // HANDOFF READ-ONLY WALL (SESSION-HANDOFF §8.3, crypto review MUST-FIX): a write tool under a
        // review/read-only Handoff Grant is refused HERE — first, before every auto-allow path and
        // before any PermissionAsk is minted, exactly like the guest out-of-scope guard below. No ask
        // ever exists, so no verdict (the recipient's own, or anyone's) can approve it: the deny
        // precedes the verdict channel structurally, not by policy.
        if (handoffWriteBanned(ev.toolName)) {
            respond(ev.requestId, false, false, ev.input, null, "denied — this handoff grant is review/read-only; write tools are disabled")
            return
        }
        // ── PRE-GRANT POLICY CHECKS (§18.1 P1-8 / design §6): no request approval, remembered rule,
        // task grant or bypass mode may precede these checks. The structured-path wall is authoritative for
        // tool schemas the daemon understands; the Bash deny screen is deliberately only defense-in-depth.
        // GUEST folder-share path guard (issue #115) + bridge workdir wall: a built-in file tool whose
        // target escapes the allowed roots is denied under EVERY mode and EVERY grant — including an
        // owner-approved bridge request (P1-8: request approval must not unlock path escapes).
        outOfScopeTarget(ev.toolName, ev.input)?.let { escaped ->
            respond(ev.requestId, false, false, ev.input, null, "denied — $escaped is outside the allowed directory")
            return
        }
        // BRIDGE destructive-command screen (issue #91): classified ONCE here. A literal DENY is refused
        // before every grant branch, but the classifier is not a shell parser and must not be described as a
        // sandbox: obfuscated/desugared equivalents can classify ASK. The ALLOW leg is a convenience fast
        // path applied after explicit grants; an ungranted ASK falls through to the phone.
        val command = (ev.input?.get("command") as? JsonPrimitive)?.content
        val bridgeBash =
            if (bridgeSession && ev.toolName == "Bash") BridgeCommandPolicy.classify(command.orEmpty(), bridgeAllowedCommands)
            else null
        if (bridgeBash == BridgeCommandPolicy.Verdict.DENY) {
            respond(ev.requestId, false, false, ev.input, null, "denied — this command is blocked for a bridge (destructive/high-risk)")
            return
        }
        // ASK is the classifier's fallback verdict for Bash syntax it cannot prove safe. Under the owner's
        // three-rule model (issue #233) it gates UNAUTHORIZED and Guardian-reviewed turns. A turn the owner
        // confirmed — their own dedicated session, a request they read and approved, or a request from a
        // chat/project they durably TRUSTED — runs its shell without a per-command card.
        val bridgeBashNeedsAsk = bridgeBash == BridgeCommandPolicy.Verdict.ASK
        // M3: feed the sequence ledger on every ATTEMPT that got past the pre-grant checks (intent matters
        // for the radar) and keep the assessment for the ask below. Deterministic + advisory: no
        // outcome changes.
        val assessed = risk?.observe(
            convoId, ev.toolName,
            command = command,
            targets = ToolMetadata.pathTargets(ev.toolName, ev.input),
        )
        val grant = if (bridgeSession) bridgeGrant() else BridgeGrant.NONE
        // OWNER BYPASS (issue #91 / #233 rule ①): the configured owner's dedicated bridge session runs
        // full — the owner asked in the chat, that IS the confirmation, on their own machine. Authority
        // is nevertheless turn-scoped: cancel revokes OWNER_BYPASS under the same lock as this allow response,
        // so a buffered classifier-ASK tool arriving afterwards falls back to the phone instead of executing.
        if (
            bridgeSession && ownerBypassSession && grant == BridgeGrant.OWNER_BYPASS &&
            !meta.neverRemember && ToolMetadata.broadGrantEligible(ev.toolName)
        ) {
            if (useBridgeGrant(grant) {
                    coordinator.recordAuto(ApprovalSource.AGENT, convoId, ev.toolName, meta.rule, "owner-bypass-session")
                    respond(ev.requestId, true, false, ev.input, null, null)
                }) return
        }
        // FULL REQUEST AUTHORIZATION (issue #190 / #233 rules ②+③): either the owner read and approved this
        // exact externally submitted request, or durably TRUSTED its exact chat/project. The turn runs full —
        // including Bash the classifier could not prove safe: the machine owner's authorization IS the wall.
        // What it does not skip (P1-8): the structured-file workdir check and any literal Bash DENY above.
        // Human-decision tools remain interactive because their decision/answer rides the verdict. The
        // supplier is trusted in-process state, revoked when the turn ends.
        if (
            (grant == BridgeGrant.OWNER_APPROVED || grant == BridgeGrant.AUTO_TRUSTED) &&
            !meta.neverRemember && ToolMetadata.broadGrantEligible(ev.toolName)
        ) {
            if (useBridgeGrant(grant) {
                    coordinator.recordAuto(
                        ApprovalSource.AGENT,
                        convoId,
                        ev.toolName,
                        meta.rule,
                        if (grant == BridgeGrant.AUTO_TRUSTED) "trusted-chat-full-turn" else "owner-approved-request",
                    )
                    respond(ev.requestId, true, false, ev.input, null, null)
                }) return
        }
        // BRIDGE whitelist fast path (issue #91 "一次授权跑完全程"): provably-safe / owner-whitelisted
        // commands run without pestering the owner — a convenience grant, so it sits BEHIND the walls.
        if (bridgeBash == BridgeCommandPolicy.Verdict.ALLOW) {
            coordinator.recordAuto(ApprovalSource.AGENT, convoId, ev.toolName, meta.rule, "bridge-command-policy")
            respond(ev.requestId, true, false, ev.input, null, null)
            return
        }
        // GUARDIAN-REVIEWED GRANT: the Reviewer did not become the machine owner. Keep its closed allow-list;
        // classifier-ASK Bash and unknown tools still reach the owner. The helper also requires a resolvable
        // in-workdir target and holds writes that would persist into the owner's later execution.
        if (grant.machineConfined && !meta.neverRemember && !bridgeBashNeedsAsk && machineConfinedMayRun(ev.toolName, ev.input)) {
            if (useBridgeGrant(grant) {
                    coordinator.recordAuto(
                        ApprovalSource.AGENT, convoId, ev.toolName, meta.rule,
                        "reviewer-approved-request",
                    )
                    respond(ev.requestId, true, false, ev.input, null, null)
                }) return
        }
        // bypassPermissions auto-allows ordinary tools — but NOT the neverRemember class (issue #156): those
        // are human-decision gates that must survive every skip-the-ask path (the ToolMeta contract).
        // ExitPlanMode, because approving a plan is always an explicit, per-plan decision; AskUserQuestion,
        // because its ANSWERS ride in the verdict — an auto-allow would answer nothing ("the user did not
        // answer"). Deliberately meta.neverRemember, not forceNeverRemember: whether a bridge-origin session
        // (#91) should also re-ask under user-chosen bypass is a separate, undecided policy.
        if (autoAllow && !meta.neverRemember && !bridgeBashNeedsAsk) {
            coordinator.recordAuto(ApprovalSource.AGENT, convoId, ev.toolName, meta.rule, "bypass-permissions")
            respond(ev.requestId, true, false, ev.input, null, null)
            return
        }
        // neverRemember tools (ExitPlanMode, AskUserQuestion) are a human-decision gate: never satisfy them
        // from a remembered rule. [forceNeverRemember] extends that to EVERY ask on a bridge-origin session
        // (issue #91), so an owner's earlier "always allow" can't auto-clear a new attacker-supplied prompt.
        // §18.1 P1-2: a HANDOFF conversation's shell is confirmed ONE COMMAND AT A TIME by the DAEMON —
        // no task/session scope may ever form for it, whatever a (possibly modified) client claims.
        val handoffOneOff = handoffAccess != null && ev.toolName == "Bash"
        val neverRemember = meta.neverRemember || forceNeverRemember || handoffOneOff
        if (!neverRemember && meta.rule in allowRules) { // remembered earlier this session → auto-allow without prompting
            coordinator.recordAuto(ApprovalSource.AGENT, convoId, ev.toolName, meta.rule, "remembered-rule")
            emit(autorunChip(meta.rule, "session-rule", grantId = null, tool = ev.toolName))
            respond(ev.requestId, true, false, ev.input, null, null)
            return
        }
        // TASK grant (approval design M2): "允许本任务" issued earlier in this task covers the action —
        // auto-run with an in-stream audit chip instead of a card. Every pre-grant check above already ran;
        // a grant can only skip the ASK, never one of those checks, and it dies with the task/session/2h TTL.
        // §18.1 P1-1: the match is CONTEXT-BOUND — same canonical root, file targets provably inside it,
        // Bash bound to the exact approved command; anything unverifiable falls through to the ask.
        if (!neverRemember) {
            grants.match(
                convoId, taskId(), ev.toolName, meta.rule, commandText = command,
                root = workdir, targets = ToolMetadata.pathTargets(ev.toolName, ev.input),
            )?.let { g ->
                coordinator.recordAuto(ApprovalSource.AGENT, convoId, ev.toolName, meta.rule, "task-grant")
                emit(autorunChip(meta.rule, "task-grant", grantId = g.id, tool = ev.toolName))
                respond(ev.requestId, true, false, ev.input, null, null)
                return
            }
        }
        val isQuestion = ev.toolName == AskQuestions.TOOL
        val askId = ev.requestId
        // #201: when the owner opted into "wait for my decision", this ask runs a renewing 24h lease chain
        // instead of the (env-configurable) one-shot window.
        val infinite = noAutoDeny()
        val timeoutMs = if (infinite) noAutoDenyWindowMs else if (isQuestion) questionTimeoutMs else verdictTimeoutMs
        // Immutable authority snapshot: a later queued prompt may rotate the conversation's live task while
        // this card is waiting. The verdict can only form a Grant for the task that minted the card.
        val askTaskId = taskId()
        val ask = PermissionAsk(
            convoId, askId, ev.toolName, meta.preview, currentMode(), meta.title, meta.rule, meta.danger, meta.dangerNote, ev.diff,
            questions = if (isQuestion) AskQuestions.parse(ev.input) else null,
            neverRemember = neverRemember,
            timeoutSec = (timeoutMs / 1000).toInt(), // phone counts its local no-response fallback against the REAL window
            // approval design M2: which grant scopes this daemon honors for the ask. A neverRemember
            // decision (plan gate, question, bridge one-off) is strictly one-shot; ordinary tool asks
            // offer 允许本次 / 允许本任务 / Session. Non-null is also the client's heartbeat gate.
            taskId = askTaskId,
            grantOptions = when {
                neverRemember -> listOf("once")
                askTaskId == null -> listOf("once", "session")
                !ApprovalGrantStore.supportsTaskGrant(ev.toolName) -> listOf("once", "session")
                else -> listOf("once", "task", "session")
            },
            noAutoDeny = infinite,
        )
        val input = ev.input
        val rule = meta.rule
        coordinator.submit(
            ask, ApprovalSource.AGENT, owner = this, timeoutMs = timeoutMs, isQuestion = isQuestion, emit = emit,
            // design §9.5: ONE non-urgent second nudge at half the unwatched window — re-emitting the ask
            // re-pushes through the conversation's ask-push hook (its coalescing bounds the noise) and
            // idempotently refreshes the card client-side
            onReminder = { emit(ask) },
            maxRenewals = if (infinite) ApprovalTimeout.NO_AUTO_DENY_MAX_RENEWALS else 0,
            // re-read per renewal: switching the preference off must bound cards ALREADY in flight
            renewsAllowed = { noAutoDeny() },
        ) { outcome ->
            when (outcome) {
                is ApprovalOutcome.Answered -> {
                    val v = outcome.verdict
                    when (v.decision) {
                        Decision.ALLOW -> {
                            // §18.1 P1-2: a verdict's scope is honored ONLY if this exact ask OFFERED it
                            // (grantOptions is the daemon-derived ceiling). A claimed-but-unoffered scope
                            // clamps to allow-once — the human did tap Allow, but nothing standing forms.
                            val offered = ask.grantOptions.orEmpty()
                            val scope = v.grantScope?.takeIf { it in offered }
                            if (v.grantScope != null && scope == null) {
                                coordinator.recordAuto(ApprovalSource.AGENT, convoId, ev.toolName, rule, "scope-clamped")
                            }
                            // session memory: legacy remember=true OR the M2 grantScope="session" — a
                            // plan-approval/question/bridge/handoff one-off gate is never remembered
                            val remember = (v.remember || scope == "session") && !neverRemember && "session" in offered
                            if (remember) allowRules.add(rule) // future matching requests auto-allow this session
                            // M2 "允许本任务": issue a task grant that dies with the task/session/2h TTL,
                            // bound to the session's canonical root + the granted command (P1-1)
                            if (scope == "task" && !neverRemember && askTaskId != null) {
                                if (taskId() == askTaskId) {
                                    grants.issueTask(convoId, askTaskId, ev.toolName, rule, root = workdir, commandText = command)
                                } else {
                                    // The approved action itself remains allow-once, but no standing authority
                                    // crosses into the task that replaced the ask's original task.
                                    coordinator.recordAuto(
                                        ApprovalSource.AGENT, convoId, ev.toolName, rule,
                                        "task-grant-stale", taskId = askTaskId,
                                    )
                                }
                            }
                            // a question verdict carries the picks — merge them into the tool input (claude reads
                            // updatedInput.answers/response); other tools pass the phone's updatedInput through as-is
                            val updated =
                                if (isQuestion) AskQuestions.answeredInput(input, v.answers, v.response) ?: v.updatedInput
                                else v.updatedInput
                            respond(askId, true, remember, input, updated, null)
                        }
                        // M2 "换种安全方式": a structured retry-under-constraints reads as guidance, not a
                        // bare refusal — the agent re-plans instead of reporting "the user rejected this"
                        Decision.DENY -> respond(
                            askId, false, false, input, null,
                            if (v.retrySafer) retrySaferMessage(v.constraints, v.message) else v.message ?: "denied",
                        )
                    }
                }
                // issue #100: timing out is the ONE outcome the phone can't observe on its own (from the CLI's
                // view the request is already answered, so no control_cancel_request ever arrives) — the
                // coordinator already retired the phone's card; deny with an HONEST reason so claude doesn't
                // report a rejection the user never made.
                ApprovalOutcome.TimedOut -> respond(askId, false, false, null, null, TIMEOUT_DENY_MESSAGE)
                // agent cancelled its own request / session closing — nothing to answer, the CLI moved on
                ApprovalOutcome.Withdrawn -> {}
            }
        }
        // M3 advisory: the badge rides a separate additive frame AFTER the ask, so the card updates in
        // place without resetting the client countdown (SMART-APPROVAL §八). Old clients drop it.
        assessed?.let {
            emit(dev.ccpocket.protocol.PermissionRiskUpdated(convoId, askId, it.level, it.reason, it.reasonCodes, System.currentTimeMillis()))
        }
    }

    /** True while any ask (approval sheet / AskUserQuestion card) is still awaiting the user's verdict. The
     *  idle reaper treats such a conversation like one with running background work — a turn blocked on a
     *  question the phone hasn't answered is NOT idle, and reaping it mid-wait would silently discard the ask.
     *  This is the daemon half of issue #55: plan mode can emit a premature `result` and only surface the real
     *  AskUserQuestion minutes later — well past the 90s idle window — so without this the pending question is
     *  reaped while the phone is backgrounded. Self-bounds: [questionTimeoutMs] eventually clears the entry —
     *  or, under issue #201's no-auto-deny preference, the 7-day renewal cap does. Bounded either way, which
     *  is what keeps this predicate safe for the reaper to trust. */
    fun hasPending(): Boolean = coordinator.hasPendingFor(this)

    /** Approval rows only. AskUserQuestion needs its answer UI and remains scoped to the conversation. */
    fun pendingApprovals(): List<PendingApproval> = coordinator.rowsFor(this)

    /** Re-emit every still-open ask to [to]. A reattaching phone (foregrounded after an iOS background suspend,
     *  or any reconnect / session re-entry) never received the live PermissionAsk that fired while it was away —
     *  without this its card never reappears and the turn wedges forever on a verdict the user was never shown
     *  (issue #55). Only still-pending asks are re-emitted (answered / withdrawn / timed-out ones already left
     *  the coordinator, so a stale card never returns), and only to the reattaching sink, so a device already
     *  showing the card doesn't churn. */
    suspend fun resurfacePending(to: suspend (Frame) -> Unit) = coordinator.resurfaceFor(this, to)

    suspend fun onCancel(ev: AgentEvent.ControlCancel) {
        coordinator.withdraw(convoId, ev.requestId) // dismiss the phone's card (old phones drop the frame)
    }

    suspend fun cancelAll() {
        // issue #100: closing / relaunching the session must retire every open card on the phone too — an ask
        // the user never saw resolved would otherwise linger with live buttons that now do nothing.
        coordinator.withdrawAllFor(this)
    }

    /**
     * For a GUEST session, the first tool target that escapes the shared [pathScope] (or null when the tool
     * is in-scope / not a guarded file tool / this is an unrestricted owner session). A relative target is
     * resolved against the session [workdir] (itself inside the scope); [PathScope.contains] then
     * canonicalizes — collapsing `..` and following symlinks — so a `../../etc/passwd` or a symlink pointing
     * out of the tree is caught, mirroring the DirList/@-completion containment (#90/#67).
     */
    private fun outOfScopeTarget(tool: String, input: JsonObject?): String? {
        // GUEST: pathScope confines file tools to the shared roots. BRIDGE (issue #91): no pathScope, but a
        // structured file tool must still not escape the bound workdir — else a Read of ~/.ssh/id_rsa
        // exfiltrates it to the chat. Bash isn't guarded here because its targets are not statically
        // knowable. On an unauthorized turn its ASK verdict reaches the owner; on a #233-authorized turn
        // that broader shell reach is part of the authority the owner explicitly accepted.
        // canonicalize the implicit bridge root: PathScope.contains assumes canonical roots, and a raw
        // workdir (trailing slash / symlinked prefix / ..) would otherwise mis-compare (review N7).
        val roots = pathScope ?: (workdir?.takeIf { bridgeSession }?.let { listOf(PathScope.canonical(it) ?: it) } ?: return null)
        return ToolMetadata.pathTargets(tool, input).firstOrNull { target ->
            // A `~` form is refused OUTRIGHT, before containment — the same rule GuestGuard applies one layer up
            // (issue #152) and for the same reason: PathScope does NOT expand a tilde, so `~/.ssh/id_rsa`
            // resolves to <workdir>/~/.ssh/id_rsa and lands INSIDE the scope, while the execution side expands
            // it to the real home dir. Containment would pass and the read would escape. No legitimate
            // in-scope tool call needs one.
            if (target.startsWith("~")) return@firstOrNull true
            val abs = if (File(target).isAbsolute || workdir == null) target else File(workdir, target).path
            !PathScope.contains(roots, abs)
        }
    }

    /**
     * May a legacy machine-confined request run [tool] with no owner card? In addition to the CLOSED tool
     * allow-list, two target-shaped conditions the tool name cannot express:
     *
     *  1. A specific-file tool must have at least one target the daemon actually RESOLVED. With no target the
     *     workdir wall passes vacuously, so "in the project" would be a claim about an unknown location —
     *     e.g. a Codex fileChange whose path the daemon could not recover. Not applied to Glob/Grep, whose
     *     absent `path` legitimately means "the session cwd".
     *  2. No target may be a file that EXECUTES on the owner's next interaction. The wall bounds where bytes
     *     land, not what they mean: `.git/config` (`core.pager`, `diff.external`), a `.git/hooks` entry and
     *     `.claude/settings.json` (hooks) all run as the OWNER — and the owner's own sessions in that project
     *     are not clean-room, unlike the bridge's. So an unattended in-project write is a persistence
     *     primitive. These fall through to the owner's card (one tap), NOT a hard deny: editing them is
     *     legitimate work, it just isn't something a group member should do while nobody is looking.
     */
    private fun machineConfinedMayRun(tool: String, input: JsonObject?): Boolean {
        if (!BridgeGrant.autoRunnable(tool)) return false
        val targets = ToolMetadata.pathTargets(tool, input)
        if (tool in BridgeGrant.SPECIFIC_FILE_TOOLS && targets.isEmpty()) return false
        // Inspect the same canonical target used by containment, not the lexical alias. Otherwise
        // `safe/hooks/pre-commit` with `safe -> .git` bypasses the persistence hold.
        val canonicalTargets = targets.map { target ->
            val absolute = if (File(target).isAbsolute || workdir == null) target else File(workdir, target).path
            PathScope.canonical(absolute) ?: return false
        }
        return canonicalTargets.none { BridgeGrant.executesForTheOwner(it) }
    }

    /** Is [tool] a write tool this conversation's Handoff Grant refuses outright (§8.3)? Fail-closed
     *  on the access axis: only an explicit CONTINUE_SCOPED grants writes — REVIEW_READ_ONLY and a
     *  newer peer's UNKNOWN both land here. Codex's apply_patch is synthesized into the Claude-shaped
     *  "Edit" upstream, but the raw names stay on the list as defense in depth. */
    private fun handoffWriteBanned(tool: String): Boolean =
        handoffAccess != null &&
            handoffAccess != dev.ccpocket.protocol.HandoffAccess.CONTINUE_SCOPED &&
            tool in HANDOFF_WRITE_TOOLS

    /** The in-stream audit chip for a grant-covered auto-run (approval design M2 §9.6). [summary] is the
     *  RULE (two tokens / tool family) — deliberately not the full command: chips ride the conversation
     *  stream to every attached client and must stay as redacted as History. Old clients drop the frame. */
    private fun autorunChip(summary: String, basis: String, grantId: String?, tool: String) =
        AuthorizedActionRecorded(
            convoId = convoId, eventId = "au-" + UUID.randomUUID(), actionSummary = summary,
            basis = basis, decidedAt = System.currentTimeMillis(),
            taskId = taskId(), matchedGrantId = grantId, tool = tool,
        )

    companion object {
        /** The file-WRITE tool families a review/read-only Handoff Grant hard-refuses (§8.3): the
         *  built-in Claude names plus the raw Codex patch-tool spellings (normally synthesized to
         *  "Edit" before reaching here). Bash is deliberately absent — it keeps the normal ask. */
        val HANDOFF_WRITE_TOOLS = setOf("Write", "Edit", "MultiEdit", "NotebookEdit", "apply_patch", "ApplyPatch")

        /** "换种安全方式" (M2): phrase the deny as re-planning guidance the agent can act on. */
        fun retrySaferMessage(constraints: List<String>?, note: String?): String = buildString {
            append("The user asked you to try a SAFER approach instead — this is NOT a plain rejection. ")
            append("Re-plan this step under the following constraints, then continue the task:")
            constraints.orEmpty().forEach { append("\n- ").append(it) }
            note?.takeIf { it.isNotBlank() }?.let { append("\n- ").append(it) }
            if (constraints.isNullOrEmpty() && note.isNullOrBlank()) {
                append("\n- prefer read-only steps, avoid network access, and keep changes inside the workspace")
            }
        }

        // issue #100: distinct from a real user "deny" so claude phrases its follow-up honestly — it reads
        // this string as the deny reason. NOT "denied": the user rejected nothing, they just didn't answer.
        const val TIMEOUT_DENY_MESSAGE =
            "Approval request timed out: the user did not respond in time (they may be away from their phone). " +
                "This is NOT a denial — do not treat it as the user rejecting the request. You may retry the " +
                "operation, or continue with other work and try again later."
    }
}
