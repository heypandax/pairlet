package dev.ccpocket.daemon.control

import dev.ccpocket.daemon.handoff.CollaboratorControl
import dev.ccpocket.daemon.review.PeerInboxService
import dev.ccpocket.daemon.review.PeerInboxStore
import dev.ccpocket.daemon.review.PeerKeys
import dev.ccpocket.daemon.review.PeerLink
import dev.ccpocket.daemon.review.PeerLinkSecret
import dev.ccpocket.daemon.review.PeerLinkStore
import dev.ccpocket.daemon.review.PeerSession
import dev.ccpocket.daemon.review.PeerTransport
import dev.ccpocket.daemon.review.ReviewRegistry
import dev.ccpocket.daemon.review.ReviewService
import dev.ccpocket.daemon.review.ReviewStore
import dev.ccpocket.daemon.review.encodeUri
import dev.ccpocket.protocol.Collaborator
import dev.ccpocket.protocol.CollaboratorDirection
import dev.ccpocket.protocol.CollaboratorInvite
import dev.ccpocket.protocol.CollaboratorListing
import dev.ccpocket.protocol.CollaboratorPurpose
import dev.ccpocket.protocol.CollaboratorTicketCreated
import dev.ccpocket.protocol.CollaboratorUpdated
import dev.ccpocket.protocol.PairCredential
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.ReviewRequest
import dev.ccpocket.protocol.ReviewStatus
import dev.ccpocket.protocol.ToPhone
import dev.ccpocket.protocol.acceptsReviewRequest
import dev.ccpocket.protocol.acceptsSessionHandoff
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import io.ktor.server.cio.CIO as ServerCIO

/**
 * The token-authenticated local control API (REVIEW-REQUEST.md §6). Three classes of test:
 *  - the GATES: a missing/wrong token, a browser `Origin`, a non-JSON POST;
 *  - REDACTION: nothing that could impersonate a link (ticket, bearer credential, private key) may
 *    appear in any response body;
 *  - the CLI's failure paths: an ambiguous label, an unknown contact, an unreachable daemon.
 */
class LocalControlRoutesTest {

    private val token = "test-token-not-a-secret"

    /** A contact ledger. Implements BOTH halves the way [dev.ccpocket.daemon.handoff.CollaboratorService]
     *  does — the control plane the routes call, and the directory [ReviewService] validates against. */
    private class FakeCollaborators(private val rows: List<Collaborator>) :
        CollaboratorControl, dev.ccpocket.daemon.handoff.CollaboratorDirectory {

        override fun labelOf(deviceId: String) = rows.firstOrNull { it.deviceId == deviceId && !it.removed }?.label
        override fun isActive(deviceId: String) = rows.any { it.deviceId == deviceId && !it.removed }
        override fun acceptsHandoff(deviceId: String) =
            isActive(deviceId) && rows.first { it.deviceId == deviceId }.acceptsSessionHandoff
        override fun acceptsReview(deviceId: String) =
            isActive(deviceId) && rows.first { it.deviceId == deviceId }.acceptsReviewRequest
        override suspend fun noteHandoff(deviceId: String, at: Long) {}

        /** What the last mint was asked to establish — the assertion target for "the CLI's contact
         *  commands mint REVIEW peers, not Session Handoff recipients". */
        var mintedPurpose: CollaboratorPurpose? = null

        val removed = mutableListOf<String>()
        var mintedTicket: CollaboratorInvite? = CollaboratorInvite(
            relay = "wss://relay.example", accountId = "acctA",
            daemonPub = dev.ccpocket.daemon.review.TestKeys.DAEMON_PUB,
            ticket = "SUPER-SECRET-TICKET", ownerLabel = "Panda · MacBook", ttlSec = 120,
            // the CLI's `collaborator` commands ARE the ReviewRequest contact surface, so what they mint
            // — and what `collaborator join` accepts back — is a REVIEW link (REVIEW-REQUEST.md §13.3)
            purpose = CollaboratorPurpose.REVIEW,
        )

        override suspend fun createTicket(label: String?, purpose: CollaboratorPurpose): CollaboratorTicketCreated {
            mintedPurpose = purpose
            // stamped like the real service — the invite carries what it establishes, and that is what
            // picks the URI door it goes out under (REVIEW-REQUEST.md §13.3)
            return mintedTicket?.let { CollaboratorTicketCreated(ok = true, invite = it.copy(purpose = purpose)) }
                ?: CollaboratorTicketCreated(ok = false, error = "can't reach the relay")
        }

        // the legacy listing is SESSION HANDOFF only, like the real service
        override suspend fun list() = CollaboratorListing(rows.filter { it.purpose == CollaboratorPurpose.SESSION_HANDOFF })

        override suspend fun contacts(purpose: CollaboratorPurpose) = rows.filter { it.purpose == purpose }

        override suspend fun remove(deviceId: String): ToPhone = remove(deviceId, CollaboratorPurpose.SESSION_HANDOFF)

        override suspend fun remove(deviceId: String, purpose: CollaboratorPurpose): ToPhone {
            val row = rows.firstOrNull { it.deviceId == deviceId && it.purpose == purpose }
                ?: return dev.ccpocket.protocol.PocketError("collaborator_not_found", "no such collaborator")
            removed += deviceId
            return CollaboratorUpdated(row.copy(removed = true))
        }

        override suspend fun onRedeemed(deviceId: String, peerPubB64: String) {}
    }

