package dev.ccpocket.daemon.control

import dev.ccpocket.daemon.execution.ExecutionFingerprint
import dev.ccpocket.daemon.execution.ExecutionGrant
import dev.ccpocket.daemon.execution.ExecutionGrantDraft
import dev.ccpocket.daemon.execution.ExecutionGrantStore
import dev.ccpocket.daemon.execution.ExecutionSource
import dev.ccpocket.daemon.execution.ExecutionTarget
import dev.ccpocket.daemon.execution.client.ExecutionClient
import dev.ccpocket.daemon.execution.encodeUri
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PocketJson
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

// ===========================================================================
//  DTOs. Same doctrine as the review half of this API (see LocalControlApi):
//  local types, never the wire frames, and nothing here has a field that could
//  hold a bearer credential, a private key or a persisted ticket. The invite
//  URI is the ONE piece of establishment material that crosses this boundary,
//  it is returned exactly once by `grants` (approve), and nothing logs it.
// ===========================================================================

@Serializable
data class LocalExecTarget(
    val grantId: String,
    val label: String,
    /** Word-free 140-bit fingerprint of the TARGET daemon's identity key ([ExecutionFingerprint]). */
    val targetFingerprint: String,
    val workspaces: List<String> = emptyList(),
    val agents: List<String> = emptyList(),
    val approvalCeiling: String? = null,
    val maxConcurrentRuns: Int = 0,
    val maxQueuedRuns: Int = 0,
    val runTimeoutMs: Long = 0,
    val perGrantRequestBudget: Int = 0,
    val expiresAt: Long = 0,
)

@Serializable
data class LocalExecTargetsRes(val ok: Boolean = true, val items: List<LocalExecTarget> = emptyList())

@Serializable
data class LocalExecRunReq(
    val target: String,
    val workspace: String,
    val prompt: String,
    /** REQUIRED — the backend is part of the grant's scope and is never inferred. `GET execution/targets`
     *  lists the ones a target allows. */
    val agent: String,
    val model: String? = null,
    val mode: String? = null,
    /** Supply one to make a retry idempotent; the daemon mints one when absent. */
    val requestId: String? = null,
)

@Serializable
data class LocalExecRunRes(
    val ok: Boolean = true,
    val runId: String,
    val requestId: String,
    val state: String,
    /** True = this exact requestId was already accepted; [runId] is the ORIGINAL run, not a second one. */
    val duplicate: Boolean = false,
)

@Serializable
data class LocalExecStatusRes(
    val ok: Boolean = true,
    val runId: String,
    val state: String,
    /** `queued` | `cancel_requested` | `process_ended` | null. Says whether a cancel actually landed. */
    val phase: String? = null,
    val approvalPending: Boolean = false,
    val updatedAt: Long = 0,
    val error: String? = null,
)

@Serializable
data class LocalExecResultRes(
    val ok: Boolean = true,
    val runId: String,
    val state: String,
    val text: String = "",
    val cursor: String? = null,
    val nextCursor: String? = null,
    val truncated: Boolean = false,
    val done: Boolean = false,
)

@Serializable
data class LocalExecJoinReq(
    val invite: String,
    /** What the TARGET owner read out to you. A mismatch refuses BEFORE the ticket is redeemed. */
    val targetFingerprint: String,
    /** `host[:port]` you were shown, when the invite names a relay this build does not already know. */
    val relayAuthority: String? = null,
    val label: String? = null,
)

@Serializable
data class LocalExecJoinRes(
    val ok: Boolean = true,
    val grantId: String,
    /** Read THIS to the target owner. It identifies the LINK, not this machine — see [fingerprintScope]. */
    val sourceLinkFingerprint: String,
    val fingerprintScope: String = ExecutionFingerprint.SOURCE_SCOPE,
    val relay: String,
)

@Serializable
data class LocalExecGrantReq(
    val label: String,
    /** alias -> absolute directory. The alias is all the source ever names; the root never leaves here. */
    val workspaces: Map<String, String>,
    val agents: List<String> = listOf("claude"),
    val approvalCeiling: String = "default",
    val ttlHours: Int = 24 * 7,
    val maxConcurrentRuns: Int = 1,
    val maxQueuedRuns: Int = 4,
    val runTimeoutMinutes: Int = 30,
    val requestBudget: Int = 200,
)

