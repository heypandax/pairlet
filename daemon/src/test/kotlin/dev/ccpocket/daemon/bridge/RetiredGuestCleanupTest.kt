package dev.ccpocket.daemon.bridge

import dev.ccpocket.daemon.DaemonCore
import dev.ccpocket.daemon.agent.AgentBackendFactory
import dev.ccpocket.daemon.conversation.LifecycleBackend
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
import dev.ccpocket.daemon.peer.PeerLinkStore
import dev.ccpocket.daemon.peer.PeerSession
import dev.ccpocket.daemon.pins.MemoryProjectPinStore
import dev.ccpocket.daemon.pins.PinStoreState
import dev.ccpocket.daemon.relay.DeviceSessions
import dev.ccpocket.protocol.AccessTier
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ClientCaps
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.ExecutionRunAccepted
import dev.ccpocket.protocol.ExecutionRunSubmit
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ListDirectories
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SessionGone
import dev.ccpocket.protocol.ShareEnded
import dev.ccpocket.protocol.ToDaemon
import dev.ccpocket.protocol.e2e.E2ECrypto
import dev.ccpocket.protocol.e2e.E2ESession
import dev.ccpocket.protocol.e2e.Wire
import dev.ccpocket.protocol.executionAgentWire
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
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
 * Folder sharing (#115) was retired (2026-10), and with it the GUEST credential. An upgraded daemon refuses a
 * guest outright from its first frame, and once its relay link is up it retires every guest it still holds the
 * way the Collaborator Link was retired ([RetiredCollaborationCleanupTest]): tombstone first, then the key, then
 * a relay revoke until the relay confirms — and must not disturb anything else on the way: not remote execution
 * (grants, credentials, links), not bridges (above all not one that carries the guest's label), not the owner's
 * own devices, not the collaborator tombstones an earlier upgrade left.
 */
class RetiredGuestCleanupTest {

    private val root = createTempDirectory("ccp-retired-guest").toFile()
    private var clock = 1_800_000_000_000L
    private val targetDir = File(root, "target").apply { mkdirs() }
    private val sourceDir = File(root, "source").apply { mkdirs() }
    private val ws = File(root, "ws/app").apply { mkdirs() }
    private val targetIdentity = Identity.loadOrCreate(File(targetDir, "identity.json"))
    private val relay = FakeRelay(targetIdentity.accountId) { clock }
    private val harness = ExecutionTargetHarness(targetDir, relay, targetIdentity, now = { clock })
    private val transport = FixtureTransport(relay) { harness }
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    private val guestId = "dev-guest"
    private val tombstoneFile = File(targetDir, RetiredCredentialStore.FILE_NAME)
    private val guestsFile = File(targetDir, "guests.json")

    @AfterTest
    fun cleanup() { root.deleteRecursively() }

    private fun guestSpec(label: String, dir: File) =
        BridgeSpec(label, listOf(dir.canonicalPath), kind = CredentialKind.GUEST, expiresAt = Long.MAX_VALUE, tier = AccessTier.COLLABORATE)

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
    private suspend fun restricted(deviceId: String, spec: BridgeSpec): Pair<E2ECrypto.KeyPair, List<Frame>> {
        val ticket = "t-$deviceId"
        assertTrue(harness.bridges.recordIntent(ticket, spec, ttlMs = 600_000))
        harness.sessions.onMintedTicket(ticket, headless = true)
        val keys = E2ECrypto.generateKeyPair()
        harness.sessions.onDevicePaired(deviceId, b64.encodeToString(keys.publicRaw))
        val first = exchange(deviceId, assertNotNull(handshakeAs(deviceId, keys, ticket)), OpenSession(ws.path))
        return keys to first
    }

    private suspend fun dial(linkId: String, frames: List<ToDaemon>): List<Frame> {
        val links = sourceLinks()
        val got = mutableListOf<Frame>()
        transport.dial(links.byId(linkId)!!, links.secretOf(linkId)!!, object : PeerSession {
            override suspend fun onOpen(channel: PeerChannel) { frames.forEach { channel.send(it) } }
            override suspend fun onFrame(channel: PeerChannel, frame: Frame) { got += frame }
        })
        return got
    }

    /** sha256 of every file under both daemon directories, except the two the retirement is meant to touch. */
    private fun snapshot(): Map<String, String> =
        listOf(targetDir, sourceDir).flatMap { dir ->
            dir.walkTopDown().filter { it.isFile && it.name != guestsFile.name && it.name != tombstoneFile.name }.map { f ->
                f.relativeTo(root).path to MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
            }
        }.toMap()

    @Test
    fun retiring_clears_only_the_guest_keys_and_everything_else_keeps_working(): Unit = runBlocking {
        // ---- stage: an ACTIVE execution grant + credential and its source link, a bridge, a guest, an owner phone,
        // and the collaborator tombstones an earlier upgrade left behind
        val appr = assertIs<ExecutionTarget.Approval.Ok>(harness.target.approve(draft()))
        var source = source()
        val join = assertIs<ExecutionSource.Join.Ok>(source.join(appr.invite.encodeUri(), appr.grant.targetDaemonFingerprint))
        val grantId = appr.grant.grantId
        assertEquals("awaiting_owner_confirm", assertIs<ExecutionSource.Query.Ok>(source.query(grantId)).info.state)
        assertIs<ExecutionGrantStore.Write.Ok>(harness.target.confirmSource(grantId, join.sourceFingerprint))
        assertEquals("active", assertIs<ExecutionSource.Query.Ok>(source.query(grantId)).info.state)
        val execDevice = join.link.deviceId

        val (bridgeKeys, _) = restricted("dev-bridge", BridgeSpec("feishu-bot", listOf(ws.canonicalPath)))
        val (guestKeys, guestFirst) = restricted(guestId, guestSpec("guest", ws))
        assertTrue(guestFirst.isEmpty(), "a guest's frame is refused and answered with nothing: $guestFirst")
        assertTrue(harness.bridges.isGuest(guestId), "precondition: the guest key is loaded (and refused)")
        val ownerKeys = E2ECrypto.generateKeyPair()
        harness.sessions.onMintedTicket("phone-ticket") // interactive: the only kind that anchors a full-power device
        harness.sessions.onDevicePaired("owner-phone", b64.encodeToString(ownerKeys.publicRaw))
        exchange("owner-phone", assertNotNull(handshakeAs("owner-phone", ownerKeys, "phone-ticket")), ClientCaps())
        val collabTombstones = setOf("dev-collab-1", "dev-collab-2")
        assertTrue(RetiredCredentialStore.save(collabTombstones, tombstoneFile))
        val guestPub = b64.encodeToString(guestKeys.publicRaw)
        assertTrue(guestsFile.readText().contains(guestPub), "precondition: the guest key is on disk")

        // ---- the upgrade: a restart, then the relay link comes up and the retirement runs
        harness.restart()
        assertTrue(harness.bridges.isGuest(guestId), "a restart alone retires nothing")
        assertTrue(RetiredCredentialStore.load(tombstoneFile) == collabTombstones, "…and the old tombstones still load")
        val before = snapshot()
        assertFalse(harness.sessions.retireLegacyGuests(), "no guest was online across the restart: no notice")

        // every other file: every byte as it was
        assertEquals(before, snapshot(), "only guests.json and the tombstone file may change")
        // the guest key is gone from memory and disk; its id is tombstoned NEXT TO the collaborator ones (ids only)
        assertFalse(guestsFile.readText().contains(guestPub), "no guest key left on disk")
        assertFalse(guestsFile.readText().contains(guestId))
        assertEquals(collabTombstones + guestId, RetiredCredentialStore.load(tombstoneFile))
        assertFalse(tombstoneFile.readText().contains(guestPub), "the tombstone holds no key")
        assertFalse(harness.bridges.isGuest(guestId))
        assertFalse(harness.bridges.isRestricted(guestId))
        assertNull(harness.bridges.pubOf(guestId))
        assertTrue(harness.sessions.isKnownDevice(guestId), "known while tombstoned")
        assertEquals(collabTombstones + guestId, harness.sessions.pendingRetiredRevocations().map { it.deviceId }.toSet())

        // ---- the execution grant and link made BEFORE still work: query + a real run
        assertTrue(harness.bridges.isExecution(execDevice))
        source = source()
        assertEquals("active", assertIs<ExecutionSource.Query.Ok>(source.query(grantId)).info.state)
        val accepted = dial(grantId, listOf(ExecutionRunSubmit("rq_after", grantId, 1, "app", executionAgentWire(AgentKind.CLAUDE), "hello")))
        assertIs<ExecutionRunAccepted>(accepted.single(), "the real run plane accepts a run: $accepted")

        // ---- the bridge still handshakes (first contact done: empty PSK) and its open still ROUTES — this core has
        // no backends, so agent_unavailable is the router's answer, proof the frame got past the bridge gate
        assertTrue(harness.bridges.isBridge("dev-bridge"))
        val bridge = assertNotNull(handshakeAs("dev-bridge", bridgeKeys, ""))
        assertEquals("agent_unavailable", assertIs<PocketError>(exchange("dev-bridge", bridge, OpenSession(ws.path)).single()).code)

        // ---- and the owner phone still routes as an owner
        val owner = assertNotNull(handshakeAs("owner-phone", ownerKeys, ""))
        assertTrue(exchange("owner-phone", owner, ClientCaps()).any { it is DaemonInfo })
        assertEquals("ghost", assertIs<SessionGone>(exchange("owner-phone", owner, SendPrompt("ghost", "hi")).single()).convoId)
        assertEquals(setOf("owner-phone"), PairedDevices.load(File(targetDir, "devices.json")).keys)
    }

    @Test
    fun a_guest_is_refused_and_its_tombstoned_id_is_never_armed_nor_allow_listed(): Unit = runBlocking {
        val (guestKeys, first) = restricted(guestId, guestSpec("guest", ws))
        assertTrue(first.isEmpty(), "refused before the retirement too: $first")
        // after a restart (the upgrade) the old guest reconnects — every frame is still refused, nothing is opened
        harness.restart()
        val again = assertNotNull(handshakeAs(guestId, guestKeys, ""), "the key is still loaded until the retirement")
        for (frame in listOf(ClientCaps(), OpenSession(ws.path), ListDirectories(root = "/"))) {
            assertTrue(exchange(guestId, again, frame).isEmpty(), "${frame::class.simpleName} must be refused")
        }
        assertEquals(0, harness.core.registry.liveCountOf(listOf(guestId)))

        harness.sessions.retireLegacyGuests()
        val devices = File(targetDir, "devices.json")
        val devicesBefore = devices.takeIf { it.exists() }?.readBytes()
        // someone is pairing a new phone right now, and the relay — which has not revoked the guest yet — re-announces it
        harness.sessions.onMintedTicket("phone-window")
        harness.sessions.onDevicePaired(guestId, b64.encodeToString(guestKeys.publicRaw))
        assertFalse(PairedDevices.load(devices).containsKey(guestId), "never written into the full-power allow-list")
        assertTrue(devicesBefore.contentEquals(devices.takeIf { it.exists() }?.readBytes()), "devices.json untouched")
        assertFalse(harness.sessions.firstContactPending(guestId), "no pairing ticket was armed for it")
        assertFalse(harness.bridges.isBridgeCandidate(guestId), "…nor held provisional")
        // its key is gone: a handshake goes unanswered, with or without the armed ticket
        assertNull(handshakeAs(guestId, guestKeys, ""))
        assertNull(handshakeAs(guestId, guestKeys, "phone-window"))

        // the armed ticket was left for the phone it belongs to
        val phone = E2ECrypto.generateKeyPair()
        harness.sessions.onDevicePaired("new-phone", b64.encodeToString(phone.publicRaw))
        assertEquals(setOf("new-phone"), PairedDevices.load(devices).keys)
        assertNotNull(handshakeAs("new-phone", phone, "phone-window"))
    }

    @Test
    fun the_relay_is_asked_to_revoke_until_it_confirms_and_retiring_is_idempotent(): Unit = runBlocking {
        restricted(guestId, guestSpec("guest", ws))
        restricted("dev-guest-2", guestSpec("guest", ws))
        harness.restart()
        harness.sessions.retireLegacyGuests()
        val both = setOf(guestId, "dev-guest-2")
        assertEquals(both, harness.sessions.pendingRetiredRevocations().map { it.deviceId }.toSet())

        // the relay is unreachable: nothing confirms. Retiring again and restarting neither fail, forget nor rewrite
        val tombBytes = tombstoneFile.readText()
        val guestsBytes = guestsFile.readText()
        assertFalse(harness.sessions.retireLegacyGuests())
        harness.restart()
        harness.restart()
        assertFalse(harness.sessions.retireLegacyGuests(), "nothing left to retire after a restart")
        assertEquals(tombBytes, tombstoneFile.readText())
        assertEquals(guestsBytes, guestsFile.readText())
        assertTrue(both.none { harness.bridges.isGuest(it) }, "the keys stay gone")
        assertEquals(both, harness.sessions.pendingRetiredRevocations().map { it.deviceId }.toSet(), "asked again next attach")

        // next attach: the authoritative replay no longer carries dev-guest-2 (already revoked there) — its tombstone
        // goes at the barrier; the relay then confirms the revoke it was asked for, and the last one goes
        val ownerKeys = E2ECrypto.generateKeyPair()
        harness.sessions.onMintedTicket("phone-ticket")
        harness.sessions.onDevicePaired("owner-phone", b64.encodeToString(ownerKeys.publicRaw))
        val devicesBefore = File(targetDir, "devices.json").readBytes()
        harness.sessions.beginAttachReplay()
        harness.sessions.onDevicePaired("owner-phone", b64.encodeToString(ownerKeys.publicRaw))
        harness.sessions.onDevicePaired(guestId, b64.encodeToString(E2ECrypto.generateKeyPair().publicRaw))
        harness.sessions.reconcileReplay(authoritativeEmpty = true)
        assertEquals(listOf(guestId), harness.sessions.pendingRetiredRevocations().map { it.deviceId })
        harness.sessions.onRelayDeviceRevoked(guestId)
        assertEquals(emptyList(), harness.sessions.pendingRetiredRevocations())
        assertEquals(emptySet(), RetiredCredentialStore.load(tombstoneFile))
        assertTrue(devicesBefore.contentEquals(File(targetDir, "devices.json").readBytes()), "devices.json untouched")

        // a later start asks for nothing and retires nothing
        harness.restart()
        assertFalse(harness.sessions.retireLegacyGuests())
        assertEquals(emptyList(), harness.sessions.pendingRetiredRevocations())
        assertFalse(harness.sessions.isKnownDevice(guestId))
    }

    @Test
    fun keys_are_kept_when_the_tombstone_cannot_be_written(): Unit = runBlocking {
        val (guestKeys, _) = restricted(guestId, guestSpec("guest", ws))
        harness.restart()
        val guestsBefore = guestsFile.readText()
        assertTrue(targetDir.setWritable(false, false)) // every write into the directory fails
        val noticed = try {
            harness.sessions.retireLegacyGuests()
        } finally {
            targetDir.setWritable(true, false)
        }
        assertFalse(noticed)
        assertFalse(tombstoneFile.exists())
        assertEquals(guestsBefore, guestsFile.readText(), "fail closed: the key stays for the next attach")
        assertTrue(harness.bridges.isGuest(guestId), "…still loaded, so still recognised and refused")
        assertEquals(emptyList(), harness.sessions.pendingRetiredRevocations(), "…and the relay is not asked yet")
        val session = assertNotNull(handshakeAs(guestId, guestKeys, ""))
        assertTrue(exchange(guestId, session, OpenSession(ws.path)).isEmpty())

        // the next attach succeeds
        harness.sessions.retireLegacyGuests()
        assertFalse(harness.bridges.isGuest(guestId))
        assertEquals(setOf(guestId), RetiredCredentialStore.load(tombstoneFile))
    }

    // ---- a guest whose label equals a live bridge's origin ----

    private inner class LiveHarness {
        val dir = File(root, "live").apply { mkdirs() }
        val identity = Identity.loadOrCreate(File(dir, "identity.json"))
        val bridges = BridgeRegistry(File(dir, "bridges.json"))
        // never launches: a plain (non-takeOver) open is lazy (#61), so a bridge's open leaves a live conversation
        // without any process behind it
        val core = DaemonCore(
            mapOf(AgentKind.CLAUDE to AgentBackendFactory { LifecycleBackend { _, _ -> "exit 1" } }),
            projectPinStore = MemoryProjectPinStore(PinStoreState(incarnation = "inc-0123456789abcdef")),
            managedSessionRoot = File(dir, "managed"),
        )
        private val outbound = ConcurrentHashMap<String, Channel<ByteArray>>()
        fun channel(deviceId: String): Channel<ByteArray> = outbound.computeIfAbsent(deviceId) { Channel(Channel.UNLIMITED) }
        val sessions = DeviceSessions(core = core, identity = identity, store = File(dir, "devices.json"), bridges = bridges) { deviceId, payload ->
            channel(deviceId).trySend(payload)
        }

        suspend fun bind(deviceId: String, spec: BridgeSpec): E2ESession {
            val ticket = "t-$deviceId"
            assertTrue(bridges.recordIntent(ticket, spec, ttlMs = 600_000))
            sessions.onMintedTicket(ticket, headless = true)
            val keys = E2ECrypto.generateKeyPair()
            sessions.onDevicePaired(deviceId, b64.encodeToString(keys.publicRaw))
            val init = E2ESession.initiator(keys.privateRaw, keys.publicRaw, identity.e2ePubRaw, ticket.encodeToByteArray())
            sessions.onFrame(deviceId, Wire.payload(Wire.HANDSHAKE, init.ephPublic))
            val resp = withTimeout(3_000) { channel(deviceId).receive() }
            assertEquals(Wire.HANDSHAKE, Wire.payloadType(resp))
            return init.finish(Wire.payloadBody(resp))
        }

        suspend fun send(deviceId: String, session: E2ESession, body: Frame) {
            val env = Envelope("0", 0L, body = body)
            sessions.onFrame(deviceId, Wire.payload(Wire.TRANSPORT, session.seal(PocketJson.encodeToString(env).encodeToByteArray())))
        }

        suspend fun received(deviceId: String, session: E2ESession): List<Frame> {
            val out = ArrayList<Frame>()
            while (true) {
                val p = withTimeoutOrNull(250) { channel(deviceId).receive() } ?: return out
                if (Wire.payloadType(p) != Wire.TRANSPORT) continue
                val plain = session.open(Wire.payloadBody(p)) ?: continue
                out += PocketJson.decodeFromString<Envelope>(plain.decodeToString()).body
            }
        }

        suspend fun liveBridgeConvos(): Int = core.registry.liveCountOf(assertNotNull(bridges.guardOf("dev-bridge")).ownedConvoIds())
    }

    @Test
    fun retiring_a_guest_never_touches_a_bridge_with_the_same_label(): Unit = runBlocking {
        val h = LiveHarness()
        val label = "feishu-bot"
        val folder = File(root, "shared").apply { mkdirs() }

        // a bridge named "feishu-bot" with a live conversation (origin = its name)
        val bridge = h.bind("dev-bridge", BridgeSpec(label, listOf(folder.canonicalPath)))
        h.send("dev-bridge", bridge, OpenSession(folder.path))
        withTimeout(5_000) { while (h.bridges.guardOf("dev-bridge")?.ownedConvoIds().isNullOrEmpty()) delay(20) }
        assertEquals(1, h.liveBridgeConvos())
        h.received("dev-bridge", bridge)

        // an old guest whose share label is the same string, online right now
        val guest = h.bind(guestId, guestSpec(label, folder))
        h.send(guestId, guest, OpenSession(folder.path)) // its first frame: confirmed, and refused
        assertTrue(h.received(guestId, guest).isEmpty())
        assertTrue(h.bridges.isGuest(guestId))
        val bridgesJson = File(h.dir, "bridges.json").readBytes()

        assertTrue(h.sessions.retireLegacyGuests(), "an online guest is told")
        // the online guest gets exactly one frame: the access-ended notice
        val ended = assertIs<ShareEnded>(h.received(guestId, guest).single())
        assertEquals(ShareEnded.REASON_REVOKED, ended.reason)
        assertFalse(h.bridges.isGuest(guestId))

        // the bridge: credential, file and its live conversation untouched
        assertTrue(h.bridges.isBridge("dev-bridge"))
        assertTrue(bridgesJson.contentEquals(File(h.dir, "bridges.json").readBytes()), "bridges.json untouched")
        assertEquals(1, h.liveBridgeConvos(), "the same-named bridge's conversation is still live")
        // …and it keeps opening sessions over the link it already had
        h.send("dev-bridge", bridge, OpenSession(folder.path))
        withTimeout(5_000) { while (h.liveBridgeConvos() < 2) delay(20) }
        assertEquals(2, h.liveBridgeConvos())

        // the conversations really do carry the shared label: closing by it would have ended them
        assertEquals(2, h.core.registry.closeByOrigin(label))
    }
}
