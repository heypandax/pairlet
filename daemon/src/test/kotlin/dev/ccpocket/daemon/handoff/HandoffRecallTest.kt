package dev.ccpocket.daemon.handoff

import dev.ccpocket.daemon.DaemonPrefs
import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentBackendFactory
import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.claude.AuthService
import dev.ccpocket.daemon.claude.StreamParser
import dev.ccpocket.daemon.codex.CodexBackend
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
import dev.ccpocket.protocol.HandoffStatus
import dev.ccpocket.protocol.HandoffUpdated
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PermissionVerdict
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.RecallHandoff
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.TurnDone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.absolutePathString
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GRACEFUL RECALL (SESSION-HANDOFF.md §5.4): "recall" must not mean "delete the lease and let the turn
 * keep running headless". While a turn is EXECUTING the daemon has to
 *
 *  1. arm the hand-back (lease `recallRequested` + `SessionHandoff.recallPending`) so NOBODY drives —
 *     the recipient's next prompt/verdict is refused, and the initiator waits instead of racing the
 *     dying turn;
 *  2. interrupt the turn through the live backend;
 *  3. only settle RECALLED (lease deleted, both sides notified) once the turn actually stopped — or,
 *     at a bounded timeout, settle anyway and SAY the stop was unclean.
 *
 * Both agent backends are driven for real here: each test runs a scripted `sh` agent process whose
 * output is parsed by the PRODUCTION parser of its kind (claude stream-json via [StreamParser], codex
 * app-server JSON-RPC via [CodexBackend.parse]) and whose `interrupt()` is what makes the turn end —
 * exactly the seam a recall depends on. Nothing here fakes the executing state.
 */
class HandoffRecallTest {

    private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")

    /**
     * A one-turn agent process: it waits for the prompt, streams [head] (the turn is now executing),
     * then waits for the interrupt and streams [tail] (the turn ends). [stoppable] = false models the
     * turn that ignores the interrupt — the bounded-wait case.
     */
    private class ScriptedTurnBackend(
        override val kind: AgentKind,
        head: Path,
        tail: Path,
        stoppable: Boolean,
        private val parser: suspend (String) -> List<AgentEvent>,
    ) : AgentBackend {
        val interrupts = AtomicInteger(0)

        @Volatile private var io: AgentIo? = null
        private val script =
            if (stoppable) "read go; cat '${head.absolutePathString()}'; read stop; cat '${tail.absolutePathString()}'; sleep 30"
            else "read go; cat '${head.absolutePathString()}'; sleep 30"

        override fun processBuilder(spec: AgentSpec): ProcessBuilder = ProcessBuilder("sh", "-c", script)
        override suspend fun attach(io: AgentIo, spec: AgentSpec) { this.io = io }
        override suspend fun parse(line: String): List<AgentEvent> = parser(line)
        override suspend fun sendPrompt(text: String, images: List<ImageData>) { io?.writeLine("go") }
        override suspend fun interrupt() { interrupts.incrementAndGet(); io?.writeLine("stop") }
        override suspend fun respondPermission(
            askId: String, allow: Boolean, remember: Boolean,
            originalInput: JsonObject?, updatedInput: String?, denyMessage: String?,
        ) {}
        override fun applySettings(mode: PermissionMode?, model: String?, effort: String?) = false
        override suspend fun onProcessEnded(sessionId: String?) {}
        override fun transcriptDir(workdir: String): Path = Path.of(workdir)
        override fun listSessions(workdir: String): List<SessionSummary> = emptyList()
        override fun replayHistory(workdir: String, sessionId: String): List<HistoryMessage> = emptyList()
        override fun resumeContextTokens(workdir: String, sessionId: String): Long? = null
    }

    /** Real wire lines per backend: what the agent prints while a turn runs, and what it prints when the
     *  turn is interrupted. Claude's pair is the CLI's own cancel shape (`<synthetic>` placeholder +
     *  is_error result); codex's is the app-server's `turn/completed` with an aborted status. */
    private fun wire(kind: AgentKind, sessionId: String): Pair<String, String> = when (kind) {
        AgentKind.CLAUDE -> Pair(
            """{"type":"system","subtype":"init","session_id":"$sessionId","cwd":"/tmp","model":"claude-sonnet-5"}""" + "\n" +
                """{"type":"assistant","message":{"model":"claude-sonnet-5","content":[{"type":"text","text":"reading the diff…"}]}}""" + "\n",
            """{"type":"assistant","message":{"model":"<synthetic>","content":[{"type":"text","text":"No response requested."}]}}""" + "\n" +
                """{"type":"result","subtype":"error_during_execution","is_error":true,"result":"No response requested."}""" + "\n",
        )
        else -> Pair(
            """{"method":"item/agentMessage/delta","params":{"itemId":"i1","delta":"reading the diff…"}}""" + "\n",
            """{"method":"turn/completed","params":{"turn":{"status":"aborted"}}}""" + "\n",
        )
    }

