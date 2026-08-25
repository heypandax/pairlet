package dev.ccpocket.daemon.handoff

import dev.ccpocket.daemon.bridge.BridgeRegistry
import dev.ccpocket.daemon.bridge.BridgeSpec
import dev.ccpocket.daemon.bridge.CredentialKind
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Collaborator
import dev.ccpocket.protocol.CollaboratorConnected
import dev.ccpocket.protocol.CollaboratorPurpose
import dev.ccpocket.protocol.CollaboratorUpdated
import dev.ccpocket.protocol.CreateCollaboratorTicket
import dev.ccpocket.protocol.HandoffAccess
import dev.ccpocket.protocol.HandoffBrief
import dev.ccpocket.protocol.HandoffKind
import dev.ccpocket.protocol.HandoffStatus
import dev.ccpocket.protocol.PairTicket
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.ToPhone
import dev.ccpocket.protocol.acceptsReviewRequest
import dev.ccpocket.protocol.acceptsSessionHandoff
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Collaborator Link control plane (SESSION-HANDOFF.md §4.1): mint → redeem (the #91 ticket-PSK
 * binding chain, exercised through BridgeRegistry exactly as DeviceSessions drives it) establishes a
 * contact with a fingerprint; remove kills the credential AND every bound Grant while the contact ROW
 * stays (flagged) for history. Follows the ShareServiceTest/HandoffRegistryTest fixture style: temp
 * stores, stubbed relay mint, no sockets.
 */
class CollaboratorServiceTest {

    private class Fixture {
        val tmp = Files.createTempDirectory("ccp-collab").toFile()
        val bridges = BridgeRegistry(store = tmp.resolve("bridges.json"))
        var now = System.currentTimeMillis()
        val handoffs = HandoffService(HandoffRegistry(HandoffStore.load(tmp.resolve("handoffs.json")), clock = { now }))
        val ownerFrames = mutableListOf<ToPhone>()
        val revokedCredentials = mutableListOf<String>()
        var mintedTicket: String? = "ticket-1"

        val service = CollaboratorService(
            accountId = "acct",
            daemonPubB64 = "daemon-pub",
            relayWsBase = "wss://relay.example",
            ownerLabel = { "Panda · MacBook" },
            registry = bridges,
            store = CollaboratorStore.load(tmp.resolve("collaborators.json")),
            mintTicket = { mintedTicket?.let { PairTicket(it, expiresInSec = 120, code = "123456") } },
            interactivePairingPending = { false },
            revokeCredential = { id ->
                revokedCredentials += id
                bridges.remove(id) // what revokeBridge does locally: the key dies
            },
            revokeGrants = { id -> handoffs.revokeRecipient(id) },
            fanoutToOwners = { ownerFrames += it },
        )

        init { handoffs.collaborators = service }

        /** Run the redeem chain the way DeviceSessions does: intent → provisional key → first-frame
         *  PSK proof (finalize) → the collaborator hook. Returns the confirmed spec. */
        suspend fun redeem(deviceId: String, ticket: String): BridgeSpec? {
            bridges.holdProvisional(deviceId, ByteArray(32) { 7 })
            val spec = bridges.finalize(deviceId, ticket.encodeToByteArray())
            if (spec?.kind == CredentialKind.COLLABORATOR) service.onRedeemed(deviceId, "peer-pub-b64")
            return spec
        }
    }

    @Test
    fun mint_and_redeem_establishes_a_contact_with_fingerprint() = runBlocking {
        val fx = Fixture()
        val created = fx.service.createTicket("Frank")
        assertTrue(created.ok, created.error)
        val invite = assertNotNull(created.invite)
        assertEquals("wss://relay.example", invite.relay)
        assertEquals("acct", invite.accountId)
        assertEquals("daemon-pub", invite.daemonPub)
        assertEquals("Panda · MacBook", invite.ownerLabel)

        val spec = fx.redeem("dev-frank", invite.ticket)
        assertEquals(CredentialKind.COLLABORATOR, assertNotNull(spec).kind)
        assertTrue(spec!!.workdirs.isEmpty(), "a collaborator credential's baseline scope is EMPTY (zero session access)")
        assertTrue(fx.bridges.isCollaborator("dev-frank"))

        val row = fx.service.list().items.single()
        assertEquals("dev-frank", row.deviceId)
        assertEquals("Frank", row.label, "the label pre-assigned at mint rides the intent spec")
        assertFalse(row.removed)
        assertNotNull(row.fingerprint, "the word-group fingerprint is stamped from the peer key")
        // both owner-facing notices fanned out: the waiting-for-scan flip + the picker refresh
        assertTrue(fx.ownerFrames.any { it is CollaboratorConnected && it.collaborator.deviceId == "dev-frank" })
        assertTrue(fx.ownerFrames.any { it is CollaboratorUpdated && it.collaborator.deviceId == "dev-frank" })
    }

