package dev.ccpocket.daemon.memo

import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.ToDaemon
import dev.ccpocket.protocol.VOICE_MEMO_AGENT_NONE
import dev.ccpocket.protocol.VoiceMemoAudio
import dev.ccpocket.protocol.VoiceMemoCancel
import dev.ccpocket.protocol.VoiceMemoError
import dev.ccpocket.protocol.VoiceMemoGet
import dev.ccpocket.protocol.VoiceMemoHash
import dev.ccpocket.protocol.VoiceMemoInputKind
import dev.ccpocket.protocol.VoiceMemoLimits
import dev.ccpocket.protocol.VoiceMemoMetrics
import dev.ccpocket.protocol.VoiceMemoStage
import dev.ccpocket.protocol.VoiceMemoStart
import dev.ccpocket.protocol.VoiceMemoState
import dev.ccpocket.protocol.VoiceMemoStatus
import dev.ccpocket.protocol.VoiceMemoValidation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/** Built ONLY by an authenticated transport — never from a frame field. */
data class MemoOwner(val deviceId: String)

/** Where a job's snapshots go. The transport builds it per connection and re-checks its own caps / revocation
 *  on every send; returns false when the frame could not be delivered (the job and its cached result survive). */
fun interface MemoReplyTarget { suspend fun send(state: dev.ccpocket.protocol.VoiceMemoState): Boolean }

data class MemoCapability(val version: Int, val agents: List<String>, val status: String)

/** Test overrides; production uses the protocol's fixed v1 limits. */
data class MemoServiceLimits(
    val registry: MemoRegistryLimits = MemoRegistryLimits(),
    val slotWaitMs: Long = VoiceMemoLimits.SLOT_WAIT_MS,
    val transcribeDeadlineMs: Long = VoiceMemoLimits.TRANSCRIBE_DEADLINE_MS,
    val summarizeDeadlineMs: Long = VoiceMemoLimits.SUMMARIZE_DEADLINE_MS,
    /** Housekeeping tick (upload idle / TTL); 0 disables the ticker (tests drive [VoiceMemoService.sweepNow]). */
    val sweepIntervalMs: Long = 5_000,
)

/**
 * Daemon entry for voice memo → tasks (code design §6). Owns the job registry, one whisper slot and the
 * workers; the transport hands in authenticated frames and a per-connection [MemoReplyTarget].
 *
 * Flow: receiving → queued → transcribing → summarizing → ready / degraded / failed / cancelled. A
 * transcript-input start begins at summarizing. The transcript is pushed as soon as it exists (summarizing
 * snapshot) and repeated in the final ready/degraded one. Nothing here dispatches a task.
 *
 * Organisers are pluggable ([MemoSummarizers]) and optional: transcription never depends on one. An attempt
 * that asked for [VOICE_MEMO_AGENT_NONE] ends in `transcribed`; so does one whose organiser can no longer
 * launch when its transcript is ready (errorCode agent_unavailable, retryable) — the daemon never substitutes
 * a different organiser for the one the phone chose.
 *
 * Every frame is answered on the caller's target and re-binds the job to it, so a reconnect's `get` moves
 * later pushes to the new connection. Delivery happens outside the registry lock; a failed delivery is a
 * content-free log line, never a cancelled job. Errors reach the phone only as fixed codes.
 */
