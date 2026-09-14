package dev.ccpocket.daemon.server

import dev.ccpocket.daemon.DaemonPrefs
import dev.ccpocket.daemon.claude.AuthService
import dev.ccpocket.daemon.disk.DirectoryService
import dev.ccpocket.daemon.disk.FileExportService
import dev.ccpocket.daemon.disk.FileInboxService
import dev.ccpocket.daemon.disk.ManagedSessionStore
import dev.ccpocket.daemon.disk.canonicalManagedWorkdir
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.pins.DurablePinFiles
import dev.ccpocket.daemon.presets.PresetService
import dev.ccpocket.daemon.presets.PresetStore
import dev.ccpocket.daemon.session.ManagedSessionService
import dev.ccpocket.daemon.session.ScanCompleteness
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.daemon.session.SessionScan
import dev.ccpocket.daemon.shell.ShellService
import dev.ccpocket.daemon.transcribe.TranscribeService
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ClientCaps
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ImportSession
import dev.ccpocket.protocol.LanHello
import dev.ccpocket.protocol.ManagedSessionsState
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.RemoveManagedSession
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.e2e.E2ECrypto
import dev.ccpocket.protocol.e2e.E2ESession
import dev.ccpocket.protocol.e2e.Wire
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.ktor.websocket.Frame as WsFrame

/**
 * The DIRECT (LAN) transport's managed-session pushes (issue #360 security review M2), through real Noise-gated
 * sockets: an owner sibling receives a change, and a device revoked while its socket sits idle receives nothing —
 * the push is re-checked against the allow-list right before sealing and the socket is closed.
 */
class LanManagedSessionsTest {

    private class FakeWsSession(override val coroutineContext: CoroutineContext) : WebSocketSession {
        val inbound = Channel<WsFrame>(Channel.UNLIMITED)
        val sent = Channel<WsFrame>(Channel.UNLIMITED)
        override val incoming: ReceiveChannel<WsFrame> get() = inbound
        override val outgoing: SendChannel<WsFrame> get() = sent
        override val extensions: List<WebSocketExtension<*>> get() = emptyList()
        override var masking: Boolean = false
        override var maxFrameSize: Long = Long.MAX_VALUE
        override suspend fun send(frame: WsFrame) { sent.send(frame) }
        override suspend fun flush() {}
        @Deprecated("Use cancel() instead.", replaceWith = ReplaceWith("cancel()", "kotlinx.coroutines.cancel"))
        override fun terminate() { inbound.close() }
    }

    private class Conn(val ws: FakeWsSession, val job: Job, private val caught: AtomicReference<Throwable?>) {
        val failure: Throwable? get() = caught.get()
    }

    private class Fixture(val scope: CoroutineScope) {
        private val tmp = Files.createTempDirectory("ccp-lan-managed").toFile()
        val identity: Identity = Identity.loadOrCreate(tmp.resolve("identity.json"))
        val allowed = ConcurrentHashMap<String, ByteArray>()
        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val workdir: String = canonicalManagedWorkdir(File(tmp, "project").apply { mkdirs() }.path)!!
        val managed = ManagedSessionService(
            store = ManagedSessionStore(File(tmp, "managed"), DurablePinFiles(), ::canonicalManagedWorkdir, System::currentTimeMillis),
            scope = serviceScope,
            scan = { wd, a -> SessionScan(a, wd, listOf(SessionSummary("s1", "s1", "", 1, workdir, 1, agent = AgentKind.CLAUDE)), ScanCompleteness.COMPLETE) },
            validateWorkdir = ::canonicalManagedWorkdir,
            agents = setOf(AgentKind.CLAUDE),
            pendingFile = File(tmp, "managed/pending-registrations.json"),
        )
        val registry = SessionRegistry(scope, backends = emptyMap())
        val router = RequestRouter(
            registry = registry,
            dirs = DirectoryService(),
            transcribe = TranscribeService(scope) { null },
            inbox = FileInboxService { null },
            shell = ShellService(scope),
            exports = FileExportService(scope, { null }),
            scope = scope,
            auth = AuthService(scope, { emptyList() }, { 0 }),
            prefs = DaemonPrefs.load(tmp.resolve("prefs.json")),
            presets = PresetService(PresetStore.load(tmp.resolve("presets.json")), { emptyList() }, { 0 }),
            scheduler = dev.ccpocket.daemon.schedule.SchedulerService(
                dev.ccpocket.daemon.schedule.ScheduleStore.load(tmp.resolve("schedules.json")),
                executor = { null },
            ),
            archiveFile = tmp.resolve("session-archive.json"),
            managedSessions = managed,
        )

        fun device(id: String): E2ECrypto.KeyPair = E2ECrypto.generateKeyPair().also { allowed[id] = it.publicRaw }

        fun connect(): Conn {
            val ws = FakeWsSession(scope.coroutineContext)
            val gate = LanE2E(identity = identity, lanUrl = { null }, pairedDevices = { HashMap(allowed) })
            val caught = AtomicReference<Throwable?>(null)
            val job = scope.launch {
                try {
                    WsConnection(ws, router, registry, e2e = gate).serve()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    caught.set(t)
                }
            }
            return Conn(ws, job, caught)
        }

