package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import dev.ccpocket.app.advanceFrameAndWait
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.attach_menu
import dev.ccpocket.app.resources.dictate
import dev.ccpocket.app.resources.qa_context_gauge
import dev.ccpocket.app.resources.switcher_open
import dev.ccpocket.app.resources.chat_copy_message
import dev.ccpocket.app.resources.chat_copy_message_done
import dev.ccpocket.app.resources.chat_copy_message_failed
import dev.ccpocket.app.resources.chat_context_collapsed
import dev.ccpocket.app.resources.chat_context_expanded
import dev.ccpocket.app.resources.chat_session_info
import dev.ccpocket.app.resources.chat_tool_failed
import dev.ccpocket.app.resources.code_copy
import dev.ccpocket.app.resources.message_queued_hint
import dev.ccpocket.app.resources.qa_model
import dev.ccpocket.app.resources.stop
import dev.ccpocket.app.resources.tool_process_failed
import dev.ccpocket.app.resources.tool_process_latest
import dev.ccpocket.app.resources.tool_process_unknown
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.chat.CHAT_CONTEXT_PANEL_TAG
import dev.ccpocket.app.ui.chat.CHAT_STREAM_TAG
import dev.ccpocket.app.ui.chat.TOOL_PROCESS_GROUP_TAG
import dev.ccpocket.app.ui.chat.TOOL_PROCESS_LIVE_TAG
import dev.ccpocket.app.ui.chat.TURN_COPY_TAG
import dev.ccpocket.app.ui.chat.TURN_SOURCE_ROW_TAG
import dev.ccpocket.app.ui.chat.USER_TURN_CONTAINER_TAG
import dev.ccpocket.protocol.ActiveSession
import dev.ccpocket.protocol.DirectoryEntry
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.StreamPiece
import dev.ccpocket.protocol.ToolEvent
import dev.ccpocket.protocol.ToolPhase
import dev.ccpocket.protocol.TurnDone
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * Chat Rhythm v1 on the phone chat screen (docs/design/claude-design-handoff/chat-rhythm-v1): the whole-turn copy
 * on the source row, its touch square kept off the body and off the row above, the three copy scopes kept apart,
 * the header's context as an overlay, and the 320pt / 1.6× composer keeping Stop until the turn really ends.
 *
 * Real [PocketRepository] fed real wire frames in a real phone-sized scene; `Density(1f, fontScale)` makes one scene
 * pixel one dp. Set CHAT_RHYTHM_OUT to keep review PNGs (the repository never tracks them).
 */
@OptIn(ExperimentalTestApi::class)
class ChatRhythmUiTest {

    private val convo = "c-rhythm"

    private fun account() = PairedDaemon(
        relay = "wss://test.invalid", accountId = "acct-rhythm", daemonPub = "pub",
        deviceId = "dev", credential = "cred", hostName = "alex-macbook",
    )

    private fun live(
        executing: Boolean = false,
        agent: AgentKind = AgentKind.CLAUDE,
        model: String = "claude-fable-5",
        contextUsed: Long = 84_000,
    ) = SessionLive(
        convoId = convo, workdir = "/Users/alex/code/cc-pocket", sessionId = "s1", mode = PermissionMode.DEFAULT,
        executing = executing, model = model, agent = agent, contextUsed = contextUsed,
    )

    /** [n] other projects each with a running session — what gives the composer's switcher its count. */
    private fun PocketRepository.otherRunningSessions(n: Int) = repeat(n) { i ->
        directories.add(
            DirectoryEntry(
                path = "/Users/alex/code/p$i", name = "p$i", isDir = true, open = true,
                activeSessions = listOf(ActiveSession(sessionId = "s-p$i", title = "Task $i", executing = true)),
                activeSessionId = "s-p$i", activeSessionTitle = "Task $i",
            ),
        )
    }

    private fun SkikoComposeUiTest.control(label: String) = onAllNodes(hasContentDescription(label) and hasClickAction())

    private fun SkikoComposeUiTest.textLayout(matcher: SemanticsMatcher): TextLayoutResult =
        mutableListOf<TextLayoutResult>().also {
            onAllNodes(matcher, useUnmergedTree = true).onFirst().fetchSemanticsNode()
                .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(it)
        }.first()