class VoiceMemoService(
    scope: CoroutineScope,
    private val transcriber: MemoTranscriber,
    private val summarizers: MemoSummarizers,
    private val clock: MemoClock = MemoClock.System,
    private val limits: MemoServiceLimits = MemoServiceLimits(),
) {
    /** One organiser adapter, wrapped into a single-entry [MemoSummarizers]. */
    constructor(
        scope: CoroutineScope,
        transcriber: MemoTranscriber,
        summarizer: MemoSummarizer,
        clock: MemoClock = MemoClock.System,
        limits: MemoServiceLimits = MemoServiceLimits(),
    ) : this(scope, transcriber, MemoSummarizers(listOf(summarizer)), clock, limits)

    private val log = logger("VoiceMemo")
    private val supervisor = SupervisorJob(scope.coroutineContext[Job])
    private val workers = CoroutineScope(scope.coroutineContext + supervisor)
    private val transcribeSlot = Semaphore(1)
    private val registry = MemoJobRegistry(clock, limits.registry) { key, generation, work ->
        workers.launch(start = CoroutineStart.LAZY) { runWorker(key, generation, work) }
    }

    init {
        if (limits.sweepIntervalMs > 0) {
            workers.launch {
                while (isActive) {
                    delay(limits.sweepIntervalMs)
                    deliver(registry.sweep())
                }
            }
        }
    }

    /**
     * LOCAL prerequisites only — file and binary checks; never launches a model or downloads anything.
     * [MemoCapability.status] describes TRANSCRIPTION alone; [MemoCapability.agents] lists the organisers that
     * can launch now, in preference order, and may be empty while the status is still ready.
     */
    fun capability(): MemoCapability = MemoCapability(
        version = VoiceMemoLimits.VERSION,
        agents = advertisedAgents(),
        status = transcriptionStatus(),
    )

    private fun advertisedAgents(): List<String> = runCatching { summarizers.available() }.getOrDefault(emptyList())

    private fun transcriptionStatus(): String =
        runCatching { transcriber.localStatus() }.getOrDefault(VoiceMemoStatus.UNKNOWN)

    /** [frame] is one of VoiceMemoStart / VoiceMemoAudio / VoiceMemoGet / VoiceMemoCancel; anything else is ignored. */
    suspend fun handle(owner: MemoOwner, frame: ToDaemon, reply: MemoReplyTarget) {
        if (owner.deviceId.isBlank()) return
        when (frame) {
            is VoiceMemoStart -> onStart(owner, frame, reply)
            is VoiceMemoAudio -> onAudio(owner, frame, reply)
            is VoiceMemoGet -> {
                val key = keyOrNull(owner, frame.memoId, frame.attemptId) ?: return
                deliver(registry.get(key, reply).pushes)
            }
            is VoiceMemoCancel -> {
                val key = keyOrNull(owner, frame.memoId, frame.attemptId) ?: return
                deliver(registry.cancel(key, reply).pushes)
            }
            else -> Unit
        }
    }

    /** Pairing revoked: cancel that device's jobs and drop its cached results and reply targets. */
    suspend fun revokeDevice(deviceId: String) {
        val n = registry.revokeDevice(deviceId)
        if (n > 0) log.info("memo revoke dropped $n job(s)")
    }

    suspend fun close() {
        registry.closeAll()
        supervisor.cancelAndJoin()
    }

    /** Test hook: run the idle-upload / TTL housekeeping now. */
    internal suspend fun sweepNow() = deliver(registry.sweep())

    internal suspend fun peek(owner: MemoOwner, memoId: String, attemptId: String): VoiceMemoState? =
        registry.peek(MemoJobKey(owner.deviceId, memoId, attemptId))

    internal suspend fun activeJobs(): Int = registry.activeJobs()

    internal suspend fun cachedTerminalJobs(): Int = registry.cachedTerminalJobs()

    // ── frames ────────────────────────────────────────────────────────────

    /** Invalid IDs are never written anywhere nor echoed back: there is no well-formed key to answer on. */
    private fun keyOrNull(owner: MemoOwner, memoId: String, attemptId: String): MemoJobKey? {
        if (VoiceMemoValidation.validateIds(memoId, attemptId) != null) {
            log.info("memo frame dropped: malformed id")
            return null
        }
        return MemoJobKey(owner.deviceId, memoId, attemptId)
    }

    private suspend fun onStart(owner: MemoOwner, start: VoiceMemoStart, reply: MemoReplyTarget) {
        val key = keyOrNull(owner, start.memoId, start.attemptId) ?: return
        gate()?.let { return refuse(reply, key, it) }
        // the organiser must be one this daemon can launch now, or "none"; WHICH one is the phone's choice
        val advertised = if (start.agent == VOICE_MEMO_AGENT_NONE) emptyList() else advertisedAgents()
        VoiceMemoValidation.validateStart(start, advertised)?.let { return refuse(reply, key, it) }
        if (start.inputKind == VoiceMemoInputKind.TRANSCRIPT) {
            val text = start.transcript ?: return refuse(reply, key, VoiceMemoError.INVALID_INPUT)
            if (VoiceMemoHash.sha256Hex(text.encodeToByteArray()) != start.sha256) return refuse(reply, key, VoiceMemoError.INVALID_INPUT)
        }
        val outcome = registry.start(key, start, reply)
        // the snapshot goes out before the worker can race ahead of it; the worker starts even if delivery
        // is cancelled, or the job would sit in queued holding an active slot
        try {
            deliver(outcome.pushes)
        } finally {
            outcome.launch?.start()
        }
    }

    private suspend fun onAudio(owner: MemoOwner, chunk: VoiceMemoAudio, reply: MemoReplyTarget) {
        val key = keyOrNull(owner, chunk.memoId, chunk.attemptId) ?: return
        gate()?.let { return refuse(reply, key, it) }
        val outcome = registry.audio(key, chunk.index, chunk.base64, reply)
        // the snapshot goes out before the worker can race ahead of it; the worker starts even if delivery
        // is cancelled, or the job would sit in queued holding an active slot
        try {
            deliver(outcome.pushes)
        } finally {
            outcome.launch?.start()
        }
    }

    /** start/audio need TRANSCRIPTION to be usable right now (the organiser is checked per start by
     *  [VoiceMemoValidation.validateStart]); get/cancel never do. */
    private fun gate(): String? =
        if (transcriptionStatus() == VoiceMemoStatus.READY) null else VoiceMemoError.NOT_READY

    private suspend fun refuse(reply: MemoReplyTarget, key: MemoJobKey, code: String) {
        log.info("memo start/chunk refused code=$code")
        deliver(listOf(MemoPush(reply, memoRejection(key.memoId, key.attemptId, code))))
    }

    private suspend fun deliver(pushes: List<MemoPush>) {
        for (p in pushes) {
            val ok = try {
                p.target.send(p.state)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (!ok) log.info("memo state undelivered stage=${p.state.stage} rev=${p.state.revision}")
        }
    }

    // ── worker ────────────────────────────────────────────────────────────

    private fun elapsedMs(since: Long): Long = ((clock.monoNanos() - since) / 1_000_000).coerceAtLeast(0)

    /** Applies a transition and delivers it; false = the job was cancelled / revoked / closed — stop. */
    private suspend fun advance(key: MemoJobKey, generation: Long, edit: MemoJobRecord.() -> Unit): Boolean {
        val pushes = registry.update(key, generation, edit) ?: return false
        deliver(pushes)
        return true
    }

    /** A finished transcription, not yet recorded: [organise] records it together with where the job goes. */
    private class Transcribed(val text: String, val transcribeMs: Long?, val audioDurationMs: Long?)

    private fun VoiceMemoMetrics.with(t: Transcribed): VoiceMemoMetrics = copy(
        transcribeMs = t.transcribeMs ?: transcribeMs,
        audioDurationMs = t.audioDurationMs ?: audioDurationMs,
    )

    private suspend fun runWorker(key: MemoJobKey, generation: Long, work: MemoWork) {
        var transcribed: Transcribed? = null
        try {
            val t = when (work) {
                is MemoWork.Transcript -> Transcribed(work.text, transcribeMs = null, audioDurationMs = null)
                is MemoWork.Audio -> transcribe(key, generation, work) ?: return
            }
            transcribed = t
            organise(key, generation, work, t)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("memo worker error: ${e::class.simpleName}")
            val done = transcribed
            advance(key, generation) {
                if (done != null) {
                    // a transcript in hand survives an organiser crash as a degraded result
                    transcript = done.text
                    metrics = metrics.with(done)
                    stage = VoiceMemoStage.DEGRADED
                    errorCode = VoiceMemoError.SUMMARY_FAILED
                } else {
                    stage = VoiceMemoStage.FAILED
                    errorCode = VoiceMemoError.TRANSCRIBE_FAILED
                }
            }
        }
    }

    /** The transcript, or null when the job ended here (failure already recorded, or the job went away).
     *  A success is NOT recorded here — [organise] records it together with where the job goes next. */
    private suspend fun transcribe(key: MemoJobKey, generation: Long, work: MemoWork.Audio): Transcribed? {
        try {
            // A timeout that fires just as acquire() succeeds still reports null — the permit would then be
            // held by nobody and the one slot gone until restart. The flag is what says who really has it.
            var held = false
            val inTime = withTimeoutOrNull(limits.slotWaitMs) { transcribeSlot.acquire(); held = true } != null
            if (held && !inTime) { transcribeSlot.release(); held = false }
            val acquired = held
            val queueMs = elapsedMs(work.queuedAtNanos)
            if (!acquired) {
                advance(key, generation) {
                    stage = VoiceMemoStage.FAILED
                    errorCode = VoiceMemoError.BUSY
                    metrics = metrics.copy(queueMs = queueMs)
                }
                return null
            }
            val result: MemoTranscribeResult
            val transcribeMs: Long
            try {
                if (!advance(key, generation) {
                        stage = VoiceMemoStage.TRANSCRIBING
                        metrics = metrics.copy(queueMs = queueMs)
                    }
                ) return null
                val t0 = clock.monoNanos()
                result = transcriber.transcribe(work.audio, MemoDeadline.afterMs(limits.transcribeDeadlineMs, clock::monoNanos))
                transcribeMs = elapsedMs(t0)
            } finally {
                transcribeSlot.release()
            }
            return when (result) {
                is MemoTranscribeResult.Failed -> {
                    advance(key, generation) {
                        stage = VoiceMemoStage.FAILED
                        errorCode = result.errorCode
                        metrics = metrics.copy(transcribeMs = transcribeMs, audioDurationMs = result.audioDurationMs)
                    }
                    null
                }
                is MemoTranscribeResult.Ok -> {
                    val invalid = VoiceMemoValidation.validateTranscript(result.transcript)
                    if (invalid != null) {
                        advance(key, generation) {
                            stage = VoiceMemoStage.FAILED
                            errorCode = invalid
                            metrics = metrics.copy(transcribeMs = transcribeMs, audioDurationMs = result.audioDurationMs)
                        }
                        null
                    } else {
                        Transcribed(result.transcript, transcribeMs, result.audioDurationMs)
                    }
                }
            }
        } finally {
            work.audio.release()
        }
    }

    /**
     * Where a transcript goes. Transcribe-only ([VOICE_MEMO_AGENT_NONE]) and an organiser that can no longer
     * launch both end in `transcribed` — the second with agent_unavailable (retryable), so the phone can offer
     * to organise it later with whatever is advertised then. Otherwise the transcript is pushed first
     * (summarizing) and then the organiser the phone chose runs.
     */
    private suspend fun organise(key: MemoJobKey, generation: Long, work: MemoWork, t: Transcribed) {
        val adapter = if (work.agent == VOICE_MEMO_AGENT_NONE) null else summarizers.forAgent(work.agent)
        if (adapter == null || !runCatching { adapter.isAvailable() }.getOrDefault(false)) {
            advance(key, generation) {
                transcript = t.text
                metrics = metrics.with(t)
                stage = VoiceMemoStage.TRANSCRIBED
                errorCode = if (work.agent == VOICE_MEMO_AGENT_NONE) null else VoiceMemoError.AGENT_UNAVAILABLE
            }
            return
        }
        val stillOurs = advance(key, generation) {
            transcript = t.text
            metrics = metrics.with(t)
            stage = VoiceMemoStage.SUMMARIZING
        }
        if (!stillOurs) return
        val t0 = clock.monoNanos()
        val r = adapter.summarize(t.text, work.locale, MemoDeadline.afterMs(limits.summarizeDeadlineMs, clock::monoNanos))
        val summarizeMs = elapsedMs(t0)
        advance(key, generation) {
            metrics = metrics.copy(summarizeMs = summarizeMs)
            if (r is MemoSummaryResult.Ok) {
                stage = VoiceMemoStage.READY
                result = r.result
                errorCode = null
            } else {
                // no trustworthy organisation: the transcript alone, with the reason
                stage = VoiceMemoStage.DEGRADED
                errorCode = r.errorCode
            }
        }
    }
}
