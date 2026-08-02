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
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ArchivedSessions
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.ListArchivedSessions
import dev.ccpocket.protocol.ListSessions
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.Sessions
import dev.ccpocket.protocol.SetSessionArchived
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #202 at the router: archiving hides a row from the REGULAR listing (filtered daemon-side, so even a
 * client that knows nothing about archives gets the tidied list), restoring brings it back, and the
 * cross-project view enumerates only the projects the store actually names. Also pins the two decisions a
 * later change could silently undo: a guest can't mutate the archive, and acting `fromArchiveView` answers
 * with the archive list so the client's listed directory is never repointed.
 */
class RequestRouterArchiveTest {

    /** Serves a fixed session list per workdir. */
    private class FixedBackend(private val byDir: Map<String, List<SessionSummary>>) : AgentBackend {
        override val kind = AgentKind.CLAUDE
        override fun listSessions(workdir: String): List<SessionSummary> = byDir[workdir].orEmpty()

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

    private fun summary(id: String, cwd: String, agent: AgentKind = AgentKind.CLAUDE) =
        SessionSummary(
            sessionId = id, title = "T-$id", firstPrompt = "p", messageCount = 1,
            cwd = cwd, lastModified = 1, agent = agent,
        )

    private lateinit var archiveFile: java.io.File

    private fun router(scope: CoroutineScope, byDir: Map<String, List<SessionSummary>>): RequestRouter {
        val tmp = Files.createTempDirectory("ccp-archive-router").toFile()
        archiveFile = tmp.resolve("session-archive.json")
        return RequestRouter(
            registry = SessionRegistry(scope, backends = mapOf(AgentKind.CLAUDE to AgentBackendFactory { FixedBackend(byDir) })),
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
            archiveFile = archiveFile,
        )
    }

    /** The archive-list branch runs off-pump (scope.launch) — await its reply. */
    private suspend fun awaitFrame(emitted: MutableList<Frame>, n: Int = 1): List<Frame> =
        withTimeout(10_000) {
            while (synchronized(emitted) { emitted.size < n }) delay(10)
            synchronized(emitted) { emitted.toList() }
        }

    // an unresolvable path keeps its raw string (RequestRouterListSessionsTest pins that), which is all
    // this test needs — the archive keys off whatever groupWorkdir returned
    private val dirA = "/no/such/ccp-arch-a"
    private val dirB = "/no/such/ccp-arch-b"

    @Test
    fun archiving_hides_the_row_from_the_regular_listing_and_restoring_brings_it_back() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val r = router(scope, mapOf(dirA to listOf(summary("s1", dirA), summary("s2", dirA))))

        var emitted = mutableListOf<Frame>()
        r.handle(SetSessionArchived(dirA, "s1", archived = true), { synchronized(emitted) { emitted += it } })
        var sessions = awaitFrame(emitted).single() as Sessions
        assertEquals(listOf("s2"), sessions.items.map { it.sessionId }, "the archived row is filtered daemon-side")
        assertTrue(sessions.archiveSupported, "an owner connection carries the #202 capability stamp")

        // and a plain re-list agrees — the filter is in emitSessions, not just the mutation's reply
        emitted = mutableListOf()
        r.handle(ListSessions(dirA), { synchronized(emitted) { emitted += it } })
        assertEquals(listOf("s2"), (awaitFrame(emitted).single() as Sessions).items.map { it.sessionId })

        emitted = mutableListOf()
        r.handle(SetSessionArchived(dirA, "s1", archived = false), { synchronized(emitted) { emitted += it } })
        sessions = awaitFrame(emitted).single() as Sessions
        assertEquals(listOf("s1", "s2"), sessions.items.map { it.sessionId }.sorted(), "archiving is reversible")
    }

    @Test
    fun the_cross_project_view_lists_every_archived_session_newest_first() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val r = router(scope, mapOf(dirA to listOf(summary("s1", dirA)), dirB to listOf(summary("s2", dirB))))

        r.handle(SetSessionArchived(dirA, "s1", archived = true), { })
        r.handle(SetSessionArchived(dirB, "s2", archived = true), { })

        val emitted = mutableListOf<Frame>()
        r.handle(ListArchivedSessions, { synchronized(emitted) { emitted += it } })
        val archived = awaitFrame(emitted).filterIsInstance<ArchivedSessions>().single()