    /** The colour drawn [dx] into a control's box at its vertical centre — the flat controls' "no resting fill". */
    private fun SkikoComposeUiTest.pixelInside(label: String, dx: Float = 4f): Int {
        val b = control(label).onFirst().getUnclippedBoundsInRoot()
        return onRoot().captureToImage().toPixelMap()[(b.left.value + dx).toInt(), ((b.top.value + b.bottom.value) / 2f).toInt()].toArgb()
    }

    private fun u(t: String) = HistoryMessage(ChatRole.USER, t)
    private fun a(t: String) = HistoryMessage(ChatRole.ASSISTANT, t)
    private fun tool(cmd: String, ok: Boolean? = true) = HistoryMessage(ChatRole.TOOL, cmd, tool = "Bash", ok = ok)

    /** Records what the app wrote; [fail] makes the write throw, the way a platform clipboard can. */
    @Suppress("DEPRECATION")
    private class Clipboard(var fail: Boolean = false) : ClipboardManager {
        var copied: AnnotatedString? = null
        override fun getText() = copied
        override fun setText(annotatedString: AnnotatedString) {
            if (fail) error("clipboard unavailable")
            copied = annotatedString
        }
    }

    private fun scene(
        width: Int = W,
        fontScale: Float = 1f,
        dark: Boolean = true,
        clipboard: Clipboard = Clipboard(),
        seed: PocketRepository.() -> Unit,
        assertions: SkikoComposeUiTest.(PocketRepository) -> Unit,
    ) = runDesktopComposeUiTest(width, H) {
        mainClock.autoAdvance = false // a streaming scene animates forever; assertions read settled frames
        lateinit var mounted: PocketRepository
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale), LocalClipboardManager provides clipboard) {
                val scope = rememberCoroutineScope()
                val repo = remember { PocketRepository(scope, account()).apply(seed) }
                mounted = repo
                PocketTheme(dark = dark) { Box(Modifier.fillMaxSize().background(Tok.base)) { ChatScreen(repo) } }
            }
        }
        advanceFrameAndWait()
        advanceFrameAndWait()
        assertions(mounted)
    }

    private fun SkikoComposeUiTest.save(name: String) {
        val dir = System.getenv("CHAT_RHYTHM_OUT")?.let(::File) ?: return
        dir.mkdirs()
        val image = Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap())
        File(dir, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }

    private fun SkikoComposeUiTest.copyTargets(): List<DpRect> =
        onAllNodes(hasTestTag(TURN_COPY_TAG)).fetchSemanticsNodes().indices.map {
            onAllNodes(hasTestTag(TURN_COPY_TAG))[it].getUnclippedBoundsInRoot()
        }

    private fun SkikoComposeUiTest.bounds(text: String, substring: Boolean = true) =
        onAllNodes(hasText(text, substring = substring)).onFirst().getUnclippedBoundsInRoot()

    private fun SkikoComposeUiTest.described(label: String) =
        onAllNodes(hasContentDescription(label)).fetchSemanticsNodes().size

    // ══ whole-turn copy: scope and honesty ═════════════════════════════════════════════════════════════════

    /**
     * Three copies, three different outputs, no merging: the source row copies the reply's raw text WITH its
     * Markdown, the quote's glyph copies the quote as plain text, the code block copies the code. And no 复制 line
     * is left under the body — the only text "copy" on screen is the code block's own.
     */
    @Test
    fun theSourceRowCopiesTheRawTurnWhileQuoteAndCodeKeepTheirOwnScopes() {
        val clipboard = Clipboard()
        scene(clipboard = clipboard, seed = {
            receiveForTest(live())
            receiveForTest(ConvoHistory(convo, listOf(u("修一下重试次数"), a(REPLY))))
        }) {
            assertEquals(2, copyTargets().size, "one whole-turn copy per turn: the prompt and the reply")
            assertEquals(1, onAllNodes(hasText(str(Res.string.code_copy))).fetchSemanticsNodes().size, "no 复制 line under a turn; only the code block's")

            onAllNodes(hasTestTag(TURN_COPY_TAG))[1].performClick()
            advanceFrameAndWait()
            assertEquals(REPLY, clipboard.copied?.text, "the reply's source row copies the original text, Markdown included")
            assertEquals(1, described(str(Res.string.chat_copy_message_done)), "…and says so in place")

            onNodeWithTag(QUOTE_COPY_TAG).performClick()
            advanceFrameAndWait()
            val quote = clipboard.copied?.text.orEmpty()
            assertTrue("retry" in quote && "3" in quote, "the quote glyph copies the quote: \"$quote\"")
            assertFalse(quote.contains("**") || quote.contains(">") || quote.contains("`"), "…as plain text: \"$quote\"")

            onAllNodes(hasText(str(Res.string.code_copy))).onFirst().performClick()
            advanceFrameAndWait()
            assertEquals("val retries = 3", clipboard.copied?.text, "the code block copies its code only")

            onAllNodes(hasTestTag(TURN_COPY_TAG))[0].performClick()
            advanceFrameAndWait()
            assertEquals("修一下重试次数", clipboard.copied?.text, "the prompt's row copies the prompt")
        }
    }

    /** Mid-stream the row copies everything received so far — at tap time, not what the row composed with. */
    @Test
    fun aStreamingReplyCopiesEverythingReceivedSoFar() {
        val clipboard = Clipboard()
        scene(clipboard = clipboard, seed = {
            receiveForTest(live())
            receiveForTest(ConvoHistory(convo, listOf(u("说明一下")), lastSeq = 1))
            receiveForTest(live(executing = true))
        }) { repo ->
            repo.receiveForTest(AssistantChunk(convo, 10, StreamPiece.Text("第一段：**已定位**。")))
            advanceFrameAndWait()
            onAllNodes(hasTestTag(TURN_COPY_TAG)).onLast().performClick()
            advanceFrameAndWait()
            assertEquals("第一段：**已定位**。", clipboard.copied?.text)

            repo.receiveForTest(AssistantChunk(convo, 11, StreamPiece.Text("\n\n第二段：修复中。")))
            advanceFrameAndWait()
            onAllNodes(hasTestTag(TURN_COPY_TAG)).onLast().performClick()
            advanceFrameAndWait()
            assertEquals("第一段：**已定位**。\n\n第二段：修复中。", clipboard.copied?.text, "the copy grows with the stream")
        }
    }

    /** A clipboard write that throws must read as a failure — never as the ✓ of a copy that did not happen. */
    @Test
    fun aFailedClipboardWriteNeverShowsSuccess() {
        val clipboard = Clipboard(fail = true)
        scene(clipboard = clipboard, seed = {
            receiveForTest(live())
            receiveForTest(ConvoHistory(convo, listOf(u("hello"), a("world"))))
        }) {
            onAllNodes(hasTestTag(TURN_COPY_TAG)).onLast().performClick()
            advanceFrameAndWait()
            assertEquals(0, described(str(Res.string.chat_copy_message_done)), "no success claim")
            assertEquals(1, described(str(Res.string.chat_copy_message_failed)), "the failure is said in place")
            mainClock.advanceTimeBy(2_000)
            advanceFrameAndWait()
            assertEquals(2, described(str(Res.string.chat_copy_message)), "…and the glyph returns to rest")
        }
    }

    // ══ touch geometry ═════════════════════════════════════════════════════════════════════════════════════

    /**
     * The copy square is a real 44 × 44 target, it rises out of the 28dp row instead of reaching down — so it
     * ends above the body's first line — and the gap above the row is wide enough that it does not reach the
     * tool fold above it either. Proven with real touches, not just bounds: a tap in the square's risen part
     * copies, a tap on the body's first line does not.
     */
    @Test
    fun theCopyTargetIsFullSizeAndStaysOffTheBodyAndTheRowAbove() {
        listOf(1f, 1.6f).forEach { scale ->
            val clipboard = Clipboard()
            scene(fontScale = scale, clipboard = clipboard, seed = {
                receiveForTest(live())
                receiveForTest(ConvoHistory(convo, listOf(u("看看测试"), tool("./gradlew test"), tool("git status"), a(BODY))))
            }) {
                val fold = onNodeWithTag(TOOL_PROCESS_GROUP_TAG).getUnclippedBoundsInRoot()
                val row = onAllNodes(hasTestTag(TURN_SOURCE_ROW_TAG)).onLast().getUnclippedBoundsInRoot()
                val target = copyTargets().last()
                val body = bounds(BODY.take(12))
                assertTrue(target.width.value >= 43.5f && target.height.value >= 43.5f, "${scale}×: the target is 44 × 44: $target")
                assertTrue(target.bottom <= row.bottom + (0.5f).toDp(), "${scale}×: the square ends on the source row: $target / $row")
                assertTrue((body.top - target.bottom).value >= 1.5f, "${scale}×: …2dp clear of the body's first line: $target / $body")
                assertTrue(target.top >= fold.bottom, "${scale}×: …and clear of the fold above: $target / $fold")
                assertTrue(target.right.value <= W + 0.5f, "${scale}×: …inside the viewport: $target")

                // a touch in the square's upper half — ABOVE the 28dp row — still reaches the copy
                onAllNodes(hasTestTag(TURN_COPY_TAG)).onLast().performTouchInput { click(Offset(centerX, 3f)) }
                advanceFrameAndWait()
                assertEquals(BODY, clipboard.copied?.text, "${scale}×: the risen part of the square is live")

                // …while a touch on the body's first line, right under the glyph, is the text's, not the copy's
                clipboard.copied = null
                mainClock.advanceTimeBy(2_000)
                advanceFrameAndWait()
                onAllNodes(hasText(BODY.take(12), substring = true)).onFirst().performTouchInput {
                    click(Offset(width - 12f, 3f))
                }
                advanceFrameAndWait()
                assertEquals(null, clipboard.copied, "${scale}×: the body's first line never triggers the copy")
            }
        }
    }

    /** The rhythm between turns: 24dp where the speaker changes, 16dp above a copy-bearing row inside a turn. */
    @Test
    fun turnsStepTwentyFourAcrossSpeakersAndSixteenAboveACopyRowWithinATurn() = scene(seed = {
        receiveForTest(live())
        receiveForTest(ConvoHistory(convo, listOf(u("看看测试"), tool("./gradlew test"), tool("git status"), a("测试通过。"))))
    }) {
        val user = onNodeWithTag(USER_TURN_CONTAINER_TAG).getUnclippedBoundsInRoot()
        val fold = onNodeWithTag(TOOL_PROCESS_GROUP_TAG).getUnclippedBoundsInRoot()
        val agentRow = onAllNodes(hasTestTag(TURN_SOURCE_ROW_TAG)).onLast().getUnclippedBoundsInRoot()
        assertEquals(24f, (fold.top - user.bottom).value, 0.5f, "user → agent side: 24dp")
        assertEquals(16f, (agentRow.top - fold.bottom).value, 0.5f, "fold → the reply's source row within the turn")
        save("phone-402-rhythm")
    }

    // ══ header overlay ═════════════════════════════════════════════════════════════════════════════════════

    /** Expanding the context covers the stream's top instead of shrinking it, and Session info stays reachable. */
    @Test
    fun theExpandedContextOverlaysTheStreamWithoutShrinkingIt() = scene(width = 320, fontScale = 1.6f, seed = {
        receiveForTest(live())
        receiveForTest(ConvoHistory(convo, listOf(u("看看测试"), a(BODY))))
    }) {
        val before = onNodeWithTag(CHAT_STREAM_TAG).getBoundsInRoot()
        onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, str(Res.string.chat_context_collapsed))).onFirst().performClick()
        advanceFrameAndWait()
        assertEquals(1, onAllNodes(hasTestTag(CHAT_CONTEXT_PANEL_TAG)).fetchSemanticsNodes().size, "the panel is open")
        val after = onNodeWithTag(CHAT_STREAM_TAG).getBoundsInRoot()
        // (it may even grow: the two-line collapsed summary gives way to the one-line CONTEXT label)
        assertEquals(before.bottom, after.bottom, "the stream still reaches the composer")
        assertTrue(after.top <= before.top, "the stream is not pushed down under the open panel: $before → $after")
        val info = onAllNodes(hasText(str(Res.string.chat_session_info))).onFirst()
        assertEquals(info.getUnclippedBoundsInRoot(), info.getBoundsInRoot(), "Session info is wholly on screen")
        assertTrue(present("claude-fable-5", substring = true) || present("fable", substring = true), "expanded, the model is stated")
        save("phone-320x1.6-context-open")
        onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, str(Res.string.chat_context_expanded))).onFirst().performClick()
        advanceFrameAndWait()
        assertEquals(0, onAllNodes(hasTestTag(CHAT_CONTEXT_PANEL_TAG)).fetchSemanticsNodes().size, "collapsing closes it")
    }

    // ══ 320pt × 1.6: fold, live line, composer ═════════════════════════════════════════════════════════════

    /**
     * The tightest phone: the fold's failed and not-returned stay separate and on screen, the live line names its
     * finished step as the LATEST one, the long model name truncates in its chip, and Stop is whole on screen —
     * then, once the turn really ends, the live line, the running note and Stop go while the fold's markers stay.
     */
    @Test
    fun atThreeTwentyAndLargeTypeStopStaysVisibleUntilTheTurnEnds() = scene(width = 320, fontScale = 1.6f, seed = {
        receiveForTest(live(agent = AgentKind.CODEX, model = "gpt-6-astra-preview-long-context-2026-09-01"))
        receiveForTest(ConvoHistory(convo, listOf(u("跑一下测试")), lastSeq = 1))
        receiveForTest(live(executing = true, agent = AgentKind.CODEX, model = "gpt-6-astra-preview-long-context-2026-09-01"))
    }) { repo ->
        var seq = 20L
        fun frame(f: dev.ccpocket.protocol.Frame) { runOnUiThread { repo.receiveForTest(f) }; advanceFrameAndWait() }
        frame(ToolEvent(convo, seq++, ToolPhase.START, "Bash", inputPreview = "pnpm test", toolUseId = "t1"))
        frame(ToolEvent(convo, seq++, ToolPhase.RESULT, "Bash", ok = false, toolUseId = "t1", outcomeOnly = true))
        frame(ToolEvent(convo, seq++, ToolPhase.START, "Read", inputPreview = "~/code/a.ts", toolUseId = "t2"))
        frame(ToolEvent(convo, seq++, ToolPhase.RESULT, "Read", ok = true, toolUseId = "t2", outcomeOnly = true))
        frame(ToolEvent(convo, seq++, ToolPhase.START, "Bash", inputPreview = "pnpm lint", toolUseId = "t3"))
        frame(ToolEvent(convo, seq++, ToolPhase.RESULT, "Bash", ok = false, toolUseId = "t3", outcomeOnly = true))

        // the live line holds the latest step, labelled as the latest — its 失败 is that step's
        val live = onNodeWithTag(TOOL_PROCESS_LIVE_TAG)
        assertTrue(present(str(Res.string.tool_process_latest)), "the finished step is called the latest")
        assertTrue(present(str(Res.string.chat_tool_failed)), "…with its own outcome")
        val liveBox = live.getUnclippedBoundsInRoot()
        assertTrue(liveBox.height.value >= 35.5f, "the live line is at least 36dp: $liveBox")

        val failed = bounds(pluralText(Res.plurals.tool_process_failed, 2))
        assertTrue(failed.right.value <= 320.5f, "the failed count is whole on screen: $failed")

        val stop = onAllNodes(hasContentDescription(str(Res.string.stop)) and hasClickAction()).onFirst()
        assertEquals(stop.getUnclippedBoundsInRoot(), stop.getBoundsInRoot(), "Stop is wholly on screen")
        val stopBox = stop.getUnclippedBoundsInRoot()
        assertTrue(stopBox.right.value <= 320.5f, "…inside the 320pt viewport")
        // wrapped below the leading controls, a LONE Stop stays a compact button at the trailing edge — not a bar
        // across the row — and keeps its large-type target
        assertEquals(320f - 16f - 4f, stopBox.right.value, 1f, "the lone Stop sits at the lane's trailing edge: $stopBox")
        assertTrue(stopBox.width.value < 160f, "…as a compact button, not a full-width bar: $stopBox")
        assertTrue(stopBox.height.value >= 57.5f && stopBox.width.value >= 83.5f, "…with its large-type target: $stopBox")
        assertTrue(present(str(Res.string.message_queued_hint)), "the running note is written once")

        val chipText = onAllNodes(hasAnyAncestor(hasContentDescription(str(Res.string.qa_model))) and SemanticsMatcher("has text layout") {
            it.config.contains(SemanticsActions.GetTextLayoutResult)
        }, useUnmergedTree = true).onFirst()
        val layout = mutableListOf<TextLayoutResult>().also {
            chipText.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(it)
        }.first()
        assertTrue(layout.isLineEllipsized(0) || layout.hasVisualOverflow, "the long model name truncates inside its chip")
        save("phone-320x1.6-running")

        frame(TurnDone(convo))
        assertEquals(0, onAllNodes(hasTestTag(TOOL_PROCESS_LIVE_TAG)).fetchSemanticsNodes().size, "the live line ends with the turn")
        assertEquals(0, onAllNodes(hasContentDescription(str(Res.string.stop)) and hasClickAction()).fetchSemanticsNodes().size, "Stop goes")
        assertFalse(present(str(Res.string.message_queued_hint)), "…and so does the running note")
        assertTrue(present(pluralText(Res.plurals.tool_process_failed, 2)), "the fold still counts its failures")
        assertFalse(present(pluralText(Res.plurals.tool_process_unknown, 1)), "no not-returned invented at the end")
        // re-enabled after the turn, the flat chip is still just its label on the container
        assertEquals(Tok.surface.toArgb(), pixelInside(str(Res.string.qa_model)), "the model control has no resting pill")
        save("phone-320x1.6-ended")
    }

    /**
     * The FIRST row of a transcript: its copy square rises 12–16dp above the source row, into the list's top gutter.
     * That gutter is scrollable content padding, so the whole 44dp square is inside the list's clip and live to a
     * touch at its very top edge — for a lone agent reply and for a lone prompt.
     */
    @Test
    fun theFirstTurnsCopyTargetIsWhollyTouchableAtTheTopOfTheList() {
        listOf(listOf(a(BODY)) to BODY, listOf(u(BODY)) to BODY).forEachIndexed { i, (history, text) ->
            val clipboard = Clipboard()
            scene(clipboard = clipboard, seed = {
                receiveForTest(live())
                receiveForTest(ConvoHistory(convo, history))
            }) {
                val who = if (i == 0) "agent" else "user"
                val node = onAllNodes(hasTestTag(TURN_COPY_TAG)).onFirst()
                val whole = node.getUnclippedBoundsInRoot()
                assertTrue(whole.width.value >= 43.5f && whole.height.value >= 43.5f, "$who: a 44dp square: $whole")
                assertEquals(whole, node.getBoundsInRoot(), "$who: no part of the first row's square is clipped by the list")
                val stream = onNodeWithTag(CHAT_STREAM_TAG).getBoundsInRoot()
                assertTrue(whole.top >= stream.top, "$who: inside the list's scroll viewport: $whole / $stream")
                val body = bounds(text.take(12))
                assertTrue((body.top - whole.bottom).value >= 1.5f, "$who: still clear of the body's first line: $whole / $body")
                node.performTouchInput { click(Offset(centerX, 1f)) }
                advanceFrameAndWait()
                assertEquals(text, clipboard.copied?.text, "$who: a touch at the square's top edge copies")
                // …and at its top-trailing corner, the part that reaches past the text column
                clipboard.copied = null
                mainClock.advanceTimeBy(2_000)
                advanceFrameAndWait()
                node.performTouchInput { click(Offset(width - 1f, 1f)) }
                advanceFrameAndWait()
                assertEquals(text, clipboard.copied?.text, "$who: a touch at the square's top-trailing corner copies")
            }
        }
    }

    /**
     * The standard phone with EVERY lane control present — attach, a short model, the switcher at 9+, an unsized
     * context (~338k) and Stop mid-turn — keeps the design's two rows: the field, then ONE tool row. The flat
     * controls draw no resting pill, and the collapsed header facts take a single line.
     */
    @Test
    fun theStandardPhoneKeepsOneToolRowWithEveryControl() {
        listOf(true, false).forEach { dark ->
            scene(dark = dark, seed = {
                receiveForTest(live(agent = AgentKind.CODEX, model = "gpt-6-astra", contextUsed = 338_000))
                receiveForTest(ConvoHistory(convo, listOf(u("修一下重试次数"), tool("./gradlew test"), tool("git status", ok = false), a(REPLY)), lastSeq = 4))
                receiveForTest(live(executing = true, agent = AgentKind.CODEX, model = "gpt-6-astra", contextUsed = 338_000))
                otherRunningSessions(10)
            }) {
                val labels = listOf(Res.string.attach_menu, Res.string.qa_model, Res.string.switcher_open, Res.string.qa_context_gauge, Res.string.stop)
                val boxes = labels.associateWith { res ->
                    assertEquals(1, control(str(res)).fetchSemanticsNodes().size, "one ${str(res)}")
                    control(str(res)).onFirst().getUnclippedBoundsInRoot().also {
                        assertTrue(it.width.value >= 47.5f && it.height.value >= 47.5f, "${str(res)} keeps its 48dp target: $it")
                        assertTrue(it.left.value >= 0f && it.right.value <= W + 0.5f, "${str(res)} stays on screen: $it")
                    }
                }
                val row = boxes.getValue(Res.string.stop).let { (it.top + it.bottom).value / 2f }
                boxes.forEach { (res, b) ->
                    assertEquals(row, (b.top + b.bottom).value / 2f, 1f, "${str(res)} shares the ONE tool row: $b")
                }
                val mic = control(str(Res.string.dictate)).onFirst().getUnclippedBoundsInRoot()
                assertTrue(mic.bottom <= boxes.getValue(Res.string.attach_menu).top, "the field is its own row above the tools")
                assertTrue(present("~338k"), "the context number is shown")
                assertTrue(present("9+"), "the switcher counts 9+")
                val model = textLayout(hasAnyAncestor(hasContentDescription(str(Res.string.qa_model))) and hasText("gpt-6-astra"))
                assertFalse(model.isLineEllipsized(0), "the short model name reads whole on the standard phone")
                listOf(Res.string.qa_model, Res.string.switcher_open, Res.string.qa_context_gauge).forEach {
                    assertEquals(Tok.surface.toArgb(), pixelInside(str(it), dx = 3f), "${str(it)} draws no resting pill")
                }
                val header = textLayout(hasText("alex-macbook", substring = true))
                assertEquals(1, header.lineCount, "the collapsed header facts take one line")
                save(if (dark) "phone-402-full-controls-dark" else "phone-402-full-controls-light")
            }
        }
    }

    /** Light theme review frame: same content as the rhythm scene, for the handoff's light counterpart. */
    @Test
    fun lightThemeRendersTheSameRhythm() = scene(dark = false, seed = {
        receiveForTest(live())
        receiveForTest(ConvoHistory(convo, listOf(u("修一下重试次数"), tool("./gradlew test"), tool("git status", ok = false), a(REPLY))))
    }) {
        assertEquals(2, copyTargets().size)
        assertNotEquals(0, onAllNodes(hasTestTag(TOOL_PROCESS_GROUP_TAG)).fetchSemanticsNodes().size)
        save("phone-402-light")
    }

    private fun pluralText(res: org.jetbrains.compose.resources.PluralStringResource, n: Int): String =
        kotlinx.coroutines.runBlocking { org.jetbrains.compose.resources.getPluralString(res, n, n) }

    private fun Float.toDp() = androidx.compose.ui.unit.Dp(this)

    private companion object {
        const val W = 402
        const val H = 874

        const val REPLY = "**结论**：已修复重试。\n\n> 请把 `retry` 改成 **3** 次\n\n```kotlin\nval retries = 3\n```\n\n完成。"

        const val BODY = "测试全部通过，覆盖率与上次一致。The retry change is covered by two new cases in RelayBackoffTest."
    }
}
