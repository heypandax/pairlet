package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.BindingRole
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.DiscoverSessions
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ImportSession
import dev.ccpocket.protocol.ListManagedSessions
import dev.ccpocket.protocol.ManagedAgentStatus
import dev.ccpocket.protocol.ManagedAvailability
import dev.ccpocket.protocol.ManagedMigrationState
import dev.ccpocket.protocol.ManagedSessionEntry
import dev.ccpocket.protocol.ManagedSessionsState
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.Sessions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #360 stage 2 with a daemon that answers LATE: every managed request is held until the test answers it by hand, so
 * the orders a real link produces — a re-list or a push while a read is running, a project switch before the answer,
 * a failure after a success — are driven one step at a time.
 */
class RepoManagedSessionsAsyncTest {
    private val dir = "/w/app"
    private val other = "/w/other"
    private val scopes = mutableListOf<CoroutineScope>()
    private val sent = mutableListOf<Frame>()

    @AfterTest
    fun tearDown() = scopes.forEach { it.cancel() }

    private fun newRepo(role: BindingRole = BindingRole.OWNER): PocketRepository {
        val scope = CoroutineScope(Dispatchers.Unconfined).also { scopes += it }
        return PocketRepository(scope).apply {
            demoMode.value = true // unanswered managed frames are swallowed by the demo loop and stay pending
            paired.value = PairedDaemon(relay = "wss://test", accountId = "acct-a", daemonPub = "pk", deviceId = "dev", credential = "c", role = role)
            onSendForTest = { f ->
                sent += f
                val heldForTheTest = f is ListManagedSessions || f is DiscoverSessions || f is ImportSession || f is dev.ccpocket.protocol.RemoveManagedSession
                if (!heldForTheTest) throw CancellationException("no transport in this test")
            }
        }
    }

    private fun s(id: String, agent: AgentKind? = null, cwd: String = dir) = SessionSummary(id, "t-$id", "", 1, cwd, 1, agent = agent)

    private val legacy = listOf(s("outside"), s("c1"), s("x1", AgentKind.CODEX))
    private val legacyIds = listOf("outside", "c1", "x1")

    private fun PocketRepository.capable(vararg agents: String = arrayOf("claude")) =
        receiveForTest(DaemonInfo(supportsManagedSessions = true, managedAgents = agents.toList()))

    private fun PocketRepository.listed() = sessions.map { it.sessionId }

    private fun requests() = sent.filterIsInstance<ListManagedSessions>()

    private fun PocketRepository.answer(
        req: ListManagedSessions, vararg ids: String, revision: Long = 1, complete: Boolean = true, nextCursor: String? = null,
        error: String? = null, workdir: String = req.workdir,
    ) = receiveForTest(
        if (error != null) ManagedSessionsState(requestId = req.requestId, workdir = workdir, allAgents = true, error = error)
        else ManagedSessionsState(
            requestId = req.requestId, workdir = workdir, canonicalWorkdir = workdir, revision = revision, allAgents = true,
            agents = listOf(ManagedAgentStatus(AgentKind.CLAUDE, ManagedMigrationState.READY, scanComplete = true)),
            items = ids.map { ManagedSessionEntry(it, AgentKind.CLAUDE, availability = ManagedAvailability.AVAILABLE, summary = s(it, cwd = "")) },
            nextCursor = nextCursor, complete = complete,
        ),
    )

