package dev.ccpocket.daemon.relay

import dev.ccpocket.daemon.DaemonCore
import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentBackendFactory
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.bridge.BridgeRegistry
import dev.ccpocket.daemon.bridge.BridgeSpec
import dev.ccpocket.daemon.bridge.CredentialKind
import dev.ccpocket.daemon.disk.canonicalManagedWorkdir
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.pins.MemoryProjectPinStore
import dev.ccpocket.daemon.pins.PinStoreState
import dev.ccpocket.daemon.session.ScanCompleteness
import dev.ccpocket.daemon.session.SessionScan
import dev.ccpocket.protocol.AccessTier
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ClientCaps
import dev.ccpocket.protocol.CollaboratorPurpose
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.DiscoverSessions
import dev.ccpocket.protocol.DiscoveredSessions
import dev.ccpocket.protocol.EnableManagedSessions
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.ImportSession
import dev.ccpocket.protocol.ListManagedSessions
import dev.ccpocket.protocol.ManagedSessionsState
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.RemoveManagedSession
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.e2e.E2ECrypto
import dev.ccpocket.protocol.e2e.E2ESession
import dev.ccpocket.protocol.e2e.Wire
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The RELAY transport's managed-session plane (issue #360 security review), end to end through real Noise sessions:
 * every restricted credential class — bridge, guest, collaborator of each purpose, and a provisional key — is denied
 * all five requests without a scan, a store touch or a push slot; an owner device reclassified as restricted stops
 * receiving pushes; and a device revoked after a push was projected gets nothing sealed.
 */
class DeviceSessionsManagedSessionsTest {

    private val dir = createTempDirectory("ccp-ds-managed").toFile()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    private class CountingBackend(private val rows: () -> List<SessionSummary>) : AgentBackend {
        val scans = AtomicInteger()
        override val kind = AgentKind.CLAUDE
        override fun listSessions(workdir: String) = rows()
        override fun scanSessions(workdir: String, agent: AgentKind): SessionScan {
            scans.incrementAndGet()
            return SessionScan(agent, workdir, rows(), ScanCompleteness.COMPLETE)
        }
        override fun processBuilder(spec: AgentSpec) = throw UnsupportedOperationException()
        override suspend fun attach(io: AgentIo, spec: AgentSpec) = throw UnsupportedOperationException()
        override suspend fun parse(line: String): Nothing = throw UnsupportedOperationException()
        override suspend fun sendPrompt(text: String, images: List<ImageData>) = throw UnsupportedOperationException()
        override suspend fun interrupt() = throw UnsupportedOperationException()
        override suspend fun respondPermission(
            askId: String, allow: Boolean, remember: Boolean,
            originalInput: JsonObject?, updatedInput: String?, denyMessage: String?,
        ) = throw UnsupportedOperationException()
        override fun applySettings(mode: PermissionMode?, model: String?, effort: String?) = false
        override suspend fun onProcessEnded(sessionId: String?) {}
        override fun transcriptDir(workdir: String): Path = Path.of(workdir)
        override fun replayHistory(workdir: String, sessionId: String) = emptyList<HistoryMessage>()
        override fun resumeContextTokens(workdir: String, sessionId: String): Long? = null
    }

    private inner class Harness {
        val project: String = File(dir, "project").apply { mkdirs() }.canonicalPath
        val workdir: String = canonicalManagedWorkdir(project)!!
        val managedRoot = File(dir, "managed")
        val backend = CountingBackend { listOf(SessionSummary("s1", "s1", "", 1, workdir, 1, agent = AgentKind.CLAUDE)) }
        val identity = Identity.loadOrCreate(File(dir, "identity.json"))
        val bridges = BridgeRegistry(File(dir, "bridges.json"))
        val core = DaemonCore(
            mapOf(AgentKind.CLAUDE to AgentBackendFactory { backend }),
            projectPinStore = MemoryProjectPinStore(PinStoreState(incarnation = "inc-0123456789abcdef")),
            managedSessionRoot = managedRoot,
        )
        private val outbound = ConcurrentHashMap<String, Channel<ByteArray>>()
        fun channel(deviceId: String): Channel<ByteArray> = outbound.computeIfAbsent(deviceId) { Channel(Channel.UNLIMITED) }
        val sessions = DeviceSessions(core = core, identity = identity, store = File(dir, "devices.json"), bridges = bridges) { deviceId, payload ->
            channel(deviceId).trySend(payload)
        }
        val service get() = core.managedSessions

        fun requests() = listOf(
            ListManagedSessions("r1", workdir, allAgents = true),
            EnableManagedSessions("r2", workdir, AgentKind.CLAUDE),
            DiscoverSessions("r3", workdir, AgentKind.CLAUDE),
            ImportSession("r4", workdir, AgentKind.CLAUDE, "s1"),
            RemoveManagedSession("r5", workdir, AgentKind.CLAUDE, "s1"),
        )
    }

