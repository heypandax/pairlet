package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.data.FileUpState
import dev.ccpocket.app.data.PendingFile
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.data.VoiceState
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.attach_menu
import dev.ccpocket.app.resources.cancel_recording
import dev.ccpocket.app.resources.chat_context_collapsed
import dev.ccpocket.app.resources.chat_context_expanded
import dev.ccpocket.app.resources.chat_you
import dev.ccpocket.app.resources.composer_uploading
import dev.ccpocket.app.resources.dictate
import dev.ccpocket.app.resources.done
import dev.ccpocket.app.resources.message_agent_hint
import dev.ccpocket.app.resources.message_queued_hint
import dev.ccpocket.app.resources.qa_context_gauge
import dev.ccpocket.app.resources.qa_model
import dev.ccpocket.app.resources.retry_voice_input
import dev.ccpocket.app.resources.send
import dev.ccpocket.app.resources.stop
import dev.ccpocket.app.resources.switcher_open
import dev.ccpocket.app.resources.transcribing
import dev.ccpocket.app.resources.voice_transcribe_failed
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
        width: Int = W,
        height: Int = H,
        fontScale: Float = 1f,
        assertions: SkikoComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(width, height) {
        mainClock.autoAdvance = false // a streaming scene animates forever; every assertion is about a settled frame
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
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

    private val politeLiveRegion = SemanticsMatcher("polite live region") {
        it.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite
    }

    /**
     * The handoff's accessibility floor: whatever a control LOOKS like — a 30pt chip, a 44pt circle — the
     * thing a thumb and the semantics tree actually get is at least [TARGET] square. Also proves the
     * enlargement stayed inside the 402pt viewport instead of buying the target with an overflow.
     */
    private fun SkikoComposeUiTest.assertFullTarget(label: String, viewportWidth: Int = W, minimum: Float = TARGET) {
        assertEquals(1, controlCount(label), "\"$label\" must be exactly one control")
        val b = control(label).onFirst().getUnclippedBoundsInRoot()
        val w = (b.right - b.left).value
        val h = (b.bottom - b.top).value
        assertTrue(w >= minimum - 0.5f, "\"$label\" is only ${w}pt wide")
        assertTrue(h >= minimum - 0.5f, "\"$label\" is only ${h}pt tall")
        assertTrue(b.left.value >= -0.5f && b.right.value <= viewportWidth + 0.5f, "\"$label\" spills the viewport: ${b.left}..${b.right}")
    }

    /** Canonical accessory actions are labelled rectangles, not the 48 dp circles used by Mic/capture. */
    private fun SkikoComposeUiTest.assertLaneAction(
        label: String,
        viewportWidth: Int = W,
        minimumHeight: Float = 48f,
    ) {
        assertEquals(1, controlCount(label), "\"$label\" must be exactly one lane action")
        val b = control(label).onFirst().getUnclippedBoundsInRoot()
        val w = (b.right - b.left).value
        val h = (b.bottom - b.top).value
        assertTrue(w >= 83.5f, "\"$label\" is only ${w}pt wide; the lane floor is 84pt")
        assertTrue(h >= minimumHeight - 0.5f, "\"$label\" is only ${h}pt tall")
        assertTrue(b.left.value >= -0.5f && b.right.value <= viewportWidth + 0.5f, "\"$label\" spills the viewport: ${b.left}..${b.right}")
    }

    private fun SkikoComposeUiTest.assertCompactRoundTarget(label: String) {
        val b = control(label).onFirst().getUnclippedBoundsInRoot()
        assertTrue(kotlin.math.abs((b.right - b.left).value - 48f) < 0.5f, "\"$label\" lost its 48pt round slot: $b")
        assertTrue(kotlin.math.abs((b.bottom - b.top).value - 48f) < 0.5f, "\"$label\" lost its 48pt round slot: $b")
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
        assertEquals(1, controlCount(str(Res.string.dictate)), "…while Mic remains independently reachable for an appended phrase")
        assertTrue(
            present(str(Res.string.message_queued_hint)),
            "the queue explanation outlives the placeholder it used to hide behind",
        )
    }

    /** #238: staged text keeps Mic and Send independent, including the two compact phone widths. */
    @Test
    fun aDraftKeepsVoiceAndSendReachableAtThreePhoneWidths() {
        listOf(W, 375, 320).forEach { viewport ->
            baseline(seed = { receiveForTest(live()) }, width = viewport) {
                onAllNodes(hasSetTextAction()).onFirst().performTextInput("keep this draft")
                waitForIdle()
                assertEquals(1, controlCount(str(Res.string.dictate)), "$viewport pt keeps one Mic")
                assertEquals(1, controlCount(str(Res.string.send)), "$viewport pt keeps one Send")
                assertFullTarget(str(Res.string.dictate), viewportWidth = viewport, minimum = 48f)
                assertFullTarget(str(Res.string.send), viewportWidth = viewport, minimum = 48f)
                assertCompactRoundTarget(str(Res.string.dictate))
                val mic = control(str(Res.string.dictate)).onFirst().getUnclippedBoundsInRoot()
                val send = control(str(Res.string.send)).onFirst().getUnclippedBoundsInRoot()
                assertTrue(mic.bottom <= send.top, "$viewport pt separates field Mic from accessory Send")
            }
        }
    }

    /** The real pendingVoiceText effect appends after the current field value and keeps review controls. */
    @Test
    fun aVoiceResultAppendsToTheLiveDraftBeforeExplicitSend() {
        lateinit var mountedRepo: PocketRepository
        baseline(
            seed = { receiveForTest(live()) },
            content = { repo -> mountedRepo = repo; ChatScreen(repo) },
        ) {
            onAllNodes(hasSetTextAction()).onFirst().performTextInput("keep this draft")
            runOnIdle { mountedRepo.pendingVoiceText.value = "add a regression test" }
            waitForIdle()
            assertTrue(present("keep this draft add a regression test"), "the transcript appends instead of replacing the draft")
            assertEquals(1, controlCount(str(Res.string.dictate)), "the result may be extended with another voice phrase")
            assertEquals(1, controlCount(str(Res.string.send)), "the combined text still waits for explicit Send")
        }
    }

    /**
     * Streaming still owns Stop + Send below; Mic stays in the field instead of squeezing a third peer in.
     *
     * Run at both compact stress widths (#238 · V3): the lane's whole trailing group drops below the
     * leading group rather than any control shrinking, clipping or disappearing into an overflow.
     */
    @Test
    fun compactStreamingDraftSeparatesMicFromStopAndSend() {
        listOf(320, 280).forEach { viewport ->
            baseline(
                seed = {
                    receiveForTest(live(executing = true, contextUsed = 84_000))
                    directories.add(
                        DirectoryEntry(
                            path = "/Users/alex/code/relay", name = "relay", isDir = true, open = true,
                            activeSessions = listOf(
                                ActiveSession(sessionId = "s-relay", title = "Fix relay backoff", executing = true),
                            ),
                            activeSessionId = "s-relay", activeSessionTitle = "Fix relay backoff",
                        ),
                    )
                },
                width = viewport,
            ) {
                onAllNodes(hasSetTextAction()).onFirst().performTextInput("queue this after dictation")
                waitForIdle()
                val labels = listOf(
                    Res.string.attach_menu,
                    Res.string.dictate,
                    Res.string.switcher_open,
                    Res.string.qa_context_gauge,
                    Res.string.stop,
                    Res.string.send,
                )
                labels.forEach { label ->
                    assertFullTarget(str(label), viewportWidth = viewport, minimum = 48f)
                }
                labels.map { str(it) }.forEachIndexed { index, first ->
                    val a = control(first).onFirst().getUnclippedBoundsInRoot()
                    labels.drop(index + 1).map { str(it) }.forEach { second ->
                        val b = control(second).onFirst().getUnclippedBoundsInRoot()
                        assertTrue(
                            a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top,
                            "$first and $second overlap at ${viewport}pt: $a / $b",
                        )
                    }
                }
                // the WHOLE trailing group moved below the leading group — not half of it, and nothing
                // was dropped to buy the fit
                val context = control(str(Res.string.qa_context_gauge)).onFirst().getUnclippedBoundsInRoot()
                listOf(Res.string.stop, Res.string.send).forEach {
                    assertTrue(
                        context.bottom <= control(str(it)).onFirst().getUnclippedBoundsInRoot().top,
                        "${str(it)} takes its own line below the leading group at ${viewport}pt",
                    )
                }
                val stop = control(str(Res.string.stop)).onFirst().getUnclippedBoundsInRoot()
                val send = control(str(Res.string.send)).onFirst().getUnclippedBoundsInRoot()
                assertLaneAction(str(Res.string.stop), viewportWidth = viewport)
                assertLaneAction(str(Res.string.send), viewportWidth = viewport)
                assertTrue(
                    kotlin.math.abs((stop.right - stop.left).value - (send.right - send.left).value) < 0.5f,
                    "stacked Stop and Send must split the row equally at ${viewport}pt: $stop / $send",
                )
                assertTrue(stop.left.value <= 6.5f, "stacked actions start at the lane gutter: $stop")
                assertTrue(send.right.value >= viewport - 8.5f, "stacked actions end at the lane gutter: $send")
            }
        }
    }

    /** The same lane at 200% type: nothing inline, nothing hidden, every target grown rather than shrunk. */
    @Test
    fun theAccessoryLaneDestacksAtDoubleTypeWithoutLosingAControl() = baseline(
        seed = {
            receiveForTest(live(executing = true, contextUsed = 84_000))
            directories.add(
                DirectoryEntry(
                    path = "/Users/alex/code/relay", name = "relay", isDir = true, open = true,
                    activeSessions = listOf(ActiveSession(sessionId = "s-relay", title = "Fix relay backoff", executing = true)),
                    activeSessionId = "s-relay", activeSessionTitle = "Fix relay backoff",
                ),
            )
        },
        fontScale = 2f,
    ) {
        onAllNodes(hasSetTextAction()).onFirst().performTextInput("queue this")
        waitForIdle()
        listOf(
            Res.string.attach_menu, Res.string.dictate, Res.string.switcher_open,
            Res.string.qa_context_gauge,
        ).forEach {
            assertFullTarget(str(it), minimum = 48f)
            // …and WHOLLY on screen. `getUnclippedBounds` alone would call a control that has been pushed
            // off the bottom of the viewport a full target, which is exactly the failure 200% type causes.
            val node = control(str(it)).onFirst()
            val shown = node.getBoundsInRoot()
            val whole = node.getUnclippedBoundsInRoot()
            assertTrue(
                kotlin.math.abs((shown.top - whole.top).value) < 0.5f &&
                    kotlin.math.abs((shown.bottom - whole.bottom).value) < 0.5f,
                "${str(it)} is clipped at 200% type: shown $shown of $whole",
            )
        }
        listOf(Res.string.stop, Res.string.send).forEach {
            assertLaneAction(str(it), minimumHeight = 58f)
            val node = control(str(it)).onFirst()
            val shown = node.getBoundsInRoot()
            val whole = node.getUnclippedBoundsInRoot()
            assertEquals(whole, shown, "${str(it)} is clipped at 200% type")
        }
        val stop = control(str(Res.string.stop)).onFirst().getUnclippedBoundsInRoot()
        val send = control(str(Res.string.send)).onFirst().getUnclippedBoundsInRoot()
        assertTrue(
            kotlin.math.abs((stop.right - stop.left).value - (send.right - send.left).value) < 0.5f,
            "large-type Stop and Send must retain equal shares: $stop / $send",
        )
    }

    /**
     * The tightest frame on the board: 200% type, an upload tray, a ribbon and a running turn all stacked
     * above the lane. Everything the lane owns must still be whole and on screen — the pressure valve is
     * the lane's own height, never a control that quietly leaves.
     */
    @Test
    fun theUploadLaneStaysWholeAtDoubleTypeWithATrayAbove() = baseline(
        seed = {
            receiveForTest(live(executing = true, contextUsed = 84_000))
            directories.add(
                DirectoryEntry(
                    path = "/Users/alex/code/relay", name = "relay", isDir = true, open = true,
                    activeSessions = listOf(ActiveSession(sessionId = "s-relay", title = "Fix relay backoff", executing = true)),
                    activeSessionId = "s-relay", activeSessionTitle = "Fix relay backoff",
                ),
            )
            pendingFiles += PendingFile(
                id = 5L, name = "trace.log", size = 2048, bytes = ByteArray(0),
                mediaType = "text/plain", state = FileUpState.Uploading,
            )
        },
        fontScale = 2f,
    ) {
        listOf(
            Res.string.attach_menu, Res.string.dictate, Res.string.switcher_open,
            Res.string.qa_context_gauge, Res.string.stop,
        ).forEach {
            assertFullTarget(str(it), minimum = 48f)
            val node = control(str(it)).onFirst()
            assertEquals(
                node.getUnclippedBoundsInRoot(), node.getBoundsInRoot(),
                "${str(it)} is clipped in the tightest 200% frame",
            )
        }
        assertEquals(0, controlCount(str(Res.string.send)), "still nothing to send before the file lands")
        assertEquals(
            1,
            onAllNodes(hasContentDescription(str(Res.string.composer_uploading, 1, 1))).fetchSemanticsNodes().size,
            "the upload status slot survives the tightest frame",
        )
    }

    /**
     * The wrap decision itself, as data (design master · "Wrapping rule · one predicate"). A rule this
     * layout depends on should be provable without measuring pixels in eight scenes.
     */
    @Test
    fun theLaneWrapPredicateStacksOnceTheWholeGroupsStopFitting() {
        // 402 pt phone, no switcher, Stop + Send: still inline
        assertTrue(composerLaneFitsInline(388.dp, switcherVisible = false, actionCount = 2, fontScale = 1f))
        // …the same phone once a cross-session switcher joins the leading group: the actions move below
        assertFalse(composerLaneFitsInline(388.dp, switcherVisible = true, actionCount = 2, fontScale = 1f))
        // idle at the narrowest supported width still fits — nothing wraps that does not need to
        assertTrue(composerLaneFitsInline(266.dp, switcherVisible = false, actionCount = 0, fontScale = 1f))
        assertFalse(composerLaneFitsInline(266.dp, switcherVisible = false, actionCount = 2, fontScale = 1f))
        // 200% type never packs inline, however wide the screen is
        assertFalse(composerLaneFitsInline(1000.dp, switcherVisible = false, actionCount = 0, fontScale = 1.5f))
    }

    /**
     * Uploading is STATUS, not a disabled Send. The slot names itself with the real moving/total sentence
     * and offers no click at all — a disabled Send would announce a send you are forbidden to make, when
     * the truth is that there is nothing to send until the workspace path exists.
     */
    @Test
    fun theUploadSlotIsNamedStatusAndIsNeverAButton() = baseline(
        seed = {
            receiveForTest(live())
            pendingFiles += PendingFile(
                id = 3L, name = "trace.log", size = 2048, bytes = ByteArray(0),
                mediaType = "text/plain", state = FileUpState.Uploading,
            )
        },
        width = 320,
    ) {
        val uploading = str(Res.string.composer_uploading, 1, 1)
        assertEquals(
            1, onAllNodes(hasContentDescription(uploading)).fetchSemanticsNodes().size,
            "the slot carries the existing upload sentence as its accessible name",
        )
        assertEquals(
            0, onAllNodes(hasContentDescription(uploading) and hasClickAction()).fetchSemanticsNodes().size,
            "…and is not a control",
        )
        assertEquals(0, controlCount(str(Res.string.send)), "no Send exists while the file is still moving")
        assertTrue(present(uploading), "the written wait stays in the ribbon above the field")
        assertFullTarget(str(Res.string.dictate), viewportWidth = 320, minimum = 48f)
    }

    /** Upload + a running turn: the interrupt survives, and Send still does not exist. */
    @Test
    fun anUploadDuringAStreamingTurnKeepsStopAndStillOffersNoSend() = baseline(
        seed = {
            receiveForTest(live(executing = true))
            pendingFiles += PendingFile(
                id = 4L, name = "trace.log", size = 2048, bytes = ByteArray(0),
                mediaType = "text/plain", state = FileUpState.Uploading,
            )
        },
        width = 320,
    ) {
        assertFullTarget(str(Res.string.stop), viewportWidth = 320, minimum = 48f)
        assertEquals(0, controlCount(str(Res.string.send)))
        assertEquals(1, controlCount(str(Res.string.dictate)), "voice stays reachable in the field")
        assertTrue(present(str(Res.string.composer_uploading, 1, 1)), "uploading leads the queue note")
        assertFalse(present(str(Res.string.message_queued_hint)), "upload feedback must outrank the queue note")
    }

    /** Idle: Mic is the only thing offered. No Stop, no Send, and no upload status pretending to be one. */
    @Test
    fun theIdleComposerOffersVoiceAndNoTurnActions() = baseline(seed = { receiveForTest(live()) }) {
        assertEquals(1, controlCount(str(Res.string.dictate)))
        assertEquals(0, controlCount(str(Res.string.send)))
        assertEquals(0, controlCount(str(Res.string.stop)))
        assertFalse(present(str(Res.string.message_queued_hint)), "nothing unusual is happening, so no ribbon")
    }

    /** A running agent turn remains independently interruptible throughout both voice capture phases. */
    @Test
    fun streamingStopSurvivesRecordingAndTranscribing() {
        listOf<VoiceState>(VoiceState.Recording(1_250), VoiceState.Transcribing).forEach { capture ->
            baseline(
                seed = {
                    receiveForTest(live(executing = true))
                    voice.value = capture
                },
                width = 320,
            ) {
                assertFullTarget(str(Res.string.stop), viewportWidth = 320)
                assertCompactRoundTarget(str(Res.string.stop))
                assertEquals(1, controlCount(str(Res.string.stop)), "$capture keeps the agent-turn interrupt")
                // …and it is the ONLY survivor: capture replaces the ordinary composer wholesale, so the
                // accessory lane, the field and its Mic are gone rather than stacked under the bar
                listOf(
                    Res.string.attach_menu, Res.string.qa_model, Res.string.qa_context_gauge,
                    Res.string.dictate, Res.string.retry_voice_input, Res.string.send,
                ).forEach {
                    assertEquals(0, controlCount(str(it)), "$capture must not leave ${str(it)} beside the recording bar")
                }
                assertFullTarget(str(Res.string.done), viewportWidth = 320, minimum = 48f)
                assertFullTarget(str(Res.string.cancel_recording), viewportWidth = 320, minimum = 48f)
                if (capture == VoiceState.Transcribing) {
                    val live = onAllNodes(politeLiveRegion, useUnmergedTree = true).fetchSemanticsNodes()
                    assertEquals(
                        1,
                        live.size,
                        "the stable transcribing text announces once without putting the ticking timer in the live region",
                    )
                    assertEquals(
                        listOf(str(Res.string.transcribing)),
                        live.single().config.getOrNull(SemanticsProperties.Text)?.map { it.text },
                        "the polite node contains only stable state text, never the ticking timer",
                    )
                    assertEquals(
                        0,
                        onAllNodes(
                            hasText(fmtElapsed(0)) and politeLiveRegion,
                            useUnmergedTree = true,
                        ).fetchSemanticsNodes().size,
                    )
                }
            }
        }
    }

    /** An attachment still uploading occupies Send's slot, but must not make voice input disappear. */
    @Test
    fun uploadOnlyDraftKeepsMicReachable() = baseline(
        seed = {
            receiveForTest(live())
            pendingFiles += PendingFile(
                id = 9L, name = "trace.log", size = 2048, bytes = ByteArray(0),
                mediaType = "text/plain", state = FileUpState.Uploading,
            )
        },
        width = 320,
    ) {
        assertFullTarget(str(Res.string.dictate), viewportWidth = 320, minimum = 48f)
        assertEquals(0, controlCount(str(Res.string.send)), "uploading still blocks Send until the path lands")
        assertTrue(present(str(Res.string.composer_uploading, 1, 1)))
    }

    /** Failed voice invokes retryVoice; assistive tech must not announce it as a fresh Dictate action. */
    @Test
    fun failedVoiceActionSpeaksRetryInsteadOfDictate() = baseline(
        seed = {
            receiveForTest(live())
            // Failed is the public composer state for both a retained-audio transcription retry and
            // a record-again fallback; retryVoice selects the retained capture when one exists.
            voice.value = VoiceState.Failed(Res.string.voice_transcribe_failed)
        },
        width = 320,
    ) {
        onAllNodes(hasSetTextAction()).onFirst().performTextInput("keep this draft")
        waitForIdle()
        assertFullTarget(str(Res.string.retry_voice_input), viewportWidth = 320, minimum = 48f)
        assertEquals(0, controlCount(str(Res.string.dictate)), "the failed action no longer masquerades as Dictate")
        assertEquals(1, controlCount(str(Res.string.send)), "retry remains independent from the staged draft's Send")
    }

    /** Failure, upload and streaming may overlap in data, but the composer owns exactly one announcement slot. */
    @Test
    fun overlappingComposerStatesStillRenderOnePriorityRibbon() = baseline(
        seed = {
            receiveForTest(live(executing = true))
            voice.value = VoiceState.Failed(Res.string.voice_transcribe_failed)
            pendingFiles += PendingFile(
                id = 10L, name = "trace.log", size = 2048, bytes = ByteArray(0),
                mediaType = "text/plain", state = FileUpState.Uploading,
            )
        },
        width = 320,
    ) {
        val failure = str(Res.string.voice_transcribe_failed)
        assertEquals(
            1,
            onAllNodes(hasText(failure) and politeLiveRegion).fetchSemanticsNodes().size,
            "voice failure is the one actionable state ribbon",
        )
        assertEquals(
            1,
            onAllNodes(politeLiveRegion).fetchSemanticsNodes().size,
            "upload/queue feedback must not create competing live regions",
        )
        assertFalse(present(str(Res.string.composer_uploading, 1, 1)), "failure outranks upload feedback")
        assertFalse(present(str(Res.string.message_queued_hint)), "failure outranks the queue note")
        assertEquals(1, controlCount(str(Res.string.retry_voice_input)))
        assertEquals(1, controlCount(str(Res.string.stop)))
        assertEquals(0, controlCount(str(Res.string.send)))
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
        assertLaneAction(str(Res.string.stop))
        assertLaneAction(str(Res.string.send))
        assertFullTarget(str(Res.string.dictate))
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
