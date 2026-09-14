package dev.ccpocket.app.ui.session

import dev.ccpocket.app.ui.session.ImportSessionsEffect as Fx
import dev.ccpocket.app.ui.session.ImportSessionsEvent as Ev
import dev.ccpocket.protocol.AgentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #360 stage 2: the discovery/import screen's pure reducer. */
class ImportSessionsStateTest {
    private val a = ManagedScope("dev-a", "/w/app")
    private val b = ManagedScope("dev-b", "/w/app") // same path, other computer

    private fun row(id: String, agent: AgentKind = AgentKind.CLAUDE, managed: Boolean = false) =
        DiscoveredSession(agent, id, "title $id", "first $id", 1_000L, managed)

    private fun ImportSessionsStep.discover(): Fx.Discover = effects.filterIsInstance<Fx.Discover>().single()

    private fun open(scope: ManagedScope = a): ImportSessionsStep = reduceImportSessions(ImportSessionsState(), Ev.Open(scope))

    private fun loaded(vararg rows: DiscoveredSession, cursor: String? = null, complete: Boolean = true): ImportSessionsState {
        val o = open()
        return reduceImportSessions(o.state, Ev.DiscoverReturned(o.discover().tag, DiscoverResult.Page(rows.toList(), cursor, complete))).state
    }

    @Test
    fun opening_loads_the_first_page_for_the_explicit_scope_with_claude_selected() {
        val step = open()
        assertEquals(ListPhase.Loading, step.state.list)
        val d = step.discover()
        assertEquals(a, d.scope)
        assertEquals(AgentKind.CLAUDE, d.agent)
        assertEquals("", d.query)
        assertNull(d.cursor)
    }

    @Test
    fun opening_filters_out_agents_the_first_version_does_not_manage() {
        val step = reduceImportSessions(ImportSessionsState(), Ev.Open(a, agents = listOf(AgentKind.DSH, AgentKind.CODEX)))
        assertEquals(listOf(AgentKind.CODEX), step.state.agents)
        assertEquals(AgentKind.CODEX, step.discover().agent)
    }

    @Test
    fun a_page_fills_rows_and_distinguishes_empty_from_partial() {
        val empty = loaded()
        assertTrue(empty.isEmptyResult)
        assertNull(empty.diagnostic)

        val o = open()
        val partial = reduceImportSessions(
            o.state, Ev.DiscoverReturned(o.discover().tag, DiscoverResult.Page(listOf(row("s1")), null, complete = false, DiscoverDiagnostic.TRUNCATED)),
        ).state
        assertEquals(ListPhase.Loaded, partial.list)
        assertFalse(partial.complete)
        assertEquals(DiscoverDiagnostic.TRUNCATED, partial.diagnostic)
        assertFalse(partial.isEmptyResult)
    }

    @Test
    fun a_failed_first_page_is_shown_and_retry_asks_again() {
        val o = open()
        val failed = reduceImportSessions(o.state, Ev.DiscoverReturned(o.discover().tag, DiscoverResult.Failure(ManagedSessionsError.DISCONNECTED))).state
        assertEquals(ListPhase.Failed(ManagedSessionsError.DISCONNECTED), failed.list)
        val retry = reduceImportSessions(failed, Ev.Retry)
        assertEquals(ListPhase.Loading, retry.state.list)
        assertEquals(a, retry.discover().scope)
    }

    @Test
    fun typing_only_schedules_a_debounce_and_only_the_latest_token_searches() {
        val s0 = loaded(row("s1"))
        val t1 = reduceImportSessions(s0, Ev.QueryTyped("fo"))
        assertTrue(t1.effects.none { it is Fx.Discover }, "typing must not hit the daemon per keystroke")
        val tok1 = t1.effects.filterIsInstance<Fx.Debounce>().single().token
        val t2 = reduceImportSessions(t1.state, Ev.QueryTyped("foo"))
        val tok2 = t2.effects.filterIsInstance<Fx.Debounce>().single().token

        assertTrue(reduceImportSessions(t2.state, Ev.DebounceElapsed(tok1)).effects.isEmpty(), "a superseded debounce is inert")
        val fire = reduceImportSessions(t2.state, Ev.DebounceElapsed(tok2))
        assertEquals("foo", fire.discover().query)
        assertEquals("foo", fire.state.appliedQuery)
        assertEquals(ListPhase.Loading, fire.state.list)
    }

