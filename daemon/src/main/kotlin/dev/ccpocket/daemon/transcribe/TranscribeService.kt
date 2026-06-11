package dev.ccpocket.daemon.transcribe

import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.AudioCancel
import dev.ccpocket.protocol.AudioChunk
import dev.ccpocket.protocol.Transcript
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Voice-capture orchestration: buffers [AudioChunk]s, runs whisper off the router's path, and
 * replies [Transcript] on the sink the chunks arrived on (so results only reach the device that
 * spoke). Transcription never touches the claude process — it runs concurrently with a turn.
 */
class TranscribeService(
    private val scope: CoroutineScope,
    private val workdirOf: suspend (String) -> Path?,
) {
    private val log = logger("Transcribe")
    private val buffer = CaptureBuffer()
    private val jobs = ConcurrentHashMap<String, Job>() // convoId -> running transcription

    suspend fun onChunk(f: AudioChunk, sink: OutboundSink) {
        when (val r = buffer.add(f)) {
            is CaptureBuffer.Result.Stale -> {}
            is CaptureBuffer.Result.Incomplete -> if (r.evicted != null) cancelJob(f.convoId)
            is CaptureBuffer.Result.Invalid ->
                sink.emit(Transcript(f.convoId, r.captureId, ok = false, error = "audio arrived corrupted — try again"))
            is CaptureBuffer.Result.Complete -> {
                cancelJob(f.convoId) // a fresh capture supersedes any transcription still running
                val workdir = workdirOf(f.convoId)
                if (workdir == null) {
                    sink.emit(Transcript(f.convoId, r.captureId, ok = false, error = "session not live"))
                    return
                }
                launchTranscription(f.convoId, r, workdir, sink)
            }
        }
    }

    suspend fun onCancel(f: AudioCancel) {
        buffer.cancel(f.convoId, f.captureId)
        cancelJob(f.convoId)
    }

    private fun launchTranscription(convoId: String, c: CaptureBuffer.Result.Complete, workdir: Path, sink: OutboundSink) {
        val job = scope.launch(Dispatchers.IO + CoroutineName("whisper-$convoId")) {
            val whisper = WhisperTranscriber.resolveWhisper()
            val model = if (whisper != null) WhisperTranscriber.resolveModel() else null
            val frame = when {
                whisper == null -> Transcript(convoId, c.captureId, ok = false, error = WhisperTranscriber.MSG_INSTALL)
                model == null -> Transcript(convoId, c.captureId, ok = false, error = WhisperTranscriber.MSG_MODEL)
                else -> when (val res = WhisperTranscriber.transcribe(c.bytes, c.mediaType, workdir, whisper, model)) {
                    is WhisperTranscriber.TranscribeResult.Ok -> Transcript(convoId, c.captureId, text = res.text)
                    is WhisperTranscriber.TranscribeResult.Err -> Transcript(convoId, c.captureId, ok = false, error = res.userMessage)
                }
            }
            sink.emit(frame)
        }
        jobs[convoId] = job
        job.invokeOnCompletion { jobs.remove(convoId, job) }
    }

    private fun cancelJob(convoId: String) {
        jobs.remove(convoId)?.let {
            it.cancel()
            log.info("$convoId transcription cancelled")
        }
    }
}
