package dev.ccpocket.daemon.control

import dev.ccpocket.daemon.handoff.CollaboratorControl
import dev.ccpocket.daemon.review.ArtifactSyntax
import dev.ccpocket.daemon.review.PeerInboxService
import dev.ccpocket.daemon.review.ReviewLimits
import dev.ccpocket.daemon.review.ReviewOwnerService
import dev.ccpocket.daemon.review.ReviewRegistry
import dev.ccpocket.daemon.server.RequestRouter
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.CollaboratorDirection
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.ReviewBrief
import dev.ccpocket.protocol.ReviewContact
import dev.ccpocket.protocol.ReviewExecutionBundle
import dev.ccpocket.protocol.ReviewInboxAction
import dev.ccpocket.protocol.ReviewRequest
import dev.ccpocket.protocol.ReviewResult
import dev.ccpocket.protocol.ReviewStatus
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

// ===========================================================================
//  Local API DTOs. DELIBERATELY separate types from the wire frames: a wire
//  model grows fields for peers, and "we accidentally serialized the credential"
//  is precisely the accident a shared type invites. Nothing here has a field
//  that could hold a ticket, a bearer credential or a private key.
// ===========================================================================

@Serializable
data class LocalError(val ok: Boolean = false, val code: String, val message: String)

@Serializable
data class LocalInviteReq(val label: String? = null)

/** The connect URI to hand the colleague. Single use, short-lived — it IS establishment material, which
 *  is why the CLI prints it once and nothing logs it. */
@Serializable
data class LocalInviteRes(
    val ok: Boolean = true,
    val invite: String,
    val ttlSec: Int,
    val label: String? = null,
    /** The word group the JOINER will read back. Derived from the invite's daemon key and display-only —
     *  it is what makes "compare the fingerprint" a two-sided check rather than a one-sided ritual. */
    val fingerprint: String = "",
)

@Serializable
data class LocalJoinReq(val invite: String, val label: String? = null)

/** One contact row, merged across both directions (the two are separate credentials, but a human
 *  thinks of "Frank" as one person). Never carries key material — only the fingerprint, which is a
 *  DISPLAY aid both ends compute the same way. */
@Serializable
data class LocalContact(
    /** The handle every other command takes: the collaborator deviceId (outbound) or link id (inbound). */
    val id: String,
    val label: String,
    /** `outbound` = I can send them review requests; `inbound` = they can send me theirs. */
    val direction: String,
    val fingerprint: String? = null,
    val connectedAt: Long = 0,
    val removed: Boolean = false,
    /** inbound only: whose account this link lives in. */
    val peerAccountId: String? = null,
)

@Serializable
data class LocalContactsRes(val ok: Boolean = true, val items: List<LocalContact> = emptyList())

@Serializable
data class LocalContactRes(val ok: Boolean = true, val contact: LocalContact)

@Serializable
data class LocalRemoveReq(val idOrLabel: String)

/** `review send`, in the CLI's own vocabulary. Artifacts arrive as raw `mr:…` tokens and are parsed
 *  DAEMON-SIDE, so the CLI, a Skill and any future UI cannot drift on what a token means. */
@Serializable
data class LocalSendReq(
    val to: String,
    val request: String,
    val artifacts: List<String> = emptyList(),
    val title: String? = null,
    val background: String? = null,
    val focusAreas: List<String> = emptyList(),
    val knownRisks: List<String> = emptyList(),
    val completedWork: List<String> = emptyList(),
    val verification: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
    val definitionOfDone: List<String> = emptyList(),
    val dueAt: Long? = null,
    val expiresAt: Long? = null,
)

@Serializable
data class LocalReviewRes(val ok: Boolean = true, val request: ReviewRequest)

@Serializable
data class LocalReviewsRes(val ok: Boolean = true, val items: List<ReviewRequest> = emptyList())

/** One inbox row: the sender's authoritative request, plus the local truth around it. */
@Serializable
data class LocalInboxItem(
    val linkId: String,
    val peerLabel: String,
    val request: ReviewRequest,
    /** Local actions queued but not yet confirmed by the sender. */
    val pending: List<String> = emptyList(),
)

@Serializable
data class LocalInboxRes(val ok: Boolean = true, val items: List<LocalInboxItem> = emptyList())

/** `review show`: [scope] says which ledger answered — `sent` (this machine is the sender and its row
 *  is authoritative) or `received` (a non-authoritative mirror of a peer's row). */
