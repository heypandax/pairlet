package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.advanceFrameAndWait
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.dir_projects
import dev.ccpocket.app.resources.new_session_cta
import dev.ccpocket.app.resources.proj_search
import dev.ccpocket.app.resources.question_answer
import dev.ccpocket.app.resources.question_freeform_back
import dev.ccpocket.app.resources.question_freeform_link
import dev.ccpocket.app.resources.switcher_current
import dev.ccpocket.app.resources.switcher_open
import dev.ccpocket.app.resources.wide_pick_session
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.ActiveSession
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.AskOption
import dev.ccpocket.protocol.AskQuestion
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.DirectoryEntry
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PermissionVerdict
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.isQuestion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A large screen resized across the two-pane line keeps what was on it (issue #334, second stage).
 *
 * With iPad multitasking the window can be resized under an open chat, and 700dp is one drag away. The two shapes
 * used to call the chat — and the list — from separate parents, so every crossing rebuilt them: the draft, the
 * reading position, a half-answered question and the open sheet were gone. Carrying that state across was not enough
 * either: a field taken out of the layout tree loses focus, and on a device the keyboard goes down with it. These
 * mount the REAL routing root, the `WideLayoutScope { ContentRouter }` pair App() uses, as a
 * [LayoutDeviceClass.LARGE_SCREEN] and resize it inside ONE composition, which is what a host that keeps its
 * composition does when its window changes size.
 *
 * What these are, honestly: offscreen Compose-desktop fixtures, NOT iPad acceptance. Focus here is Compose's own focus
 * state, read off the Focused semantics; no iPadOS window mode, software or hardware keyboard, IME or touch scrolling
 * is exercised, so they cannot show a keyboard staying up. Nor is the Secure Approval sheet: App() draws it outside
 * the router, so it is not mounted here at all. The question card is, because it docks inside the chat.
 */
@OptIn(ExperimentalTestApi::class)
class WideLayoutContinuityTest {

    private class Scene(start: DpSize) {
        var size by mutableStateOf(start)
        val listState = LazyListState()
        lateinit var repo: PocketRepository
        var wide: Boolean? = null
    }

    /** Mounts the root pair as a large screen at [start], on a canvas roomy enough for every size a test turns it to. */
    private fun scene(
        tag: String,
        start: DpSize = TABLET,
        seed: PocketRepository.() -> Unit,
        block: SkikoComposeUiTest.(Scene) -> Unit,
    ) = runSkikoComposeUiTest(Size(CANVAS.width.value, CANVAS.height.value), Density(1f)) {
        // The v2 runner and a frozen clock, as in PhoneLandscapeLayoutTest: a launched effect is queued for the
        // scheduler rather than started inside the measure pass a resize subcomposes in, and every assertion reads
        // a settled frame of a chat that otherwise keeps animating.
        mainClock.autoAdvance = false
        val s = Scene(start)
        setContent {
            val scope = rememberCoroutineScope()
            s.repo = remember {
                PocketRepository(
                    scope,
                    PairedDaemon(relay = "wss://test.invalid", accountId = "acct-334-$tag", daemonPub = "pub", deviceId = "dev", credential = "cred"),
                ).apply(seed)
            }
            PocketTheme {
                // loose constraints, top-start: the scope measures exactly the size under test
                Box(Modifier.fillMaxSize()) {
                    WideLayoutScope(Modifier.size(s.size).background(Tok.base), policy = WideLayoutPolicy(LayoutDeviceClass.LARGE_SCREEN)) {
                        s.wide = LocalWideLayout.current
                        ContentRouter(s.repo, chatListStateForTest = s.listState)
                    }
                }
            }
        }
        advanceFrameAndWait()
        block(s)
    }

    @Test
    fun crossingTheLineKeepsTheChatsDraftReadingPositionAndOpenSheet() = scene("chat", seed = { seedChat("chat") }) { s ->
        awaitLanded(s)
        val convo = s.repo.convoId.value
        val session = s.repo.sessionKey.value
        composer().performClick()
        advanceFrameAndWait()
        composer().performTextInput(DRAFT)
        composer().assertIsFocused()
        parkMidTranscript(s)
        openSwitcher()
        // a sheet has no text input, so it takes focus off the composer as it opens (PocketSheet)
        composer().assertIsNotFocused()

        crossTheLine(s) { moment ->
            assertEquals(convo, s.repo.convoId.value, "$moment: the same conversation is open")
            assertEquals(session, s.repo.sessionKey.value, "$moment: …as the same session")
            assertEquals(DRAFT, composerText(), "$moment: the unsent draft is still in the field")
            // a rebuilt chat lands back on its latest message — staying put is what shows it was kept instead
            assertEquals(READ_AT, s.listState.firstVisibleItemIndex, "$moment: the reader is still on the same message")
            assertTrue(sheetShowing(), "$moment: the switcher the user opened is still open")
            assertFalse(composerFocused(), "$moment: …and no crossing gives focus back to the composer under it")
            // …and the kept sheet measures itself for the shape it is now in. Had the chat kept the old LocalWideLayout,
            // the sheet would be capped on the 699dp window or stretched across the 1024dp window's chat pane.
            val column = chatColumnWidth(s.size.width.value.toInt(), s.wide == true)
            assertEquals(
                if (s.wide == true) minOf(SHEET_MEASURE_MAX.value.toInt(), column) else column,
                sheetWidth(),
                "$moment: the sheet's width follows the shape it is in",
            )
        }
    }

    /**
     * Focus comes through a crossing too. A field taken out of the layout tree loses focus even when its state is carried
     * along, so the chat has to stay where it is, not only keep what it held. The selection the user left in the draft
     * is the field's own, and it comes through with it.
     */
    @Test
    fun crossingTheLineKeepsTheComposerFocusedWithItsSelection() = scene("focus", seed = { seedChat("focus") }) { s ->
        awaitLanded(s)
        composer().performClick()
        advanceFrameAndWait()
        composer().assertIsFocused()
        composer().performTextInput(DRAFT)
        advanceFrameAndWait() // a selection is checked against the text the field last composed, so the draft lands first
        composer().performTextInputSelection(SELECTION)
        advanceFrameAndWait()
        assertEquals(SELECTION, composerSelection(), "sanity: part of the draft is selected")

        crossTheLine(s) { moment ->
            assertTrue(composerFocused(), "$moment: the composer still has focus")
            assertEquals(DRAFT, composerText(), "$moment: …the draft")
            assertEquals(SELECTION, composerSelection(), "$moment: …and the selection the user left in it")
        }
        composer().assertIsFocused()
        // what is typed next replaces that selection, as it would have before the window changed size
        composer().performTextInput(TYPED)
        advanceFrameAndWait()
        assertEquals(DRAFT.replaceRange(SELECTION.min, SELECTION.max, TYPED), composerText())

        // …but a closed chat keeps no focus. Closed while the composer has it and reopened, the chat composes fresh, and
        // a resumed session does not take focus by itself.
        s.repo.backToBrowse()
        advanceFrameAndWait()
        assertTrue(placeholderShowing(), "sanity: the chat closed")
        assertTrue(s.repo.openSession(CHAT_DIR, resumeId = "s-334-focus"), "sanity: the reopen was accepted")
        advanceFrameAndWait()
        s.repo.receiveForTest(live("focus", convo = "c-334-focus-again"))
        s.repo.receiveForTest(history("c-334-focus-again"))
        advanceFrameAndWait()
        assertFalse(placeholderShowing(), "sanity: the chat reopened")
        mainClock.advanceTimeBy(FOCUS_GRACE_MS)
        advanceFrameAndWait()
        composer().assertIsNotFocused()
    }

    /**
     * A crossing never gives the composer focus it did not have. Untouched, it stays unfocused; and when the user has
     * moved focus to another field, the Projects search in the pane beside the chat, a crossing that covers that field
     * leaves focus with nothing rather than handing it to the composer.
     */
    @Test
    fun crossingTheLineNeverHandsTheComposerFocus() = scene("elsewhere", seed = { seedChat("elsewhere") }) { s ->
        awaitLanded(s)
        crossTheLine(s, rounds = 1) { moment -> assertFalse(composerFocused(), "$moment: an untouched composer stays unfocused") }

        // the user types in the composer, then opens the search beside the chat, which lands focused (#260)
        composer().performClick()
        advanceFrameAndWait()
        composer().performTextInput(DRAFT)
        composer().assertIsFocused()
        onNode(hasContentDescription(str(Res.string.proj_search))).performClick()
        advanceFrameAndWait()
        onNodeWithTag(PROJECTS_SEARCH_TAG).assertIsFocused()
        composer().assertIsNotFocused()

        crossTheLine(s) { moment ->
            assertFalse(composerFocused(), "$moment: focus the user moved away is not handed back to the composer")
            assertEquals(DRAFT, composerText(), "$moment: …which still holds the draft")
            // one column covers the list, and the list that comes back beside the chat is a fresh one
            assertNull(searchText(), "$moment: the search is not open: the list it was in was covered and came back fresh")
        }
    }

    @Test
    fun crossingTheLineKeepsAHalfAnsweredQuestion() = scene("ask", seed = { seedChat("ask"); receiveForTest(question("ask")) }) { s ->
        awaitLanded(s)
        val ask = s.repo.pendingAsk.value
        assertTrue(ask?.isQuestion == true, "sanity: the question is this chat's pending ask")
        composer().performTextInput(DRAFT)
        // A single-select pick steps the card on to the next question. Which question is showing, and what was
        // picked, live only in the card's own composition.
        onAllNodes(hasText("Warm")).onFirst().performClick()
        advanceFrameAndWait()
        assertTrue(present(DENSITY) && !present(PALETTE), "sanity: the pick moved the card on to the second question")
        answerButton().assertIsNotEnabled()

        crossTheLine(s) { moment ->
            assertEquals(ask, s.repo.pendingAsk.value, "$moment: the same question is pending")
            // the repository cannot show this part: the rendered card has to still be on the second question
            assertTrue(present(DENSITY), "$moment: the card is on screen, on the question the user reached")
            assertFalse(present(PALETTE), "$moment: …not back on the first")
            assertEquals(DRAFT, composerText(), "$moment: the draft under the card is still there")
        }

        // the first pick survived every crossing, so the second is all Answer still needs
        onAllNodes(hasText("Roomy")).onFirst().performClick()
        advanceFrameAndWait()
        answerButton().assertIsEnabled()
        var verdict: PermissionVerdict? = null
        s.repo.onSendForTest = { if (it is PermissionVerdict) verdict = it }
        answerButton().performClick()
        advanceFrameAndWait()
        assertEquals(mapOf(PALETTE to "Warm", DENSITY to "Roomy"), verdict?.answers, "both picks reach the daemon")
    }

    /**
     * Focus in the question card's own field comes through a crossing, and the composer that yields to that field
     * (design ③) neither comes back nor takes focus meanwhile. Leaving the field afterwards hands nothing to the composer.
     */
    @Test
    fun crossingTheLineLeavesFocusInTheQuestionCardsField() = scene(
        "freeform", seed = { seedChat("freeform"); receiveForTest(question("freeform")) },
    ) { s ->
        awaitLanded(s)
        composer().performClick()
        advanceFrameAndWait()
        composer().performTextInput(DRAFT)
        composer().assertIsFocused()
        // answering in the user's own words opens a field in the card, above the composer, and a tap moves focus into it
        onNode(hasText(str(Res.string.question_freeform_link))).performClick()
        advanceFrameAndWait()
        assertEquals(listOf("", DRAFT), editableTexts(), "sanity: the card's empty field sits above the composer")
        onAllNodes(hasSetTextAction())[0].performClick()
        advanceFrameAndWait()
        onAllNodes(hasSetTextAction())[0].performTextInput(ANSWER)
        advanceFrameAndWait()
        assertEquals(listOf(ANSWER), editableTexts(), "sanity: the composer yields while the card's field has focus")
        onAllNodes(hasSetTextAction()).onFirst().assertIsFocused()

        crossTheLine(s) { moment ->
            assertEquals(listOf(ANSWER), editableTexts(), "$moment: the card's field is still the only one, answer and all")
            assertTrue(onAllNodes(hasSetTextAction()).onFirst().fetchSemanticsNode().focused(), "$moment: …and it still has focus")
        }

        // back to the options: the card's field goes, the composer returns with its draft, and focus is not handed to it.
        // Activated by its click action, as a screen reader would: a desktop test pointer press focuses the link, which
        // brings the composer back and moves the card before the release, with or without a crossing.
        onNode(hasText(str(Res.string.question_freeform_back))).performSemanticsAction(SemanticsActions.OnClick)
        advanceFrameAndWait()
        assertEquals(listOf(DRAFT), editableTexts(), "the composer is back, draft and all")
        composer().assertIsNotFocused()
    }

    /**
     * Crossing keeps; closing does not. After the chat has stayed through crossings, closing it still takes everything
     * it held on screen, and a switch still starts the next session from that session's own state — neither brings focus.
     */
    @Test
    fun aChatClosedOrSwitchedAwayFromStartsFreshAfterCrossings() = scene("fresh", seed = { seedChat("fresh") }) { s ->
        val tail = awaitLanded(s)
        composer().performTextInput(DRAFT)
        mainClock.advanceTimeBy(DRAFT_SAVE_MS) // any pause in typing lets the debounced saver keep the draft
        parkMidTranscript(s)
        openSwitcher()
        crossTheLine(s, rounds = 1) { moment -> assertTrue(sheetShowing(), "$moment: sanity: the switcher came along") }

        // close with the sheet up and the reader mid-transcript
        s.repo.backToBrowse()
        advanceFrameAndWait()
        assertNull(s.repo.convoId.value)
        assertTrue(listPaneShowing() && placeholderShowing(), "a closed chat leaves the list beside the empty pane")
        assertFalse(sheetShowing(), "…and takes its sheet with it")
        // with nothing open, a crossing carries the list alone
        s.size = NARROW
        advanceFrameAndWait()
        assertTrue(listPaneShowing() && !placeholderShowing(), "one column: the list alone")
        s.size = TABLET
        advanceFrameAndWait()

        // reopening the same session composes a new chat: nothing the closed one held on screen comes back
        assertTrue(s.repo.openSession(CHAT_DIR, resumeId = "s-334-fresh"), "sanity: the reopen was accepted")
        advanceFrameAndWait()
        s.repo.receiveForTest(live("fresh", convo = "c-334-fresh-again"))
        s.repo.receiveForTest(history("c-334-fresh-again"))
        assertTrue(awaitLanded(s) >= tail, "the reopened chat opens on its latest message, not where the closed one was read")
        assertEquals("c-334-fresh-again", s.repo.convoId.value)
        assertFalse(sheetShowing(), "the sheet left open when the chat closed does not reopen with it")
        assertEquals(DRAFT, composerText(), "the draft comes back from the store, as reopening always has")
        // …without focus: only a brand-new session focuses its composer as it opens, never a resumed one
        composer().assertIsNotFocused()

        // switch through the real sheet row on a narrow window, where the sheet is the only list on screen…
        s.size = NARROW
        advanceFrameAndWait()
        openSwitcher()
        onAllNodes(hasText(OTHER_TITLE)).onFirst().performClick()
        advanceFrameAndWait()
        assertTrue(s.repo.switchingSession.value, "sanity: the switch is in flight")
        // …and cross back to two panes before the daemon answers: the held chat keeps its pane (#165)
        s.size = TABLET
        advanceFrameAndWait()
        advanceFrameAndWait()
        assertEquals(true, s.wide)
        assertFalse(placeholderShowing(), "an in-flight switch holds the chat across the crossing")
        assertEquals(0, s.listState.firstVisibleItemIndex, "sanity: the switch emptied the transcript it left")
        s.repo.receiveForTest(live("fresh", convo = "c-334-other-fresh", dir = OTHER_DIR, session = "s-334-other-fresh"))
        s.repo.receiveForTest(history("c-334-other-fresh"))
        assertTrue(awaitLanded(s) >= tail, "the session switched into opens on its latest message")
        assertEquals("s-334-other-fresh", s.repo.sessionKey.value, "the chat is the switched-into session")
        assertFalse(s.repo.switchingSession.value, "…and the landed switch released the router")
        // read the composer in one column, where it is the only text field on screen
        s.size = NARROW
        advanceFrameAndWait()
        assertEquals("", composerText(), "it starts from its own draft, not the text left in the other session's field")
        assertEquals(DRAFT, s.repo.draftFor("s-334-fresh"), "…which the switch saved under the session it left")
        assertFalse(sheetShowing(), "the switcher closed on the tap")
        composer().assertIsNotFocused()
    }

    @Test
    fun crossingTheLineKeepsTheProjectsListAsItWas() = scene("list", seed = { seedProjects() }) { s ->
        assertTrue(listPaneShowing() && placeholderShowing(), "sanity: Projects beside the empty chat pane")
        onNode(hasContentDescription(str(Res.string.proj_search))).performClick()
        advanceFrameAndWait()
        onNodeWithTag(PROJECTS_SEARCH_TAG).performTextInput(QUERY)
        advanceFrameAndWait()
        onNodeWithTag(PROJECTS_SEARCH_TAG).assertIsFocused()

        crossTheLine(s) { moment ->
            assertEquals(s.wide, placeholderShowing(), "$moment: the empty chat pane is there only beside the list")
            assertEquals(QUERY, searchText(), "$moment: the search being typed is still open, query and all")
            assertTrue(searchFocused(), "$moment: …and still focused")
        }

        // a chat opened beside the list leaves the list pane exactly as it was…
        s.repo.receiveForTest(live("list"))
        s.repo.receiveForTest(history("c-334-list"))
        advanceFrameAndWait()
        assertFalse(placeholderShowing(), "sanity: the chat took the right pane")
        assertEquals(QUERY, searchText(), "opening a chat beside the list does not rebuild it")
        // …but a narrow window's chat covers the list, and the list that returns after it is a fresh one
        s.size = NARROW
        advanceFrameAndWait()
        assertNull(searchText(), "sanity: one column, the chat alone")
        s.repo.backToBrowse()
        advanceFrameAndWait()
        assertTrue(listPaneShowing(), "back lands on Projects")
        assertNull(searchText(), "a list the chat covered comes back fresh, its search closed")
    }

    /**
     * 1024 → 699 → 700 → 320 → 1024, [rounds] times: both sides of the line, the line itself and a slim window, in one
     * composition. [assertKept] runs on each settled frame, with the moment named.
     */
    private fun SkikoComposeUiTest.crossTheLine(s: Scene, rounds: Int = 2, assertKept: (moment: String) -> Unit) {
        repeat(rounds) { round ->
            for ((size, wide) in CROSSINGS) {
                s.size = size
                advanceFrameAndWait()
                advanceFrameAndWait()
                val moment = "round ${round + 1} at ${size.width.value.toInt()}dp"
                assertEquals(wide, s.wide, "$moment: ${if (wide) "two panes" else "one column"}")
                assertKept(moment)
            }
        }
    }

    /**
     * An open chat long enough to scroll, plus one live session in another project — the only thing that gives the
     * composer its switcher chip. Ids are per test: drafts are kept by session id in a store the test JVM shares.
     */
    private fun PocketRepository.seedChat(tag: String) {
        receiveForTest(live(tag))
        receiveForTest(history("c-334-$tag"))
        directories.add(
            DirectoryEntry(
                path = OTHER_DIR, name = "billing", isDir = true, open = true,
                activeSessions = listOf(ActiveSession(sessionId = "s-334-other-$tag", title = OTHER_TITLE)),
                activeSessionId = "s-334-other-$tag", activeSessionTitle = OTHER_TITLE,
            ),
        )
    }

    private fun PocketRepository.seedProjects() {
        directories.add(DirectoryEntry(path = CHAT_DIR, name = "relay-server", isDir = true, hasSessions = true))
        directories.add(DirectoryEntry(path = OTHER_DIR, name = "billing", isDir = true, hasSessions = true))
    }

    private fun live(tag: String, convo: String = "c-334-$tag", dir: String = CHAT_DIR, session: String = "s-334-$tag") =
        SessionLive(
            convoId = convo, workdir = dir, sessionId = session,
            mode = PermissionMode.DEFAULT, executing = false, model = "claude-fable-5", agent = AgentKind.CLAUDE,
        )

    private fun history(convo: String) = ConvoHistory(
        convo,
        (1..60).map {
            if (it % 2 == 0) {
                HistoryMessage(ChatRole.ASSISTANT, "Step $it: retry the relay handshake with jittered backoff, then re-check the session lock before resuming.")
            } else {
                HistoryMessage(ChatRole.USER, "What does step ${it + 1} change?")
            }
        },
    )

    /** Two single-select questions, so a pick visibly moves the card on. */
    private fun question(tag: String) = PermissionAsk(
        convoId = "c-334-$tag", askId = "q-334-$tag", tool = "AskUserQuestion", inputPreview = "",
        questions = listOf(
            AskQuestion(PALETTE, options = listOf(AskOption("Warm", null), AskOption("Cool", null))),
            AskQuestion(DENSITY, options = listOf(AskOption("Compact", null), AskOption("Roomy", null))),
        ),
    )

    /** Frames until the chat has landed on its latest message, as every open does; returns where it parked. */
    private fun SkikoComposeUiTest.awaitLanded(s: Scene): Int {
        repeat(60) {
            if (s.listState.firstVisibleItemIndex > READ_AT) return s.listState.firstVisibleItemIndex
            advanceFrameAndWait()
        }
        error("the chat never landed on its latest message")
    }

    /** Scroll back into the history, where someone partway through a long reply would be. */
    private fun SkikoComposeUiTest.parkMidTranscript(s: Scene) {
        runOnIdle { s.listState.requestScrollToItem(READ_AT) }
        advanceFrameAndWait()
        assertEquals(READ_AT, s.listState.firstVisibleItemIndex, "sanity: parked mid-transcript")
    }

    private fun SkikoComposeUiTest.openSwitcher() {
        onNode(hasContentDescription(str(Res.string.switcher_open))).performClick()
        advanceFrameAndWait()
        assertTrue(sheetShowing(), "sanity: the stack chip opened the session switcher")
    }

    /** Where the chat is laid out: the whole window in one column, else what the list pane and its hairline leave. */
    private fun chatColumnWidth(width: Int, wide: Boolean) =
        if (wide) width - minOf(LIST_PANE, width - CHAT_PANE_MIN) - HAIRLINE else width

    /** The chat's text field: the first editable field that is not the Projects search in the pane beside the chat. */
    private fun SkikoComposeUiTest.composer() = onAllNodes(hasSetTextAction() and !hasTestTag(PROJECTS_SEARCH_TAG)).onFirst()

    private fun SkikoComposeUiTest.composerText() =
        composer().fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun SkikoComposeUiTest.composerSelection() =
        composer().fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]

    private fun SkikoComposeUiTest.composerFocused() = composer().fetchSemanticsNode().focused()

    /** Every editable field on screen, in order, by its text. */
    private fun SkikoComposeUiTest.editableTexts() =
        onAllNodes(hasSetTextAction()).fetchSemanticsNodes().map { it.config[SemanticsProperties.EditableText].text }

    /** The Projects search field's text, or null when no search is open. */
    private fun SkikoComposeUiTest.searchText() =
        onAllNodesWithTag(PROJECTS_SEARCH_TAG).fetchSemanticsNodes().singleOrNull()
            ?.config?.getOrNull(SemanticsProperties.EditableText)?.text

    private fun SkikoComposeUiTest.searchFocused() =
        onAllNodesWithTag(PROJECTS_SEARCH_TAG).fetchSemanticsNodes().singleOrNull()?.focused() == true

    /** The Focused semantics: whether this node holds focus, as the field that has the keyboard does on a device. */
    private fun SemanticsNode.focused() = config.getOrNull(SemanticsProperties.Focused) == true

    private fun SkikoComposeUiTest.answerButton() = onNode(hasText(str(Res.string.question_answer)) and hasClickAction())

    /** The open sheet's width, read off its full-width drag handle. */
    private fun SkikoComposeUiTest.sheetWidth(): Int {
        val handles = onAllNodesWithTag(POCKET_SHEET_DRAG_HANDLE_TAG, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(1, handles.size, "one sheet is up")
        return handles.single().size.width
    }

    /** Either list screen: the sessions list by its one CTA, Projects by its title. */
    private fun SkikoComposeUiTest.listPaneShowing() =
        present(str(Res.string.new_session_cta)) || present(str(Res.string.dir_projects))

    private fun SkikoComposeUiTest.placeholderShowing() = present(str(Res.string.wide_pick_session))

    private fun SkikoComposeUiTest.sheetShowing() = present(str(Res.string.switcher_current))

    private companion object {
        val CANVAS = DpSize(1100.dp, 1100.dp)
        val HEIGHT = 1024.dp
        val TABLET = DpSize(1024.dp, HEIGHT)
        val NARROW = DpSize(699.dp, HEIGHT)
        val CROSSINGS = listOf(NARROW to false, DpSize(700.dp, HEIGHT) to true, DpSize(320.dp, HEIGHT) to false, TABLET to true)
        const val READ_AT = 12

        /** Past ChatScreen's 400ms draft debounce. */
        const val DRAFT_SAVE_MS = 500L

        /** Past the 250ms a brand-new session waits before it focuses its composer, so no late focus goes unseen. */
        const val FOCUS_GRACE_MS = 500L

        /** WideLayout's LEFT_PANE_WIDTH, RIGHT_PANE_MIN_WIDTH and the hairline between the panes. */
        const val LIST_PANE = 380
        const val CHAT_PANE_MIN = 360
        const val HAIRLINE = 1

        /** The test tag on the Projects header's search field. */
        const val PROJECTS_SEARCH_TAG = "projects-search"
        const val CHAT_DIR = "/Users/alex/code/relay-server"
        const val OTHER_DIR = "/Users/alex/code/billing"
        const val OTHER_TITLE = "Wire up usage endpoint"
        const val DRAFT = "keep this draft through the resize"

        /** "draft" in [DRAFT], selected, and what the user types over it. */
        val SELECTION = TextRange(10, 15)
        const val TYPED = "text"
        const val ANSWER = "Warm, with the contrast kept high"
        const val QUERY = "relay"
        const val PALETTE = "Which palette should the dashboard use?"
        const val DENSITY = "Which row density?"
    }
}
