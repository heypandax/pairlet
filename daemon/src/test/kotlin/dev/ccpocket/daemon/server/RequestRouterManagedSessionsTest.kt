package dev.ccpocket.daemon.server

import dev.ccpocket.daemon.DaemonPrefs
import dev.ccpocket.daemon.bridge.BridgeCaps
import dev.ccpocket.daemon.bridge.GuestCaps
import dev.ccpocket.daemon.bridge.GuestScope
import dev.ccpocket.daemon.claude.AuthService
import dev.ccpocket.daemon.conversation.KeyedSink
import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.disk.DirectoryService
import dev.ccpocket.daemon.disk.FileExportService
import dev.ccpocket.daemon.disk.FileInboxService
import dev.ccpocket.daemon.disk.ManagedSessionStore
import dev.ccpocket.daemon.disk.canonicalManagedWorkdir
import dev.ccpocket.daemon.handoff.CollaboratorCaps
import dev.ccpocket.daemon.handoff.CollaboratorScope
import dev.ccpocket.daemon.pins.DurablePinFiles
import dev.ccpocket.daemon.presets.PresetService
import dev.ccpocket.daemon.presets.PresetStore
import dev.ccpocket.daemon.session.ManagedSessionService
import dev.ccpocket.daemon.session.ScanCompleteness
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.daemon.session.SessionScan
import dev.ccpocket.daemon.shell.ShellService
import dev.ccpocket.daemon.transcribe.TranscribeService
import dev.ccpocket.protocol.AccessTier
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ClientCaps
import dev.ccpocket.protocol.CollaboratorPurpose
import dev.ccpocket.protocol.DiscoverSessions
import dev.ccpocket.protocol.DiscoveredSession
import dev.ccpocket.protocol.DiscoveredSessions
import dev.ccpocket.protocol.EnableManagedSessions
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ImportSession
import dev.ccpocket.protocol.ListManagedSessions
import dev.ccpocket.protocol.ListSessions
import dev.ccpocket.protocol.ManagedAgentStatus
import dev.ccpocket.protocol.ManagedSessionEntry
import dev.ccpocket.protocol.ManagedSessionErrors
import dev.ccpocket.protocol.ManagedSessionsState
import dev.ccpocket.protocol.RemoveManagedSession
import dev.ccpocket.protocol.Sessions
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.ToDaemon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The router's managed-session door (issue #360): capability negotiation in both directions, per-connection egress
 * gating, owner-only admission (the three-way test) that refuses before anything is read, requester-excluded push
 * fan-out filtered per connection, and the old/new client × old/new daemon matrix.
 */
