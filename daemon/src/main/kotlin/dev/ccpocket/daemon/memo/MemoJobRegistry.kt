package dev.ccpocket.daemon.memo

import dev.ccpocket.protocol.VoiceMemoError
import dev.ccpocket.protocol.VoiceMemoInputKind
import dev.ccpocket.protocol.VoiceMemoLimits
import dev.ccpocket.protocol.VoiceMemoMetrics
import dev.ccpocket.protocol.VoiceMemoResult
import dev.ccpocket.protocol.VoiceMemoStage
import dev.ccpocket.protocol.VoiceMemoStart
import dev.ccpocket.protocol.VoiceMemoState
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Wall time (only for [VoiceMemoState.expiresAtMs]) and a monotonic clock (everything that measures or
 *  expires). Injected so TTL / idle / metrics tests don't sleep. */
interface MemoClock {
    fun wallMs(): Long
    fun monoNanos(): Long

    companion object {
        val System: MemoClock = object : MemoClock {
            override fun wallMs(): Long = java.lang.System.currentTimeMillis()
            override fun monoNanos(): Long = java.lang.System.nanoTime()
        }
    }
}

/** An absolute monotonic deadline; every step asks for what is LEFT instead of getting its own allowance. */
class MemoDeadline(private val endNanos: Long, private val nanoTime: () -> Long = { java.lang.System.nanoTime() }) {
    fun remainingMs(): Long = ((endNanos - nanoTime()) / 1_000_000).coerceAtLeast(0)

    companion object {
        fun afterMs(ms: Long, nanoTime: () -> Long = { java.lang.System.nanoTime() }): MemoDeadline =
            MemoDeadline(nanoTime() + ms * 1_000_000, nanoTime)
    }
}

/** Server-side identity of one attempt. [deviceId] comes from the authenticated transport, never a frame. */
data class MemoJobKey(val deviceId: String, val memoId: String, val attemptId: String)

/** Everything a start frame says about its input: a repeated start with the same fingerprint is the same
 *  request; a different one under the same key is [VoiceMemoError.INPUT_CONFLICT]. */
data class MemoFingerprint(
    val inputKind: String,
    val sha256: String,
    val byteLength: Long?,
    val durationMs: Long?,
    val mediaType: String?,
    val chunkCount: Int?,
    val agent: String,
    val model: String?,
    val locale: String?,
) {
    companion object {
        fun of(s: VoiceMemoStart) =
            MemoFingerprint(s.inputKind, s.sha256, s.byteLength, s.durationMs, s.mediaType, s.chunkCount, s.agent, s.model, s.locale)
    }
}

/** What a worker is handed when its job leaves the registry's hands. */
sealed interface MemoWork {
    /** The organiser the phone asked for, or [dev.ccpocket.protocol.VOICE_MEMO_AGENT_NONE] (transcribe only). */
    val agent: String
    val locale: String?

    class Audio(val audio: MemoAudio, override val agent: String, override val locale: String?, val queuedAtNanos: Long) : MemoWork
    class Transcript(val text: String, override val agent: String, override val locale: String?) : MemoWork
}

/** One state frame to deliver OUTSIDE the registry lock. */
class MemoPush(val target: MemoReplyTarget, val state: VoiceMemoState)

class MemoOutcome(val pushes: List<MemoPush>, val launch: Job? = null)

data class MemoRegistryLimits(
    val maxActivePerDevice: Int = 1,
    val maxActiveTotal: Int = VoiceMemoLimits.MAX_ACTIVE_JOBS,
    val maxCachedJobs: Int = VoiceMemoLimits.MAX_CACHED_JOBS,
    val resultTtlMs: Long = VoiceMemoLimits.RESULT_TTL_MS,
    val uploadIdleMs: Long = VoiceMemoLimits.UPLOAD_IDLE_MS,
)

internal object MemoRetry {
    private val RETRYABLE = setOf(
        VoiceMemoError.UPLOAD_TIMEOUT,
        VoiceMemoError.TRANSCRIBE_FAILED,
        VoiceMemoError.TRANSCRIBE_TIMEOUT,
        VoiceMemoError.SUMMARY_FAILED,
        VoiceMemoError.SUMMARY_TIMEOUT,
        VoiceMemoError.BUSY,
        VoiceMemoError.AGENT_UNAVAILABLE,
    )

    fun retryable(code: String?): Boolean = code != null && code in RETRYABLE
}

/** A refusal that never touched the registry: revision 0, so it can't outrank any snapshot the phone holds. */
internal fun memoRejection(memoId: String, attemptId: String, code: String) = VoiceMemoState(
    memoId = memoId,
    attemptId = attemptId,
    revision = 0,
    stage = VoiceMemoStage.FAILED,
    errorCode = code,
    retryable = MemoRetry.retryable(code),
)

