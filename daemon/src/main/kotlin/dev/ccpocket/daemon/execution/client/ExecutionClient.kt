package dev.ccpocket.daemon.execution.client

import dev.ccpocket.daemon.execution.ExecutionFingerprint
import dev.ccpocket.daemon.execution.ExecutionRelayPolicy
import dev.ccpocket.daemon.execution.ExecutionSource
import dev.ccpocket.daemon.review.PeerChannel
import dev.ccpocket.daemon.review.PeerLinkStore
import dev.ccpocket.daemon.review.PeerSession
import dev.ccpocket.daemon.review.PeerTransport
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ExecutionGrantInfo
import dev.ccpocket.protocol.ExecutionGrantQuery
import dev.ccpocket.protocol.ExecutionRunAccepted
import dev.ccpocket.protocol.ExecutionRunCancel
import dev.ccpocket.protocol.ExecutionRunOutput
import dev.ccpocket.protocol.ExecutionRunResult
import dev.ccpocket.protocol.ExecutionRunState
import dev.ccpocket.protocol.ExecutionRunStatus
import dev.ccpocket.protocol.ExecutionRunSubmit
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.ToDaemon
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom

/**
 * Issue #367 G1 — the SOURCE half: "ask an already-authorised target machine to run one task, then fetch
 * the answer". Built on the G0 [ExecutionSource] link (join / grant query) and its [PeerLinkStore] files;
 * this class adds the run frames, the timeouts and the local mirror.
 *
 * What it deliberately does NOT do:
 *  - it never decides anything. Every refusal here is either a local shape check or a code the TARGET
 *    returned; there is no local allow-list that could disagree with the grant;
 *  - it never returns a credential, ticket, invite secret or private key — see [Target] and the DTOs the
 *    local control API builds from them;
 *  - a lost reply is NOT a cancel. A timeout means "this call stopped waiting", and the caller polls; only
 *    [cancel] asks the target to stop, and even that distinguishes "received" from "the process is gone".
 *
 * IDEMPOTENCE: every submit carries a requestId. Re-submitting the SAME requestId returns the SAME runId
 * from the target (`duplicate = true`), which is what makes a retry over a flaky link safe.
 */
