package dev.ccpocket.app.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.data.ChatItem
import dev.ccpocket.app.data.SidePane
import dev.ccpocket.app.data.ToolProcessScope
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.tool_process_unknown_one
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.ui.chat.TOOL_PROCESS_GROUP_TAG
import dev.ccpocket.app.ui.chat.TOOL_PROCESS_LIVE_TAG
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.PermissionAsk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tool Process Live v1 on the desktop pane: a running turn's steps land in one live fold, so the stream above
 * it holds still while steps start and finish — the same guarantee the phone list gives.
 */
@OptIn(ExperimentalTestApi::class)
class ToolProcessLivePaneUiTest {

    private class LiveSeed(
        override val messages: SnapshotStateList<ChatItem>,
        private val scope: ToolProcessScope,
    ) : SeedDesktopModel() {
        var turnRunning by mutableStateOf(true)
        override val streaming: Boolean get() = turnRunning
        override val toolProcessScope: ToolProcessScope? get() = scope
        override val ask: PermissionAsk? get() = null
        override val watch: DkWatch? get() = null
        override val sidePanes: List<SidePane> get() = emptyList()
    }

    private val prompt = "把登录页的错误提示改成中文"

    private fun ComposeUiTest.promptTop(): Float = onNodeWithText(prompt, substring = true).getUnclippedBoundsInRoot().top.value

    private fun ComposeUiTest.assertLive(text: String) =
        onNode(hasTestTag(TOOL_PROCESS_LIVE_TAG) and hasText(text, substring = true)).assertExists("live line should say \"$text\"")

    @Test
    fun aRunningTurnsStepsNeverMoveThePane() = runComposeUiTest {
        val scope = ToolProcessScope("mac-live-pane", AgentKind.CLAUDE, "s-live-pane", "c-live-pane")
        val messages = mutableStateListOf<ChatItem>().apply {
            repeat(8) { add(ChatItem.User("earlier $it")); add(ChatItem.Assistant("answer $it " + "words ".repeat(40))) }
            add(ChatItem.User(prompt))
            add(ChatItem.Assistant("我先看一下登录表单的实现。"))
        }
        val model = LiveSeed(messages, scope)
        setContent { PocketTheme { Box(Modifier.requiredSize(1180.dp, 760.dp)) { DesktopApp(model) } } }
        waitForIdle()

        fun finish(id: String, ok: Boolean = true) = Snapshot.withMutableSnapshot {
            val i = messages.indexOfLast { it is ChatItem.Tool && it.taskId == id }
            messages[i] = (messages[i] as ChatItem.Tool).copy(ok = ok)
        }
        fun start(tool: String, preview: String, id: String) = Snapshot.withMutableSnapshot {
            messages.add(ChatItem.Tool(tool, preview, taskId = id))
        }

        start("Read", "src/login/LoginForm.tsx", "t1")
        waitForIdle()
        assertLive("Read")
        val anchor = promptTop()
        val steps: List<Pair<String, () -> Unit>> = listOf(
            "Read finishes" to { finish("t1") },
            "Grep starts" to { start("Grep", "errorMessage src/", "t2") },
            "Grep finishes" to { finish("t2") },
            "three Reads start" to {
                start("Read", "src/login/errors.ts", "t3"); start("Read", "src/i18n/zh-CN.json", "t4"); start("Read", "src/login/LoginForm.test.tsx", "t5")
            },
            "the Reads finish" to { finish("t4"); finish("t3"); finish("t5") },
            "a test run fails" to { start("Bash", "pnpm test login", "t6"); finish("t6", ok = false) },
            "the fix lands" to { start("Edit", "src/login/__snapshots__/LoginForm.test.tsx.snap", "t7"); finish("t7") },
        )
        for ((what, step) in steps) {
            step()
            waitForIdle()
            val now = promptTop()
            assertTrue(kotlin.math.abs(now - anchor) < 0.5f, "\"$what\" moved the prompt by ${now - anchor}px")
        }
        assertEquals(1, onAllNodesWithTag(TOOL_PROCESS_GROUP_TAG).fetchSemanticsNodes().size)

        // the turn ends: the fold settles, nothing is live any more
        Snapshot.withMutableSnapshot { model.turnRunning = false }
        waitForIdle()
        assertEquals(0, onAllNodesWithTag(TOOL_PROCESS_LIVE_TAG).fetchSemanticsNodes().size)
    }

    @Test
    fun aCallAPromptPushedOffTheTailStillReadsAsRunningWhenOpened() = runComposeUiTest {
        val scope = ToolProcessScope("mac-live-push", AgentKind.CLAUDE, "s-live-push", "c-live-push")
        val messages = mutableStateListOf<ChatItem>(
            ChatItem.User(prompt),
            ChatItem.Tool("Read", "src/a.ts", ok = true),
            ChatItem.Tool("Bash", "pnpm test login", taskId = "t-slow"),
            ChatItem.User("顺便把分页游标也重置一下"), // typed mid-turn
        )
        val model = LiveSeed(messages, scope)
        setContent { PocketTheme { Box(Modifier.requiredSize(1180.dp, 760.dp)) { DesktopApp(model) } } }
        waitForIdle()
        onNodeWithTag(TOOL_PROCESS_GROUP_TAG).performClick()
        waitForIdle()
        assertTrue(onAllNodes(hasText("pnpm test login", substring = true)).fetchSemanticsNodes().isNotEmpty(), "sanity: opened")
        assertEquals(
            0, onAllNodes(hasText(str(Res.string.tool_process_unknown_one))).fetchSemanticsNodes().size,
            "the call is still running — neither the row nor the header may say it has no result",
        )
    }
}