    @Test
    fun a_second_mint_while_an_intent_is_pending_is_refused() = runBlocking {
        val fx = Fixture()
        assertTrue(fx.service.createTicket("Frank").ok)
        val second = fx.service.createTicket("Alex")
        assertFalse(second.ok, "the one-pending-intent serialization rule covers collaborator mints too")
        assertNull(second.invite)
    }

    @Test
    fun remove_kills_credential_and_grants_but_keeps_the_row() = runBlocking {
        val fx = Fixture()
        val invite = assertNotNull(fx.service.createTicket("Frank").invite)
        fx.redeem("dev-frank", invite.ticket)

        // a WAITING handoff bound to the contact + an IN_PROGRESS one on another session
        val reg = fx.handoffs.registry
        val waiting = (reg.create(
            sourceSessionId = "sess-1", workdir = "/tmp/p1", agent = AgentKind.CLAUDE,
            initiatorDeviceId = "owner", kind = HandoffKind.REVIEW, access = HandoffAccess.REVIEW_READ_ONLY,
            brief = HandoffBrief(request = "review"), recipientDeviceId = "dev-frank",
        ) as HandoffRegistry.HandoffOutcome.Ok).handoff
        val inProgress = (reg.create(
            sourceSessionId = "sess-2", workdir = "/tmp/p2", agent = AgentKind.CLAUDE,
            initiatorDeviceId = "owner", kind = HandoffKind.REVIEW, access = HandoffAccess.REVIEW_READ_ONLY,
            brief = HandoffBrief(request = "review 2"), recipientDeviceId = "dev-frank",
        ) as HandoffRegistry.HandoffOutcome.Ok).handoff
        assertTrue(reg.accept(inProgress.id, "dev-frank") is HandoffRegistry.HandoffOutcome.Ok)

        val reply = fx.service.remove("dev-frank")
        assertTrue(reply is CollaboratorUpdated && reply.collaborator.removed, "the caller gets the terminal row back")
        assertEquals(listOf("dev-frank"), fx.revokedCredentials, "the credential is revoked exactly once")
        assertFalse(fx.bridges.isCollaborator("dev-frank"), "the E2E key is gone")

        // the Grants died with the link: the offer can never be accepted, the lease is recalled
        assertEquals(HandoffStatus.CANCELLED, reg.byId(waiting.id)?.status)
        assertEquals(HandoffStatus.RECALLED, reg.byId(inProgress.id)?.status)

        // the ROW is kept, flagged — past handoffs still reference this contact
        val row = fx.service.list().items.single()
        assertTrue(row.removed)
        assertEquals("Frank", row.label)

        // a second remove is a clean error, not a crash or double-revoke
        assertTrue(fx.service.remove("dev-frank") is PocketError)
        assertEquals(1, fx.revokedCredentials.size)
    }

    @Test
    fun directory_view_validates_bindings_and_bumps_stats() = runBlocking {
        val fx = Fixture()
        val invite = assertNotNull(fx.service.createTicket("Frank").invite)
        fx.redeem("dev-frank", invite.ticket)

        assertTrue(fx.service.isActive("dev-frank"))
        assertEquals("Frank", fx.service.labelOf("dev-frank"))
        assertFalse(fx.service.isActive("dev-stranger"), "an unknown device is never a bindable contact")

        fx.service.noteHandoff("dev-frank", at = 42L)
        val row = fx.service.list().items.single()
        assertEquals(1, row.handoffCount)
        assertEquals(42L, row.lastHandoffAt)

        fx.service.remove("dev-frank")
        assertFalse(fx.service.isActive("dev-frank"), "a removed contact is not bindable")
        assertNull(fx.service.labelOf("dev-frank"))
    }

