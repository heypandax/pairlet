package dev.ccpocket.app.ui.memo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.ccpocket.app.localClock
import dev.ccpocket.app.memo.MemoAction
import dev.ccpocket.app.memo.MemoDispatchPhase
import dev.ccpocket.app.memo.MemoDispatchState
import dev.ccpocket.app.memo.MemoEnterFailure
import dev.ccpocket.app.memo.MemoListStatus
import dev.ccpocket.app.memo.MemoNewSessionOptions
import dev.ccpocket.app.memo.MemoUiState
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.str
import dev.ccpocket.protocol.AgentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Target picker v3 (TARGET_PICKER_BRIEF.md): level 1 recent sessions + projects, level 2 "new session here" +
 * the project's sessions, the two confirm target blocks, and what the result page shows while the target is
 * being opened / created or could not be.
 */
@OptIn(ExperimentalTestApi::class)
class MemoTargetPickerUiTest {

    private class Host(initial: MemoUiState) {
        var state by mutableStateOf(initial)
        val actions = mutableListOf<MemoAction>()
        var closes = 0
    }

    private fun picker(host: Host, width: Int = PHONE_W, height: Int = 1400, fontScale: Float = 1f, assertions: androidx.compose.ui.test.SkikoComposeUiTest.() -> Unit) =
        memoScene(width = width, height = height, fontScale = fontScale, content = {
            MemoTargetPicker(host.state, { host.actions += it }) { host.closes++ }
        }, assertions = assertions)

