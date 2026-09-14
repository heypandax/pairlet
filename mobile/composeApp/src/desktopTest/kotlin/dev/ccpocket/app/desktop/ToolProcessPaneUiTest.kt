package dev.ccpocket.app.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.assertPresent
import dev.ccpocket.app.data.ChatItem
import dev.ccpocket.app.data.SidePane
import dev.ccpocket.app.data.ToolProcessPrefs
import dev.ccpocket.app.data.ToolProcessScope
import dev.ccpocket.app.present
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.ui.chat.CHAT_STREAM_TAG
import dev.ccpocket.app.ui.chat.TOOL_PROCESS_GROUP_TAG
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.PermissionAsk
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #380 on the desktop pane, rendered for real: folded runs collapse to one row and open back into the
 * ordinary tool rows; failures, running tools, errors and the approval card are never swallowed; a split
 * column follows ITS session's switch; a page lands on its source row; two panes open folds independently.
 */
@OptIn(ExperimentalTestApi::class)
class ToolProcessPaneUiTest {

    private val touched = mutableListOf<ToolProcessScope>()

    private fun collapse(scope: ToolProcessScope) {
        touched += scope
        ToolProcessPrefs.shared.setCollapsed(scope, true)
    }

    @AfterTest
    fun resetSwitches() = touched.forEach { ToolProcessPrefs.shared.setCollapsed(it, false) }

    private open class Seed(
        override val messages: SnapshotStateList<ChatItem>,
        private val scope: ToolProcessScope?,
        private val pendingAsk: PermissionAsk? = null,
        private val columns: List<SidePane> = emptyList(),
    ) : SeedDesktopModel() {
        override val toolProcessScope: ToolProcessScope? get() = scope
        override val ask: PermissionAsk? get() = pendingAsk
        override val watch: DkWatch? get() = null
        override val sidePanes: List<SidePane> get() = columns
        override val streaming: Boolean get() = false
    }

    /** A seed whose transcript pages like the live model: [prepend] lands older rows + bumps the generation. */
    private class PagingSeed(messages: SnapshotStateList<ChatItem>, scope: ToolProcessScope) : Seed(messages, scope) {
        private val gen = mutableStateOf(0)
        private var count = 0
        override val historyHasMore: Boolean get() = true
        override val historyPrependGen: Int get() = gen.value
        override val lastHistoryPrependCount: Int get() = count
        override fun loadOlderHistory() {}
        fun prepend(older: List<ChatItem>) = Snapshot.withMutableSnapshot {
            messages.addAll(0, older)
            count = older.size
            gen.value++
        }
    }

    private fun ComposeUiTest.groups() = onAllNodesWithTag(TOOL_PROCESS_GROUP_TAG).fetchSemanticsNodes().size

    private fun ComposeUiTest.fullyVisibleText(text: String): Boolean {
        val nodes = onAllNodesWithText(text, substring = true)
        if (nodes.fetchSemanticsNodes().isEmpty()) return false
        val shown = nodes.onFirst().getBoundsInRoot()
        val whole = nodes.onFirst().getUnclippedBoundsInRoot()
        return (shown.bottom - shown.top).value > 0f &&kotlin.math.abs((shown.top - whole.top).value) < 0.5f &&
            kotlin.math.abs((shown.bottom - whole.bottom).value) < 0.5f
    }

    @Test
    fun finishedToolsFoldIntoOneRowThatOpensAndTheSwitchRestoresTheStream() = runComposeUiTest {
        val scope = ToolProcessScope("mac-pane-fold", AgentKind.CLAUDE, "s-pane-fold", "c-pane-fold")
        val model = Seed(
            mutableStateListOf(
                ChatItem.User("please look"),
                ChatItem.Tool("Bash", "echo one", ok = true),
                ChatItem.Tool("Read", "src/a.kt", ok = true),
                ChatItem.Thinking("considering", seconds = 3),
                ChatItem.Assistant("all done here"),
            ),
            scope,
        )
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()
        assertEquals(0, groups(), "default is off — today's stream, untouched")
        assertPresent("echo one", substring = true)

        collapse(scope)
        waitForIdle()
        assertEquals(1, groups())
        assertFalse(present("echo one", substring = true), "the folded tool rows are behind the fold")
        assertPresent("all done here", substring = true)
        assertPresent("please look", substring = true)

        onNodeWithTag(TOOL_PROCESS_GROUP_TAG).performClick()
        waitForIdle()
        assertPresent("echo one", substring = true)
        assertPresent("src/a.kt", substring = true)
        assertEquals(1, groups(), "the header stays as the way to close the fold")

        onNodeWithTag(TOOL_PROCESS_GROUP_TAG).performClick()
        waitForIdle()
        assertFalse(present("echo one", substring = true))

        ToolProcessPrefs.shared.setCollapsed(scope, false)
        waitForIdle()
        assertEquals(0, groups())
        assertPresent("echo one", substring = true)
    }

