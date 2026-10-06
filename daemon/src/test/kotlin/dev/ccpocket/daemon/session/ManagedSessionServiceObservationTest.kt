package dev.ccpocket.daemon.session

import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.disk.ManagedSessionStore
import dev.ccpocket.daemon.disk.ObservationLookup
import dev.ccpocket.daemon.disk.canonicalManagedWorkdir
import dev.ccpocket.daemon.pins.DurablePinFiles
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ImportSession
import dev.ccpocket.protocol.ListManagedSessions
import dev.ccpocket.protocol.ManagedSessionErrors
import dev.ccpocket.protocol.ManagedSessionsState
import dev.ccpocket.protocol.ObservationAttributions
import dev.ccpocket.protocol.ObservationBinding
import dev.ccpocket.protocol.ObservedProgress
import dev.ccpocket.protocol.ObservedStates
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.SetSessionObservation
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
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/design/DOTS-SESSION-OBSERVABILITY.md §4.2 / §4.6 at the service layer: import + bind lands as one member with
 * its policy (and nothing when refused); binding a session this daemon is driving is refused without touching it; a
 * bound row projects its binding + progress for a capable peer, and reads as a plain row for an undeclared one.
 */
class ManagedSessionServiceObservationTest {
    private val tmp: Path = Files.createTempDirectory("ccp-managed-obs-svc")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val root: File = Files.createDirectories(tmp.resolve("store")).toFile()
    private val project: Path = Files.createDirectories(tmp.resolve("proj"))
    private val workdir: String = assertNotNull(canonicalManagedWorkdir(project.toString()))
    private val live = HashSet<String>()
    private val progress = HashMap<String, ObservedProgress>()
    private val binding = ObservationBinding(parentTaskRef = "Dot · task")

    @AfterTest
    fun cleanup() {
        scope.cancel()
        tmp.toFile().deleteRecursively()
    }

    private fun service() = ManagedSessionService(
        store = ManagedSessionStore(root, DurablePinFiles(), ::canonicalManagedWorkdir) { 1L },
        scope = scope,
        scan = { wd, a -> SessionScan(a, wd, listOf(row("t1"), row("t2")), ScanCompleteness.COMPLETE) },
        validateWorkdir = ::canonicalManagedWorkdir,
        agents = setOf(AgentKind.CLAUDE, AgentKind.CODEX),
        pendingFile = File(root, "pending-registrations.json"),
        liveSession = { it in live },
        progressOf = { _, sid -> progress[sid] },
    )

    private fun row(id: String) = SessionSummary(id, "title $id", "prompt", 1, workdir, 5, agent = AgentKind.CODEX)

    private suspend fun ManagedSessionService.reply(frame: ToDaemon): ManagedSessionsState = mutate(frame).first

    @Test
    fun import_with_binding_lands_bound_and_projects_binding_plus_progress() = runBlocking<Unit> {
        val svc = service()
        progress["t1"] = ObservedProgress(state = ObservedStates.RUNNING, observedAt = 9)
        val imported = svc.reply(ImportSession("r1", workdir, AgentKind.CODEX, "t1", observation = binding))
        assertNull(imported.error, imported.message)
        assertTrue(imported.changed)
        val entry = assertNotNull(imported.entry)
        val obs = assertNotNull(entry.observation)
        assertEquals(binding, obs.binding)
        assertTrue(obs.readOnly)
        assertEquals(ObservedStates.RUNNING, obs.progress?.state)
        assertEquals(obs, entry.summary?.observation, "the summary carries the same snapshot as the entry")
        assertEquals(ObservationLookup.Bound(binding), svc.store.observationOf(workdir, AgentKind.CODEX, "t1"))
        // an unbound member stays exactly as before
        val plain = svc.reply(ImportSession("r2", workdir, AgentKind.CODEX, "t2"))
        assertNull(plain.entry?.observation)
        assertNull(plain.entry?.summary?.observation)
    }