    private fun waitUntil(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(10) }
        return cond()
    }

    @Test
    fun re_lists_during_a_read_do_not_restart_it_they_re_read_once_after_it_lands() {
        val repo = newRepo()
        repo.capable()
        repo.receiveForTest(Sessions(dir, legacy, groups = emptyList()))
        repeat(3) { repo.receiveForTest(Sessions(dir, legacy, groups = emptyList())) }
        assertEquals(1, requests().size, "a slower daemon than the re-list rate must still get to finish its first read")
        assertTrue(repo.managedListLoading.value)

        repo.answer(requests()[0], "c1")
        assertEquals(listOf("c1", "x1"), repo.listed())
        assertFalse(repo.managedListLoading.value)
        assertEquals(2, requests().size, "exactly one follow-up read for everything that arrived meanwhile")
        repo.answer(requests()[1], "c1")
        assertEquals(2, requests().size)
    }

    @Test
    fun a_push_during_a_read_is_folded_into_one_follow_up_read() {
        val repo = newRepo()
        repo.capable()
        repo.receiveForTest(Sessions(dir, legacy, groups = emptyList()))
        repo.receiveForTest(ManagedSessionsState(workdir = dir, canonicalWorkdir = dir, revision = 7, allAgents = true))
        assertEquals(1, requests().size)
        repo.answer(requests()[0], "c1")
        assertEquals(2, requests().size)
    }

    @Test
    fun a_project_that_read_successfully_keeps_its_list_when_a_later_read_fails_or_times_out() {
        val repo = newRepo()
        repo.managedListPageTimeoutMs = 50
        repo.capable()
        repo.receiveForTest(Sessions(dir, legacy, groups = emptyList()))
        repo.answer(requests()[0], "c1")
        assertEquals(listOf("c1", "x1"), repo.listed())

        repo.receiveForTest(Sessions(dir, legacy, groups = emptyList()))
        repo.answer(requests().last(), error = "managed_store_unavailable")
        assertEquals(listOf("c1", "x1"), repo.listed(), "the previously accepted list stays on screen")

        repo.receiveForTest(Sessions(dir, legacy, groups = emptyList())) // unanswered: times out
        Thread.sleep(250)
        assertEquals(listOf("c1", "x1"), repo.listed())
        assertFalse(repo.managedListLoading.value)
    }

    @Test
    fun a_project_that_never_read_successfully_falls_back_to_the_daemon_rows_on_failure() {
        val repo = newRepo()
        repo.capable()
        repo.receiveForTest(Sessions(dir, legacy, groups = emptyList()))
        repo.answer(requests()[0], error = "managed_store_unavailable")
        assertEquals(legacyIds, repo.listed())
        assertFalse(repo.managedListLoading.value)
    }

    @Test
    fun the_loading_state_is_bounded_and_the_read_keeps_going_behind_the_daemon_rows() {
        val repo = newRepo()
        repo.managedLoadingMaxMs = 50
        repo.capable()
        repo.receiveForTest(Sessions(dir, legacy, groups = emptyList()))
        assertTrue(repo.managedListLoading.value)
        assertTrue(waitUntil(2_000) { !repo.managedListLoading.value && repo.listed() == legacyIds }, "still ${repo.listed()}")
        repo.answer(requests()[0], "c1") // the read was never cancelled
        assertEquals(listOf("c1", "x1"), repo.listed())
    }

    @Test
    fun guest_and_collaborator_bindings_never_send_managed_frames_or_wait() {
        for (role in listOf(BindingRole.GUEST, BindingRole.COLLABORATOR)) {
            sent.clear()
            val repo = newRepo(role)
            repo.capable()
            repo.receiveForTest(Sessions(dir, legacy, groups = emptyList()))
            assertTrue(requests().isEmpty(), "$role sent $sent")
            assertFalse(repo.managedListLoading.value, "$role must not wait for a list it may not read")
            assertEquals(legacyIds, repo.listed())
            assertFalse(repo.managedImportAvailable())
        }
    }

    @Test
    fun reconnecting_to_the_same_computer_reads_the_list_again() {
        val repo = newRepo()
        repo.capable()
        repo.receiveForTest(Sessions(dir, legacy, groups = emptyList()))
        repo.answer(requests()[0], "c1")
        repo.disconnect()
        repo.demoMode.value = true
        repo.capable()
        repo.listSessions(dir)
        repo.receiveForTest(Sessions(dir, legacy, groups = emptyList()))
        assertEquals(2, requests().size)
        assertTrue(repo.managedListLoading.value, "a new link proves nothing about the old list")
        repo.answer(requests()[1], "c1")
        assertEquals(listOf("c1", "x1"), repo.listed())
    }

    @Test
    fun a_change_of_managed_agents_during_a_read_drops_its_answer_and_reads_again() {
        val repo = newRepo()
        repo.capable("claude")
        repo.receiveForTest(Sessions(dir, legacy, groups = emptyList()))
        val first = requests().single()
        repo.capable("claude", "codex")
        assertEquals(2, requests().size)
        repo.answer(first, "c1")
        assertNull(repo.managedList.value, "the answer of the retired read is dropped")
        repo.answer(requests()[1], "c1")
        assertEquals(listOf("c1", "x1"), repo.listed(), "Codex is managed now but not READY in this answer: its daemon row stays")
    }

    @Test
    fun a_page_timing_out_in_the_middle_never_shows_a_partial_list() {
        val repo = newRepo()
        repo.managedListPageTimeoutMs = 50
        repo.capable()
        repo.receiveForTest(Sessions(dir, legacy, groups = emptyList()))
        repo.answer(requests()[0], "c1", complete = false, nextCursor = "p2")
        assertEquals("p2", requests()[1].cursor)
        assertTrue(waitUntil(2_000) { !repo.managedListLoading.value }, "the timed-out page ends the read")
        assertNull(repo.managedList.value)
        assertEquals(legacyIds, repo.listed())
    }

    @Test
    fun while_a_failing_read_keeps_the_last_list_a_session_this_app_created_and_running_sessions_stay_visible() {
        val repo = newRepo()
        repo.capable()
        repo.receiveForTest(Sessions(dir, legacy, groups = emptyList()))
        repo.answer(requests()[0], "c1")
        // the list stops being readable
        repo.receiveForTest(Sessions(dir, legacy, groups = emptyList()))
        repo.answer(requests().last(), error = "managed_store_unavailable")
        assertTrue(repo.managedListStale.value, "the list shown is the last result — say so")

        // this app starts a session; its registration never reaches the store the client can read
        repo.createHere("new1")
        val busy = s("busy1").copy(busy = true)
        repo.receiveForTest(Sessions(dir, legacy + s("new1") + busy, groups = emptyList()))
        repo.answer(requests().last(), error = "managed_store_unavailable")

        assertTrue("new1" in repo.listed(), "a live session this app created must not be hidden: ${repo.listed()}")
        assertTrue("busy1" in repo.listed(), "a running session must not be hidden")
        assertFalse("outside" in repo.listed(), "an unregistered outside session still stays out")
        assertTrue(repo.managedListStale.value)

        repo.receiveForTest(Sessions(dir, legacy, groups = emptyList()))
        repo.answer(requests().last(), "c1", revision = 2)
        assertFalse(repo.managedListStale.value, "a successful read ends the stale state")
    }

    /** Open a session from this app and let its SessionLive land, then leave the chat. [resumeId] null = brand-new. */
    private fun PocketRepository.openHere(id: String, resumeId: String?) {
        openSession(dir, resumeId, title = "t-$id", agent = AgentKind.CLAUDE)
        convoId.value = "cv-$id"
        receiveForTest(dev.ccpocket.protocol.SessionLive("cv-$id", dir, id, executing = false))
        convoId.value = null; sessionKey.value = null
    }

    private fun PocketRepository.createHere(id: String) = openHere(id, resumeId = null)

    /** Success, then a failing read: the project shows its last accepted list ([c1]). */
    private fun PocketRepository.staleAfterSuccess() {
        capable()
        receiveForTest(Sessions(dir, legacy, groups = emptyList()))
        answer(requests()[0], "c1")
        receiveForTest(Sessions(dir, legacy, groups = emptyList()))
        answer(requests().last(), error = "managed_store_unavailable")
        assertTrue(managedListStale.value)
    }

    @Test
    fun a_resumed_session_is_not_kept_and_a_removed_session_created_here_is_not_brought_back() = kotlinx.coroutines.runBlocking {
        val repo = newRepo()
        repo.staleAfterSuccess()
        repo.openHere("old1", resumeId = "old1") // opening an existing session is not creating one
        repo.createHere("new1")

        var removed: dev.ccpocket.app.ui.session.RemoveResult? = null
        repo.removeManagedMember(dir, AgentKind.CLAUDE, "new1") { removed = it }
        val remove = sent.filterIsInstance<dev.ccpocket.protocol.RemoveManagedSession>().single()
        repo.receiveForTest(ManagedSessionsState(remove.requestId, dir, AgentKind.CLAUDE, sessionId = "new1", changed = true))
        assertEquals(dev.ccpocket.app.ui.session.RemoveResult.Removed, removed)
        repo.answer(requests().last(), error = "managed_store_unavailable") // the re-read after removal still fails

        repo.receiveForTest(Sessions(dir, legacy + s("old1") + s("new1"), groups = emptyList()))
        repo.answer(requests().last(), error = "managed_store_unavailable")
        assertFalse("old1" in repo.listed(), "a merely resumed session is not 'created here': ${repo.listed()}")
        assertFalse("new1" in repo.listed(), "a session the user removed from the list does not come back: ${repo.listed()}")
    }

    @Test
    fun a_disconnect_forgets_the_sessions_created_here() {
        val repo = newRepo()
        repo.staleAfterSuccess()
        repo.createHere("new1")
        repo.disconnect()
        repo.demoMode.value = true
        repo.capable()
        repo.listSessions(dir)
        repo.receiveForTest(Sessions(dir, legacy + s("new1"), groups = emptyList()))
        repo.answer(requests().last(), "c1")
        repo.receiveForTest(Sessions(dir, legacy + s("new1"), groups = emptyList()))
        repo.answer(requests().last(), error = "managed_store_unavailable")
        assertTrue(repo.managedListStale.value)
        assertFalse("new1" in repo.listed(), "the previous link's 'created here' ids do not carry over: ${repo.listed()}")
    }

    @Test
    fun a_late_success_for_a_timed_out_change_still_triggers_a_re_read() = kotlinx.coroutines.runBlocking {
        val repo = newRepo()
        repo.managedCallTimeoutMs = 50
        repo.capable()
        repo.receiveForTest(Sessions(dir, legacy, groups = emptyList()))
        repo.answer(requests()[0], "c1")
        RepoManagedSessionsGateway(repo).import(dev.ccpocket.app.ui.session.ManagedScope("acct-a", dir), AgentKind.CLAUDE, "c9")
        val importReq = sent.filterIsInstance<ImportSession>().last()
        val afterTimeout = requests().size // the timeout's own re-read is still running
        repo.receiveForTest(ManagedSessionsState(importReq.requestId, dir, AgentKind.CLAUDE, sessionId = "c9", changed = true)) // arrives late
        repo.answer(requests().last(), "c1", revision = 1) // that re-read raced ahead of the commit
        assertEquals(afterTimeout + 1, requests().size, "the late success is not dropped: one more read follows")
        repo.answer(requests().last(), "c9", "c1", revision = 2)
        assertEquals(listOf("c9", "c1", "x1"), repo.listed())
    }

    @Test
    fun a_change_that_times_out_is_unconfirmed_and_the_list_is_read_again() = kotlinx.coroutines.runBlocking {
        val repo = newRepo()
        repo.managedCallTimeoutMs = 50
        repo.capable()
        repo.receiveForTest(Sessions(dir, legacy, groups = emptyList()))
        repo.answer(requests()[0], "c1")
        val before = requests().size
        val result = RepoManagedSessionsGateway(repo).import(dev.ccpocket.app.ui.session.ManagedScope("acct-a", dir), AgentKind.CLAUDE, "c9")
        assertEquals(dev.ccpocket.app.ui.session.ImportResult.Failure(dev.ccpocket.app.ui.session.ManagedSessionsError.UNCONFIRMED), result)
        assertEquals(before + 1, requests().size, "the daemon may have committed it: the list is re-read")
        repo.answer(requests().last(), "c9", "c1", revision = 2) // it had
        assertEquals(listOf("c9", "c1", "x1"), repo.listed())
    }

    @Test
    fun switching_project_during_a_read_keeps_its_late_answer_off_the_new_project() {
        val repo = newRepo()
        repo.capable()
        repo.receiveForTest(Sessions(dir, legacy, groups = emptyList()))
        val forApp = requests().single()
        repo.listSessions(other)
        repo.receiveForTest(Sessions(other, listOf(s("o1", cwd = other), s("ox", AgentKind.CODEX, cwd = other)), groups = emptyList()))
        repo.answer(forApp, "c1")
        assertEquals(listOf("ox"), repo.listed(), "the other project still waits for its own list")
        assertNull(repo.managedList.value)
        val accepted = repo.managedAccepted.value
        assertEquals(dir, accepted?.workdir, "the late answer is handed to the RECENT snapshot of the project it belongs to")
        assertEquals(listOf("c1", "x1"), accepted?.rows?.map { it.sessionId })
    }
}