/** The approval reply. [invite] carries the one-time ticket AND the invite secret: shown once, never logged. */
@Serializable
data class LocalExecGrantCreatedRes(
    val ok: Boolean = true,
    val grantId: String,
    val invite: String,
    val ttlSec: Int,
    /** Read this to the source operator so they can pin the right target before redeeming. */
    val targetFingerprint: String,
    /**
     * What this grant's permission ceiling actually means for files, in one sentence, because the mode
     * name alone understates it: at `acceptEdits` the remote agent EDITS INSIDE THE GRANTED WORKSPACE
     * WITHOUT ASKING — the owner is not prompted per edit, only for things outside the ceiling. Rendered
     * by every surface that shows an approval so nobody learns this from a diff.
     */
    val ceilingNote: String,
)

@Serializable
data class LocalExecGrant(
    val grantId: String,
    val label: String,
    val state: String,
    val revision: Long,
    val createdAt: Long,
    val expiresAt: Long,
    val workspaces: List<String> = emptyList(),
    val agents: List<String> = emptyList(),
    val approvalCeiling: String,
    val maxConcurrentRuns: Int,
    val maxQueuedRuns: Int,
    val runTimeoutMs: Long,
    val perGrantRequestBudget: Int,
    /** Present once the source has connected: the LINK fingerprint to confirm (不是机器身份). */
    val sourceLinkFingerprint: String? = null,
    val endedReason: String? = null,
)

@Serializable
data class LocalExecGrantsRes(val ok: Boolean = true, val items: List<LocalExecGrant> = emptyList())

@Serializable
data class LocalExecGrantRes(val ok: Boolean = true, val grant: LocalExecGrant)

@Serializable
data class LocalExecConfirmReq(val fingerprint: String)

/** `cancel` has no arguments today, but POST still requires a JSON body (that IS the CSRF gate). */
@Serializable
data class LocalExecCancelReq(val reason: String? = null)

// ===========================================================================
//  Routes
// ===========================================================================

/**
 * What the #367 routes need — and nothing else. Both halves are providers because both only exist once
 * their prerequisites do: the owner plane needs the relay link (minting a connect ticket does), and the
 * source plane needs at least one joined link.
 */
class ExecutionControlDeps(
    /** Target-side owner plane: approve / confirm / revoke. Null until the relay link is up. */
    val target: () -> ExecutionTarget?,
    /** Target-side persisted grants — readable without a relay, which is why `list` is separate. */
    val grants: () -> ExecutionGrantStore?,
    /** Source-side client. Null on a build/instance with no execution links configured. */
    val client: () -> ExecutionClient?,
)

/**
 * `/v1/local/execution/…` — the #367 surface for a LOCAL caller (an Agent, the `pairlet agent` CLI, a
 * Skill). It is installed beside the review routes and reuses their gate EXACTLY ([authorize]): the 0600
 * local-control token, `Content-Type: application/json` on POST, and an outright refusal of any request
 * carrying an `Origin` header.
 *
 * It is deliberately TWO planes on one prefix:
 *  - the SOURCE plane (`targets`, `run`, `runs/…`, `join`) drives another machine that already authorised us;
 *  - the OWNER plane (`grants…`) is how THIS machine authorises someone else. Creating a grant is a new
 *    execution permission on this computer, so it exists only here, behind the token, never on the wire.
 *
 * No route returns a relay bearer credential, an E2E private key or a persisted ticket. `grants` (approve)
 * returns the invite URI once — that is establishment material by design, and nothing logs it.
 */