    @Test
    fun binding_a_session_this_daemon_drives_is_refused_and_imports_nothing() = runBlocking<Unit> {
        val svc = service()
        live += "t1"
        val refused = svc.reply(ImportSession("r1", workdir, AgentKind.CODEX, "t1", observation = binding))
        assertEquals(ManagedSessionErrors.OBSERVATION_CONFLICT, refused.error)
        assertEquals("t1", refused.sessionId)
        assertNull(svc.list(ListManagedSessions("r", workdir, AgentKind.CODEX)).items!!.firstOrNull { it.sessionId == "t1" }, "refused as a whole: not imported")
        // a plain import is fine, and binding it later is refused while it is live, accepted once it is not
        assertNull(svc.reply(ImportSession("r2", workdir, AgentKind.CODEX, "t1")).error)
        assertEquals(ManagedSessionErrors.OBSERVATION_CONFLICT, svc.reply(SetSessionObservation("r3", workdir, AgentKind.CODEX, "t1", binding)).error)
        live -= "t1"
        val bound = svc.reply(SetSessionObservation("r4", workdir, AgentKind.CODEX, "t1", binding))
        assertNull(bound.error)
        assertTrue(bound.changed)
        assertEquals(binding, bound.entry?.observation?.binding)
        // clearing never needs the live check (it only loosens the policy)
        live += "t1"
        val cleared = svc.reply(SetSessionObservation("r5", workdir, AgentKind.CODEX, "t1", null))
        assertNull(cleared.error)
        assertNull(cleared.entry?.observation)
    }

    @Test
    fun a_client_cannot_mint_verified_or_writable_bindings_and_cannot_bind_a_non_member() = runBlocking<Unit> {
        val svc = service()
        assertEquals(ManagedSessionErrors.OBSERVATION_INVALID, svc.reply(ImportSession("r", workdir, AgentKind.CODEX, "t1", observation = binding.copy(attribution = ObservationAttributions.VERIFIED))).error)
        assertEquals(ManagedSessionErrors.OBSERVATION_INVALID, svc.reply(SetSessionObservation("r", workdir, AgentKind.CODEX, "t1", binding.copy(readOnly = false))).error)
        assertEquals(ManagedSessionErrors.NOT_FOUND, svc.reply(SetSessionObservation("r", workdir, AgentKind.CODEX, "t1", binding)).error)
    }

    @Test
    fun strip_observation_removes_the_snapshot_for_an_undeclared_connection() = runBlocking<Unit> {
        val svc = service()
        svc.reply(ImportSession("r1", workdir, AgentKind.CODEX, "t1", observation = binding))
        val listed = svc.list(ListManagedSessions("r", workdir, AgentKind.CODEX))
        assertNotNull(listed.items!!.single { it.sessionId == "t1" }.observation)
        val stripped = ManagedSessionService.stripObservation(listed)
        assertTrue(stripped.items!!.all { it.observation == null && it.summary?.observation == null })
        assertEquals(listed.items!!.map { it.sessionId }, stripped.items!!.map { it.sessionId })
    }

    @Test
    fun the_accept_path_answers_a_set_observation_request_and_pushes_to_other_owners() = runBlocking<Unit> {
        val svc = service()
        svc.reply(ImportSession("r1", workdir, AgentKind.CODEX, "t1"))
        val replies = CopyOnWriteArrayList<Frame>()
        val pushes = CopyOnWriteArrayList<ManagedSessionsState>()
        svc.attach("other") { pushes += it }
        svc.accept(SetSessionObservation("r2", workdir, AgentKind.CODEX, "t1", binding), OutboundSink { replies += it }, requesterKey = "me")
        withTimeout(5_000) { while (replies.isEmpty() || pushes.isEmpty()) delay(20) }
        val reply = replies.single() as ManagedSessionsState
        assertEquals("r2", reply.requestId)
        assertNull(reply.error)
        assertEquals(binding, reply.entry?.observation?.binding)
        assertNull(pushes.single().requestId)
        assertFalse(pushes.single().items!!.none { it.observation?.binding == binding })
    }
}
