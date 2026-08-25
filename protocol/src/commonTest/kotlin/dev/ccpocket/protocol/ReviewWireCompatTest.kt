package dev.ccpocket.protocol

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wire compatibility for the ReviewRequest family (REVIEW-REQUEST.md §10): round-trips of every frame,
 * defaults for absent fields (old JSON → new peer), UNKNOWN fallback for enum values only a newer peer
 * knows, and the drop path for the brand-new discriminators (new frame → old peer).
 *
 * The family is deliberately independent of pocket/handoff.*: nothing here may make a Session Handoff
 * frame decode differently, and a ReviewRequest carries no session/workdir/transcript field at all.
 */
class ReviewWireCompatTest {

    private fun request() = ReviewRequest(
        id = "rr_1",
        senderDeviceId = "devA",
        senderLabel = "Panda",
        recipientDeviceId = "devB",
        recipientLabel = "Frank",
        title = "relay ACK + retry race",
        brief = ReviewBrief(
            request = "review protocol compatibility and the retry race",
            background = "I don't usually own this module",
            focusAreas = listOf("ack ordering", "revoke race"),
        ),
        artifacts = listOf(
            ArtifactRef(
                kind = ArtifactKind.MERGE_REQUEST,
                url = "https://git.example.com/team/repo/-/merge_requests/42",
                repo = "git.example.com/team/repo",
                base = "aaa1111",
                head = "bbb2222",
            ),
        ),
        status = ReviewStatus.QUEUED,
        revision = 1,
        createdAt = 1_000,
        updatedAt = 1_000,
        dueAt = 5_000,
        expiresAt = 9_000,
    )

    private fun contact() = ReviewContact(
        id = "devB",
        label = "Frank",
        direction = CollaboratorDirection.OUTBOUND,
        fingerprint = "tiger-brick-mango-void · ember-delta-canvas-orbit",
        connectedAt = 2_000,
        purpose = CollaboratorPurpose.REVIEW,
        canSend = true,
    )

    private fun inboxItem() = ReviewInboxItem(
        linkId = "pl_7",
        peerLabel = "Frank",
        peerFingerprint = "tiger-brick-mango-void · ember-delta-canvas-orbit",
        request = request(),
        pending = listOf("acknowledge"),
    )

    private fun bundle() = ReviewExecutionBundle(
        requestId = "rr_1",
        peer = PreparePeer(linkId = "pl_7", label = "Frank", fingerprint = "tiger-brick-mango-void · ember-delta-canvas-orbit"),
        title = "relay ACK + retry race",
        status = ReviewStatus.DELIVERED,
        revision = 2,
        brief = ReviewBrief(request = "review protocol compatibility and the retry race"),
        artifacts = listOf(ArtifactRef(kind = ArtifactKind.MERGE_REQUEST, url = "https://git.example.com/team/repo/-/merge_requests/42")),
        dueAt = 5_000,
        recommendedPrompt = "Review MR !42 against the brief; report blockers first.",
        notes = listOf("you have not acknowledged this yet"),
    )

    /** An opaque single-use connect URI in the shape the daemon mints for a REVIEW peer
     *  (`ccpocket://review-contact#<base64url>`, §13.3). Kept as a constant because two tests reason
     *  about the ONE frame that may return it. */
    private val inviteUri = REVIEW_CONTACT_INVITE_URI_PREFIX + "eyJyZWxheSI6IndzczovL3IifQ"

    /** Frames whose only required field is a discriminator are still only reachable through an
     *  [Envelope]: decoding a body on its own would not exercise the polymorphic path the wire uses. */
    private fun decodeBody(json: String): Frame =
        PocketJson.decodeFromString<Envelope>("""{"id":"30","ts":0,"to":"PEER","body":$json}""").body

    // ---- round-trips ------------------------------------------------------