@Serializable
data class LocalShowRes(
    val ok: Boolean = true,
    val scope: String,
    val request: ReviewRequest,
    val peerLabel: String? = null,
    val pending: List<String> = emptyList(),
)

@Serializable
data class LocalIdReq(val id: String)

@Serializable
data class LocalDeclineReq(val id: String, val reason: String? = null)

@Serializable
data class LocalRespondReq(val id: String, val result: ReviewResult)

@Serializable
data class LocalActionRes(val ok: Boolean = true, val id: String, val queued: Boolean, val status: String)

@Serializable
data class LocalPrepareRes(val ok: Boolean = true, val bundle: ReviewExecutionBundle)

// ===========================================================================
//  Routes
// ===========================================================================

/**
 * The three things the local control API needs — deliberately NOT the whole [dev.ccpocket.daemon.DaemonCore].
 * This surface must not be able to reach a session, a directory or a shell even by accident, and the
 * cheapest way to guarantee that is to never hand it the handle.
 *
 * [collaborators] is a provider, not a value: the contact plane only exists once the relay link is up
 * (minting an invite needs it), so the routes have to ask again on every call rather than capture a null.
 */
class LocalControlDeps(
    val collaborators: () -> CollaboratorControl?,
    val reviews: dev.ccpocket.daemon.review.ReviewService,
    val peerInbox: PeerInboxService,
    /**
     * The owner-plane implementation, shared with the wire router (pocket/review.* owner frames).
     * Contact resolution, prepare's refusal codes and the queued-action semantics are decided ONCE
     * there — the HTTP routes below are a transport over it, not a second copy of the rules.
     *
     * Defaulted so a test can build deps without threading one through; production passes
     * [dev.ccpocket.daemon.DaemonCore.reviewOwner] so the wire and the CLI share one instance.
     */
    val owner: ReviewOwnerService = ReviewOwnerService(collaborators, reviews, peerInbox),
)

/**
 * The token-authenticated LOCAL CONTROL API (REVIEW-REQUEST.md §6): the collaborator + ReviewRequest
 * surface the CLI and Skills drive. Installed beside the legacy loopback routes by
 * [dev.ccpocket.daemon.relay.PairLoopback], but under a separate prefix and separate rules.
 *
 * Every route here enforces three things before it does anything:
 *  - the local-control token (a browser can reach loopback; it cannot read the 0600 token file);
 *  - `Content-Type: application/json` on POST (a form/`text/plain` post is the CSRF-shaped request a
 *    page can make without a preflight — refusing it means an attacker needs a real preflight, which
 *    the missing token then fails);
 *  - NO `Origin` header at all. A CLI never sends one; every browser does. This is the cheapest honest
 *    statement of "this API is not for web pages".
 *
 * Nothing here returns a credential, ticket, private key or relay bearer — see the DTOs above.
 */
