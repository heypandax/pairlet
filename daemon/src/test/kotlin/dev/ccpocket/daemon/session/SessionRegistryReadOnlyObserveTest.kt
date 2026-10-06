package dev.ccpocket.daemon.session

import dev.ccpocket.daemon.conversation.KeyedSink
import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.disk.LiveProcesses
import dev.ccpocket.daemon.disk.ObservationLookup
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ObservationBinding
import dev.ccpocket.protocol.ObservationErrors
import dev.ccpocket.protocol.ObservedStates
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.SessionLive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.appendText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/design/DOTS-SESSION-OBSERVABILITY.md §4.3: a read-only policy (persisted binding or `observeOnly`) yields an
 * observe view REGARDLESS of writer detection — no external process, idle rollout, probe ABSENT — and never a
 * controllable conversation; a take-over of a bound session, a bound session without a record, and a store that
 * cannot be read all refuse with a stable code; an undeclared peer gets the legacy `observing` + notice.
 *
 * Backends are EMPTY on purpose: if any branch reached the launch path it would fail with "no backend registered",
 * which is exactly the regression these tests pin.
 */
class SessionRegistryReadOnlyObserveTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tmp: Path = Files.createTempDirectory("ccp-ro-observe")
    private val workdir = tmp.resolve("proj").also { Files.createDirectories(it) }.toString()
    private val sid = "01a1-${UUID.randomUUID()}"
    private val rollout: Path = tmp.resolve("rollout-2026-10-05T20-01-13-$sid.jsonl")
    private val binding = ObservationBinding(parentTaskRef = "Dot · task")
    private var policy: ObservationLookup = ObservationLookup.Unbound

    private class Capture : OutboundSink {
        val frames = CopyOnWriteArrayList<Frame>()
        override suspend fun emit(frame: Frame) { frames += frame }
        inline fun <reified T : Frame> last(): T? = frames.filterIsInstance<T>().lastOrNull()
    }

    private fun sink(key: String, capture: Capture) = KeyedSink(key, capture)

    private fun registry(probe: LiveProcesses.ExternalClaude = LiveProcesses.ExternalClaude.ABSENT) = SessionRegistry(
        scope,
        backends = emptyMap(),
        processProbe = { _, _ -> probe },
        codexProcessProbe = { _, _ -> probe },
        transcriptResolver = { agent, _, s -> if (agent == AgentKind.CODEX && s == sid) rollout else null },
    ).also { it.observationPolicy = { _, _, _ -> policy } }

    private fun writeRollout(ended: Boolean) {
        rollout.writeText(
            """
            {"timestamp":"2026-10-06T03:00:00.000Z","type":"session_meta","payload":{"id":"$sid","cwd":"$workdir","cli_version":"0.155.1"}}
            {"timestamp":"2026-10-06T03:00:01.000Z","type":"event_msg","payload":{"type":"task_started","turn_id":"t1"}}
            {"timestamp":"2026-10-06T03:00:02.000Z","type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"hello from the dot"}]}}
            """.trimIndent() + "\n" +
                (if (ended) """{"timestamp":"2026-10-06T03:00:03.000Z","type":"event_msg","payload":{"type":"task_complete","turn_id":"t1"}}""" + "\n" else ""),
        )
        // an OLD file (hours ago): no freshness gate can mistake it for a live writer
        Files.setLastModifiedTime(rollout, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 3_600_000))
    }

    private suspend fun awaitLive(c: Capture): SessionLive = withTimeout(5_000) {
        while (c.last<SessionLive>() == null) delay(20)
        c.last<SessionLive>()!!
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        tmp.toFile().deleteRecursively()
    }

    @Test
    fun bound_session_opens_read_only_without_any_writer_and_announces_the_snapshot_to_a_capable_peer() = runBlocking<Unit> {
        writeRollout(ended = true)
        policy = ObservationLookup.Bound(binding)
        val r = registry()
        val c = Capture()
        val convoId = r.open(OpenSession(workdir, sid, agent = AgentKind.CODEX), sink("dev:phone", c), peerSupportsObservation = true)
        assertTrue(convoId.isNotEmpty(), "an observe view, not a refusal: ${c.frames}")
        assertTrue(r.observing(convoId))
        assertFalse(r.isLiveSession(sid), "no controllable conversation was created")
        val live = awaitLive(c)
        assertTrue(live.observing)
        assertEquals(sid, live.sessionId)
        val obs = assertNotNull(live.observation)
        assertTrue(obs.readOnly)
        assertEquals(binding, obs.binding)
        val progress = assertNotNull(obs.progress)
        assertEquals(ObservedStates.IDLE, progress.state)
        assertEquals("t1", progress.turnId)
        assertEquals(dev.ccpocket.daemon.conversation.ObserveSession.READ_ONLY_NOTICE, live.notice)
    }

    @Test
    fun an_undeclared_peer_gets_observing_and_the_notice_but_no_snapshot() = runBlocking<Unit> {
        writeRollout(ended = false)
        policy = ObservationLookup.Bound(binding)
        val c = Capture()
        val convoId = registry().open(OpenSession(workdir, sid, agent = AgentKind.CODEX), sink("dev:old", c))
        assertTrue(convoId.isNotEmpty())
        val live = awaitLive(c)
        assertTrue(live.observing)
        assertNull(live.observation)
        assertNotNull(live.notice)
    }

    @Test
    fun observe_only_without_a_binding_is_read_only_too_and_follows_the_same_path() = runBlocking<Unit> {
        writeRollout(ended = false)
        policy = ObservationLookup.Unbound
        val c = Capture()
        val convoId = registry().open(OpenSession(workdir, sid, agent = AgentKind.CODEX, observeOnly = true), sink("dev:phone", c), peerSupportsObservation = true)
        assertTrue(convoId.isNotEmpty(), "${c.frames}")
        val live = awaitLive(c)
        val obs = assertNotNull(live.observation)
        assertTrue(obs.readOnly)
        assertNull(obs.binding)
        assertEquals(ObservedStates.RUNNING, assertNotNull(obs.progress).state)
    }

    @Test
    fun take_over_of_a_bound_session_is_refused_with_a_stable_code() = runBlocking<Unit> {
        writeRollout(ended = true)
        policy = ObservationLookup.Bound(binding)
        val r = registry()
        val c = Capture()
        val convoId = r.open(OpenSession(workdir, sid, agent = AgentKind.CODEX, takeOver = true), sink("dev:phone", c))
        assertEquals("", convoId)
        assertEquals(ObservationErrors.READ_ONLY, assertNotNull(c.last<PocketError>()).code)
        assertFalse(r.isLiveSession(sid))
        // …and so is a rename, whichever agent the client guessed
        assertNotNull(r.renameSession(workdir, sid, "new title"))
    }

    @Test
    fun observe_only_without_a_readable_record_creates_nothing() = runBlocking<Unit> {
        policy = ObservationLookup.Unbound
        val c = Capture()
        val convoId = registry().open(OpenSession(workdir, sid, agent = AgentKind.CODEX, observeOnly = true), sink("dev:phone", c))
        assertEquals("", convoId)
        assertEquals(ObservationErrors.OBSERVE_UNAVAILABLE, assertNotNull(c.last<PocketError>()).code)
    }

    @Test
    fun an_unreadable_policy_store_refuses_control_rather_than_degrading_to_writable() = runBlocking<Unit> {
        writeRollout(ended = true)
        policy = ObservationLookup.Unavailable("undecodable")
        val r = registry()
        val c = Capture()
        assertEquals("", r.open(OpenSession(workdir, sid, agent = AgentKind.CODEX), sink("dev:phone", c)))
        assertEquals(ObservationErrors.READ_ONLY, assertNotNull(c.last<PocketError>()).code)
        assertNotNull(r.renameSession(workdir, sid, "x"))
    }

    @Test
    fun a_bridge_cannot_open_a_read_only_view() = runBlocking<Unit> {
        writeRollout(ended = true)
        policy = ObservationLookup.Bound(binding)
        val c = Capture()
        assertEquals("", registry().open(OpenSession(workdir, sid, agent = AgentKind.CODEX), sink("bridge", c), origin = "feishu-bot"))
        assertEquals(ObservationErrors.READ_ONLY, assertNotNull(c.last<PocketError>()).code)
    }

    @Test
    fun a_same_client_reopen_reaps_its_previous_read_only_observer_and_another_clients_survives() = runBlocking<Unit> {
        writeRollout(ended = true)
        policy = ObservationLookup.Bound(binding)
        val r = registry()
        val phone1 = r.open(OpenSession(workdir, sid, agent = AgentKind.CODEX), sink("dev:phone", Capture()))
        val desktop = r.open(OpenSession(workdir, sid, agent = AgentKind.CODEX), sink("dev:desktop", Capture()))
        val phone2 = r.open(OpenSession(workdir, sid, agent = AgentKind.CODEX), sink("dev:phone", Capture()))
        assertFalse(r.observing(phone1))
        assertTrue(r.observing(phone2))
        assertTrue(r.observing(desktop))
    }

    @Test
    fun new_turn_records_reach_the_observer_as_progress_and_history() = runBlocking<Unit> {
        writeRollout(ended = true)
        policy = ObservationLookup.Bound(binding)
        val c = Capture()
        val convoId = registry().open(OpenSession(workdir, sid, agent = AgentKind.CODEX, lastEventSeq = 0), sink("dev:phone", c), peerSupportsObservation = true)
        assertTrue(convoId.isNotEmpty())
        assertEquals(ObservedStates.IDLE, assertNotNull(awaitLive(c).observation?.progress).state)
        // the outside driver starts a new turn: the tail sees it within a tick or two
        rollout.appendText("""{"timestamp":"2026-10-06T03:10:00.000Z","type":"event_msg","payload":{"type":"task_started","turn_id":"t2"}}""" + "\n")
        rollout.appendText("""{"timestamp":"2026-10-06T03:10:01.000Z","type":"response_item","payload":{"type":"custom_tool_call","name":"shell","call_id":"c","input":"make test"}}""" + "\n")
        Files.setLastModifiedTime(rollout, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()))
        withTimeout(10_000) {
            while (c.last<SessionLive>()?.observation?.progress?.state != ObservedStates.RUNNING) delay(50)
        }
        val p = assertNotNull(c.last<SessionLive>()?.observation?.progress)
        assertEquals("t2", p.turnId)
        assertEquals("shell · make test", p.currentAction)
    }
}