class ExecutionClient(
    private val transport: PeerTransport,
    private val links: PeerLinkStore,
    private val runs: ExecutionClientStore,
    relayPolicy: ExecutionRelayPolicy = ExecutionRelayPolicy.DEFAULT,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val source = ExecutionSource(transport, links, relayPolicy, now)

    /** One authorised target, as the caller may see it. Scope only — never key material. */
    data class Target(
        val grantId: String,
        val label: String,
        val targetFingerprint: String,
        val workspaces: List<String>,
        val agents: List<String>,
        val approvalCeiling: String?,
        val maxConcurrentRuns: Int,
        val maxQueuedRuns: Int,
        val runTimeoutMs: Long,
        val perGrantRequestBudget: Int,
        val expiresAt: Long,
    )

    sealed interface Outcome<out T> {
        data class Ok<T>(val value: T) : Outcome<T>
        /**
         * [code] is the TARGET's stable refusal code, or one of this client's own. The three are
         * deliberately distinguishable, because they mean different things to a human:
         *
         *  - a TARGET code (`grant_expired`, `run_queue_full`, `workspace_not_allowed`, …) — the link is
         *    healthy and the target said no. Fix the request.
         *  - [LINK_UNAVAILABLE] — the DIAL itself failed: the relay no longer honours this credential, or
         *    the target is offline. The overwhelmingly common cause is that the target owner REVOKED the
         *    grant, which drops the credential and cuts the link, so there is nothing left to refuse with.
         *    Nothing to fix on this side — ask the target owner for a fresh invite.
         *  - `no_reply` — dialled fine, then silence within the timeout. A slow target, or a grant the
         *    target dropped without revoking at the relay. Retrying the SAME requestId is safe.
         */
        data class Failed(val code: String) : Outcome<Nothing>
    }

    data class Submitted(val runId: String, val requestId: String, val state: String, val duplicate: Boolean)

    data class RunStatus(
        val runId: String,
        val state: String,
        val phase: String?,
        val approvalPending: Boolean,
        val updatedAt: Long,
        val error: String?,
    )

    data class RunOutput(
        val runId: String,
        val state: String,
        val text: String,
        val cursor: String?,
        val nextCursor: String?,
        val truncated: Boolean,
        val done: Boolean,
    )

    // ---------------------------------------------------------------- establish

    /** Join at the execution door. Returns the LINK fingerprint the TARGET owner must confirm. */
    suspend fun join(
        invite: String,
        expectedTargetFingerprint: String,
        confirmedRelayAuthority: String? = null,
        label: String? = null,
    ): ExecutionSource.Join = source.join(invite, expectedTargetFingerprint, confirmedRelayAuthority, label)

    /** This link's fingerprint — 本次链路指纹, not a machine identity ([ExecutionFingerprint.SOURCE_SCOPE]). */
    fun sourceFingerprint(grantId: String): String? = source.sourceFingerprint(grantId)

    /**
     * Every target whose grant is currently ACTIVE. A link that is unreachable, still awaiting the owner's
     * confirmation, or revoked simply does not appear — `targets` is "where can I send work RIGHT NOW",
     * so a stale local row must never be listed as usable.
     */
    suspend fun targets(timeoutMs: Long = READ_TIMEOUT_MS): List<Target> = links.active().mapNotNull { link ->
        val info = when (val q = query(link.id, timeoutMs)) {
            is Outcome.Ok -> q.value
            is Outcome.Failed -> return@mapNotNull null
        }
        if (info.state != ACTIVE) return@mapNotNull null
        Target(
            grantId = info.grantId,
            label = link.label,
            targetFingerprint = link.fingerprint,
            workspaces = info.workspaceAliases,
            agents = info.agents,
            approvalCeiling = info.approvalCeiling,
            maxConcurrentRuns = info.maxConcurrentRuns,
            maxQueuedRuns = info.maxQueuedRuns,
            runTimeoutMs = info.runTimeoutMs,
            perGrantRequestBudget = info.perGrantRequestBudget,
            expiresAt = info.expiresAt,
        )
    }

    suspend fun query(grantId: String, timeoutMs: Long = READ_TIMEOUT_MS): Outcome<ExecutionGrantInfo> =
        when (val reply = exchange(grantId, ExecutionGrantQuery(grantId), timeoutMs)) {
            is ExecutionGrantInfo -> if (reply.grantId == grantId) Outcome.Ok(reply) else Outcome.Failed("grant_mismatch")
            is PocketError -> Outcome.Failed(reply.code)
            null -> Outcome.Failed(silence())
            else -> Outcome.Failed("unexpected_reply")
        }

    // ---------------------------------------------------------------- runs

    /**
     * Submit one task. The grant revision is read from the target immediately beforehand — the source has
     * no authority to assert one, and a scope change between the read and the submit is refused by the
     * target as `revision_changed`, which is the correct outcome rather than a silently widened run.
     */
    suspend fun submit(
        grantId: String,
        workspaceAlias: String,
        prompt: String,
        /** REQUIRED — the backend is part of the grant's scope and is never inferred (see [ExecutionRunSubmit.agent]). */
        agent: AgentKind,
        model: String? = null,
        mode: PermissionMode? = null,
        requestId: String = newRequestId(),
        timeoutMs: Long = SUBMIT_TIMEOUT_MS,
    ): Outcome<Submitted> {
        if (prompt.isBlank()) return Outcome.Failed("prompt_invalid")
        val info = when (val q = query(grantId, READ_TIMEOUT_MS)) {
            is Outcome.Failed -> return Outcome.Failed(q.code)
            is Outcome.Ok -> q.value
        }
        if (info.state != ACTIVE) return Outcome.Failed("grant_${info.state}")
        // PRE-FLIGHT (wire P1): the target's own list of allowed backends came back with the revision we
        // are about to submit under, so a mismatch is answerable HERE — with the same code the target
        // would have used — instead of costing a round trip to be told the obvious.
        val agentWire = dev.ccpocket.protocol.executionAgentWire(agent)
        if (info.agents.isNotEmpty() && agentWire !in info.agents) return Outcome.Failed("agent_not_allowed")
        val submit = ExecutionRunSubmit(
            requestId = requestId,
            grantId = grantId,
            revision = info.revision,
            workspaceAlias = workspaceAlias,
            agent = agentWire,
            prompt = prompt,
            model = model,
            mode = mode,
        )
        return when (val reply = exchange(grantId, submit, timeoutMs)) {
            is ExecutionRunAccepted -> {
                // wire P2: one connection carries one request, so a reply naming a DIFFERENT requestId is
                // not "a reply out of order" — it is a reply to something this caller never sent, and
                // recording its runId would attach our mirror row to someone else's run.
                if (reply.requestId != requestId) return Outcome.Failed("unexpected_reply")
                runs.put(ClientRun(reply.runId, grantId, requestId, reply.state, cursor = null, updatedAt = now()))
                Outcome.Ok(Submitted(reply.runId, requestId, reply.state, reply.duplicate))
            }
            is PocketError -> Outcome.Failed(reply.code)
            null -> Outcome.Failed(silence())
            else -> Outcome.Failed("unexpected_reply")
        }
    }

    suspend fun status(runId: String, timeoutMs: Long = READ_TIMEOUT_MS): Outcome<RunStatus> {
        val row = runs.byId(runId) ?: return Outcome.Failed("run_unknown")
        val req = newRequestId()
        return when (val reply = exchange(row.grantId, ExecutionRunStatus(req, row.grantId, runId), timeoutMs)) {
            is ExecutionRunState -> {
                if (reply.runId != runId) return Outcome.Failed("unexpected_reply") // wire P2
                runs.put(row.copy(state = reply.state, updatedAt = now()))
                Outcome.Ok(RunStatus(reply.runId, reply.state, reply.phase, reply.approvalPending, reply.updatedAt, reply.error))
            }
            is PocketError -> Outcome.Failed(reply.code)
            null -> Outcome.Failed(silence())
            else -> Outcome.Failed("unexpected_reply")
        }
    }

    /** One output page. [cursor] defaults to the mirror's own cursor, so repeated calls tail the output. */
    suspend fun result(runId: String, cursor: String? = null, timeoutMs: Long = READ_TIMEOUT_MS): Outcome<RunOutput> {
        val row = runs.byId(runId) ?: return Outcome.Failed("run_unknown")
        val req = newRequestId()
        val from = cursor ?: row.cursor
        return when (val reply = exchange(row.grantId, ExecutionRunResult(req, row.grantId, runId, from), timeoutMs)) {
            is ExecutionRunOutput -> {
                // wire P2: an output page for another run must never advance THIS run's cursor
                if (reply.runId != runId) return Outcome.Failed("unexpected_reply")
                // advance the mirror only as far as the target actually served
                runs.put(row.copy(state = reply.state, cursor = reply.nextCursor ?: from, updatedAt = now()))
                Outcome.Ok(
                    RunOutput(reply.runId, reply.state, reply.text, reply.cursor, reply.nextCursor, reply.truncated, reply.done),
                )
            }
            is PocketError -> Outcome.Failed(reply.code)
            null -> Outcome.Failed(silence())
            else -> Outcome.Failed("unexpected_reply")
        }
    }

    /** Ask the target to stop. The reply's `phase` is what says whether the process is provably gone. */
    suspend fun cancel(runId: String, timeoutMs: Long = SUBMIT_TIMEOUT_MS): Outcome<RunStatus> {
        val row = runs.byId(runId) ?: return Outcome.Failed("run_unknown")
        val req = newRequestId()
        return when (val reply = exchange(row.grantId, ExecutionRunCancel(req, row.grantId, runId), timeoutMs)) {
            is ExecutionRunState -> {
                if (reply.runId != runId) return Outcome.Failed("unexpected_reply") // wire P2
                runs.put(row.copy(state = reply.state, updatedAt = now()))
                Outcome.Ok(RunStatus(reply.runId, reply.state, reply.phase, reply.approvalPending, reply.updatedAt, reply.error))
            }
            is PocketError -> Outcome.Failed(reply.code)
            null -> Outcome.Failed(silence())
            else -> Outcome.Failed("unexpected_reply")
        }
    }

    // ---------------------------------------------------------------- transport

    /**
     * One connect, one request, one reply. Mirrors [ExecutionSource.query]: the first frame that DECRYPTS
     * proves the target bound this key, so the one-time first-contact PSK is burned there and every later
     * connect is an ordinary pinned reconnect.
     */
    /**
     * One connect + one request + at most one reply. Null means "no frame came back"; [dialFailed] then
     * says whether the CONNECTION failed (→ [LINK_UNAVAILABLE]) or the target simply stayed silent
     * (→ `no_reply`). Distinguishing them is the whole point: a revoked grant drops the credential AND
     * cuts the link on the target, so the source sees a dial failure and must not report it as a timeout.
     */
    private suspend fun exchange(grantId: String, request: ToDaemon, timeoutMs: Long): Frame? {
        dialFailed = false
        val link = links.byId(grantId)?.takeIf { !it.removed } ?: return PocketError("link_unknown", "no such execution link")
        val secret = links.beginHandshake(grantId) ?: return PocketError("link_unknown", "no such execution link")
        val reply = CompletableDeferred<Frame>()
        val session = object : PeerSession {
            override suspend fun onOpen(channel: PeerChannel) = channel.send(request)
            override suspend fun onFrame(channel: PeerChannel, frame: Frame) { reply.complete(frame) }
        }
        val failed = java.util.concurrent.atomic.AtomicBoolean(false)
        val frame = withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                val dial = async {
                    runCatching { transport.dial(link, secret, session) }
                        .onFailure { if (it !is kotlinx.coroutines.CancellationException) failed.set(true) }
                }
                select<Unit> {
                    reply.onAwait {}
                    dial.onAwait {}
                }
                dial.cancel()
                if (reply.isCompleted) reply.await() else null
            }
        }
        if (frame == null) {
            // a dial that failed AFTER a reply landed is just the connection closing behind us
            dialFailed = failed.get()
            return null
        }
        links.clearTicket(grantId)
        return frame
    }

    /** Set by the last [exchange]: did the CONNECTION fail, as opposed to the target going quiet? */
    @Volatile
    private var dialFailed = false

    /** The code a null [exchange] result deserves — see [Outcome.Failed]. */
    private fun silence(): String = if (dialFailed) LINK_UNAVAILABLE else "no_reply"

    companion object {
        const val SUBMIT_TIMEOUT_MS = 30_000L
        const val READ_TIMEOUT_MS = 20_000L

        /**
         * The link is GONE, not refusing: the dial failed. Almost always the target owner revoked the
         * grant — #367 makes a revoke drop the credential and cut the live session in one step, so there
         * is deliberately nothing left that could seal a `grant_revoked` back. Callers must render this
         * as "this target is no longer reachable — ask for a new invite", never as a transient error.
         */
        const val LINK_UNAVAILABLE = "link_unavailable"
        private const val ACTIVE = "active"
        private val RNG = SecureRandom()

        fun defaultLinkPaths(): Pair<java.io.File, java.io.File> =
            dev.ccpocket.daemon.review.ReviewFiles.path("execution-links.json") to
                dev.ccpocket.daemon.review.ReviewFiles.path("execution-link-secrets.json")

        /** A caller that supplies no requestId gets a fresh one; retrying then means REUSING it. */
        fun newRequestId(): String = "rq_" + dev.ccpocket.daemon.review.b64(ByteArray(12).also { RNG.nextBytes(it) })
    }
}
