package dev.ccpocket.protocol

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wire compatibility for the Collaborator Link family (SESSION-HANDOFF.md §4.1): frame round-trips,
 * defaults for absent fields, UNKNOWN direction fallback, unknown-key skipping, the drop path for
 * brand-new discriminators — and the fingerprint golden value, which is a CROSS-VERSION HUMAN
 * CONTRACT (two people read these words to each other; both builds must derive identical words).
 */
class CollaboratorWireCompatTest {

    private fun collaborator() = Collaborator(
        deviceId = "devB",
        label = "Frank",
        direction = CollaboratorDirection.OUTBOUND,
        connectedAt = 1_000,
        fingerprint = "tiger-brick · mango-void",
        handoffCount = 2,
        hasDaemon = false,
    )

    // ---- round-trips ------------------------------------------------------

    @Test
    fun every_frame_roundtrips_through_an_envelope() {
        val frames = listOf(
            Envelope(id = "1", ts = 0, body = CreateCollaboratorTicket(label = "Frank")),
            Envelope(id = "2", ts = 0, body = CollaboratorTicketCreated(ok = true, invite = CollaboratorInvite("wss://r", "acct", "pk", "tkt", ownerLabel = "Panda"))),
            Envelope(id = "3", ts = 0, body = ListCollaborators),
            Envelope(id = "4", ts = 0, body = CollaboratorListing(listOf(collaborator()))),
            Envelope(id = "5", ts = 0, body = RemoveCollaborator("devB")),
            Envelope(id = "6", ts = 0, body = CollaboratorUpdated(collaborator().copy(removed = true))),
            Envelope(id = "7", ts = 0, body = CollaboratorConnected(collaborator())),
        )
        frames.forEach { env ->
            assertEquals(env, PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(env)))
        }
        // the object frame keeps its discriminator on the wire
        assertTrue("\"t\":\"pocket/collaborator.list\"" in PocketJson.encodeToString(frames[2]))
    }

    @Test
    fun invite_omits_null_ownerLabel_and_decodes_bare_required_fields() {
        val minimal = Envelope(id = "8", ts = 0, body = CollaboratorTicketCreated(ok = true, invite = CollaboratorInvite("wss://r", "acct", "pk", "tkt")))
        val json = PocketJson.encodeToString(minimal)
        assertFalse("ownerLabel" in json, json) // explicitNulls=false — absent, not null

        // a bare four-field blob (what any v1 QR carries) decodes with the ttl default
        val bare = """{"id":"9","ts":0,"to":"PEER","body":{"t":"pocket/collaborator.ticket_created","ok":true,
            "invite":{"relay":"wss://r","accountId":"acct","daemonPub":"pk","ticket":"tkt"}}}""".replace("\n", "")
        val inv = (PocketJson.decodeFromString<Envelope>(bare).body as CollaboratorTicketCreated).invite!!
        assertEquals(600, inv.ttlSec)
        assertNull(inv.ownerLabel)
        assertEquals(CollaboratorPurpose.SESSION_HANDOFF, inv.purpose, "a v1 QR is a Session Handoff invite")
    }

    /**
     * The invite says WHAT IT ESTABLISHES, because the redeemer has to tell the two features apart before
     * burning a single-use ticket (REVIEW-REQUEST.md §13.3).
     *
     * The mint FRAME being new is not enough: the artifact that actually crosses machines is this one, and
     * without a marker a phone's ordinary scanner would redeem a Review QR as a handoff contact — consuming
     * the ticket the colleague's daemon was meant to redeem.
     */
    @Test
    fun the_invite_declares_its_purpose_additively() {
        val review = CollaboratorInvite("wss://r", "acct", "pk", "tkt", purpose = CollaboratorPurpose.REVIEW)
        val env = Envelope(id = "16", ts = 0, body = CollaboratorTicketCreated(ok = true, invite = review))
        val json = PocketJson.encodeToString(env)
        assertTrue("\"purpose\":\"review\"" in json, json)
        assertEquals(env, PocketJson.decodeFromString<Envelope>(json))

        // an OLD peer skips the unknown key rather than failing the whole invite — the usual
        // ignoreUnknownKeys contract, pinned here as the shape it takes for this field
        val fromNewer = """{"id":"17","ts":0,"to":"PEER","body":{"t":"pocket/collaborator.ticket_created","ok":true,
            "invite":{"relay":"wss://r","accountId":"acct","daemonPub":"pk","ticket":"tkt","futureField":"x"}}}""".replace("\n", "")
        assertEquals(
            CollaboratorPurpose.SESSION_HANDOFF,
            (PocketJson.decodeFromString<Envelope>(fromNewer).body as CollaboratorTicketCreated).invite!!.purpose,
        )

        // a purpose only a newer build knows is UNKNOWN, which no redeem path accepts
        val futurePurpose = """{"id":"18","ts":0,"to":"PEER","body":{"t":"pocket/collaborator.ticket_created","ok":true,
            "invite":{"relay":"wss://r","accountId":"acct","daemonPub":"pk","ticket":"tkt","purpose":"pair_programming"}}}""".replace("\n", "")
        assertEquals(
            CollaboratorPurpose.UNKNOWN,
            (PocketJson.decodeFromString<Envelope>(futurePurpose).body as CollaboratorTicketCreated).invite!!.purpose,
        )
    }

    /**
     * …and the purpose ALONE is not enough, which is why the two features also travel through different
     * URI doors (REVIEW-REQUEST.md §13.3).
     *
     * `purpose` is a trailing field: an ALREADY-RELEASED app does not read it, decodes the default, and
     * redeems a Review ticket at its ordinary collaborator scanner — burning the single-use ticket the
     * colleague's daemon was waiting for. It cannot be taught otherwise after the fact; the only thing
     * that reaches it is a host it does not recognise, which it declines instead of consuming.
     *
     * So the host is a wire contract in its own right, pinned here rather than in either of the two
     * PORTED codecs (the daemon's and the app's), because those two may drift on everything EXCEPT which
     * door a ticket was addressed to.
     */
    @Test
    fun the_two_invite_doors_are_distinct_and_the_legacy_one_is_frozen() {
        // frozen: printed QRs and pasted links from every released build parse against this exact string
        assertEquals("ccpocket://collab#", COLLAB_INVITE_URI_PREFIX)
        assertEquals("ccpocket://review-contact#", REVIEW_CONTACT_INVITE_URI_PREFIX)
        assertTrue(COLLAB_INVITE_URI_PREFIX != REVIEW_CONTACT_INVITE_URI_PREFIX)
        // …and neither is a prefix of the other, so a host match can never be ambiguous
        assertFalse(REVIEW_CONTACT_INVITE_URI_PREFIX.startsWith(COLLAB_INVITE_URI_PREFIX.removeSuffix("#")))

        // the mapping every producer uses: only REVIEW leaves the legacy door
        assertEquals(COLLAB_INVITE_URI_PREFIX, inviteUriPrefix(CollaboratorPurpose.SESSION_HANDOFF))
        assertEquals(REVIEW_CONTACT_INVITE_URI_PREFIX, inviteUriPrefix(CollaboratorPurpose.REVIEW))
        // a purpose this build cannot read never gets published as a Review invite; the decoders' exact
        // purpose match is what makes UNKNOWN unusable at BOTH doors
        assertEquals(COLLAB_INVITE_URI_PREFIX, inviteUriPrefix(CollaboratorPurpose.UNKNOWN))
    }

    // ---- old/minimal JSON → defaults --------------------------------------

    @Test
    fun a_minimal_collaborator_decodes_via_defaults() {
        val json = """{"id":"10","ts":0,"to":"PEER","body":{"t":"pocket/collaborator.updated",
            "collaborator":{"deviceId":"devB"}}}""".replace("\n", "")
        val c = (PocketJson.decodeFromString<Envelope>(json).body as CollaboratorUpdated).collaborator
        assertEquals("devB", c.deviceId)
        assertEquals("", c.label)
        assertEquals(CollaboratorDirection.UNKNOWN, c.direction, "absent direction must read as the most restricted")
        assertNull(c.hasDaemon)
        assertNull(c.fingerprint)
        assertFalse(c.removed)
    }

    @Test
    fun unknown_direction_degrades_to_UNKNOWN() {
        val json = """{"id":"11","ts":0,"to":"PEER","body":{"t":"pocket/collaborator.updated",
            "collaborator":{"deviceId":"devB","direction":"both_pending"}}}""".replace("\n", "")
        val c = (PocketJson.decodeFromString<Envelope>(json).body as CollaboratorUpdated).collaborator
        assertEquals(CollaboratorDirection.UNKNOWN, c.direction)
    }

    @Test
    fun unknown_structured_keys_are_skipped_losslessly() {
        // a FUTURE peer tail-appends a structured field: this build must skip it and keep what it knows
        val json = """{"id":"12","ts":0,"to":"PEER","body":{"t":"pocket/collaborator.updated",
            "collaborator":{"deviceId":"devB","direction":"outbound",
            "recentSessions":[{"id":"s1","at":1},{"id":"s2","at":2}],
            "label":"Frank"}}}""".replace("\n", "")
        val c = (PocketJson.decodeFromString<Envelope>(json).body as CollaboratorUpdated).collaborator
        assertEquals(CollaboratorDirection.OUTBOUND, c.direction)
        assertEquals("Frank", c.label, "fields AFTER the unknown key must survive the skip")
    }

    // ---- new frame → old peer: the drop path ------------------------------

    @Test
    fun an_unknown_collaborator_discriminator_fails_decode_for_the_drop_path() {
        // every ingress wraps decode in runCatching — a failed decode IS the silent drop
        val json = """{"id":"13","ts":0,"to":"PEER","body":{"t":"pocket/collaborator.presence","deviceId":"devB"}}"""
        assertFailsWith<Exception> { PocketJson.decodeFromString<Envelope>(json) }
    }

    // ---- purpose: the two features' recipient sets are not interchangeable -

    @Test
    fun a_collaborator_without_purpose_stays_a_session_handoff_contact() {
        // Every contact minted before the field existed carries no `purpose`, and its historical meaning
        // is exactly Session Handoff. Reading it as anything else would silently re-scope a link the
        // owner already established and verified by fingerprint — an upgrade may not do that.
        val json = """{"id":"14","ts":0,"to":"PEER","body":{"t":"pocket/collaborator.updated",
            "collaborator":{"deviceId":"devB","label":"Frank","direction":"outbound","connectedAt":1000}}}""".replace("\n", "")
        val c = (PocketJson.decodeFromString<Envelope>(json).body as CollaboratorUpdated).collaborator
        assertEquals(CollaboratorPurpose.SESSION_HANDOFF, c.purpose)
        assertTrue(c.acceptsSessionHandoff, "an upgrade must not take away a recipient the owner already had")
        assertFalse(
            c.acceptsReviewRequest,
            "…and must not GIVE it a new one either: an existing contact is a person's App, established " +
                "to receive a runtime lease. Using ReviewRequest with someone means making a Review link.",
        )
    }

    @Test
    fun purpose_roundtrips_and_gates_the_two_features_apart() {
        val review = collaborator().copy(purpose = CollaboratorPurpose.REVIEW)
        val env = Envelope(id = "15", ts = 0, body = CollaboratorUpdated(review))
        val json = PocketJson.encodeToString(env)
        assertTrue("\"purpose\":\"review\"" in json, json)
        assertEquals(env, PocketJson.decodeFromString<Envelope>(json))

        // a REVIEW peer is a colleague's DAEMON: binding a runtime handoff to it would hand a
        // task-context contact the session drive lease it was never established for
        assertFalse(review.acceptsSessionHandoff)
        assertTrue(review.acceptsReviewRequest)

        // a severed link is a recipient for nothing, whatever it was established for
        assertFalse(review.copy(removed = true).acceptsReviewRequest)
        assertFalse(collaborator().copy(removed = true).acceptsSessionHandoff)
    }

    @Test
    fun an_unknown_purpose_degrades_to_UNKNOWN_and_is_eligible_for_neither_feature() {
        val json = """{"id":"16","ts":0,"to":"PEER","body":{"t":"pocket/collaborator.updated",
            "collaborator":{"deviceId":"devB","purpose":"something_new"}}}""".replace("\n", "")
        val c = (PocketJson.decodeFromString<Envelope>(json).body as CollaboratorUpdated).collaborator
        assertEquals(CollaboratorPurpose.UNKNOWN, c.purpose)
        // fail closed: a purpose this build cannot read is one it cannot honour, so the contact is
        // offered as a recipient for NEITHER feature rather than guessed into the wrong one
        assertFalse(c.acceptsSessionHandoff)
        assertFalse(c.acceptsReviewRequest)
    }

    // ---- fingerprint golden values (a cross-version human contract) -------

    @Test
    fun fingerprint_golden_values_are_frozen() {
        // Two people read these words to each other across possibly different app versions.
        // If this test breaks, you changed FP_WORDS or the hash — old and new builds would render
        // DIFFERENT words for the SAME key and every verification would fail. Don't.
        assertEquals("vivid-cedar-grove-pixel · coral-grove-mango-haven", collaboratorFingerprint("PUBKEY_TEST"))
        assertEquals("quartz-fjord-amber-mango · zephyr-mango-ember-void", collaboratorFingerprint("abc"))
    }
}
