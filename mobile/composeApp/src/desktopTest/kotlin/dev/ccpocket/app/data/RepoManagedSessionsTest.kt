package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.ui.session.DiscoverResult
import dev.ccpocket.app.ui.session.DiscoveredKey
import dev.ccpocket.app.ui.session.ImportResult
import dev.ccpocket.app.ui.session.ManagedScope
import dev.ccpocket.app.ui.session.ManagedSessionsError
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.DiscoverSessions
import dev.ccpocket.protocol.DiscoveredSession
import dev.ccpocket.protocol.DiscoveredSessions
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ImportSession
import dev.ccpocket.protocol.ListManagedSessions
import dev.ccpocket.protocol.ManagedAgentStatus
import dev.ccpocket.protocol.ManagedAvailability
import dev.ccpocket.protocol.ManagedMigrationState
import dev.ccpocket.protocol.ManagedSessionEntry
import dev.ccpocket.protocol.ManagedSessionsState
import dev.ccpocket.protocol.SessionGroup
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.Sessions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #360 stage 2: the repository's managed-list wiring against a stand-in daemon (no transport). */
class RepoManagedSessionsTest {
    private val dir = "/w/app"
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val repo = PocketRepository(scope).apply {
        paired.value = PairedDaemon(relay = "wss://test", accountId = "acct-a", daemonPub = "pk", deviceId = "dev", credential = "c")
    }
    private val sent = mutableListOf<Frame>()
    /** Answers a request synchronously; null = leave it unanswered. */
    private var answer: (Frame) -> Frame? = { null }

    init {
        // demo mode keeps the repository off the network: an UNANSWERED managed request is swallowed there and stays
        // pending (a real live link does not throw on send), instead of reading as a dead link
        repo.demoMode.value = true
        repo.onSendForTest = { f ->
            sent += f
            val reply = answer(f)
            if (reply != null) {
                repo.receiveForTest(reply)
                throw CancellationException("answered — no transport in this test")
            }
            if (!(f is ListManagedSessions || f is DiscoverSessions || f is ImportSession)) throw CancellationException("no transport in this test")
        }
    }

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun s(id: String, agent: AgentKind? = null, modified: Long = 1, group: String? = null, cwd: String = dir) =
        SessionSummary(id, "t-$id", "", 1, cwd, modified, agent = agent, group = group)

    private val legacyRows = listOf(s("outside", modified = 9), s("c2", modified = 5), s("x1", AgentKind.CODEX, modified = 4), s("c1", modified = 3))
    private val legacyIds = listOf("outside", "c2", "x1", "c1")

    private fun capable(agents: List<String> = listOf("claude", "codex")) =
        repo.receiveForTest(DaemonInfo(supportsManagedSessions = true, managedAgents = agents))

    /** One page of a v2 all-agents list: Claude READY, Codex UNINITIALIZED; live rows carry cwd "". */
    private fun readyState(
        requestId: String?, revision: Long, vararg ids: String,
        workdir: String = dir, complete: Boolean = true, nextCursor: String? = null, withStatuses: Boolean = true,
    ) = ManagedSessionsState(
        requestId = requestId, workdir = workdir, canonicalWorkdir = workdir, revision = revision, allAgents = true,
        agents = if (!withStatuses) emptyList() else listOf(
            ManagedAgentStatus(AgentKind.CLAUDE, ManagedMigrationState.READY, scanComplete = true),
            ManagedAgentStatus(AgentKind.CODEX, ManagedMigrationState.UNINITIALIZED, scanComplete = true),
        ),
        items = ids.map { ManagedSessionEntry(it, AgentKind.CLAUDE, availability = ManagedAvailability.AVAILABLE, summary = s(it, modified = 7, cwd = "")) },
        nextCursor = nextCursor, complete = complete,
    )

    private fun listed() = repo.sessions.map { it.sessionId }
    private fun listRequests() = sent.filterIsInstance<ListManagedSessions>()
    private fun lastListRequest() = listRequests().last()

