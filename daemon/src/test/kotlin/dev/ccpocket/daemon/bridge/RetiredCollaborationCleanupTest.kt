package dev.ccpocket.daemon.bridge

import dev.ccpocket.daemon.RetiredPeerLinks
import dev.ccpocket.daemon.execution.ExecutionGrantDraft
import dev.ccpocket.daemon.execution.ExecutionGrantStore
import dev.ccpocket.daemon.execution.ExecutionRelayPolicy
import dev.ccpocket.daemon.execution.ExecutionSource
import dev.ccpocket.daemon.execution.ExecutionTarget
import dev.ccpocket.daemon.execution.ExecutionTargetHarness
import dev.ccpocket.daemon.execution.FakeRelay
import dev.ccpocket.daemon.execution.FixtureTransport
import dev.ccpocket.daemon.execution.encodeUri
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.identity.PairedDevices
import dev.ccpocket.daemon.peer.PeerChannel
import dev.ccpocket.daemon.peer.PeerLink
import dev.ccpocket.daemon.peer.PeerLinkSecret
import dev.ccpocket.daemon.peer.PeerLinkStore
import dev.ccpocket.daemon.peer.PeerSession
import dev.ccpocket.protocol.AccessTier
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ClientCaps
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.ExecutionGrantQuery
import dev.ccpocket.protocol.ExecutionRunAccepted
import dev.ccpocket.protocol.ExecutionRunSubmit
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SessionGone
import dev.ccpocket.protocol.ToDaemon
import dev.ccpocket.protocol.e2e.E2ECrypto
import dev.ccpocket.protocol.e2e.E2ESession
import dev.ccpocket.protocol.executionAgentWire
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Session handoff and review contacts were retired (2026-10), and with them the Collaborator Link credential
 * and the review peer links. An upgraded daemon clears both at startup — and must not disturb anything else
 * on the way: not remote execution (grants, credentials, links, journals), not bridges or folder-share guests,
 * not the owner's own devices.
 *
 * One machine is staged with every kind of state at once (a target daemon with an ACTIVE execution grant +
 * credential, a bridge, a guest and an owner phone; a source daemon with a used execution link), the retired
 * collaborator keys and review peer links are added the way an older daemon left them, and then the upgrade
 * runs: a target restart (which builds the credential registry) and the peer-link clear.
 */
class RetiredCollaborationCleanupTest {

    private val root = createTempDirectory("ccp-retired-collab").toFile()
    private var clock = 1_800_000_000_000L
    private val targetDir = File(root, "target").apply { mkdirs() }
    private val sourceDir = File(root, "source").apply { mkdirs() }
    private val ws = File(root, "ws/app").apply { mkdirs() }
    private val targetIdentity = Identity.loadOrCreate(File(targetDir, "identity.json"))
    private val relay = FakeRelay(targetIdentity.accountId) { clock }
    private val harness = ExecutionTargetHarness(targetDir, relay, targetIdentity, now = { clock })
    private val transport = FixtureTransport(relay) { harness }
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    private val collabReview = "dev-collab-review"
    private val collabHandoff = "dev-collab-handoff"
    private val collabPubs = mapOf(collabReview to newPub(), collabHandoff to newPub())

    @AfterTest
    fun cleanup() { root.deleteRecursively() }

    private fun newPub(): String = b64.encodeToString(E2ECrypto.generateKeyPair().publicRaw)

    private fun sourceLinks() =
        PeerLinkStore.load(File(sourceDir, "execution-links.json"), File(sourceDir, "execution-link-secrets.json"))

    private fun source() = ExecutionSource(transport, sourceLinks(), ExecutionRelayPolicy(setOf("relay.test"))) { clock }

    private fun draft() = ExecutionGrantDraft(
        sourceLabel = "Studio Mac",
        workspaces = mapOf("app" to ws.path),
        allowedAgents = listOf(AgentKind.CLAUDE),
        approvalCeiling = PermissionMode.DEFAULT,
        ttlMs = 7L * 24 * 3600_000,
    )

