package dev.ccpocket.daemon.transcribe

import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.protocol.AudioCancel
import dev.ccpocket.protocol.AudioChunk
import dev.ccpocket.protocol.Transcript
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Dictation's one-run-per-conversation rule (2026-10-05): the phone's retry re-sends the SAME recording under a
 * fresh captureId, which used to cancel the first run (that kept running anyway — the whisper call cannot be
 * interrupted) and transcribe the audio a second time. A fake transcriber with a controllable duration stands
 * in for whisper; nothing here starts a whisper process.
 */
class TranscribeServiceTest {

    private val scope = CoroutineScope(SupervisorJob())
    private var now = 1_000L
    private val calls = AtomicInteger()
    private var gate = CompletableDeferred<Unit>()
    private var outcome: (CaptureBuffer.Result.Complete) -> Transcript = { Transcript("", it.captureId, text = "hello there") }

    private val service = TranscribeService(
        scope,
        { convoId, c, _ ->
            calls.incrementAndGet()
            gate.await()
            outcome(c).copy(convoId = convoId, captureId = c.captureId)
        },
        nowMs = { now },
        workdirOf = { Path.of(System.getProperty("java.io.tmpdir")) },
    )

    private val replies = Channel<Transcript>(Channel.UNLIMITED)
    private val sink = OutboundSink { replies.send(it as Transcript) }

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun audio(seed: Int) = Base64.getEncoder().encodeToString(ByteArray(256) { (it * seed).toByte() })

    private suspend fun capture(convo: String, id: String, seed: Int = 7) =
        service.onChunk(AudioChunk(convo, id, 0, last = true, mediaType = "audio/mp4", base64 = audio(seed)), sink)

    private suspend fun reply(): Transcript = withTimeout(5_000) { replies.receive() }
    private suspend fun noReply(): Transcript? = withTimeoutOrNull(200) { replies.receive() }
    private suspend fun idle() = withTimeout(5_000) { while (service.isTranscribing()) delay(5) }

    @Test
    fun aReSendOfTheSameAudioSharesTheRunningTranscription() = runBlocking {
        capture("c-1", "cap-a")
        capture("c-1", "cap-b") // the retry: same recording, fresh captureId
        assertNull(noReply(), "the first run is not cancelled, so nothing is answered before it finishes")
        assertTrue(service.isTranscribing())

        gate.complete(Unit)
        val got = listOf(reply(), reply()).associateBy { it.captureId }
        assertEquals(setOf("cap-a", "cap-b"), got.keys, "each captureId gets its own transcript")
        assertTrue(got.values.all { it.ok && it.text == "hello there" && it.convoId == "c-1" })
        assertEquals(1, calls.get(), "whisper ran once for both")
        idle()
    }

    @Test
    fun aReSendShortlyAfterTheRunFinishedReusesItsResult() = runBlocking {
        gate.complete(Unit)
        capture("c-1", "cap-a")
        assertEquals("cap-a", reply().captureId)
        idle()

        now += TranscribeService.RESULT_REUSE_MS
        capture("c-1", "cap-b")
        val reused = reply()
        assertEquals("cap-b", reused.captureId)
        assertTrue(reused.ok && reused.text == "hello there")
        assertEquals(1, calls.get(), "within the window the result is reused, whisper does not run")
        assertFalse(service.isTranscribing())

        now += 1 // past the window, counted from the run's completion
        capture("c-1", "cap-c")
        assertEquals("cap-c", reply().captureId)
        assertEquals(2, calls.get(), "past the window the audio is transcribed again")
    }

    @Test
    fun aFailedResultIsNotReused() = runBlocking {
        gate.complete(Unit)
        outcome = { Transcript("", it.captureId, ok = false, error = "transcription timed out") }
        capture("c-1", "cap-a")
        assertFalse(reply().ok)

        outcome = { Transcript("", it.captureId, text = "second time lucky") }
        capture("c-1", "cap-b")
        assertEquals("second time lucky", reply().text, "a retry after a failure really retries")
        assertEquals(2, calls.get())
    }

    @Test
    fun differentAudioSupersedesTheRunningOneWhichIsAnsweredWithAFailure() = runBlocking {
        outcome = { Transcript("", it.captureId, text = "text of ${it.captureId}") }
        capture("c-1", "cap-a", seed = 3)
        capture("c-1", "cap-b", seed = 5) // a different recording

        val superseded = reply()
        assertEquals("cap-a", superseded.captureId, "the replaced capture is answered at once instead of left waiting")
        assertFalse(superseded.ok)
        assertEquals(TranscribeService.MSG_SUPERSEDED, superseded.error)

        gate.complete(Unit) // both whisper calls finish; only the current run may answer
        val current = reply()
        assertEquals("cap-b", current.captureId)
        assertEquals("text of cap-b", current.text)
        assertNull(noReply(), "the superseded run's late result is never sent")
        assertEquals(2, calls.get())
    }

    @Test
    fun conversationsNeverShareARunOrAResult() = runBlocking {
        capture("c-1", "cap-a")
        capture("c-2", "cap-b") // byte-identical audio, another conversation
        gate.complete(Unit)
        val got = listOf(reply(), reply()).associateBy { it.captureId }
        assertEquals("c-1", got.getValue("cap-a").convoId)
        assertEquals("c-2", got.getValue("cap-b").convoId)
        assertEquals(2, calls.get(), "each conversation transcribes its own capture")
        idle()

        capture("c-3", "cap-c") // c-1's finished result answers only c-1
        assertEquals("cap-c", reply().captureId)
        assertEquals(3, calls.get())
    }

    @Test
    fun aCancelledCaptureIsStillAnsweredWithNothing() = runBlocking {
        capture("c-1", "cap-a")
        assertTrue(service.isTranscribing())
        service.onCancel(AudioCancel("c-1", "cap-a"))
        assertFalse(service.isTranscribing(), "the update gate is released as before")
        gate.complete(Unit)
        assertNull(noReply(), "the phone cancelled: no answer is owed")
    }
}