    @Test
    fun a_debounce_for_an_unchanged_query_does_not_refetch() {
        val s0 = loaded(row("s1"))
        val t = reduceImportSessions(s0, Ev.QueryTyped("x"))
        val back = reduceImportSessions(t.state, Ev.QueryTyped(""))
        val tok = back.effects.filterIsInstance<Fx.Debounce>().single().token
        assertTrue(reduceImportSessions(back.state, Ev.DebounceElapsed(tok)).effects.isEmpty())
    }

    @Test
    fun an_older_search_answer_never_overwrites_the_newer_query() {
        val s0 = open()
        val oldTag = s0.discover().tag
        val typed = reduceImportSessions(s0.state, Ev.QueryTyped("new"))
        val fired = reduceImportSessions(typed.state, Ev.DebounceElapsed(typed.effects.filterIsInstance<Fx.Debounce>().single().token))
        val late = reduceImportSessions(fired.state, Ev.DiscoverReturned(oldTag, DiscoverResult.Page(listOf(row("old")), null, true)))
        assertEquals(fired.state, late.state)
    }

    @Test
    fun switching_agent_resets_rows_and_searches_that_agent_with_the_current_text() {
        val s0 = loaded(row("s1"), cursor = "c1")
        val typed = reduceImportSessions(s0, Ev.QueryTyped("bug")).state
        val sw = reduceImportSessions(typed, Ev.SelectAgent(AgentKind.CODEX))
        assertEquals(AgentKind.CODEX, sw.state.agent)
        assertTrue(sw.state.items.isEmpty())
        assertNull(sw.state.nextCursor)
        assertEquals("bug", sw.discover().query)
        assertEquals(AgentKind.CODEX, sw.discover().agent)
        assertTrue(reduceImportSessions(sw.state, Ev.SelectAgent(AgentKind.DSH)).effects.isEmpty(), "unmanaged agents cannot be selected")
    }

    @Test
    fun load_more_appends_the_next_page_and_dedupes_rows() {
        val s0 = loaded(row("s1"), row("s2"), cursor = "c1")
        assertTrue(s0.canLoadMore)
        val more = reduceImportSessions(s0, Ev.LoadMore)
        assertEquals("c1", more.discover().cursor)
        assertTrue(more.state.loadingMore)
        assertTrue(reduceImportSessions(more.state, Ev.LoadMore).effects.isEmpty(), "no double page request")
        val done = reduceImportSessions(
            more.state, Ev.DiscoverReturned(more.discover().tag, DiscoverResult.Page(listOf(row("s2"), row("s3")), null, true)),
        ).state
        assertEquals(listOf("s1", "s2", "s3"), done.items.map { it.nativeId })
        assertFalse(done.loadingMore)
        assertFalse(done.canLoadMore)
    }

    @Test
    fun a_failed_next_page_keeps_existing_rows() {
        val s0 = loaded(row("s1"), cursor = "c1")
        val more = reduceImportSessions(s0, Ev.LoadMore)
        val failed = reduceImportSessions(more.state, Ev.DiscoverReturned(more.discover().tag, DiscoverResult.Failure(ManagedSessionsError.TIMEOUT))).state
        assertEquals(listOf("s1"), failed.items.map { it.nativeId })
        assertEquals(ListPhase.Loaded, failed.list)
        assertEquals(ManagedSessionsError.TIMEOUT, failed.loadMoreError)
        assertEquals("c1", reduceImportSessions(failed, Ev.Retry).discover().cursor, "retry resumes the page, not page 1")
    }

