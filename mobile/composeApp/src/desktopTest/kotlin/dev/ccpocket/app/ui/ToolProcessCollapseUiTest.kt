package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.assertPresent
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.data.ProcessSummary
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.chat_tool_failed
import dev.ccpocket.app.resources.chat_you
import dev.ccpocket.app.resources.rewind_menu_rewind
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.ui.chat.TURN_COPY_TAG
import dev.ccpocket.app.ui.chat.CHAT_STREAM_TAG
import dev.ccpocket.app.ui.chat.TOOL_PROCESS_GROUP_TAG
import dev.ccpocket.app.ui.chat.TOOL_PROCESS_LIVE_TAG
import dev.ccpocket.app.resources.tool_process_failed
import dev.ccpocket.app.resources.tool_process_tools
import dev.ccpocket.app.resources.tool_process_unknown
import dev.ccpocket.app.ui.chat.processSummaryLabel
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.ConvoHistoryPage
import dev.ccpocket.protocol.FetchHistoryPage
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.ToolEvent
import dev.ccpocket.protocol.ToolPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #380 on the phone/iPad chat screen, rendered for real: the fold, its expansion, the switch, paging
 * under folds (source-mapped landing, no self-driving page loop), reading position, bottom-follow, the
 * merge that fills in outcomes, and the rows that must never fold.
 */
@OptIn(ExperimentalTestApi::class)
class ToolProcessCollapseUiTest {

    private fun account(id: String) = PairedDaemon(
        relay = "wss://test.invalid", accountId = id, daemonPub = "pub", deviceId = "dev", credential = "cred",
    )

    private fun live(convo: String) = SessionLive(
        convoId = convo, workdir = "/w/proj", sessionId = "s-$convo",
        mode = PermissionMode.DEFAULT, executing = false,
        model = "claude-sonnet-4-5", agent = AgentKind.CLAUDE,
    )

    private fun u(t: String) = HistoryMessage(ChatRole.USER, t)
    private fun a(t: String) = HistoryMessage(ChatRole.ASSISTANT, t)
    private fun tool(preview: String, ok: Boolean? = true) = HistoryMessage(ChatRole.TOOL, preview, tool = "Bash", ok = ok)

    private fun ComposeUiTest.groups() = onAllNodesWithTag(TOOL_PROCESS_GROUP_TAG).fetchSemanticsNodes().size

    private fun plural(res: org.jetbrains.compose.resources.PluralStringResource, n: Int) =
        kotlinx.coroutines.runBlocking { org.jetbrains.compose.resources.getPluralString(res, n, n) }

    private fun pluralCount(res: org.jetbrains.compose.resources.PluralStringResource, n: Int) = plural(res, n)

    /** A paired repository showing [history] in a [height]-tall phone scene, list parked where it lands. */
    private fun ComposeUiTest.mount(
        acct: String, convo: String, history: List<HistoryMessage>,
        listState: LazyListState = LazyListState(), height: Int = 800,
        firstSeq: Long? = null, hasMore: Boolean = false,
        onSend: ((Frame) -> Unit)? = null,
    ): PocketRepository {
        var repo: PocketRepository? = null
        setContent {
            val scope = rememberCoroutineScope()
            val r = remember {
                PocketRepository(scope, account(acct)).apply {
                    onSend?.let { onSendForTest = it }
                    receiveForTest(live(convo))
                    receiveForTest(ConvoHistory(convo, history, lastSeq = (firstSeq ?: 1L) + history.size, firstSeq = firstSeq, hasMore = hasMore))
                }
            }
            repo = r
            PocketTheme { Box(Modifier.requiredSize(390.dp, height.dp)) { ChatScreen(r, listStateForTest = listState) } }
        }
        waitForIdle()
        return repo!!
    }

