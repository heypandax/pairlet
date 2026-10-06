package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.secure.SecureStore
import dev.ccpocket.app.telemetry.TelEvent
import dev.ccpocket.app.telemetry.TelKey
import dev.ccpocket.app.telemetry.telemetryTap
import dev.ccpocket.app.voice.DictationEvent
import dev.ccpocket.app.voice.RecordedAudio
import dev.ccpocket.app.voice.VOICE_MAX_MS
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Attached
import dev.ccpocket.protocol.AudioCancel
import dev.ccpocket.protocol.AudioChunk
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.Directories
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.Role
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.TextEdit
import dev.ccpocket.protocol.Transcript
import dev.ccpocket.protocol.TranscriptRefine
import dev.ccpocket.protocol.TranscriptRefineError
import dev.ccpocket.protocol.TranscriptRefined
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Voice input v2 at the repository (docs/design/VOICE-INPUT-V2-REVIEW.md §11): PocketRepository hosting
 * [VoiceSendFlow]. Drives the real paths — [PocketRepository.startVoice] and the bar's actions, the engines through
 * their test seams, frames in through `receiveForTest` and out through `onSendForTest` — on a virtual clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceRefineHostTest {

    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    private val sent = mutableListOf<Frame>()
    private val tel = mutableListOf<Map<TelKey, Any>>()
    private var composerText = ""
    /** The current capture's native event stream; [startVoice][PocketRepository.startVoice] opens a fresh one. */
    private var dictation = Channel<DictationEvent>(Channel.UNLIMITED)
    private val nativeEngine: () -> kotlinx.coroutines.flow.Flow<DictationEvent> =
        { Channel<DictationEvent>(Channel.UNLIMITED).also { dictation = it }.receiveAsFlow() }

    @BeforeTest fun setUp() {
        clearStore()
        telemetryTap = { e, p -> if (e == TelEvent.VoiceRefine) synchronized(tel) { tel += p } }
    }

    @AfterTest fun tearDown() {
        telemetryTap = null
        dev.ccpocket.app.push.PushRegistrar.appForeground.value = true // onAppBackground() lowers this process-wide flag
        scope.cancel()
        clearStore()
    }

    private fun clearStore() {
        SecureStore.remove(PocketRepository.K_VOICE_AFTER_DICTATION)
        SecureStore.remove(PocketRepository.K_VOICE_REFINE_ACK)
        listOf(SESSION, CONVO, NEW_SESSION).forEach { SecureStore.remove(PocketRepository.K_DRAFT_PREFIX + it) }
    }

    private fun run() = scheduler.runCurrent()
    private fun advance(ms: Long) { scheduler.advanceTimeBy(ms); scheduler.runCurrent() }

    /** A chat on a Ready link to a computer that refines for Claude; [send] = the user chose "correct and send". */
    private fun repo(send: Boolean = true, native: Boolean = true, sessionId: String? = SESSION) = PocketRepository(scope).apply {
        paired.value = null
        onSendForTest = { sent += it }
        composerProbe = { ComposerProbe(composerText, composing = false) }
        if (native) nativeDictationForTest = nativeEngine
        else recordForTest = { RecordedAudio(byteArrayOf(1, 2, 3, 4), "audio/wav", durationMs = 1_200) }
        voiceNowMs = { scheduler.currentTime }
        connected.value = true
        phase.value = ConnPhase.Ready
        convoId.value = CONVO
        workdir.value = WORKDIR
        sessionKey.value = sessionId
        sessionAgent.value = AgentKind.CLAUDE
        receiveForTest(DaemonInfo(transcriptRefineAgents = listOf("claude")))
        if (send) {
            acknowledgeVoiceRefineDisclosure()
            assertTrue(setVoiceAfterDictation(VoiceAfterDictation.SEND))
        }
        run()
        sent.clear()
    }

    private fun refines() = sent.filterIsInstance<TranscriptRefine>()
    private fun prompts() = sent.filterIsInstance<SendPrompt>()
    private fun cancels() = sent.filterIsInstance<AudioCancel>()
    private fun outcomes() = synchronized(tel) { tel.map { it[TelKey.Outcome] } }

    /** Record on the send bar, tap the send arrow, and let the native recogniser deliver [text]: the refine is out. */
    private fun PocketRepository.dictateAndSend(text: String = ORIGINAL): TranscriptRefine {
        startVoice(); run()
        assertEquals(VoiceBarMode.EDIT_SEND, voiceBarMode.value, "an eligible capture starts on the send bar")
        finishAndSendVoice(); run()
        final(text); run()
        return refines().single()
    }

    private fun PocketRepository.answer(
        req: TranscriptRefine, text: String = REFINED, edits: List<TextEdit> = EDITS, ok: Boolean = true,
        autoSend: Boolean = true, error: String? = null, captureId: String = req.captureId, convo: String = req.convoId,
    ) {
        receiveForTest(TranscriptRefined(convo, captureId, ok, text, edits, agent = "claude", error = error, autoSend = autoSend))
        run()
    }

    // ── LEGACY: today's bar, exactly ────────────────────────────────────────────────────────────────

    @Test fun composeSettingKeepsTodaysBarExactly() {
        val r = repo(send = false)
        r.startVoice(); run()
        assertEquals(VoiceBarMode.LEGACY, r.voiceBarMode.value)
        r.hear("first words")
        r.stopVoice(); run()
        final("open the build log"); run()
        assertEquals("open the build log", r.pendingVoiceText.value, "✓ lands the transcript in the composer, as today")
        assertTrue(r.voice.value is VoiceState.Idle)
        assertNull(r.voiceComposerReason.value)

        r.pendingVoiceText.value = null // the composer took it
        r.startVoice(); run()
        r.hear("never mind")
        r.cancelVoice(); run()
        assertTrue(r.voice.value is VoiceState.Idle, "✕ discards")
        assertNull(r.pendingVoiceText.value)

        r.startVoice(); run()
        r.hear("left behind")
        r.backToBrowse(); run()
        assertEquals("", r.draftFor(SESSION), "leaving drops an unfinished capture, as today — nothing lands anywhere")

        assertTrue(refines().isEmpty() && prompts().isEmpty(), "today's bar never asks for a refine and never sends")
        assertTrue(outcomes().isEmpty(), "and records no voice_refine")
    }

    @Test fun sendSettingWithoutTheDisclosureIsTodaysBar() {
        SecureStore.putString(PocketRepository.K_VOICE_AFTER_DICTATION, "send") // never acknowledged on this device
        val r = repo(send = false)
        assertEquals(VoiceAfterDictation.SEND, r.voiceAfterDictation.value)
        r.startVoice(); run()
        assertEquals(VoiceBarMode.LEGACY, r.voiceBarMode.value)
        r.finishAndSendVoice(); run() // even a send tap cannot send from today's bar
        final("open the build log"); run()
        assertEquals("open the build log", r.pendingVoiceText.value)
        assertTrue(refines().isEmpty() && prompts().isEmpty())
    }

    @Test fun composeSettingOnWhisperIsTodaysUploadAndLanding() {
        val r = repo(send = false, native = false)
        r.startVoice(); run()
        r.stopVoice(); run()
        val chunk = sent.filterIsInstance<AudioChunk>().single()
        r.receiveForTest(Transcript(CONVO, chunk.captureId, text = "open the build log", ok = true)); run()
        assertEquals("open the build log", r.pendingVoiceText.value)
        assertTrue(refines().isEmpty() && prompts().isEmpty())
    }

    // ── the send bar: happy paths ───────────────────────────────────────────────────────────────────

    @Test fun refinedWithoutCorrectionsIsSentOnceWithoutAttachments() {
        val r = repo()
        val req = r.dictateAndSend()
        assertEquals(CONVO, req.convoId)
        assertEquals(ORIGINAL, req.text)
        assertEquals("claude", req.agentHint, "the hint is this session's agent, not the phone's default")
        assertTrue(!req.locale.isNullOrBlank(), "the refiner's instructions follow the app's language")
        assertEquals(VoiceState.Refining(ORIGINAL, "claude"), r.voice.value)

        r.answer(req, text = ORIGINAL, edits = emptyList())
        val prompt = prompts().single()
        assertEquals(ORIGINAL, prompt.text, "the daemon's text is sent")
        assertTrue(prompt.images.isEmpty())
        assertTrue(r.voice.value is VoiceState.Idle)
        assertNull(r.pendingVoiceText.value)
        assertEquals(VoiceBarMode.LEGACY, r.voiceBarMode.value)
        assertEquals(ORIGINAL, r.messages.filterIsInstance<ChatItem.User>().last().text)

        r.answer(req, text = ORIGINAL, edits = emptyList()) // a duplicate answer
        advance(PocketRepository.REFINE_BUDGET_MS * 2)
        assertEquals(1, prompts().size, "sent exactly once")
        assertNull(r.pendingVoiceText.value, "and never landed a copy in the composer")
        assertEquals(listOf("requested", "auto_sent"), outcomes())
        assertEquals("0", tel.last()[TelKey.Edits])
        assertEquals("0-2999", tel.last()[TelKey.LatencyMs])
    }

    @Test fun correctionsAreHighlightedThenSentWhenTheHoldEnds() {
        val r = repo()
        val req = r.dictateAndSend()
        advance(4_000)
        r.answer(req)
        val preview = assertIs<VoiceState.Preview>(r.voice.value)
        assertEquals(REFINED, preview.text)
        assertEquals(listOf(4 until 10), preview.ranges)
        advance(PocketRepository.PREVIEW_HOLD_MS - 1)
        assertTrue(prompts().isEmpty(), "held while the correction is shown")
        advance(1)
        assertEquals(REFINED, prompts().single().text)
        assertTrue(r.voice.value is VoiceState.Idle)
        assertEquals("1", tel.last()[TelKey.Edits])
        assertEquals("3000-5999", tel.last()[TelKey.LatencyMs])
    }

    @Test fun theFirstWhisperSendTravelsUnderTheCapturesOwnId() {
        val r = repo(native = false)
        r.startVoice(); run()
        r.finishAndSendVoice(); run()
        val chunk = sent.filterIsInstance<AudioChunk>().single()
        r.receiveForTest(Transcript(CONVO, chunk.captureId, text = ORIGINAL, ok = true)); run()
        assertEquals(chunk.captureId, refines().single().captureId, "one id for the capture, its audio and its refine")
        r.answer(refines().single(), text = ORIGINAL, edits = emptyList())
        assertEquals(ORIGINAL, prompts().single().text)
    }

    // ── answers that do not send ────────────────────────────────────────────────────────────────────

    @Test fun anAnswerWithoutAutoSendLandsForReview() {
        val r = repo()
        r.answer(r.dictateAndSend(), autoSend = false)
        assertEquals(REFINED, r.pendingVoiceText.value)
        assertEquals(VoiceComposerReason.REVIEW, r.voiceComposerReason.value)
        assertTrue(prompts().isEmpty())
        assertEquals("to_composer_review", outcomes().last())
    }

    @Test fun anAnswerFromA250DaemonNeverSends() {
        val r = repo()
        val req = r.dictateAndSend()
        // 2.5.0 predates `autoSend`: the field is absent on the wire, even for an answer with nothing to correct
        val wire = """{"t":"pocket/transcript.refined","convoId":"${req.convoId}","captureId":"${req.captureId}",""" +
            """"ok":true,"text":"$ORIGINAL","edits":[],"agent":"claude"}"""
        r.receiveForTest(PocketJson.decodeFromString<Frame>(wire)); run()
        advance(PocketRepository.REFINE_BUDGET_MS)
        assertTrue(prompts().isEmpty())
        assertEquals(ORIGINAL, r.pendingVoiceText.value)
        assertEquals(VoiceComposerReason.REVIEW, r.voiceComposerReason.value)
    }

    @Test fun failuresLandTheOriginalWithTheirReason() {
        for ((error, reason) in listOf(
            TranscriptRefineError.UNAVAILABLE to VoiceComposerReason.UNAVAILABLE,
            TranscriptRefineError.INVALID to VoiceComposerReason.NOT_ADOPTED,
            TranscriptRefineError.TIMEOUT to VoiceComposerReason.TIMEOUT,
        )) {
            sent.clear()
            val r = repo()
            r.answer(r.dictateAndSend(), ok = false, text = "", edits = emptyList(), autoSend = false, error = error)
            assertEquals(ORIGINAL, r.pendingVoiceText.value, error)
            assertEquals(reason, r.voiceComposerReason.value, error)
            assertTrue(prompts().isEmpty(), error)
        }
    }

    @Test fun theDeadlineLandsTheOriginalAndALateAnswerIsIgnored() {
        val r = repo()
        val req = r.dictateAndSend()
        advance(PocketRepository.REFINE_BUDGET_MS - 1)
        assertIs<VoiceState.Refining>(r.voice.value)
        advance(1)
        assertEquals(ORIGINAL, r.pendingVoiceText.value)
        assertEquals(VoiceComposerReason.TIMEOUT, r.voiceComposerReason.value)
        assertEquals(listOf(AudioCancel(CONVO, req.captureId)), cancels(), "the refine is cancelled, best effort")
        assertTrue(r.voice.value is VoiceState.Idle)

        r.pendingVoiceText.value = null // the composer took it
        r.answer(req, text = ORIGINAL, edits = emptyList())
        advance(PocketRepository.PREVIEW_HOLD_MS * 2)
        assertTrue(prompts().isEmpty(), "an answer after the deadline never sends")
        assertNull(r.pendingVoiceText.value, "nor lands a second time")
        assertEquals(listOf("requested", "to_composer_timeout"), outcomes())
        assertEquals("timeout", tel.last()[TelKey.LatencyMs])
    }

    @Test fun anAnswerCancelsTheDeadlineSoTheHoldCanEnd() {
        val r = repo()
        val req = r.dictateAndSend()
        advance(PocketRepository.REFINE_BUDGET_MS - 300) // the hold below runs past where the budget would have ended
        r.answer(req)
        advance(PocketRepository.PREVIEW_HOLD_MS)
        assertEquals(REFINED, prompts().single().text)
        assertNull(r.pendingVoiceText.value)
        assertNull(r.voiceComposerReason.value)
        assertTrue(cancels().isEmpty())
    }

    @Test fun anotherCapturesOrAnotherConversationsAnswerIsIgnored() {
        val r = repo()
        val req = r.dictateAndSend()
        r.answer(req, text = ORIGINAL, edits = emptyList(), captureId = "someone-else")
        r.answer(req, text = ORIGINAL, edits = emptyList(), convo = "another-convo")
        assertTrue(prompts().isEmpty())
        assertIs<VoiceState.Refining>(r.voice.value)
        r.answer(req, text = ORIGINAL, edits = emptyList())
        assertEquals(1, prompts().size)
    }

    // ── the capture stops being sendable ───────────────────────────────────────────────────────────

    @Test fun editDuringTheHoldLandsTheRefinedTextWithoutAReason() {
        val r = repo()
        val req = r.dictateAndSend()
        r.answer(req)
        assertIs<VoiceState.Preview>(r.voice.value)
        r.finishAndEditVoice(); run()
        assertEquals(REFINED, r.pendingVoiceText.value)
        assertNull(r.voiceComposerReason.value)
        advance(PocketRepository.REFINE_BUDGET_MS * 2)
        assertTrue(prompts().isEmpty())
        assertEquals("to_composer_edit", outcomes().last())
    }

    @Test fun editWhileRefiningLandsTheOriginalAndCancelsTheRefine() {
        val r = repo()
        val req = r.dictateAndSend()
        r.finishAndEditVoice(); run()
        assertEquals(ORIGINAL, r.pendingVoiceText.value)
        assertNull(r.voiceComposerReason.value)
        assertEquals(listOf(AudioCancel(CONVO, req.captureId)), cancels())
        r.answer(req, text = ORIGINAL, edits = emptyList())
        assertTrue(prompts().isEmpty())
    }

    @Test fun theCapIsAnEditNeverASend() {
        val r = repo()
        r.startVoice(); run()
        assertEquals(VoiceBarMode.EDIT_SEND, r.voiceBarMode.value)
        advance(VOICE_MAX_MS)
        assertEquals(VoiceState.Transcribing, r.voice.value, "the cap ends the recording as ✓ does")
        assertEquals(VoiceBarMode.EDIT_DONE, r.voiceBarMode.value)
        final(ORIGINAL); run()
        assertEquals(ORIGINAL, r.pendingVoiceText.value)
        assertNull(r.voiceComposerReason.value)
        assertTrue(refines().isEmpty() && prompts().isEmpty())
    }

    @Test fun aRefusedSubmitLandsTheTextAsNotSent() {
        val r = repo()
        r.beforeVoiceSubmitForTest = { r.sessionDegraded.value = true } // degraded between the last check and the submit
        r.answer(r.dictateAndSend(), text = ORIGINAL, edits = emptyList())
        assertTrue(prompts().isEmpty(), "the degraded-session gate refused it")
        assertEquals(ORIGINAL, r.pendingVoiceText.value)
        assertEquals(VoiceComposerReason.NOT_SENT, r.voiceComposerReason.value)
        assertTrue(r.messages.any { it is ChatItem.Sys }, "with the gate's own explanation in the chat")
        assertEquals("to_composer_not_sent", outcomes().last())
    }

    @Test fun leavingWhileRefiningAppendsToTheOriginsDraftOnly() {
        val r = repo()
        val req = r.dictateAndSend()
        r.saveDraft(SESSION, "keep this") // what App.kt saves on the way out
        r.backToBrowse(); run()
        assertEquals("keep this $ORIGINAL", r.draftFor(SESSION), "appended once, to the conversation the capture started in")
        assertNull(r.pendingVoiceText.value, "never into whatever composer comes next")
        assertNull(r.voiceComposerReason.value)
        assertEquals(listOf(AudioCancel(CONVO, req.captureId)), cancels())
        r.answer(req, text = ORIGINAL, edits = emptyList())
        assertTrue(prompts().isEmpty())
        assertEquals("keep this $ORIGINAL", r.draftFor(SESSION))
    }

    @Test fun leavingMidRecordingKeepsTheLiveTextOnTheNewBar() {
        val r = repo()
        r.startVoice(); run()
        r.hear("half a sentence")
        r.backToBrowse(); run()
        assertEquals("half a sentence", r.draftFor(SESSION))
        assertNull(r.pendingVoiceText.value)
    }

    @Test fun aBrandNewSessionsReKeyIsFollowed() {
        val r = repo(sessionId = null) // composer keyed by the conversation until the session has an id
        r.dictateAndSend()
        r.receiveForTest(SessionLive(CONVO, WORKDIR, NEW_SESSION, executing = false, agent = AgentKind.CLAUDE)); run()
        r.backToBrowse(); run()
        assertEquals(ORIGINAL, r.draftFor(NEW_SESSION), "the text follows the composer to its new key")
        assertEquals("", r.draftFor(CONVO))
    }

    @Test fun backgroundingWhileRefiningLandsTheOriginal() {
        val r = repo()
        val req = r.dictateAndSend()
        r.onAppBackground(); run()
        assertEquals(ORIGINAL, r.pendingVoiceText.value)
        assertNull(r.voiceComposerReason.value)
        assertEquals(listOf(AudioCancel(CONVO, req.captureId)), cancels())
        r.answer(req, text = ORIGINAL, edits = emptyList())
        assertTrue(prompts().isEmpty())
    }

    @Test fun aNewRecordingWhileOneWaitsKeepsTheOldText() {
        val r = repo()
        val old = r.dictateAndSend()
        r.startVoice(); run()
        assertEquals(ORIGINAL, r.pendingVoiceText.value, "the waiting capture's text is kept, in the composer")
        assertEquals(listOf(AudioCancel(CONVO, old.captureId)), cancels())
        assertIs<VoiceState.Recording>(r.voice.value)
        assertEquals(VoiceBarMode.EDIT_DONE, r.voiceBarMode.value, "the composer is about to hold text: this one cannot send")
        r.answer(old, text = ORIGINAL, edits = emptyList())
        assertTrue(prompts().isEmpty(), "the superseded capture never sends")

        r.pendingVoiceText.value = null
        r.finishAndSendVoice(); run()
        final("second thought"); run()
        assertEquals("second thought", r.pendingVoiceText.value)
        assertTrue(prompts().isEmpty())
        assertEquals(1, refines().size)
    }

    @Test fun aManualSendWhileRefiningKeepsTheDictationUnsent() {
        val r = repo()
        val req = r.dictateAndSend()
        assertTrue(r.sendPrompt("typed instead"))
        run()
        assertEquals(listOf("typed instead"), prompts().map { it.text })
        assertEquals(ORIGINAL, r.pendingVoiceText.value, "the dictation lands in the composer after the typed message")
        r.answer(req, text = ORIGINAL, edits = emptyList())
        assertEquals(1, prompts().size)
    }

    // ── the bar's mode ─────────────────────────────────────────────────────────────────────────────

    @Test fun theModeIsFrozenAtStartAndOnlyFalls() {
        val r = repo()
        composerText = "a draft"
        r.startVoice(); run()
        assertEquals(VoiceBarMode.EDIT_DONE, r.voiceBarMode.value, "a draft in the composer: ✓ will not send")
        composerText = "" // eligible again now
        assertEquals(VoiceBarMode.EDIT_DONE, r.voiceBarMode.value, "never rises during the capture")
        r.finishAndSendVoice(); run() // whatever the UI calls, this capture cannot send
        final(ORIGINAL); run()
        assertEquals(ORIGINAL, r.pendingVoiceText.value)
        assertTrue(refines().isEmpty())
        assertEquals(VoiceBarMode.LEGACY, r.voiceBarMode.value, "no capture, no bar")
    }

    @Test fun losingEligibilityMidCaptureDowngradesTheBar() {
        val downgrades: List<Pair<String, PocketRepository.() -> Unit>> = listOf(
            "an attachment" to { attachImages(listOf(byteArrayOf(1, 2, 3))) },
            "the computer's refiners" to { receiveForTest(DaemonInfo(transcriptRefineAgents = listOf("claude", "codex"))) },
            "the session's agent" to { receiveForTest(SessionLive(CONVO, WORKDIR, SESSION, executing = false, agent = AgentKind.CODEX)) },
            "the setting" to { assertTrue(setVoiceAfterDictation(VoiceAfterDictation.COMPOSE)) },
            "the app going away" to { onAppBackground() },
        )
        for ((what, change) in downgrades) {
            sent.clear()
            val r = repo()
            r.startVoice(); run()
            assertEquals(VoiceBarMode.EDIT_SEND, r.voiceBarMode.value, what)
            change(r); run()
            assertEquals(VoiceBarMode.EDIT_DONE, r.voiceBarMode.value, what)
            r.finishAndSendVoice(); run()
            final(ORIGINAL); run()
            assertEquals(ORIGINAL, r.pendingVoiceText.value, what)
            assertTrue(refines().isEmpty() && prompts().isEmpty(), what)
        }
    }

    @Test fun aSlowTranscriptNoLongerSendsOnceThePlainComposerIsBack() {
        val r = repo(native = false)
        r.startVoice(); run()
        r.finishAndSendVoice(); run()
        val chunk = sent.filterIsInstance<AudioChunk>().single()
        advance(PocketRepository.TRANSCRIBE_TIMEOUT_MS + 1)
        assertEquals(VoiceState.StillWaiting, r.voice.value)
        assertEquals(VoiceBarMode.EDIT_DONE, r.voiceBarMode.value)
        r.receiveForTest(Transcript(CONVO, chunk.captureId, text = ORIGINAL, ok = true)); run()
        assertEquals(ORIGINAL, r.pendingVoiceText.value)
        assertTrue(refines().isEmpty() && prompts().isEmpty())
    }

    // ── a dropped link ─────────────────────────────────────────────────────────────────────────────

    @Test fun aDropWhileRefiningLandsTheOriginalAsDisconnected() {
        val drop = CompletableDeferred<Unit>()
        val r = PocketRepository(scope).apply {
            paired.value = PairedDaemon(relay = "wss://127.0.0.1:9", accountId = "acct-voice-refine", daemonPub = "pk",
                deviceId = "dev", credential = "cred")
            onSendForTest = { sent += it }
            composerProbe = { ComposerProbe(composerText, composing = false) }
            nativeDictationForTest = nativeEngine
            dialForTest = { _, _ -> drop.await() } // the relay "socket" lives until the test drops it
            startRelay()
            receiveControlForTest(Attached(Role.DEVICE, "acct-voice-refine"))
            receiveForTest(Directories(emptyList()))
            receiveForTest(SessionLive(CONVO, WORKDIR, SESSION, executing = false, agent = AgentKind.CLAUDE))
            receiveForTest(DaemonInfo(transcriptRefineAgents = listOf("claude")))
            acknowledgeVoiceRefineDisclosure()
            assertTrue(setVoiceAfterDictation(VoiceAfterDictation.SEND))
        }
        run()
        assertEquals(ConnPhase.Ready, r.phase.value)
        sent.clear()
        val req = r.dictateAndSend()

        drop.complete(Unit); run() // the socket died
        assertEquals(ORIGINAL, r.pendingVoiceText.value)
        assertEquals(VoiceComposerReason.DISCONNECTED, r.voiceComposerReason.value)
        assertEquals(listOf(AudioCancel(CONVO, req.captureId)), cancels(), "queued for the same computer after the reconnect")
        r.answer(req, text = ORIGINAL, edits = emptyList())
        assertTrue(prompts().isEmpty())
        assertEquals("to_composer_disconnected", outcomes().last())
    }

    @Test fun disconnectingFromTheComputerQueuesNothingAndKeepsTheText() {
        val r = repo()
        r.dictateAndSend()
        r.disconnect(); run()
        assertTrue(cancels().isEmpty(), "the outboxes were drained: a cancel would reach the next computer")
        assertEquals(ORIGINAL, r.draftFor(SESSION))
        assertNull(r.pendingVoiceText.value)
        assertTrue(r.daemonTranscriptRefineAgents.value.isEmpty())
    }

    @Test fun theReasonLineLastsUntilASendOrLeaving() {
        val r = repo()
        r.answer(r.dictateAndSend(), autoSend = false)
        assertEquals(VoiceComposerReason.REVIEW, r.voiceComposerReason.value)
        r.pendingVoiceText.value = null
        advance(60_000)
        assertEquals(VoiceComposerReason.REVIEW, r.voiceComposerReason.value, "sticky, unlike the 2.5 s notice")
        assertTrue(r.sendPrompt(REFINED))
        assertNull(r.voiceComposerReason.value, "the first send clears it")

        r.answer(r.dictateAndSend2(), autoSend = false)
        assertEquals(VoiceComposerReason.REVIEW, r.voiceComposerReason.value)
        r.clearVoiceComposerReason()
        assertNull(r.voiceComposerReason.value)
    }

    /** A second dictation on the same repository (the first one's refine is no longer the single one). */
    private fun PocketRepository.dictateAndSend2(): TranscriptRefine {
        pendingVoiceText.value = null
        val before = refines().size
        startVoice(); run()
        finishAndSendVoice(); run()
        final(ORIGINAL); run()
        return refines().drop(before).single()
    }

    /** iOS live dictation: the recogniser's current hypothesis. */
    private fun PocketRepository.hear(hypothesis: String) {
        dictation.trySend(DictationEvent.Partial(hypothesis, "")); run()
        assertNotEquals(VoiceState.Idle, voice.value)
    }

    /** The recogniser's final text; its stream ends right after, as the platform's does. */
    private fun final(text: String) {
        dictation.trySend(DictationEvent.Final(text)); dictation.close()
    }

    private companion object {
        const val CONVO = "vr-convo-1"
        const val SESSION = "vr-session-1"
        const val NEW_SESSION = "vr-session-new"
        const val WORKDIR = "/work/voice-refine"
        const val ORIGINAL = "请比较 cloud 和 Claude 的输出"
        const val REFINED = "请比较 Claude 和 Claude 的输出"
        val EDITS = listOf(TextEdit("cloud", "Claude"))
    }
}