    @Test
    fun failuresRunningToolsErrorsAndTheApprovalCardStayVisible() = runComposeUiTest {
        val scope = ToolProcessScope("mac-pane-attn", AgentKind.CLAUDE, "s-pane-attn", "c-pane-attn")
        collapse(scope)
        val model = Seed(
            mutableStateListOf(
                ChatItem.Tool("Bash", "ls first", ok = true),
                ChatItem.Tool("Bash", "rm -rf build", ok = false),
                ChatItem.Tool("Bash", "gradle test", taskId = "t-run"),
                ChatItem.Tool("Read", "one.kt", ok = true),
                ChatItem.Tool("Read", "two.kt", ok = true),
                ChatItem.Sys("upstream exploded"),
            ),
            scope,
            pendingAsk = PermissionAsk("demo", "ask-380", "Bash", "git push --force", title = "Run command"),
        )
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()
        assertEquals(1, groups(), "only the run of two finished reads folds")
        assertPresent("ls first", substring = true) // a lone finished tool isn't worth a fold
        assertPresent("rm -rf build", substring = true)
        assertPresent("gradle test", substring = true)
        assertPresent("upstream exploded", substring = true)
        assertPresent("git push --force", substring = true)
        assertFalse(present("one.kt", substring = true))
    }

    @Test
    fun aSplitColumnFollowsItsOwnSessionsSwitch() = runComposeUiTest {
        val column = SidePane(7, "sid-col-380", "/Users/dev/api", "Column", AgentKind.CLAUDE).apply {
            convoId.value = "convo-col-380"
            opening.value = false
            transcript.messages.add(ChatItem.Tool("Bash", "column step one", ok = true))
            transcript.messages.add(ChatItem.Tool("Bash", "column step two", ok = true))
            transcript.messages.add(ChatItem.Assistant("column reply"))
        }
        val focusedScope = ToolProcessScope(null, AgentKind.CLAUDE, "s-focused-380", "c-focused-380")
        val model = Seed(
            mutableStateListOf(
                ChatItem.Tool("Bash", "focused step one", ok = true),
                ChatItem.Tool("Bash", "focused step two", ok = true),
            ),
            focusedScope,
            columns = listOf(column),
        )
        collapse(ToolProcessScope(null, AgentKind.CLAUDE, "sid-col-380", "convo-col-380"))
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()
        assertEquals(1, groups(), "only the column's session is switched on")
        assertFalse(present("column step one", substring = true))
        assertPresent("focused step one", substring = true)
        assertTrue(present("column reply", substring = true))
    }

    // ── review P1-4 ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun aPageOfOlderHistoryLandsOnTheSourceRowWithFoldsOn() = runComposeUiTest {
        val scope = ToolProcessScope("mac-pane-page", AgentKind.CLAUDE, "s-pane-page", "c-pane-page")
        collapse(scope)
        val model = PagingSeed(
            mutableStateListOf<ChatItem>(ChatItem.User("first-window-question")).apply {
                repeat(25) { add(ChatItem.Assistant("window answer $it " + "words ".repeat(30))); add(ChatItem.User("follow-up $it")) }
            },
            scope,
        )
        setContent { PocketTheme { Box(Modifier.requiredSize(1180.dp, 760.dp)) { DesktopApp(model) } } }
        waitForIdle()
        // the reader drags up to the top of the loaded window. A drag is what unpins the list here: under the
        // test clock a wheel event scrolls without the list ever reporting a scroll in progress, so the list
        // stays pinned and follows the page to the end — with folds OFF too (checked; a harness limit)
        repeat(6) { onNodeWithTag(CHAT_STREAM_TAG).performTouchInput { swipeDown() }; waitForIdle() }
        onNodeWithTag(CHAT_STREAM_TAG).performScrollToIndex(1)
        waitForIdle()
        assertTrue(fullyVisibleText("first-window-question"), "sanity: reading the window's first row")

