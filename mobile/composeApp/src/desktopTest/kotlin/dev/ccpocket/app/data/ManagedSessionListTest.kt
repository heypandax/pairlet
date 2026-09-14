package dev.ccpocket.app.data

import dev.ccpocket.app.ui.session.DiscoverDiagnostic
import dev.ccpocket.app.ui.session.ManagedSessionsError
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ManagedAgentStatus
import dev.ccpocket.protocol.ManagedAvailability
import dev.ccpocket.protocol.ManagedMigrationState
import dev.ccpocket.protocol.ManagedSessionEntry
import dev.ccpocket.protocol.SessionSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** #360 stage 2: which rows a project shows once some agents are on the managed list. */
class ManagedSessionListTest {
    private val dir = "/tmp/app" // the spelling the client listed with
    private val canonical = "/private/tmp/app" // the daemon's realpath of that project — NOT what the client compares with

    private fun s(id: String, agent: AgentKind? = null, modified: Long = 1, group: String? = null, cwd: String = dir) =
        SessionSummary(id, "title $id", "", 1, cwd, modified, agent = agent, group = group)

    private val legacy = listOf(
        s("outside-new", modified = 9), // an outside terminal's new Claude session — not a member
        s("c2", modified = 5), s("x1", AgentKind.CODEX, modified = 4), s("c1", modified = 3),
        s("k1", AgentKind.KIMI, modified = 2),
    )

    private fun status(agent: AgentKind?, ready: Boolean) =
        ManagedAgentStatus(agent, if (ready) ManagedMigrationState.READY else ManagedMigrationState.UNINITIALIZED, scanComplete = true)

    /** v2 rows: a live summary carries cwd "" and no last-known fallback. */
    private fun member(
        id: String, agent: AgentKind? = AgentKind.CLAUDE, availability: ManagedAvailability = ManagedAvailability.AVAILABLE,
        group: String? = null, ambiguous: Boolean = false,
    ) = if (availability == ManagedAvailability.AVAILABLE) ManagedSessionEntry(id, agent, availability = availability, summary = s(id, agent, modified = 7, cwd = ""), group = group, groupAmbiguous = ambiguous)
    else ManagedSessionEntry(id, agent, availability = availability, lastKnownTitle = "known $id", lastKnownModified = 42, group = group, groupAmbiguous = ambiguous)

    private fun list(vararg items: ManagedSessionEntry, claudeReady: Boolean = true, codexReady: Boolean = false, extra: List<ManagedAgentStatus> = emptyList()) =
        ManagedProjectList(dir, canonical, 3, listOf(status(AgentKind.CLAUDE, claudeReady), status(AgentKind.CODEX, codexReady)) + extra, items.toList())

    private val both = setOf(AgentKind.CLAUDE, AgentKind.CODEX)

    @Test
    fun without_a_managed_list_or_capability_the_legacy_rows_are_returned_untouched() {
        assertSame(legacy, mergeManagedSessions(legacy, null, both, dir).rows)
        assertSame(legacy, mergeManagedSessions(legacy, list(member("c1")), emptySet(), dir).rows, "no managedAgents = no capability")
        assertSame(legacy, mergeManagedSessions(legacy, list(member("c1"), claudeReady = false), both, dir).rows, "UNINITIALIZED stays legacy")
        assertFalse(mergeManagedSessions(legacy, null, both, dir).loading)
    }

    @Test
    fun a_ready_agent_uses_the_fixed_managed_order_and_hides_outside_sessions_while_other_agents_stay_legacy() {
        val merged = mergeManagedSessions(legacy, list(member("c1"), member("c2")), both, dir)
        assertEquals(listOf("c1", "c2", "x1", "k1"), merged.rows.map { it.sessionId })
        assertTrue(merged.missing.isEmpty())
    }

    @Test
    fun an_empty_cwd_takes_the_daemon_row_of_the_same_session_then_the_requested_workdir_never_the_realpath() {
        val daemonRow = s("c1", cwd = "/tmp/app/sub") // the daemon's own listing of the session
        val merged = mergeManagedSessions(listOf(daemonRow, s("x1", AgentKind.CODEX)), list(member("c1"), member("c7")), both, dir)
        assertEquals("/tmp/app/sub", merged.rows.first { it.sessionId == "c1" }.cwd, "the same id's daemon row wins")
        assertEquals(dir, merged.rows.first { it.sessionId == "c7" }.cwd, "no daemon row: the workdir the client asked with")
        assertTrue(merged.rows.none { it.cwd == canonical }, "the realpath would break cwd == workdir matching (selection, approvals)")
    }

