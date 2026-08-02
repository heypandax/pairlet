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