    /** The node is on screen in full — not merely composed, not partly clipped by the list viewport. */
    private fun ComposeUiTest.fullyVisible(tag: String): Boolean {
        val nodes = onAllNodesWithTag(tag)
        if (nodes.fetchSemanticsNodes().isEmpty()) return false
        val shown = nodes.onFirst().getBoundsInRoot()
        val whole = nodes.onFirst().getUnclippedBoundsInRoot()
        return (shown.bottom - shown.top).value > 0f &&
            kotlin.math.abs((shown.top - whole.top).value) < 0.5f &&
            kotlin.math.abs((shown.bottom - whole.bottom).value) < 0.5f
    }

    @Test
    fun finishedToolsFoldByDefaultAndTheFoldOpensAndCloses() = runComposeUiTest {
        mount("acct-380-fold", "c-fold", listOf(u("please look"), tool("echo one"), tool("echo two"), a("all done here")))
        waitForIdle()
        assertEquals(1, groups(), "finished steps fold without any switch")
        assertFalse(present("echo one", substring = true))
        assertPresent("all done here", substring = true)
        assertPresent("please look", substring = true)

        onNodeWithTag(TOOL_PROCESS_GROUP_TAG).performClick()
        waitForIdle()
        assertPresent("echo one", substring = true)
        assertPresent("echo two", substring = true)

        onNodeWithTag(TOOL_PROCESS_GROUP_TAG).performClick()
        waitForIdle()
        assertFalse(present("echo two", substring = true))
    }