    private class Fixture(
        val scope: CoroutineScope,
        kind: AgentKind,
        stoppable: Boolean,
        recallTimeoutMs: Long,
        wire: Pair<String, String>,
    ) {
        private val tmp = Files.createTempDirectory("ccp-recall").toFile()
        val workdir: String = Files.createTempDirectory("ccp-recall-wd").toString()
        val backend = ScriptedTurnBackend(
            kind = kind,
            head = tmp.toPath().resolve("head.jsonl").apply { writeText(wire.first) },
            tail = tmp.toPath().resolve("tail.jsonl").apply { writeText(wire.second) },
            stoppable = stoppable,
            parser = parserOf(kind),
        )
        val registry = SessionRegistry(scope, backends = mapOf(kind to AgentBackendFactory { backend }))
        val handoffs = HandoffService(
            HandoffRegistry(HandoffStore.load(tmp.resolve("handoffs.json"))),
            recallTimeoutMs = recallTimeoutMs,
            recallPollMs = 20,
        )
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
        val agent: AgentKind = kind

        init {
            registry.handoffs = handoffs // also installs the registry as the graceful recall's turn control
        }

        fun sinkFor(deviceId: String, into: MutableList<Frame>): OutboundSink =
            KeyedSink("dev:$deviceId", OutboundSink { into += it })

        suspend fun route(frame: Frame, deviceId: String, into: MutableList<Frame>) =
            router.handle(frame, sinkFor(deviceId, into), deviceId = deviceId)

        /** The DeviceSessions collaborator ingress: caps type gate → guard vet → router with the scope. */
        suspend fun routeAsCollaborator(frame: Frame, deviceId: String, into: MutableList<Frame>) {
            if (!CollaboratorCaps.ingressAllowed(frame)) {
                into += PocketError("collaborator_forbidden", "not permitted for a collaborator link")
                return
            }
            val guard = handoffs.collaboratorGuard(deviceId)
            when (val v = guard.vet(frame)) {
                is CollaboratorGuard.Verdict.Deny -> into += PocketError(v.code, v.message)
                is CollaboratorGuard.Verdict.Allow -> router.handle(
                    v.frame, sinkFor(deviceId, into), deviceId = deviceId,
                    collabScope = CollaboratorScope(deviceId, v.pathScope, access = v.access ?: HandoffAccess.REVIEW_READ_ONLY),
                ) { convoId -> guard.noteOpened(convoId) }
            }
        }

        companion object {
            const val SESSION_ID = "aaaaaaaa-bbbb-cccc-dddd-111111111111"
            private fun parserOf(kind: AgentKind): suspend (String) -> List<AgentEvent> = when (kind) {
                AgentKind.CLAUDE -> { line -> StreamParser.parse(line) }
                else -> CodexBackend(codexBin = null)::parse
            }
        }
    }

    private fun frames(): MutableList<Frame> = CopyOnWriteArrayList()

    /** Poll [probe] until it yields — [what] names the wait in the timeout failure. No fixed sleeps
     *  anywhere: every wait is on the real condition. */
    private suspend fun <T : Any> await(what: String, timeoutMs: Long = 10_000, probe: suspend () -> T?): T =
        withTimeout(timeoutMs) {
            var v: T? = probe()
            while (v == null) {
                delay(20)
                v = probe()
            }
            v
        }

