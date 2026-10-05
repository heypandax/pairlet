package dev.ccpocket.daemon.transcribe

import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.AudioCancel
import dev.ccpocket.protocol.AudioChunk
import dev.ccpocket.protocol.Transcript
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Voice-capture orchestration: buffers [AudioChunk]s, runs whisper off the router's path, and
 * replies [Transcript] on the sink the chunks arrived on (so results only reach the device that
 * spoke). Transcription never touches the claude process — it runs concurrently with a turn.
 *
 * One transcription per conversation at a time. A capture whose audio is byte-identical to the one being
 * transcribed — the phone's retry re-sends the same recording under a fresh captureId — does not replace it:
 * it waits for the same run and every captureId gets its own [Transcript] (2026-10-05: the retry used to
 * cancel the first run, and both ran to completion anyway, since the whisper call cannot be interrupted).
 * The same holds for a short while after a run finished: [RESULT_REUSE_MS]. A capture with different audio
 * still supersedes the running one, whose captureIds are now answered with a failure instead of nothing.
 */
class TranscribeService internal constructor(
    private val scope: CoroutineScope,
    /** Turns one complete capture into its [Transcript] reply. Production runs whisper; a test hands in a fake. */
    private val transcriber: suspend (convoId: String, capture: CaptureBuffer.Result.Complete, workdir: Path) -> Transcript,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val workdirOf: suspend (String) -> Path?,
) {
    constructor(scope: CoroutineScope, workdirOf: suspend (String) -> Path?) : this(scope, ::whisperTranscript, workdirOf = workdirOf)

    /** A captureId waiting on a run, and the sink its chunks arrived on. */
    private class Waiter(val captureId: String, val sink: OutboundSink)

    /** The conversation's transcription in flight: its audio's digest and every capture waiting on it. */
    private class Run(val digest: String) {
        lateinit var job: Job
        val waiters = ArrayList<Waiter>()
    }

    /** The conversation's last successful transcription, for a re-send of the same audio. Memory only. */
    private class Recent(val digest: String, val reply: Transcript, val atMs: Long)

    private val log = logger("Transcribe")
    private val buffer = CaptureBuffer()
    private val lock = Any()
    private val runs = HashMap<String, Run>()       // convoId -> transcription in flight; guarded by [lock]
    private val recent = HashMap<String, Recent>()  // convoId -> last successful result; guarded by [lock]

    suspend fun onChunk(f: AudioChunk, sink: OutboundSink) {
        when (val r = buffer.add(f)) {
            is CaptureBuffer.Result.Stale -> {}
            is CaptureBuffer.Result.Incomplete -> if (r.evicted != null) answerSuperseded(f.convoId, supersede(f.convoId))
            is CaptureBuffer.Result.Invalid ->
                sink.emit(Transcript(f.convoId, r.captureId, ok = false, error = "audio arrived corrupted — try again"))
            is CaptureBuffer.Result.Complete -> onComplete(f.convoId, r, sink)
        }
    }

    suspend fun onCancel(f: AudioCancel) {
        buffer.cancel(f.convoId, f.captureId)
        // the phone cancelled: nobody is waiting for an answer, so none is sent
        val cancelled = synchronized(lock) { runs.remove(f.convoId) } ?: return
        cancelled.job.cancel()
        log.info("${f.convoId} transcription cancelled")
    }

    private suspend fun onComplete(convoId: String, c: CaptureBuffer.Result.Complete, sink: OutboundSink) {
        val digest = digestOf(c.bytes)
        val workdir = workdirOf(convoId)
        val now = nowMs()
        var reuse: Transcript? = null
        var superseded: List<Waiter> = emptyList()
        var started: Run? = null
        // one critical section from lookup to install: two captures of the same audio completing at once
        // (two devices) must end up sharing one run, never superseding each other
        synchronized(lock) {
            recent.values.removeIf { now - it.atMs > RESULT_REUSE_MS }
            reuse = recent[convoId]?.takeIf { it.digest == digest }?.reply
            val running = runs[convoId]
            when {
                // a re-send that found a finished result leaves whatever is running alone
                reuse != null -> {}
                running != null && running.digest == digest -> {
                    running.waiters += Waiter(c.captureId, sink)
                    log.info("$convoId capture re-sent while transcribing — sharing the running transcription")
                    return
                }
                else -> {
                    superseded = supersedeLocked(convoId) // a different recording replaces the running one
                    if (workdir != null) started = installRunLocked(convoId, c, digest, workdir, sink)
                }
            }
        }
        reuse?.let {
            log.info("$convoId capture re-sent after transcribing — reusing the result")
            sink.emit(it.copy(captureId = c.captureId))
            return
        }
        answerSuperseded(convoId, superseded)
        val run = started
        if (run == null) {
            sink.emit(Transcript(convoId, c.captureId, ok = false, error = "session not live"))
            return
        }
        run.job.start()
    }

    /** Register [c]'s transcription as the conversation's run; it starts once the caller has left the lock. */
    private fun installRunLocked(convoId: String, c: CaptureBuffer.Result.Complete, digest: String, workdir: Path, sink: OutboundSink): Run {
        val run = Run(digest)
        run.waiters += Waiter(c.captureId, sink)
        run.job = scope.launch(Dispatchers.IO + CoroutineName("whisper-$convoId"), start = CoroutineStart.LAZY) {
            val reply = transcriber(convoId, c, workdir)
            val waiters = synchronized(lock) {
                // superseded or cancelled while whisper ran (the call cannot be interrupted): whoever removed this
                // run already settled its captures
                if (runs[convoId] !== run) return@launch
                runs.remove(convoId)
                if (reply.ok) recent[convoId] = Recent(digest, reply, nowMs())
                run.waiters.toList()
            }
            waiters.forEach { it.sink.emit(reply.copy(captureId = it.captureId)) }
        }
        run.job.invokeOnCompletion { synchronized(lock) { if (runs[convoId] === run) runs.remove(convoId) } }
        runs[convoId] = run
        return run
    }

    /** True while any dictation capture is being transcribed (the auto-update idle gate reads this). */
    fun isTranscribing(): Boolean = synchronized(lock) { runs.isNotEmpty() }

    private fun supersede(convoId: String): List<Waiter> = synchronized(lock) { supersedeLocked(convoId) }

    /** Retire the conversation's running transcription in favour of a different capture; returns who waited on it. */
    private fun supersedeLocked(convoId: String): List<Waiter> {
        val old = runs.remove(convoId) ?: return emptyList()
        old.job.cancel()
        log.info("$convoId transcription cancelled")
        return old.waiters.toList()
    }

    /** Before 2026-10-05 a superseded capture was answered with nothing and its phone waited out its timeout. */
    private suspend fun answerSuperseded(convoId: String, waiters: List<Waiter>) {
        waiters.forEach { it.sink.emit(Transcript(convoId, it.captureId, ok = false, error = MSG_SUPERSEDED)) }
    }

    private fun digestOf(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        /** How long a finished result answers a re-send of the same audio without running whisper again. */
        const val RESULT_REUSE_MS = 30_000L

        /** The reply to a capture another recording in the same conversation replaced. An app of any version shows
         *  it as an ordinary, retryable failure (it matches none of the setup-diagnostic prefixes). */
        const val MSG_SUPERSEDED = "a newer recording replaced this one — try again"
    }
}

private suspend fun whisperTranscript(convoId: String, c: CaptureBuffer.Result.Complete, workdir: Path): Transcript {
    val whisper = WhisperTranscriber.resolveWhisper()
    val model = if (whisper != null) WhisperTranscriber.resolveModel() else null
    return when {
        whisper == null -> Transcript(convoId, c.captureId, ok = false, error = WhisperTranscriber.MSG_INSTALL)
        model == null -> Transcript(convoId, c.captureId, ok = false, error = WhisperTranscriber.MSG_MODEL)
        else -> when (val res = WhisperTranscriber.transcribe(c.bytes, c.mediaType, workdir, whisper, model)) {
            is WhisperTranscriber.TranscribeResult.Ok -> Transcript(convoId, c.captureId, text = res.text)
            is WhisperTranscriber.TranscribeResult.Err -> Transcript(convoId, c.captureId, ok = false, error = res.userMessage)
        }
    }
}