internal fun memoUnknown(memoId: String, attemptId: String) = VoiceMemoState(
    memoId = memoId,
    attemptId = attemptId,
    revision = 0,
    stage = VoiceMemoStage.UNKNOWN,
    errorCode = VoiceMemoError.UNKNOWN_JOB,
)

/** Mutable job state; touched only under [MemoJobRegistry]'s mutex. */
class MemoJobRecord internal constructor(val key: MemoJobKey, val fingerprint: MemoFingerprint, internal var generation: Long) {
    var stage: String = VoiceMemoStage.RECEIVING
    var transcript: String? = null
    var result: VoiceMemoResult? = null
    var errorCode: String? = null
    var metrics: VoiceMemoMetrics = VoiceMemoMetrics()
    var revision: Long = 0
        internal set
    internal var upload: MemoUploadBuffer? = null
    internal var lastActivityNanos: Long = 0
    internal var terminalAtNanos: Long? = null
    internal var expiresAtWallMs: Long? = null
    internal var target: MemoReplyTarget? = null
    internal var handle: Job? = null

    val isTerminal: Boolean get() = stage in VoiceMemoStage.terminal

    /** The organiser this attempt asked for (part of its fingerprint; never echoed in a snapshot). */
    val agent: String get() = fingerprint.agent

    fun snapshot(): VoiceMemoState = VoiceMemoState(
        memoId = key.memoId,
        attemptId = key.attemptId,
        revision = revision,
        stage = stage,
        transcript = transcript,
        result = result,
        metrics = metrics,
        errorCode = errorCode,
        retryable = MemoRetry.retryable(errorCode),
        expiresAtMs = expiresAtWallMs,
    )

    /** Snapshot minus the bookkeeping fields — what "a real change" is measured against. */
    internal fun content(): VoiceMemoState = snapshot().copy(revision = 0, expiresAtMs = null)
}

/**
 * The daemon's memo job table (code design §6.2). One [Mutex] serialises every mutation; nothing slow runs
 * under it (the worker is created LAZY inside the lock so its handle is registered exactly once, and the
 * caller starts it — and delivers every [MemoPush] — after the lock is released).
 *
 * - Key = authenticated device + memoId + attemptId, so two phones can't see or collide with each other.
 * - Same key + same [MemoFingerprint] → the current state; different → input_conflict; a terminal job is
 *   never restarted by a repeated start.
 * - At most [MemoRegistryLimits.maxActivePerDevice] active jobs per device (receiving counts) and
 *   [MemoRegistryLimits.maxActiveTotal] daemon-wide; past either → busy, nothing recorded.
 * - [MemoJobRecord.revision] rises only when the snapshot content actually changes; reads never bump it.
 * - Terminal results live at most [MemoRegistryLimits.resultTtlMs] and at most
 *   [MemoRegistryLimits.maxCachedJobs] of them (oldest terminal evicted first; active jobs never evicted).
 * - cancel of an unknown key leaves a bounded marker so an out-of-order start is born cancelled.
 * - A receiving job idle for [MemoRegistryLimits.uploadIdleMs] fails with upload_timeout and drops its audio.
 * - Every job carries a generation; cancel/revoke bump it, so a worker's late completion is discarded.
 */