    private fun open(session: E2ESession, framed: ByteArray): Frame? {
        if (Wire.payloadType(framed) != Wire.TRANSPORT) return null
        val plain = session.open(Wire.payloadBody(framed)) ?: return null
        return PocketJson.decodeFromString<Envelope>(plain.decodeToString()).body
    }

    private suspend fun handshake(h: Harness, deviceId: String, keys: E2ECrypto.KeyPair, psk: String): E2ESession {
        val init = E2ESession.initiator(keys.privateRaw, keys.publicRaw, h.identity.e2ePubRaw, psk = psk.encodeToByteArray())
        h.sessions.onFrame(deviceId, Wire.payload(Wire.HANDSHAKE, init.ephPublic))
        val resp = withTimeout(5_000) { h.channel(deviceId).receive() }
        assertEquals(Wire.HANDSHAKE, Wire.payloadType(resp))
        return init.finish(Wire.payloadBody(resp))
    }

    private suspend fun owner(h: Harness, deviceId: String, declare: Boolean = true): E2ESession {
        h.sessions.onMintedTicket("ticket-$deviceId")
        val keys = E2ECrypto.generateKeyPair()
        h.sessions.onDevicePaired(deviceId, b64.encodeToString(keys.publicRaw))
        val session = handshake(h, deviceId, keys, "ticket-$deviceId")
        val info = assertNotNull(open(session, withTimeout(5_000) { h.channel(deviceId).receive() }) as? DaemonInfo)
        assertTrue(info.supportsManagedSessions)
        assertEquals(listOf("claude"), info.managedAgents, "only agents this daemon really manages")
        send(h, deviceId, session, ClientCaps(supportsManagedSessions = declare)) // an owner frame: attaches its push slot
        return session
    }

    @Test
    fun a_registration_notice_reaches_only_declared_relay_owner_devices() = runBlocking {
        // security review R2: an owner device whose connection never declared the capability gets no notice
        val h = Harness()
        val a = owner(h, "devA")
        send(h, "devA", a, ListManagedSessions("warm", h.workdir, allAgents = true))
        assertNull(awaitReply(h, "devA", a, "warm").error)
        val b = owner(h, "devB", declare = false)
        delay(200)
        assertEquals(2, h.service.subscriberCount(), "precondition: the undeclared owner device is attached too")

        h.managedRoot.mkdirs()
        h.service.store.fileFor(h.workdir).writeText("{corrupt")
        h.service.onNativeSession(
            dev.ccpocket.daemon.session.NativeSessionReport(
                "convo", AgentKind.CLAUDE, h.workdir, "0b9a0008-aaaa-bbbb-cccc-000000000008", null, { true }, ownerCreated = true,
            ),
        )
        val notice = awaitFrame(h, "devA", a) as PocketError
        assertEquals(dev.ccpocket.daemon.session.ManagedSessionService.REGISTER_FAILED, notice.code)
        assertTrue(drain(h, "devB", b).none { it is PocketError }, "the undeclared device gets no registration notice")
    }

    /** A restricted credential of [kind] (or a provisional key when [provisional]), handshaken and ready to send. */
    private suspend fun restricted(h: Harness, deviceId: String, spec: BridgeSpec, provisional: Boolean = false): E2ESession {
        val ticket = "t-$deviceId"
        h.bridges.recordIntent(ticket, spec, ttlMs = if (provisional) 60 else 120_000)
        h.sessions.onMintedTicket(ticket, headless = true)
        val keys = E2ECrypto.generateKeyPair()
        h.sessions.onDevicePaired(deviceId, b64.encodeToString(keys.publicRaw))
        val session = handshake(h, deviceId, keys, ticket)
        if (provisional) delay(150) // the intent lapses before the first transport frame: never confirmed
        return session
    }

    private suspend fun send(h: Harness, deviceId: String, session: E2ESession, body: Frame) {
        val env = Envelope("0", 0L, body = body)
        h.sessions.onFrame(deviceId, Wire.payload(Wire.TRANSPORT, session.seal(PocketJson.encodeToString(env).encodeToByteArray())))
    }

    private suspend fun drain(h: Harness, deviceId: String, session: E2ESession, ms: Long = 400): List<Frame> {
        val out = ArrayList<Frame>()
        while (true) {
            val next = withTimeoutOrNull(ms) { h.channel(deviceId).receive() } ?: return out
            open(session, next)?.let { out += it }
        }
    }