class RequestRouterManagedSessionsTest {
    private val tmp = Files.createTempDirectory("ccp-router-managed").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val project = Files.createDirectories(tmp.toPath().resolve("proj")).toString()
    private val workdir = canonicalManagedWorkdir(project)!!
    private var scans = 0
    private val rows = ArrayList<SessionSummary>()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        tmp.deleteRecursively()
    }

    private fun service() = ManagedSessionService(
        store = ManagedSessionStore(File(tmp, "managed"), DurablePinFiles(), ::canonicalManagedWorkdir, System::currentTimeMillis),
        scope = scope,
        scan = { wd, a -> scans++; SessionScan(a, wd, rows.filter { it.agent == a }, ScanCompleteness.COMPLETE) },
        validateWorkdir = ::canonicalManagedWorkdir,
        agents = setOf(AgentKind.CLAUDE, AgentKind.CODEX),
        pendingFile = File(tmp, "managed/pending-registrations.json"),
    )

    private fun router(managed: ManagedSessionService?) = RequestRouter(
        registry = SessionRegistry(scope, backends = emptyMap()),
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

    /** A connection's outbound path as both transports build it: the per-connection caps gate runs at emission. */
    private class Conn(val caps: RequestRouter.ClientCapsHolder?) : OutboundSink {
        val frames = mutableListOf<Frame>()
        override suspend fun emit(frame: Frame) {
            if (!RequestRouter.allowedForCaps(frame, caps)) return
            synchronized(frames) { frames += frame }
        }
        fun managed() = synchronized(frames) { frames.filter { it is ManagedSessionsState || it is DiscoveredSessions } }
        fun <T> last(cls: Class<T>): T? = synchronized(frames) { frames.filterIsInstance(cls).lastOrNull() }
    }

    private fun declared() = RequestRouter.ClientCapsHolder().apply { supportsManagedSessions = true }

    private suspend fun awaitFrames(conn: Conn, n: Int) = withTimeout(5_000) { while (conn.managed().size < n) delay(10) }

    private fun row(id: String, agent: AgentKind = AgentKind.CLAUDE) = SessionSummary(id, id, "", 1, workdir, 1, agent = agent)

    private val requests: List<ToDaemon> get() = listOf(
        ListManagedSessions("r1", workdir, allAgents = true),
        EnableManagedSessions("r2", workdir, AgentKind.CLAUDE),
        DiscoverSessions("r3", workdir, AgentKind.CLAUDE),
        ImportSession("r4", workdir, AgentKind.CLAUDE, "s1"),
        RemoveManagedSession("r5", workdir, AgentKind.CLAUDE, "s1"),
    )

    @Test
    fun the_client_caps_declaration_sets_the_bit_and_egress_gates_both_frames_per_connection() = runBlocking {
        val r = router(service())
        val caps = RequestRouter.ClientCapsHolder()
        r.handle(ClientCaps(supportsManagedSessions = false), Conn(caps), caps = caps)
        assertFalse(caps.supportsManagedSessions)
        r.handle(ClientCaps(supportsManagedSessions = true), Conn(caps), caps = caps)
        assertTrue(caps.supportsManagedSessions)

        val state = ManagedSessionsState(workdir = workdir)
        val page = DiscoveredSessions("r", workdir, AgentKind.CLAUDE)
        for (frame in listOf(state, page)) {
            assertFalse(RequestRouter.allowedForCaps(frame, null), "no holder = fail closed")
            assertFalse(RequestRouter.allowedForCaps(frame, RequestRouter.ClientCapsHolder()), "undeclared = fail closed")
            assertTrue(RequestRouter.allowedForCaps(frame, declared()))
        }
        assertTrue(RequestRouter.allowedForCaps(Sessions(workdir, emptyList()), null), "the legacy list is ungated")
    }

    @Test
    fun an_undeclared_connection_gets_silence_and_nothing_is_read() = runBlocking {
        val r = router(service())
        for (caps in listOf(null, RequestRouter.ClientCapsHolder())) {
            val conn = Conn(caps)
            for (req in requests) r.handle(req, conn, caps = caps)
            delay(300)
            assertTrue(conn.frames.isEmpty(), "caps=$caps got ${conn.frames}")
        }
        assertEquals(0, scans)
        assertFalse(File(tmp, "managed").exists(), "no store was touched")
    }

    @Test
    fun guest_bridge_and_collaborator_are_forbidden_before_anything_is_read() = runBlocking {
        val r = router(service())
        val guest = GuestScope(roots = listOf(project), ownedSessions = emptySet(), label = "guest", expiresAt = null, tier = AccessTier.REVIEW)
        val callers: List<Pair<String, suspend (Frame, Conn) -> Unit>> = listOf(
            "bridge" to { f, c -> r.handle(f, c, origin = "feishu-bot", caps = c.caps) },
            "guest" to { f, c -> r.handle(f, c, origin = "guest", guestScope = guest, caps = c.caps) },
            "guest-without-origin" to { f, c -> r.handle(f, c, guestScope = guest, caps = c.caps) },
            "collaborator" to { f, c -> r.handle(f, c, caps = c.caps, deviceId = "devCollab", collabScope = CollaboratorScope("devCollab")) },
        )
        for ((who, call) in callers) {
            for (req in requests) {
                val conn = Conn(declared())
                call(req, conn)
                val reply = conn.managed().single()
                val error = (reply as? ManagedSessionsState)?.error ?: (reply as DiscoveredSessions).error
                assertEquals(ManagedSessionErrors.FORBIDDEN, error, "$who ${req::class.simpleName}")
                if (reply is ManagedSessionsState) {
                    assertNull(reply.items); assertNull(reply.canonicalWorkdir); assertNull(reply.agents)
                } else assertTrue((reply as DiscoveredSessions).items.isEmpty())
            }
        }
        delay(200)
        assertEquals(0, scans, "no scan ran for a restricted credential")
        assertFalse(File(tmp, "managed").exists())
    }

    @Test
    fun restricted_ingress_whitelists_deny_every_managed_request_and_egress_denies_both_replies() {
        for (req in requests) {
            assertFalse(GuestCaps.ingressAllowed(req), "guest ${req::class.simpleName}")
            assertFalse(BridgeCaps.ingressAllowed(req), "bridge ${req::class.simpleName}")
            for (purpose in CollaboratorPurpose.entries) assertFalse(CollaboratorCaps.ingressAllowed(req, purpose), "collaborator/$purpose")
        }
        for (reply in listOf(ManagedSessionsState(workdir = workdir), DiscoveredSessions("r", workdir, AgentKind.CLAUDE))) {
            assertFalse(GuestCaps.egressAllowed(reply))
            assertFalse(BridgeCaps.egressAllowed(reply))
            for (purpose in CollaboratorPurpose.entries) assertFalse(CollaboratorCaps.egressAllowed(reply, purpose))
        }
    }

    @Test
    fun an_owner_is_served_and_invalid_or_uncorrelatable_requests_are_handled() = runBlocking {
        val r = router(service())
        rows += row("s1")
        val conn = Conn(declared())
        r.handle(EnableManagedSessions("r1", workdir, AgentKind.CLAUDE), conn, caps = conn.caps)
        awaitFrames(conn, 1)
        val enabled = conn.last(ManagedSessionsState::class.java)!!
        assertEquals("r1", enabled.requestId)
        assertEquals(listOf("s1"), enabled.items!!.map { it.sessionId })

        r.handle(EnableManagedSessions("r2", workdir, null), conn, caps = conn.caps)
        awaitFrames(conn, 2)
        assertEquals(ManagedSessionErrors.INVALID_REQUEST, conn.last(ManagedSessionsState::class.java)!!.error, "a missing agent is never Claude")

        r.handle(ListManagedSessions("bad request id", workdir, allAgents = true), conn, caps = conn.caps)
        delay(300)
        assertEquals(2, conn.managed().size, "a reply that could not be correlated would read as a push: dropped")

        val unwired = router(null)
        assertTrue(unwired.managedSessionAgentWires().isEmpty(), "an unwired daemon advertises nothing")
        val c2 = Conn(declared())
        unwired.handle(ListManagedSessions("r3", workdir, allAgents = true), c2, caps = c2.caps)
        assertEquals(ManagedSessionErrors.UNSUPPORTED, c2.last(ManagedSessionsState::class.java)!!.error)
        assertEquals(listOf("claude", "codex"), r.managedSessionAgentWires())
    }

    @Test
    fun a_change_is_pushed_to_other_capable_owner_connections_but_not_to_the_requester_or_undeclared_ones() = runBlocking {
        val svc = service()
        val r = router(svc)
        rows += row("s1")
        val requester = Conn(declared())
        val sibling = Conn(declared())
        val legacySibling = Conn(RequestRouter.ClientCapsHolder())
        // each transport's delivery closure: gate on that connection's CURRENT declaration, filter its vocabulary
        fun deliverTo(conn: Conn): suspend (ManagedSessionsState) -> Unit = { state ->
            if (conn.caps?.supportsManagedSessions == true) conn.emit(ManagedSessionService.filterAgents(state) { a -> RequestRouter.capsAllow(conn.caps, a) })
        }
        svc.attach("dev:requester", deliver = deliverTo(requester))
        svc.attach("dev:sibling", deliver = deliverTo(sibling))
        svc.attach("dev:legacy", deliver = deliverTo(legacySibling))

        r.handle(ImportSession("r1", workdir, AgentKind.CLAUDE, "s1"), KeyedSink("dev:requester", requester), caps = requester.caps)
        withTimeout(5_000) { while (sibling.managed().isEmpty()) delay(10) }
        awaitFrames(requester, 2)
        delay(300)
        val push = sibling.managed().single() as ManagedSessionsState
        assertNull(push.requestId, "a push carries no requestId")
        assertTrue(push.allAgents)
        assertEquals(workdir, push.canonicalWorkdir)
        assertEquals(listOf("s1"), push.items!!.map { it.sessionId })
        val mine = requester.managed().map { it as ManagedSessionsState }
        assertEquals(listOf("r1"), mine.mapNotNull { it.requestId }, "the requester gets its reply")
        assertEquals(1, mine.count { it.requestId == null }, "…and the push too, so a timed-out requester still converges")
        assertTrue(legacySibling.frames.isEmpty(), "an undeclared sibling on the same daemon never sees a managed frame")

        svc.detach("dev:sibling")
        r.handle(RemoveManagedSession("r2", workdir, AgentKind.CLAUDE, "s1"), KeyedSink("dev:requester", requester), caps = requester.caps)
        awaitFrames(requester, 4)
        delay(300)
        assertEquals(1, sibling.managed().size, "a detached connection receives nothing more")
        assertTrue(legacySibling.frames.isEmpty())
    }

    @Test
    fun old_client_on_new_daemon_keeps_the_exact_legacy_list() = runBlocking {
        val r = router(service())
        val old = Conn(null)
        r.handle(ListSessions(project), old, caps = null)
        val sessions = old.frames.filterIsInstance<Sessions>().single()
        assertEquals(project.let { canonicalManagedWorkdir(it) }?.let { project }, sessions.workdir)
        assertTrue(old.managed().isEmpty())
    }

    @Test
    fun rows_are_cut_to_the_connection_vocabulary() {
        val state = ManagedSessionsState(
            workdir = workdir,
            agents = listOf(ManagedAgentStatus(AgentKind.CLAUDE), ManagedAgentStatus(AgentKind.CODEX), ManagedAgentStatus(null)),
            items = listOf(ManagedSessionEntry("a", AgentKind.CLAUDE), ManagedSessionEntry("b", AgentKind.CODEX), ManagedSessionEntry("c", null)),
            entry = ManagedSessionEntry("b", AgentKind.CODEX),
        )
        val onlyClaude = ManagedSessionService.filterAgents(state) { it == AgentKind.CLAUDE }
        assertEquals(listOf(AgentKind.CLAUDE), onlyClaude.agents!!.map { it.agent })
        assertEquals(listOf("a"), onlyClaude.items!!.map { it.sessionId })
        assertNull(onlyClaude.entry)
        val page = DiscoveredSessions("r", workdir, AgentKind.CODEX, listOf(DiscoveredSession("x", AgentKind.CODEX), DiscoveredSession("y", null)))
        assertTrue(ManagedSessionService.filterAgents(page) { it == AgentKind.CLAUDE }.items.isEmpty())
        assertNotNull(ManagedSessionService.filterAgents(state) { true }.entry)
    }
}
