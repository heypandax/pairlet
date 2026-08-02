package dev.ccpocket.daemon.handoff

import dev.ccpocket.daemon.DaemonPrefs
import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentBackendFactory
import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.agent.PermissionBridge
import dev.ccpocket.daemon.bridge.PathScope
import dev.ccpocket.daemon.claude.AuthService
import dev.ccpocket.daemon.conversation.KeyedSink
import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.disk.DirectoryService
import dev.ccpocket.daemon.disk.FileExportService
import dev.ccpocket.daemon.disk.FileInboxService
import dev.ccpocket.daemon.presets.PresetService
import dev.ccpocket.daemon.presets.PresetStore
import dev.ccpocket.daemon.server.RequestRouter
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.daemon.shell.ShellService
import dev.ccpocket.daemon.transcribe.TranscribeService
import dev.ccpocket.protocol.AcceptHandoff
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.CreateHandoff
import dev.ccpocket.protocol.Decision
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HandoffAccess
import dev.ccpocket.protocol.HandoffBrief
import dev.ccpocket.protocol.HandoffCreated
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PermissionVerdict
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.SessionLive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The crypto-review MUST-FIX pair for REVIEW_READ_ONLY (SESSION-HANDOFF.md §8.3):
 *
 *  A. HOT→COLD REBUILD — a collaborator's vetted OpenSession that lands on the owner's still-live
 *     Conversation must not plain-reattach (that silently dropped the Grant's pathScope, access
 *     ceiling and clamped mode): the live convo is closed and the open falls through to the cold
 *     path, which rebuilds the PermissionBridge with the Grant's walls. The owner keeps spectating
 *     by re-opening (ordinary reattach onto the NEW convo).
 *
 *  B. WRITE-TOOL HARD WALL — under a review/read-only Grant the recipient IS the lease controller
 *     and answers its own asks, so write tools are denied BEFORE any ask exists; no verdict (its
 *     own included) can ever release them.
 *
 * Follows the CollaboratorRouterWiringTest fixture: stub backend, lazy opens, no processes.
 */
class CollaboratorGrantEnforcementTest {

    private class StubBackend : AgentBackend {
        override val kind = AgentKind.CLAUDE
        override fun listSessions(workdir: String) = emptyList<dev.ccpocket.protocol.SessionSummary>()
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
        override fun transcriptDir(workdir: String): Path = throw UnsupportedOperationException()
        override fun replayHistory(workdir: String, sessionId: String) = emptyList<HistoryMessage>()
        override fun resumeContextTokens(workdir: String, sessionId: String): Long? = null
    }

    private class Fixture(scope: CoroutineScope) {
        var now: Long = System.currentTimeMillis()
        private val tmp = Files.createTempDirectory("ccp-grant-wiring").toFile()
        val workdir: String = Files.createTempDirectory("ccp-grant-wd").toString()
        val registry = SessionRegistry(scope, backends = mapOf(AgentKind.CLAUDE to AgentBackendFactory { StubBackend() }))
        val handoffs = HandoffService(HandoffRegistry(HandoffStore.load(tmp.resolve("handoffs.json")), clock = { now }))
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
        )

        init {
            registry.handoffs = handoffs
        }

        /** A sink with the relay's STABLE per-device identity (`dev:<deviceId>`): the conversation
         *  fan-out, the handoff fan-out and the §3.3 spectator migration all key on it, so a device that
         *  sends many frames stays ONE attached client (and a re-opened view replaces the old one)
         *  instead of stacking a fresh view per frame the way a bare lambda sink did. */
        fun sinkFor(deviceId: String, into: MutableList<Frame>): OutboundSink =
            KeyedSink("dev:$deviceId", OutboundSink { into += it })

        /** Route [frame] as an OWNER device. */
        suspend fun route(frame: Frame, deviceId: String, into: MutableList<Frame>) =
            router.handle(frame, sinkFor(deviceId, into), deviceId = deviceId)

        /** Route [frame] the way DeviceSessions does for a COLLABORATOR credential: CollaboratorCaps
         *  type gate + CollaboratorGuard vet, then the router with the vetted collabScope (pathScope
         *  AND the grant's access — the §8.3 wall inputs). */
        suspend fun routeAsCollaborator(frame: Frame, deviceId: String, into: MutableList<Frame>) {
            if (!CollaboratorCaps.ingressAllowed(frame)) {
                into += PocketError("collaborator_forbidden", "not permitted for a collaborator link")
                return
            }
            val guard = handoffs.collaboratorGuard(deviceId)
            when (val v = guard.vet(frame, now)) {
                is CollaboratorGuard.Verdict.Deny -> into += PocketError(v.code, v.message)
                is CollaboratorGuard.Verdict.Allow -> router.handle(
                    v.frame, sinkFor(deviceId, into), deviceId = deviceId,
                    collabScope = CollaboratorScope(deviceId, v.pathScope, access = v.access ?: HandoffAccess.REVIEW_READ_ONLY),
                ) { convoId -> guard.noteOpened(convoId) }
            }
        }