        // five older rows that show as three (question · fold · answer)
        model.prepend(
            listOf(
                ChatItem.User("older-question"),
                ChatItem.Tool("Bash", "older one", ok = true),
                ChatItem.Tool("Bash", "older two", ok = true),
                ChatItem.Tool("Bash", "older three", ok = true),
                ChatItem.Assistant("older-answer"),
            ),
        )
        waitForIdle()
        assertTrue(fullyVisibleText("first-window-question"), "the page lands with the row being read still on screen")
        assertFalse(fullyVisibleText("older-question"), "…and the older page stays above the viewport")
    }

    @Test
    fun twoPanesOfTheSameSessionOpenFoldsIndependently() = runComposeUiTest {
        val scope = ToolProcessScope("mac-pane-twin", AgentKind.CLAUDE, "s-pane-twin", "c-pane-twin")
        collapse(scope)
        val model = Seed(
            mutableStateListOf(
                ChatItem.User("twin prompt"),
                ChatItem.Tool("Bash", "twin step one", ok = true),
                ChatItem.Tool("Bash", "twin step two", ok = true),
                ChatItem.Assistant("twin reply"),
            ),
            scope,
        )
        setContent {
            PocketTheme {
                Row(Modifier.requiredSize(1400.dp, 700.dp)) {
                    ChatPane(model, Modifier.weight(1f))
                    ChatPane(model, Modifier.weight(1f))
                }
            }
        }
        waitForIdle()
        assertEquals(2, groups(), "one fold per pane")
        onAllNodesWithTag(TOOL_PROCESS_GROUP_TAG).onFirst().performClick()
        waitForIdle()
        assertEquals(1, onAllNodesWithText("twin step one", substring = true).fetchSemanticsNodes().size, "opened in ONE pane only")
        assertEquals(2, groups(), "both panes keep their fold header")
    }

    // ── re-review: P2-5 on the desktop, and short panes ──────────────────────────────────────────────────

    private fun ComposeUiTest.fullyVisibleTag(tag: String): Boolean {
        val nodes = onAllNodesWithTag(tag)
        if (nodes.fetchSemanticsNodes().isEmpty()) return false
        val shown = nodes.onFirst().getBoundsInRoot()
        val whole = nodes.onFirst().getUnclippedBoundsInRoot()
        return (shown.bottom - shown.top).value > 0f && kotlin.math.abs((shown.top - whole.top).value) < 0.5f &&
            kotlin.math.abs((shown.bottom - whole.bottom).value) < 0.5f
    }

    @Test
    fun pinnedOpeningAFoldAboveTheEndKeepsItsHeaderOnScreen() = runComposeUiTest {
        val scope = ToolProcessScope("mac-pane-near", AgentKind.CLAUDE, "s-pane-near", "c-pane-near")
        collapse(scope)
        val model = Seed(
            mutableStateListOf<ChatItem>().apply {
                repeat(10) { add(ChatItem.User("q$it")); add(ChatItem.Assistant("answer $it " + "words ".repeat(40))) }
                add(ChatItem.User("now"))
                repeat(8) { add(ChatItem.Tool("Bash", "near step $it", ok = true)) }
                add(ChatItem.Assistant("the final answer, a few lines long " + "x ".repeat(60)))
            },
            scope,
        )
        setContent { PocketTheme { Box(Modifier.requiredSize(1180.dp, 760.dp)) { DesktopApp(model) } } }
        waitForIdle()
        assertTrue(fullyVisibleTag(TOOL_PROCESS_GROUP_TAG), "sanity: the fold sits just above the final answer")

        onNodeWithTag(TOOL_PROCESS_GROUP_TAG).performClick()
        waitForIdle()
        assertTrue(fullyVisibleTag(TOOL_PROCESS_GROUP_TAG), "opening it must not follow to the end and push the header out")
        assertPresent("near step 0", substring = true)
    }

    @Test
    fun openingAFoldInAShortPaneKeepsFollowingTheEnd() = runComposeUiTest {
        val scope = ToolProcessScope("mac-pane-short", AgentKind.CLAUDE, "s-pane-short", "c-pane-short")
        collapse(scope)
        val messages = mutableStateListOf<ChatItem>(
            ChatItem.User("go"),
            ChatItem.Tool("Bash", "short one", ok = true),
            ChatItem.Tool("Bash", "short two", ok = true),
            ChatItem.Assistant("short reply"),
        )
        val model = Seed(messages, scope)
        setContent { PocketTheme { Box(Modifier.requiredSize(1180.dp, 760.dp)) { DesktopApp(model) } } }
        waitForIdle()

        onNodeWithTag(TOOL_PROCESS_GROUP_TAG).performClick() // not the last row; the pane cannot scroll yet
        waitForIdle()
        // the desktop has no jump-to-latest control: had this unpinned, only switching sessions would bring the
        // stream back to its end
        Snapshot.withMutableSnapshot {
            repeat(30) { messages.add(ChatItem.Assistant("more output $it " + "words ".repeat(40))) }
            messages.add(ChatItem.Assistant("the-newest-line"))
        }
        waitForIdle()
        assertTrue(fullyVisibleText("the-newest-line"), "output after opening a fold in a short pane is still followed")
    }
}