    @Test
    fun createReviewRequest_roundtrips_and_omits_null_optionals() {
        val env = Envelope(
            id = "1", ts = 7,
            body = CreateReviewRequest(
                recipientDeviceId = "devB",
                title = "review this",
                brief = ReviewBrief(request = "look at the retry race"),
                artifacts = listOf(ArtifactRef(kind = ArtifactKind.DOCUMENT_URL, url = "https://docs.example/x")),
            ),
        )
        val json = PocketJson.encodeToString(env)
        assertTrue("\"t\":\"pocket/review.create\"" in json, json)
        assertTrue("\"kind\":\"document_url\"" in json, json)
        assertFalse("dueAt" in json, json)      // explicitNulls=false — absent, not null
        assertFalse("expiresAt" in json, json)
        assertEquals(env, PocketJson.decodeFromString<Envelope>(json))
    }

    @Test
    fun created_listing_and_updated_roundtrip_with_a_full_entity() {
        val created = Envelope("2", 0, body = ReviewRequestCreated(ok = true, request = request()))
        assertEquals(created, PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(created)))

        val responded = request().copy(
            status = ReviewStatus.RESPONDED,
            revision = 4,
            updatedAt = 3_000,
            result = ReviewResult(
                verdict = ReviewVerdict.REQUEST_CHANGES,
                summary = "two blockers",
                findings = listOf(
                    ReviewFinding(
                        title = "ack can land after revoke",
                        severity = HandoffFinding.SEVERITY_HIGH,
                        detail = "the ACK path has no revoke fence",
                        artifactIndex = 0, file = "relay/Ack.kt", line = 42,
                    ),
                ),
                verification = listOf("ran :relay:test"),
                openQuestions = listOf("is the retry bounded?"),
                recommendedNextSteps = listOf("fence the ACK on the revoke epoch"),
                respondedByDeviceId = "devB", respondedAt = 3_000,
            ),
        )
        val upd = Envelope("3", 0, body = ReviewUpdated(responded))
        val json = PocketJson.encodeToString(upd)
        assertTrue("\"status\":\"responded\"" in json, json)
        assertTrue("\"verdict\":\"request_changes\"" in json, json)
        assertTrue("\"severity\":\"high\"" in json, json)
        assertEquals(upd, PocketJson.decodeFromString<Envelope>(json))

