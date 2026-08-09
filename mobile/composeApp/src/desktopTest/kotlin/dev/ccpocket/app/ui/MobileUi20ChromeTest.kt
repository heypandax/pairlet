package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.allow_once
import dev.ccpocket.app.resources.ap_required
import dev.ccpocket.app.resources.chat_context
import dev.ccpocket.app.resources.chat_session_info
import dev.ccpocket.app.resources.chat_src_tool
import dev.ccpocket.app.resources.chat_tool_failed
import dev.ccpocket.app.resources.chat_you
import dev.ccpocket.app.resources.copy_path
import dev.ccpocket.app.resources.deny
import dev.ccpocket.app.resources.done
import dev.ccpocket.app.resources.message_queued_hint
import dev.ccpocket.app.resources.new_session_cta
import dev.ccpocket.app.resources.ses_active
import dev.ccpocket.app.resources.ses_conn_connecting
import dev.ccpocket.app.resources.ses_conn_offline
import dev.ccpocket.app.resources.ses_conn_online
import dev.ccpocket.app.resources.ses_messages
import dev.ccpocket.app.resources.ses_recent
import dev.ccpocket.app.resources.st_act_answer
import dev.ccpocket.app.resources.st_act_review
import dev.ccpocket.app.resources.st_also_running
import dev.ccpocket.app.resources.st_answer
import dev.ccpocket.app.resources.st_complete
import dev.ccpocket.app.resources.st_running
import dev.ccpocket.app.resources.time_just_now
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.ui.chat.ChatHeader
import dev.ccpocket.app.ui.chat.ChatStateBlock
import dev.ccpocket.app.ui.chat.ContextLine
import dev.ccpocket.app.ui.chat.ToolTurnBand
import dev.ccpocket.app.ui.chat.chatStateUi
import dev.ccpocket.app.ui.session.SessionListRow
import dev.ccpocket.app.ui.session.SessionRowUi
import dev.ccpocket.app.ui.session.SurfaceState
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.PendingApproval
import dev.ccpocket.protocol.PendingApprovals
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.Sessions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Mobile UI 2.0 chrome for Sessions and Chat, composed at the release baseline (iPhone 17, 402 × 874 pt).
 *
 * The pure ladders are pinned in `SessionStateUiTest` / `ChatStateUiTest`; this file is what actually
 * reaches the screen: the context hierarchy, the Active/Recent split, one written state per row, the pinned
 * dock, the collapsible chat context, the turn source labels — and the things that must NOT appear (emoji
 * metadata, a `-` placeholder, a fabricated branch or timestamp, a second decision path).
 *
 * [runDesktopComposeUiTest] gives a real 402 × 874 scene, and pinning `Density(1f, fontScale)` makes one
 * scene pixel one dp — so the same assertions double as the overflow proof at 100% and at 200% type.
 */
@OptIn(ExperimentalTestApi::class)
class MobileUi20ChromeTest {

    private val dir = "/Users/alex/code/cc-pocket"
    private val convo = "c-ui20"

    private fun account() = PairedDaemon(
        relay = "wss://test.invalid", accountId = "acct-ui20", daemonPub = "pub",
        deviceId = "dev", credential = "cred", hostName = "alex-macbook",
    )

    private fun summary(
        id: String, title: String = id, live: Boolean = false, branch: String? = "main",
        prompt: String = "", count: Int = 0,
    ) = SessionSummary(
        sessionId = id, title = title, firstPrompt = prompt, messageCount = count, cwd = dir,
        lastModified = 0L, gitBranch = branch, live = live, agent = AgentKind.CLAUDE,
    )

    private fun approvalAsk(title: String = "Run command") = PermissionAsk(
        convoId = convo, askId = "ap-1", tool = "Bash", title = title,
        inputPreview = "./gradlew :protocol:test", timeoutSec = 600,
    )

    private fun sessionLive(executing: Boolean = false) = SessionLive(
        convoId = convo, workdir = dir, sessionId = "s1", mode = PermissionMode.DEFAULT,
        executing = executing, model = "claude-sonnet-4-5", agent = AgentKind.CLAUDE,
    )