    @Test
    fun theFoldRowCountsInTheRightGrammaticalNumber() {
        // pinned to English: the JVM default locale is the developer machine's, and a Chinese locale has no
        // plural nouns to get wrong — the assertion would pass without testing anything
        val saved = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.US)
        try {
            runComposeUiTest {
                setContent {
                    PocketTheme {
                        androidx.compose.foundation.layout.Column {
                            Text(processSummaryLabel(ProcessSummary(tools = 1, thoughts = 1, images = 1, imagesTruncated = false)))
                            Text(processSummaryLabel(ProcessSummary(tools = 8, thoughts = 2, images = 3, imagesTruncated = false)))
                        }
                    }
                }
                waitForIdle()
                assertFalse(present("1 tools", substring = true), "singular count rendered with a plural noun")
                assertFalse(present("1 thoughts", substring = true))
                assertFalse(present("1 images", substring = true))
                assertPresent("1 tool · 1 thought · 1 image", substring = true)
                assertPresent("8 tools · 2 thoughts · 3 images", substring = true)
            }
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun failuresAndUnknownOutcomesAreNamedOnTheFoldAndTheApprovalStaysVisible() = runComposeUiTest {
        val repo = mount(
            "acct-380-attn", "c-attn",
            listOf(
                u("go"), tool("read one"), tool("read two"), tool("rm -rf build", ok = false),
                tool("outcome unknown", ok = null), tool("read three"), tool("read four"),
            ),
        )
        repo.receiveForTest(PermissionAsk("c-attn", "ask-380", "Bash", "git push --force", title = "Force push 380"))
        waitForIdle()
        // Tool Process Live v1: the failure and the missing outcome no longer cut the run in three — they stay
        // inside, and the fold's own row names them so they are still seen without opening it
        assertEquals(1, groups())
        assertPresent(plural(Res.plurals.tool_process_failed, 1), substring = true)
        assertPresent(plural(Res.plurals.tool_process_unknown, 1), substring = true)
        assertFalse(present("rm -rf build", substring = true))
        // the phone pins a pending approval above the stream by its title (the decision sheet itself lives at
        // the app root, outside this list) — folding must not have displaced it
        assertTrue(repo.pendingAsk.value != null)
        assertPresent("Force push 380", substring = true)

        onNodeWithTag(TOOL_PROCESS_GROUP_TAG).performClick()
        waitForIdle()
        assertPresent("rm -rf build", substring = true)
        assertPresent("outcome unknown", substring = true)
        assertPresent(str(Res.string.chat_tool_failed), substring = true)
    }

    @Test
    fun aPageOfOlderHistoryLandsOnTheSourceRowTheReaderWasOn() = runComposeUiTest {
        val listState = LazyListState()
        val repo = mount(
            "acct-380-page", "c-page", (1..30).flatMap { listOf(u("q$it"), a("answer $it")) },
            listState = listState, height = 600, firstSeq = 100, hasMore = true,
        )
        waitForIdle()
        runOnIdle { listState.requestScrollToItem(0) }
        waitForIdle()
        repo.loadOlderHistory()
        // five older rows that show as THREE: the question, one fold, the answer
        repo.receiveForTest(
            ConvoHistoryPage("c-page", listOf(u("older q"), tool("s1"), tool("s2"), tool("s3"), a("older a")), firstSeq = 50, hasMore = true),
        )
        waitForIdle()
        assertEquals(
            3 + 1, listState.firstVisibleItemIndex,
            "the old window's first row (source 5) is display row 3 once folded, +1 for the loader — not 5 + 1",
        )
    }

    @Test
    fun foldedRowsDoNotDriveHistoryPagingByThemselves() = runComposeUiTest {
        val pageRequests = mutableListOf<Frame>()
        var armed: PocketRepository? = null
        val repo = mount(
            "acct-380-runaway", "c-run", listOf(u("start")) + (1..40).map { tool("step $it") } + listOf(a("end")),
            firstSeq = 100, hasMore = true,
            onSend = { f -> if (f is FetchHistoryPage) pageRequests.add(f) },
        )
        armed = repo
        waitForIdle()
        // the window is short, so ONE automatic page is today's short-window behaviour; answer every request
        // with another page that folds away entirely and make sure it does not become a loop
        var seq = 100L
        repeat(8) {
            if (pageRequests.size > it) {
                seq -= 40
                armed.receiveForTest(ConvoHistoryPage("c-run", (1..40).map { i -> tool("older $seq/$i") }, firstSeq = seq, hasMore = true))
            }
            waitForIdle()
        }
        assertTrue(pageRequests.size <= 1, "folded pages must not keep paging history in (${pageRequests.size} requests)")
    }

    // ── review P1-4 / P2-5 ───────────────────────────────────────────────────────────────────────

    private fun longTranscript() = (1..12).flatMap {
        listOf(u("question $it"), tool("a$it"), tool("b$it"), tool("c$it"), a("answer $it — " + "detail ".repeat(20)))
    }

    @Test
    fun aReaderAtTheBottomStaysThereWhenTheSwitchFlipsOrTheLastFoldOpens() = runComposeUiTest {
        val listState = LazyListState()
        val history = (1..10).flatMap { listOf(u("q$it"), a("answer $it " + "words ".repeat(25))) } +
            listOf(u("now"), tool("t1"), tool("t2"), tool("t3"), tool("t4"), tool("t5"), tool("t6"))
        val repo = mount("acct-380-tail", "c-tail", history, listState = listState, height = 700)
        assertFalse(listState.canScrollForward, "sanity: landed at the end")

        waitForIdle()
        assertFalse(listState.canScrollForward, "folding shortened the list — still at the end")

        onNodeWithTag(TOOL_PROCESS_GROUP_TAG).performClick() // the LAST row
        waitForIdle()
        assertFalse(listState.canScrollForward, "opening the last fold keeps following the end")
    }

    @Test
    fun openingAFoldNearTheBottomKeepsItsHeaderOnScreen() = runComposeUiTest {
        val listState = LazyListState()
        val history = (1..10).flatMap { listOf(u("q$it"), a("answer $it " + "words ".repeat(25))) } +
            listOf(u("now")) + (1..8).map { tool("step $it") } + listOf(a("the final answer, a few lines long " + "x ".repeat(40)))
        val repo = mount("acct-380-near", "c-near", history, listState = listState, height = 700)
        waitForIdle()
        assertTrue(fullyVisible(TOOL_PROCESS_GROUP_TAG), "sanity: the fold sits just above the final answer")

        onNodeWithTag(TOOL_PROCESS_GROUP_TAG).performClick()
        waitForIdle()
        assertTrue(fullyVisible(TOOL_PROCESS_GROUP_TAG), "opening it must not drag the list to the end and push the header out")
        assertPresent("step 1", substring = true)
    }

    @Test
    fun aReplayThatFillsInOutcomesSettlesTheLiveSteps() = runComposeUiTest {
        val repo = mount("acct-380-merge", "c-merge", listOf(u("go")))
        // a first call whose outcome is reported: this daemon says when calls finish (#380 outcome frames)
        repo.receiveForTest(ToolEvent("c-merge", 1, ToolPhase.START, "Read", inputPreview = "warm up", toolUseId = "tu-0"))
        repo.receiveForTest(ToolEvent("c-merge", 2, ToolPhase.RESULT, "Read", ok = true, toolUseId = "tu-0", outcomeOnly = true))
        repo.receiveForTest(ToolEvent("c-merge", 3, ToolPhase.START, "Bash", inputPreview = "live one", toolUseId = "tu-1"))
        repo.receiveForTest(ToolEvent("c-merge", 4, ToolPhase.START, "Bash", inputPreview = "live two", toolUseId = "tu-2"))
        waitForIdle()
        // a tool starting is a turn in flight: both calls are the live line's — never folded as if they succeeded
        assertTrue(repo.streaming.value)
        assertEquals(1, groups())
        assertEquals(1, onAllNodesWithTag(TOOL_PROCESS_LIVE_TAG).fetchSemanticsNodes().size)
        assertPresent("×2", substring = true)
        assertPresent("live one · live two", substring = true)

        // the reattach replay carries ok=true for both; TranscriptMerge enriches the same cards in place
        repo.receiveForTest(ConvoHistory("c-merge", listOf(u("go"), tool("warm up"), tool("live one"), tool("live two")), lastSeq = 5))
        waitForIdle()
        assertEquals(1, groups())
        assertFalse(present("live one", substring = true), "both finished: counted on the fold, not running on its line")
        assertPresent(pluralCount(Res.plurals.tool_process_tools, 3), substring = true)
    }

    @Test
    fun onADaemonThatNeverReportsOutcomesOnlyTheNewestCallRunsAndNothingIsMarkedWithoutResult() = runComposeUiTest {
        // before 2.1.1 a daemon sends START only for ordinary tools
        val repo = mount("acct-380-old", "c-old", listOf(u("go")))
        repo.receiveForTest(ToolEvent("c-old", 1, ToolPhase.START, "Read", inputPreview = "first.kt", toolUseId = "o-1"))
        repo.receiveForTest(ToolEvent("c-old", 2, ToolPhase.START, "Grep", inputPreview = "needle", toolUseId = "o-2"))
        repo.receiveForTest(ToolEvent("c-old", 3, ToolPhase.START, "Edit", inputPreview = "last.kt", toolUseId = "o-3"))
        waitForIdle()
        assertPresent("last.kt", substring = true)
        assertFalse(present("+2", substring = true), "the earlier calls are not \"still running\" forever")
        assertPresent(pluralCount(Res.plurals.tool_process_tools, 2), substring = true)
        repo.receiveForTest(dev.ccpocket.protocol.TurnDone("c-old"))
        waitForIdle()
        assertEquals(0, onAllNodesWithTag(TOOL_PROCESS_LIVE_TAG).fetchSemanticsNodes().size)
        assertFalse(present(plural(Res.plurals.tool_process_unknown, 3), substring = true), "no \"without result\" noise")
        assertPresent(pluralCount(Res.plurals.tool_process_tools, 3), substring = true)
    }

    @Test
    fun collapsedModeKeepsRewindAndCopyWorking() = runComposeUiTest {
        val repo = mount(
            "acct-380-rewind", "c-rewind",
            listOf(
                HistoryMessage(ChatRole.USER, "rewind me", seq = 1, uuid = "u-1"),
                tool("x1"), tool("x2"),
                a("copy this reply"),
            ),
        )
        waitForIdle()
        assertEquals(1, groups())
        // Chat Rhythm v1: each turn's copy rides its source row as a glyph (no 复制 text under the body)
        assertEquals(2, onAllNodesWithTag(TURN_COPY_TAG).fetchSemanticsNodes().size, "whole-turn copy on the prompt and the reply")

        onAllNodesWithText(str(Res.string.chat_you), substring = true, ignoreCase = true).onFirst()
            .performTouchInput { longClick() }
        waitForIdle()
        assertPresent(str(Res.string.rewind_menu_rewind))
    }

    // ── re-review: bottom-follow against a last row taller than the viewport, and short transcripts ─────────

    /** A reply several screens tall — the case where "scroll to the last item" is NOT "scroll to the end". */
    private fun tallReply() = a("the tall reply begins. " + "a long line of generated output that wraps ".repeat(260) + "the-tall-reply-ends")

    @Test
    fun aPinnedReaderStaysAtTheEndOfATallLastReplyWhenTheSwitchFlips() = runComposeUiTest {
        val listState = LazyListState()
        val history = (1..6).flatMap { listOf(u("q$it"), a("answer $it")) } + listOf(u("go"), tool("t1"), tool("t2"), tallReply())
        val repo = mount("acct-380-tall-flip", "c-tall-flip", history, listState = listState, height = 700)
        assertFalse(listState.canScrollForward, "sanity: landed at the very end of the tall reply")

        waitForIdle()
        // (the fold itself is scrolled out of view above the tall reply, so it is not asserted as a node here)
        assertFalse(listState.canScrollForward, "folding above a tall last reply must keep the END of it on screen")
    }

    @Test
    fun aPinnedReaderStaysAtTheEndOfATallLastReplyWhenAToolJoinsAFold() = runComposeUiTest {
        val listState = LazyListState()
        val rows = (1..6).flatMap { listOf(u("q$it"), a("answer $it")) } + listOf(u("go"), tool("t1"), tool("t2"))
        val repo = mount("acct-380-tall-join", "c-tall-join", rows + listOf(tool("t3", ok = null), tallReply()), listState = listState, height = 700)
        waitForIdle()
        assertFalse(listState.canScrollForward, "sanity: still at the end after folding t1 + t2")

        // the replay now knows t3 succeeded: it joins the fold, the row count drops, the messages count does not
        repo.receiveForTest(ConvoHistory("c-tall-join", rows + listOf(tool("t3"), tallReply()), lastSeq = 40))
        waitForIdle()
        assertFalse(listState.canScrollForward, "…and the reader is still at the end of the tall reply")
    }

    @Test
    fun openingAFoldInAShortTranscriptKeepsFollowingTheEnd() = runComposeUiTest {
        val listState = LazyListState()
        val repo = mount("acct-380-short", "c-short", listOf(u("go"), tool("s1"), tool("s2"), tool("s3"), a("short reply")), listState = listState, height = 800)
        waitForIdle()
        assertFalse(listState.canScrollForward || listState.canScrollBackward, "sanity: everything fits, nothing to scroll")

        onNodeWithTag(TOOL_PROCESS_GROUP_TAG).performClick() // NOT the last row — the reply is
        waitForIdle()
        // nothing moved (nothing can), so the reader was never "reading above the end"; a phone has no gesture
        // left to re-pin a list that cannot scroll, so this must keep following what streams in next
        repo.receiveForTest(
            ConvoHistory("c-short", (1..20).map { a("more output $it " + "words ".repeat(30)) } + listOf(a("the-newest-line")), lastSeq = 30, delta = true),
        )
        waitForIdle()
        assertFalse(listState.canScrollForward, "new output after opening a fold in a short transcript is still followed")
    }
}
