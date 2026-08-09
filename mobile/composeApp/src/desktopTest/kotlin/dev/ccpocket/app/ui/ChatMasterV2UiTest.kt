package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import dev.ccpocket.app.data.FileUpState
import dev.ccpocket.app.data.PendingFile
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.attach_menu
import dev.ccpocket.app.resources.chat_context_collapsed
import dev.ccpocket.app.resources.chat_context_expanded
import dev.ccpocket.app.resources.chat_you
import dev.ccpocket.app.resources.composer_uploading
import dev.ccpocket.app.resources.dictate
import dev.ccpocket.app.resources.message_agent_hint
import dev.ccpocket.app.resources.message_queued_hint
import dev.ccpocket.app.resources.qa_context_gauge
import dev.ccpocket.app.resources.qa_model
import dev.ccpocket.app.resources.send
import dev.ccpocket.app.resources.stop
import dev.ccpocket.app.resources.switcher_open
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.ActiveSession
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.DirectoryEntry
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SessionLive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Chat Master v2 — the gaps between the archived handoff and the shipped Chat screen, at the release
 * baseline (iPhone 17, 402 × 874 pt).
 *
 * `MobileUi20ChromeTest` already pins the header/context/state grammar. This file pins the three things
 * that master corrected: a list-first transcript with no chat bubble, a composer that tells the truth
 * about which agent it talks to and why Send behaves the way it does, and 48pt targets under the 30pt
 * pills and 44pt circles. Every scene is a real [PocketRepository] fed real wire frames.
 */
@OptIn(ExperimentalTestApi::class)
class ChatMasterV2UiTest {

    private val dir = "/Users/alex/code/cc-pocket"
    private val convo = "c-master2"

    private fun account() = PairedDaemon(
        relay = "wss://test.invalid", accountId = "acct-master2", daemonPub = "pub",
        deviceId = "dev", credential = "cred", hostName = "alex-macbook",
    )

    private fun live(
        executing: Boolean = false,
        agent: AgentKind = AgentKind.CLAUDE,
        model: String = "claude-fable-5",
        contextUsed: Long? = null,
    ) = SessionLive(
        convoId = convo, workdir = dir, sessionId = "s1", mode = PermissionMode.DEFAULT,
        executing = executing, model = model, agent = agent, contextUsed = contextUsed,
    )