    private fun waitUntil(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(10) }
        return cond()
    }

    // ── capability absent: exactly the old behaviour ────────────────────────────────────────────────────

    @Test
    fun without_the_capability_sessions_are_the_daemon_rows_and_no_managed_frame_is_sent() {
        repo.receiveForTest(DaemonInfo())
        repo.receiveForTest(Sessions(dir, legacyRows + s("c1"), groups = listOf(SessionGroup("g", "G", 0))))
        assertEquals(legacyIds, listed())
        assertFalse(repo.managedListLoading.value)
        assertTrue(sent.none { it is ListManagedSessions }, "an older daemon must never see a managed frame: $sent")
        assertFalse(repo.managedImportAvailable())
        repo.receiveForTest(readyState(null, 9, "c1")) // a stray managed frame changes nothing
        assertEquals(legacyIds, listed())
        assertTrue(sent.none { it is ListManagedSessions })
    }

    // ── capability present ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun a_ready_agent_renders_from_the_managed_list_in_fixed_order_while_others_stay_legacy() {
        answer = { f -> if (f is ListManagedSessions) readyState(f.requestId, 1, "c1", "c2") else null }
        capable()
        repo.receiveForTest(Sessions(dir, legacyRows, groups = emptyList()))
        assertEquals(listOf("c1", "c2", "x1"), listed(), "outside session hidden, Claude in managed order, Codex legacy")
        assertTrue(repo.managedImportAvailable())
        val req = lastListRequest()
        assertTrue(req.allAgents && req.agent == null && req.cursor == null, "first page of an all-agents read: $req")
        assertEquals(dir, repo.sessions.first().cwd, "a managed row's empty cwd resolves to the canonical project")

        // an outside terminal touches c2 and starts another session: legacy order changes, managed order does not
        repo.receiveForTest(Sessions(dir, listOf(s("outside2", modified = 20), s("c2", modified = 19)) + legacyRows, groups = emptyList()))
        assertEquals(listOf("c1", "c2", "x1"), listed())
    }

    @Test
    fun the_list_is_read_page_by_page_and_shown_only_once_complete() {
        answer = { f ->
            if (f is ListManagedSessions) when (f.cursor) {
                null -> readyState(f.requestId, 1, "c1", complete = false, nextCursor = "p2")
                "p2" -> readyState(f.requestId, 1, "c2", complete = true, withStatuses = false)
                else -> null
            } else null
        }
        capable()
        repo.receiveForTest(Sessions(dir, legacyRows, groups = emptyList()))
        assertEquals(listOf(null, "p2"), listRequests().map { it.cursor })
        assertTrue(listRequests().all { it.allAgents && it.agent == null })
        assertEquals(listOf("c1", "c2", "x1"), listed())
    }

    @Test
    fun an_incomplete_page_without_a_cursor_is_a_failed_read_and_falls_back_to_legacy() {
        answer = { f -> if (f is ListManagedSessions) readyState(f.requestId, 1, "c1", complete = false, nextCursor = null) else null }
        capable()
        repo.receiveForTest(Sessions(dir, legacyRows, groups = emptyList()))
        assertEquals(legacyIds, listed(), "complete defaults to false: a missing flag is never 'all read'")
        assertFalse(repo.managedListLoading.value)
    }

    @Test
    fun an_invalid_cursor_restarts_the_read_from_the_first_page() {
        var restarted = false
        answer = { f ->
            if (f is ListManagedSessions) when {
                f.cursor == null && !restarted -> readyState(f.requestId, 1, "old", complete = false, nextCursor = "stale")
                f.cursor == "stale" -> { restarted = true; ManagedSessionsState(requestId = f.requestId, workdir = dir, allAgents = true, error = "managed_cursor_invalid") }
                f.cursor == null -> readyState(f.requestId, 2, "c1", "c2")
                else -> null
            } else null
        }
        capable()
        repo.receiveForTest(Sessions(dir, legacyRows, groups = emptyList()))
        assertEquals(listOf(null, "stale", null), listRequests().map { it.cursor })
        assertEquals(listOf("c1", "c2", "x1"), listed(), "rows of the abandoned first page are not kept")
    }

    @Test
    fun until_the_first_managed_read_arrives_managed_agents_show_a_loading_state_not_outside_sessions() {
        capable(listOf("claude"))
        repo.receiveForTest(Sessions(dir, legacyRows, groups = emptyList()))
        assertTrue(repo.managedListLoading.value)
        assertEquals(listOf("x1"), listed(), "Claude waits for its list; Codex (not managed here) shows as before")
        repo.receiveForTest(readyState(lastListRequest().requestId, 1, "c1"))
        assertFalse(repo.managedListLoading.value)
        assertEquals(listOf("c1", "x1"), listed())
    }

    @Test
    fun a_missing_member_is_flagged_for_the_unavailable_marker() {
        answer = { f ->
            if (f is ListManagedSessions) readyState(f.requestId, 1, "c1").let { st ->
                st.copy(items = st.items!! + ManagedSessionEntry("gone", AgentKind.CLAUDE, availability = ManagedAvailability.MISSING, lastKnownTitle = "old"))
            } else null
        }
        capable()
        repo.receiveForTest(Sessions(dir, legacyRows, groups = emptyList()))
        assertEquals(listOf("c1", "gone", "x1"), listed())
        assertEquals(setOf("gone"), repo.managedMissing.value)
    }

    @Test
    fun a_refused_list_after_a_success_keeps_the_accepted_list_never_empty() {
        answer = { f -> if (f is ListManagedSessions) readyState(f.requestId, 1, "c1") else null }
        capable()
        repo.receiveForTest(Sessions(dir, legacyRows, groups = emptyList()))
        assertEquals(listOf("c1", "x1"), listed())
        answer = { f -> if (f is ListManagedSessions) ManagedSessionsState(requestId = f.requestId, workdir = dir, allAgents = true, error = "managed_store_unavailable") else null }
        repo.receiveForTest(Sessions(dir, legacyRows, groups = emptyList()))
        assertEquals(listOf("c1", "x1"), listed(), "items=null is 'cannot read': the previous display stays")
    }

    @Test
    fun an_unanswered_first_list_request_times_out_to_the_legacy_rows() {
        repo.managedListPageTimeoutMs = 50
        capable()
        repo.receiveForTest(Sessions(dir, legacyRows, groups = emptyList())) // an older-behaving daemon drops the frame
        assertTrue(waitUntil(2_000) { listed() == legacyIds && !repo.managedListLoading.value }, "still ${listed()} after the timeout")
        // a later re-list while the capability keeps failing does not blank the managed agents again
        repo.receiveForTest(Sessions(dir, legacyRows, groups = emptyList()))
        assertEquals(legacyIds, listed())
    }

    @Test
    fun unknown_request_ids_other_projects_and_stale_pushes_are_dropped() {
        capable()
        repo.receiveForTest(Sessions(dir, legacyRows, groups = emptyList()))
        repo.receiveForTest(readyState("nobody-asked", 1, "c1"))
        assertNull(repo.managedList.value)
        repo.receiveForTest(readyState(lastListRequest().requestId, 5, "c1", "c2"))
        assertEquals(listOf("c1", "c2", "x1"), listed())

        val before = listRequests().size
        repo.receiveForTest(readyState(null, 4, "c2")) // older push: nothing to re-read
        repo.receiveForTest(readyState(null, 99, "zzz", workdir = "/w/other")) // another project's push
        assertEquals(before, listRequests().size)
        assertEquals(listOf("c1", "c2", "x1"), listed(), "a push's own items are never applied")

        repo.receiveForTest(readyState(null, 6, "c2", "c1")) // newer push → re-read the whole list
        assertEquals(before + 1, listRequests().size)
        repo.receiveForTest(readyState(lastListRequest().requestId, 6, "c2", "c1"))
        assertEquals(listOf("c2", "c1", "x1"), listed())
    }

    @Test
    fun a_reply_that_lands_after_a_disconnect_is_dropped() {
        capable()
        repo.receiveForTest(Sessions(dir, legacyRows, groups = emptyList()))
        val rid = lastListRequest().requestId
        repo.disconnect()
        repo.demoMode.value = true // disconnect() leaves the demo; stay off the network
        capable()
        repo.listSessions(dir) // #349: after a disconnect a list is only accepted for a project the user browses to
        repo.receiveForTest(Sessions(dir, legacyRows, groups = emptyList()))
        repo.receiveForTest(readyState(rid, 1, "c1")) // the old connection's answer
        assertNull(repo.managedList.value, "the old link's reply is never accepted")
    }

    @Test
    fun disconnect_forgets_the_groups_so_the_next_computer_does_not_inherit_them() {
        repo.receiveForTest(Sessions(dir, legacyRows, groups = listOf(SessionGroup("g", "Old machine group", 0))))
        assertEquals(1, repo.sessionGroups.size)
        repo.disconnect()
        assertTrue(repo.sessionGroups.isEmpty())
    }

    // ── gateway over the repository ───────────────────────────────────────────────────────────────────

    @Test
    fun the_gateway_maps_discovery_pages_and_refusals() = runBlocking {
        capable()
        val gw = RepoManagedSessionsGateway(repo)
        val here = ManagedScope("acct-a", dir)
        answer = { f ->
            if (f is DiscoverSessions) DiscoveredSessions(
                f.requestId, f.workdir, f.agent,
                items = listOf(
                    DiscoveredSession("n1", AgentKind.CLAUDE, title = "T", firstPrompt = "P", lastModified = 3, alreadyManaged = true),
                    DiscoveredSession("undecodable", agent = null, title = "?"),
                ),
                nextCursor = "c2", complete = false, diagnostic = null,
            ) else null
        }
        val page = assertIs<DiscoverResult.Page>(gw.discover(here, AgentKind.CLAUDE, "q", null))
        assertEquals("c2", page.nextCursor)
        assertTrue(page.complete, "more pages is not a partial scan")
        assertEquals(listOf("n1"), page.items.map { it.nativeId }, "a row naming no agent is dropped")
        assertTrue(page.items.single().alreadyManaged)
        val req = sent.filterIsInstance<DiscoverSessions>().single()
        assertEquals("q", req.query)
        assertEquals(AgentKind.CLAUDE, req.agent, "requests always name their agent")

        answer = { f -> if (f is DiscoverSessions) DiscoveredSessions(f.requestId, f.workdir, f.agent, complete = false, diagnostic = "truncated") else null }
        assertFalse(assertIs<DiscoverResult.Page>(gw.discover(here, AgentKind.CLAUDE, "", null)).complete)

        answer = { f -> if (f is ImportSession) ManagedSessionsState(f.requestId, f.workdir, f.agent, error = "managed_forbidden") else null }
        assertEquals(ImportResult.Failure(ManagedSessionsError.DENIED), gw.import(here, AgentKind.CLAUDE, "n1"))
        assertEquals(AgentKind.CLAUDE, sent.filterIsInstance<ImportSession>().last().agent)

        // the reply's agent is not trusted: the key is the one we asked for
        answer = { f -> if (f is ImportSession) ManagedSessionsState(f.requestId, f.workdir, agent = null, sessionId = f.sessionId, alreadyManaged = true) else null }
        assertEquals(ImportResult.Imported(DiscoveredKey(AgentKind.CLAUDE, "n1"), true), gw.import(here, AgentKind.CLAUDE, "n1"))

        assertEquals(DiscoverResult.Failure(ManagedSessionsError.UNSUPPORTED), gw.discover(here, AgentKind.KIMI, "", null))
        assertEquals(DiscoverResult.Failure(ManagedSessionsError.DISCONNECTED), gw.discover(ManagedScope("acct-other", dir), AgentKind.CLAUDE, "", null))
    }

    @Test
    fun a_successful_import_for_the_listed_project_re_reads_its_managed_list() = runBlocking {
        // the first list read lands first: a read still running would only be marked dirty, not re-sent (#360 P2-1)
        answer = { f -> if (f is ListManagedSessions) readyState(f.requestId, 1, "c1") else null }
        capable()
        repo.receiveForTest(Sessions(dir, legacyRows, groups = emptyList()))
        val before = listRequests().size
        answer = { f ->
            when (f) {
                is ImportSession -> ManagedSessionsState(f.requestId, f.workdir, f.agent, sessionId = f.sessionId, changed = true)
                is ListManagedSessions -> readyState(f.requestId, 2, "c9", "c1")
                else -> null
            }
        }
        RepoManagedSessionsGateway(repo).import(ManagedScope("acct-a", dir), AgentKind.CLAUDE, "c9")
        assertEquals(before + 1, listRequests().size)
    }

    @Test
    fun a_corrupt_store_reads_as_read_only_through_the_real_gateway() = runBlocking {
        capable()
        answer = { f ->
            if (f is ListManagedSessions) ManagedSessionsState(
                requestId = f.requestId, workdir = dir, allAgents = true, readOnly = true, error = "managed_store_corrupt",
            ) else null
        }
        assertEquals(
            dev.ccpocket.app.ui.session.ManagedStatusResult.Statuses(emptyList(), readOnly = true),
            RepoManagedSessionsGateway(repo).status(ManagedScope("acct-a", dir)),
            "agents/items are null for a corrupt store — that is 'read-only', not a failed read",
        )
    }

    @Test
    fun a_pending_gateway_call_ends_as_disconnected_when_the_link_drops() = runBlocking {
        capable()
        val gw = RepoManagedSessionsGateway(repo)
        val call = scope.async { gw.discover(ManagedScope("acct-a", dir), AgentKind.CLAUDE, "", null) }
        repo.disconnect()
        assertEquals(DiscoverResult.Failure(ManagedSessionsError.DISCONNECTED), call.await())
    }
}
