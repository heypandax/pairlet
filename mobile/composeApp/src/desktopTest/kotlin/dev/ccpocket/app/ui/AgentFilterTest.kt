package dev.ccpocket.app.ui

import dev.ccpocket.app.data.ALL_AGENTS
import dev.ccpocket.app.data.agentFilterIsAll
import dev.ccpocket.app.data.encodeAgentFilter
import dev.ccpocket.app.data.parseAgentFilter
import dev.ccpocket.app.data.toggleAgentFilter
import dev.ccpocket.protocol.ActiveSession
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.DirectoryEntry
import dev.ccpocket.protocol.SessionSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The agent filter as a SET (issue #248) — migration, round-trip, the toggle rules, and the two filter call
 * sites under a MULTI-agent selection, which the single-value control could never express.
 *
 * The migration cases matter most: this key has been on real phones since #31, so every legacy shape has to
 * land on the same rows the old build showed, and "unset" has to mean "no filter" or an upgrade silently
 * empties someone's Projects list.
 */
class AgentFilterTest {

    @Test
    fun legacy_values_migrate_without_changing_what_the_user_sees() {
        assertEquals(ALL_AGENTS, parseAgentFilter(null))          // never set → no filter
        assertEquals(ALL_AGENTS, parseAgentFilter(""))
        assertEquals(ALL_AGENTS, parseAgentFilter("both"))        // the pre-#248 "show everything" value
        assertEquals(setOf(AgentKind.CLAUDE), parseAgentFilter("claude"))
        assertEquals(setOf(AgentKind.CODEX), parseAgentFilter("codex"))
        assertEquals(setOf(AgentKind.OPENCODE), parseAgentFilter("opencode"))
        assertEquals(setOf(AgentKind.ZCODE), parseAgentFilter("zcode"))
        // a token this build doesn't know (older/newer peer, corruption) must widen, never blank the list
        assertEquals(ALL_AGENTS, parseAgentFilter("gemini"))
        assertEquals(setOf(AgentKind.CODEX), parseAgentFilter("gemini,codex"))
    }

    @Test
    fun multi_select_round_trips_and_stays_readable_to_an_older_build() {
        assertEquals("both", encodeAgentFilter(ALL_AGENTS))                       // downgrade → "show all"
        assertEquals("both", encodeAgentFilter(emptySet()))
        assertEquals("claude", encodeAgentFilter(setOf(AgentKind.CLAUDE)))        // byte-identical to the old radio
        assertEquals("claude,codex", encodeAgentFilter(setOf(AgentKind.CODEX, AgentKind.CLAUDE))) // enum order, not insertion
        val multi = setOf(AgentKind.CLAUDE, AgentKind.ZCODE)
        assertEquals(multi, parseAgentFilter(encodeAgentFilter(multi)))
    }

    @Test
    fun no_filter_is_one_predicate_including_the_impossible_empty_set() {
        assertTrue(agentFilterIsAll(ALL_AGENTS))
        assertTrue(agentFilterIsAll(emptySet())) // fails open rather than hiding every row
        assertFalse(agentFilterIsAll(setOf(AgentKind.CLAUDE)))
        assertFalse(agentFilterIsAll(ALL_AGENTS - AgentKind.KIMI))
    }

    @Test
    fun toggling_narrows_from_all_and_can_never_reach_an_empty_selection() {
        // tapping an agent while "All" is on narrows to it (a full set minus one would be a silent surprise)
        assertEquals(setOf(AgentKind.CODEX), toggleAgentFilter(ALL_AGENTS, AgentKind.CODEX))
        // then it composes, which is the whole point of #248
        assertEquals(
            setOf(AgentKind.CODEX, AgentKind.CLAUDE),
            toggleAgentFilter(setOf(AgentKind.CODEX), AgentKind.CLAUDE),
        )
        assertEquals(
            setOf(AgentKind.CODEX),
            toggleAgentFilter(setOf(AgentKind.CODEX, AgentKind.CLAUDE), AgentKind.CLAUDE),
        )
        // turning the LAST one off returns to "All" — an empty list with no way back is not a state
        assertEquals(ALL_AGENTS, toggleAgentFilter(setOf(AgentKind.CODEX), AgentKind.CODEX))
    }

    @Test
    fun sessions_filter_keeps_every_selected_backend_and_nulls_count_as_claude() {
        val legacy = SessionSummary("l", "L", "", 0, "/l", 1) // pre-agent daemon: no backend stamped
        val claude = SessionSummary("c", "C", "", 0, "/c", 1, agent = AgentKind.CLAUDE)
        val codex = SessionSummary("x", "X", "", 0, "/x", 1, agent = AgentKind.CODEX)
        val kimi = SessionSummary("k", "K", "", 0, "/k", 1, agent = AgentKind.KIMI)
        val all = listOf(legacy, claude, codex, kimi)

        assertEquals(all, filterSessionsByAgent(all, ALL_AGENTS))
        assertEquals(listOf(legacy, claude, codex), filterSessionsByAgent(all, setOf(AgentKind.CLAUDE, AgentKind.CODEX)))
        assertEquals(listOf(legacy, claude), filterSessionsByAgent(all, setOf(AgentKind.CLAUDE)))
        assertEquals(listOf(codex), filterSessionsByAgent(all, setOf(AgentKind.CODEX)))
    }

    @Test
    fun project_filter_keeps_a_row_matching_any_selected_backend() {
        val claude = DirectoryEntry("/p/c", "c", isDir = true, hasSessions = true, sessionAgents = listOf(AgentKind.CLAUDE))
        val kimi = DirectoryEntry("/p/k", "k", isDir = true, hasSessions = true, sessionAgents = listOf(AgentKind.KIMI))
        val codex = DirectoryEntry("/p/x", "x", isDir = true, hasSessions = true, sessionAgents = listOf(AgentKind.CODEX))
        val dirs = listOf(claude, kimi, codex)

        assertEquals(listOf("/p/c", "/p/x"), filterDirectoriesByAgent(dirs, setOf(AgentKind.CLAUDE, AgentKind.CODEX)).map { it.path })
        assertEquals(dirs, filterDirectoriesByAgent(dirs, ALL_AGENTS))
    }

    @Test
    fun no_filter_leaves_live_rows_byte_identical() {
        // the rebuild below re-picks activeSessionId from the first SURVIVING session; under "all" nothing is
        // filtered out, so the row must come back untouched rather than silently re-pointed
        val first = ActiveSession("s1", "first", agent = AgentKind.CODEX)
        val second = ActiveSession("s2", "second", agent = AgentKind.CLAUDE)
        val row = DirectoryEntry(
            "/p/mixed", "mixed", isDir = true, hasSessions = true,
            sessionAgents = listOf(AgentKind.CLAUDE, AgentKind.CODEX),
            open = true, activeSessionId = "s2", activeSessionTitle = "second",
            activeSessions = listOf(first, second),
        )
        assertEquals(listOf(row), filterDirectoriesByAgent(listOf(row), ALL_AGENTS))
        // narrowed, the scalars follow the surviving session
        assertEquals("s1", filterDirectoriesByAgent(listOf(row), setOf(AgentKind.CODEX)).single().activeSessionId)
    }

    @Test
    fun projects_empty_state_names_the_filter_that_is_actually_hiding_rows() {
        // #250: an agent filter that empties the list must NOT land in the search empty state (which read
        // «No projects match ""» and offered a Clear-filter button wired to the empty query)
        assertEquals(
            DirEmptyKind.AGENT_FILTERED,
            dirEmptyKind(loaded = true, reportedCount = 4, nothingToShow = true, query = "", agentFiltered = true),
        )
        // a real search term still owns its own state, even with an agent filter also on
        assertEquals(
            DirEmptyKind.NO_QUERY_MATCH,
            dirEmptyKind(loaded = true, reportedCount = 4, nothingToShow = true, query = "zz", agentFiltered = true),
        )
        // the computer reported nothing: no filter to clear, so neither "clear" button may appear
        assertEquals(
            DirEmptyKind.NO_PROJECTS,
            dirEmptyKind(loaded = true, reportedCount = 0, nothingToShow = true, query = "", agentFiltered = false),
        )
        assertEquals(
            DirEmptyKind.NO_PROJECTS,
            dirEmptyKind(loaded = true, reportedCount = 3, nothingToShow = true, query = "", agentFiltered = false),
        )
        // rows to show, or still loading → no empty state at all (the skeleton owns the screen)
        assertEquals(
            DirEmptyKind.NONE,
            dirEmptyKind(loaded = true, reportedCount = 3, nothingToShow = false, query = "", agentFiltered = true),
        )
        assertEquals(
            DirEmptyKind.NONE,
            dirEmptyKind(loaded = false, reportedCount = 0, nothingToShow = true, query = "", agentFiltered = false),
        )
    }

    /**
     * Issue #260 narrowed which of the three #250 states are REACHABLE without changing any of them.
     *
     * Search is now a mode of the Projects screen: the field only exists while it is expanded, and
     * collapsing it clears the query. So [DirEmptyKind.NO_QUERY_MATCH] is reachable only with the field on
     * screen — a blank query is the only thing the collapsed screen can ever ask about, which is exactly
     * the input that routes to the other two states. The classifier is unchanged; this pins the consequence
     * so nobody "simplifies" the query parameter away on the grounds that the search box moved.
     */
    @Test
    fun collapsing_search_can_only_reach_the_two_states_that_are_not_about_a_query() {
        // whatever else is true, a cleared query never lands in the text-search state…
        for (filtered in listOf(true, false)) {
            for (reported in listOf(0, 4)) {
                val kind = dirEmptyKind(
                    loaded = true, reportedCount = reported, nothingToShow = true,
                    query = "", agentFiltered = filtered,
                )
                assertTrue(
                    kind != DirEmptyKind.NO_QUERY_MATCH,
                    "a collapsed search cannot blame a query it no longer holds (reported=$reported, filtered=$filtered)",
                )
            }
        }
        // …and the state it DOES reach still names the real cause
        assertEquals(
            DirEmptyKind.AGENT_FILTERED,
            dirEmptyKind(loaded = true, reportedCount = 4, nothingToShow = true, query = "", agentFiltered = true),
        )
        assertEquals(
            DirEmptyKind.NO_PROJECTS,
            dirEmptyKind(loaded = true, reportedCount = 0, nothingToShow = true, query = "", agentFiltered = false),
        )
    }
}