    /**
     * Compose [content] against a paired repository in a real 402 × 874 pt scene at [fontScale].
     *
     * `autoAdvance = false` because a streaming scene animates forever — every assertion below is about a
     * settled first frame, never about waiting one out.
     */
    private fun baseline(
        fontScale: Float = 1f,
        dark: Boolean = true,
        seed: PocketRepository.() -> Unit = {},
        content: @Composable (PocketRepository) -> Unit,
        assertions: SkikoComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(W, H) {
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                val scope = rememberCoroutineScope()
                val repo = remember { PocketRepository(scope, account()).apply(seed) }
                PocketTheme(dark = dark) { Box(Modifier.fillMaxSize()) { content(repo) } }
            }
        }
        waitForIdle()
        assertions()
    }

    /** Icon-only affordances carry their label as a content description, not as text. */
    private fun SkikoComposeUiTest.described(label: String): Boolean =
        onAllNodes(hasContentDescription(label)).fetchSemanticsNodes().isNotEmpty()

    /** Nothing may spill past the 402 pt viewport — the "no horizontal scroll" half of the responsive gate. */
    private fun SkikoComposeUiTest.assertWithinViewport(text: String) {
        val b = onAllNodes(hasText(text, substring = true)).onFirst().getUnclippedBoundsInRoot()
        assertTrue(b.left.value >= -0.5f, "\"$text\" starts off-screen at ${b.left}")
        assertTrue(b.right.value <= W + 0.5f, "\"$text\" overflows the ${W}pt viewport, ending at ${b.right}")
    }

    // ══ Sessions ═══════════════════════════════════════════════════════════════════════════════════

    @Test
    fun sessionsShowsTheContextHierarchyTheActiveRecentSplitAndAPinnedDock() = baseline(
        seed = {
            receiveForTest(
                Sessions(
                    dir,
                    listOf(summary("s-live", "Refactor auth module", live = true), summary("s-done", "Release notes 1.6")),
                ),
            )
        },
        content = { SessionsScreen(it) },
    ) {
        // Computer → Project → path, every line from a real value
        assertTrue(present("alex-macbook", substring = true), "the paired machine names the computer")
        // the link names exactly one honest state — and this scene has no live socket, so never "online"
        val connLabels = listOf(Res.string.ses_conn_online, Res.string.ses_conn_connecting, Res.string.ses_conn_offline).map { str(it) }
        assertEquals(1, connLabels.count { present(it, substring = true) }, "the link states exactly one state")
        assertFalse(present(str(Res.string.ses_conn_online), substring = true), "…and never claims online while it is not")
        assertTrue(present("cc-pocket", substring = true), "the project folder is its own line")
        assertTrue(present("~/code/cc-pocket", substring = true), "the FULL workdir is on screen, not a tail fragment")
        // both halves of the list, each under its own heading, each row writing its state
        assertTrue(present(str(Res.string.ses_active).uppercase()), "running work gets the Active section")
        assertTrue(present(str(Res.string.ses_recent).uppercase()), "settled work gets the Recent section")
        assertTrue(present(str(Res.string.st_running), substring = true))
        assertTrue(present(str(Res.string.st_complete), substring = true))
        // the dock is PINNED to the bottom: it must sit below every row of the list
        val dock = onAllNodes(hasText(str(Res.string.new_session_cta))).onFirst()
        dock.assertIsDisplayed()
        val lastRowBottom = onAllNodes(hasText("Release notes 1.6")).onFirst().getUnclippedBoundsInRoot().bottom
        assertTrue(
            dock.getUnclippedBoundsInRoot().top > lastRowBottom,
            "New session is a pinned bottom dock, not the first card in the list",
        )
        assertWithinViewport(str(Res.string.new_session_cta))
        assertWithinViewport("~/code/cc-pocket")
    }

    @Test
    fun sessionRowsCarryNoEmojiMetadataAndNoPlaceholderForAMissingBranch() = baseline(
        seed = { receiveForTest(Sessions(dir, listOf(summary("s1", "Fix flaky socket test", branch = null, count = 8)))) },
        content = { SessionsScreen(it) },
    ) {
        assertFalse(present("💬", substring = true), "the message count is a written count, not an emoji label")
        assertFalse(present("⑂", substring = true), "the branch is named, not glyphed")
        // the WHOLE metadata line, exactly: state, agent, count. lastModified is 0 and gitBranch is null,
        // so both are simply absent — no `-`, no empty separator, nothing standing in for what isn't known
        assertTrue(
            present("${str(Res.string.st_complete)} · Claude · ${str(Res.string.ses_messages, 8)}"),
            "a row omits the facts it lacks instead of padding them",
        )
    }

    @Test
    fun anAttentionRowStatesItsStateAndItsActionOpensTheSession() = runDesktopComposeUiTest(W, H) {
        var opened = 0
        setContent {
            PocketTheme {
                SessionListRow(
                    SessionRowUi(summary("s1", "Refactor auth module"), SurfaceState.APPROVAL),
                    onOpen = { opened++ }, onLongPress = null,
                )
            }
        }
        waitForIdle()
        assertTrue(present(str(Res.string.ap_required), substring = true), "the state is written, never colour-only")
        onAllNodes(hasText(str(Res.string.st_act_review))).onFirst().performClick()
        assertEquals(1, opened, "Review opens the session — the decision still belongs to Secure Approval")
    }

    @Test
    fun anAnswerRowSaysAnswerNotApprove() = runDesktopComposeUiTest(W, H) {
        setContent {
            PocketTheme {
                SessionListRow(
                    SessionRowUi(summary("s1", "Pick a palette"), SurfaceState.ANSWER),
                    onOpen = {}, onLongPress = null,
                )
            }
        }
        waitForIdle()
        assertTrue(present(str(Res.string.st_answer), substring = true))
        assertTrue(present(str(Res.string.st_act_answer)))
        assertFalse(present(str(Res.string.ap_required), substring = true), "a question is not a permission gate")
    }

    @Test
    fun aRealPendingApprovalPromotesItsOwnRowAndOnlyItsOwnRow() = baseline(
        seed = {
            receiveForTest(
                Sessions(dir, listOf(summary("s-blocked", "Refactor auth module"), summary("s-idle", "Release notes 1.6"))),
            )
            // the daemon's own account-wide snapshot, carrying the real session/workdir context
            receiveForTest(PendingApprovals(listOf(PendingApproval(approvalAsk(), workdir = dir, sessionId = "s-blocked"))))
        },
        content = { SessionsScreen(it) },
    ) {
        assertTrue(present(str(Res.string.ap_required), substring = true), "the blocked session states it")
        assertEquals(
            1,
            onAllNodes(hasText(str(Res.string.st_act_review))).fetchSemanticsNodes().size,
            "exactly one row owns the intervention — the idle session must not borrow it",
        )
    }

    @Test
    fun theSessionsDockAndContextSurviveTwoHundredPercentType() = baseline(
        fontScale = 2f,
        seed = { receiveForTest(Sessions(dir, listOf(summary("s1", "Refactor the authentication module end to end")))) },
        content = { SessionsScreen(it) },
    ) {
        onAllNodes(hasText(str(Res.string.new_session_cta))).onFirst().assertIsDisplayed()
        assertWithinViewport(str(Res.string.new_session_cta))
        assertWithinViewport("~/code/cc-pocket")
    }

    @Test
    fun sessionsRendersTheSameGrammarInLightTheme() = baseline(
        dark = false,
        seed = { receiveForTest(Sessions(dir, listOf(summary("s1", "Refactor auth module", live = true)))) },
        content = { SessionsScreen(it) },
    ) {
        assertTrue(present(str(Res.string.st_running), substring = true))
        onAllNodes(hasText(str(Res.string.new_session_cta))).onFirst().assertIsDisplayed()
    }

    // ══ Chat ═══════════════════════════════════════════════════════════════════════════════════════

    @Test
    fun chatContextExpandsToTheFullPathAndCollapsesBack() = runDesktopComposeUiTest(W, H) {
        val long = "/Users/alex/code/cc-pocket-android-client/app/src/main/kotlin"
        setContent {
            PocketTheme {
                var expanded by remember { mutableStateOf(false) }
                ChatHeader(
                    title = "Refactor auth module",
                    summary = listOf(ContextLine("Claude"), ContextLine("default"), ContextLine("alex-macbook")),
                    workdir = long,
                    expanded = expanded,
                    onToggleContext = { expanded = !expanded },
                    onBack = {},
                    onSessionInfo = {},
                )
            }
        }
        waitForIdle()
        assertTrue(present("Claude · default · alex-macbook"), "collapsed, the summary is one line of real facts")
        assertFalse(present(str(Res.string.chat_session_info)), "and the expanded-only controls stay away")
        assertFalse(present("⌄"), "the disclosure uses an optically centered drawn chevron, not a font glyph")

        onAllNodes(hasText("Claude · default · alex-macbook")).onFirst().performClick()
        waitForIdle()
        assertTrue(present("~/code/cc-pocket-android-client/app/src/main/kotlin"), "expanded reveals the FULL path")
        assertTrue(described(str(Res.string.copy_path)), "…beside its own copy affordance")
        assertTrue(present(str(Res.string.chat_session_info)), "…and session info stays explicitly reachable")
        assertFalse(present("⌃"), "the expanded mark is the same chevron rotated, not another font glyph")

        onAllNodes(hasText(str(Res.string.chat_context).uppercase())).onFirst().performClick()
        waitForIdle()
        assertFalse(present(str(Res.string.chat_session_info)), "collapsing gives the region back to the stream")
        assertTrue(present("Claude · default · alex-macbook"), "…and restores the summary")
    }

    @Test
    fun theChatHeaderNeitherLiftsASessionBranchNorInventsATimestamp() = baseline(
        seed = {
            // the branch IS known here — it rode in on the session list — and must still not reach Chat:
            // gitBranch is per-session truth for that row, not a fact about the conversation
            receiveForTest(Sessions(dir, listOf(summary("s1", "Refactor auth module", branch = "feat/auth-refactor"))))
            receiveForTest(sessionLive())
            receiveForTest(ConvoHistory(convo, listOf(HistoryMessage(ChatRole.USER, "add a unit test for the stream parser"))))
        },
        content = { ChatScreen(it) },
    ) {
        assertTrue(present("Claude", substring = true), "the agent is real and named")
        assertFalse(present("feat/auth-refactor", substring = true), "no branch is lifted into the conversation header")
        assertFalse(present(str(Res.string.time_just_now), substring = true), "no chat event carries a clock, so none is drawn")
    }

    @Test
    fun theWorkStreamLabelsUserAgentAndToolTurns() = baseline(
        seed = {
            receiveForTest(sessionLive())
            receiveForTest(
                ConvoHistory(
                    convo,
                    listOf(
                        HistoryMessage(ChatRole.USER, "add a unit test for the stream parser"),
                        HistoryMessage(ChatRole.ASSISTANT, "The parser now emits exactly one event per frame."),
                        HistoryMessage(ChatRole.TOOL, "./gradlew :protocol:test", tool = "Bash", ok = true),
                    ),
                ),
            )
        },
        content = { ChatScreen(it) },
    ) {
        assertTrue(present(str(Res.string.chat_you).uppercase()), "the user turn names its source")
        assertTrue(present("CLAUDE"), "the agent turn names the REAL backend, not a generic \"assistant\"")
        assertTrue(present(str(Res.string.chat_src_tool).uppercase()), "and a tool call says so")
        assertTrue(present("Bash"), "the tool token is the daemon's own")
        assertTrue(present("./gradlew :protocol:test"), "the payload is the literal command, never a summary")
        assertTrue(present(str(Res.string.done)), "a real ok = true reads as Done")
    }

    @Test
    fun aToolWithNoRecordedOutcomeClaimsNone() = runDesktopComposeUiTest(W, H) {
        setContent { PocketTheme { ToolTurnBand(tool = "Bash", preview = "./gradlew :protocol:test", status = null) } }
        waitForIdle()
        assertTrue(present("./gradlew :protocol:test"))
        assertFalse(present(str(Res.string.done)), "ok == null is running-or-unknown; it must not read as success")
        assertFalse(present(str(Res.string.chat_tool_failed)), "…nor as failure")
        assertFalse(present("passed", substring = true), "and no test count is invented")
    }

    @Test
    fun theChatStateBlockLeadsWithApprovalDemotesRunningAndDecidesNothing() = runDesktopComposeUiTest(W, H) {
        setContent {
            PocketTheme {
                ChatStateBlock(chatStateUi(approvalAsk("Upload coverage to Codecov"), sessionDegraded = false, streaming = true)!!)
            }
        }
        waitForIdle()
        assertTrue(present(str(Res.string.ap_required), substring = true), "the intervention leads")
        assertTrue(present("Upload coverage to Codecov"), "quoting the ask's own title")
        assertTrue(present(str(Res.string.st_also_running)), "and Running is only the qualifying line")
        // Secure Approval owns the decision: this block offers no second way to answer
        assertFalse(present(str(Res.string.st_act_review)), "no second Review path")
        assertFalse(present(str(Res.string.deny)), "no second Deny")
        assertFalse(present(str(Res.string.allow_once)), "no second Allow")
    }

    @Test
    fun theChatHeaderStateBlockAndComposerAllSurviveTwoHundredPercentType() = baseline(
        fontScale = 2f,
        seed = {
            receiveForTest(sessionLive(executing = true))
            receiveForTest(ConvoHistory(convo, listOf(HistoryMessage(ChatRole.USER, "add a unit test for the stream parser"))))
        },
        content = { ChatScreen(it) },
    ) {
        // the pinned state and the composer both stay reachable in the same frame at double type
        onAllNodes(hasText(str(Res.string.st_running), substring = true)).onFirst().assertIsDisplayed()
        onAllNodes(hasText(str(Res.string.message_queued_hint), substring = true)).onFirst().assertIsDisplayed()
        assertWithinViewport(str(Res.string.st_running))
    }

    private companion object {
        const val W = 402
        const val H = 874
    }
}
