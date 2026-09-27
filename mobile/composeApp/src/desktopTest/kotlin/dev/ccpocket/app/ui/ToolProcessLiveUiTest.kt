package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.assertPresent
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.chat_src_tool
import dev.ccpocket.app.resources.chat_tool_failed
import dev.ccpocket.app.resources.done
import dev.ccpocket.app.resources.subagent_tools
import dev.ccpocket.app.resources.tool_process_unknown
import dev.ccpocket.app.resources.tool_process_unknown_one
import dev.ccpocket.app.resources.thinking_streaming
import dev.ccpocket.app.resources.tool_process_autoruns
import dev.ccpocket.app.resources.tool_process_expand_one
import dev.ccpocket.app.resources.tool_process_failed
import dev.ccpocket.app.resources.tool_process_group
import dev.ccpocket.app.resources.tool_process_tools
import dev.ccpocket.app.resources.tool_process_waiting
import dev.ccpocket.app.str
import dev.ccpocket.app.ui.chat.TOOL_PROCESS_GROUP_TAG
import dev.ccpocket.app.ui.chat.TOOL_PROCESS_LIVE_TAG
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.StreamPiece
import dev.ccpocket.protocol.ToolEvent
import dev.ccpocket.protocol.ToolPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tool Process Live v1 on the phone chat screen, rendered for real: while a turn runs, its steps land in ONE
 * block that is born in its final shape, so nothing above the tail moves as steps start and finish (the
 * user's report: every tool appeared as a full band, then folded away, and the transcript bounced).
 */
@OptIn(ExperimentalTestApi::class)
class ToolProcessLiveUiTest {

    private fun account(id: String) = PairedDaemon(
        relay = "wss://test.invalid", accountId = id, daemonPub = "pub", deviceId = "dev", credential = "cred",
    )

    private fun live(convo: String, executing: Boolean) = SessionLive(
        convoId = convo, workdir = "/w/acme-web", sessionId = "s-$convo",
        mode = PermissionMode.DEFAULT, executing = executing,
        model = "claude-sonnet-4-5", agent = AgentKind.CLAUDE,
    )

    private fun u(t: String) = HistoryMessage(ChatRole.USER, t)
    private fun a(t: String) = HistoryMessage(ChatRole.ASSISTANT, t)

    private val prompt = "把登录页的错误提示改成中文"

    /** A scrolled transcript (so a height change at the tail would move what is above it) whose last turn runs. */
    private fun ComposeUiTest.mountRunningTurn(acct: String, convo: String, listState: LazyListState = LazyListState()): PocketRepository {
        var repo: PocketRepository? = null
        val history = (1..8).flatMap { listOf(u("earlier question $it"), a("earlier answer $it " + "words ".repeat(25))) } +
            listOf(u(prompt), a("我先看一下登录表单的实现。"))
        setContent {
            val scope = rememberCoroutineScope()
            val r = remember {
                PocketRepository(scope, account(acct)).apply {
                    receiveForTest(live(convo, executing = false))
                    receiveForTest(ConvoHistory(convo, history, lastSeq = history.size.toLong()))
                    receiveForTest(live(convo, executing = true))
                }
            }
            repo = r
            PocketTheme { Box(Modifier.requiredSize(390.dp, 760.dp)) { ChatScreen(r, listStateForTest = listState) } }
        }
        waitForIdle()
        return repo!!
    }

    private fun ComposeUiTest.promptTop(): Float =
        onNodeWithText(prompt, substring = true).getUnclippedBoundsInRoot().top.value

    @Test
    fun nothingAboveTheTailMovesWhileStepsStartAndFinish() = runComposeUiTest {
        val listState = LazyListState()
        val convo = "c-live-still"
        val repo = mountRunningTurn("acct-live-still", convo, listState)
        var seq = 100L
        fun start(tool: String, preview: String, id: String) =
            repo.receiveForTest(ToolEvent(convo, seq++, ToolPhase.START, tool, inputPreview = preview, toolUseId = id))
        fun done(tool: String, id: String, ok: Boolean = true) =
            repo.receiveForTest(ToolEvent(convo, seq++, ToolPhase.RESULT, tool, ok = ok, toolUseId = id, outcomeOnly = true))
        fun think(text: String) = repo.receiveForTest(AssistantChunk(convo, seq++, StreamPiece.Thinking(text)))

        // the run is born with its first step
        start("Read", "~/code/acme-web/src/login/LoginForm.tsx", "tu-1")
        waitForIdle()
        assertTrue(listState.canScrollBackward, "sanity: the transcript is scrolled, so a tail height change would show")
        val anchor = promptTop()

        val steps: List<Pair<String, () -> Unit>> = listOf(
            "Read finishes" to { done("Read", "tu-1") },
            "Grep starts" to { start("Grep", "\"errorMessage\" src/", "tu-2") },
            "Grep finishes" to { done("Grep", "tu-2") },
            "the model thinks" to { think("先确认错误文案集中在哪里") },
            "three parallel Reads start" to {
                start("Read", "~/code/acme-web/src/login/messages.ts", "tu-3")
                start("Read", "~/code/acme-web/src/login/validate.ts", "tu-4")
                start("Read", "~/code/acme-web/src/i18n/zh.ts", "tu-5")
            },
            "one of them finishes first" to { done("Read", "tu-4") },
            "the other two finish" to { done("Read", "tu-3"); done("Read", "tu-5") },
            "Edit starts" to { start("Edit", "~/code/acme-web/src/login/messages.ts", "tu-6") },
            "a grant auto-approves it" to {
                repo.receiveForTest(
                    dev.ccpocket.protocol.AuthorizedActionRecorded(convo, "ev-6", "Edit messages.ts", "task-grant", decidedAt = 1L, tool = "Edit"),
                )
            },
            "Edit finishes" to { done("Edit", "tu-6") },
            "a test run starts" to { start("Bash", "pnpm test login", "tu-7") },
            "the test run fails" to { done("Bash", "tu-7", ok = false) },
            "the fix starts" to { start("Edit", "~/code/acme-web/src/login/__snapshots__/LoginForm.test.tsx.snap", "tu-8") },
            "the fix lands" to { done("Edit", "tu-8") },
        )
        for ((what, step) in steps) {
            step()
            waitForIdle()
            val now = promptTop()
            assertTrue(
                kotlin.math.abs(now - anchor) < 0.5f,
                "\"$what\" moved the user's prompt by ${now - anchor}px — a live run must not change height",
            )
        }
        assertEquals(1, groups(), "one fold for the whole run — neither the failure nor the audit chip cut it")
        assertPresent(plural(Res.plurals.tool_process_autoruns, 1), substring = true)
    }

    @Test
    fun theLiveLineSaysWhatRunsNowAndSettlesWhenTheReplyBegins() = runComposeUiTest {
        val convo = "c-live-line"
        val repo = mountRunningTurn("acct-live-line", convo)
        var seq = 100L
        fun start(tool: String, preview: String, id: String) =
            repo.receiveForTest(ToolEvent(convo, seq++, ToolPhase.START, tool, inputPreview = preview, toolUseId = id))
        fun done(tool: String, id: String, ok: Boolean = true) =
            repo.receiveForTest(ToolEvent(convo, seq++, ToolPhase.RESULT, tool, ok = ok, toolUseId = id, outcomeOnly = true))

        start("Read", "~/code/acme-web/src/login/LoginForm.tsx", "tu-1")
        waitForIdle()
        assertLive("Read")
        assertLive("~/code/acme-web/src/login/LoginForm.tsx")
        assertPresent(str(Res.string.tool_process_group)) // nothing finished yet: the header only says "Process"

        // the step finished and the agent is deciding the next one: the line KEEPS the step, with its outcome —
        // most calls finish in milliseconds, so a line that only showed calls while they ran showed nothing
        done("Read", "tu-1")
        waitForIdle()
        assertLive("Read")
        assertLive(str(Res.string.done))
        assertFalse(present(str(Res.string.thinking_streaming), substring = true), "between steps the line is not \"thinking\"")
        assertPresent(plural(Res.plurals.tool_process_tools, 1), substring = true)

        start("Grep", "\"errorMessage\" src/", "tu-2")
        waitForIdle()
        assertLive("Grep")
        assertFalse(present(str(Res.string.done)), "the next step replaces the finished one")

        done("Grep", "tu-2")
        start("Read", "~/code/acme-web/src/login/messages.ts", "tu-3")
        start("Read", "~/code/acme-web/src/login/validate.ts", "tu-4")
        start("Read", "~/code/acme-web/src/i18n/zh.ts", "tu-5")
        waitForIdle()
        assertLive("×3")
        assertLive("messages.ts · validate.ts · zh.ts")
        done("Read", "tu-3"); done("Read", "tu-4"); done("Read", "tu-5")

        // a running step that waits on the user: the line says so; the decision stays with the approval UI
        start("Bash", "pnpm test login", "tu-6")
        repo.receiveForTest(PermissionAsk(convo, "ask-live", "Bash", "pnpm test login", title = "Run command"))
        waitForIdle()
        assertLive(str(Res.string.tool_process_waiting))
        repo.receiveForTest(dev.ccpocket.protocol.AskWithdrawn(convo, "ask-live")) // answered elsewhere
        done("Bash", "tu-6", ok = false)
        waitForIdle()
        // a failure is held on the line until the next step starts, and counted on the fold's own row
        assertLive(str(Res.string.chat_tool_failed))
        assertPresent(plural(Res.plurals.tool_process_failed, 1), substring = true)

        // the reply begins: the run is over, the line gives way, the fold keeps its counts and its failure
        repo.receiveForTest(AssistantChunk(convo, seq++, StreamPiece.Text("第一次测试失败是因为快照里还是英文，")))
        waitForIdle()
        assertEquals(0, onAllNodesWithTag(TOOL_PROCESS_LIVE_TAG).fetchSemanticsNodes().size)
        assertEquals(1, groups())
        assertPresent(plural(Res.plurals.tool_process_failed, 1), substring = true)
        assertPresent(plural(Res.plurals.tool_process_tools, 6), substring = true)
    }

    @Test
    fun aLiveFoldEndingTheStreamIsItsOnlyLiveSignal() = runComposeUiTest {
        val convo = "c-live-tail"
        val repo = mountRunningTurn("acct-live-tail", convo)
        repo.receiveForTest(ToolEvent(convo, 100, ToolPhase.START, "Read", inputPreview = "a.kt", toolUseId = "tu-1"))
        repo.receiveForTest(ToolEvent(convo, 101, ToolPhase.RESULT, "Read", ok = true, toolUseId = "tu-1", outcomeOnly = true))
        waitForIdle()
        // between steps the line keeps the finished step — and the stream's own "Thinking…" tail row is not added under it
        assertLive("a.kt")
        assertLive(str(Res.string.done))
        assertEquals(0, onAllNodesWithText(str(Res.string.thinking_streaming), substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun rawInputJsonPreviewsShowTheirArgumentRelativeToTheWorkdir() = runComposeUiTest {
        val convo = "c-live-json"
        val repo = mountRunningTurn("acct-live-json", convo) // the session's workdir is /w/acme-web
        repo.receiveForTest(ToolEvent(convo, 100, ToolPhase.START, "Read", inputPreview = """{"file_path":"/w/acme-web/src/login/LoginForm.tsx","limit":40}""", toolUseId = "tu-1"))
        waitForIdle()
        assertLive("src/login/LoginForm.tsx")
        assertFalse(present("file_path", substring = true), "the raw JSON never reaches the live line")
        repo.receiveForTest(ToolEvent(convo, 101, ToolPhase.RESULT, "Read", ok = true, toolUseId = "tu-1", outcomeOnly = true))
        repo.receiveForTest(ToolEvent(convo, 102, ToolPhase.START, "Bash", inputPreview = """{"command":"pnpm --filter @acme/web test login","timeout":120000}""", toolUseId = "tu-2"))
        waitForIdle()
        assertLive("pnpm --filter @acme/web test login")
        assertFalse(present("{\"command\"", substring = true))
    }

    @Test
    fun anOpenedLiveFoldListsItsStepsWithTheLiveLineUnderThem() = runComposeUiTest {
        val convo = "c-live-open"
        val repo = mountRunningTurn("acct-live-open", convo)
        repo.receiveForTest(ToolEvent(convo, 100, ToolPhase.START, "Read", inputPreview = "first-file.kt", toolUseId = "tu-1"))
        repo.receiveForTest(ToolEvent(convo, 101, ToolPhase.RESULT, "Read", ok = true, toolUseId = "tu-1", outcomeOnly = true))
        repo.receiveForTest(ToolEvent(convo, 102, ToolPhase.START, "Grep", inputPreview = "needle", toolUseId = "tu-2"))
        waitForIdle()
        onNodeWithTag(TOOL_PROCESS_GROUP_TAG).performClick()
        waitForIdle()
        val member = onNodeWithText("first-file.kt", substring = true).getUnclippedBoundsInRoot()
        val live = onNodeWithTag(TOOL_PROCESS_LIVE_TAG).getUnclippedBoundsInRoot()
        assertTrue(live.top >= member.bottom, "the live line stays at the bottom of the opened fold")
        assertLive("Grep")
        assertFalse(present(str(Res.string.chat_src_tool).uppercase()), "inside the card the header is the label")
    }

    @Test
    fun aSettledSingleStepNamesItsToolOnTheFold() = runComposeUiTest {
        var repo: PocketRepository? = null
        setContent {
            val scope = rememberCoroutineScope()
            val r = remember {
                PocketRepository(scope, account("acct-live-one")).apply {
                    receiveForTest(live("c-live-one", executing = false))
                    receiveForTest(
                        ConvoHistory(
                            "c-live-one",
                            listOf(u("看看改了什么"), HistoryMessage(ChatRole.TOOL, "git diff --stat", tool = "Bash", ok = true), a("改动涉及 3 个文件。")),
                            lastSeq = 3,
                        ),
                    )
                }
            }
            repo = r
            PocketTheme { Box(Modifier.requiredSize(390.dp, 760.dp)) { ChatScreen(r) } }
        }
        waitForIdle()
        assertEquals(1, groups())
        assertPresent("git diff --stat", substring = true) // the tool and its target, not "Process · 1 tool"
        assertPresent(str(Res.string.tool_process_expand_one))
        assertFalse(present(plural(Res.plurals.tool_process_tools, 1), substring = true))
    }

    @Test
    fun aCallRunningWhenTheListAttachedIsRunningAndItsOutcomeFindsItByName() = runComposeUiTest {
        // the phone attaches mid-turn: the history replay carries the running call without its id
        val convo = "c-live-attach"
        var repo: PocketRepository? = null
        setContent {
            val scope = rememberCoroutineScope()
            val r = remember {
                PocketRepository(scope, account("acct-live-attach")).apply {
                    receiveForTest(live(convo, executing = false))
                    receiveForTest(
                        ConvoHistory(
                            convo,
                            listOf(
                                u(prompt), a("先跑一下测试。"),
                                HistoryMessage(ChatRole.TOOL, "src/login/a.ts", tool = "Read", ok = true),
                                HistoryMessage(ChatRole.TOOL, "pnpm test login", tool = "Bash", ok = null),
                            ),
                            lastSeq = 4,
                        ),
                    )
                    receiveForTest(live(convo, executing = true))
                }
            }
            repo = r
            PocketTheme { Box(Modifier.requiredSize(390.dp, 760.dp)) { ChatScreen(r) } }
        }
        waitForIdle()
        assertLive("pnpm test login")
        assertFalse(present(str(Res.string.tool_process_unknown_one)), "a call still running is never \"not returned\"")
        assertFalse(present(plural(Res.plurals.tool_process_unknown, 1), substring = true))
        // its outcome arrives with an id no card has: matched by name, the fold settles it as done
        repo!!.receiveForTest(ToolEvent(convo, 100, ToolPhase.RESULT, "Bash", ok = true, toolUseId = "tu-late", outcomeOnly = true))
        waitForIdle()
        assertLive(str(Res.string.done))
        repo!!.receiveForTest(dev.ccpocket.protocol.TurnDone(convo))
        waitForIdle()
        assertPresent(plural(Res.plurals.tool_process_tools, 2), substring = true)
        assertFalse(present(plural(Res.plurals.tool_process_unknown, 1), substring = true))
    }

    @Test
    fun aSubagentsInnerCallJoinsItsReplayedCardInsteadOfBecomingAStep() = runComposeUiTest {
        // the phone attached while a sub-agent ran: its card came from the replay, without an id
        val convo = "c-live-inner"
        var repo: PocketRepository? = null
        setContent {
            val scope = rememberCoroutineScope()
            val r = remember {
                PocketRepository(scope, account("acct-live-inner")).apply {
                    receiveForTest(live(convo, executing = false))
                    receiveForTest(
                        ConvoHistory(
                            convo,
                            listOf(u(prompt), HistoryMessage(ChatRole.TOOL, "general-purpose: 调查登录失败", tool = "Task", ok = null)),
                            lastSeq = 2,
                        ),
                    )
                    receiveForTest(live(convo, executing = true))
                }
            }
            repo = r
            PocketTheme { Box(Modifier.requiredSize(390.dp, 760.dp)) { ChatScreen(r) } }
        }
        waitForIdle()
        repo!!.receiveForTest(ToolEvent(convo, 100, ToolPhase.START, "Grep", inputPreview = "needle", toolUseId = "kid-1", parentToolUseId = "agent-1"))
        waitForIdle()
        assertEquals(0, groups(), "an inner call is never a step of the main chain")
        assertFalse(present("needle", substring = true))
        assertPresent(str(Res.string.subagent_tools, 1), substring = true) // the sub-agent card counts it
    }

    private fun ComposeUiTest.groups() = onAllNodesWithTag(TOOL_PROCESS_GROUP_TAG).fetchSemanticsNodes().size

    private fun ComposeUiTest.assertLive(text: String) =
        onNode(hasTestTag(TOOL_PROCESS_LIVE_TAG) and hasText(text, substring = true)).assertExists("live line should say \"$text\"")

    private fun plural(res: org.jetbrains.compose.resources.PluralStringResource, n: Int) =
        kotlinx.coroutines.runBlocking { org.jetbrains.compose.resources.getPluralString(res, n, n) }
}
