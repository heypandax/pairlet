package dev.ccpocket.daemon.execution

import dev.ccpocket.daemon.bridge.TierClamp
import dev.ccpocket.daemon.conversation.KeyedSink
import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.AskWithdrawn
import dev.ccpocket.protocol.CancelTurn
import dev.ccpocket.protocol.EXECUTION_FRAME_BUDGET_BYTES
import dev.ccpocket.protocol.ExecutionGrantInfo
import dev.ccpocket.protocol.ExecutionGrantQuery
import dev.ccpocket.protocol.ExecutionRunAccepted
import dev.ccpocket.protocol.ExecutionRunCancel
import dev.ccpocket.protocol.ExecutionRunOutput
import dev.ccpocket.protocol.ExecutionRunResult
import dev.ccpocket.protocol.ExecutionRunStatus
import dev.ccpocket.protocol.ExecutionRunSubmit
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.ToDaemon
import dev.ccpocket.protocol.TurnDone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import dev.ccpocket.protocol.ExecutionRunState as RunStateFrame

/**
 * Issue #367 G1 — the TARGET's run plane: the only thing an authenticated EXECUTION link can make this
 * machine do. It owns nothing the owner planes own; it re-derives its authority from the persisted grant
 * on EVERY frame ([ExecutionAuthorizer]) and drives ordinary sessions through [SessionRegistry].
 *
 * Three properties the review asked for, stated where they are implemented:
 *
 *  1. NO IMPLIED AUTHORITY. A frame carries ids and text, never permission. Workspace root, agent,
 *     permission ceiling, concurrency, timeout and request budget all come from the grant, and a queued
 *     run re-checks every one of them immediately before it starts ([startLocked]) — "it passed when it
 *     was queued" is not a permission.
 *  2. THE REMOTE CALLER IS NOT AN APPROVER. A [PermissionAsk] puts the run in
 *     [ExecutionRunState.WAITING_APPROVAL] and rides the TARGET owner's existing approval chain. There is
 *     no verdict frame in the execution wire and no verdict path here: the source can only observe.
 *  3. THE JOURNAL DECIDES, NOT THE LINK. Accept is durable before the ACK, terminal states are monotonic,
 *     and a crash mid-run resolves to INTERRUPTED_UNKNOWN and is never retried.
 *
 * CONCURRENCY. [gate] serialises SCHEDULING only (submit capacity, queue pump, start). A session callback
 * ([onSessionFrame]) must NEVER take it: `startLocked` holds it while calling into [SessionRegistry],
 * which emits into this service's own sink — a reentrant wait there would deadlock the whole plane. The
 * journal is independently synchronised and enforces monotonicity, which is where the real invariant is.
 */