        assertEquals(setOf("s1", "s2"), archived.items.map { it.sessionId }.toSet(), "spans projects")
        // each row names its own project, which is how the client groups the view
        assertEquals(setOf(dirA, dirB), archived.items.map { it.cwd }.toSet())
    }

    @Test
    fun acting_from_the_archive_view_answers_with_the_archive_not_a_project_listing() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val r = router(scope, mapOf(dirA to listOf(summary("s1", dirA))))
        r.handle(SetSessionArchived(dirA, "s1", archived = true), { })

        val emitted = mutableListOf<Frame>()
        r.handle(
            SetSessionArchived(dirA, "s1", archived = false, fromArchiveView = true),
            { synchronized(emitted) { emitted += it } },
        )
        val reply = awaitFrame(emitted).single()

        // a Sessions(dirA) reply here would repoint the client's listed directory to whatever project the
        // restored row belonged to — the whole reason fromArchiveView exists
        assertTrue(reply is ArchivedSessions, "expected the archive list, got ${reply::class.simpleName}")
        assertTrue((reply as ArchivedSessions).items.isEmpty(), "the restored row left the archive")
    }

    @Test
    fun a_guest_cannot_mutate_the_archive_and_sees_no_capability() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val r = router(scope, mapOf(dirA to listOf(summary("s1", dirA))))
        val guest = GuestScope(
            roots = listOf(dirA), ownedSessions = setOf("s1"), label = "shared",
            expiresAt = null, tier = dev.ccpocket.protocol.AccessTier.REVIEW,
        )

        val emitted = mutableListOf<Frame>()
        r.handle(
            SetSessionArchived(dirA, "s1", archived = true),
            { synchronized(emitted) { emitted += it } },
            guestScope = guest,
        )
        val sessions = awaitFrame(emitted).single() as Sessions

        assertFalse(sessions.archiveSupported, "a guest client must not render archive affordances")
        assertEquals(listOf("s1"), sessions.items.map { it.sessionId }, "the mutation was a no-op")
        assertTrue(
            !archiveFile.exists() || archiveFile.readText().trim().let { it.isEmpty() || it == "{}" },
            "nothing was written to the owner's store",
        )
    }

    @Test
    fun the_archive_view_strips_opencode_rows_for_clients_that_never_declared_them() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val r = router(
            scope,
            mapOf(dirA to listOf(summary("s1", dirA), summary("s2", dirA, agent = AgentKind.OPENCODE))),
        )
        r.handle(SetSessionArchived(dirA, "s1", archived = true), { })
        r.handle(SetSessionArchived(dirA, "s2", archived = true), { })

        val emitted = mutableListOf<Frame>()
        r.handle(ListArchivedSessions, { synchronized(emitted) { emitted += it } }) // no caps declared
        val archived = awaitFrame(emitted).filterIsInstance<ArchivedSessions>().single()

        // an already-shipped client hard-fails the WHOLE envelope on an unknown AgentKind — the same
        // filter emitSessions applies has to apply here
        assertEquals(listOf("s1"), archived.items.map { it.sessionId })
    }

    @Test
    fun a_guest_cannot_reach_the_cross_project_scan_through_the_archive_view_branch() = runBlocking {
        // emitArchivedSessions is a WHOLE-MACHINE enumeration and takes no guest scope, so the branch that
        // reaches it has to be gated on ownership too — not just the mutation above it. Without this a
        // non-owner would get a full cross-project scan computed on every frame, outside its shared root.
        val scope = CoroutineScope(Dispatchers.Default)
        val r = router(scope, mapOf(dirA to listOf(summary("s1", dirA))))
        val guest = GuestScope(
            roots = listOf(dirA), ownedSessions = setOf("s1"), label = "shared",
            expiresAt = null, tier = dev.ccpocket.protocol.AccessTier.REVIEW,
        )

        val emitted = mutableListOf<Frame>()
        r.handle(
            SetSessionArchived(dirA, "s1", archived = true, fromArchiveView = true),
            { synchronized(emitted) { emitted += it } },
            guestScope = guest,
        )
        val reply = awaitFrame(emitted).single()

        assertTrue(reply is Sessions, "a guest must fall through to its scoped list, got ${reply::class.simpleName}")
        assertTrue(emitted.none { it is ArchivedSessions }, "no cross-project enumeration for a non-owner")
    }

    @Test
    fun a_collaborator_can_neither_archive_nor_enumerate() = runBlocking {
        // A COLLABORATOR arrives with origin == null AND guestScope == null — only collabScope is set — so
        // guarding on the first two alone is vacuous for exactly the weakest credential the product hands
        // out (a link sent to a contact). Sabotage here is silent: hiding the owner's sessions everywhere.
        val scope = CoroutineScope(Dispatchers.Default)
        val r = router(scope, mapOf(dirA to listOf(summary("s1", dirA))))
        val collab = dev.ccpocket.daemon.handoff.CollaboratorScope(deviceId = "dev-collab")

        val emitted = mutableListOf<Frame>()
        r.handle(
            SetSessionArchived(dirA, "s1", archived = true),
            { synchronized(emitted) { emitted += it } },
            collabScope = collab,
        )
        val sessions = awaitFrame(emitted).single() as Sessions
        assertEquals(listOf("s1"), sessions.items.map { it.sessionId }, "the mutation must not have applied")

        val after = mutableListOf<Frame>()
        r.handle(ListArchivedSessions, { synchronized(after) { after += it } }, collabScope = collab)
        kotlinx.coroutines.delay(200)
        assertTrue(synchronized(after) { after.isEmpty() }, "no archive enumeration for a collaborator")
    }

    @Test
    fun the_archive_view_clips_first_prompt_so_one_frame_cannot_overrun_the_relay() = runBlocking {
        // firstPrompt is untruncated on the wire and can be enormous (skill injection has produced ~800KB
        // single messages here). Unlike a per-project Sessions frame this one aggregates the WHOLE machine,
        // and the archive screen refetches on every open — an oversize frame would drop the relay socket in
        // a loop. The view renders title + cwd and never reads firstPrompt, so clipping costs nothing.
        val huge = "x".repeat(50_000)
        val scope = CoroutineScope(Dispatchers.Default)
        val r = router(
            scope,
            mapOf(dirA to listOf(summary("s1", dirA).copy(firstPrompt = huge))),
        )
        r.handle(SetSessionArchived(dirA, "s1", archived = true), { })

        val emitted = mutableListOf<Frame>()
        r.handle(ListArchivedSessions, { synchronized(emitted) { emitted += it } })
        val archived = awaitFrame(emitted).filterIsInstance<ArchivedSessions>().single()

        assertTrue(
            archived.items.single().firstPrompt.length <= RequestRouter.ARCHIVE_PROMPT_CLIP,
            "expected a clipped prompt, got ${archived.items.single().firstPrompt.length} chars",
        )
    }
}