    /**
     * The acceptance scenario, run against a REAL executing turn of [kind]: the recipient is mid-review
     * when the initiator recalls.
     */
    private fun recallMidTurn(kind: AgentKind) = runBlocking {
        if (isWindows()) return@runBlocking // the scripted agent runs via sh/cat
        val scope = CoroutineScope(Dispatchers.Default)
        val fx = Fixture(scope, kind, stoppable = true, recallTimeoutMs = 10_000, wire = wire(kind, Fixture.SESSION_ID))
        val owner = frames(); val frank = frames()
        // both sides are attached fan-out targets, like the relay's owner attach / collaborator attach
        fx.handoffs.attach(fx.sinkFor("owner", owner))
        fx.handoffs.attach(fx.sinkFor("dev-frank", frank), recipientDeviceId = "dev-frank")
        try {
            fx.route(
                CreateHandoff(
                    workdir = fx.workdir, sessionId = Fixture.SESSION_ID,
                    brief = HandoffBrief(request = "review the recall path"),
                    agent = fx.agent, recipientDeviceId = "dev-frank",
                ),
                "owner", owner,
            )
            val h = assertNotNull(owner.filterIsInstance<HandoffCreated>().last().handoff)
            fx.routeAsCollaborator(AcceptHandoff(h.id), "dev-frank", frank)
            fx.routeAsCollaborator(OpenSession(fx.workdir, resumeId = Fixture.SESSION_ID), "dev-frank", frank)
            val convoId = await("the recipient's convo") { frank.filterIsInstance<SessionLive>().firstOrNull()?.convoId }

            // the recipient starts a turn — a REAL one: the scripted agent process spawns and streams
            fx.routeAsCollaborator(SendPrompt(convoId, "look at the diff"), "dev-frank", frank)
            await("an executing turn") { fx.registry.turnExecuting(Fixture.SESSION_ID).takeIf { it } }
            assertEquals(0, fx.backend.interrupts.get(), "nothing has interrupted the turn yet")
            assertNull(fx.registry.driveDenied(convoId, "dev-frank"), "the recipient is driving")

            // ---- the initiator recalls WHILE the turn runs ----
            owner.clear()
            fx.route(RecallHandoff(h.id), "owner", owner)

            // 1) the answer is "recall in flight", NOT "recalled": the row is still IN_PROGRESS
            val pending = owner.filterIsInstance<HandoffUpdated>().last().handoff
            assertEquals(HandoffStatus.IN_PROGRESS, pending.status, "control must not move before the stable point")
            assertTrue(pending.recallPending, "both sides must see the hand-back in flight")

            // 2) nobody drives in that window — the recipient's prompt AND verdict are refused…
            frank.clear()
            fx.routeAsCollaborator(SendPrompt(convoId, "one more thing"), "dev-frank", frank)
            fx.routeAsCollaborator(PermissionVerdict(convoId, "ask-1", Decision.ALLOW), "dev-frank", frank)
            val denied = frank.filterIsInstance<PocketError>().map { it.code }
            assertEquals(2, denied.size, "both frames must be refused: $denied")
            assertTrue(denied.all { it == "handoff_grant_inactive" }, "the grant is over for new work: $denied")
            // …and the initiator cannot race the dying turn either
            val ownerDeny = assertNotNull(fx.registry.driveDenied(convoId, "owner"), "the initiator waits for the stable point")
            assertEquals(HandoffGuard.DenyReason.RECALL_PENDING, ownerDeny.reason)

            // 3) the daemon interrupted the live backend of THIS kind…
            await("the backend's interrupt") { fx.backend.interrupts.get().takeIf { it > 0 } }
            // …the turn really ended (the agent answered the interrupt with its own turn-end wire lines)…
            await("the interrupted turn to settle") { frank.filterIsInstance<TurnDone>().firstOrNull() }

            // 4) …and only THEN control comes back: RECALLED, lease gone, both sides told
            val recalled = await("the terminal RECALLED") {
                fx.handoffs.registry.byId(h.id)?.takeIf { it.status == HandoffStatus.RECALLED }
            }
            assertFalse(recalled.recallPending, "the settle clears the in-flight marker")
            assertFalse(recalled.recallIncomplete, "the turn stopped cleanly — nothing to warn about")
            assertNull(fx.handoffs.registry.activeFor(Fixture.SESSION_ID), "terminal: the lease is gone")
            // The ledger settles BEFORE the announcement goes out (the settle writes the row, then
            // HandoffService.broadcast cuts the ended Grant's session views — §5.3 item 7 — and only
            // then fans the terminal HandoffUpdated out). So the await above proves the transition, not
            // its delivery: wait on the delivery itself, the same real-condition way, instead of reading
            // the fan-out lists in the same instant the row flipped.
            for ((who, seen) in listOf("initiator" to owner, "recipient" to frank)) {
                await("the $who to be told the recall completed") {
                    seen.filterIsInstance<HandoffUpdated>().firstOrNull { it.handoff.status == HandoffStatus.RECALLED }
                }
            }
            // the owner drives its session again; the collaborator's grant is dead
            assertNull(fx.registry.driveDenied(convoId, "owner"))
            frank.clear()
            fx.routeAsCollaborator(SendPrompt(convoId, "still here?"), "dev-frank", frank)
            assertTrue("handoff_grant_inactive" in frank.filterIsInstance<PocketError>().map { it.code })
        } finally {
            fx.registry.closeAll()
            scope.cancel()
        }
    }

