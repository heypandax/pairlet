package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.assertPresent
import dev.ccpocket.app.data.ConnPhase
import dev.ccpocket.app.data.FileUpState
import dev.ccpocket.app.data.PendingFile
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.memo_new_session_offline
import dev.ccpocket.app.resources.new_session_title
import dev.ccpocket.app.resources.new_session_unavailable
import dev.ccpocket.app.resources.new_task_agent
import dev.ccpocket.app.resources.new_task_keeps_running
import dev.ccpocket.app.resources.new_task_keeps_running_approval
import dev.ccpocket.app.resources.new_task_keeps_running_question
import dev.ccpocket.app.resources.new_task_project
import dev.ccpocket.app.resources.new_task_send_failed
import dev.ccpocket.app.str
import dev.ccpocket.app.telemetry.TelEvent
import dev.ccpocket.app.telemetry.TelKey
import dev.ccpocket.app.telemetry.telemetryTap
import dev.ccpocket.app.theme.Metric
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.AskOption
import dev.ccpocket.protocol.AskQuestion
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SessionLive
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The chat header's "+" (new-session-from-chat-v1), rendered for real on the phone chat screen: where it sits, what
 * it says to a screen reader, how it answers when the computer can't be reached, and that the Fast Start sheet it
 * opens BORROWS this conversation's project and agent — the Projects FAB's sticky picks come back untouched.
 *
 * The send-path cases run in demo mode, which answers OpenSession / SendPrompt locally: the real repository path,
 * end to end, with no link.
 */
@OptIn(ExperimentalTestApi::class)
class NewSessionEntryTest {

    private fun account(id: String) = PairedDaemon(
        relay = "wss://test.invalid", accountId = id, daemonPub = "pub", deviceId = "dev", credential = "cred",
    )

    /** A Codex conversation: not the default agent, so a chip that merely echoed the default could not pass. */
    private fun live(convo: String, observing: Boolean = false) = SessionLive(
        convoId = convo, workdir = WORKDIR, sessionId = "s-$convo", observing = observing,
        mode = PermissionMode.DEFAULT, executing = false, agent = AgentKind.CODEX,
    )

    private fun ComposeUiTest.mountChat(
        acct: String,
        observing: Boolean = false,
        reach: ConnPhase = ConnPhase.Ready,
    ): PocketRepository {
        var repo: PocketRepository? = null
        setContent {
            val scope = rememberCoroutineScope()
            val r = remember {
                PocketRepository(scope, account(acct)).apply {
                    receiveForTest(live("c-$acct", observing))
                    phase.value = reach
                }
            }
            repo = r
            PocketTheme { Box(Modifier.requiredSize(390.dp, 760.dp)) { ChatScreen(r) } }
        }
        waitForIdle()
        return repo!!
    }

    /** The demo variant: a chat in [ORIGIN], reached through ORIGIN's session list, with the FAB's own sticky picks. */
    private fun ComposeUiTest.mountDemoChat(acct: String, sent: MutableList<Frame>): PocketRepository {
        var repo: PocketRepository? = null
        setContent {
            val scope = rememberCoroutineScope()
            val r = remember {
                PocketRepository(scope, account(acct)).apply {
                    enterDemo()
                    finishDemoConnect()
                    onSendForTest = { sent += it }
                    receiveForTest(SessionLive("c-$acct", ORIGIN, "s-$acct", executing = false, agent = AgentKind.CODEX))
                    sessionsDir.value = ORIGIN
                    newTaskDir.value = FAB_DIR
                    newTaskAgent.value = AgentKind.CLAUDE
                }
            }
            repo = r
            PocketTheme { Box(Modifier.requiredSize(390.dp, 760.dp)) { ChatScreen(r) } }
        }
        waitForIdle()
        return repo!!
    }

    private fun ComposeUiTest.plus(): SemanticsNodeInteraction = onNodeWithContentDescription(str(Res.string.new_session_title))
    private fun ComposeUiTest.greyedPlus(): SemanticsNodeInteraction = onNodeWithContentDescription(str(Res.string.new_session_unavailable))
    private fun ComposeUiTest.ellipsis(): SemanticsNodeInteraction = onNodeWithText("⋯")
    private fun ComposeUiTest.sheetOpen() =
        present(str(Res.string.new_task_keeps_running)) || present(str(Res.string.new_task_send_failed))