        /** Wait for the open's SessionLive. Reads a SNAPSHOT each poll ([frames] is copy-on-write), so
         *  the query never iterates a list the daemon's coroutines are appending to (§8: the
         *  ConcurrentModificationException came from exactly that, not from a missing sleep). */
        suspend fun awaitLive(into: List<Frame>): String = withTimeout(5_000) {
            var live: SessionLive? = null
            while (live == null) {
                live = into.filterIsInstance<SessionLive>().firstOrNull()
                if (live == null) delay(20)
            }
            live.convoId
        }

        suspend fun openSession(deviceId: String, into: MutableList<Frame>): String {
            route(OpenSession(workdir, resumeId = SESSION_ID), deviceId, into)
            return awaitLive(into)
        }

        suspend fun createBound(deviceId: String, recipient: String?, into: MutableList<Frame>): dev.ccpocket.protocol.SessionHandoff {
            route(
                CreateHandoff(
                    workdir, SESSION_ID, HandoffBrief(request = "review the ACK path"),
                    recipientDeviceId = recipient,
                ),
                deviceId, into,
            )
            val created = into.filterIsInstance<HandoffCreated>().last()
            assertTrue(created.ok, "create must succeed: ${created.error}")
            return assertNotNull(created.handoff)
        }

        /** The pathScope the guard's vetOpen derives for a grant on [workdir] with no allowedRoots. */
        fun grantScope(): List<String> = listOfNotNull(PathScope.canonical(workdir))