    @Test
    fun import_goes_in_flight_then_marks_imported_and_notifies_the_host() {
        val s0 = loaded(row("s1"))
        val key = DiscoveredKey(AgentKind.CLAUDE, "s1")
        val click = reduceImportSessions(s0, Ev.ImportClicked(key))
        val fx = click.effects.filterIsInstance<Fx.Import>().single()
        assertEquals(a, fx.scope)
        assertEquals(ImportPhase.InFlight, click.state.imports[key])

        val ok = reduceImportSessions(click.state, Ev.ImportReturned(fx.tag, key, ImportResult.Imported(key, alreadyManaged = false)))
        assertTrue(ok.state.isImported(ok.state.items.single()))
        assertNull(ok.state.imports[key])
        assertEquals(listOf<Fx>(Fx.Imported(a, key, false)), ok.effects)
    }

    @Test
    fun import_failure_is_per_row_and_can_be_retried() {
        val s0 = loaded(row("s1"))
        val key = DiscoveredKey(AgentKind.CLAUDE, "s1")
        val click = reduceImportSessions(s0, Ev.ImportClicked(key))
        val tag = click.effects.filterIsInstance<Fx.Import>().single().tag
        val bad = reduceImportSessions(click.state, Ev.ImportReturned(tag, key, ImportResult.Failure(ManagedSessionsError.NOT_FOUND))).state
        assertEquals(ImportPhase.Failed(ManagedSessionsError.NOT_FOUND), bad.imports[key])
        assertFalse(bad.isImported(bad.items.single()))
        assertEquals(1, reduceImportSessions(bad, Ev.ImportClicked(key)).effects.filterIsInstance<Fx.Import>().size)
    }

    @Test
    fun repeat_import_is_idempotent_on_the_client() {
        val key = DiscoveredKey(AgentKind.CLAUDE, "s1")
        val s0 = loaded(row("s1"), row("m", managed = true))
        val click = reduceImportSessions(s0, Ev.ImportClicked(key))
        assertTrue(reduceImportSessions(click.state, Ev.ImportClicked(key)).effects.isEmpty(), "in-flight: no second request")
        assertTrue(reduceImportSessions(s0, Ev.ImportClicked(DiscoveredKey(AgentKind.CLAUDE, "m"))).effects.isEmpty(), "already managed: nothing to send")
        // daemon says "already a member" → still success, host still told to locate it
        val tag = click.effects.filterIsInstance<Fx.Import>().single().tag
        val again = reduceImportSessions(click.state, Ev.ImportReturned(tag, key, ImportResult.Imported(key, alreadyManaged = true)))
        assertEquals(listOf<Fx>(Fx.Imported(a, key, true)), again.effects)
    }

    @Test
    fun switching_computer_or_project_drops_every_late_answer() {
        val s0 = loaded(row("s1"))
        val key = DiscoveredKey(AgentKind.CLAUDE, "s1")
        val click = reduceImportSessions(s0, Ev.ImportClicked(key))
        val importTag = click.effects.filterIsInstance<Fx.Import>().single().tag
        val more = reduceImportSessions(click.state, Ev.QueryTyped("q"))

        val switched = reduceImportSessions(more.state, Ev.Open(b))
        assertEquals(b, switched.state.scope)
        assertTrue(switched.state.items.isEmpty())
        assertTrue(switched.state.imports.isEmpty())
        assertEquals("", switched.state.queryInput)
        val oldDebounce = more.effects.filterIsInstance<Fx.Debounce>().single().token
        assertTrue(reduceImportSessions(switched.state, Ev.DebounceElapsed(oldDebounce)).effects.isEmpty())

        val lateImport = reduceImportSessions(switched.state, Ev.ImportReturned(importTag, key, ImportResult.Imported(key, false)))
        assertEquals(switched.state, lateImport.state)
        assertTrue(lateImport.effects.isEmpty(), "a late import must not jump the new computer's list")

        val lateDiscover = reduceImportSessions(switched.state, Ev.DiscoverReturned(RequestTag(s0.generation, s0.seq), DiscoverResult.Page(listOf(row("x")), null, true)))
        assertEquals(switched.state, lateDiscover.state)
    }