fun Route.installLocalControl(core: LocalControlDeps, token: String) {
    val log = logger("LocalControl")

    // ---- collaborators -----------------------------------------------------

    get("$LOCAL_CONTROL_PREFIX/collaborators") {
        if (!call.authorize(token, post = false)) return@get
        call.ok(LocalContactsRes.serializer(), LocalContactsRes(items = core.owner.contacts().map { it.toLocal() }))
    }

    post("$LOCAL_CONTROL_PREFIX/collaborators/invite") {
        if (!call.authorize(token)) return@post
        val req = call.body(LocalInviteReq.serializer()) ?: return@post
        // the CLI's contact commands ARE the ReviewRequest peer commands (SKILL.md), so this mints a
        // REVIEW-purpose link — never a Session Handoff runtime recipient
        when (val res = core.owner.invite(req.label)) {
            is ReviewOwnerService.Outcome.Refused -> call.fail(statusFor(res.code), res.code, res.message)
            // the URI is establishment material: returned once, never logged
            is ReviewOwnerService.Outcome.Ok -> call.ok(
                LocalInviteRes.serializer(),
                LocalInviteRes(
                    invite = res.value.uri,
                    ttlSec = res.value.ttlSec,
                    label = res.value.label,
                    fingerprint = res.value.fingerprint,
                ),
            )
        }
    }

    post("$LOCAL_CONTROL_PREFIX/collaborators/join") {
        if (!call.authorize(token)) return@post
        val req = call.body(LocalJoinReq.serializer()) ?: return@post
        when (val res = core.owner.join(req.invite, req.label)) {
            is ReviewOwnerService.Outcome.Refused -> call.fail(statusFor(res.code), res.code, res.message)
            is ReviewOwnerService.Outcome.Ok -> call.ok(LocalContactRes.serializer(), LocalContactRes(contact = res.value.toLocal()))
        }
    }

    post("$LOCAL_CONTROL_PREFIX/collaborators/remove") {
        if (!call.authorize(token)) return@post
        val req = call.body(LocalRemoveReq.serializer()) ?: return@post
        when (val res = core.owner.remove(req.idOrLabel)) {
            is ReviewOwnerService.Outcome.Refused -> call.fail(statusFor(res.code), res.code, res.message)
            is ReviewOwnerService.Outcome.Ok -> {
                log.info("contact \"${res.value.label}\" (${res.value.direction.name.lowercase()}) removed via local control")
                call.ok(LocalContactRes.serializer(), LocalContactRes(contact = res.value.toLocal()))
            }
        }
    }

    // ---- review: the SENDER's authoritative ledger --------------------------

    get("$LOCAL_CONTROL_PREFIX/reviews") {
        if (!call.authorize(token, post = false)) return@get
        val rawStatus = call.parameters["status"]
        val status = rawStatus?.let(::parseStatus)
        if (rawStatus != null && !rawStatus.equals("all", true) && status == null) {
            return@get call.fail(HttpStatusCode.BadRequest, "review_bad_status", "unknown review status")
        }
        call.ok(LocalReviewsRes.serializer(), LocalReviewsRes(items = core.reviews.registry.list(status = status)))
    }

    post("$LOCAL_CONTROL_PREFIX/reviews/send") {
        if (!call.authorize(token)) return@post
        val req = call.body(LocalSendReq.serializer()) ?: return@post
        val recipient = when (val r = core.owner.resolveRecipient(req.to)) {
            is ReviewOwnerService.Outcome.Refused -> return@post call.fail(statusFor(r.code), r.code, r.message)
            is ReviewOwnerService.Outcome.Ok -> r.value
        }
        val artifacts = req.artifacts.map { token ->
            ArtifactSyntax.parse(token).getOrElse {
                return@post call.fail(HttpStatusCode.BadRequest, "review_bad_artifact", it.message ?: "bad --artifact")
            }
        }
        val brief = ReviewBrief(
            request = req.request,
            background = req.background,
            completedWork = req.completedWork,
            focusAreas = req.focusAreas,
            knownRisks = req.knownRisks,
            verification = req.verification,
            constraints = req.constraints,
            definitionOfDone = req.definitionOfDone,
        )
        ReviewLimits.brief(brief)?.let { return@post call.fail(HttpStatusCode.BadRequest, "review_invalid", it) }
        when (
            val out = core.reviews.send(
                // the local user IS the owner of this machine; the wire path stamps a real deviceId
                senderDeviceId = RequestRouter.LOCAL_DEVICE_ID,
                senderLabel = null,
                recipientDeviceId = recipient,
                title = req.title.orEmpty(),
                brief = brief,
                artifacts = artifacts,
                dueAt = req.dueAt,
                expiresAt = req.expiresAt,
            )
        ) {
            is ReviewRegistry.Outcome.Refused -> call.fail(HttpStatusCode.Conflict, out.code, out.message)
            is ReviewRegistry.Outcome.Ok -> call.ok(LocalReviewRes.serializer(), LocalReviewRes(request = out.request))
        }
    }

    post("$LOCAL_CONTROL_PREFIX/reviews/cancel") {
        if (!call.authorize(token)) return@post
        val req = call.body(LocalIdReq.serializer()) ?: return@post
        ownerMutation(call, core) { it.cancel(req.id) }
    }

    post("$LOCAL_CONTROL_PREFIX/reviews/close") {
        if (!call.authorize(token)) return@post
        val req = call.body(LocalIdReq.serializer()) ?: return@post
        ownerMutation(call, core) { it.close(req.id) }
    }

    // ---- review: the RECIPIENT's inbox --------------------------------------

    get("$LOCAL_CONTROL_PREFIX/reviews/inbox") {
        if (!call.authorize(token, post = false)) return@get
        val rawStatus = call.parameters["status"]
        val status = rawStatus?.let(::parseStatus)
        if (rawStatus != null && !rawStatus.equals("all", true) && status == null) {
            return@get call.fail(HttpStatusCode.BadRequest, "review_bad_status", "unknown review status")
        }
        val items = core.owner.inbox(status).map {
            LocalInboxItem(linkId = it.linkId, peerLabel = it.peerLabel, request = it.request, pending = it.pending)
        }
        call.ok(LocalInboxRes.serializer(), LocalInboxRes(items = items))
    }

    get("$LOCAL_CONTROL_PREFIX/reviews/show") {
        if (!call.authorize(token, post = false)) return@get
        val id = call.parameters["id"].orEmpty()
        if (id.isBlank()) return@get call.fail(HttpStatusCode.BadRequest, "bad_request", "id is required")
        val received = core.peerInbox.resolve(id)
        when {
            received.size > 1 -> call.fail(HttpStatusCode.Conflict, "review_ambiguous", "two peers sent a request with that id")
            received.size == 1 -> {
                val row = received.single()
                call.ok(
                    LocalShowRes.serializer(),
                    LocalShowRes(
                        scope = SCOPE_RECEIVED,
                        request = row.request,
                        peerLabel = core.peerInbox.links.byId(row.linkId)?.label,
                        pending = core.peerInbox.pendingActions(row),
                    ),
                )
            }
            else -> {
                val sent = core.reviews.registry.byId(id)
                if (sent == null) call.fail(HttpStatusCode.NotFound, "review_not_found", "no review request with that id")
                else call.ok(LocalShowRes.serializer(), LocalShowRes(scope = SCOPE_SENT, request = sent))
            }
        }
    }

    post("$LOCAL_CONTROL_PREFIX/reviews/prepare") {
        if (!call.authorize(token)) return@post
        val req = call.body(LocalIdReq.serializer()) ?: return@post
        when (val res = core.owner.prepare(req.id)) {
            is ReviewOwnerService.Outcome.Refused -> call.fail(statusFor(res.code), res.code, res.message)
            is ReviewOwnerService.Outcome.Ok -> call.ok(LocalPrepareRes.serializer(), LocalPrepareRes(bundle = res.value))
        }
    }

    post("$LOCAL_CONTROL_PREFIX/reviews/acknowledge") {
        if (!call.authorize(token)) return@post
        val req = call.body(LocalIdReq.serializer()) ?: return@post
        call.action(core.owner.act(req.id, ReviewInboxAction.ACKNOWLEDGE))
    }

    post("$LOCAL_CONTROL_PREFIX/reviews/start") {
        if (!call.authorize(token)) return@post
        val req = call.body(LocalIdReq.serializer()) ?: return@post
        call.action(core.owner.act(req.id, ReviewInboxAction.START))
    }

    post("$LOCAL_CONTROL_PREFIX/reviews/decline") {
        if (!call.authorize(token)) return@post
        val req = call.body(LocalDeclineReq.serializer()) ?: return@post
        call.action(core.owner.act(req.id, ReviewInboxAction.DECLINE, reason = req.reason))
    }

    post("$LOCAL_CONTROL_PREFIX/reviews/respond") {
        if (!call.authorize(token)) return@post
        val req = call.body(LocalRespondReq.serializer()) ?: return@post
        call.action(core.owner.act(req.id, ReviewInboxAction.RESPOND, result = req.result))
    }
}

