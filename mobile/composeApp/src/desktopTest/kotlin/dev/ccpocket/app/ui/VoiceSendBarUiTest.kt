package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import dev.ccpocket.app.advanceFrameAndWait
import dev.ccpocket.app.data.VoiceBarMode
import dev.ccpocket.app.data.VoiceComposerReason
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.cancel_recording
import dev.ccpocket.app.resources.done
import dev.ccpocket.app.resources.transcribing
import dev.ccpocket.app.resources.voice_edit_instead
import dev.ccpocket.app.resources.voice_finish_edit
import dev.ccpocket.app.resources.voice_finish_send
import dev.ccpocket.app.resources.voice_reason_disconnected
import dev.ccpocket.app.resources.voice_reason_not_adopted
import dev.ccpocket.app.resources.voice_reason_not_sent
import dev.ccpocket.app.resources.voice_reason_review
import dev.ccpocket.app.resources.voice_reason_timeout
import dev.ccpocket.app.resources.voice_reason_unavailable
import dev.ccpocket.app.resources.voice_refining
import dev.ccpocket.app.resources.voice_sending
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Voice input v2's recording bar by mode (README "后续决定", review §11): today's bar unchanged, the send bar's
 * keyboard and send arrow, the arrow falling to ✓ in place, the waiting and preview stages, and the reason lines.
 * The bar is rendered on its own here; [VoiceComposerHooksUiTest] drives it from a real repository in the chat.
 */
@OptIn(ExperimentalTestApi::class)
class VoiceSendBarUiTest {

    private val politeLiveRegion = SemanticsMatcher("polite live region") {
        it.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite
    }