    @Test
    fun a_missing_member_keeps_its_place_with_last_known_data_and_is_flagged() {
        val merged = mergeManagedSessions(legacy, list(member("gone", availability = ManagedAvailability.MISSING, group = "g1"), member("c2")), both, dir)
        val gone = merged.rows.first()
        assertEquals("gone", gone.sessionId)
        assertEquals("known gone", gone.title)
        assertEquals(42L, gone.lastModified)
        assertEquals("g1", gone.group)
        assertEquals(dir, gone.cwd)
        assertEquals(AgentKind.CLAUDE, gone.agent)
        assertEquals(setOf("gone"), merged.missing)
    }

    @Test
    fun an_unknown_availability_member_shows_last_known_data_without_the_missing_flag() {
        val merged = mergeManagedSessions(legacy, list(member("c9", availability = ManagedAvailability.UNKNOWN)), both, dir)
        assertEquals("known c9", merged.rows.first().title)
        assertTrue(merged.missing.isEmpty())
    }

    @Test
    fun the_group_assignment_from_the_entry_wins_and_members_of_non_ready_agents_are_not_pulled_in() {
        val m = list(member("c1", group = "g2"), member("x1", AgentKind.CODEX))
        val merged = mergeManagedSessions(legacy, m, both, dir)
        assertEquals("g2", merged.rows.first { it.sessionId == "c1" }.group)
        assertEquals(listOf("c1", "x1", "k1"), merged.rows.map { it.sessionId }, "codex is UNINITIALIZED: its legacy row stays in legacy order")
    }

    @Test
    fun the_same_id_under_two_ready_agents_is_two_rows_and_both_are_marked_group_unclear() {
        val m = list(member("dup", ambiguous = true), member("dup", AgentKind.CODEX, availability = ManagedAvailability.MISSING, ambiguous = true), codexReady = true)
        val merged = mergeManagedSessions(legacy, m, both, dir)
        assertEquals(listOf("dup" to AgentKind.CLAUDE, "dup" to AgentKind.CODEX), merged.rows.filter { it.sessionId == "dup" }.map { it.sessionId to it.agent })
        assertEquals(setOf("dup", "codex:dup"), merged.ambiguous)
        assertEquals(setOf("codex:dup"), merged.missing, "missing is keyed by agent + id, not by the bare id")
        assertEquals(merged.rows.size, merged.rows.map { it.managedRowKey() }.toSet().size, "row keys stay unique for lazy lists")
    }

    @Test
    fun rows_and_statuses_naming_no_agent_are_dropped_never_guessed() {
        assertSame(legacy, mergeManagedSessions(legacy, list(member("c1"), claudeReady = false, extra = listOf(status(null, ready = true))), both, dir).rows)
        val merged = mergeManagedSessions(legacy, list(member("c1"), member("mystery", agent = null)), both, dir)
        assertEquals(listOf("c1", "x1", "k1"), merged.rows.map { it.sessionId })
    }

    @Test
    fun before_the_first_managed_read_arrives_managed_agents_wait_and_other_agents_show() {
        val merged = mergeManagedSessions(legacy, null, both, dir, awaitingFirstRead = true)
        assertTrue(merged.loading)
        assertEquals(listOf("k1"), merged.rows.map { it.sessionId }, "an outside Claude/Codex session must not flash in and vanish")
        assertSame(legacy, mergeManagedSessions(legacy, null, emptySet(), dir, awaitingFirstRead = true).rows, "no capability: nothing waits")
    }

    @Test
    fun wire_vocabularies_map_to_client_buckets() {
        assertEquals(ManagedSessionsError.DENIED, managedErrorOf("managed_forbidden"))
        assertEquals(ManagedSessionsError.CURSOR_EXPIRED, managedErrorOf("managed_cursor_invalid"))
        assertEquals(ManagedSessionsError.STORE_CORRUPT, managedErrorOf("managed_store_corrupt"))
        assertEquals(ManagedSessionsError.STORE_UNAVAILABLE, managedErrorOf("managed_store_unavailable"))
        assertEquals(ManagedSessionsError.INTERNAL, managedErrorOf("managed_future_code"))
        assertEquals(null, managedDiagnosticOf("complete"))
        assertEquals(DiscoverDiagnostic.PERMISSION, managedDiagnosticOf("permission_denied"))
        assertEquals(DiscoverDiagnostic.UNKNOWN, managedDiagnosticOf("something_new"))
        assertEquals(setOf(AgentKind.CLAUDE, AgentKind.CODEX), managedAgentsOf(listOf("claude", "codex", "future")))
    }
}