const val LOCAL_CONTROL_PREFIX = "/v1/local"
const val DIR_OUTBOUND = "outbound"
const val DIR_INBOUND = "inbound"
const val SCOPE_SENT = "sent"
const val SCOPE_RECEIVED = "received"
private const val MAX_LOCAL_BODY_BYTES = ReviewLimits.MAX_ENCODED_BYTES + 64 * 1024

/** Wire contact → the CLI's own contact DTO. Same fields, different vocabulary: the CLI has always
 *  spelled direction as a lowercase string, and `--json` consumers key on that. */
private fun ReviewContact.toLocal() = LocalContact(
    id = id,
    label = label,
    direction = if (direction == CollaboratorDirection.INBOUND) DIR_INBOUND else DIR_OUTBOUND,
    fingerprint = fingerprint,
    connectedAt = connectedAt,
    removed = removed,
)

/**
 * Refusal code → HTTP status. One table rather than a status argument at every call site: the shared
 * [ReviewOwnerService] answers in codes (the wire has no statuses), and the CLI keys on `code` anyway —
 * the status is transport politeness, and it must stay consistent across routes that share a code.
 */
private fun statusFor(code: String): HttpStatusCode = when (code) {
    "relay_offline" -> HttpStatusCode.ServiceUnavailable
    "contact_not_found", "review_not_found", "review_no_recipient", "review_link_removed" -> HttpStatusCode.NotFound
    "peer_link_persist_failed" -> HttpStatusCode.InternalServerError
    "review_invalid", "invite_invalid" -> HttpStatusCode.BadRequest
    else -> HttpStatusCode.Conflict
}