        companion object { const val SESSION_ID = "aaaaaaaa-bbbb-cccc-dddd-ffffffffffff" }
    }

    /**
     * One client's received frames. COPY-ON-WRITE, deliberately not `Collections.synchronizedList`:
     * every assertion and await ITERATES these lists (`none` / `filterIsInstance`) while daemon
     * coroutines are still appending to them, and a synchronized list only locks each single call —
     * the iteration itself raced and threw ConcurrentModificationException. A COW list iterates an
     * immutable snapshot, so the race is gone by construction (no lock, and above all no sleep).
     */
    private fun frames(): MutableList<Frame> = CopyOnWriteArrayList()

    // ---- A. the hot→cold rebuild -------------------------------------------

    @Test
    fun a_collaborator_open_onto_a_hot_convo_rebuilds_it_with_the_grant_walls() = runBlocking {
        val fx = Fixture(CoroutineScope(Dispatchers.Default))
        val a = frames(); val frank = frames()
        val ownerConvoId = fx.openSession("owner", a) // the owner's LIVE, wall-less conversation
        val h = fx.createBound("owner", recipient = "dev-frank", into = a)
        fx.routeAsCollaborator(AcceptHandoff(h.id), "dev-frank", frank)

        // the collaborator even asks for BYPASS — the grant must clamp it, never honor it
        fx.routeAsCollaborator(
            OpenSession("/somewhere/else", resumeId = Fixture.SESSION_ID, mode = PermissionMode.BYPASS_PERMISSIONS),
            "dev-frank", frank,
        )
        val collabConvoId = fx.awaitLive(frank)

        // NOT a hot reattach onto the owner's convo: that path dropped pathScope + access + clamped mode
        assertNotEquals(ownerConvoId, collabConvoId, "a grant open must never plain-reattach the owner's convo")
        // the owner's wall-less convo is CLOSED — no second writer, no wall-less side door left live
        assertNull(fx.registry.modeOf(ownerConvoId), "the owner's live convo must be closed by the rebuild")
        // the rebuilt convo enforces EXACTLY the grant's walls: pathScope + read-only access ceiling…
        assertTrue(
            fx.registry.enforcesGrant(collabConvoId, fx.grantScope(), HandoffAccess.REVIEW_READ_ONLY),
            "the cold rebuild must carry the grant's pathScope + access into the conversation",
        )
        // …and the REVIEW-clamped mode (BYPASS → DEFAULT), not what the collaborator requested
        assertEquals(PermissionMode.DEFAULT, fx.registry.modeOf(collabConvoId), "mode must be the grant-clamped one")
        // the lease-holding recipient drives the rebuilt convo
        assertNull(fx.registry.driveDenied(collabConvoId, "dev-frank"))

        // a reconnecting collaborator MATCHES the walls and reattaches warm — no churn, same convo
        frank.clear()
        fx.routeAsCollaborator(OpenSession(fx.workdir, resumeId = Fixture.SESSION_ID), "dev-frank", frank)
        assertEquals(collabConvoId, fx.awaitLive(frank), "a matching grant re-open reattaches, not rebuilds")
    }

    /**
     * §3.3 INITIATOR AUTO-SPECTATE: the rebuild must MOVE the owner's live view onto the rebuilt
     * conversation — no manual re-open. What the initiator's client sees is the ordinary reattach
     * stream (SessionLive for the NEW convoId + the transcript), and from then on it is inside the
     * conversation's fan-out set, so every AssistantChunk / ToolEvent / PermissionAsk / TurnDone of the
     * recipient's review reaches it live. It stays a SPECTATOR (the controller lease refuses its input)
     * and the old wall-less conversation is gone, so the migration adds no second writer.
     */
    @Test
    fun the_initiator_is_migrated_onto_the_rebuilt_convo_without_re_opening() = runBlocking {
        val fx = Fixture(CoroutineScope(Dispatchers.Default))
        val a = frames(); val frank = frames()
        val ownerConvoId = fx.openSession("owner", a) // the owner is watching its own live session
        val h = fx.createBound("owner", recipient = "dev-frank", into = a)
        fx.routeAsCollaborator(AcceptHandoff(h.id), "dev-frank", frank)
        fx.routeAsCollaborator(OpenSession(fx.workdir, resumeId = Fixture.SESSION_ID), "dev-frank", frank)
        val collabConvoId = fx.awaitLive(frank)
        assertNotEquals(ownerConvoId, collabConvoId, "the grant open rebuilt the conversation")

        // the owner sent NOTHING since: the migration itself pushed it a SessionLive for the new convo
        val migrated = withTimeout(5_000) {
            var live: SessionLive? = null
            while (live == null) {
                live = a.filterIsInstance<SessionLive>().lastOrNull { it.convoId == collabConvoId }
                if (live == null) delay(20)
            }
            live
        }
        assertEquals(collabConvoId, migrated.convoId)
        assertNull(fx.registry.modeOf(ownerConvoId), "the wall-less convo is closed — never a second writer")

        // …and the owner is in the rebuilt conversation's FAN-OUT set, not merely notified once: a frame
        // the conversation pushes to all of its clients (here: the mode re-announce) reaches the owner too
        val before = a.filterIsInstance<SessionLive>().size
        fx.registry.switchMode(dev.ccpocket.protocol.SwitchMode(collabConvoId, PermissionMode.PLAN))
        withTimeout(5_000) { while (a.filterIsInstance<SessionLive>().size <= before) delay(20) }
        assertEquals(collabConvoId, a.filterIsInstance<SessionLive>().last().convoId, "the owner streams the recipient's session live")

        // spectator, not driver: the lease still refuses the owner's input while the handoff runs
        assertNotNull(fx.registry.driveDenied(collabConvoId, "owner"), "the owner watches, the lease holder drives")
        assertNull(fx.registry.driveDenied(collabConvoId, "dev-frank"))
    }

    @Test
    fun the_owner_spectates_the_rebuilt_convo_via_ordinary_reattach() = runBlocking {
        val fx = Fixture(CoroutineScope(Dispatchers.Default))
        val a = frames(); val frank = frames()
        fx.openSession("owner", a)
        val h = fx.createBound("owner", recipient = "dev-frank", into = a)
        fx.routeAsCollaborator(AcceptHandoff(h.id), "dev-frank", frank)
        fx.routeAsCollaborator(OpenSession(fx.workdir, resumeId = Fixture.SESSION_ID), "dev-frank", frank)
        val collabConvoId = fx.awaitLive(frank)

        // the owner re-opens its session (what a client does when its old convo goes away): it lands
        // on the COLLABORATOR's convo via the ordinary reattach path — same convoId, no fork
        val b = frames()
        fx.route(OpenSession(fx.workdir, resumeId = Fixture.SESSION_ID), "owner", b)
        assertEquals(collabConvoId, fx.awaitLive(b), "the owner's re-open must reattach the rebuilt convo")

        // spectator only while IN_PROGRESS: the controller lease still denies the owner's input
        assertNotNull(fx.registry.driveDenied(collabConvoId, "owner"), "the owner watches, the lease holder drives")
        assertNull(fx.registry.driveDenied(collabConvoId, "dev-frank"))
    }

    // ---- B. the REVIEW_READ_ONLY write wall in the PermissionBridge --------

    private data class Resp(val askId: String, val allow: Boolean, val deny: String?)

    private fun reviewBridge(
        scope: CoroutineScope,
        wd: String,
        emitted: MutableList<Frame>,
        responses: MutableList<Resp>,
        access: HandoffAccess = HandoffAccess.REVIEW_READ_ONLY,
        coordinator: dev.ccpocket.daemon.approval.ApprovalCoordinator =
            dev.ccpocket.daemon.approval.ApprovalCoordinator(scope),
    ) = PermissionBridge(
        convoId = "c1",
        mode = PermissionMode.DEFAULT,
        coordinator = coordinator,
        emit = { emitted += it },
        allowRules = java.util.concurrent.ConcurrentHashMap.newKeySet(),
        respond = { askId, allow, _, _, _, deny -> responses += Resp(askId, allow, deny) },
        pathScope = listOfNotNull(PathScope.canonical(wd)),
        workdir = wd,
        handoffAccess = access,
    )

    @Test
    fun review_grant_hard_refuses_write_tools_before_any_ask_exists() = runBlocking {
        val wd = Files.createTempDirectory("ccp-review-wd").toString()
        val emitted = frames(); val responses: MutableList<Resp> = CopyOnWriteArrayList()
        val bridge = reviewBridge(CoroutineScope(Dispatchers.Default), wd, emitted, responses)

        // every write-tool family is denied outright — even with an IN-SCOPE target
        for ((i, tool) in listOf("Write", "Edit", "MultiEdit", "NotebookEdit").withIndex()) {
            bridge.onControlRequest(AgentEvent.ControlRequest("w$i", tool, buildJsonObject { put("file_path", "$wd/a.txt") }))
        }
        assertEquals(4, responses.size, "each write tool must be answered (denied) immediately")
        assertTrue(responses.all { !it.allow && it.deny?.contains("review/read-only") == true })
        assertTrue(emitted.filterIsInstance<PermissionAsk>().isEmpty(), "no ask card may ever exist for a banned write")

        // a Read stays a NORMAL ask (the collaborator answers its own reads)…
        bridge.onControlRequest(AgentEvent.ControlRequest("r1", "Read", buildJsonObject { put("file_path", "$wd/a.txt") }))
        assertEquals("r1", emitted.filterIsInstance<PermissionAsk>().single().askId)
        // …while an out-of-scope Read still hits the pathScope wall (denied, no ask)
        bridge.onControlRequest(AgentEvent.ControlRequest("r2", "Read", buildJsonObject { put("file_path", "/etc/passwd") }))
        assertTrue(responses.any { it.askId == "r2" && !it.allow && it.deny?.contains("outside") == true })
        assertTrue(emitted.filterIsInstance<PermissionAsk>().none { it.askId == "r2" })

        // Bash is deliberately NOT on the hard-refuse list — it keeps the ordinary ask
        bridge.onControlRequest(AgentEvent.ControlRequest("b1", "Bash", buildJsonObject { put("command", "ls") }))
        assertTrue(emitted.filterIsInstance<PermissionAsk>().any { it.askId == "b1" }, "Bash must still route to an ask")
    }

    @Test
    fun a_recipients_verdict_cannot_release_a_hard_refused_write() = runBlocking {
        val wd = Files.createTempDirectory("ccp-review-wd2").toString()
        val emitted = frames(); val responses: MutableList<Resp> = CopyOnWriteArrayList()
        val testScope = CoroutineScope(Dispatchers.Default)
        val coordinator = dev.ccpocket.daemon.approval.ApprovalCoordinator(testScope)
        val bridge = reviewBridge(testScope, wd, emitted, responses, coordinator = coordinator)

        bridge.onControlRequest(AgentEvent.ControlRequest("w1", "Write", buildJsonObject { put("file_path", "$wd/a.txt") }))
        assertEquals(listOf(false), responses.map { it.allow }, "the deny already reached the agent")

        // the recipient IS the lease controller — its self-approval must find NOTHING to release: the
        // deny preceded the verdict channel structurally, so the verdict lands on the coordinator's
        // unclaimed path (false — RequestRouter then answers the tapping device with ask_expired)
        assertTrue(!coordinator.onVerdict(PermissionVerdict("c1", "w1", Decision.ALLOW)))
        assertTrue(responses.none { it.allow }, "no allow may ever reach the agent for a banned write")
    }

    @Test
    fun an_unknown_access_clamps_to_read_only_not_open() = runBlocking {
        // a NEWER peer's access value this daemon can't interpret must fail CLOSED (the §8.3 clamp)
        val wd = Files.createTempDirectory("ccp-review-wd3").toString()
        val emitted = frames(); val responses: MutableList<Resp> = CopyOnWriteArrayList()
        val bridge = reviewBridge(CoroutineScope(Dispatchers.Default), wd, emitted, responses, access = HandoffAccess.UNKNOWN)

        bridge.onControlRequest(AgentEvent.ControlRequest("w1", "Edit", buildJsonObject { put("file_path", "$wd/a.txt") }))
        assertTrue(responses.single().let { !it.allow && it.deny?.contains("review/read-only") == true })
        assertTrue(emitted.filterIsInstance<PermissionAsk>().isEmpty())
    }
}