    private class NoopTransport : PeerTransport {
        override fun generateKeys() = PeerKeys("cHJpdmF0ZS1rZXk", "cHVibGljLWtleQ")
        override suspend fun redeem(relay: String, ticket: String, devicePubB64: String) =
            PairCredential(deviceId = "devB", credential = "BEARER-CREDENTIAL", accountId = "acctA")
        override suspend fun dial(link: PeerLink, secret: PeerLinkSecret, session: PeerSession) {
            throw IllegalStateException("no relay in tests")
        }
    }

    private class Fixture(val scope: CoroutineScope, dir: java.io.File, contacts: List<Collaborator>) {
        val collaborators = FakeCollaborators(contacts)
        val reviews = ReviewService(ReviewRegistry(ReviewStore.load(dir.resolve("reviews.json")), clock = { 1_000 }))
            .also { it.collaborators = collaborators } // as RelayClient wires it in production
        val links = PeerLinkStore.load(dir.resolve("peer-links.json"), dir.resolve("peer-secrets.json"))
        val inbox = PeerInboxStore.load(dir.resolve("review-inbox.json"))
        val peerInbox = PeerInboxService(scope, links, inbox, NoopTransport(), clock = { 1_000 })
        val deps = LocalControlDeps({ collaborators }, reviews, peerInbox)
    }

    /** A REVIEW contact — the only purpose this API's surface deals in (REVIEW-REQUEST.md §13.3). */
    private fun contact(id: String, label: String, purpose: CollaboratorPurpose = CollaboratorPurpose.REVIEW) = Collaborator(
        deviceId = id, label = label, direction = CollaboratorDirection.OUTBOUND,
        connectedAt = 1, fingerprint = "tiger-brick-mango-void · ember-delta-canvas-orbit",
        purpose = purpose,
    )

    /** Start the routes on an ephemeral port and run [block] against them. */
    private fun <T> serving(
        contacts: List<Collaborator> = listOf(contact("devB", "Frank")),
        block: suspend (Fixture, HttpClient, String) -> T,
    ): T = runBlocking {
        val dir = Files.createTempDirectory("ccp-local-control").toFile()
        val scope = CoroutineScope(SupervisorJob())
        val f = Fixture(scope, dir, contacts)
        val server = embeddedServer(ServerCIO, host = "127.0.0.1", port = 0) {
            routing { installLocalControl(f.deps, token) }
        }
        server.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(CIO)
        try {
            block(f, client, "http://127.0.0.1:$port$LOCAL_CONTROL_PREFIX")
        } finally {
            client.close()
            server.stop(0, 0)
            scope.cancel()
        }
    }

    private suspend fun HttpClient.authGet(url: String) = get(url) { header(LocalControlToken.HEADER, token) }

    private suspend fun HttpClient.authPost(url: String, body: String): HttpResponse = post(url) {
        header(LocalControlToken.HEADER, token)
        header("Content-Type", "application/json")
        setBody(body)
    }