    @Test
    fun an_expired_cursor_restarts_from_the_first_page_on_retry() {
        val s0 = loaded(row("s1"), cursor = "c1")
        val more = reduceImportSessions(s0, Ev.LoadMore)
        val failed = reduceImportSessions(more.state, Ev.DiscoverReturned(more.discover().tag, DiscoverResult.Failure(ManagedSessionsError.CURSOR_EXPIRED))).state
        val retry = reduceImportSessions(failed, Ev.Retry)
        assertNull(retry.discover().cursor, "a stale cursor is never resent")
        assertTrue(retry.state.items.isEmpty())
    }

    @Test
    fun an_unanswered_import_is_checked_and_becomes_imported_when_the_list_has_it() {
        val key = DiscoveredKey(AgentKind.CLAUDE, "s1")
        val click = reduceImportSessions(loaded(row("s1")), Ev.ImportClicked(key))
        val importTag = click.effects.filterIsInstance<Fx.Import>().single().tag
        val lost = reduceImportSessions(click.state, Ev.ImportReturned(importTag, key, ImportResult.Failure(ManagedSessionsError.UNCONFIRMED)))
        assertEquals(ImportPhase.Checking, lost.state.imports[key], "not 'failed', not an endless spinner: a bounded check")
        val check = lost.effects.filterIsInstance<Fx.Reconcile>().single()
        assertEquals(key, check.key)

        val landed = reduceImportSessions(lost.state, Ev.ReconcileReturned(check.tag, key, managed = true))
        assertTrue(landed.state.isImported(landed.state.items.single()))
        assertNull(landed.state.imports[key])
        assertEquals(listOf<Fx>(Fx.Imported(a, key, alreadyManaged = false)), landed.effects)
    }

    @Test
    fun an_unanswered_import_that_did_not_land_is_unconfirmed_and_can_be_retried() {
        val key = DiscoveredKey(AgentKind.CLAUDE, "s1")
        val click = reduceImportSessions(loaded(row("s1")), Ev.ImportClicked(key))
        val lost = reduceImportSessions(click.state, Ev.ImportReturned(click.effects.filterIsInstance<Fx.Import>().single().tag, key, ImportResult.Failure(ManagedSessionsError.UNCONFIRMED)))
        val check = lost.effects.filterIsInstance<Fx.Reconcile>().single()
        for (answer in listOf(false, null)) {
            val settled = reduceImportSessions(lost.state, Ev.ReconcileReturned(check.tag, key, managed = answer)).state
            assertEquals(ImportPhase.Failed(ManagedSessionsError.UNCONFIRMED), settled.imports[key])
            assertEquals(1, reduceImportSessions(settled, Ev.ImportClicked(key)).effects.filterIsInstance<Fx.Import>().size, "retry is offered")
        }
        assertTrue(reduceImportSessions(lost.state, Ev.ImportClicked(key)).effects.isEmpty(), "no second import while checking")
        val moved = reduceImportSessions(lost.state, Ev.Open(b)).state
        assertEquals(moved, reduceImportSessions(moved, Ev.ReconcileReturned(check.tag, key, managed = true)).state, "a late check for the old computer is dropped")
    }

    @Test
    fun closing_drops_late_answers_too() {
        val o = open()
        val closed = reduceImportSessions(o.state, Ev.Close).state
        assertNull(closed.scope)
        val late = reduceImportSessions(closed, Ev.DiscoverReturned(o.discover().tag, DiscoverResult.Page(listOf(row("s1")), null, true)))
        assertEquals(closed, late.state)
        assertTrue(reduceImportSessions(closed, Ev.LoadMore).effects.isEmpty())
    }

    @Test
    fun membership_changes_for_this_scope_update_the_mark_so_remove_then_reimport_works() {
        val key = DiscoveredKey(AgentKind.CLAUDE, "m")
        val s0 = loaded(row("m", managed = true))
        val removed = reduceImportSessions(s0, Ev.MembershipChanged(a, key, managed = false)).state
        assertFalse(removed.isImported(removed.items.single()))
        assertEquals(1, reduceImportSessions(removed, Ev.ImportClicked(key)).effects.filterIsInstance<Fx.Import>().size)
        val otherComputer = reduceImportSessions(s0, Ev.MembershipChanged(b, key, managed = false)).state
        assertTrue(otherComputer.isImported(otherComputer.items.single()), "another computer's change is not ours")
    }
}