    @Test
    fun a_claude_turn_is_interrupted_and_control_returns_at_the_stable_point() = recallMidTurn(AgentKind.CLAUDE)

    @Test
    fun a_codex_turn_is_interrupted_and_control_returns_at_the_stable_point() = recallMidTurn(AgentKind.CODEX)

    /**
     * The bounded wait (§5.4 + §5 item 4): a turn that ignores the interrupt must not hold the owner
     * hostage — control comes back at the timeout, and the row says the stop was NOT clean instead of
     * pretending everything stopped.
     */
    @Test
    fun a_turn_that_ignores_the_interrupt_settles_at_the_timeout_and_admits_it() = runBlocking {
        if (isWindows()) return@runBlocking
        val scope = CoroutineScope(Dispatchers.Default)
        val fx = Fixture(
            scope, AgentKind.CLAUDE, stoppable = false, recallTimeoutMs = 400,
            wire = wire(AgentKind.CLAUDE, Fixture.SESSION_ID),
        )
        val owner = frames(); val frank = frames()
        fx.handoffs.attach(fx.sinkFor("owner", owner))
        try {
            fx.route(
                CreateHandoff(
                    workdir = fx.workdir, sessionId = Fixture.SESSION_ID,
                    brief = HandoffBrief(request = "review the recall path"),
                    agent = fx.agent, recipientDeviceId = "dev-frank",
                ),
                "owner", owner,
            )
            val h = assertNotNull(owner.filterIsInstance<HandoffCreated>().last().handoff)
            fx.routeAsCollaborator(AcceptHandoff(h.id), "dev-frank", frank)
            fx.routeAsCollaborator(OpenSession(fx.workdir, resumeId = Fixture.SESSION_ID), "dev-frank", frank)
            val convoId = await("the recipient's convo") { frank.filterIsInstance<SessionLive>().firstOrNull()?.convoId }
            fx.routeAsCollaborator(SendPrompt(convoId, "look at the diff"), "dev-frank", frank)
            await("an executing turn") { fx.registry.turnExecuting(Fixture.SESSION_ID).takeIf { it } }

            fx.route(RecallHandoff(h.id), "owner", owner)
            val recalled = await("the timed-out recall to settle") {
                fx.handoffs.registry.byId(h.id)?.takeIf { it.status == HandoffStatus.RECALLED }
            }
            assertTrue(recalled.recallIncomplete, "an unclean stop must be visible in the state, not glossed over")
            assertTrue(fx.registry.turnExecuting(Fixture.SESSION_ID), "the turn really is still running — that's why")
            assertNull(fx.handoffs.registry.activeFor(Fixture.SESSION_ID), "the owner still gets control back")
            assertNull(fx.registry.driveDenied(convoId, "owner"))
            assertTrue(frank.filterIsInstance<TurnDone>().isEmpty(), "no fake TurnDone was invented for the client")
        } finally {
            fx.registry.closeAll()
            scope.cancel()
        }
    }

    /** The idle half of §5.4: with no turn running there is no stable point to wait for — one update,
     *  straight to RECALLED, no recallPending flicker. */
    @Test
    fun an_idle_session_is_recalled_immediately() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val fx = Fixture(
            scope, AgentKind.CLAUDE, stoppable = true, recallTimeoutMs = 10_000,
            wire = wire(AgentKind.CLAUDE, Fixture.SESSION_ID),
        )
        val owner = frames(); val frank = frames()
        try {
            fx.route(
                CreateHandoff(
                    workdir = fx.workdir, sessionId = Fixture.SESSION_ID,
                    brief = HandoffBrief(request = "review the recall path"),
                    agent = fx.agent, recipientDeviceId = "dev-frank",
                ),
                "owner", owner,
            )
            val h = assertNotNull(owner.filterIsInstance<HandoffCreated>().last().handoff)
            fx.routeAsCollaborator(AcceptHandoff(h.id), "dev-frank", frank)
            // accepted but never opened: nothing is executing
            owner.clear()
            fx.route(RecallHandoff(h.id), "owner", owner)
            val updates = owner.filterIsInstance<HandoffUpdated>().map { it.handoff }
            assertEquals(1, updates.size, "an idle recall answers once: ${updates.map { it.status }}")
            assertEquals(HandoffStatus.RECALLED, updates.single().status)
            assertFalse(updates.single().recallPending)
            assertFalse(updates.single().recallIncomplete)
            assertEquals(0, fx.backend.interrupts.get(), "no process, nothing to interrupt")
            assertNull(fx.handoffs.registry.activeFor(Fixture.SESSION_ID))
        } finally {
            fx.registry.closeAll()
            scope.cancel()
        }
    }
}