fun Route.installExecutionControl(core: ExecutionControlDeps, token: String) {
    val log = logger("ExecutionControl")

    // ---- source plane -------------------------------------------------------

    get("$LOCAL_CONTROL_PREFIX/execution/targets") {
        if (!call.authorize(token, post = false)) return@get
        val client = core.client() ?: return@get call.fail(HttpStatusCode.ServiceUnavailable, "execution_unavailable", "no execution client on this daemon")
        val items = client.targets().map {
            LocalExecTarget(
                grantId = it.grantId, label = it.label, targetFingerprint = it.targetFingerprint,
                workspaces = it.workspaces, agents = it.agents, approvalCeiling = it.approvalCeiling,
                maxConcurrentRuns = it.maxConcurrentRuns, maxQueuedRuns = it.maxQueuedRuns,
                runTimeoutMs = it.runTimeoutMs, perGrantRequestBudget = it.perGrantRequestBudget,
                expiresAt = it.expiresAt,
            )
        }
        call.ok(LocalExecTargetsRes.serializer(), LocalExecTargetsRes(items = items))
    }

    post("$LOCAL_CONTROL_PREFIX/execution/run") {
        if (!call.authorize(token)) return@post
        val req = call.body(LocalExecRunReq.serializer()) ?: return@post
        val client = core.client() ?: return@post call.fail(HttpStatusCode.ServiceUnavailable, "execution_unavailable", "no execution client on this daemon")
        val agent = parseAgent(req.agent) ?: return@post call.fail(HttpStatusCode.BadRequest, "agent_invalid", "unknown agent")
        val mode = req.mode?.let { parseMode(it) ?: return@post call.fail(HttpStatusCode.BadRequest, "mode_invalid", "unknown permission mode") }
        val requestId = req.requestId ?: ExecutionClient.newRequestId()
        when (
            val out = client.submit(
                grantId = req.target, workspaceAlias = req.workspace, prompt = req.prompt,
                agent = agent, model = req.model, mode = mode, requestId = requestId,
            )
        ) {
            is ExecutionClient.Outcome.Failed -> call.fail(execStatusFor(out.code), out.code, execMessageFor(out.code))
            is ExecutionClient.Outcome.Ok -> {
                // ids only: the prompt never appears in a log line
                log.info("execution run ${out.value.runId.take(10)}… accepted by ${req.target.take(10)}…")
                call.ok(
                    LocalExecRunRes.serializer(),
                    LocalExecRunRes(runId = out.value.runId, requestId = out.value.requestId, state = out.value.state, duplicate = out.value.duplicate),
                )
            }
        }
    }

    get("$LOCAL_CONTROL_PREFIX/execution/runs/{id}") {
        if (!call.authorize(token, post = false)) return@get
        val id = call.parameters["id"].orEmpty()
        if (id.isBlank()) return@get call.fail(HttpStatusCode.BadRequest, "bad_request", "run id is required")
        val client = core.client() ?: return@get call.fail(HttpStatusCode.ServiceUnavailable, "execution_unavailable", "no execution client on this daemon")
        when (val out = client.status(id)) {
            is ExecutionClient.Outcome.Failed -> call.fail(execStatusFor(out.code), out.code, execMessageFor(out.code))
            is ExecutionClient.Outcome.Ok -> call.ok(LocalExecStatusRes.serializer(), out.value.toRes())
        }
    }

    get("$LOCAL_CONTROL_PREFIX/execution/runs/{id}/result") {
        if (!call.authorize(token, post = false)) return@get
        val id = call.parameters["id"].orEmpty()
        if (id.isBlank()) return@get call.fail(HttpStatusCode.BadRequest, "bad_request", "run id is required")
        val client = core.client() ?: return@get call.fail(HttpStatusCode.ServiceUnavailable, "execution_unavailable", "no execution client on this daemon")
        when (val out = client.result(id, call.parameters["cursor"])) {
            is ExecutionClient.Outcome.Failed -> call.fail(execStatusFor(out.code), out.code, execMessageFor(out.code))
            is ExecutionClient.Outcome.Ok -> call.ok(
                LocalExecResultRes.serializer(),
                LocalExecResultRes(
                    runId = out.value.runId, state = out.value.state, text = out.value.text,
                    cursor = out.value.cursor, nextCursor = out.value.nextCursor,
                    truncated = out.value.truncated, done = out.value.done,
                ),
            )
        }
    }

    post("$LOCAL_CONTROL_PREFIX/execution/runs/{id}/cancel") {
        if (!call.authorize(token)) return@post
        val id = call.parameters["id"].orEmpty()
        if (id.isBlank()) return@post call.fail(HttpStatusCode.BadRequest, "bad_request", "run id is required")
        val client = core.client() ?: return@post call.fail(HttpStatusCode.ServiceUnavailable, "execution_unavailable", "no execution client on this daemon")
        when (val out = client.cancel(id)) {
            is ExecutionClient.Outcome.Failed -> call.fail(execStatusFor(out.code), out.code, execMessageFor(out.code))
            is ExecutionClient.Outcome.Ok -> call.ok(LocalExecStatusRes.serializer(), out.value.toRes())
        }
    }

    post("$LOCAL_CONTROL_PREFIX/execution/join") {
        if (!call.authorize(token)) return@post
        val req = call.body(LocalExecJoinReq.serializer()) ?: return@post
        val client = core.client() ?: return@post call.fail(HttpStatusCode.ServiceUnavailable, "execution_unavailable", "no execution client on this daemon")
        when (val out = client.join(req.invite, req.targetFingerprint, req.relayAuthority, req.label)) {
            is ExecutionSource.Join.Refused -> call.fail(execStatusFor(out.code), out.code, "the invite was refused")
            is ExecutionSource.Join.Ok -> {
                // NOTHING about the invite is logged: it carries a live ticket and the invite secret
                log.info("joined execution target ${out.link.id.take(10)}… — awaiting the target owner's confirmation")
                call.ok(
                    LocalExecJoinRes.serializer(),
                    LocalExecJoinRes(
                        grantId = out.link.id,
                        sourceLinkFingerprint = out.sourceFingerprint,
                        fingerprintScope = out.fingerprintScope,
                        relay = out.relay::class.simpleName?.lowercase() ?: "known",
                    ),
                )
            }
        }
    }

    // ---- owner plane --------------------------------------------------------

    get("$LOCAL_CONTROL_PREFIX/execution/grants") {
        if (!call.authorize(token, post = false)) return@get
        val store = core.grants() ?: return@get call.fail(HttpStatusCode.ServiceUnavailable, "execution_unavailable", "no execution grant store")
        store.unavailableReason?.let { return@get call.fail(HttpStatusCode.Conflict, it, "the execution grant store is not usable right now") }
        call.ok(LocalExecGrantsRes.serializer(), LocalExecGrantsRes(items = store.all().map { it.toLocal() }))
    }

    post("$LOCAL_CONTROL_PREFIX/execution/grants") {
        if (!call.authorize(token)) return@post
        val req = call.body(LocalExecGrantReq.serializer()) ?: return@post
        // SHAPE FIRST, availability second: a typo in the ceiling must read as a typo whether or not the
        // relay happens to be up, and the answer must not depend on how far the request got.
        val agents = req.agents.map { parseAgent(it) ?: return@post call.fail(HttpStatusCode.BadRequest, "agent_invalid", "unknown agent") }
        val ceiling = parseMode(req.approvalCeiling) ?: return@post call.fail(HttpStatusCode.BadRequest, "ceiling_invalid", "unknown permission mode")
        val target = core.target() ?: return@post call.fail(HttpStatusCode.ServiceUnavailable, "relay_offline", "minting an execution invite needs the relay link")
        val draft = ExecutionGrantDraft(
            sourceLabel = req.label,
            workspaces = req.workspaces,
            allowedAgents = agents,
            approvalCeiling = ceiling,
            ttlMs = req.ttlHours.toLong().coerceAtLeast(0) * 3600_000L,
            maxConcurrentRuns = req.maxConcurrentRuns,
            maxQueuedRuns = req.maxQueuedRuns,
            runTimeoutMs = req.runTimeoutMinutes.toLong().coerceAtLeast(0) * 60_000L,
            perGrantRequestBudget = req.requestBudget,
        )
        when (val res = target.approve(draft)) {
            is ExecutionTarget.Approval.Refused ->
                call.fail(execStatusFor(res.code), res.code, approveMessageFor(res.code, res.retryAfterMs))
            is ExecutionTarget.Approval.Ok -> {
                log.info("execution grant ${res.grant.grantId.take(10)}… approved — invite returned once")
                call.ok(
                    LocalExecGrantCreatedRes.serializer(),
                    LocalExecGrantCreatedRes(
                        grantId = res.grant.grantId,
                        invite = res.invite.encodeUri(),
                        ttlSec = res.invite.ttlSec,
                        targetFingerprint = res.grant.targetDaemonFingerprint,
                        ceilingNote = ceilingNote(ceiling),
                    ),
                )
            }
        }
    }

    post("$LOCAL_CONTROL_PREFIX/execution/grants/{id}/confirm") {
        if (!call.authorize(token)) return@post
        val id = call.parameters["id"].orEmpty()
        val req = call.body(LocalExecConfirmReq.serializer()) ?: return@post
        val target = core.target() ?: return@post call.fail(HttpStatusCode.ServiceUnavailable, "relay_offline", "the execution owner plane is not up")
        val store = core.grants() ?: return@post call.fail(HttpStatusCode.ServiceUnavailable, "execution_unavailable", "no execution grant store")
        when (val w = target.confirmSource(id, req.fingerprint)) {
            is ExecutionGrantStore.Write.Ok -> call.ok(LocalExecGrantRes.serializer(), LocalExecGrantRes(grant = w.grant.toLocal()))
            is ExecutionGrantStore.Write.Refused -> call.fail(HttpStatusCode.Conflict, w.code, "the fingerprint was not accepted")
            ExecutionGrantStore.Write.NotFound -> call.fail(HttpStatusCode.NotFound, "grant_not_found", "no such execution grant")
            ExecutionGrantStore.Write.Unavailable ->
                call.fail(HttpStatusCode.Conflict, store.unavailableReason ?: "store_unavailable", "the execution grant store is not usable right now")
            ExecutionGrantStore.Write.PersistFailed -> call.fail(HttpStatusCode.InternalServerError, "persist_failed", "could not record the confirmation")
        }
    }

    delete("$LOCAL_CONTROL_PREFIX/execution/grants/{id}") {
        if (!call.authorize(token, post = false)) return@delete
        val id = call.parameters["id"].orEmpty()
        val target = core.target() ?: return@delete call.fail(HttpStatusCode.ServiceUnavailable, "relay_offline", "the execution owner plane is not up")
        when (val w = target.revoke(id)) {
            is ExecutionGrantStore.Write.Ok -> {
                log.info("execution grant ${id.take(10)}… revoked via local control")
                call.ok(LocalExecGrantRes.serializer(), LocalExecGrantRes(grant = w.grant.toLocal()))
            }
            is ExecutionGrantStore.Write.Refused -> call.fail(HttpStatusCode.Conflict, w.code, "the grant could not be revoked")
            ExecutionGrantStore.Write.NotFound -> call.fail(HttpStatusCode.NotFound, "grant_not_found", "no such execution grant")
            ExecutionGrantStore.Write.Unavailable -> call.fail(HttpStatusCode.Conflict, "store_unavailable", "the execution grant store is not usable right now")
            // the revoke IS in force in memory; it is simply not durable yet (the store is now unavailable)
            ExecutionGrantStore.Write.PersistFailed ->
                call.fail(HttpStatusCode.InternalServerError, "revoke_persist_pending", "revoked in memory; the daemon is retrying the disk write")
        }
    }
}

