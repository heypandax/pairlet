package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.ccpocket.app.advanceFrameAndWait
import dev.ccpocket.app.data.ComposerProbe
import dev.ccpocket.app.data.ConnPhase
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.data.VoiceAfterDictation
import dev.ccpocket.app.data.VoiceBarMode
import dev.ccpocket.app.data.VoiceComposerReason
import dev.ccpocket.app.data.VoiceState
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.cancel_recording
import dev.ccpocket.app.resources.done
import dev.ccpocket.app.resources.voice_edit_instead
import dev.ccpocket.app.resources.voice_finish_edit
import dev.ccpocket.app.resources.voice_finish_send
import dev.ccpocket.app.resources.send
import dev.ccpocket.app.resources.voice_reason_review
import dev.ccpocket.app.resources.voice_reason_timeout
import dev.ccpocket.app.resources.voice_refining
import dev.ccpocket.app.resources.voice_sending
import dev.ccpocket.app.secure.SecureStore
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.voice.DictationEvent
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.TextEdit
import dev.ccpocket.protocol.TranscriptRefine
import dev.ccpocket.protocol.TranscriptRefined
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Voice input v2 in the chat screen (README "后续决定", review §11 "接界面时的约束"): the composer probe ChatScreen
 * registers, the send bar driven by a real [PocketRepository] — its native engine and wire frames through the test
 * seams — through correcting, the highlighted hold and the send, the reason line's lifetime, and the 400 ms draft
 * save against a dictation the repository lands in the draft of the chat being left.
 */
@OptIn(ExperimentalTestApi::class)
class VoiceComposerHooksUiTest {

    private val convo = "c-voice-v2"
    private val session = "s-voice-v2"

    private fun account() = PairedDaemon(
        relay = "wss://test.invalid", accountId = "acct-voice-v2", daemonPub = "pub",
        deviceId = "dev", credential = "cred", hostName = "alex-macbook",
    )

    @BeforeTest fun setUp() = clearStore()
    @AfterTest fun tearDown() = clearStore()

    private fun clearStore() {
        SecureStore.remove(PocketRepository.K_VOICE_AFTER_DICTATION)
        SecureStore.remove(PocketRepository.K_VOICE_REFINE_ACK)
        listOf(session, convo, DIR).forEach { SecureStore.remove(PocketRepository.K_DRAFT_PREFIX + it) }
    }

    /** What a scene shares with its assertions: the frames sent, the current native recogniser, the mount switch. */
    private class Rig {
        val sent = mutableListOf<Frame>()
        var dictation = Channel<DictationEvent>(Channel.UNLIMITED)
        val mounted: MutableState<Boolean> = mutableStateOf(true)
        lateinit var repo: PocketRepository
    }

    /** A Claude chat on a Ready link to a computer that refines for Claude; [send] = "Correct and send" is on. */
    private fun chat(send: Boolean = true, assertions: SkikoComposeUiTest.(Rig) -> Unit) = runDesktopComposeUiTest(402, 874) {
        mainClock.autoAdvance = false // every assertion is about a settled frame; timers move only when the test says so
        val rig = Rig()
        setContent {
            val scope = rememberCoroutineScope()
            val repo = remember {
                PocketRepository(scope, account()).apply {
                    onSendForTest = { rig.sent += it }
                    nativeDictationForTest = { Channel<DictationEvent>(Channel.UNLIMITED).also { rig.dictation = it }.receiveAsFlow() }
                    connected.value = true
                    phase.value = ConnPhase.Ready
                    receiveForTest(SessionLive(convoId = convo, workdir = DIR, sessionId = session, mode = PermissionMode.DEFAULT, executing = false, agent = AgentKind.CLAUDE))
                    // a resumed chat with a turn on screen: the open's land-at-the-latest scroll happens at mount, as
                    // in ChatMasterV2UiTest — the first send of an EMPTY chat re-enters it inside a measure pass under
                    // this harness's unconfined effect dispatcher, which is not what is being tested here
                    receiveForTest(ConvoHistory(convo, listOf(HistoryMessage(ChatRole.USER, "check the relay logs"))))
                    receiveForTest(DaemonInfo(transcriptRefineAgents = listOf("claude")))
                    if (send) {
                        acknowledgeVoiceRefineDisclosure()
                        assertTrue(setVoiceAfterDictation(VoiceAfterDictation.SEND))
                    }
                }
            }
            rig.repo = repo
            PocketTheme { Box(Modifier.fillMaxSize()) { if (rig.mounted.value) ChatScreen(repo) } }
        }
        advanceFrameAndWait()
        assertions(rig)
    }

    private fun SkikoComposeUiTest.controlCount(label: String) =
        onAllNodes(hasContentDescription(label) and hasClickAction()).fetchSemanticsNodes().size

    private fun SkikoComposeUiTest.field() = onAllNodes(hasSetTextAction()).onFirst()

