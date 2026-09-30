package dev.ccpocket.app.memo

import dev.ccpocket.protocol.AgentKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two-level target picker (projects → sessions) and dispatching into a session the batch CREATES
 * (TARGET_PICKER_BRIEF §1, §2, §5): catalog reads per level, what may be picked, how a pick stays valid, and a
 * created session that the memo then points at — so the rest of a batch never creates a second one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoTargetPickerTest {

    private val memo = uuid(850)
    private val app = "/work/app"
    private val other = "/work/other"
    private var harness: MemoHarness? = null

    @AfterTest
    fun noActorFailures() {
        harness?.let { assertEquals(emptyList(), it.errors, "the actor threw") }
    }

    private val inApp = MemoTarget(SCOPE.bindingId, "s-app", app, AgentKind.CLAUDE, project = "app", title = "Refactor")
    private val observed = MemoTarget(SCOPE.bindingId, "s-obs", app, AgentKind.CODEX, project = "app", title = "Watching")
    private val created = MemoTarget(SCOPE.bindingId, "", app, AgentKind.CODEX, project = "app")

    private fun TestScope.open(vararg todos: String, setup: MemoHarness.() -> Unit = {}): MemoHarness {
        val h = MemoHarness(this).also { harness = it }
        h.seed(seededDoc(memo, *todos.mapIndexed { i, text -> "t${i + 1}" to text }.toTypedArray()))
        h.gateway.rows = listOf(TARGET_ROW)
        h.gateway.projects = listOf(MemoProjectRow(app, "app", sessionCount = 2, running = true), MemoProjectRow(other, "other", sessionCount = 0))
        h.gateway.sessions = mapOf(
            app to listOf(MemoTargetRow(inApp, MemoTargetStatus.IDLE, mode = "plan", lastModifiedMs = 5), MemoTargetRow(observed, MemoTargetStatus.OBSERVING)),
        )
        h.setup()
        h.start()
        h.act(MemoAction.OpenMemo(memo))
        return h
    }

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun MemoHarness.records() = checkNotNull(stored(memo)).dispatches

    /** Enter creates the session: its target has no id yet. */
    private fun MemoHarness.createOnEnter(target: MemoTarget = created) {
        gateway.enterResult = { t, batch ->
            if (t.newSession) MemoEnterResult.Entered(MemoTargetLease(target, "convo-new", batch, 1))
            else MemoEnterResult.Entered(MemoTargetLease(t, "convo-old", batch, 1))
        }
    }

    // 1 ── catalog reads ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun theCatalogIsReadForTheLevelShownAndAFailedReadKeepsTheLastRows() = runTest {
        val h = open("a")
        assertEquals(listOf<String?>(null), h.gateway.catalogRequests, "entering DETAIL reads the first level")
        assertEquals(MemoListStatus.READY, h.state.catalog.status)
        assertEquals(listOf(TARGET_ROW), h.state.catalog.recent)
        assertEquals(listOf(app, other), h.state.catalog.projects.map { it.workdir })
        assertNull(h.state.catalog.project)

        h.act(MemoAction.OpenTargetProject(app))
        assertEquals(app, h.gateway.catalogRequests.last())
        assertEquals(listOf(inApp, observed), h.state.catalog.project!!.sessions.map { it.target })
        h.act(MemoAction.RefreshTargets)
        assertEquals(app, h.gateway.catalogRequests.last(), "a refresh reads the level shown")
        h.act(MemoAction.CloseTargetProject)
        assertNull(h.gateway.catalogRequests.last())
        assertNull(h.state.catalog.project)

        h.gateway.catalogThrows = true
        h.act(MemoAction.RefreshTargets)
        assertEquals(MemoListStatus.FAILED, h.state.catalog.status)
        assertEquals(listOf(TARGET_ROW), h.state.catalog.recent, "the last rows stay")
        assertEquals(2, h.state.catalog.projects.size)

        h.gateway.catalogThrows = false
        h.act(MemoAction.SelectTarget(TARGET))
        h.confirm()
        advance(10_000)
        val before = h.gateway.catalogRequests.size
        h.act(MemoAction.BackToList)
        h.act(MemoAction.ReturnToMemo)
        assertEquals(before + 1, h.gateway.catalogRequests.size, "returning to the memo reads it again")
        assertEquals(MemoListStatus.READY, h.state.catalog.status)
    }

    // 2 ── picking an existing session ─────────────────────────────────────────────────────────────────

    @Test
    fun onlyASelectableRowTheCatalogShowsCanBePicked() = runTest {
        val h = open("a")
        h.act(MemoAction.SelectTarget(inApp))
        assertNull(h.state.selection.target, "a project's session is not loaded until the project is opened")
        h.act(MemoAction.OpenTargetProject(app))
        h.act(MemoAction.SelectTarget(observed))
        assertNull(h.state.selection.target, "an observing session cannot be picked")
        h.act(MemoAction.SelectTarget(inApp.copy(sessionId = "s-unknown")))
        assertNull(h.state.selection.target)
        h.act(MemoAction.SelectTarget(inApp.copy(newSession = true)))
        assertNull(h.state.selection.target, "a new-session target is never picked this way")
        h.act(MemoAction.SelectTarget(inApp))
        assertEquals(inApp, h.state.selection.target!!.target)
        assertEquals("plan", h.state.selection.target!!.mode)
        h.act(MemoAction.SelectTarget(TARGET))
        assertEquals(TARGET, h.state.selection.target!!.target, "recent rows are pickable on either level")
    }

    // 3 ── picking a new session ───────────────────────────────────────────────────────────────────────

    @Test
    fun aNewSessionCanBePickedOnlyInAListedProjectOnAnOfferedAgent() = runTest {
        val h = open("a")
        h.act(MemoAction.SelectNewSession("/work/elsewhere", AgentKind.CLAUDE))
        assertNull(h.state.selection.target, "not a project of this computer")
        h.act(MemoAction.SelectNewSession(app, AgentKind.KIMI))
        assertNull(h.state.selection.target, "an agent the computer does not offer")

        h.act(MemoAction.SelectNewSession(other, AgentKind.CODEX))
        val row = h.state.selection.target!!
        assertEquals(MemoTarget(SCOPE.bindingId, "", other, AgentKind.CODEX, project = "other", title = "", newSession = true), row.target)
        assertEquals(MemoTargetStatus.IDLE, row.status)
        assertEquals("acceptEdits", row.mode)
        assertEquals(MemoDispatchBlock.NONE, h.state.selection.block)

        // The project open on the second level counts even if the first level no longer lists it.
        h.gateway.projects = emptyList()
        h.gateway.sessions = mapOf("/work/fresh" to emptyList())
        h.act(MemoAction.OpenTargetProject("/work/fresh"))
        h.act(MemoAction.SelectNewSession("/work/fresh", AgentKind.CLAUDE))
        assertEquals("/work/fresh", h.state.selection.target!!.target.workdir)
        assertTrue(h.state.selection.target!!.target.newSession)
    }

    // 4 ── identity ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun newSessionIsPartOfTheIdentity() = runTest {
        val existing = MemoTarget(SCOPE.bindingId, "", app, AgentKind.CLAUDE)
        assertFalse(existing.sameAs(existing.copy(newSession = true)))
        assertTrue(existing.sameAs(existing.copy(title = "label", project = "label")))

        val h = open("a")
        h.act(MemoAction.SelectNewSession(app, AgentKind.CLAUDE))
        val picked = h.state.selection.target!!.target
        h.act(MemoAction.ConfirmDispatch(h.state.selection.shown, picked.copy(newSession = false)))
        assertEquals(MemoToast.CHANGED_SINCE_SHOWN, h.state.toast, "a sheet for an existing session does not authorise creating one")
        assertTrue(h.gateway.entered.isEmpty())
    }

    // 5 ── validity of the pick ────────────────────────────────────────────────────────────────────────

    @Test
    fun aPickIsJudgedByTheRowsLoadedNowAndANewSessionByTheOfferedAgents() = runTest {
        val h = open("a")
        h.act(MemoAction.OpenTargetProject(app))
        h.act(MemoAction.SelectTarget(inApp))
        h.act(MemoAction.CloseTargetProject)
        assertEquals(MemoDispatchBlock.NONE, h.state.selection.block, "not in any loaded row: still picked")
        assertEquals(inApp, h.state.selection.target!!.target)

        h.gateway.rows = listOf(TARGET_ROW, MemoTargetRow(inApp, MemoTargetStatus.NEEDS_TAKEOVER))
        h.act(MemoAction.RefreshTargets)
        assertEquals(MemoDispatchBlock.TARGET_UNAVAILABLE, h.state.selection.block)
        assertEquals(MemoTargetStatus.NEEDS_TAKEOVER, h.state.selection.target!!.status)

        h.act(MemoAction.SelectNewSession(app, AgentKind.CODEX))
        assertEquals(MemoDispatchBlock.NONE, h.state.selection.block)
        h.gateway.agents = listOf(AgentKind.CLAUDE)
        h.act(MemoAction.RefreshTargets)
        assertEquals(MemoDispatchBlock.TARGET_UNAVAILABLE, h.state.selection.block, "the agent is no longer offered")
        h.gateway.agents = emptyList()
        h.act(MemoAction.RefreshTargets)
        assertEquals(MemoDispatchBlock.NONE, h.state.selection.block, "agents not known: the host checks on entry")
    }

    // 6 + 7 ── dispatching into a created session ──────────────────────────────────────────────────────

    @Test
    fun aCreatedSessionBecomesTheTargetAndItsIdIsLearnedAfterDelivery() = runTest {
        val h = open("a", "b")
        h.createOnEnter()
        val named = created.copy(sessionId = "s-new", title = "Fix build")
        h.gateway.resolve = { lease -> if (h.gateway.submitted.isEmpty()) lease.target else named }
        h.act(MemoAction.SelectNewSession(app, AgentKind.CODEX))
        h.confirm()

        val entered = h.gateway.entered.single().first
        assertTrue(entered.newSession, "the gateway is asked to create the session")
        val d = h.state.dispatch!!
        assertTrue(d.creating)
        assertEquals(MemoDispatchPhase.SENDING, d.phase)
        assertNull(d.openFailure)
        assertEquals(created, d.target)
        assertTrue(h.records().all { it.target == created && it.convoId == "convo-new" }, "records point at the created session")
        assertEquals(created, h.state.selection.target!!.target, "the pick is the created session, never a new one again")
        assertEquals("acceptEdits", h.state.selection.target!!.mode)

        h.receipt("prompt-1", convoId = "convo-new")
        assertEquals(named, h.state.dispatch!!.target)
        assertTrue(h.records().all { it.target == named }, "the learned id is stored")
        assertEquals(named, h.state.selection.target!!.target)

        h.receipt("prompt-2", convoId = "convo-new")
        val done = h.state.dispatch!!
        assertEquals(MemoDispatchPhase.DONE, done.phase)
        assertTrue(done.creating, "creating stays true after the session exists")
        assertEquals(named, h.state.selection.target!!.target)
        assertTrue(h.gateway.resolveCalls.size >= 3, "after each delivery and at the end: ${h.gateway.resolveCalls.size}")
    }

    @Test
    fun aSessionWhoseIdIsNeverLearnedIsDroppedFromThePickWhenTheBatchEnds() = runTest {
        val h = open("a")
        h.createOnEnter()
        h.act(MemoAction.SelectNewSession(app, AgentKind.CODEX))
        h.confirm()
        h.receipt("prompt-1", convoId = "convo-new")
        assertEquals(MemoDispatchPhase.DONE, h.state.dispatch!!.phase)
        assertNull(h.state.selection.target, "never kept, never back to 'new session'")
        assertEquals(MemoDispatchBlock.NO_SELECTION, h.state.selection.block, "(the only item was delivered)")
        assertTrue(h.records().all { it.target == created && !it.target.newSession })
    }

    @Test
    fun whatIsLeftAfterAStopGoesIntoTheSameCreatedSession() = runTest {
        val h = open("a", "b")
        h.createOnEnter()
        val named = created.copy(sessionId = "s-new", title = "Fix build")
        h.gateway.resolve = { named }
        h.act(MemoAction.SelectNewSession(app, AgentKind.CODEX))
        h.confirm()
        advance(10_000) // no receipt for item 1
        assertEquals(MemoDispatchPhase.STOPPED, h.state.dispatch!!.phase)
        assertEquals(named, h.state.selection.target!!.target, "the stop learned the id")
        assertTrue(h.records().all { it.target == named })

        h.gateway.rows = listOf(TARGET_ROW, MemoTargetRow(named, MemoTargetStatus.RUNNING))
        h.act(MemoAction.RefreshTargets)
        h.confirm()
        val second = h.gateway.entered.last().first
        assertEquals(named, second, "the rest goes into the same session")
        assertFalse(second.newSession)
        assertFalse(h.state.dispatch!!.creating)
        assertEquals(listOf("t1", "t2"), h.gateway.submitted.map { it.todoId })
    }

    @Test
    fun aStoppedBatchWithoutAnIdClearsThePickSoNothingCreatesAgain() = runTest {
        val h = open("a", "b")
        h.createOnEnter()
        h.act(MemoAction.SelectNewSession(app, AgentKind.CODEX))
        h.confirm()
        advance(10_000)
        assertEquals(MemoDispatchPhase.STOPPED, h.state.dispatch!!.phase)
        assertNull(h.state.selection.target)
        assertEquals(MemoDispatchBlock.NO_TARGET, h.state.selection.block)
        h.confirm()
        assertEquals(1, h.gateway.entered.size, "no second session is created")
    }

    @Test
    fun aSessionThatCannotBeCreatedSendsNothingAndKeepsThePick() = runTest {
        val h = open("a", "b")
        h.gateway.enterResult = { _, _ -> MemoEnterResult.Failed(MemoEnterFailure.TIMEOUT) }
        h.act(MemoAction.SelectNewSession(app, AgentKind.CODEX))
        val pick = h.state.selection.target!!.target
        h.confirm()
        val d = h.state.dispatch!!
        assertEquals(MemoDispatchPhase.OPEN_FAILED, d.phase)
        assertEquals(MemoEnterFailure.TIMEOUT, d.openFailure)
        assertTrue(d.creating)
        assertTrue(h.gateway.submitted.isEmpty())
        assertTrue(h.records().all { it.state == MemoTodoState.DRAFT })
        assertEquals(pick, h.state.selection.target!!.target, "still 'new session' — the user may retry")
        assertTrue(h.gateway.resolveCalls.isEmpty())

        h.gateway.enterResult = null
        h.createOnEnter()
        h.confirm()
        assertEquals(2, h.gateway.entered.size)
        assertTrue(h.gateway.entered.last().first.newSession)
    }

    @Test
    fun aCreatedLeaseMustBeARealSessionInTheSameProjectAndAgent() = runTest {
        for (wrong in listOf(created.copy(newSession = true), created.copy(workdir = other), created.copy(agent = AgentKind.CLAUDE), created.copy(bindingId = "x"))) {
            val h = open("a") { createOnEnter(wrong) }
            h.act(MemoAction.SelectNewSession(app, AgentKind.CODEX))
            h.confirm()
            assertEquals(MemoDispatchPhase.OPEN_FAILED, h.state.dispatch!!.phase, "$wrong")
            assertEquals(MemoEnterFailure.NOT_THE_TARGET, h.state.dispatch!!.openFailure)
            assertTrue(h.gateway.submitted.isEmpty())
            assertTrue(h.state.selection.target!!.target.newSession)
        }
    }

    @Test
    fun anExistingSessionBatchIsNotCreating() = runTest {
        val h = open("a")
        h.act(MemoAction.SelectTarget(TARGET))
        h.confirm()
        assertNotNull(h.state.dispatch)
        assertFalse(h.state.dispatch!!.creating)
        assertEquals(TARGET, h.gateway.entered.single().first)
    }
}