// ---- shared helpers --------------------------------------------------------

private fun ExecutionClient.RunStatus.toRes() = LocalExecStatusRes(
    runId = runId, state = state, phase = phase, approvalPending = approvalPending, updatedAt = updatedAt, error = error,
)

/** Scope only. No ticket hash, no device id, no key — a grant row has all three and none is the CLI's. */
private fun ExecutionGrant.toLocal() = LocalExecGrant(
    grantId = grantId,
    label = sourceLabel,
    state = state.wire,
    revision = revision,
    createdAt = createdAt,
    expiresAt = expiresAt,
    workspaces = workspaces.map { it.alias },
    agents = allowedAgents.map { AgentKind.serializer().descriptor.getElementName(it.ordinal) },
    approvalCeiling = PermissionMode.serializer().descriptor.getElementName(approvalCeiling.ordinal),
    maxConcurrentRuns = maxConcurrentRuns,
    maxQueuedRuns = maxQueuedRuns,
    runTimeoutMs = runTimeoutMs,
    perGrantRequestBudget = perGrantRequestBudget,
    sourceLinkFingerprint = sourceLinkFingerprint,
    endedReason = endedReason,
)

/** Wire spelling -> enum, through the SAME serializer the protocol uses, so the CLI cannot invent names. */
internal fun parseAgent(raw: String): AgentKind? =
    runCatching { PocketJson.decodeFromString(AgentKind.serializer(), "\"${raw.trim().lowercase()}\"") }.getOrNull()