    /** A tap on the sheet's scrim, which covers the header: the dismissal a user makes by tapping above the sheet. */
    private fun ComposeUiTest.tapAboveTheSheet() { ellipsis().performClick(); waitForIdle() }

    private val isButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    // ── 1. where it sits ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun plusSitsLeftOfTheEllipsisAndIsNamedNewSession() = runComposeUiTest {
        mountChat("acct-nse-place")

        plus().assertExists().assertIsEnabled().assertHasClickAction().assert(isButton)
            .assertWidthIsEqualTo(Metric.touch).assertHeightIsEqualTo(Metric.touch)
        ellipsis().assertExists()
        // left of "⋯", in the same row, 6dp apart — the ellipsis keeps the slot's end it always had
        val p = plus().getUnclippedBoundsInRoot()
        val e = ellipsis().getUnclippedBoundsInRoot()
        assertTrue(abs((e.left - p.right).value - 6f) < 0.5f, "6dp between + and ⋯, was ${e.left - p.right}")
        assertTrue(abs((e.top - p.top).value) < 0.5f, "+ and ⋯ share the row: tops ${p.top} vs ${e.top}")
    }

    // ── 2. observing ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun observingHidesTheEllipsisButKeepsPlus() = runComposeUiTest {
        val repo = mountChat("acct-nse-observe", observing = true)
        assertTrue(repo.observing.value, "sanity: a read-only observe of a terminal session")

        ellipsis().assertDoesNotExist()
        plus().assertExists().assertIsEnabled()
    }

    // ── 3. unreachable ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun anUnreachableComputerGreysPlusOutInPlace() = runComposeUiTest {
        val repo = mountChat("acct-nse-offline")
        val reachable = plus().getUnclippedBoundsInRoot()
        val ellipsisBefore = ellipsis().getUnclippedBoundsInRoot()

        runOnIdle { repo.phase.value = ConnPhase.ComputerOffline }
        waitForIdle()

        greyedPlus().assertExists().assertIsNotEnabled().assert(isButton)
        plus().assertDoesNotExist() // one name at a time: the enabled one is gone
        assertEquals(reachable, greyedPlus().getUnclippedBoundsInRoot(), "greyed out in place, never hidden or moved")
        assertEquals(ellipsisBefore, ellipsis().getUnclippedBoundsInRoot(), "the header does not re-flow")
    }

    @Test
    fun tappingTheGreyedPlusSaysWhyForAboutFourSecondsAndOpensNothing() = runComposeUiTest {
        mainClock.autoAdvance = false
        mountChat("acct-nse-offline-tap", reach = ConnPhase.ComputerOffline)
        val why = str(Res.string.memo_new_session_offline)

        greyedPlus().performClick()
        mainClock.advanceTimeBy(100)
        waitForIdle()
        assertPresent(why)
        assertFalse(sheetOpen(), "a greyed-out + opens nothing")

        mainClock.advanceTimeBy(3_500)
        waitForIdle()
        assertPresent(why) // still there at ~3.6s
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        assertFalse(present(why), "gone by ~4.6s")
    }

    // ── 4. the sheet: borrowed picks, the quiet line, given back ─────────────────────────────────────

    @Test
    fun theSheetBorrowsThisChatsProjectAndAgentAndGivesThePicksBack() = runComposeUiTest {
        val repo = mountChat("acct-nse-sheet")
        runOnIdle {
            // the Projects FAB's sticky picks, left by an earlier Fast Start
            repo.newTaskDir.value = FAB_DIR
            repo.newTaskAgent.value = AgentKind.CLAUDE
        }

        plus().performClick()
        waitForIdle()

        onNodeWithContentDescription(str(Res.string.new_task_project)).assert(hasText("acme-web"))
        onNodeWithContentDescription(str(Res.string.new_task_agent)).assert(hasText("Codex"))
        assertPresent(str(Res.string.new_task_keeps_running))
        runOnIdle {
            assertEquals(WORKDIR, repo.newTaskDir.value)
            assertEquals(AgentKind.CODEX, repo.newTaskAgent.value)
        }

        tapAboveTheSheet()

        assertFalse(sheetOpen(), "the scrim tap closed the sheet")
        runOnIdle {
            assertEquals(FAB_DIR, repo.newTaskDir.value, "the FAB's project pick is back")
            assertEquals(AgentKind.CLAUDE, repo.newTaskAgent.value, "…and its agent pick")
        }
    }

    @Test
    fun theQuietLineSaysWhatTheChatLeftBehindStillWaitsOn() = runComposeUiTest {
        val repo = mountChat("acct-nse-quiet")
        runOnIdle {
            repo.pendingAsk.value = PermissionAsk(
                convoId = "c-acct-nse-quiet", askId = "ap-1", tool = "Bash", inputPreview = "rm -rf build", title = "Run command",
            )
        }
        plus().performClick()
        waitForIdle()
        assertPresent(str(Res.string.new_task_keeps_running_approval))
        assertFalse(present(str(Res.string.new_task_keeps_running)), "exactly one of the three lines")

        runOnIdle {
            repo.pendingAsk.value = PermissionAsk(
                convoId = "c-acct-nse-quiet", askId = "q-1", tool = "AskUserQuestion", inputPreview = "",
                questions = listOf(AskQuestion(question = "Which palette?", options = listOf(AskOption("Warm", null)))),
            )
        }
        waitForIdle()
        assertPresent(str(Res.string.new_task_keeps_running_question))
        assertFalse(present(str(Res.string.new_task_keeps_running_approval)))
    }

    // ── the send path (demo loopback) ────────────────────────────────────────────────────────────────

    @Test
    fun aSendLandsInTheNewSessionPointsBackAtItsProjectAndLeavesTheOldChatAsItWas() = runComposeUiTest {
        val events = mutableListOf<Pair<TelEvent, Map<TelKey, Any>>>()
        telemetryTap = { e, p -> synchronized(events) { events += e to p } }
        try {
            val sent = mutableListOf<Frame>()
            val repo = mountDemoChat("acct-nse-send", sent)
            assertEquals(ConnPhase.Ready, repo.phase.value, "the demo reaches Ready, so its + is live")
            val originConvo = repo.convoId.value
            val originKey = repo.composerKey()
            // a half-written note in this chat's composer: it belongs to THIS chat
            onAllNodes(hasSetTextAction()).onFirst().performTextInput("half-written note")
            waitForIdle()

            plus().performClick()
            waitForIdle()
            onNodeWithTag("new-task-prompt").performTextInput("audit the release script")
            runOnIdle { repo.newTaskDir.value = TARGET } // what a project-chip pick writes
            onNodeWithTag("new-task-send").performClick()
            waitForIdle()

            runOnIdle {
                val open = sent.filterIsInstance<OpenSession>().last()
                assertEquals(TARGET, open.workdir, "the session opens in the project on the chip")
                assertEquals(AgentKind.CODEX, open.agent, "…on the agent the chip borrowed from this chat")
                assertNull(open.resumeId, "a NEW session")
                assertEquals(listOf("audit the release script"), sent.filterIsInstance<SendPrompt>().map { it.text })
                assertNotNull(repo.convoId.value)
                assertNotEquals(originConvo, repo.convoId.value, "the app is in the new conversation")
                assertEquals(TARGET, repo.sessionsDir.value, "BACK leads to the new project's list, not the one we came from")
                assertFalse(repo.switchingSession.value, "the hold over the chat is released once it lands")
                assertEquals(FAB_DIR, repo.newTaskDir.value, "the FAB's sticky project pick is given back")
                assertEquals(AgentKind.CLAUDE, repo.newTaskAgent.value, "…and its agent pick")
                assertEquals("half-written note", repo.draftFor(originKey), "the chat left behind keeps its draft")
            }
            assertFalse(sheetOpen(), "the sheet closed on the send")
            assertFalse(present("half-written note"), "the old chat's draft is not carried into the new chat's composer")

            val used = synchronized(events) { events.filter { it.first == TelEvent.FeatureUsed }.map { it.second } }
            val entry = used.single { it[TelKey.Feature] == "new_session_entry" }
            assertEquals("header", entry[TelKey.Target])
            val result = used.single { it[TelKey.Feature] == "new_session_result" }
            assertEquals("delivered", result[TelKey.Result])
            assertEquals("header", result[TelKey.Target])
        } finally {
            telemetryTap = null
        }
    }

    /** Demo mode is how App Review and first-time users meet the app. Its responder used to hand EVERY brand-new open
     *  the same convoId, so a "+" pressed inside a new demo session waited out startTaskWithPrompt's window for a
     *  conversation that never looked new — and the prompt was never sent. */
    @Test
    fun inTheDemoANewSessionCanStartAnotherOne() = runComposeUiTest {
        val sent = mutableListOf<Frame>()
        val repo = mountDemoChat("acct-nse-demo-twice", sent)
        runOnIdle { repo.openSession(ORIGIN) } // the dock's one-tap new session: a brand-new demo conversation
        waitForIdle()
        val first = repo.convoId.value
        assertNotNull(first, "sanity: the first new demo session landed")

        plus().performClick()
        waitForIdle()
        onNodeWithTag("new-task-prompt").performTextInput("and another one")
        onNodeWithTag("new-task-send").performClick()
        waitForIdle()

        runOnIdle {
            assertNull(repo.newTaskError.value)
            assertEquals(listOf("and another one"), sent.filterIsInstance<SendPrompt>().map { it.text })
            assertNotEquals(first, repo.convoId.value, "a second new conversation, not the first one again")
        }
    }

    /** The open lands but the prompt is refused (here: an upload still running from the chat that was left). The
     *  chat is still on screen — the hold kept it — so the sheet comes back HERE with the draft and the picks as sent,
     *  and only an ordinary dismissal afterwards gives the FAB's picks back. */
    @Test
    fun aRefusedFirstPromptReopensTheSheetInTheChatWithThePicksAsSent() = runComposeUiTest {
        val events = mutableListOf<Pair<TelEvent, Map<TelKey, Any>>>()
        telemetryTap = { e, p -> synchronized(events) { events += e to p } }
        try {
            val sent = mutableListOf<Frame>()
            val repo = mountDemoChat("acct-nse-refused", sent)
            runOnIdle {
                repo.pendingFiles.add(
                    PendingFile(1L, "build.log", 10L, ByteArray(10), "text/plain", FileUpState.Uploading),
                )
            }

            plus().performClick()
            waitForIdle()
            onNodeWithTag("new-task-prompt").performTextInput("audit the release script")
            runOnIdle { repo.newTaskDir.value = TARGET }
            onNodeWithTag("new-task-send").performClick()
            waitForIdle()

            runOnIdle {
                assertEquals(PocketRepository.NewTaskError.SEND_REFUSED, repo.newTaskError.value)
                assertTrue(sent.filterIsInstance<SendPrompt>().isEmpty(), "nothing was sent")
                assertEquals("audit the release script", repo.newTaskDraft.value, "the draft is held")
                assertEquals(TARGET, repo.sessionsDir.value, "the new session did open: BACK leads to its project")
                assertEquals(TARGET, repo.newTaskDir.value, "the sheet comes back with the project as sent")
                assertEquals(AgentKind.CODEX, repo.newTaskAgent.value, "…and the agent as sent")
            }
            assertPresent(str(Res.string.new_task_send_failed))

            tapAboveTheSheet()
            runOnIdle {
                assertEquals(FAB_DIR, repo.newTaskDir.value, "an ordinary dismissal gives the FAB's picks back")
                assertEquals(AgentKind.CLAUDE, repo.newTaskAgent.value)
            }
            val result = synchronized(events) {
                events.filter { it.first == TelEvent.FeatureUsed }.map { it.second }
            }.single { it[TelKey.Feature] == "new_session_result" }
            assertEquals("send_refused", result[TelKey.Result])
        } finally {
            telemetryTap = null
        }
    }

    private companion object {
        const val WORKDIR = "/w/acme-web"
        const val ORIGIN = "/w/nse-origin"
        const val TARGET = "/w/nse-target"
        const val FAB_DIR = "/w/billing"
    }
}