    // ── level 1 ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun levelOneShowsLoadingFailureWithRetryAndAnEmptyLibraryDistinctly() {
        val host = Host(MemoFx.detail(catalog = MemoFx.catalog(status = MemoListStatus.LOADING)))
        picker(host) {
            assertEquals(MemoAction.RefreshTargets, host.actions.first(), "opening the picker asks for a fresh catalog")
            assertTrue(has(str(Res.string.memo_targets_sub_projects, "alex-mac")))
            assertTrue(has(str(Res.string.memo_targets_loading, "alex-mac")))
            assertFalse(has(str(Res.string.memo_targets_projects).uppercase()), "no list while it is still being read")

            host.state = MemoFx.detail(catalog = MemoFx.catalog(status = MemoListStatus.FAILED))
            waitForIdle()
            assertTrue(has(str(Res.string.memo_targets_failed_title)))
            assertFalse(has(str(Res.string.memo_targets_no_projects, "alex-mac")), "a failure is never an empty list")
            buttons(str(Res.string.memo_rec_retry)).onFirst().tap()
            waitForIdle()
            assertEquals(2, host.actions.count { it == MemoAction.RefreshTargets }, "retry reads the catalog again")

            host.state = MemoFx.detail(catalog = MemoFx.catalog(recent = emptyList(), projects = emptyList()))
            waitForIdle()
            assertTrue(has(str(Res.string.memo_targets_no_projects, "alex-mac")))
            assertFalse(has(str(Res.string.memo_targets_recent).uppercase()))
        }
    }

    @Test
    fun levelOneListsRecentSessionsAndProjectsAndMarksTheCurrentTarget() {
        val host = Host(MemoFx.detail())
        picker(host) {
            assertTrue(has(str(Res.string.memo_targets_recent).uppercase()) && has(str(Res.string.memo_targets_projects).uppercase()))
            assertTrue(has("Pairlet · alex-mac") && has("docs-site · alex-mac"), "recent rows carry their project")
            assertTrue(has(str(Res.string.memo_target_reason_running)), "a running session is selectable, with its consequence")
            assertEquals(1, count(str(Res.string.memo_target_current)), "the selected session is marked")
            assertEquals(1, count(str(Res.string.memo_project_holds_target)), "…and so is the project that holds it")
            assertTrue(has(str(Res.string.memo_project_sessions, 3)) && has(str(Res.string.memo_project_sessions, 1)))
            assertTrue(has(str(Res.string.memo_project_no_sessions)), "0 sessions reads 暂无会话")
            assertEquals(1, count(str(Res.string.memo_project_running)))
            assertTrue(has("uncounted"), "a project whose sessions were not counted says no number")

            host.state = MemoFx.detail(catalog = MemoFx.catalog(recent = emptyList()))
            waitForIdle()
            assertFalse(has(str(Res.string.memo_targets_recent).uppercase()), "no recent sessions: the whole section is absent")
        }
    }

    @Test
    fun aRecentSessionIsPickedDirectlyAndAProjectOpensLevelTwo() {
        val host = Host(MemoFx.detail())
        picker(host) {
            onAllNodes(hasText("文档整理")).onFirst().tap()
            waitForIdle()
            assertEquals(listOf<MemoAction>(MemoAction.SelectTarget(MemoFx.running.target)), host.actions.filterIsInstance<MemoAction.SelectTarget>())
            assertEquals(1, host.closes, "a pick goes straight back to the result")

            onAllNodes(hasText("scratch")).onFirst().tap()
            waitForIdle()
            assertEquals(MemoAction.OpenTargetProject(MemoFx.SCRATCH), host.actions.last())
            assertEquals(1, host.closes, "opening a project stays in the picker")
        }
    }

    // ── level 2 ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun levelTwoBacksOutToLevelOneAndListsTheProjectsSessions() {
        val host = Host(MemoFx.detail(catalog = MemoFx.catalog(project = MemoFx.pairletLevel())))
        picker(host) {
            assertTrue(has("Pairlet"), "the title is the project")
            assertTrue(has(str(Res.string.memo_new_session_in_project)), "the first row is always 'new session here'")
            assertTrue(has(str(Res.string.memo_target_reason_running)) && has(str(Res.string.memo_target_reason_observing)))
            val modified = MemoFx.pairletLevel().sessions[1].lastModifiedMs
            val at = localClock(modified)
            val hm = "${at.hour.toString().padStart(2, '0')}:${at.minute.toString().padStart(2, '0')}"
            assertTrue(has(str(Res.string.memo_time_today, hm)) || has(str(Res.string.memo_time_yesterday, hm)), "last modified is shown")

            onAllNodes(hasText("依赖升级")).onFirst().performClick()
            waitForIdle()
            assertTrue(host.actions.none { it is MemoAction.SelectTarget }, "an unselectable session does not respond")

            tapDescription(str(Res.string.memo_targets_back_projects_cd))
            assertEquals(MemoAction.CloseTargetProject, host.actions.last())
            assertEquals(0, host.closes, "back from a project returns to level 1, not to the result")
        }
    }

    @Test
    fun levelTwoLoadingFailureAndEmpty() {
        val host = Host(MemoFx.detail(catalog = MemoFx.catalog(project = MemoFx.pairletLevel(MemoListStatus.LOADING, emptyList()))))
        picker(host) {
            assertTrue(has(str(Res.string.memo_project_loading)))
            host.state = MemoFx.detail(catalog = MemoFx.catalog(project = MemoFx.pairletLevel(MemoListStatus.FAILED, emptyList())))
            waitForIdle()
            assertTrue(has(str(Res.string.memo_project_failed_title)))
            assertFalse(has(str(Res.string.memo_project_empty)))
            buttons(str(Res.string.memo_rec_retry)).onFirst().tap()
            waitForIdle()
            assertEquals(MemoAction.OpenTargetProject(MemoFx.PAIRLET), host.actions.last(), "retry re-opens the same project")
            host.state = MemoFx.detail(catalog = MemoFx.catalog(project = MemoFx.pairletLevel(MemoListStatus.READY, emptyList())))
            waitForIdle()
            assertTrue(has(str(Res.string.memo_project_empty)))
            assertTrue(has(str(Res.string.memo_new_session_in_project)), "…with the new-session entry still first")
        }
    }

    @Test
    fun newSessionExpandsPreselectsTheDefaultAgentAndSelectsTheOneChosen() {
        val host = Host(MemoFx.detail(catalog = MemoFx.catalog(project = MemoFx.pairletLevel())))
        picker(host) {
            assertFalse(has(str(Res.string.memo_pick_new_session)), "collapsed at first")
            tapText(str(Res.string.memo_new_session_in_project))
            assertTrue(has(str(Res.string.memo_default)), "the default agent is marked")
            assertTrue(has(str(Res.string.mode_default_label)), "the app's default mode, by the chat's own mode names")
            assertTrue(has(str(Res.string.memo_permission_readonly_note)))
            onAllNodes(hasText("Codex")).onFirst().tap()
            waitForIdle()
            buttons(str(Res.string.memo_pick_new_session)).onFirst().tap()
            waitForIdle()
            assertEquals(MemoAction.SelectNewSession(MemoFx.PAIRLET, AgentKind.CODEX), host.actions.last())
            assertEquals(1, host.closes)

            // untouched, the default is what gets selected
            host.state = MemoFx.detail(catalog = MemoFx.catalog(project = MemoFx.pairletLevel().copy(workdir = MemoFx.DOCS, name = "docs-site")))
            waitForIdle()
            tapText(str(Res.string.memo_new_session_in_project))
            buttons(str(Res.string.memo_pick_new_session)).onFirst().tap()
            waitForIdle()
            assertEquals(MemoAction.SelectNewSession(MemoFx.DOCS, AgentKind.CLAUDE), host.actions.last())
        }
    }

    @Test
    fun withNoAgentsNewSessionIsDisabledAndSaysWhy() {
        val host = Host(MemoFx.detail(catalog = MemoFx.catalog(project = MemoFx.pairletLevel(), newSession = MemoNewSessionOptions())))
        picker(host) {
            assertTrue(has(str(Res.string.memo_new_session_offline)))
            onAllNodes(hasText(str(Res.string.memo_new_session_in_project))).onFirst().performClick()
            waitForIdle()
            assertFalse(has(str(Res.string.memo_pick_new_session)), "a disabled entry does not expand")
            assertTrue(host.actions.none { it is MemoAction.SelectNewSession })
        }
    }

    @Test
    fun permissionModesUseTheAppsNamesAndFallBackToTheRawWord() {
        for ((mode, expected) in listOf(
            "ACCEPT_EDITS" to str(Res.string.mode_accept_label),
            "plan" to str(Res.string.mode_plan_label),
            "auto" to str(Res.string.mode_auto_label),
            "someNewMode" to "someNewMode",
        )) {
            val host = Host(MemoFx.detail(catalog = MemoFx.catalog(project = MemoFx.pairletLevel(), newSession = MemoFx.newOptions.copy(mode = mode))))
            picker(host) {
                tapText(str(Res.string.memo_new_session_in_project))
                assertTrue(has(expected), "$mode → $expected")
            }
        }
    }

    // ── result entry and confirm ────────────────────────────────────────────────────────────────────

    @Test
    fun theConfirmSheetTellsAnExistingSessionFromANewOne() {
        val existing = MemoFx.targetRow.copy(mode = "ACCEPT_EDITS")
        var state by mutableStateOf(MemoFx.detail(selection = MemoFx.readySelection(MemoFx.threeDrafts).copy(target = existing)))
        val actions = mutableListOf<MemoAction>()
        memoScene(content = { VoiceMemoScreen(state, 0f, { actions += it }, {}) }) {
            val sendExisting = str(Res.string.memo_dispatch_n, 3)
            assertTrue(has("Pairlet · 移动端构建排查 · Claude"))
            buttons(sendExisting).onFirst().tap()
            waitForIdle()
            assertTrue(has(str(Res.string.memo_confirm_existing)))
            assertTrue(has(str(Res.string.memo_confirm_mode_line, str(Res.string.mode_accept_label))), "the session's own mode")
            assertFalse(has(str(Res.string.memo_confirm_new_note)))
            buttons(str(Res.string.memo_back_edit)).onFirst().tap()
            waitForIdle()

            state = MemoFx.detail(selection = MemoFx.readySelection(MemoFx.threeDrafts).copy(target = MemoFx.newTarget))
            waitForIdle()
            val sendNew = str(Res.string.memo_dispatch_new_n, 3)
            assertTrue(has("scratch · ${str(Res.string.memo_target_new_session)} · Codex"), "the entry says it is a new session")
            assertEquals(0, buttonCount(sendExisting))
            buttons(sendNew).onFirst().tap()
            waitForIdle()
            assertTrue(has(str(Res.string.memo_target_new_session)), "the tag")
            assertTrue(has(str(Res.string.memo_confirm_new_note)), "the first-message sentence")
            assertTrue(has(str(Res.string.memo_confirm_mode_line, str(Res.string.mode_default_label))), "the app default mode")
            assertTrue(has(str(Res.string.memo_multi_notice)), "the same-turn notice holds for a new session too")
            assertFalse(has(str(Res.string.memo_confirm_existing)))
            buttons(sendNew).onLast().tap()
            waitForIdle()
            val confirmed = actions.filterIsInstance<MemoAction.ConfirmDispatch>().single()
            assertTrue(confirmed.target.newSession && confirmed.target.workdir == MemoFx.SCRATCH)
        }
    }

    @Test
    fun anUnnamedSessionReadsNewSession() {
        val unnamed = MemoFx.targetRow.copy(target = MemoFx.target.copy(title = ""))
        val state = MemoFx.detail(selection = MemoFx.readySelection(MemoFx.threeDrafts).copy(target = unnamed))
        memoScene(content = { VoiceMemoScreen(state, 0f, {}, {}) }) {
            assertTrue(has("Pairlet · ${str(Res.string.memo_target_untitled)} · Claude"), "a just-created session without a name reads 新会话")
        }
    }

    // ── dispatch in progress / failed, on the result page ───────────────────────────────────────────

    private fun batch(phase: MemoDispatchPhase, creating: Boolean, memoId: String = "m1", failure: String? = null) = MemoDispatchState(
        batchId = "b-$phase-$creating", memoId = memoId, memoTitle = "出门想到的三件事",
        target = if (creating) MemoFx.newTarget.target else MemoFx.target, convoId = null,
        phase = phase, creating = creating, openFailure = failure, total = 3,
    )

    @Test
    fun whileOpeningTheResultPageSaysSoAndOffersNothingElse() {
        val sel = MemoFx.readySelection(MemoFx.threeDrafts).copy(target = MemoFx.newTarget)
        var state by mutableStateOf(MemoFx.detail(selection = sel).copy(dispatch = batch(MemoDispatchPhase.OPENING, creating = true)))
        val actions = mutableListOf<MemoAction>()
        memoScene(content = { VoiceMemoScreen(state, 0f, { actions += it }, {}) }) {
            assertTrue(has(str(Res.string.memo_notice_creating)))
            assertTrue(has(str(Res.string.memo_notice_creating_sub, "scratch", "Codex", 3)))
            buttons(str(Res.string.memo_dispatch_new_n, 3)).onFirst().assertIsNotEnabled()
            onAllNodes(hasContentDescription(str(Res.string.memo_target_change_cd, "scratch · ${str(Res.string.memo_target_new_session)} · Codex"))).onFirst().performClick()
            waitForIdle()
            assertTrue(actions.none { it == MemoAction.RefreshTargets }, "the target cannot be changed mid-open")

            state = MemoFx.detail().copy(dispatch = batch(MemoDispatchPhase.OPENING, creating = false))
            waitForIdle()
            assertTrue(has(str(Res.string.memo_notice_opening)) && !has(str(Res.string.memo_notice_creating)))

            state = MemoFx.detail().copy(dispatch = batch(MemoDispatchPhase.OPENING, creating = false, memoId = "other"))
            waitForIdle()
            assertFalse(has(str(Res.string.memo_notice_opening)), "another memo's batch is not this page's business")
            buttons(str(Res.string.memo_dispatch_n, 3)).onFirst().assertIsEnabled()
        }
    }

    @Test
    fun aFailedOpenOrCreateIsExplainedAndDismissedLocally() {
        var state by mutableStateOf(MemoFx.detail().copy(dispatch = batch(MemoDispatchPhase.OPEN_FAILED, creating = true, failure = MemoEnterFailure.TIMEOUT)))
        val actions = mutableListOf<MemoAction>()
        memoScene(content = { VoiceMemoScreen(state, 0f, { actions += it }, {}) }) {
            val tail = str(Res.string.memo_open_failed_tail, 3)
            assertTrue(has(str(Res.string.memo_open_failed_create_title)))
            assertTrue(has(str(Res.string.memo_two_sentences, str(Res.string.memo_enter_timeout, "alex-mac"), tail)), "reason + the fixed consequence")
            assertFalse(allScreenText().any { it.contains('%') })
            buttons(str(Res.string.memo_got_it)).onFirst().tap()
            waitForIdle()
            assertFalse(has(str(Res.string.memo_open_failed_create_title)), "知道了 folds it away")
            assertTrue(actions.isEmpty(), "…locally: nothing is sent, nothing retried")

            state = MemoFx.detail().copy(dispatch = batch(MemoDispatchPhase.OPEN_FAILED, creating = false, failure = "some_new_word"))
            waitForIdle()
            assertTrue(has(str(Res.string.memo_open_failed_title)), "a new batch's failure shows again")
            assertTrue(has(str(Res.string.memo_two_sentences, str(Res.string.memo_enter_other), tail)), "an unknown reason word reads as 'no specific reason'")
            buttons(str(Res.string.memo_dispatch_n, 3)).onFirst().assertIsEnabled()
        }
    }

    @Test
    fun theChatStripSaysCreatingAndThenCreated() {
        var state by mutableStateOf(batch(MemoDispatchPhase.OPENING, creating = true))
        memoScene(content = { MemoDispatchNotice(state, onReturnToMemo = {}) }) {
            assertTrue(has(str(Res.string.memo_notice_creating)) && has(str(Res.string.memo_notice_creating_sub, "scratch", "Codex", 3)))
            state = batch(MemoDispatchPhase.DONE, creating = true).copy(delivered = 3)
            waitForIdle()
            assertTrue(has(str(Res.string.memo_notice_done_created_sub, "Codex")))
            state = batch(MemoDispatchPhase.OPENING, creating = false)
            waitForIdle()
            assertTrue(has(str(Res.string.memo_notice_opening)))
        }
    }

    // ── 320 dp · 200 % type ─────────────────────────────────────────────────────────────────────────

    @Test
    fun atThreeTwentyAndTwoHundredPercentTheNewSessionSetupAndItsConfirmStayUsable() {
        val host = Host(MemoFx.detail(catalog = MemoFx.catalog(project = MemoFx.pairletLevel())))
        picker(host, width = SMALL_W, height = SMALL_H, fontScale = 2f) {
            tapText(str(Res.string.memo_new_session_in_project))
            val go = buttons(str(Res.string.memo_pick_new_session)).onFirst()
            go.performScrollTo()
            waitForIdle()
            assertInViewport(go, "选为目标会话", SMALL_W, SMALL_H)
            assertInViewport(onAllNodes(hasText("Pairlet")).onFirst(), "the project title", SMALL_W, SMALL_H)
        }
        val state = MemoFx.detail(selection = MemoFx.readySelection(MemoFx.threeDrafts).copy(target = MemoFx.newTarget))
        memoScene(width = SMALL_W, height = SMALL_H, fontScale = 2f, content = { VoiceMemoScreen(state, 0f, {}, {}) }) {
            val label = str(Res.string.memo_dispatch_new_n, 3)
            assertInViewport(buttons(label).onFirst(), "the result's dispatch button", SMALL_W, SMALL_H)
            buttons(label).onFirst().tap()
            waitForIdle()
            assertInViewport(buttons(label).onLast(), "the confirm sheet's button", SMALL_W, SMALL_H)
            assertInViewport(onAllNodes(hasText(str(Res.string.memo_multi_notice))).onFirst(), "the same-turn notice", SMALL_W, SMALL_H)
        }
    }
}
