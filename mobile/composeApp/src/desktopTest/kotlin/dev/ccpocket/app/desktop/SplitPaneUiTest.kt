package dev.ccpocket.app.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.ccpocket.app.assertPresent
import dev.ccpocket.app.data.ChatItem
import dev.ccpocket.app.data.SidePane
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.split_pane_close
import dev.ccpocket.app.resources.split_pane_focus
import dev.ccpocket.app.resources.split_session_ended
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.PermissionAsk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The desktop split view (issue #311), rendered for real.
 *
 * The claims that matter are the ones a screenshot cannot make: that three conversations are on screen at
 * the same time, that each column has its OWN composer (the whole point — a column you can only read is a
 * watch pane, which already existed), and that a column does not offer the header verbs that would act on
 * a different conversation.
 */
@OptIn(ExperimentalTestApi::class)
class SplitPaneUiTest {

    /** A seed shell with [n] extra columns, each carrying one assistant line so it renders a transcript. */
    private class SplitSeed(private val extra: List<SidePane>) : SeedDesktopModel() {
        var promoted: SidePane? = null
        var closed: Long? = null
        // the seed ships the fleet WATCH pane for screenshots; the live model's watch is always null
        // (RepoDesktopModel), and that is the shell state a split renders under — match it here
        override val watch: DkWatch? get() = null
        override val sidePanes: List<SidePane> get() = extra
        override val canSplit: Boolean get() = extra.size < 2
        override fun promoteSplit(pane: SidePane) { promoted = pane }
        override fun closeSplit(paneId: Long) { closed = paneId }
        override fun sendSidePrompt(pane: SidePane, text: String): Boolean = true
    }

    private fun pane(id: Long, title: String, line: String, agent: AgentKind = AgentKind.CLAUDE) =
        SidePane(id, "sid-$id", "/Users/dev/api", title, agent).apply {
            convoId.value = "convo-$id"
            opening.value = false
            transcript.messages.add(ChatItem.Assistant(line))
        }

    @Test
    fun threeConversationsShareTheMainAreaAtOnce() = runComposeUiTest {
        val model = SplitSeed(listOf(pane(1, "Tidy CI workflow", "CI is green"), pane(2, "Bump maxFrame", "patched to 4MB")))
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()
        assertPresent("Refactor auth module") // the focused conversation keeps the leftmost column
        assertPresent("CI is green")          // column two's own stream
        assertPresent("patched to 4MB")       // column three's own stream
    }

    @Test
    fun everyColumnHasItsOwnComposer() = runComposeUiTest {
        val model = SplitSeed(listOf(pane(1, "Tidy CI workflow", "CI is green"), pane(2, "Bump maxFrame", "patched to 4MB")))
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()
        // one text field per column — a column you cannot type into is the read-only watch pane, not this
        assertEquals(3, onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
    }

    @Test
    fun aColumnOffersFocusAndCloseInsteadOfTheFocusedConversationsVerbs() = runComposeUiTest {
        val model = SplitSeed(listOf(pane(1, "Tidy CI workflow", "CI is green")))
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()
        assertPresent(str(Res.string.split_pane_focus))
        onAllNodes(hasText(str(Res.string.split_pane_focus))).onFirst().performClick()
        assertEquals("sid-1", model.promoted?.sessionId)
    }

    @Test
    fun aSingleChatKeepsTheClassicMainAreaUntouched() = runComposeUiTest {
        setContent { PocketTheme { DesktopApp(SplitSeed(emptyList())) } }
        waitForIdle()
        // no split declared → no column chrome at all, and exactly the one composer the app always had
        assertTrue(!present(str(Res.string.split_pane_focus)))
        assertEquals(1, onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
    }

    @Test
    fun anApprovalRaisedByAColumnIsShownInThatColumn() = runComposeUiTest {
        val p = pane(1, "Tidy CI workflow", "CI is green")
        p.pendingAsk.value = PermissionAsk("convo-1", "ask-1", "Bash", "rm -rf build", title = "Run command")
        val model = SplitSeed(listOf(p))
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()
        assertPresent("rm -rf build", substring = true)
    }

    @Test
    fun aColumnWhoseSessionEndedSaysSoAndOffersOnlyTheClose() = runComposeUiTest {
        val p = pane(1, "Tidy CI workflow", "CI is green").apply { gone.value = true }
        val model = SplitSeed(listOf(p))
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()
        assertPresent(str(Res.string.split_session_ended))
        assertTrue(!present("CI is green")) // the dead stream is not left on screen looking live
    }

    /** The ended column's one verb has to WORK — it is now drawn by the focused chat's shared notice
     *  ([ChatNotice]) rather than a look-alike built in the split view, so this pins the wiring that the
     *  reuse could have quietly dropped. (Its `tightCenter` alignment is a visual claim no unit test can
     *  make; that stays a device check.) */
    @Test
    fun theEndedColumnsCloseActuallyClosesThatColumn() = runComposeUiTest {
        val p = pane(1, "Tidy CI workflow", "CI is green").apply { gone.value = true }
        val model = SplitSeed(listOf(p))
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()
        onAllNodes(hasText(str(Res.string.split_pane_close))).onFirst().performClick()
        assertEquals(1L, model.closed)
    }
}