    /** Record on the send bar, tap the arrow, and let the recogniser deliver [ORIGINAL]: the refine is out. */
    private fun SkikoComposeUiTest.dictateAndSend(rig: Rig): TranscriptRefine {
        runOnIdle { rig.repo.startVoice() }
        advanceFrameAndWait()
        assertEquals(VoiceBarMode.EDIT_SEND, rig.repo.voiceBarMode.value, "an empty composer on a refining computer: a send bar")
        assertEquals(1, controlCount(str(Res.string.voice_finish_edit)))
        assertEquals(0, controlCount(str(Res.string.cancel_recording)))
        onAllNodes(hasContentDescription(str(Res.string.voice_finish_send)) and hasClickAction()).onFirst().performClick()
        advanceFrameAndWait()
        runOnIdle { rig.dictation.trySend(DictationEvent.Final(ORIGINAL)); rig.dictation.close() }
        advanceFrameAndWait()
        return rig.sent.filterIsInstance<TranscriptRefine>().single()
    }

    // ── the probe ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theComposerProbeReadsTheLiveFieldAndLeavesWithTheChat() = chat(send = false) { rig ->
        val probe = assertNotNull(rig.repo.composerProbe, "ChatScreen registers its composer")
        assertEquals(ComposerProbe("", composing = false), probe())
        field().performTextInput("half typed")
        advanceFrameAndWait()
        assertEquals(ComposerProbe("half typed", composing = false), rig.repo.composerProbe?.invoke())
        assertEquals("", rig.repo.draftFor(rig.repo.composerKey()), "the live text — the 400 ms draft has not even been saved yet")
        runOnIdle { rig.mounted.value = false }
        advanceFrameAndWait()
        assertNull(rig.repo.composerProbe, "a chat that left composition reports nothing, so nothing is eligible")
    }

    @Test
    fun aDraftMakesTheBarOneThatCannotSend() = chat { rig ->
        field().performTextInput("a draft")
        advanceFrameAndWait()
        runOnIdle { rig.repo.startVoice() }
        advanceFrameAndWait()
        assertEquals(VoiceBarMode.EDIT_DONE, rig.repo.voiceBarMode.value, "the probe saw the draft")
        assertEquals(1, controlCount(str(Res.string.voice_finish_edit)))
        assertEquals(1, controlCount(str(Res.string.done)))
        assertEquals(0, controlCount(str(Res.string.voice_finish_send)))
    }

    // ── the send bar in the chat ────────────────────────────────────────────────────────────────────

    @Test
    fun correctionsAreShownInPlaceThenSent() = chat { rig ->
        val req = dictateAndSend(rig)
        assertEquals(VoiceState.Refining(ORIGINAL, "claude"), rig.repo.voice.value)
        assertTrue(present(ORIGINAL), "the original stays above the bar while it is corrected")
        assertTrue(present(str(Res.string.voice_refining, "Claude")), "the pill names the agent by its product name")
        assertEquals(1, controlCount(str(Res.string.voice_edit_instead)))
        assertEquals(0, controlCount(str(Res.string.voice_finish_send)), "nothing to tap twice while it waits")
        assertEquals(0, onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size, "the composer field stays hidden")

        runOnIdle { rig.repo.receiveForTest(TranscriptRefined(req.convoId, req.captureId, ok = true, text = REFINED, edits = EDITS, agent = "claude", autoSend = true)) }
        advanceFrameAndWait()
        assertIs<VoiceState.Preview>(rig.repo.voice.value)
        assertTrue(present(str(Res.string.voice_sending)))
        val shown = onAllNodes(hasText(REFINED), useUnmergedTree = true).fetchSemanticsNodes().single()
            .config[SemanticsProperties.Text].single()
        assertEquals(listOf(4 to 10), shown.spanStyles.map { it.start to it.end }, "the corrected word is marked")
        assertTrue(rig.sent.filterIsInstance<SendPrompt>().isEmpty(), "held while the correction is on show")

        mainClock.advanceTimeBy(PocketRepository.PREVIEW_HOLD_MS + 50)
        advanceFrameAndWait()
        assertEquals(listOf(REFINED), rig.sent.filterIsInstance<SendPrompt>().map { it.text })
        assertIs<VoiceState.Idle>(rig.repo.voice.value)
        assertEquals("", rig.repo.composerProbe?.invoke()?.text, "sent, not landed")
        assertNull(rig.repo.voiceComposerReason.value)
    }

    @Test
    fun editInsteadLandsTheOriginalWithoutSending() = chat { rig ->
        dictateAndSend(rig)
        onAllNodes(hasContentDescription(str(Res.string.voice_edit_instead)) and hasClickAction()).onFirst().performClick()
        advanceFrameAndWait(); advanceFrameAndWait()
        field().assertTextEquals(ORIGINAL)
        assertNull(rig.repo.voiceComposerReason.value, "the user chose to edit: no reason to explain")
        mainClock.advanceTimeBy(PocketRepository.REFINE_BUDGET_MS * 2)
        advanceFrameAndWait()
        assertTrue(rig.sent.filterIsInstance<SendPrompt>().isEmpty())
    }

    // ── the reason line ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun theReasonLineOutlivesTheLandingAndClearsOnTheFirstEdit() = chat { rig ->
        val req = dictateAndSend(rig)
        // corrected, but the computer did not authorise sending it: it lands for review
        runOnIdle { rig.repo.receiveForTest(TranscriptRefined(req.convoId, req.captureId, ok = true, text = REFINED, edits = EDITS, agent = "claude", autoSend = false)) }
        advanceFrameAndWait(); advanceFrameAndWait()
        field().assertTextEquals(REFINED)
        assertEquals(VoiceComposerReason.REVIEW, rig.repo.voiceComposerReason.value, "the landing append is not an edit")
        assertTrue(present(str(Res.string.voice_reason_review)))
        mainClock.advanceTimeBy(10_000)
        advanceFrameAndWait()
        assertTrue(present(str(Res.string.voice_reason_review)), "sticky — not the 2.5 s notice")

        field().performTextInput(" now")
        advanceFrameAndWait()
        assertNull(rig.repo.voiceComposerReason.value, "the user's first edit clears it")
        assertFalse(present(str(Res.string.voice_reason_review)))
    }

    @Test
    fun theReasonLineClearsOnSend() = chat { rig ->
        val req = dictateAndSend(rig)
        mainClock.advanceTimeBy(PocketRepository.REFINE_BUDGET_MS + 50) // the computer never answers
        advanceFrameAndWait(); advanceFrameAndWait()
        assertEquals(VoiceComposerReason.TIMEOUT, rig.repo.voiceComposerReason.value)
        field().assertTextEquals(ORIGINAL)
        assertTrue(present(str(Res.string.voice_reason_timeout)))
        assertTrue(rig.sent.none { it is SendPrompt })
        assertTrue(rig.sent.filterIsInstance<TranscriptRefine>().single() == req)
        onAllNodes(hasContentDescription(str(Res.string.send)) and hasClickAction()).onFirst().performClick()
        advanceFrameAndWait()
        assertEquals(listOf(ORIGINAL), rig.sent.filterIsInstance<SendPrompt>().map { it.text })
        assertNull(rig.repo.voiceComposerReason.value, "a send clears it")
        assertFalse(present(str(Res.string.voice_reason_timeout)))
    }

    // ── the debounced draft save vs. the origin draft ──────────────────────────────────────────────

    /**
     * Leaving while a send-bar capture still has text: the repository appends it to the draft of the chat being left,
     * after the leave path saved this composer. A 400 ms draft save still waiting from the last keystroke must not
     * then overwrite that append with the composer's older text — and coming back opens the composer on it.
     */
    @Test
    fun aDictationLandedForTheChatBeingLeftOutlivesTheDebouncedSave() = chat { rig ->
        val key = assertNotNull(rig.repo.composerKey())
        field().performTextInput("keep this")
        advanceFrameAndWait() // the composer's debounced save of "keep this" is now waiting
        runOnIdle { rig.repo.startVoice() }
        advanceFrameAndWait()
        assertEquals(VoiceBarMode.EDIT_DONE, rig.repo.voiceBarMode.value, "a draft in the field: this capture can only land")
        runOnIdle { rig.dictation.trySend(DictationEvent.Partial("half a sentence", "")) }
        advanceFrameAndWait()

        // the switcher's / "All projects" contract: this composer's draft is saved first, then the chat is left
        runOnIdle { rig.repo.saveDraft(key, "keep this"); rig.repo.backToBrowse() }
        assertEquals("keep this half a sentence", rig.repo.draftFor(key), "appended to the draft it came from")
        mainClock.advanceTimeBy(1_000) // well past the save that was waiting when the chat was left
        advanceFrameAndWait()
        assertEquals("keep this half a sentence", rig.repo.draftFor(key), "the stale debounced save did not overwrite it")

        runOnIdle { rig.mounted.value = false }
        advanceFrameAndWait()
        runOnIdle { rig.mounted.value = true }
        advanceFrameAndWait()
        field().assertTextEquals("keep this half a sentence")
    }

    @Test
    fun theDebouncedSaveStillSavesWhatIsTyped() = chat(send = false) { rig ->
        val key = assertNotNull(rig.repo.composerKey())
        field().performTextInput("first words")
        advanceFrameAndWait()
        mainClock.advanceTimeBy(500)
        advanceFrameAndWait()
        assertEquals("first words", rig.repo.draftFor(key))
        field().performTextInput(" and more")
        advanceFrameAndWait()
        mainClock.advanceTimeBy(500)
        advanceFrameAndWait()
        assertEquals("first words and more", rig.repo.draftFor(key))
    }

    private companion object {
        const val DIR = "/Users/alex/code/voice-v2"
        const val ORIGINAL = "请比较 cloud 和 Claude 的输出"
        const val REFINED = "请比较 Claude 和 Claude 的输出"
        val EDITS = listOf(TextEdit("cloud", "Claude"))
    }
}