internal fun parseMode(raw: String): PermissionMode? =
    runCatching { PocketJson.decodeFromString(PermissionMode.serializer(), "\"${raw.trim()}\"") }.getOrNull()

/** Refusal code -> HTTP status. Same idea as the review table: the CLI keys on `code`, not the status. */
/**
 * What the owner is really granting, per ceiling. `acceptEdits` is the one that needs saying out loud: the
 * remote agent writes inside the granted workspace with NO prompt on this machine. The other two do prompt.
 */
internal fun ceilingNote(ceiling: PermissionMode): String = when (ceiling) {
    PermissionMode.ACCEPT_EDITS ->
        "ceiling acceptEdits: the remote agent EDITS FILES IN THE GRANTED WORKSPACE WITHOUT ASKING YOU. " +
            "Anything above that ceiling still stops for your approval on this computer."
    PermissionMode.PLAN ->
        "ceiling plan: the remote agent may read and propose, and every write stops for your approval on this computer."
    else ->
        "ceiling default: every file write and command stops for your approval on this computer."
}

/** A refusal that is a WAIT says how long; one that is a NO does not pretend otherwise. */
private fun approveMessageFor(code: String, retryAfterMs: Long?): String {
    val wait = retryAfterMs?.let { " — try again in about ${(it + 59_999) / 60_000} minute(s)" } ?: ""
    return when (code) {
        "mint_busy" ->
            "another pairing is already in progress; only one at a time is allowed, and a spent invite " +
                "holds the slot until it lapses$wait"
        "interactive_pairing_pending" ->
            "a phone pairing ticket can still be redeemed, so a headless mint has to wait$wait"
        else -> "the grant was refused"
    }
}