    private suspend fun handshakeAs(deviceId: String, keys: E2ECrypto.KeyPair, psk: String): E2ESession? {
        val init = E2ESession.initiator(keys.privateRaw, keys.publicRaw, targetIdentity.e2ePubRaw, psk.encodeToByteArray())
        return harness.handshake(deviceId, init.ephPublic)?.let { init.finish(it) }
    }

    private suspend fun exchange(deviceId: String, session: E2ESession, frame: Frame): List<Frame> {
        harness.send(deviceId, session, frame)
        return harness.drain(deviceId, session)
    }

    /** A bridge / guest bound through the real intent → first-frame finalize chain, so the registry wrote it. */
    private suspend fun restricted(deviceId: String, spec: BridgeSpec) {
        val ticket = "t-$deviceId"
        assertTrue(harness.bridges.recordIntent(ticket, spec, ttlMs = 600_000))
        harness.sessions.onMintedTicket(ticket, headless = true)
        val keys = E2ECrypto.generateKeyPair()
        harness.sessions.onDevicePaired(deviceId, b64.encodeToString(keys.publicRaw))
        exchange(deviceId, assertNotNull(handshakeAs(deviceId, keys, ticket)), ClientCaps())
    }

    /** Send raw frames to the target over the source's link; the replies. */
    private suspend fun dial(linkId: String, frames: List<ToDaemon>): List<Frame> {
        val links = sourceLinks()
        val got = mutableListOf<Frame>()
        transport.dial(links.byId(linkId)!!, links.secretOf(linkId)!!, object : PeerSession {
            override suspend fun onOpen(channel: PeerChannel) { frames.forEach { channel.send(it) } }
            override suspend fun onFrame(channel: PeerChannel, frame: Frame) { got += frame }
        })
        return got
    }

    /** collaborator-keys.json exactly as an older daemon wrote it: one row per purpose, `purpose` included. */
    private fun writeOldCollaboratorKeys() {
        val rows = collabPubs.entries.associate { (id, pub) ->
            val entry = PocketJson.encodeToJsonElement(
                BridgeEntry.serializer(),
                BridgeEntry(pub, BridgeSpec("Frank", emptyList(), maxSessions = 1, kind = CredentialKind.COLLABORATOR), 1_700_000_000_000L),
            ).jsonObject
            val spec = JsonObject(entry["spec"]!!.jsonObject + ("purpose" to JsonPrimitive(if (id == collabReview) "REVIEW" else "SESSION_HANDOFF")))
            id to JsonObject(entry + ("spec" to spec))
        }
        File(targetDir, "collaborator-keys.json").writeText(PocketJson.encodeToString(JsonObject.serializer(), JsonObject(rows)))
    }

    private fun writeReviewPeerLink() {
        val keys = E2ECrypto.generateKeyPair()
        val pub = b64.encodeToString(keys.publicRaw)
        val store = PeerLinkStore.load(File(sourceDir, RetiredPeerLinks.PUBLIC_FILE), File(sourceDir, RetiredPeerLinks.SECRET_FILE))
        assertTrue(
            store.put(
                PeerLink("pl_review", "Frank", "wss://relay.test", "acct-frank", newPub(), "dev-at-frank", "fp", clock),
                PeerLinkSecret("pl_review", "relay-credential-at-frank", b64.encodeToString(keys.privateRaw), pub),
            ),
        )
    }

    private val retiredFiles = setOf(
        "collaborator-keys.json", RetiredCredentialStore.FILE_NAME, RetiredPeerLinks.PUBLIC_FILE, RetiredPeerLinks.SECRET_FILE,
    )

    /** sha256 of every file under both daemon directories, except the four the cleanup is meant to touch. */
    private fun snapshot(): Map<String, String> =
        listOf(targetDir, sourceDir).flatMap { dir ->
            dir.walkTopDown().filter { it.isFile && it.name !in retiredFiles }.map { f ->
                f.relativeTo(root).path to MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
            }
        }.toMap()