private suspend fun ownerMutation(call: ApplicationCall, core: LocalControlDeps, op: (ReviewRegistry) -> ReviewRegistry.Outcome) {
    when (val out = op(core.reviews.registry)) {
        is ReviewRegistry.Outcome.Refused -> call.fail(HttpStatusCode.Conflict, out.code, out.message)
        is ReviewRegistry.Outcome.Ok -> {
            if (out.changed) core.reviews.broadcast(listOf(out.request))
            call.ok(LocalReviewRes.serializer(), LocalReviewRes(request = out.request))
        }
    }
}

private suspend fun ApplicationCall.action(result: ReviewOwnerService.Outcome<ReviewOwnerService.Acted>) {
    when (result) {
        // deliberately NOT statusFor(): for an action VERB every refusal is a conflict with the current
        // state, including `review_not_found` ("you cannot acknowledge something that isn't in your
        // inbox"). Only the lookup routes read the same code as a missing resource.
        is ReviewOwnerService.Outcome.Refused -> fail(HttpStatusCode.Conflict, result.code, result.message)
        is ReviewOwnerService.Outcome.Ok -> ok(
            LocalActionRes.serializer(),
            LocalActionRes(
                id = result.value.requestId,
                queued = result.value.queued,
                status = result.value.status.name.lowercase(),
            ),
        )
    }
}

/** Decode a status through the wire's tolerant enum serializer. Callers reject UNKNOWN explicitly so a
 * typo never widens a filtered history request into "show everything". */
private fun parseStatus(raw: String): ReviewStatus? = when (raw.lowercase()) {
    "", "all" -> null
    else -> runCatching { PocketJson.decodeFromString(ReviewStatus.serializer(), "\"${raw.lowercase()}\"") }
        .getOrNull()?.takeIf { it != ReviewStatus.UNKNOWN }
}

// ---- the three gates, in one place ----------------------------------------

/** Enforce the token + the anti-browser rules. Answers the call itself on refusal and returns false. */
private suspend fun ApplicationCall.authorize(token: String, post: Boolean = true): Boolean {
    // a CLI never sets Origin; a browser always does. Refusing its PRESENCE (not just a wrong value) is
    // the honest statement that this API has no web callers at all.
    if (request.headers["Origin"] != null) {
        fail(HttpStatusCode.Forbidden, "forbidden_origin", "this API is not callable from a web page")
        return false
    }
    if (post && request.headers["Content-Type"]?.substringBefore(';')?.trim()?.lowercase() != "application/json") {
        fail(HttpStatusCode.UnsupportedMediaType, "bad_content_type", "Content-Type: application/json is required")
        return false
    }
    if (post && request.headers["Content-Length"]?.toLongOrNull()?.let { it > MAX_LOCAL_BODY_BYTES } == true) {
        fail(HttpStatusCode.PayloadTooLarge, "body_too_large", "request body exceeds $MAX_LOCAL_BODY_BYTES bytes")
        return false
    }
    if (!LocalControlToken.matches(token, request.headers[LocalControlToken.HEADER])) {
        fail(HttpStatusCode.Unauthorized, "unauthorized", "missing or wrong local control token")
        return false
    }
    return true
}

private suspend fun <T> ApplicationCall.body(serializer: KSerializer<T>): T? {
    val bytes = runCatching {
        receiveChannel().readRemaining(MAX_LOCAL_BODY_BYTES.toLong() + 1).readByteArray()
    }.getOrNull()
    if (bytes != null && bytes.size > MAX_LOCAL_BODY_BYTES) {
        fail(HttpStatusCode.PayloadTooLarge, "body_too_large", "request body exceeds $MAX_LOCAL_BODY_BYTES bytes")
        return null
    }
    val text = bytes?.toString(Charsets.UTF_8)
    val parsed = text?.let { runCatching { PocketJson.decodeFromString(serializer, it) }.getOrNull() }
    if (parsed == null) fail(HttpStatusCode.BadRequest, "bad_request", "could not read the request body")
    return parsed
}

private suspend fun <T> ApplicationCall.ok(serializer: KSerializer<T>, value: T) =
    respondText(PocketJson.encodeToString(serializer, value), ContentType.Application.Json)

private suspend fun ApplicationCall.fail(status: HttpStatusCode, code: String, message: String) =
    respondText(
        PocketJson.encodeToString(LocalError.serializer(), LocalError(code = code, message = message)),
        ContentType.Application.Json,
        status,
    )