        val listing = Envelope("4", 0, body = ReviewListing(listOf(request(), responded)))
        assertEquals(listing, PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(listing)))
    }

    @Test
    fun control_frames_roundtrip() {
        listOf(
            Envelope("5", 0, body = ListReviewRequests()),
            Envelope("6", 0, body = ListReviewRequests(status = ReviewStatus.DELIVERED, sinceRevision = 3)),
            Envelope("7", 0, body = GetReviewRequest("rr_1")),
            Envelope("8", 0, body = MarkReviewDelivered("rr_1", idempotencyKey = "k1")),
            Envelope("9", 0, body = AcknowledgeReviewRequest("rr_1", idempotencyKey = "k2")),
            Envelope("10", 0, body = StartReviewRequest("rr_1", idempotencyKey = "k3")),
            Envelope("11", 0, body = DeclineReviewRequest("rr_1", reason = "not my module", idempotencyKey = "k4")),
            Envelope("12", 0, body = RespondReviewRequest("rr_1", ReviewResult(summary = "lgtm"), idempotencyKey = "k5")),
            Envelope("13", 0, body = CancelReviewRequest("rr_1")),
            Envelope("14", 0, body = CloseReviewRequest("rr_1")),
            Envelope("15", 0, body = ReviewRequestCreated(ok = false, error = "no such contact", code = "review_no_recipient")),
        ).forEach { env ->
            assertEquals(env, PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(env)))
        }
    }

    // ---- old JSON (missing new fields) → defaults -------------------------

    @Test
    fun a_minimal_request_decodes_via_defaults() {
        val json = """{"id":"16","ts":0,"to":"PEER","body":{"t":"pocket/review.updated",
            "request":{"id":"rr_9"}}}""".replace("\n", "")
        val r = (PocketJson.decodeFromString<Envelope>(json).body as ReviewUpdated).request
        assertEquals("rr_9", r.id)
        assertEquals(ReviewStatus.UNKNOWN, r.status, "absent status must read as the locked fallback")
        assertEquals("", r.recipientDeviceId)
        assertEquals(0, r.revision)
        assertTrue(r.artifacts.isEmpty())
        assertEquals("", r.brief.request)
        assertNull(r.result)
        assertNull(r.expiresAt)
    }

    @Test
    fun a_minimal_result_and_finding_decode_via_defaults() {
        val json = """{"id":"17","ts":0,"to":"PEER","body":{"t":"pocket/review.respond","requestId":"rr_1",
            "result":{"summary":"ok","findings":[{"title":"nit"}]}}}""".replace("\n", "")
        val body = PocketJson.decodeFromString<Envelope>(json).body as RespondReviewRequest
        assertEquals("", body.idempotencyKey, "an older peer omits the key — the daemon must still apply once")
        val res = body.result
        assertEquals(ReviewVerdict.UNKNOWN, res.verdict, "an omitted verdict must never read as approve")
        assertEquals("", res.respondedByDeviceId, "the daemon stamps identity — a blank draft must decode")
        assertEquals(0, res.respondedAt)
        val f = res.findings.single()
        assertEquals("nit", f.title)
        assertEquals(HandoffFinding.SEVERITY_INFO, f.severity)
        assertNull(f.artifactIndex); assertNull(f.file); assertNull(f.line)
    }

    @Test
    fun unknown_structured_keys_are_skipped_losslessly() {
        // a FUTURE peer tail-appends a structured field (attachments, §11.3 / M3): this build must skip
        // it precisely and keep every field it does know — including the ones AFTER the unknown key.
        val json = """{"id":"18","ts":0,"to":"PEER","body":{"t":"pocket/review.updated",
            "request":{"id":"rr_10","status":"delivered",
            "attachments":[{"id":"a1","name":"spec.pdf","bytes":12},{"id":"a2","name":"x.png","bytes":34}],
            "recipientLabel":"Frank","revision":2}}}""".replace("\n", "")
        val r = (PocketJson.decodeFromString<Envelope>(json).body as ReviewUpdated).request
        assertEquals("rr_10", r.id)
        assertEquals(ReviewStatus.DELIVERED, r.status)
        assertEquals("Frank", r.recipientLabel, "fields AFTER the unknown key must survive the skip")
        assertEquals(2, r.revision)
    }

    // ---- unknown enum values → UNKNOWN (never a failed decode) ------------

    @Test
    fun unknown_enum_values_degrade_to_UNKNOWN() {
        val json = """{"id":"19","ts":0,"to":"PEER","body":{"t":"pocket/review.updated",
            "request":{"id":"rr_1","status":"escalated",
            "artifacts":[{"kind":"jira_ticket","url":"https://x"}],
            "result":{"verdict":"merge_it","summary":"s"}}}}""".replace("\n", "")
        val r = (PocketJson.decodeFromString<Envelope>(json).body as ReviewUpdated).request
        assertEquals(ReviewStatus.UNKNOWN, r.status)
        assertEquals(ArtifactKind.UNKNOWN, r.artifacts.single().kind, "an unknown artifact kind must fail closed")
        assertEquals(ReviewVerdict.UNKNOWN, r.result!!.verdict)
        assertFalse(r.status.isTerminal, "an unreadable state stays locked, it does not read as finished")
    }

    @Test
    fun terminal_set_matches_the_state_machine() {
        assertTrue(ReviewStatus.CLOSED.isTerminal)
        assertTrue(ReviewStatus.DECLINED.isTerminal)
        assertTrue(ReviewStatus.CANCELLED.isTerminal)
        assertTrue(ReviewStatus.EXPIRED.isTerminal)
        assertFalse(ReviewStatus.QUEUED.isTerminal)
        assertFalse(ReviewStatus.DELIVERED.isTerminal)
        assertFalse(ReviewStatus.ACKNOWLEDGED.isTerminal)
        assertFalse(ReviewStatus.IN_PROGRESS.isTerminal)
        assertFalse(ReviewStatus.RESPONDED.isTerminal, "the sender still has to close a responded request")
        assertFalse(ReviewStatus.UNKNOWN.isTerminal)
    }

    // ---- new frame → old peer: the drop path ------------------------------

    @Test
    fun an_unknown_review_frame_fails_the_envelope_decode_the_drop_path() {
        val json = """{"id":"20","ts":0,"to":"PEER","body":{"t":"pocket/review.frobnicate","x":1}}"""
        assertTrue(
            runCatching { PocketJson.decodeFromString<Envelope>(json) }.isFailure,
            "unknown discriminator must throw (the transport's drop path), not decode to garbage",
        )
    }

    /** The two families must stay independent: a review row carries no session/workdir/transcript key,
     *  and adding it must not have changed how a handoff row encodes. */
    @Test
    fun review_carries_no_runtime_context_and_leaves_handoff_untouched() {
        val json = PocketJson.encodeToString(Envelope("21", 0, body = ReviewUpdated(request())))
        listOf("sourceSessionId", "workdir", "sourceConvoId", "sourceEventSeq", "allowedRoots").forEach {
            assertFalse(it in json, "a task-context handoff must not carry $it: $json")
        }
        val h = Envelope("22", 0, body = HandoffUpdated(SessionHandoff(id = "h1", sourceSessionId = "s1")))
        assertEquals(h, PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(h)))
    }

    // =======================================================================
    //  OWNER-LOCAL control plane (§6 + §12): contacts, this machine's received
    //  inbox, prepare, and the recipient-side actions the daemon queues for its
    //  own owner. Same red lines as above, plus one this plane adds: these
    //  replies are the owner's whole read surface and clients cache, log and
    //  re-render them, so nothing here may be able to hold key material.
    // =======================================================================

    @Test
    fun owner_local_frames_roundtrip_through_an_envelope() {
        listOf(
            Envelope("30", 0, body = ListReviewContacts),
            Envelope("31", 0, body = ReviewContactsListing(listOf(contact(), contact().copy(id = "pl_7", direction = CollaboratorDirection.INBOUND, canSend = false)))),
            Envelope("32", 0, body = CreateReviewInvite(label = "Frank")),
            Envelope("33", 0, body = ReviewInviteCreated(ok = true, invite = inviteUri, ttlSec = 600, label = "Frank")),
            Envelope("34", 0, body = ReviewInviteCreated(ok = false, error = "no relay identity yet", code = "review_no_account")),
            Envelope("35", 0, body = JoinReviewContact(invite = inviteUri, label = "Frank")),
            Envelope("36", 0, body = RemoveReviewContact("pl_7", direction = CollaboratorDirection.INBOUND)),
            Envelope("37", 0, body = ReviewContactUpdated(ok = true, contact = contact())),
            Envelope("38", 0, body = ListReviewInbox(status = ReviewStatus.DELIVERED)),
            Envelope("39", 0, body = ReviewInboxListing(listOf(inboxItem()))),
            Envelope("40", 0, body = PrepareReviewRequest("rr_1")),
            Envelope("41", 0, body = ReviewPrepared(ok = true, bundle = bundle())),
            Envelope("42", 0, body = ActOnReviewInbox("rr_1", ReviewInboxAction.RESPOND, result = ReviewResult(summary = "lgtm"))),
            Envelope("43", 0, body = ReviewInboxActed(ok = true, requestId = "rr_1", queued = true, status = ReviewStatus.RESPONDED)),
        ).forEach { env ->
            assertEquals(env, PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(env)))
        }
        // the object frame keeps its discriminator on the wire
        assertTrue("\"t\":\"pocket/review.contacts\"" in PocketJson.encodeToString(Envelope("44", 0, body = ListReviewContacts)))
    }

    @Test
    fun owner_local_frames_omit_null_optionals() {
        val invite = PocketJson.encodeToString(Envelope("45", 0, body = CreateReviewInvite()))
        assertFalse("label" in invite, invite) // explicitNulls=false — absent, not null

        val minted = PocketJson.encodeToString(Envelope("46", 0, body = ReviewInviteCreated(ok = true, invite = inviteUri, ttlSec = 600)))
        assertFalse("\"label\"" in minted, minted)
        assertFalse("\"error\"" in minted, minted)
        assertFalse("\"code\"" in minted, minted)

        val updated = PocketJson.encodeToString(Envelope("47", 0, body = ReviewContactUpdated(ok = true, contact = contact().copy(fingerprint = null))))
        assertFalse("fingerprint" in updated, updated)
        assertFalse("\"error\"" in updated, updated)

        val act = PocketJson.encodeToString(Envelope("48", 0, body = ActOnReviewInbox("rr_1", ReviewInboxAction.ACKNOWLEDGE)))
        assertTrue("\"action\":\"acknowledge\"" in act, act)
        assertFalse("reason" in act, act)
        assertFalse("result" in act, act)
    }

    // ---- old/minimal JSON → defaults --------------------------------------

    @Test
    fun minimal_owner_local_frames_decode_via_defaults() {
        assertTrue((decodeBody("""{"t":"pocket/review.contacts_listing"}""") as ReviewContactsListing).items.isEmpty())
        assertNull((decodeBody("""{"t":"pocket/review.contact_invite"}""") as CreateReviewInvite).label)

        val invited = decodeBody("""{"t":"pocket/review.contact_invited","ok":false}""") as ReviewInviteCreated
        assertNull(invited.invite, "a refusal carries no URI — an absent one must never read as a mint")
        assertEquals(0, invited.ttlSec)
        assertNull(invited.code)

        assertNull((decodeBody("""{"t":"pocket/review.contact_join","invite":"$inviteUri"}""") as JoinReviewContact).label)

        val remove = decodeBody("""{"t":"pocket/review.contact_remove","id":"devB"}""") as RemoveReviewContact
        assertEquals(
            CollaboratorDirection.UNKNOWN, remove.direction,
            "the two directions are separate id spaces — an unstated one must not pick a ledger for the daemon",
        )

        assertNull((decodeBody("""{"t":"pocket/review.contact_updated","ok":true}""") as ReviewContactUpdated).contact)
        assertNull((decodeBody("""{"t":"pocket/review.inbox"}""") as ListReviewInbox).status)
        assertTrue((decodeBody("""{"t":"pocket/review.inbox_listing"}""") as ReviewInboxListing).items.isEmpty())
        assertNull((decodeBody("""{"t":"pocket/review.prepared","ok":false}""") as ReviewPrepared).bundle)

        val act = decodeBody("""{"t":"pocket/review.inbox_act","requestId":"rr_1"}""") as ActOnReviewInbox
        assertEquals(ReviewInboxAction.UNKNOWN, act.action, "an omitted verb must never resolve to a transition")
        assertNull(act.reason)
        assertNull(act.result)

        val acted = decodeBody("""{"t":"pocket/review.inbox_acted","ok":true}""") as ReviewInboxActed
        assertEquals("", acted.requestId)
        assertFalse(acted.queued, "absent must read as 'nothing was sent', so a stale reply can't claim delivery")
        assertEquals(ReviewStatus.UNKNOWN, acted.status)
    }

    @Test
    fun a_minimal_contact_and_inbox_item_decode_via_defaults() {
        val c = (decodeBody("""{"t":"pocket/review.contacts_listing","items":[{"id":"devB"}]}""") as ReviewContactsListing).items.single()
        assertEquals("", c.label)
        assertEquals(CollaboratorDirection.UNKNOWN, c.direction, "absent direction must read as the most restricted")
        assertNull(c.fingerprint)
        assertEquals(0, c.connectedAt)
        assertFalse(c.removed)
        assertEquals(CollaboratorPurpose.REVIEW, c.purpose, "a row in a review listing is a review contact by construction")
        assertFalse(c.canSend, "eligibility is the daemon's answer — absent must never read as allowed")

        val item = (
            decodeBody("""{"t":"pocket/review.inbox_listing","items":[{"linkId":"pl_7","request":{"id":"rr_9"}}]}""")
                as ReviewInboxListing
            ).items.single()
        assertEquals("", item.peerLabel)
        assertEquals("", item.peerFingerprint)
        assertEquals("rr_9", item.request.id)
        assertTrue(item.pending.isEmpty(), "absent pending must read as 'nothing in flight', not as 'unknown'")
    }

    @Test
    fun the_execution_bundle_roundtrips_and_reads_absent_untrusted_as_true() {
        val env = Envelope("49", 0, body = ReviewPrepared(ok = true, bundle = bundle()))
        val json = PocketJson.encodeToString(env)
        assertTrue("\"peerContentIsUntrusted\":true" in json, json)
        assertFalse("expiresAt" in json, json)
        assertEquals(env, PocketJson.decodeFromString<Envelope>(json))

        // a bundle from a build that predates the flag must still be read as untrusted: the brief and
        // artifacts are text a colleague typed, so the safe reading of "absent" is the only one that
        // keeps that prose DATA rather than promoting it into the agent's instruction channel (§11.2)
        val old = decodeBody(
            """{"t":"pocket/review.prepared","ok":true,"bundle":{"requestId":"rr_1",
            "peer":{"linkId":"pl_7","label":"Frank","fingerprint":"tiger-brick-mango-void"},
            "title":"relay ACK + retry race","status":"delivered","revision":2,
            "brief":{"request":"look at the retry race"},"artifacts":[],
            "recommendedPrompt":"review it"}}""".replace("\n", ""),
        ) as ReviewPrepared
        val b = old.bundle!!
        assertTrue(b.peerContentIsUntrusted)
        assertEquals("pl_7", b.peer.linkId)
        assertEquals(ReviewStatus.DELIVERED, b.status)
        assertNull(b.dueAt)
        assertNull(b.expiresAt)
        assertTrue(b.notes.isEmpty())
    }

    // ---- unknown enum values → UNKNOWN (never a failed decode) ------------

    @Test
    fun an_unknown_inbox_action_degrades_to_UNKNOWN() {
        val act = decodeBody("""{"t":"pocket/review.inbox_act","requestId":"rr_1","action":"escalate"}""") as ActOnReviewInbox
        assertEquals(
            ReviewInboxAction.UNKNOWN, act.action,
            "a verb only a newer client knows must fail closed, not be guessed into the nearest transition",
        )
    }

    // ---- the owner plane carries public metadata only (§12) ---------------

    @Test
    fun owner_replies_cannot_carry_key_material() {
        // These are the owner's whole read surface, and clients cache, log and re-render them freely.
        // A field able to hold a credential or key would therefore park a secret somewhere nobody
        // guards it, so the shapes themselves must have no such field — fingerprints are display aids.
        listOf(
            Envelope("50", 0, body = ReviewContactsListing(listOf(contact(), contact().copy(id = "pl_7", direction = CollaboratorDirection.INBOUND, canSend = false)))),
            Envelope("51", 0, body = ReviewInboxListing(listOf(inboxItem()))),
            Envelope("52", 0, body = ReviewPrepared(ok = true, bundle = bundle())),
            Envelope("53", 0, body = ReviewContactUpdated(ok = true, contact = contact())),
        ).forEach { env ->
            val json = PocketJson.encodeToString(env).lowercase()
            listOf("credential", "ticket", "privatekey", "psk", "bearer").forEach { needle ->
                assertFalse(needle in json, "an owner reply must not be able to carry \"$needle\": $json")
            }
        }
    }

    @Test
    fun only_the_invite_reply_returns_establishment_material() {
        // The one exception, and it is deliberate: a connect URI mints a credential for whoever redeems
        // it, so exactly ONE reply returns one — once, at the owner's explicit request, TTL-bounded.
        val minted = PocketJson.encodeToString(Envelope("54", 0, body = ReviewInviteCreated(ok = true, invite = inviteUri, ttlSec = 600)))
        assertTrue("\"invite\":\"$inviteUri\"" in minted, minted)

        // No other REPLY may grow one: a URI riding a listing or a status would be a redeemable secret
        // sitting in exactly the frames clients treat as cacheable. (JoinReviewContact also carries an
        // invite, but that is owner -> daemon: the owner is handing over a URI they already hold.)
        listOf(
            Envelope("55", 0, body = ReviewContactsListing(listOf(contact()))),
            Envelope("56", 0, body = ReviewInboxListing(listOf(inboxItem()))),
            Envelope("57", 0, body = ReviewPrepared(ok = true, bundle = bundle())),
            Envelope("58", 0, body = ReviewContactUpdated(ok = true, contact = contact())),
            Envelope("59", 0, body = ReviewInboxActed(ok = true, requestId = "rr_1", queued = true, status = ReviewStatus.ACKNOWLEDGED)),
        ).forEach { env ->
            val json = PocketJson.encodeToString(env)
            assertFalse("invite" in json, "only pocket/review.contact_invited returns a connect URI: $json")
            assertFalse("ccpocket://" in json, json)
        }
        assertTrue("\"invite\"" in PocketJson.encodeToString(Envelope("60", 0, body = JoinReviewContact(invite = inviteUri))))
    }
}