    private fun baseline(
        seed: PocketRepository.() -> Unit = {},
        content: @Composable (PocketRepository) -> Unit = { ChatScreen(it) },
        assertions: SkikoComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(W, H) {
        mainClock.autoAdvance = false // a streaming scene animates forever; every assertion is about a settled frame
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                val scope = rememberCoroutineScope()
                val repo = remember { PocketRepository(scope, account()).apply(seed) }
                PocketTheme { Box(Modifier.fillMaxSize()) { content(repo) } }
            }
        }
        waitForIdle()
        assertions()
    }

    /** The one node that both carries [label] and is the control — icon glyphs inside it are merged in. */
    private fun SkikoComposeUiTest.control(label: String) =
        onAllNodes(hasContentDescription(label) and hasClickAction())

    private fun SkikoComposeUiTest.controlCount(label: String) = control(label).fetchSemanticsNodes().size

    /**
     * The handoff's accessibility floor: whatever a control LOOKS like — a 30pt chip, a 44pt circle — the
     * thing a thumb and the semantics tree actually get is at least [TARGET] square. Also proves the
     * enlargement stayed inside the 402pt viewport instead of buying the target with an overflow.
     */
    private fun SkikoComposeUiTest.assertFullTarget(label: String) {
        assertEquals(1, controlCount(label), "\"$label\" must be exactly one control")
        val b = control(label).onFirst().getUnclippedBoundsInRoot()
        val w = (b.right - b.left).value
        val h = (b.bottom - b.top).value
        assertTrue(w >= TARGET - 0.5f, "\"$label\" is only ${w}pt wide")
        assertTrue(h >= TARGET - 0.5f, "\"$label\" is only ${h}pt tall")
        assertTrue(b.left.value >= -0.5f && b.right.value <= W + 0.5f, "\"$label\" spills the viewport: ${b.left}..${b.right}")
    }

    // ══ 1 · transcript grammar ═════════════════════════════════════════════════════════════════════

    /**
     * A user turn is a full-width list entry. It used to be a right-aligned bubble capped at 300dp, so a
     * pasted log reflowed into a narrow ribbon while the agent's reply beside it read at full measure —
     * two grammars for one conversation, and the one thing Chat Master v2 names explicitly.
     */
    @Test
    fun aUserTurnIsFullWidthAndCarriesNoBubble() = baseline(
        seed = {
            receiveForTest(live())
            receiveForTest(ConvoHistory(convo, listOf(HistoryMessage(ChatRole.USER, LONG_PROMPT))))
        },
    ) {
        val b = onAllNodes(hasText(LONG_PROMPT)).onFirst().getUnclippedBoundsInRoot()
        val w = (b.right - b.left).value
        assertTrue(b.left.value <= 20f, "the turn starts at the transcript's own gutter, not inset by a bubble (${b.left})")
        assertTrue(w > 300f, "the turn owns the column: ${w}pt would still fit the retired 300dp bubble cap")
        assertTrue(b.right.value <= W + 0.5f, "…and still nothing spills the 402pt viewport")
    }

    /** The label is what says "you" now, so it keeps its trailing edge — the container no longer does. */
    @Test
    fun theUserLabelStillReadsFromTheTrailingEdge() = baseline(
        seed = {
            receiveForTest(live())
            receiveForTest(ConvoHistory(convo, listOf(HistoryMessage(ChatRole.USER, LONG_PROMPT))))
        },
    ) {
        val label = onAllNodes(hasText(str(Res.string.chat_you).uppercase())).onFirst().getUnclippedBoundsInRoot()
        val body = onAllNodes(hasText(LONG_PROMPT)).onFirst().getUnclippedBoundsInRoot()
        assertTrue(
            kotlin.math.abs((label.right - body.right).value) < 2f,
            "the source label stays flush with the turn's trailing edge (${label.right} vs ${body.right})",
        )
    }

    /** The compact transcript keeps two wrapping lines, then the existing band tap reveals the literal command. */
    @Test
    fun aLongToolCommandCollapsesAndExpandsWithoutHorizontalOverflow() = baseline(
        seed = {
            receiveForTest(live())
            receiveForTest(
                ConvoHistory(convo, listOf(HistoryMessage(ChatRole.TOOL, LONG_COMMAND, tool = "Bash", ok = true))),
            )
        },
    ) {
        fun layout(): TextLayoutResult {
            val node = onAllNodes(hasText(LONG_COMMAND), useUnmergedTree = true).onFirst().fetchSemanticsNode()
            return mutableListOf<TextLayoutResult>().also {
                node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(it)
            }.first()
        }

        val collapsed = layout()
        assertTrue(collapsed.hasVisualOverflow, "a long tool starts as the compact preview")
        assertEquals(2, collapsed.lineCount, "the preview wraps within two lines")

        onAllNodes(hasText(LONG_COMMAND) and hasClickAction()).onFirst().performClick()
        waitForIdle()
        val expanded = layout()
        assertFalse(expanded.hasVisualOverflow, "expanding reveals the literal payload with no horizontal clip")
        assertTrue(expanded.lineCount > 2, "the complete command wraps onto ${expanded.lineCount} lines")
    }

    // ══ 2 · composer truth ═════════════════════════════════════════════════════════════════════════

    /** The placeholder names the backend this conversation actually has. */
    @Test
    fun theIdlePlaceholderNamesClaude() = baseline(seed = { receiveForTest(live()) }) {
        assertTrue(present(str(Res.string.message_agent_hint, "Claude")), "a Claude session invites you to message Claude")
    }

    /** …which is the whole point: a Codex session used to invite you to "Message Claude…". */
    @Test
    fun theIdlePlaceholderNamesCodex() = baseline(
        seed = { receiveForTest(live(agent = AgentKind.CODEX, model = "gpt-5-codex")) },
    ) {
        assertTrue(present(str(Res.string.message_agent_hint, "Codex")), "a Codex session says Codex")
        assertFalse(present(str(Res.string.message_agent_hint, "Claude")), "…and never the other backend's name")
    }

    /** Streaming with nothing typed: the slot is Stop. Not Stop AND a microphone, not Stop and a Send. */
    @Test
    fun streamingWithAnEmptyFieldOffersStopAlone() = baseline(seed = { receiveForTest(live(executing = true)) }) {
        assertEquals(1, controlCount(str(Res.string.stop)), "the action slot is the interrupt")
        assertEquals(0, controlCount(str(Res.string.dictate)), "…with no microphone beside it")
        assertEquals(0, controlCount(str(Res.string.send)), "…and nothing to send")
        assertTrue(present(str(Res.string.message_queued_hint)), "the note already explains what a send would do")
    }

    /**
     * Streaming with a draft: Stop and Send coexist (Claude's stream-json weaves a mid-turn message into
     * the running turn), and the explanation SURVIVES the typing. It used to ride the field's placeholder,
     * so it disappeared at exactly the keystroke that made it true.
     */
    @Test
    fun streamingWithADraftOffersStopAndSendAndKeepsSayingSendsQueue() = baseline(
        seed = { receiveForTest(live(executing = true)) },
    ) {
        onAllNodes(hasSetTextAction()).onFirst().performTextInput("also update the changelog")
        waitForIdle()
        assertEquals(1, controlCount(str(Res.string.stop)), "the interrupt stays put")
        assertEquals(1, controlCount(str(Res.string.send)), "…and Send joins it rather than replacing it")
        assertTrue(
            present(str(Res.string.message_queued_hint)),
            "the queue explanation outlives the placeholder it used to hide behind",
        )
    }

    /**
     * Uploads in flight: the send state is muted (no live Send control at all) and the wait is WRITTEN,
     * with the real file counts — the landed `@`-references do not exist until the daemon receipts them.
     */
    @Test
    fun anUploadInProgressWritesTheWaitAndOffersNoLiveSend() = baseline(
        seed = {
            receiveForTest(live())
            pendingFiles += PendingFile(
                id = 1L, name = "trace.log", size = 2048, bytes = ByteArray(0),
                mediaType = "text/plain", state = FileUpState.Uploading,
            )
            pendingFiles += PendingFile(
                id = 2L, name = "crash.txt", size = 512, bytes = ByteArray(0),
                mediaType = "text/plain", state = FileUpState.Landed, path = "~/inbox/crash.txt",
            )
        },
    ) {
        assertTrue(
            present(str(Res.string.composer_uploading, 1, 2)),
            "the note counts the REAL files still moving, out of the real total",
        )
        assertEquals(0, controlCount(str(Res.string.send)), "and nothing offers to send before the landing")
    }

    // ══ 3 · targets and disclosure semantics ═══════════════════════════════════════════════════════

    /**
     * Every accessory control the idle composer offers is a full [TARGET] slot, though the model chip is
     * still drawn 30pt tall and the microphone 44pt round. The gauge is the tell: at rest it draws a 30 ×
     * 30 capsule, which is narrower than a thumb.
     */
    @Test
    fun everyIdleComposerControlIsAFullTouchTarget() = baseline(
        seed = { receiveForTest(live(contextUsed = 84_000)) },
    ) {
        assertFullTarget(str(Res.string.attach_menu))
        assertFullTarget(str(Res.string.qa_model))
        assertFullTarget(str(Res.string.qa_context_gauge))
        assertFullTarget(str(Res.string.dictate))
    }

    /** The same floor for the mid-turn pair, which is where the row is tightest. */
    @Test
    fun theMidTurnStopAndSendAreFullTouchTargetsToo() = baseline(
        seed = { receiveForTest(live(executing = true, contextUsed = 84_000)) },
    ) {
        onAllNodes(hasSetTextAction()).onFirst().performTextInput("also update the changelog")
        waitForIdle()
        assertFullTarget(str(Res.string.stop))
        assertFullTarget(str(Res.string.send))
        assertFullTarget(str(Res.string.attach_menu))
        assertFullTarget(str(Res.string.qa_context_gauge))
    }

    /** The session-stack chip only exists when there is somewhere to jump to — and then it is a target. */
    @Test
    fun theSessionStackChipIsAFullTouchTargetWhenItExists() = baseline(
        seed = {
            receiveForTest(live())
            // another project with a session of its own alive — the only thing that gives the chip a count
            directories.add(
                DirectoryEntry(
                    path = "/Users/alex/code/relay", name = "relay", isDir = true, open = true,
                    activeSessions = listOf(ActiveSession(sessionId = "s-relay", title = "Fix flaky retry backoff", executing = true)),
                    activeSessionId = "s-relay", activeSessionTitle = "Fix flaky retry backoff",
                ),
            )
        },
    ) {
        assertFullTarget(str(Res.string.switcher_open))
    }

    /**
     * The disclosure speaks its state. The drawn chevron carries nothing to a screen reader, and the
     * action label ("Show full context") says what a tap does, never where the row is now.
     */
    @Test
    fun theContextDisclosureSpeaksCollapsedAndExpanded() = baseline(seed = { receiveForTest(live()) }) {
        val collapsed = str(Res.string.chat_context_collapsed)
        val expanded = str(Res.string.chat_context_expanded)
        assertEquals(1, stateNodes(collapsed), "collapsed says so")
        assertEquals(0, stateNodes(expanded))

        onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, collapsed)).onFirst().performClick()
        waitForIdle()
        assertEquals(1, stateNodes(expanded), "…and expanding updates the spoken state, not only the chevron")
        assertEquals(0, stateNodes(collapsed))
    }

    private fun SkikoComposeUiTest.stateNodes(state: String) =
        onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state)).fetchSemanticsNodes().size

    private companion object {
        const val W = 402
        const val H = 874

        /** The handoff's interactive minimum, in pt. */
        const val TARGET = 48f

        val LONG_PROMPT =
            "here is the failing run: the relay drops the socket about forty seconds after the phone " +
                "reconnects, and the daemon keeps replaying the same history frame each time"

        val LONG_COMMAND =
            "./gradlew :mobile:composeApp:desktopTest --tests 'dev.ccpocket.app.ui.MobileUi20ChromeTest' --rerun-tasks"
    }
}