    private fun tombstones(): String = File(targetDir, RetiredCredentialStore.FILE_NAME).readText()

    @Test
    fun the_upgrade_clears_collaborator_credentials_and_review_links_and_nothing_else(): Unit = runBlocking {
        // ---- stage: an ACTIVE execution grant + credential, its source link (ticket burned), a bridge, a guest,
        // and an owner phone that completed first contact
        val appr = assertIs<ExecutionTarget.Approval.Ok>(harness.target.approve(draft()))
        var source = source()
        val join = assertIs<ExecutionSource.Join.Ok>(source.join(appr.invite.encodeUri(), appr.grant.targetDaemonFingerprint))
        val grantId = appr.grant.grantId
        assertEquals("awaiting_owner_confirm", assertIs<ExecutionSource.Query.Ok>(source.query(grantId)).info.state)
        assertIs<ExecutionGrantStore.Write.Ok>(harness.target.confirmSource(grantId, join.sourceFingerprint))
        assertEquals("active", assertIs<ExecutionSource.Query.Ok>(source.query(grantId)).info.state)
        val execDevice = join.link.deviceId
        assertNull(sourceLinks().secretOf(grantId)!!.ticket, "precondition: the execution link's first contact is done")

        restricted("dev-bridge", BridgeSpec("feishu-bot", listOf(ws.path)))
        restricted("dev-guest", BridgeSpec.guest("guest", ws.path, AccessTier.REVIEW, expiresAt = System.currentTimeMillis() + 3_600_000))
        val ownerKeys = E2ECrypto.generateKeyPair()
        harness.sessions.onMintedTicket("phone-ticket", headless = true) // headless: no #91 exclusion stamp
        harness.sessions.onDevicePaired("owner-phone", b64.encodeToString(ownerKeys.publicRaw))
        exchange("owner-phone", assertNotNull(handshakeAs("owner-phone", ownerKeys, "phone-ticket")), ClientCaps())
        assertEquals(setOf("owner-phone"), PairedDevices.load(File(targetDir, "devices.json")).keys)
        for (name in listOf("execution-credentials.json", "execution-grants.json", "bridges.json", "guests.json", "devices.json")) {
            assertTrue(File(targetDir, name).exists(), "precondition: $name on disk")
        }
        for (name in listOf("execution-links.json", "execution-link-secrets.json")) {
            assertTrue(File(sourceDir, name).exists(), "precondition: $name on disk")
        }

        // ---- what an older daemon left behind
        writeOldCollaboratorKeys()
        writeReviewPeerLink()
        val before = snapshot()

        // ---- the upgrade
        harness.restart()
        assertEquals(1, RetiredPeerLinks.clear(sourceDir))

        // execution, bridges, guests and the owner's devices: every byte as it was
        assertEquals(before, snapshot(), "only the retired files may change")
        assertTrue(harness.bridges.isExecution(execDevice))
        assertEquals(grantId, harness.bridges.executionGrantIdOf(execDevice))
        assertTrue(harness.bridges.isBridge("dev-bridge"))
        assertTrue(harness.bridges.isGuest("dev-guest"))
        assertEquals(setOf("owner-phone"), PairedDevices.load(File(targetDir, "devices.json")).keys)

        // the collaborator keys are gone, their ids tombstoned (ids only — no key material)
        assertEquals("{}", File(targetDir, "collaborator-keys.json").readText())
        assertEquals(collabPubs.keys, harness.bridges.retiredCredentialIds())
        for ((id, pub) in collabPubs) {
            assertFalse(harness.bridges.isRestricted(id), "$id is no credential any more")
            assertNull(harness.bridges.pubOf(id))
            assertTrue(harness.sessions.isKnownDevice(id), "$id stays known while tombstoned")
            assertFalse(tombstones().contains(pub), "the tombstone holds no key")
        }
        assertTrue(collabPubs.keys.all { tombstones().contains(it) })

        // the review peer link lost its credential and key; its public row is kept, marked removed
        val review = PeerLinkStore.load(File(sourceDir, RetiredPeerLinks.PUBLIC_FILE), File(sourceDir, RetiredPeerLinks.SECRET_FILE))
        assertNull(review.secretOf("pl_review"))
        assertTrue(review.all().single().removed)
        val secretsText = File(sourceDir, RetiredPeerLinks.SECRET_FILE).readText()
        assertFalse(secretsText.contains("relay-credential-at-frank"), "no relay credential left on disk")
        assertFalse(secretsText.contains("privateKeyB64"), "no private key left on disk")

        // ---- the execution grant and link made BEFORE the upgrade still work after it: a fresh handshake
        // (the target restarted, so its live session is gone) answers the grant query and accepts a run
        source = source()
        assertEquals("active", assertIs<ExecutionSource.Query.Ok>(source.query(grantId)).info.state)
        val accepted = dial(grantId, listOf(ExecutionRunSubmit("rq_after", grantId, 1, "app", executionAgentWire(AgentKind.CLAUDE), "hello")))
        assertIs<ExecutionRunAccepted>(accepted.single(), "the real run plane accepts a run: $accepted")
        assertEquals("active", assertIs<ExecutionSource.Query.Ok>(source.query(grantId)).info.state)

        // ---- and the owner phone still routes as an owner (its first contact is done: empty PSK)
        val owner = assertNotNull(handshakeAs("owner-phone", ownerKeys, ""))
        assertTrue(exchange("owner-phone", owner, ClientCaps()).any { it is DaemonInfo })
        assertEquals("ghost", assertIs<SessionGone>(exchange("owner-phone", owner, SendPrompt("ghost", "hi")).single()).convoId)
    }