    @Test
    fun contact_rows_survive_a_reload_and_reconcile_against_the_key_truth() = runBlocking {
        val fx = Fixture()
        val invite = assertNotNull(fx.service.createTicket("Frank").invite)
        fx.redeem("dev-frank", invite.ticket)

        // the key dies elsewhere (revoked from another device / pruned by the relay attach replay)...
        fx.bridges.remove("dev-frank")
        // ...and the ledger follows the key truth on the next read: the row settles to removed
        val row = fx.service.list().items.single()
        assertTrue(row.removed, "a row without a live credential must read as removed (fail closed)")

        // reload from disk: still there, still removed
        val reloaded = CollaboratorStore.load(fx.tmp.resolve("collaborators.json"))
        assertEquals(true, reloaded.byId("dev-frank")?.removed)
    }

    @Test
    fun the_credential_key_lives_in_its_own_file_not_devices_or_guests() = runBlocking {
        val fx = Fixture()
        val invite = assertNotNull(fx.service.createTicket("Frank").invite)
        fx.redeem("dev-frank", invite.ticket)

        assertTrue(fx.tmp.resolve("collaborator-keys.json").readText().contains("dev-frank"))
        // downgrade isolation: never in the full-power or other restricted stores
        assertFalse(fx.tmp.resolve("bridges.json").takeIf { it.exists() }?.readText()?.contains("dev-frank") == true)
        assertFalse(fx.tmp.resolve("guests.json").takeIf { it.exists() }?.readText()?.contains("dev-frank") == true)

        // a fresh registry over the same dir reloads it as a collaborator
        val reloaded = BridgeRegistry(store = fx.tmp.resolve("bridges.json"))
        assertTrue(reloaded.isCollaborator("dev-frank"))
        assertEquals(CredentialKind.COLLABORATOR, reloaded.kindOf("dev-frank"))
    }

    // ---- purpose separation, against the REAL service (REVIEW-REQUEST.md §13.3) ----

    /**
     * The legacy `pocket/collaborator.*` family is the SESSION HANDOFF view and nothing else.
     *
     * This matters most for an OLD App, which cannot read [Collaborator.purpose] at all: it renders
     * whatever these frames carry straight into its contact page and its handoff recipient picker. A
     * Review peer appearing there is a daemon-purpose link offered for a runtime lease.
     */
    @Test
    fun the_legacy_listing_and_pushes_carry_session_handoff_rows_only() = runBlocking {
        val fx = Fixture()
        val handoff = assertNotNull(fx.service.createTicket("Frank").invite)
        fx.redeem("dev-frank", handoff.ticket)

        fx.mintedTicket = "ticket-2"
        val review = assertNotNull(fx.service.createTicket("Frank's daemon", CollaboratorPurpose.REVIEW).invite)
        assertEquals(CollaboratorPurpose.REVIEW, review.purpose, "the invite says what it establishes")
        fx.ownerFrames.clear()
        fx.redeem("dev-frank-daemon", review.ticket)

        // the row exists and is scoped to its own ledger…
        assertEquals(listOf("dev-frank-daemon"), fx.service.contacts(CollaboratorPurpose.REVIEW).map { it.deviceId })
        // …but the legacy listing does not show it
        assertEquals(listOf("dev-frank"), fx.service.list().items.map { it.deviceId })
        // …and neither does the legacy push: establishing a review link is silent on this family
        assertTrue(
            fx.ownerFrames.none { it is CollaboratorConnected || it is CollaboratorUpdated },
            "a review link must not be announced through the handoff frames: ${fx.ownerFrames}",
        )
    }