    private fun codeOf(text: String) =
        runCatching { PocketJson.decodeFromString(LocalError.serializer(), text).code }.getOrNull()

    // ---- the gates ---------------------------------------------------------

    @Test
    fun a_missing_or_wrong_token_is_refused_on_every_route() = serving { _, client, base ->
        listOf("$base/collaborators", "$base/reviews", "$base/reviews/inbox").forEach { url ->
            val none = client.get(url)
            assertEquals(HttpStatusCode.Unauthorized, none.status, url)
            assertEquals("unauthorized", codeOf(none.bodyAsText()))
            val wrong = client.get(url) { header(LocalControlToken.HEADER, "not-the-token") }
            assertEquals(HttpStatusCode.Unauthorized, wrong.status, url)
        }
        val post = client.post("$base/reviews/send") {
            header("Content-Type", "application/json")
            setBody("""{"to":"Frank","request":"x"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, post.status)
    }

    /** A page can reach loopback; the point is that it announces itself while doing so. */
    @Test
    fun a_browser_origin_is_refused_even_with_a_correct_token() = serving { _, client, base ->
        val res = client.get("$base/collaborators") {
            header(LocalControlToken.HEADER, token)
            header("Origin", "https://evil.example")
        }
        assertEquals(HttpStatusCode.Forbidden, res.status)
        assertEquals("forbidden_origin", codeOf(res.bodyAsText()))

        // …including the same-origin form a local page would send
        val loopback = client.post("$base/reviews/cancel") {
            header(LocalControlToken.HEADER, token)
            header("Origin", "http://127.0.0.1:8799")
            header("Content-Type", "application/json")
            setBody("""{"id":"rr_1"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, loopback.status)
    }

    @Test
    fun a_post_without_a_json_content_type_is_refused() = serving { _, client, base ->
        val res = client.post("$base/reviews/cancel") {
            header(LocalControlToken.HEADER, token)
            header("Content-Type", "text/plain")
            setBody("""{"id":"rr_1"}""")
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, res.status)
        assertEquals("bad_content_type", codeOf(res.bodyAsText()))
        // a charset parameter is fine — that is an ordinary well-formed JSON post
        val ok = client.post("$base/reviews/cancel") {
            header(LocalControlToken.HEADER, token)
            header("Content-Type", "application/json; charset=utf-8")
            setBody("""{"id":"rr_nope"}""")
        }
        assertEquals(HttpStatusCode.Conflict, ok.status)
        assertEquals("review_not_found", codeOf(ok.bodyAsText()))
    }

    @Test
    fun a_malformed_body_is_a_clean_bad_request() = serving { _, client, base ->
        val res = client.authPost("$base/reviews/cancel", "{not json")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("bad_request", codeOf(res.bodyAsText()))
    }

    @Test
    fun an_oversized_body_is_rejected_before_json_decode() = serving { _, client, base ->
        val res = client.authPost("$base/reviews/cancel", "x".repeat(400 * 1024))
        assertEquals(HttpStatusCode.PayloadTooLarge, res.status)
        assertEquals("body_too_large", codeOf(res.bodyAsText()))
    }

    @Test
    fun an_unknown_status_filter_is_rejected_instead_of_widening_to_all_history() = serving { _, client, base ->
        val res = client.authGet("$base/reviews?status=delviered")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("review_bad_status", codeOf(res.bodyAsText()))
    }

    // ---- redaction ---------------------------------------------------------

    /** The invite route necessarily HANDS OUT establishment material once — but nothing else may, and
     *  the listing/show routes must never echo a credential or key back. */
    @Test
    fun no_route_but_invite_ever_returns_link_secrets() = serving { f, client, base ->
        val invite = f.collaborators.mintedTicket!!
        // join stores the ticket, the bearer credential and the private key…
        val joined = client.authPost(
            "$base/collaborators/join",
            PocketJson.encodeToString(LocalJoinReq.serializer(), LocalJoinReq(invite.encodeUri(), "Panda")),
        )
        assertEquals(HttpStatusCode.OK, joined.status, joined.bodyAsText())
        assertNoSecrets(joined.bodyAsText())

        // …and none of them may come back out
        assertNoSecrets(client.authGet("$base/collaborators").bodyAsText())
        assertNoSecrets(client.authGet("$base/reviews").bodyAsText())
        assertNoSecrets(client.authGet("$base/reviews/inbox").bodyAsText())

        val link = f.links.active().single()
        assertEquals("BEARER-CREDENTIAL", f.links.secretOf(link.id)!!.credential, "the secret IS stored, just never served")
        assertEquals("SUPER-SECRET-TICKET", f.links.secretOf(link.id)!!.ticket)
    }

    private fun assertNoSecrets(body: String) {
        listOf("SUPER-SECRET-TICKET", "BEARER-CREDENTIAL", "cHJpdmF0ZS1rZXk", "credential", "privateKey", "ticket")
            .forEach { assertFalse(it in body, "response leaked \"$it\": $body") }
    }

    @Test
    fun the_invite_route_returns_the_uri_once_and_reports_a_relay_outage_honestly() = serving { f, client, base ->
        val res = client.authPost("$base/collaborators/invite", """{"label":"Frank"}""")
        assertEquals(HttpStatusCode.OK, res.status)
        val decoded = PocketJson.decodeFromString(LocalInviteRes.serializer(), res.bodyAsText())
        // the REVIEW door (REVIEW-REQUEST.md §13.3), not the Session Handoff one an older app would
        // recognise and redeem
        assertTrue(decoded.invite.startsWith(dev.ccpocket.daemon.review.REVIEW_CONTACT_URI_PREFIX), decoded.invite)
        assertEquals(120, decoded.ttlSec)

        f.collaborators.mintedTicket = null
        val down = client.authPost("$base/collaborators/invite", """{"label":"Frank"}""")
        assertEquals(HttpStatusCode.Conflict, down.status)
        assertEquals("invite_refused", codeOf(down.bodyAsText()))
    }

    /**
     * BILATERAL fingerprint verification: the invite reply carries the SAME word group the joiner will be
     * asked to confirm, computed from the same real daemon key. Without it the inviter has nothing to
     * compare against, and the check detects nothing.
     */
    @Test
    fun the_invite_reply_carries_the_fingerprint_the_joiner_will_confirm() = serving { _, client, base ->
        val res = client.authPost("$base/collaborators/invite", """{"label":"Frank"}""")
        val minted = PocketJson.decodeFromString(LocalInviteRes.serializer(), res.bodyAsText())
        assertTrue(minted.fingerprint.isNotBlank(), "the inviter needs their half of the comparison")

        // the JOINER's half, derived exactly as their end derives it: decode the URI, fingerprint the key
        // it pins. Both ends must be reading the same bytes, not two hopefully-equal copies.
        val invite = dev.ccpocket.daemon.review.decodeReviewContactInvite(minted.invite)
        assertNotNull(invite, "the minted URI must decode — and it only does with a REAL P-256 key")
        assertEquals(
            dev.ccpocket.protocol.collaboratorFingerprint(invite.daemonPub), minted.fingerprint,
            "inviter and joiner must see identical words",
        )
        // display derivative only: the key itself never rides this field
        assertFalse(invite.daemonPub in minted.fingerprint)
    }

    // ---- resolution + failure paths ---------------------------------------

    @Test
    fun an_ambiguous_label_is_refused_rather_than_guessed() =
        serving(contacts = listOf(contact("devB", "Frank"), contact("devC", "Frank"))) { _, client, base ->
            val res = client.authPost(
                "$base/reviews/send",
                PocketJson.encodeToString(
                    LocalSendReq.serializer(),
                    LocalSendReq(to = "Frank", request = "check the retry race", artifacts = listOf("mr:https://git.example/mr/1")),
                ),
            )
            assertEquals(HttpStatusCode.Conflict, res.status)
            assertEquals("contact_ambiguous", codeOf(res.bodyAsText()))

            // removing by that label is equally ambiguous, and must not silently pick one
            val rm = client.authPost("$base/collaborators/remove", """{"idOrLabel":"Frank"}""")
            assertEquals(HttpStatusCode.Conflict, rm.status)
            assertEquals("contact_ambiguous", codeOf(rm.bodyAsText()))
            // …by id it is unambiguous
            val byId = client.authPost("$base/collaborators/remove", """{"idOrLabel":"devC"}""")
            assertEquals(HttpStatusCode.OK, byId.status, byId.bodyAsText())
        }

    @Test
    fun an_unknown_contact_and_a_bad_artifact_each_get_their_own_code() = serving { _, client, base ->
        val unknown = client.authPost(
            "$base/reviews/send",
            PocketJson.encodeToString(
                LocalSendReq.serializer(),
                LocalSendReq(to = "Nobody", request = "x", artifacts = listOf("mr:https://git.example/mr/1")),
            ),
        )
        assertEquals("review_no_recipient", codeOf(unknown.bodyAsText()))

        val bad = client.authPost(
            "$base/reviews/send",
            PocketJson.encodeToString(
                LocalSendReq.serializer(),
                LocalSendReq(to = "Frank", request = "x", artifacts = listOf("document:file:///etc/passwd")),
            ),
        )
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertEquals("review_bad_artifact", codeOf(bad.bodyAsText()))
    }

    @Test
    fun the_full_owner_side_send_show_and_close_path_works_over_the_api() = serving { f, client, base ->
        val sent = client.authPost(
            "$base/reviews/send",
            PocketJson.encodeToString(
                LocalSendReq.serializer(),
                LocalSendReq(
                    to = "Frank", request = "check the retry race", title = "the ACK path",
                    artifacts = listOf("mr:https://git.example/mr/1 | ACK fence"),
                    focusAreas = listOf("ack ordering"),
                ),
            ),
        )
        assertEquals(HttpStatusCode.OK, sent.status, sent.bodyAsText())
        val created = PocketJson.decodeFromString(LocalReviewRes.serializer(), sent.bodyAsText()).request
        assertEquals(ReviewStatus.QUEUED, created.status)
        assertEquals("devB", created.recipientDeviceId)
        assertEquals("Frank", created.recipientLabel)

        val shown = PocketJson.decodeFromString(
            LocalShowRes.serializer(),
            client.authGet("$base/reviews/show?id=${created.id}").bodyAsText(),
        )
        assertEquals(SCOPE_SENT, shown.scope)
        assertEquals(created.id, shown.request.id)

        // the recipient hasn't started, so cancel is legal
        val cancelled = client.authPost("$base/reviews/cancel", """{"id":"${created.id}"}""")
        assertEquals(HttpStatusCode.OK, cancelled.status)
        assertEquals(
            ReviewStatus.CANCELLED,
            PocketJson.decodeFromString(LocalReviewRes.serializer(), cancelled.bodyAsText()).request.status,
        )
    }

    @Test
    fun prepare_says_so_when_the_id_is_one_you_sent_rather_than_received() = serving { _, client, base ->
        val sent = client.authPost(
            "$base/reviews/send",
            PocketJson.encodeToString(
                LocalSendReq.serializer(),
                LocalSendReq(to = "Frank", request = "x", artifacts = listOf("mr:https://git.example/mr/1")),
            ),
        )
        val id = PocketJson.decodeFromString(LocalReviewRes.serializer(), sent.bodyAsText()).request.id
        val res = client.authPost("$base/reviews/prepare", """{"id":"$id"}""")
        assertEquals(HttpStatusCode.Conflict, res.status)
        assertEquals("review_not_received", codeOf(res.bodyAsText()))

        val missing = client.authPost("$base/reviews/prepare", """{"id":"rr_nope"}""")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals("review_not_found", codeOf(missing.bodyAsText()))
    }

    @Test
    fun a_recipient_action_on_an_id_that_is_not_in_the_inbox_is_refused() = serving { _, client, base ->
        val res = client.authPost("$base/reviews/acknowledge", """{"id":"rr_nope"}""")
        assertEquals(HttpStatusCode.Conflict, res.status)
        assertEquals("review_not_found", codeOf(res.bodyAsText()))
    }

    @Test
    fun the_inbox_renders_a_mirrored_row_with_its_peer_label_and_pending_actions() = serving { f, client, base ->
        val invite = f.collaborators.mintedTicket!!
        client.authPost(
            "$base/collaborators/join",
            PocketJson.encodeToString(LocalJoinReq.serializer(), LocalJoinReq(invite.encodeUri(), "Panda")),
        )
        val link = f.links.active().single()
        f.inbox.mirror(
            link.id,
            ReviewRequest(
                id = "rr_in", senderDeviceId = "devA", recipientDeviceId = "devB", title = "their ask",
                brief = dev.ccpocket.protocol.ReviewBrief(request = "look at this"),
                status = ReviewStatus.DELIVERED, revision = 2, createdAt = 1,
            ),
            1,
        )
        val body = client.authGet("$base/reviews/inbox").bodyAsText()
        val listed = PocketJson.decodeFromString(LocalInboxRes.serializer(), body)
        assertEquals(1, listed.items.size)
        assertEquals("Panda", listed.items.single().peerLabel)
        assertEquals("rr_in", listed.items.single().request.id)

        // an acknowledge is queued (the transport can't dial in tests) and surfaces as pending
        val ack = client.authPost("$base/reviews/acknowledge", """{"id":"rr_in"}""")
        assertEquals(HttpStatusCode.OK, ack.status, ack.bodyAsText())
        assertTrue(PocketJson.decodeFromString(LocalActionRes.serializer(), ack.bodyAsText()).queued)
        val after = PocketJson.decodeFromString(LocalInboxRes.serializer(), client.authGet("$base/reviews/inbox").bodyAsText())
        assertEquals(listOf("acknowledged"), after.items.single().pending)

        // and `show` resolves it from the RECEIVED side
        val shown = PocketJson.decodeFromString(
            LocalShowRes.serializer(), client.authGet("$base/reviews/show?id=rr_in").bodyAsText(),
        )
        assertEquals(SCOPE_RECEIVED, shown.scope)
        assertEquals("Panda", shown.peerLabel)
    }

    // ---- the CLI's "no daemon" path ---------------------------------------

    @Test
    fun the_cli_client_fails_cleanly_when_nothing_is_listening() = runBlocking {
        // port 1 is never a cc-pocket daemon; the point is a clear CliktError, never an implicit spawn
        val tokenPath = Files.createTempDirectory("ccp-client-token").resolve("token").toFile()
        LocalControlToken.loadOrCreate(tokenPath)
        val client = LocalControlClient(1, "start it: cc-pocket-daemon run", tokenPath = tokenPath)
        val err = runCatching { client.get("/collaborators", LocalContactsRes.serializer()) }.exceptionOrNull()
        assertTrue(err is com.github.ajalt.clikt.core.CliktError, "expected a CliktError, got $err")
        assertTrue("no cc-pocket daemon" in err.message.orEmpty(), err.message.orEmpty())
    }

    @Test
    fun the_token_comparison_is_exact_and_survives_a_reload() {
        val dir = Files.createTempDirectory("ccp-token").toFile()
        val path = dir.resolve("local-control-token")
        val minted = LocalControlToken.loadOrCreate(path)
        assertTrue(minted.length >= 40, "the token must carry real entropy: ${minted.length} chars")
        assertEquals(minted, LocalControlToken.loadOrCreate(path), "a second call must not re-mint")
        assertEquals(minted, LocalControlToken.read(path))
        assertTrue(LocalControlToken.matches(minted, minted))
        assertFalse(LocalControlToken.matches(minted, null))
        assertFalse(LocalControlToken.matches(minted, ""))
        assertFalse(LocalControlToken.matches(minted, minted.dropLast(1)))
        assertFalse(LocalControlToken.matches(minted, minted + "x"))
    }

    @Test
    fun token_creation_fails_closed_when_it_cannot_be_persisted() {
        val dir = Files.createTempDirectory("ccp-token-fail").toFile()
        val parentIsAFile = dir.resolve("not-a-directory").apply { writeText("x") }
        val err = runCatching { LocalControlToken.loadOrCreate(parentIsAFile.resolve("token")) }.exceptionOrNull()
        assertNotNull(err, "the daemon and CLI must never each continue with a different in-memory token")
    }
}