    @Test
    fun a_tombstoned_id_is_never_armed_never_allow_listed_and_never_handshakes() = runBlocking {
        writeOldCollaboratorKeys()
        harness.restart()
        val devices = File(targetDir, "devices.json")
        val devicesBefore = devices.takeIf { it.exists() }?.readBytes()

        // someone is pairing a new phone right now: an interactive ticket is armed
        harness.sessions.onMintedTicket("phone-window")
        // …and the relay, which has not revoked the old collaborator yet, re-announces it
        harness.sessions.onDevicePaired(collabReview, collabPubs.getValue(collabReview))

        assertFalse(PairedDevices.load(devices).containsKey(collabReview), "never written into the full-power allow-list")
        assertTrue(devicesBefore.contentEquals(devices.takeIf { it.exists() }?.readBytes()), "devices.json untouched")
        assertFalse(harness.sessions.firstContactPending(collabReview), "no pairing ticket was armed for it")
        assertFalse(harness.bridges.isBridgeCandidate(collabReview), "…nor held provisional")
        // the old key is gone, so its handshake goes unanswered (unknown device)
        assertNull(handshakeAs(collabReview, E2ECrypto.generateKeyPair(), ""))
        assertNull(handshakeAs(collabReview, E2ECrypto.generateKeyPair(), "phone-window"))

        // the armed ticket was left for the phone it belongs to
        val phone = E2ECrypto.generateKeyPair()
        harness.sessions.onDevicePaired("new-phone", b64.encodeToString(phone.publicRaw))
        assertEquals(setOf("new-phone"), PairedDevices.load(devices).keys)
        assertTrue(handshakeAs("new-phone", phone, "phone-window") != null)
    }