class RunService(
    private val store: ExecutionGrantStore,
    private val journal: RunJournal,
    private val registry: SessionRegistry,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) : ExecutionRunPlane {

    private val log = logger("ExecutionRun")
    private val gate = Mutex()

    /** runId -> the submitted prompt. IN MEMORY ONLY: a prompt that died with the process must not be
     *  resurrected, and recovery marks exactly that case `interrupted_before_start`. */
    private val prompts = ConcurrentHashMap<String, String>()

    /** runId -> ask ids this run is currently blocked on. Diagnostic; the coordinator is the authority. */
    private val openAsks = ConcurrentHashMap<String, MutableSet<String>>()

    // ================================================================= ingress

    override suspend fun handle(deviceId: String, linkPubB64: String, frame: ToDaemon, reply: suspend (Frame) -> Unit) {
        val out: Frame = when (frame) {
            is ExecutionGrantQuery -> onGrantQuery(deviceId, linkPubB64, frame)
            is ExecutionRunSubmit -> onSubmit(deviceId, linkPubB64, frame)
            is ExecutionRunStatus -> onStatus(deviceId, linkPubB64, frame)
            is ExecutionRunResult -> onResult(deviceId, linkPubB64, frame)
            is ExecutionRunCancel -> onCancel(deviceId, linkPubB64, frame)
            // The transport already applied ExecutionCaps.ingressAllowed; this is the second, independent
            // "everything unlisted is denied", so a caps addition cannot silently reach the run plane.
            else -> denied("execution_forbidden")
        }
        reply(out)
    }

    /** Recovery/maintenance tick: pending revoke writes, grant lifecycle, run timeouts, retention, queue. */
    override suspend fun maintain() {
        store.retryIfDue(now())
        val t = now()
        for (run in journal.live()) {
            val grant = store.byId(run.grantId)
            val lifecycle = grant?.let { ExecutionAuthorizer.lifecycleDeny(it, store.observeClock(t)) }
            val deny = when {
                grant == null -> "grant_unknown"
                lifecycle != null -> lifecycle
                grant.revision != run.revision -> "revision_changed"
                else -> null
            }
            if (deny != null) {
                stop(run, deny, ExecutionRunState.FAILED)
                continue
            }
            val deadline = run.deadlineAt
            if (deadline != null && deadline <= t) {
                stop(run, "run_timeout", ExecutionRunState.EXPIRED)
                continue
            }
            refreshApproval(run.runId)
        }
        gate.withLock { pumpLocked() }
        journal.purge()
    }

    // ================================================================= handlers

    private fun onGrantQuery(deviceId: String, pin: String, f: ExecutionGrantQuery): Frame {
        return when (val d = ExecutionAuthorizer.query(store, deviceId, pin, f.grantId, now())) {
            is ExecutionAuthorizer.Decision.Allow -> grantInfo(d.grant)
            is ExecutionAuthorizer.Decision.Deny -> denied(d.code)
        }
    }

    private suspend fun onSubmit(deviceId: String, pin: String, f: ExecutionRunSubmit): Frame {
        if (!RunJournal.validRequestId(f.requestId)) return denied("request_id_invalid")
        // the backend is a SCOPE decision and is never inferred here — see [ExecutionRunSubmit.agent].
        // An unresolvable wire name is `agent_not_allowed`: a backend this build cannot even name is
        // certainly not one the owner's grant allows.
        val agent = dev.ccpocket.protocol.executionAgentOrNull(f.agent) ?: return denied("agent_not_allowed")
        val grant = when (val d = ExecutionAuthorizer.run(store, deviceId, pin, f.grantId, f.revision, f.workspaceAlias, agent, now())) {
            is ExecutionAuthorizer.Decision.Deny -> return denied(d.code)
            is ExecutionAuthorizer.Decision.Allow -> d.grant
        }
        val prompt = f.prompt
        if (prompt.isBlank() || prompt.toByteArray(Charsets.UTF_8).size > MAX_PROMPT_BYTES) return denied("prompt_invalid")
        val model = f.model
        if (model != null && !validModel(model)) return denied("model_invalid")
        // NEVER BYPASS. The clamp cannot produce it from an allowed ceiling; the second line means a future
        // widening of ALLOWED_CEILINGS still cannot make it reachable from here by accident.
        val clamped = TierClamp.clampTo(f.mode ?: grant.approvalCeiling, grant.approvalCeiling)
        val mode = if (clamped == PermissionMode.BYPASS_PERMISSIONS) PermissionMode.DEFAULT else clamped
        // wire contract 2: hash the mode the SOURCE ASKED FOR, never the clamped result. The clamp moves
        // when the owner moves the ceiling, so hashing it would turn an honest retry of an unchanged
        // request into `run_conflict` the moment the grant was revised.
        val hash = RunJournal.payloadHash(f.grantId, f.requestId, f.workspaceAlias, agent, f.mode, model, prompt)

        val reply = gate.withLock {
            val existing = journal.byKey(f.grantId, f.requestId)
            if (existing != null) {
                // a retry of the same request gets the ORIGINAL run id, never a second run
                if (existing.payloadHash != hash) return@withLock denied("run_conflict")
                return@withLock ExecutionRunAccepted(f.requestId, existing.runId, existing.state.wire, duplicate = true, revision = existing.revision)
            }
            val live = journal.liveOfGrant(f.grantId)
            val active = live.count { it.state != ExecutionRunState.ACCEPTED }
            val queued = live.count { it.state == ExecutionRunState.ACCEPTED }
            if (active >= grant.maxConcurrentRuns && queued >= grant.maxQueuedRuns) return@withLock denied("run_queue_full")
            when (val a = journal.accept(f.grantId, f.requestId, f.revision, f.workspaceAlias, agent, mode, model, hash, grant.perGrantRequestBudget)) {
                is RunJournal.Accept.Refused -> denied(a.code)
                is RunJournal.Accept.Ok -> {
                    prompts[a.run.runId] = prompt
                    ExecutionRunAccepted(f.requestId, a.run.runId, a.run.state.wire, duplicate = a.duplicate, revision = a.run.revision)
                }
            }
        }
        if (reply is ExecutionRunAccepted && !reply.duplicate) scope.launch { gate.withLock { pumpLocked() } }
        return reply
    }

    private suspend fun onStatus(deviceId: String, pin: String, f: ExecutionRunStatus): Frame {
        val run = lookup(deviceId, pin, f.grantId, f.runId) ?: return denied("run_not_found")
        refreshApproval(run.runId)
        return state(f.requestId, journal.byId(run.runId) ?: run)
    }

    private suspend fun onResult(deviceId: String, pin: String, f: ExecutionRunResult): Frame {
        val run = lookup(deviceId, pin, f.grantId, f.runId) ?: return denied("run_not_found")
        refreshApproval(run.runId)
        val cur = journal.byId(run.runId) ?: run
        val offset = when (val c = f.cursor) {
            null, "" -> 0
            else -> c.toIntOrNull()?.takeIf { it >= 0 } ?: return denied("cursor_invalid")
        }
        return page(f.requestId, cur, offset)
    }

    private suspend fun onCancel(deviceId: String, pin: String, f: ExecutionRunCancel): Frame {
        val run = lookup(deviceId, pin, f.grantId, f.runId) ?: return denied("run_not_found")
        // idempotent: cancelling a finished run reports the terminal truth, it does not "re-cancel"
        if (run.state.terminal) return state(f.requestId, run, phase = PHASE_ENDED)
        journal.noteCancelRequested(run.runId)
        val convoId = run.convoId
        if (convoId == null) {
            // never reached a backend, so this is provably over — nothing ran
            prompts.remove(run.runId)
            val ended = finishRun(run.runId, ExecutionRunState.CANCELLED, "cancelled_before_start")
            scope.launch { gate.withLock { pumpLocked() } }
            return state(f.requestId, ended ?: run, phase = PHASE_NOT_STARTED)
        }
        val stopped = withTimeoutOrNull(CANCEL_CONFIRM_MS) {
            registry.cancelTurn(CancelTurn(convoId))
            registry.close(convoId, force = true)
        }
        if (stopped == true) {
            val ended = finishRun(run.runId, ExecutionRunState.CANCELLED, "cancelled")
            scope.launch { gate.withLock { pumpLocked() } }
            return state(f.requestId, ended ?: run, phase = PHASE_ENDED)
        }
        // HONEST: the request is recorded and the interrupt was sent, but this side cannot yet say the
        // process is gone. Never conflate "received" with "stopped"; maintain() finalises it later.
        return state(f.requestId, journal.byId(run.runId) ?: run, phase = PHASE_CANCEL_REQUESTED)
    }

    // ================================================================= scheduling

    /** Start whatever the grants' concurrency ceilings currently allow. Caller holds [gate]. */
    private suspend fun pumpLocked() {
        for (run in journal.live().filter { it.state == ExecutionRunState.ACCEPTED }.sortedBy { it.createdAt }) {
            val grant = store.byId(run.grantId) ?: continue
            val active = journal.liveOfGrant(run.grantId).count { it.state != ExecutionRunState.ACCEPTED }
            if (active >= grant.maxConcurrentRuns) continue
            startLocked(run.runId)
        }
    }

    /** Take one ACCEPTED run to a live session, re-authorising it first. Caller holds [gate]. */
    private suspend fun startLocked(runId: String) {
        val run = journal.byId(runId) ?: return
        if (run.state != ExecutionRunState.ACCEPTED) return
        val prompt = prompts[runId]
        if (prompt == null) {
            finishRun(runId, ExecutionRunState.FAILED, "prompt_lost")
            return
        }
        val bound = store.byId(run.grantId)
        val pin = bound?.sourceLinkPub
        val deviceId = bound?.sourceDeviceId
        if (pin == null || deviceId == null) {
            finishRun(runId, ExecutionRunState.FAILED, "grant_unknown")
            return
        }
        val decision = ExecutionAuthorizer.run(store, deviceId, pin, run.grantId, run.revision, run.workspaceAlias, run.agent, now())
        val grant = when (decision) {
            is ExecutionAuthorizer.Decision.Deny -> { finishRun(runId, ExecutionRunState.FAILED, decision.code); return }
            is ExecutionAuthorizer.Decision.Allow -> decision.grant
        }
        val root = decision.workdir
        if (root == null) {
            finishRun(runId, ExecutionRunState.FAILED, "workspace_not_allowed")
            return
        }

        journal.transition(runId, ExecutionRunState.STARTING, deadlineAt = now() + grant.runTimeoutMs)
        val sink = KeyedSink(
            SINK_KEY_PREFIX + runId,
            OutboundSink { frame -> onSessionFrame(runId, frame) },
            // no human is attached to THIS sink — an ask must keep its bounded window and must reach the
            // TARGET owner's phone instead (the ask-push path reads exactly this flag)
            watching = false,
        )
        val convoId = runCatching {
            registry.open(
                OpenSession(
                    workdir = root,
                    resumeId = null,   // a run NEVER continues an existing session (MVP scope)
                    model = run.model,
                    mode = run.mode,
                    agent = run.agent,
                    takeOver = false,  // never wrestle a live writer for a session
                ),
                sink,
                // origin + pathScope are what make this conversation restricted: ownerCreated = false
                // (so #360 never registers it as one of the owner's managed sessions), clean-room launch,
                // and the file-tool wall in PermissionBridge
                origin = RunService.originOf(run.grantId),
                pathScope = listOf(root),
                handoffAccess = null,
                // #367 LOW-3: an approval from this run reaches the OWNER's phone naming the source they
                // authorised and the link fingerprint they confirmed — never the opaque grant id. The
                // remote caller still cannot answer it; there is no verdict frame on the execution wire.
                askOriginLabel = askOriginLabelFor(grant),
            )
        }.getOrElse {
            log.warn("execution run ${runId.take(10)}… could not open a session (${it::class.simpleName})")
            ""
        }
        if (convoId.isEmpty()) {
            finishRun(runId, ExecutionRunState.FAILED, "agent_unavailable")
            return
        }
        journal.transition(runId, ExecutionRunState.RUNNING, convoId = convoId)
        prompts.remove(runId)
        val delivered = runCatching { registry.sendPrompt(SendPrompt(convoId, prompt, promptId = PROMPT_ID_PREFIX + runId)) }
            .getOrDefault(false)
        if (!delivered) {
            runCatching { registry.close(convoId, force = true) }
            finishRun(runId, ExecutionRunState.FAILED, "session_unavailable")
        }
    }

    // ================================================================= session events

    /**
     * The run's view of its own session — a small, closed set; everything else a conversation emits is for
     * a human client and has no run meaning. MUST NOT take [gate] (see the class KDoc).
     */
    private fun onSessionFrame(runId: String, frame: Frame) {
        when (frame) {
            is PermissionAsk -> {
                openAsks.getOrPut(runId) { java.util.Collections.synchronizedSet(HashSet()) } += frame.askId
                journal.transition(runId, ExecutionRunState.WAITING_APPROVAL, approvalPending = true)
            }
            is AskWithdrawn -> {
                val open = openAsks[runId]
                open?.remove(frame.askId)
                if (open == null || open.isEmpty()) {
                    journal.transition(runId, ExecutionRunState.RUNNING, approvalPending = false, note = "ask_withdrawn")
                }
            }
            is TurnDone -> {
                journal.appendOutput(runId, frame.finalText.orEmpty())
                val end = if (frame.error != null) ExecutionRunState.FAILED else ExecutionRunState.COMPLETED
                val done = finishRun(runId, end, frame.error?.take(MAX_ERROR_CHARS))
                val convoId = done?.convoId
                scope.launch {
                    convoId?.let { runCatching { registry.close(it, force = true) } }
                    gate.withLock { pumpLocked() }
                }
            }
            // the agent process died without a result: the run is over and must not be replayed
            is PocketError -> if (frame.code == "process_exited") {
                finishRun(runId, ExecutionRunState.FAILED, "process_exited")
                scope.launch { gate.withLock { pumpLocked() } }
            }
            else -> Unit
        }
    }

    // ================================================================= helpers

    /** A run frame may only ever name a run of the grant the caller's own link is bound to, and only while
     *  that grant is ACTIVE — a revoked grant stops answering about its history too. */
    private fun lookup(deviceId: String, pin: String, grantId: String, runId: String): ExecutionRun? {
        val d = ExecutionAuthorizer.query(store, deviceId, pin, grantId, now())
        if (d !is ExecutionAuthorizer.Decision.Allow || d.grant.state != ExecutionGrantState.ACTIVE) return null
        return journal.byId(runId)?.takeIf { it.grantId == grantId }
    }

    /** Terminal transition + the bookkeeping every ending shares. */
    private fun finishRun(runId: String, to: ExecutionRunState, error: String?): ExecutionRun? {
        prompts.remove(runId)
        openAsks.remove(runId)
        return journal.transition(runId, to, error = error)
    }

    /** End a live run from the maintenance tick: stop the session first, then record the terminal state. */
    private suspend fun stop(run: ExecutionRun, code: String, to: ExecutionRunState) {
        run.convoId?.let { convoId ->
            withTimeoutOrNull(CANCEL_CONFIRM_MS) {
                registry.cancelTurn(CancelTurn(convoId))
                registry.close(convoId, force = true)
            }
        }
        finishRun(run.runId, to, code)
    }

    /**
     * WAITING_APPROVAL is only true while the coordinator actually holds an ask for the run's conversation.
     * An ANSWERED ask emits no frame at all ([dev.ccpocket.daemon.approval.ApprovalCoordinator.onVerdict]
     * resolves it silently), so the flag has to be re-read from the authoritative ledger rather than
     * inferred from the sink.
     */
    private suspend fun refreshApproval(runId: String) {
        val run = journal.byId(runId) ?: return
        if (run.state != ExecutionRunState.WAITING_APPROVAL) return
        val convoId = run.convoId ?: return
        val stillPending = runCatching { registry.pendingApprovals().any { it.ask.convoId == convoId } }.getOrDefault(true)
        if (!stillPending) {
            openAsks.remove(runId)
            journal.transition(runId, ExecutionRunState.RUNNING, approvalPending = false, note = "approval_resolved")
        }
    }

    private fun state(requestId: String?, run: ExecutionRun, phase: String? = null): RunStateFrame =
        RunStateFrame(
            requestId = requestId,
            runId = run.runId,
            state = run.state.wire,
            phase = phase ?: phaseOf(run),
            approvalPending = run.approvalPending,
            updatedAt = run.updatedAt,
            error = run.error,
        )

    private fun phaseOf(run: ExecutionRun): String? = when {
        run.state.terminal -> PHASE_ENDED
        run.cancelRequestedAt != null -> PHASE_CANCEL_REQUESTED
        run.state == ExecutionRunState.ACCEPTED -> PHASE_QUEUED
        else -> null
    }

    /**
     * One output page. The cursor is a UTF-8 BYTE offset and every slice is cut on a character boundary;
     * the page then SHRINKS until the ENCODED frame fits [EXECUTION_FRAME_BUDGET_BYTES], because JSON
     * escaping — not the raw text length — is what the transport actually has to carry.
     */
    private fun page(requestId: String, run: ExecutionRun, offset: Int): Frame {
        val bytes = run.output.toByteArray(Charsets.UTF_8)
        if (offset > bytes.size) return denied("cursor_invalid")
        var take = minOf(PAGE_BYTES, bytes.size - offset)
        while (true) {
            var end = offset + take
            while (end > offset && end < bytes.size && (bytes[end].toInt() and 0xC0) == 0x80) end--
            val next = if (end < bytes.size) end.toString() else null
            val out = ExecutionRunOutput(
                requestId = requestId,
                runId = run.runId,
                state = run.state.wire,
                cursor = offset.toString(),
                nextCursor = next,
                text = String(bytes, offset, end - offset, Charsets.UTF_8),
                truncated = run.truncated,
                done = run.state.terminal && next == null,
            )
            val encoded = PocketJson.encodeToString(ExecutionRunOutput.serializer(), out).toByteArray(Charsets.UTF_8).size
            if (encoded <= EXECUTION_FRAME_BUDGET_BYTES || take <= MIN_PAGE_BYTES) return out
            take /= 2
        }
    }

    /**
     * #367 security review LOW-2. A model id reaches a CLI ARGUMENT, so "no control characters" is not a
     * sufficient screen: a value beginning with `-` is read as another FLAG by every CLI this daemon
     * launches, and shell/quoting metacharacters have no business in a model name. An allow-list of the
     * characters real model ids actually use, and a first character that cannot start an option.
     */
    private fun validModel(model: String): Boolean =
        model.length in 1..128 && !model.startsWith("-") && MODEL_ID.matches(model)

    /** The owner-facing name of a run's source: the label they approved plus the link fingerprint they
     *  confirmed. Both are owner-decided values from the grant — never anything the source sent. */
    private fun askOriginLabelFor(grant: ExecutionGrant): String {
        val fp = grant.sourceLinkFingerprint?.takeIf { it.isNotBlank() }
        return "remote run · ${grant.sourceLabel}" + (fp?.let { " · $it" } ?: "")
    }

    /** Refusals carry a STABLE, log-safe code and a fixed message — never a path, key, ticket or prompt. */
    private fun denied(code: String): PocketError = PocketError(code, "execution request refused")

    companion object {
        /** `origin` marks the conversation as remotely driven; the clean-room launch, the approval windows
         *  and the execution file wall all key off exactly this prefix. */
        const val ORIGIN_PREFIX = "execution:"
        const val SINK_KEY_PREFIX = "execution-run:"
        const val PROMPT_ID_PREFIX = "exec-"
        const val PHASE_QUEUED = "queued"
        const val PHASE_CANCEL_REQUESTED = "cancel_requested"
        const val PHASE_ENDED = "process_ended"
        const val PHASE_NOT_STARTED = "not_started"

        /** How long a cancel waits for proof the process is gone before answering "request received". */
        const val CANCEL_CONFIRM_MS = 8_000L
        const val MAX_PROMPT_BYTES = 256 * 1024
        const val MAX_ERROR_CHARS = 300

        /** Characters a real backend model id uses. Anything else cannot reach a CLI argument (LOW-2). */
        private val MODEL_ID = Regex("^[A-Za-z0-9._:/-]+$")
        private const val PAGE_BYTES = 512 * 1024
        private const val MIN_PAGE_BYTES = 4 * 1024

        fun originOf(grantId: String): String = ORIGIN_PREFIX + grantId

        /** Is [origin] a remotely-driven execution session? ONE predicate, shared by the permission wall,
         *  the child-environment strip and every test that pins them. */
        fun isExecutionOrigin(origin: String?): Boolean = origin != null && origin.startsWith(ORIGIN_PREFIX)
    }
}

/** The scope disclosure an ACTIVE grant may return; a grant awaiting confirmation reports its state only. */
internal fun grantInfo(g: ExecutionGrant): ExecutionGrantInfo =
    if (g.state != ExecutionGrantState.ACTIVE) {
        ExecutionGrantInfo(g.grantId, g.revision, g.state.wire, g.expiresAt)
    } else {
        ExecutionGrantInfo(
            grantId = g.grantId,
            revision = g.revision,
            state = g.state.wire,
            expiresAt = g.expiresAt,
            workspaceAliases = g.workspaces.map { it.alias },
            agents = g.allowedAgents.map { AgentKind.serializer().descriptor.getElementName(it.ordinal) },
            approvalCeiling = PermissionMode.serializer().descriptor.getElementName(g.approvalCeiling.ordinal),
            maxConcurrentRuns = g.maxConcurrentRuns,
            maxQueuedRuns = g.maxQueuedRuns,
            runTimeoutMs = g.runTimeoutMs,
            perGrantRequestBudget = g.perGrantRequestBudget,
        )
    }
