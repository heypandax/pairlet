package dev.ccpocket.daemon.handoff

import dev.ccpocket.daemon.bridge.BridgeRegistry
import dev.ccpocket.daemon.bridge.BridgeSpec
import dev.ccpocket.daemon.bridge.CredentialKind
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Collaborator
import dev.ccpocket.protocol.CollaboratorConnected
import dev.ccpocket.protocol.CollaboratorUpdated
import dev.ccpocket.protocol.CreateCollaboratorTicket
import dev.ccpocket.protocol.HandoffAccess
import dev.ccpocket.protocol.HandoffBrief
import dev.ccpocket.protocol.HandoffKind
import dev.ccpocket.protocol.HandoffStatus
import dev.ccpocket.protocol.PairTicket
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.ToPhone
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
        val created = fx.service.createTicket(CreateCollaboratorTicket(label = "Frank"))
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
        assertTrue(fx.service.createTicket(CreateCollaboratorTicket("Frank")).ok)
        val second = fx.service.createTicket(CreateCollaboratorTicket("Alex"))
        assertFalse(second.ok, "the one-pending-intent serialization rule covers collaborator mints too")
        assertNull(second.invite)
    }

    @Test
    fun remove_kills_credential_and_grants_but_keeps_the_row() = runBlocking {
        val fx = Fixture()
        val invite = assertNotNull(fx.service.createTicket(CreateCollaboratorTicket("Frank")).invite)
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
        val invite = assertNotNull(fx.service.createTicket(CreateCollaboratorTicket("Frank")).invite)
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
        val invite = assertNotNull(fx.service.createTicket(CreateCollaboratorTicket("Frank")).invite)
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
        val invite = assertNotNull(fx.service.createTicket(CreateCollaboratorTicket("Frank")).invite)
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