    private fun scene(
        width: Int = 402,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
        assertions: SkikoComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(width, 400) {
        mainClock.autoAdvance = false // the waveform and spinners animate forever
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                PocketTheme { Box(Modifier.fillMaxSize()) { Column(Modifier.fillMaxWidth()) { content() } } }
            }
        }
        advanceFrameAndWait()
        mainClock.advanceTimeBy(300) // past the bar's 220 ms morph-in, so bounds are where they settle
        advanceFrameAndWait()
        assertions()
    }

    private fun SkikoComposeUiTest.control(label: String) = onAllNodes(hasContentDescription(label) and hasClickAction())
    private fun SkikoComposeUiTest.controlCount(label: String) = control(label).fetchSemanticsNodes().size
    private fun SkikoComposeUiTest.named(label: String) = onAllNodes(hasContentDescription(label)).fetchSemanticsNodes().size

    /** Every callback a bar can fire, counted. */
    private class Taps {
        var cancel = 0; var done = 0; var edit = 0; var send = 0
        val all get() = listOf(cancel, done, edit, send)
    }

    @Composable
    private fun Bar(
        taps: Taps,
        mode: VoiceBarMode,
        transcribing: Boolean = false,
        wait: VoiceBarWait? = null,
    ) = RecordingBar(
        elapsedMs = 7_250, transcribing = transcribing, levels = listOf(0.2f, 0.6f, 0.4f),
        onCancel = { taps.cancel++ }, onDone = { taps.done++ }, mode = mode,
        onFinishEdit = { taps.edit++ }, onFinishSend = { taps.send++ }, wait = wait,
    )

    // ── the three bars while recording ──────────────────────────────────────────────────────────────

    @Test
    fun todaysBarIsTodaysItemForItem() {
        val taps = Taps()
        scene(content = { Bar(taps, VoiceBarMode.LEGACY) }) {
            assertEquals(1, controlCount(str(Res.string.cancel_recording)))
            assertEquals(1, controlCount(str(Res.string.done)))
            listOf(Res.string.voice_finish_edit, Res.string.voice_finish_send, Res.string.voice_edit_instead).forEach {
                assertEquals(0, named(str(it)), "today's bar has no ${str(it)}")
            }
            control(str(Res.string.cancel_recording)).onFirst().performClick()
            control(str(Res.string.done)).onFirst().performClick()
            assertEquals(listOf(1, 1, 0, 0), taps.all, "✕ cancels, ✓ is done")
        }
    }

    @Test
    fun todaysBarKeepsItsCheckLiveThroughTranscription() {
        val taps = Taps()
        scene(content = { Bar(taps, VoiceBarMode.LEGACY, transcribing = true) }) {
            control(str(Res.string.done)).onFirst().assertIsEnabled()
            assertEquals(1, controlCount(str(Res.string.cancel_recording)))
            assertTrue(present(str(Res.string.transcribing)))
        }
    }

    @Test
    fun theSendBarTradesCancelForTheKeyboardAndCheckForTheSendArrow() {
        val taps = Taps()
        scene(content = { Bar(taps, VoiceBarMode.EDIT_SEND) }) {
            assertEquals(1, controlCount(str(Res.string.voice_finish_edit)))
            assertEquals(1, controlCount(str(Res.string.voice_finish_send)))
            assertEquals(0, named(str(Res.string.cancel_recording)), "nothing on a send bar discards")
            assertEquals(0, named(str(Res.string.done)))
            control(str(Res.string.voice_finish_edit)).onFirst().performClick()
            control(str(Res.string.voice_finish_send)).onFirst().performClick()
            assertEquals(listOf(0, 0, 1, 1), taps.all, "keyboard → finish and edit, arrow → finish and send")
        }
    }

    @Test
    fun aSendBarThatCannotSendKeepsTodaysCheck() {
        val taps = Taps()
        scene(content = { Bar(taps, VoiceBarMode.EDIT_DONE) }) {
            assertEquals(1, controlCount(str(Res.string.voice_finish_edit)))
            assertEquals(1, controlCount(str(Res.string.done)))
            assertEquals(0, named(str(Res.string.voice_finish_send)))
            assertEquals(0, named(str(Res.string.cancel_recording)))
            control(str(Res.string.done)).onFirst().performClick()
            assertEquals(listOf(0, 1, 0, 0), taps.all, "✓ is done (stopVoice), exactly as today's")
        }
    }

    @Test
    fun theArrowFallsToTheCheckInPlace() {
        val mode = mutableStateOf(VoiceBarMode.EDIT_SEND)
        val taps = Taps()
        scene(content = { Bar(taps, mode.value) }) {
            val arrow = control(str(Res.string.voice_finish_send)).onFirst().getUnclippedBoundsInRoot()
            runOnIdle { mode.value = VoiceBarMode.EDIT_DONE } // the capture can no longer send
            advanceFrameAndWait()
            assertEquals(0, named(str(Res.string.voice_finish_send)))
            assertEquals(arrow, control(str(Res.string.done)).onFirst().getUnclippedBoundsInRoot(), "✓ takes the arrow's own slot")
            assertEquals(1, controlCount(str(Res.string.voice_finish_edit)), "the keyboard stays")
        }
    }

    // ── after ✓ ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun transcribingRestsTheTrailingControlAndKeepsTheKeyboard() {
        listOf(VoiceBarMode.EDIT_SEND to Res.string.voice_finish_send, VoiceBarMode.EDIT_DONE to Res.string.done).forEach { (mode, trailing) ->
            val taps = Taps()
            scene(content = { Bar(taps, mode, transcribing = true) }) {
                control(str(trailing)).onFirst().assertIsNotEnabled()
                assertEquals(0, named(str(Res.string.voice_finish_edit)), "$mode: the recording is over")
                control(str(Res.string.voice_edit_instead)).onFirst().assertIsEnabled().performClick()
                assertEquals(listOf(0, 0, 1, 0), taps.all, "$mode: edit instead still works")
                assertTrue(present(str(Res.string.transcribing)))
            }
        }
    }

    @Test
    fun correctingNamesTheAgentAndOffersOnlyEditInstead() {
        val taps = Taps()
        scene(content = { Bar(taps, VoiceBarMode.EDIT_SEND, wait = VoiceBarWait.Correcting(refinerDisplayName("claude"))) }) {
            val state = str(Res.string.voice_refining, "Claude")
            assertTrue(present(state), "the pill reads the agent's product name")
            listOf(Res.string.voice_finish_send, Res.string.done, Res.string.cancel_recording, Res.string.voice_finish_edit).forEach {
                assertEquals(0, named(str(it)), "nothing but edit-instead while it waits: ${str(it)}")
            }
            val ring = onAllNodes(hasTestTag(SEND_WAIT_RING_TAG), useUnmergedTree = true).fetchSemanticsNodes().single()
            assertFalse(hasClickAction().matches(ring), "the waiting ring is status, not a control")
            val live = onAllNodes(politeLiveRegion, useUnmergedTree = true).fetchSemanticsNodes()
            assertEquals(listOf(state), live.single().config.getOrNull(SemanticsProperties.Text)?.map { it.text }, "one polite state line")
            control(str(Res.string.voice_edit_instead)).onFirst().performClick()
            assertEquals(listOf(0, 0, 1, 0), taps.all)
        }
    }

    @Test
    fun theAgentNameIsItsProductName() {
        assertEquals("Claude", refinerDisplayName("claude"))
        assertEquals("Codex", refinerDisplayName("codex"))
        assertEquals("DeepSeek", refinerDisplayName("dsh"))
        assertEquals("future-agent", refinerDisplayName("future-agent"), "an unknown agent is shown as it came")
    }

    @Test
    fun sendingShowsTheHoldAndStillOffersEditInstead() {
        val taps = Taps()
        scene(content = { Bar(taps, VoiceBarMode.EDIT_SEND, wait = VoiceBarWait.Sending) }) {
            assertTrue(present(str(Res.string.voice_sending)))
            assertEquals(1, controlCount(str(Res.string.voice_edit_instead)))
            assertEquals(0, named(str(Res.string.voice_finish_send)))
            assertEquals(1, onAllNodes(hasTestTag(SEND_WAIT_RING_TAG), useUnmergedTree = true).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun thePreviewMarksExactlyTheCorrectedRanges() {
        val text = "请比较 Claude 和 Claude 的输出"
        val mark = SpanStyle(background = Color.Red)
        val styled = voicePreviewText(text, listOf(4 until 10, 40 until 45, IntRange.EMPTY), mark)
        assertEquals(text, styled.text)
        assertEquals(listOf(4 to 10), styled.spanStyles.map { it.start to it.end }, "a range that does not fit is skipped")

        scene(content = { VoiceTextPreview(voicePreviewText(text, listOf(4 until 10), voiceCorrectionMark())) }) {
            val node = onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().single()
            val shown = node.config[SemanticsProperties.Text].single()
            assertEquals(listOf(4 to 10), shown.spanStyles.map { it.start to it.end }, "only the second 'Claude' — the one that changed")
            assertTrue(shown.spanStyles.single().item.background != Color.Unspecified, "the correction carries a background tint")
        }
    }

    // ── the reason line ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun everyReasonHasItsOwnLine() {
        val expected = mapOf(
            VoiceComposerReason.TIMEOUT to Res.string.voice_reason_timeout,
            VoiceComposerReason.NOT_ADOPTED to Res.string.voice_reason_not_adopted,
            VoiceComposerReason.UNAVAILABLE to Res.string.voice_reason_unavailable,
            VoiceComposerReason.REVIEW to Res.string.voice_reason_review,
            VoiceComposerReason.DISCONNECTED to Res.string.voice_reason_disconnected,
            VoiceComposerReason.NOT_SENT to Res.string.voice_reason_not_sent,
        )
        assertEquals(VoiceComposerReason.entries.toSet(), expected.keys)
        VoiceComposerReason.entries.forEach { assertEquals(expected.getValue(it), voiceReasonRes(it), it.name) }
        val lines = VoiceComposerReason.entries.map { str(voiceReasonRes(it)) }
        assertEquals(lines.size, lines.toSet().size, "six distinct sentences")
        assertTrue(lines.none { it.isBlank() })
    }

    // ── 320 pt, 200 % type ──────────────────────────────────────────────────────────────────────────

    /**
     * The narrowest phone at double type: the two controls keep their 48 pt targets, stay on screen, never overlap
     * each other, and the pill's text stays between them — every bar, every stage.
     */
    @Test
    fun bothControlsStayApartAtTheNarrowestWidthAndDoubleType() {
        data class Case(val name: String, val mode: VoiceBarMode, val transcribing: Boolean, val wait: VoiceBarWait?, val leading: String, val trailing: String?)
        val cases = listOf(
            Case("today's", VoiceBarMode.LEGACY, false, null, str(Res.string.cancel_recording), str(Res.string.done)),
            Case("send bar", VoiceBarMode.EDIT_SEND, false, null, str(Res.string.voice_finish_edit), str(Res.string.voice_finish_send)),
            Case("send bar, cannot send", VoiceBarMode.EDIT_DONE, false, null, str(Res.string.voice_finish_edit), str(Res.string.done)),
            Case("transcribing", VoiceBarMode.EDIT_SEND, true, null, str(Res.string.voice_edit_instead), str(Res.string.voice_finish_send)),
            Case("correcting", VoiceBarMode.EDIT_SEND, false, VoiceBarWait.Correcting("Claude"), str(Res.string.voice_edit_instead), null),
            Case("sending", VoiceBarMode.EDIT_SEND, false, VoiceBarWait.Sending, str(Res.string.voice_edit_instead), null),
        )
        for (c in cases) {
            val taps = Taps()
            scene(width = 320, fontScale = 2f, content = { Bar(taps, c.mode, c.transcribing, c.wait) }) {
                val lead = control(c.leading).onFirst().getUnclippedBoundsInRoot()
                val trail = if (c.trailing != null) control(c.trailing).onFirst().getUnclippedBoundsInRoot()
                else onAllNodes(hasTestTag(SEND_WAIT_RING_TAG), useUnmergedTree = true).onFirst().getUnclippedBoundsInRoot()
                listOf(lead, trail).forEach { b ->
                    assertTrue(kotlin.math.abs((b.right - b.left).value - 48f) < 0.5f && kotlin.math.abs((b.bottom - b.top).value - 48f) < 0.5f, "${c.name}: 48 pt target, got $b")
                    assertTrue(b.left.value >= -0.5f && b.right.value <= 320.5f, "${c.name}: on screen, got $b")
                }
                assertTrue(lead.right <= trail.left, "${c.name}: the controls never overlap: $lead / $trail")
                val pillText = when {
                    c.wait is VoiceBarWait.Correcting -> str(Res.string.voice_refining, "Claude")
                    c.wait == VoiceBarWait.Sending -> str(Res.string.voice_sending)
                    else -> fmtElapsed(7_250)
                }
                val t = onAllNodes(hasText(pillText), useUnmergedTree = true).onFirst().getUnclippedBoundsInRoot()
                assertTrue(t.left >= lead.right && t.right <= trail.left, "${c.name}: \"$pillText\" sits between the controls: $lead / $t / $trail")
                assertTrue(overlapFree(lead, trail), c.name)
            }
        }
    }

    private fun overlapFree(a: DpRect, b: DpRect): Boolean =
        !Rect(a.left.value, a.top.value, a.right.value, a.bottom.value).overlaps(Rect(b.left.value, b.top.value, b.right.value, b.bottom.value))
}