class MemoJobRegistry(
    private val clock: MemoClock = MemoClock.System,
    private val limits: MemoRegistryLimits = MemoRegistryLimits(),
    /** Must return a NOT-yet-started job (CoroutineStart.LAZY); the caller starts [MemoOutcome.launch]. */
    private val spawn: (MemoJobKey, Long, MemoWork) -> Job,
) {
    private val mutex = Mutex()
    private val jobs = LinkedHashMap<MemoJobKey, MemoJobRecord>()
    private val cancelMarkers = LinkedHashMap<MemoJobKey, Long>()
    private var nextGeneration = 1L

    suspend fun start(key: MemoJobKey, start: VoiceMemoStart, reply: MemoReplyTarget): MemoOutcome = mutex.withLock {
        val pushes = sweepLocked()
        val fp = MemoFingerprint.of(start)
        jobs[key]?.let { existing ->
            if (existing.fingerprint != fp) {
                pushes += MemoPush(reply, memoRejection(key.memoId, key.attemptId, VoiceMemoError.INPUT_CONFLICT))
                return@withLock MemoOutcome(pushes)
            }
            existing.target = reply
            pushes += MemoPush(reply, existing.snapshot())
            return@withLock MemoOutcome(pushes)
        }
        if (cancelMarkers.remove(key) != null) {
            // cancelled before it arrived: born terminal, and stays so for later repeats of this start
            val rec = MemoJobRecord(key, fp, nextGeneration++)
            rec.target = reply
            jobs[key] = rec
            change(rec) {
                stage = VoiceMemoStage.CANCELLED
                errorCode = VoiceMemoError.CANCELLED
            }
            pushes += MemoPush(reply, rec.snapshot())
            evictLocked()
            return@withLock MemoOutcome(pushes)
        }
        if (activeCount { it.deviceId == key.deviceId } >= limits.maxActivePerDevice ||
            activeCount { true } >= limits.maxActiveTotal
        ) {
            pushes += MemoPush(reply, memoRejection(key.memoId, key.attemptId, VoiceMemoError.BUSY))
            return@withLock MemoOutcome(pushes)
        }
        val rec = MemoJobRecord(key, fp, nextGeneration++)
        rec.target = reply
        rec.lastActivityNanos = clock.monoNanos()
        var launch: Job? = null
        when (start.inputKind) {
            VoiceMemoInputKind.AUDIO -> {
                rec.upload = MemoUploadBuffer(requireNotNull(start.byteLength), requireNotNull(start.chunkCount), start.sha256)
                rec.stage = VoiceMemoStage.RECEIVING
            }
            else -> {
                val text = requireNotNull(start.transcript)
                rec.stage = VoiceMemoStage.SUMMARIZING
                rec.transcript = text
                launch = spawn(key, rec.generation, MemoWork.Transcript(text, start.agent, start.locale))
                rec.handle = launch
            }
        }
        rec.revision = 1
        jobs[key] = rec
        pushes += MemoPush(reply, rec.snapshot())
        MemoOutcome(pushes, launch)
    }

    suspend fun audio(key: MemoJobKey, index: Int, base64: String, reply: MemoReplyTarget): MemoOutcome = mutex.withLock {
        val pushes = sweepLocked()
        val rec = jobs[key] ?: run {
            pushes += MemoPush(reply, memoUnknown(key.memoId, key.attemptId))
            return@withLock MemoOutcome(pushes)
        }
        rec.target = reply
        val buffer = rec.upload
        if (rec.stage != VoiceMemoStage.RECEIVING || buffer == null) {
            // a resend after the upload finished: answer with where the job is, never restart it
            pushes += MemoPush(reply, rec.snapshot())
            return@withLock MemoOutcome(pushes)
        }
        when (val r = buffer.accept(index, base64)) {
            // only a NEW chunk is progress: re-sending one the buffer already holds must not keep an
            // unfinished upload — and the slot and memory it occupies — alive for ever
            is MemoChunkResult.Accepted -> {
                rec.lastActivityNanos = clock.monoNanos()
                MemoOutcome(pushes)
            }
            MemoChunkResult.Duplicate -> MemoOutcome(pushes)
            is MemoChunkResult.Rejected -> {
                change(rec) {
                    stage = VoiceMemoStage.FAILED
                    errorCode = r.errorCode
                }
                pushes += MemoPush(reply, rec.snapshot())
                evictLocked()
                MemoOutcome(pushes)
            }
            is MemoChunkResult.Complete -> {
                rec.upload = null
                val handle = spawn(key, rec.generation, MemoWork.Audio(r.audio, rec.agent, rec.fingerprint.locale, clock.monoNanos()))
                rec.handle = handle
                change(rec) { stage = VoiceMemoStage.QUEUED }
                pushes += MemoPush(reply, rec.snapshot())
                MemoOutcome(pushes, handle)
            }
        }
    }

    suspend fun get(key: MemoJobKey, reply: MemoReplyTarget): MemoOutcome = mutex.withLock {
        val pushes = sweepLocked()
        val rec = jobs[key]
        if (rec == null) {
            pushes += MemoPush(reply, memoUnknown(key.memoId, key.attemptId))
        } else {
            rec.target = reply
            pushes += MemoPush(reply, rec.snapshot())
        }
        MemoOutcome(pushes)
    }

    suspend fun cancel(key: MemoJobKey, reply: MemoReplyTarget): MemoOutcome = mutex.withLock {
        val pushes = sweepLocked()
        val rec = jobs[key]
        when {
            rec == null -> {
                cancelMarkers.remove(key)
                cancelMarkers[key] = clock.monoNanos()
                while (cancelMarkers.size > limits.maxCachedJobs) cancelMarkers.remove(cancelMarkers.keys.first())
                pushes += MemoPush(reply, memoUnknown(key.memoId, key.attemptId))
            }
            rec.isTerminal -> {
                rec.target = reply
                pushes += MemoPush(reply, rec.snapshot())
            }
            else -> {
                rec.target = reply
                stopLocked(rec)
                change(rec) {
                    stage = VoiceMemoStage.CANCELLED
                    errorCode = VoiceMemoError.CANCELLED
                }
                pushes += MemoPush(reply, rec.snapshot())
                evictLocked()
            }
        }
        MemoOutcome(pushes)
    }

    /**
     * A worker's transition. Null when the job is gone, already terminal, or no longer this worker's
     * [generation] (cancelled / revoked) — the late result is dropped and the worker should stop.
     */
    suspend fun update(key: MemoJobKey, generation: Long, edit: MemoJobRecord.() -> Unit): List<MemoPush>? = mutex.withLock {
        // no housekeeping here: a sweep's notices for OTHER jobs would be lost whenever this worker's own job
        // turns out to be gone and the call answers null. The ticker and every frame sweep instead.
        val pushes = ArrayList<MemoPush>()
        val rec = jobs[key]
        if (rec == null || rec.generation != generation || rec.isTerminal) return@withLock null
        if (change(rec, edit)) rec.target?.let { pushes += MemoPush(it, rec.snapshot()) }
        if (rec.isTerminal) evictLocked()
        pushes
    }

    /** Idle-upload and TTL housekeeping; returns the upload_timeout frames to deliver. */
    suspend fun sweep(): List<MemoPush> = mutex.withLock { sweepLocked() }

    /** Pairing revoked: stop that device's jobs and forget everything it had here. */
    suspend fun revokeDevice(deviceId: String): Int = mutex.withLock {
        val gone = jobs.values.filter { it.key.deviceId == deviceId }
        gone.forEach { stopLocked(it); jobs.remove(it.key) }
        cancelMarkers.keys.removeAll { it.deviceId == deviceId }
        gone.size
    }

    suspend fun closeAll() = mutex.withLock {
        jobs.values.forEach { stopLocked(it) }
        jobs.clear()
        cancelMarkers.clear()
    }

    suspend fun peek(key: MemoJobKey): VoiceMemoState? = mutex.withLock { jobs[key]?.snapshot() }

    suspend fun activeJobs(): Int = mutex.withLock { activeCount { true } }

    suspend fun cachedTerminalJobs(): Int = mutex.withLock { jobs.values.count { it.isTerminal } }

    // ── locked helpers ────────────────────────────────────────────────────

    private fun activeCount(match: (MemoJobKey) -> Boolean): Int = jobs.values.count { !it.isTerminal && match(it.key) }

    /** Detach the worker (bumping the generation first so anything it still reports is stale) and drop audio. */
    private fun stopLocked(rec: MemoJobRecord) {
        rec.generation = nextGeneration++
        rec.handle?.cancel()
        rec.handle = null
        rec.upload?.release()
        rec.upload = null
    }

    /** Applies [edit]; bumps the revision only when the visible content changed. */
    private fun change(rec: MemoJobRecord, edit: MemoJobRecord.() -> Unit): Boolean {
        val before = rec.content()
        rec.edit()
        if (rec.content() == before) return false
        rec.revision++
        if (rec.isTerminal && rec.terminalAtNanos == null) {
            rec.terminalAtNanos = clock.monoNanos()
            rec.expiresAtWallMs = clock.wallMs() + limits.resultTtlMs
            rec.handle = null
            rec.upload?.release()
            rec.upload = null
        }
        return true
    }

    private fun sweepLocked(): MutableList<MemoPush> {
        val pushes = ArrayList<MemoPush>()
        val now = clock.monoNanos()
        val idleNanos = limits.uploadIdleMs * 1_000_000
        val ttlNanos = limits.resultTtlMs * 1_000_000
        for (rec in jobs.values) {
            if (rec.stage == VoiceMemoStage.RECEIVING && now - rec.lastActivityNanos >= idleNanos) {
                stopLocked(rec)
                change(rec) {
                    stage = VoiceMemoStage.FAILED
                    errorCode = VoiceMemoError.UPLOAD_TIMEOUT
                }
                rec.target?.let { pushes += MemoPush(it, rec.snapshot()) }
            }
        }
        jobs.values.removeAll { rec -> rec.terminalAtNanos?.let { now - it >= ttlNanos } ?: false }
        cancelMarkers.values.removeAll { now - it >= ttlNanos }
        evictLocked()
        return pushes
    }

    private fun evictLocked() {
        var terminal = jobs.values.count { it.isTerminal }
        while (terminal > limits.maxCachedJobs) {
            val oldest = jobs.values.filter { it.isTerminal }.minByOrNull { it.terminalAtNanos ?: Long.MIN_VALUE } ?: break
            jobs.remove(oldest.key)
            terminal--
        }
    }
}