/** One human sentence per failure CLASS — the three [ExecutionClient.Outcome.Failed] cases mean different
 *  things to whoever is reading a CLI error, and "refused or did not answer" hid that. */
private fun execMessageFor(code: String): String = when (code) {
    ExecutionClient.LINK_UNAVAILABLE ->
        "this execution link is no longer reachable — the target owner most likely revoked the grant; ask for a new invite"
    "no_reply" -> "the target did not answer in time — retrying with the SAME requestId is safe"
    "link_unknown" -> "this daemon has no such execution link"
    else -> "the target refused the request"
}

private fun execStatusFor(code: String): HttpStatusCode = when (code) {
    "no_reply", "target_offline", "relay_offline" -> HttpStatusCode.GatewayTimeout
    // #367: the link is GONE, not slow — 410 so a caller cannot mistake it for something a retry fixes
    ExecutionClient.LINK_UNAVAILABLE -> HttpStatusCode.Gone
    "link_unknown", "run_unknown", "run_not_found", "grant_unknown" -> HttpStatusCode.NotFound
    "invite_invalid", "prompt_invalid", "model_invalid", "request_id_invalid", "cursor_invalid" -> HttpStatusCode.BadRequest
    "mint_failed", "persist_failed", "run_persist_failed" -> HttpStatusCode.InternalServerError
    else -> HttpStatusCode.Conflict
}