    /** An old App cannot see a Review row, so any id it sends that resolves to one is stale or guessed.
     *  Honouring it would revoke a credential its user never knew existed. */
    @Test
    fun the_legacy_remove_refuses_a_review_row_without_touching_it() = runBlocking {
        val fx = Fixture()
        val review = assertNotNull(fx.service.createTicket("Frank's daemon", CollaboratorPurpose.REVIEW).invite)
        fx.redeem("dev-frank-daemon", review.ticket)
        fx.ownerFrames.clear()

        val refused = fx.service.remove("dev-frank-daemon") // the legacy RemoveCollaborator path
        assertTrue(refused is PocketError, "got $refused")
        assertEquals("collaborator_not_found", (refused as PocketError).code)
        // NOTHING was mutated: the credential lives, the row is untouched, no push went out
        assertTrue(fx.revokedCredentials.isEmpty(), "a refusal must not revoke: ${fx.revokedCredentials}")
        assertTrue(fx.bridges.isCollaborator("dev-frank-daemon"))
        assertFalse(fx.service.contacts(CollaboratorPurpose.REVIEW).single().removed)
        assertTrue(fx.ownerFrames.isEmpty())

        // the purpose-scoped path DOES remove it, and that is the only way to
        val ok = fx.service.remove("dev-frank-daemon", CollaboratorPurpose.REVIEW)
        assertTrue(ok is CollaboratorUpdated && ok.collaborator.removed, "got $ok")
        assertEquals(listOf("dev-frank-daemon"), fx.revokedCredentials)
    }

    /** …and the mirror image: a handoff row is not removable through the review-scoped path. */
    @Test
    fun a_purpose_scoped_remove_refuses_the_other_features_row() = runBlocking {
        val fx = Fixture()
        val handoff = assertNotNull(fx.service.createTicket("Frank").invite)
        assertEquals(CollaboratorPurpose.SESSION_HANDOFF, handoff.purpose, "the historical default")
        fx.redeem("dev-frank", handoff.ticket)

        val refused = fx.service.remove("dev-frank", CollaboratorPurpose.REVIEW)
        assertTrue(refused is PocketError, "got $refused")
        assertTrue(fx.revokedCredentials.isEmpty())
        assertFalse(fx.service.list().items.single().removed)
    }

    /** The two directory predicates the router and the send path read — from the real ledger. */
    @Test
    fun each_purpose_is_eligible_for_exactly_one_feature() = runBlocking {
        val fx = Fixture()
        fx.redeem("dev-frank", assertNotNull(fx.service.createTicket("Frank").invite).ticket)
        fx.mintedTicket = "ticket-2"
        fx.redeem("dev-daemon", assertNotNull(fx.service.createTicket("D", CollaboratorPurpose.REVIEW).invite).ticket)

        assertTrue(fx.service.acceptsHandoff("dev-frank"))
        assertFalse(fx.service.acceptsReview("dev-frank"), "a handoff contact is never widened into a review peer")
        assertTrue(fx.service.acceptsReview("dev-daemon"))
        assertFalse(fx.service.acceptsHandoff("dev-daemon"), "a review peer is never a runtime recipient")
    }

    /** A mint whose spec is gone by redeem time has nothing saying what the link is for. Guessing the
     *  historical default would turn a lost REVIEW mint into a handoff-bindable contact. */
    @Test
    fun a_redeem_with_no_surviving_spec_is_eligible_for_neither_feature() = runBlocking {
        val fx = Fixture()
        fx.service.onRedeemed("dev-orphan", "peer-pub-b64") // no intent, no spec — the fail-closed path
        val row = fx.service.contacts(CollaboratorPurpose.UNKNOWN).single()
        assertEquals("dev-orphan", row.deviceId)
        assertFalse(row.acceptsSessionHandoff)
        assertFalse(row.acceptsReviewRequest)
        assertTrue(fx.service.list().items.isEmpty(), "and it is in neither ledger")
    }

    /** An unreadable purpose can only come from a newer peer's value: refuse the mint outright. */
    @Test
    fun an_unreadable_purpose_cannot_be_minted() = runBlocking {
        val fx = Fixture()
        val refused = fx.service.createTicket("Frank", CollaboratorPurpose.UNKNOWN)
        assertFalse(refused.ok)
        assertNull(refused.invite, "a refused mint must not carry establishment material")
    }

    /** The wire type reused as row shape must round-trip removed contacts (history semantics). */
    @Test
    fun store_keeps_removed_rows() {
        val tmp = Files.createTempDirectory("ccp-collab-store").toFile()
        val store = CollaboratorStore.load(tmp.resolve("collaborators.json"))
        store.upsert(Collaborator(deviceId = "d1", label = "Frank", removed = false))
        store.upsert(Collaborator(deviceId = "d1", label = "Frank", removed = true))
        store.upsert(Collaborator(deviceId = "d2", label = "Alex"))
        assertEquals(2, store.all().size)
        assertEquals(true, store.byId("d1")?.removed)
        assertEquals(false, store.byId("d2")?.removed)
    }
}