    private suspend fun awaitFrame(h: Harness, deviceId: String, session: E2ESession): Frame =
        assertNotNull(open(session, withTimeout(5_000) { h.channel(deviceId).receive() }))

    /** The reply to [requestId]; a requester also receives the change's push, in either order, which is skipped. */
    private suspend fun awaitReply(h: Harness, deviceId: String, session: E2ESession, requestId: String): ManagedSessionsState =
        withTimeout(5_000) {
            while (true) {
                val f = open(session, h.channel(deviceId).receive())
                if (f is ManagedSessionsState && f.requestId == requestId) return@withTimeout f
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }

    @Test
    fun every_restricted_credential_class_is_denied_every_request_without_a_scan_store_or_push_slot() = runBlocking {
        val h = Harness()
        val root = listOf(h.project)
        val credentials = buildList {
            add("bridge" to BridgeSpec("feishu-bot", root))
            add("guest" to BridgeSpec("guest", root, kind = CredentialKind.GUEST, expiresAt = System.currentTimeMillis() + 3_600_000, tier = AccessTier.REVIEW))
            for (purpose in CollaboratorPurpose.entries) add("collab-${purpose.name.lowercase()}" to BridgeSpec("peer", root, kind = CredentialKind.COLLABORATOR, purpose = purpose))
        }
        val slotsBefore = h.service.subscriberCount()
        for ((i, pair) in (credentials.map { it to false } + listOf(("provisional" to BridgeSpec("late", root)) to true)).withIndex()) {
            val (cred, provisional) = pair
            val (label, spec) = cred
            val id = "dev-$label-$i"
            val session = restricted(h, id, spec, provisional)
            send(h, id, session, ClientCaps(supportsManagedSessions = true))
            for (req in h.requests()) send(h, id, session, req)
            val frames = drain(h, id, session)
            assertTrue(frames.none { it is ManagedSessionsState || it is DiscoveredSessions }, "$label received a managed frame: $frames")
            assertTrue(frames.all { it is PocketError && it.code.contains("forbidden") }, "$label: only refusals, got $frames")
            if (provisional) assertTrue(frames.isEmpty(), "a provisional key is dropped silently")
        }
        delay(200)
        assertEquals(0, h.backend.scans.get(), "no restricted request scanned anything")
        assertEquals(slotsBefore, h.service.subscriberCount(), "no restricted credential got a push slot")
        assertFalse(h.managedRoot.exists(), "no restricted request touched the store")
    }

    @Test
    fun an_owner_reclassified_as_restricted_stops_receiving_pushes() = runBlocking {
        val h = Harness()
        val a = owner(h, "devA")
        val b = owner(h, "devB")
        send(h, "devA", a, ListManagedSessions("warm", h.workdir, allAgents = true)) // attaches devA's push slot
        assertNull((awaitFrame(h, "devA", a) as ManagedSessionsState).error)
        assertEquals(2, h.service.subscriberCount())

        send(h, "devB", b, ImportSession("i1", h.workdir, AgentKind.CLAUDE, "s1"))
        assertEquals("i1", awaitReply(h, "devB", b, "i1").requestId)
        val push = awaitFrame(h, "devA", a) as ManagedSessionsState
        assertNull(push.requestId, "positive control: an owner sibling gets the push")

        h.bridges.holdProvisional("devA", E2ECrypto.generateKeyPair().publicRaw) // now a bridge candidate
        send(h, "devB", b, RemoveManagedSession("r1", h.workdir, AgentKind.CLAUDE, "s1"))
        assertEquals("r1", awaitReply(h, "devB", b, "r1").requestId)
        assertTrue(drain(h, "devA", a).none { it is ManagedSessionsState || it is DiscoveredSessions }, "a reclassified device gets no push")
    }

    @Test
    fun a_device_revoked_after_a_push_was_projected_gets_nothing_sealed() = runBlocking {
        val h = Harness()
        val a = owner(h, "devA")
        val b = owner(h, "devB")
        send(h, "devA", a, ListManagedSessions("warm", h.workdir, allAgents = true))
        awaitFrame(h, "devA", a)
        h.service.beforePushDelivery = { h.sessions.onDeviceRevoked("devA") }
        send(h, "devB", b, ImportSession("i1", h.workdir, AgentKind.CLAUDE, "s1"))
        assertEquals("i1", awaitReply(h, "devB", b, "i1").requestId)
        delay(300)
        assertTrue(drain(h, "devA", a).isEmpty(), "the captured push to a revoked device is dropped at sealing")
        assertEquals(1, h.service.subscriberCount(), "the revoke released its slot")
    }
}