    @Test
    fun the_relay_is_asked_to_revoke_each_tombstone_until_it_confirms_and_never_again_after() = runBlocking {
        writeOldCollaboratorKeys()
        harness.restart()
        assertEquals(collabPubs.keys, harness.sessions.pendingRetiredRevocations().map { it.deviceId }.toSet())

        // the relay is unreachable: nothing confirms. A restart neither fails nor forgets, nor rewrites anything
        val tombBytes = tombstones()
        harness.restart()
        harness.restart()
        assertEquals("{}", File(targetDir, "collaborator-keys.json").readText())
        assertEquals(tombBytes, tombstones())
        assertEquals(collabPubs.keys, harness.sessions.pendingRetiredRevocations().map { it.deviceId }.toSet())

        // next attach: the authoritative replay still carries the review contact (the relay never revoked it)
        // but not the handoff one (already revoked there) — that one's tombstone goes at the barrier
        val ownerKeys = E2ECrypto.generateKeyPair()
        harness.sessions.onMintedTicket("phone-ticket")
        harness.sessions.onDevicePaired("owner-phone", b64.encodeToString(ownerKeys.publicRaw))
        val devicesBefore = File(targetDir, "devices.json").readBytes()
        harness.sessions.beginAttachReplay()
        harness.sessions.onDevicePaired("owner-phone", b64.encodeToString(ownerKeys.publicRaw))
        harness.sessions.onDevicePaired(collabReview, collabPubs.getValue(collabReview))
        harness.sessions.reconcileReplay(authoritativeEmpty = true)
        assertEquals(setOf(collabReview), harness.bridges.retiredCredentialIds())
        assertEquals(listOf(collabReview), harness.sessions.pendingRetiredRevocations().map { it.deviceId })

        // the relay confirms the revoke it was asked for: the last tombstone goes, and nothing else moves
        harness.sessions.onRelayDeviceRevoked(collabReview)
        assertEquals(emptySet(), harness.bridges.retiredCredentialIds())
        assertTrue(devicesBefore.contentEquals(File(targetDir, "devices.json").readBytes()), "devices.json untouched")
        assertEquals(setOf("owner-phone"), PairedDevices.load(File(targetDir, "devices.json")).keys)
        assertFalse(tombstones().contains(collabReview))

        // a later start asks for nothing again
        harness.restart()
        assertEquals(emptyList(), harness.sessions.pendingRetiredRevocations())
        assertFalse(harness.sessions.isKnownDevice(collabReview))
        // …and the cleared peer-link store is likewise left alone on a second clear
        writeReviewPeerLink()
        assertEquals(1, RetiredPeerLinks.clear(sourceDir))
        val cleared = File(sourceDir, RetiredPeerLinks.SECRET_FILE).readText()
        assertEquals(0, RetiredPeerLinks.clear(sourceDir))
        assertEquals(cleared, File(sourceDir, RetiredPeerLinks.SECRET_FILE).readText())
    }

    @Test
    fun a_relay_revoke_of_an_ordinary_device_still_takes_the_ordinary_path() = runBlocking {
        writeOldCollaboratorKeys()
        harness.restart()
        val keys = E2ECrypto.generateKeyPair()
        harness.sessions.onMintedTicket("phone-ticket")
        harness.sessions.onDevicePaired("owner-phone", b64.encodeToString(keys.publicRaw))
        assertEquals(setOf("owner-phone"), PairedDevices.load(File(targetDir, "devices.json")).keys)
        harness.sessions.onRelayDeviceRevoked("owner-phone")
        assertEquals(emptySet(), PairedDevices.load(File(targetDir, "devices.json")).keys, "an owner device is revoked as before")
        assertEquals(collabPubs.keys, harness.bridges.retiredCredentialIds(), "…and no tombstone moved")
    }

    @Test
    fun keys_are_kept_when_the_tombstones_cannot_be_written() {
        writeOldCollaboratorKeys()
        val keysBefore = File(targetDir, "collaborator-keys.json").readText()
        assertTrue(targetDir.setWritable(false, false)) // every write into the directory fails
        val registry = try {
            BridgeRegistry(File(targetDir, "bridges.json"))
        } finally {
            targetDir.setWritable(true, false)
        }
        assertFalse(File(targetDir, RetiredCredentialStore.FILE_NAME).exists())
        assertEquals(keysBefore, File(targetDir, "collaborator-keys.json").readText(), "fail closed: the keys stay for the next start")
        assertEquals(collabPubs.keys, registry.retiredCredentialIds(), "…and this run still treats them as retired")
        assertTrue(collabPubs.keys.none { registry.isRestricted(it) }, "…and never as credentials")
    }
}