        fun close() {
            serviceScope.cancel()
            tmp.deleteRecursively()
        }
    }

    private fun envelopeText(body: Frame) = PocketJson.encodeToString(Envelope("c", 0, body = body))

    private suspend fun Fixture.handshake(conn: Conn, id: String, keys: E2ECrypto.KeyPair, declare: Boolean = true): E2ESession {
        val initiator = E2ESession.initiator(keys.privateRaw, keys.publicRaw, identity.e2ePubRaw, ByteArray(0))
        conn.ws.inbound.send(WsFrame.Text(envelopeText(LanHello(id))))
        conn.ws.inbound.send(WsFrame.Binary(true, Wire.payload(Wire.HANDSHAKE, initiator.ephPublic)))
        val reply = assertNotNull(withTimeout(5_000) { conn.ws.sent.receive() } as? WsFrame.Binary)
        assertEquals(Wire.HANDSHAKE, Wire.payloadType(reply.data))
        val session = initiator.finish(Wire.payloadBody(reply.data))
        val info = receive(conn, session) as DaemonInfo
        assertTrue(info.supportsManagedSessions)
        assertEquals(listOf("claude"), info.managedAgents)
        send(conn, session, ClientCaps(supportsManagedSessions = declare))
        return session
    }

    @Test
    fun a_registration_notice_reaches_only_declared_lan_connections(): Unit = runBlocking {
        // security review R2: the notice is a managed-session frame like any other — an undeclared owner socket gets none
        val f = Fixture(this)
        try {
            val keysA = f.device("devA"); val keysB = f.device("devB")
            val a = f.connect(); val sa = f.handshake(a, "devA", keysA)
            val b = f.connect(); val sb = f.handshake(b, "devB", keysB, declare = false)
            send(a, sa, dev.ccpocket.protocol.ListManagedSessions("warm", f.workdir, allAgents = true))
            assertEquals("warm", (receive(a, sa) as ManagedSessionsState).requestId) // A's declaration is in effect
            send(b, sb, ClientCaps(supportsManagedSessions = false))
            kotlinx.coroutines.delay(200)

            f.managed.store.fileFor(f.workdir).also { it.parentFile.mkdirs() }.writeText("{corrupt")
            f.managed.onNativeSession(
                dev.ccpocket.daemon.session.NativeSessionReport(
                    "convo", AgentKind.CLAUDE, f.workdir, "0b9a0009-aaaa-bbbb-cccc-000000000009", null, { true }, ownerCreated = true,
                ),
            )
            val notice = receive(a, sa) as dev.ccpocket.protocol.PocketError
            assertEquals(ManagedSessionService.REGISTER_FAILED, notice.code)
            assertNull(withTimeoutOrNull(500) { b.ws.sent.receive() }, "an undeclared connection receives no registration notice")
            a.ws.inbound.close(); b.ws.inbound.close()
        } finally {
            f.close()
        }
    }

    private suspend fun send(conn: Conn, session: E2ESession, body: Frame) {
        conn.ws.inbound.send(WsFrame.Binary(true, Wire.payload(Wire.TRANSPORT, session.seal(envelopeText(body).encodeToByteArray()))))
    }

    private suspend fun receive(conn: Conn, session: E2ESession): Frame {
        val frame = assertNotNull(withTimeout(5_000) { conn.ws.sent.receive() } as? WsFrame.Binary)
        val plain = assertNotNull(session.open(Wire.payloadBody(frame.data)))
        return PocketJson.decodeFromString<Envelope>(plain.decodeToString()).body
    }

    @Test
    fun an_idle_revoked_lan_device_gets_no_push_and_its_socket_is_closed(): Unit = runBlocking {
        val f = Fixture(this)
        try {
            val keysA = f.device("devA"); val keysB = f.device("devB")
            val a = f.connect(); val sa = f.handshake(a, "devA", keysA)
            val b = f.connect(); val sb = f.handshake(b, "devB", keysB)

            // the requester receives its reply AND the push, in either order
            suspend fun replyTo(requestId: String): ManagedSessionsState = withTimeout(5_000) {
                var found: ManagedSessionsState? = null
                while (found == null) found = (receive(b, sb) as? ManagedSessionsState)?.takeIf { it.requestId == requestId }
                found
            }
            send(b, sb, ImportSession("i1", f.workdir, AgentKind.CLAUDE, "s1"))
            assertEquals("i1", replyTo("i1").requestId)
            val push = receive(a, sa) as ManagedSessionsState
            assertNull(push.requestId, "positive control: an owner sibling receives the push")

            f.allowed.remove("devA") // revoked elsewhere; this socket stays silent
            send(b, sb, RemoveManagedSession("r1", f.workdir, AgentKind.CLAUDE, "s1"))
            assertEquals("r1", replyTo("r1").requestId)
            assertNull(withTimeoutOrNull(500) { a.ws.sent.receive() }, "a revoked device must not learn the change")
            withTimeout(5_000) { a.job.join() }
            assertTrue(a.failure?.message.orEmpty().contains("revoked"), "the revoked socket is closed: ${a.failure}")
            b.ws.inbound.close()
        } finally {
            f.close()
        }
    }
}
