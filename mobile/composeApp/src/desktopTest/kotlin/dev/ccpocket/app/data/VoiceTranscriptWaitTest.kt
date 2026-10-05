package dev.ccpocket.app.data

import dev.ccpocket.app.net.TransientDisposition
import dev.ccpocket.app.net.TransientEnqueueResult
import dev.ccpocket.app.net.TransientTicket
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.voice_no_response
import dev.ccpocket.app.resources.voice_transcribe_failed
import dev.ccpocket.app.voice.RecordedAudio
import dev.ccpocket.protocol.AudioCancel
import dev.ccpocket.protocol.AudioChunk
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.Transcript
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Remote dictation's wait for the transcript (2026-10-05, "fails first, then the text appears"): an upload stuck
 * behind a slow link ran the 15 s guard out, the app showed "No response" and dropped the transcript that came
 * a few seconds later; the user's retry re-sent the same recording under a new captureId.
 *
 * Drives the real path: a staged [PocketRepository.keptAudio] and [PocketRepository.retryVoice] (the same
 * upload ✓ triggers), outbound chunks observed through `onSendForTest`, transcripts injected with
 * `receiveForTest`, time on a virtual scheduler.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceTranscriptWaitTest {

    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))

    @AfterTest
    fun tearDown() = scope.cancel()

    private val sent = mutableListOf<Frame>()

    private fun repo() = PocketRepository(scope).apply {
        convoId.value = "c1"
        onSendForTest = { sent += it }
    }

    private fun audio() = RecordedAudio(byteArrayOf(1, 2, 3, 4, 5), "audio/mp4", durationMs = 1_200)

    private fun chunks() = sent.filterIsInstance<AudioChunk>()

    /** Stage a recording and send it, exactly as ✓ (or the retry mic) does. Returns the captureId sent. */
    private fun PocketRepository.dictate(): String {
        keptAudio = audio()
        voice.value = VoiceState.Transcribing
        retryVoice()
        scheduler.runCurrent()
        return chunks().last().captureId
    }

    private fun advance(ms: Long) {
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
    }

    // ── 1. the timeout no longer fails the capture ──────────────────────────────────────────────

    @Test
    fun aTranscriptArrivingAfterTheTimeoutIsStillUsed() {
        val r = repo()
        val a = r.dictate()
        advance(PocketRepository.TRANSCRIBE_TIMEOUT_MS + 2_000) // the upload was stuck: past the old 15 s guard

        r.receiveForTest(Transcript("c1", a, text = "open the build log", ok = true))

        assertEquals("open the build log", r.pendingVoiceText.value, "a late transcript of the capture must still land in the composer")
        assertTrue(r.voice.value is VoiceState.Idle, "and the voice flow ends as on any delivery")
    }

    @Test
    fun pastTheTimeoutTheWaitIsNeutralUntilTheFinalGiveUp() {
        val r = repo()
        val a = r.dictate()
        advance(PocketRepository.TRANSCRIBE_TIMEOUT_MS - 1)
        assertEquals(VoiceState.Transcribing, r.voice.value)

        advance(2)
        assertEquals(VoiceState.StillWaiting, r.voice.value, "past the ordinary window: still waiting, not failed")

        advance(PocketRepository.TRANSCRIBE_GIVE_UP_MS - PocketRepository.TRANSCRIBE_TIMEOUT_MS - 2)
        assertEquals(VoiceState.StillWaiting, r.voice.value, "one moment before the give-up it is still a wait")

        advance(2)
        val failed = assertIs<VoiceState.Failed>(r.voice.value, "the give-up is final")
        assertEquals(Res.string.voice_no_response, failed.res, "with the existing failure copy")

        r.receiveForTest(Transcript("c1", a, text = "too late", ok = true))
        assertNull(r.pendingVoiceText.value, "after the give-up nothing is waited for any longer")
    }

    // ── 2. a retry: the first transcript of the recording wins, once ────────────────────────────

    @Test
    fun afterARetryTheEarlierSendsTranscriptLandsOnce() {
        val r = repo()
        val a = r.dictate()
        advance(PocketRepository.TRANSCRIBE_TIMEOUT_MS + 1)
        assertEquals(VoiceState.StillWaiting, r.voice.value)

        r.retryVoice()
        scheduler.runCurrent()
        val b = chunks().last().captureId
        assertTrue(a != b, "a retry sends the kept recording under a fresh captureId")
        assertEquals(VoiceState.Transcribing, r.voice.value)

        r.receiveForTest(Transcript("c1", a, text = "first send", ok = true))
        assertEquals("first send", r.pendingVoiceText.value, "the earlier send of the same recording is accepted")
        r.pendingVoiceText.value = null // the composer took it

        r.receiveForTest(Transcript("c1", b, text = "first send", ok = true))
        assertNull(r.pendingVoiceText.value, "the second transcript of the same recording is dropped, never inserted twice")
        assertTrue(r.voice.value is VoiceState.Idle)
        advance(PocketRepository.TRANSCRIBE_GIVE_UP_MS * 2)
        assertTrue(r.voice.value is VoiceState.Idle, "no wait or timer outlives the delivery")
    }

    @Test
    fun afterARetryTheRetrysTranscriptLandsOnce() {
        val r = repo()
        val a = r.dictate()
        advance(PocketRepository.TRANSCRIBE_TIMEOUT_MS + 1)
        r.retryVoice()
        scheduler.runCurrent()
        val b = chunks().last().captureId

        r.receiveForTest(Transcript("c1", b, text = "second send", ok = true))
        assertEquals("second send", r.pendingVoiceText.value)
        r.pendingVoiceText.value = null

        r.receiveForTest(Transcript("c1", a, text = "second send", ok = true))
        assertNull(r.pendingVoiceText.value, "the earlier send's transcript arriving last is dropped")
        assertTrue(r.voice.value is VoiceState.Idle)
    }

    @Test
    fun afterARetryOnlyBothFailingIsAFailure() {
        val r = repo()
        val a = r.dictate()
        advance(PocketRepository.TRANSCRIBE_TIMEOUT_MS + 1)
        r.retryVoice()
        scheduler.runCurrent()
        val b = chunks().last().captureId

        r.receiveForTest(Transcript("c1", a, ok = false, error = "transcription timed out"))
        assertEquals(VoiceState.Transcribing, r.voice.value, "one send of the recording failing leaves the other to answer")

        r.receiveForTest(Transcript("c1", b, ok = false, error = "transcription failed (whisper exit 1)"))
        val failed = assertIs<VoiceState.Failed>(r.voice.value)
        assertEquals(Res.string.voice_transcribe_failed, failed.res)
        assertEquals("transcription failed (whisper exit 1)", failed.detail)
    }

    // ── 3. the wait starts once the audio has left the phone ────────────────────────────────────

    @Test
    fun theWaitStartsOnlyOnceTheChunkIsWritten() {
        val r = repo()
        val outcome = CompletableDeferred<TransientDisposition>()
        r.voiceUploadForTest = { TransientTicket(TransientEnqueueResult.ACCEPTED, outcome) }
        r.dictate()
        assertTrue(r.voiceUploading.value, "queued, not yet taken by the socket: S3 reads uploading")

        advance(PocketRepository.TRANSCRIBE_TIMEOUT_MS * 3) // a stalled upload, well past the old guard
        assertEquals(VoiceState.Transcribing, r.voice.value, "time spent queued does not count against the computer")
        assertTrue(r.voiceUploading.value)

        outcome.complete(TransientDisposition.WRITTEN)
        scheduler.runCurrent()
        assertTrue(!r.voiceUploading.value, "written: the wait for the transcript begins")

        advance(PocketRepository.TRANSCRIBE_TIMEOUT_MS - 1)
        assertEquals(VoiceState.Transcribing, r.voice.value, "the ordinary window counts from the write")
        advance(2)
        assertEquals(VoiceState.StillWaiting, r.voice.value)
    }

    @Test
    fun aMultiChunkCaptureWaitsForItsLastChunk() {
        val r = repo()
        val outcomes = mutableListOf<CompletableDeferred<TransientDisposition>>()
        r.voiceUploadForTest = { CompletableDeferred<TransientDisposition>().also { outcomes += it }.let { TransientTicket(TransientEnqueueResult.ACCEPTED, it) } }
        r.keptAudio = RecordedAudio(ByteArray(dev.ccpocket.app.voice.AUDIO_CHUNK_B64) { it.toByte() }, "audio/mp4", 60_000) // > 1 chunk once base64'd
        r.voice.value = VoiceState.Transcribing
        r.retryVoice()
        scheduler.runCurrent()
        assertEquals(2, outcomes.size)

        outcomes[0].complete(TransientDisposition.WRITTEN)
        advance(PocketRepository.TRANSCRIBE_TIMEOUT_MS * 2)
        assertTrue(r.voiceUploading.value, "the first chunk alone is not the capture")
        assertEquals(VoiceState.Transcribing, r.voice.value)

        outcomes[1].complete(TransientDisposition.WRITTEN)
        scheduler.runCurrent()
        assertTrue(!r.voiceUploading.value)
        advance(PocketRepository.TRANSCRIBE_TIMEOUT_MS + 1)
        assertEquals(VoiceState.StillWaiting, r.voice.value)
    }

    @Test
    fun aChunkTheConnectionDroppedIsResentByTheOrdinaryOutboxUnderTheSameId() {
        val r = repo()
        val outcome = CompletableDeferred<TransientDisposition>()
        r.voiceUploadForTest = { TransientTicket(TransientEnqueueResult.ACCEPTED, outcome) }
        val a = r.dictate()
        assertEquals(1, chunks().size)

        outcome.complete(TransientDisposition.NOT_WRITTEN) // the connection it was bound to went away first
        scheduler.runCurrent()
        assertEquals(listOf(a, a), chunks().map { it.captureId }, "re-queued on the ordinary outbox, which survives the reconnect")
        assertTrue(!r.voiceUploading.value)

        r.receiveForTest(Transcript("c1", a, text = "made it", ok = true))
        assertEquals("made it", r.pendingVoiceText.value)
    }

    @Test
    fun aCancelledUploadIsNotResent() {
        val r = repo()
        val outcome = CompletableDeferred<TransientDisposition>()
        r.voiceUploadForTest = { TransientTicket(TransientEnqueueResult.ACCEPTED, outcome) }
        r.dictate()
        r.cancelVoice()
        scheduler.runCurrent()
        assertTrue(sent.any { it is AudioCancel })
        assertTrue(!r.voiceUploading.value)

        outcome.complete(TransientDisposition.NOT_WRITTEN) // its fence kept it off the wire
        scheduler.runCurrent()
        assertEquals(1, chunks().size, "an abandoned capture is not re-queued")
        assertTrue(r.voice.value is VoiceState.Idle)
    }

    // ── a late transcript never lands anywhere else ─────────────────────────────────────────────

    @Test
    fun aLateTranscriptNeverLandsInAnotherSession() {
        val r = repo()
        val a = r.dictate()
        advance(PocketRepository.TRANSCRIBE_TIMEOUT_MS + 1)
        assertEquals(VoiceState.StillWaiting, r.voice.value)

        r.convoId.value = "c2"
        r.receiveForTest(Transcript("c1", a, text = "belongs to c1", ok = true))
        assertNull(r.pendingVoiceText.value, "a transcript dictated in c1 must not reach c2's composer")
    }

    @Test
    fun leavingTheChatEndsTheWait() {
        val r = repo()
        val a = r.dictate()
        advance(PocketRepository.TRANSCRIBE_TIMEOUT_MS + 1)

        r.backToBrowse()
        r.convoId.value = "c1" // the same chat, opened again
        r.receiveForTest(Transcript("c1", a, text = "from before", ok = true))
        assertNull(r.pendingVoiceText.value, "leaving the chat abandoned the capture")
        assertTrue(r.voice.value is VoiceState.Idle)
        assertTrue(!r.voiceUploading.value)
    }

    @Test
    fun aLateTranscriptNeverLandsInACaptureStartedAfterACancel() {
        val r = repo()
        val a = r.dictate()
        advance(PocketRepository.TRANSCRIBE_TIMEOUT_MS + 1)
        r.cancelVoice()
        scheduler.runCurrent()
        assertTrue(r.voice.value is VoiceState.Idle)

        val b = r.dictate() // a new recording, sent afresh
        r.receiveForTest(Transcript("c1", a, text = "the cancelled one", ok = true))
        assertNull(r.pendingVoiceText.value, "the cancelled capture's transcript is dropped")
        assertEquals(VoiceState.Transcribing, r.voice.value, "and the new capture keeps waiting")

        r.receiveForTest(Transcript("c1", b, text = "the new one", ok = true))
        assertEquals("the new one", r.pendingVoiceText.value)
    }

    @Test
    fun sendingAMessageDismissesTheWait() {
        val r = repo()
        val a = r.dictate()
        advance(PocketRepository.TRANSCRIBE_TIMEOUT_MS + 1)
        r.sendPrompt("typed instead")
        assertTrue(r.voice.value is VoiceState.Idle, "sending dismisses the wait, as it dismisses the failure chip")
        r.receiveForTest(Transcript("c1", a, text = "late", ok = true))
        assertNull(r.pendingVoiceText.value, "after the user sent something else the late transcript stays out of the composer")
    }
}
