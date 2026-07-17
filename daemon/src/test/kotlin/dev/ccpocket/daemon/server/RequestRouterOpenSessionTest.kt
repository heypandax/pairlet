package dev.ccpocket.daemon.server

import dev.ccpocket.daemon.DaemonPrefs
import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentBackendFactory
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.bridge.GuestScope
import dev.ccpocket.daemon.claude.AuthService
import dev.ccpocket.daemon.disk.DirectoryService
import dev.ccpocket.daemon.disk.FileExportService
import dev.ccpocket.daemon.disk.FileInboxService
import dev.ccpocket.daemon.presets.PresetService
import dev.ccpocket.daemon.presets.PresetStore
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.daemon.shell.ShellService
import dev.ccpocket.daemon.transcribe.TranscribeService
import dev.ccpocket.protocol.AccessTier
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.SessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The "open a project" contract (issue #152): OpenSession with resumeId == null must work for a
 * directory that has NO agent history at all — the phone's folder browser upgrades any browsed
 * directory into a session cwd, so the router must accept a fresh dir (and the raw `~` form the
 * picker ships, mirroring the ListSessions tilde rule), refuse an unusable path with `bad_workdir`,
 * and keep a GUEST clamped to its shared root (`share_out_of_scope`) even at this second gate —
 * GuestGuard vets first, but the router's re-check is the belt-and-suspenders the #115 scope relies
 * on. The lazy open (#61) spawns no process, so a stub backend drives the whole path.
 */
class RequestRouterOpenSessionTest {

    /** Never launches: a plain (non-takeOver) open is lazy (#61), so no process member is reached. */
    private class StubBackend : AgentBackend {
        override val kind = AgentKind.CLAUDE
        override fun listSessions(workdir: String): List<SessionSummary> = emptyList()
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

    private fun router(scope: CoroutineScope): RequestRouter {
        val registry = SessionRegistry(scope, backends = mapOf(AgentKind.CLAUDE to AgentBackendFactory { StubBackend() }))
        val tmp = Files.createTempDirectory("ccp-router-open").toFile()
        return RequestRouter(
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
    }

    /** SessionLive lands async (Conversation.open announces it off the inbound pump) — poll for it. */
    private suspend fun awaitLive(emitted: List<Frame>): SessionLive = withTimeout(5_000) {
        while (emitted.none { it is SessionLive }) delay(20)
        emitted.filterIsInstance<SessionLive>().first()
    }

    private fun guestScope(root: Path) = GuestScope(
        roots = listOf(root.toRealPath().toString()),
        ownedSessions = emptySet(), label = "alex", expiresAt = null, tier = AccessTier.COLLABORATE,
    )

    @Test
    fun a_directory_with_no_history_opens_a_new_session() = runBlocking {
        val fresh = Files.createTempDirectory("ccp-open-fresh") // no ~/.claude project, no rollouts — nothing
        val emitted = Collections.synchronizedList(mutableListOf<Frame>())
        router(CoroutineScope(Dispatchers.Default)).handle(OpenSession(fresh.toString()), { emitted += it })

        val live = awaitLive(emitted)
        assertEquals(fresh.toRealPath().toString(), live.workdir, "the announced cwd is the canonicalized picked dir")
        assertTrue(emitted.none { it is PocketError }, "a readable no-history dir must not be refused: $emitted")
    }

    @Test
    fun the_pickers_raw_tilde_workdir_expands_to_the_daemon_home() = runBlocking {
        // the phone's folder browser (issue #152) ships "~"-anchored workdirs raw — only the daemon
        // knows the remote machine's home (same contract the ListSessions tilde test pins)
        val emitted = Collections.synchronizedList(mutableListOf<Frame>())
        router(CoroutineScope(Dispatchers.Default)).handle(OpenSession("~"), { emitted += it })

        val live = awaitLive(emitted)
        assertEquals(Path.of(System.getProperty("user.home")).toRealPath().toString(), live.workdir)
    }

    @Test
    fun a_path_without_an_existing_parent_is_bad_workdir() = runBlocking {
        val emitted = Collections.synchronizedList(mutableListOf<Frame>())
        router(CoroutineScope(Dispatchers.Default)).handle(OpenSession("/no/such/ccp-parent/child"), { emitted += it })

        val err = emitted.filterIsInstance<PocketError>().firstOrNull()
        assertNotNull(err, "an unusable path must answer with a PocketError")
        assertEquals("bad_workdir", err.code)
        assertTrue(emitted.none { it is SessionLive })
    }

    @Test
    fun a_guest_open_outside_its_shared_root_is_refused_at_the_router_too() = runBlocking {
        val root = Files.createTempDirectory("ccp-open-root")
        val outside = Files.createTempDirectory("ccp-open-outside")
        val emitted = Collections.synchronizedList(mutableListOf<Frame>())
        router(CoroutineScope(Dispatchers.Default))
            .handle(OpenSession(outside.toString()), { emitted += it }, origin = "share:alex", guestScope = guestScope(root))

        val err = emitted.filterIsInstance<PocketError>().firstOrNull()
        assertNotNull(err, "the router's own scope re-check must refuse an out-of-root open")
        assertEquals("share_out_of_scope", err.code)
        assertTrue(emitted.none { it is SessionLive })
    }

    @Test
    fun a_guest_open_of_a_fresh_subfolder_under_its_root_still_works() = runBlocking {
        val root = Files.createTempDirectory("ccp-open-root2")
        val sub = Files.createDirectory(root.resolve("fresh-project")) // exists, zero history
        val emitted = Collections.synchronizedList(mutableListOf<Frame>())
        router(CoroutineScope(Dispatchers.Default))
            .handle(OpenSession(sub.toString()), { emitted += it }, origin = "share:alex", guestScope = guestScope(root))

        val live = awaitLive(emitted)
        assertEquals(sub.toRealPath().toString(), live.workdir, "in-scope fresh dirs stay openable for a guest")
    }
}
