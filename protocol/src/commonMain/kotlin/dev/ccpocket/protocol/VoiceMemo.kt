package dev.ccpocket.protocol

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.random.Random

// ── voice memo → tasks (docs/design/VOICE-MEMO-TO-TASK.md, VOICE-MEMO-CODE-DESIGN.md) ─────────────────
//
// A memo is recorded on the phone, transcribed by a one-shot whisper-cli on the paired computer, and
// organised into a title / summary / to-do list by a tool-less one-shot agent call. Nothing here executes
// a task: dispatching a confirmed to-do reuses [SendPrompt] / [PromptAck].
//
// Every vocabulary below is a String, never an enum: an already-shipped peer hard-fails a whole Envelope
// on an unknown enum value, and these lists are expected to grow. Both directions are capability-gated
// ([ClientCaps.supportsVoiceMemo], [DaemonInfo.voiceMemoVersion]) — an unknown frame TYPE is not covered by
// `ignoreUnknownKeys`, so neither side sends a memo frame to a peer that did not declare it.

/** Fixed v1 limits, shared by the phone and the daemon so they cannot drift. */
object VoiceMemoLimits {
    /** The memo contract this build speaks. A daemon announcing N serves every contract from 1 to N, so a
     *  phone accepts any announcement that is at least its own.
     *
     *  1 — audio/transcript in, Claude organises, READY/DEGRADED/FAILED out.
     *  2 — any advertised organiser may be asked for, [VOICE_MEMO_AGENT_NONE] asks for none, and an attempt
     *      may end in [VoiceMemoStage.TRANSCRIBED]; a v1 daemon refuses both, so a v2 phone needs a v2 daemon. */
    const val VERSION = 2

    /** What the recording UI stops at. */
    const val MAX_RECORDING_MS = 180_000L
    /** What the daemon accepts: the UI limit plus the native stop / container latency. */
    const val MAX_AUDIO_DURATION_MS = 181_500L
    const val MAX_AUDIO_BYTES = 8L * 1024 * 1024
    /** Raw bytes per chunk; each chunk is Base64-encoded on its own. */
    const val CHUNK_BYTES = 128 * 1024
    const val MAX_CHUNKS = 64
    const val AUDIO_MEDIA_TYPE = "audio/mp4"

    const val MAX_TRANSCRIPT_CODE_POINTS = 20_000
    const val MAX_TRANSCRIPT_UTF8_BYTES = 80 * 1024
    const val MAX_TITLE_CODE_POINTS = 80
    const val MAX_SUMMARY_CODE_POINTS = 1_200
    const val MAX_TODOS = 20
    const val MAX_TODO_CODE_POINTS = 1_000
    const val MAX_TARGET_HINT_CODE_POINTS = 120
    const val MAX_LANGUAGE_CHARS = 35
    const val MAX_MODEL_STDOUT_BYTES = 64 * 1024

    const val UPLOAD_IDLE_MS = 60_000L
    const val SLOT_WAIT_MS = 30_000L
    const val TRANSCRIBE_DEADLINE_MS = 180_000L
    const val SUMMARIZE_DEADLINE_MS = 90_000L
    const val RESULT_TTL_MS = 24L * 60 * 60 * 1000
    const val MAX_CACHED_JOBS = 100
    const val MAX_ACTIVE_JOBS = 2

    const val MAX_LOCAL_MEMOS = 100

    /** Base64 length of one full chunk — checked BEFORE decoding so an oversized string never allocates. */
    val MAX_CHUNK_BASE64_CHARS: Int = ((CHUNK_BYTES + 2) / 3) * 4

    fun chunkCountFor(byteLength: Long): Int = ((byteLength + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt()
}

/** [DaemonInfo.voiceMemoStatus] vocabulary: the state of TRANSCRIPTION on the computer. A client treats any
 *  other value as "not usable". Organisers are advertised separately ([DaemonInfo.voiceMemoAgents]) and their
 *  absence is not a status — a memo can be transcribed without one. */
object VoiceMemoStatus {
    const val READY = "ready"
    const val WHISPER_MISSING = "whisper_missing"
    const val MODEL_MISSING = "model_missing"
    const val CONVERTER_MISSING = "converter_missing"
    /** Kept for a daemon that still reports it; a client reads it as "transcription unusable". */
    const val AGENT_UNAVAILABLE = "agent_unavailable"
    const val UNSUPPORTED_PLATFORM = "unsupported_platform"
    const val UNKNOWN = "unknown"
}

/** [VoiceMemoState.stage] vocabulary. */
object VoiceMemoStage {
    const val RECEIVING = "receiving"
    const val QUEUED = "queued"
    const val TRANSCRIBING = "transcribing"
    const val SUMMARIZING = "summarizing"
    const val READY = "ready"
    /** Organising was attempted and failed; the transcript is there, the result is not. */
    const val DEGRADED = "degraded"
    /** The transcript is there and nothing was organised: the phone asked for [VOICE_MEMO_AGENT_NONE], or the
     *  organiser it asked for was gone by the time transcription finished ([VoiceMemoState.errorCode] says so).
     *  Not a failure: the to-dos are the user's to write, or to have organised later. */
    const val TRANSCRIBED = "transcribed"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
    /** The daemon holds no record of this attempt (restart / TTL). NOT "it never ran". */
    const val UNKNOWN = "unknown"

    val terminal = setOf(READY, DEGRADED, TRANSCRIBED, FAILED, CANCELLED, UNKNOWN)
    val known = terminal + setOf(RECEIVING, QUEUED, TRANSCRIBING, SUMMARIZING)
}

/** [VoiceMemoState.errorCode] vocabulary — machine-readable; the UI maps each to localized copy. */
object VoiceMemoError {
    const val UNSUPPORTED = "unsupported"
    const val NOT_READY = "not_ready"
    const val BUSY = "busy"
    const val INVALID_INPUT = "invalid_input"
    const val INPUT_CONFLICT = "input_conflict"
    const val UPLOAD_TIMEOUT = "upload_timeout"
    const val AUDIO_INVALID = "audio_invalid"
    const val AUDIO_TOO_LONG = "audio_too_long"
    const val TRANSCRIBE_FAILED = "transcribe_failed"
    const val TRANSCRIBE_TIMEOUT = "transcribe_timeout"
    const val EMPTY_TRANSCRIPT = "empty_transcript"
    const val TRANSCRIPT_TOO_LONG = "transcript_too_long"
    const val AGENT_UNAVAILABLE = "agent_unavailable"
    const val SUMMARY_FAILED = "summary_failed"
    const val SUMMARY_TIMEOUT = "summary_timeout"
    const val INVALID_RESULT = "invalid_result"
    const val CANCELLED = "cancelled"
    const val UNKNOWN_JOB = "unknown_job"
}

object VoiceMemoInputKind {
    const val AUDIO = "audio"
    const val TRANSCRIPT = "transcript"
}

/** Organiser adapters, by the agent's wire name ([AgentKind]'s serial names). A daemon lists the ones it can
 *  launch in [DaemonInfo.voiceMemoAgents]; the list may be empty — transcription works without any. */
const val VOICE_MEMO_AGENT_CLAUDE = "claude"
const val VOICE_MEMO_AGENT_CODEX = "codex"

/** "Transcribe only": no organiser is run and the attempt ends in [VoiceMemoStage.TRANSCRIBED]. */
const val VOICE_MEMO_AGENT_NONE = "none"

/**
 * phone -> daemon: register a processing attempt, or read back the state of the same one. Two mutually
 * exclusive shapes, selected by [inputKind]: `audio` announces an upload ([mediaType] / [byteLength] /
 * [chunkCount] set, [transcript] null) and is followed by [VoiceMemoAudio] chunks; `transcript`
 * re-organises text the phone already holds ([transcript] set, the audio fields null). [sha256] is the
 * hash of that input. A retry is a NEW [attemptId] under the same [memoId].
 *
 * [agent] names the organiser adapter — one the daemon advertised — or [VOICE_MEMO_AGENT_NONE] for a
 * transcript-only attempt. A `transcript` input with `none` is meaningless and refused.
 */
@Serializable
@SerialName("pocket/memo.start")
data class VoiceMemoStart(
    val memoId: String,
    val attemptId: String,
    val inputKind: String,
    val agent: String = VOICE_MEMO_AGENT_CLAUDE,
    val model: String? = null, // v1: must be null
    val locale: String? = null,
    val mediaType: String? = null,
    val durationMs: Long? = null,
    val byteLength: Long? = null,
    // defaulted so a start without it decodes and is REFUSED by validation, instead of being dropped unanswered
    val sha256: String = "",
    val chunkCount: Int? = null,
    val transcript: String? = null,
) : ToDaemon

/** phone -> daemon: one chunk of a registered audio attempt. [base64] encodes this chunk alone. */
@Serializable
@SerialName("pocket/memo.audio")
data class VoiceMemoAudio(
    val memoId: String,
    val attemptId: String,
    val index: Int,
    val base64: String,
) : ToDaemon

/** phone -> daemon: read the attempt's state (reconnect, foreground, result re-delivery). */
@Serializable
@SerialName("pocket/memo.get")
data class VoiceMemoGet(val memoId: String, val attemptId: String) : ToDaemon

/** phone -> daemon: stop an attempt that has not finished. Idempotent; never touches a dispatched task. */
@Serializable
@SerialName("pocket/memo.cancel")
data class VoiceMemoCancel(val memoId: String, val attemptId: String) : ToDaemon

/**
 * daemon -> phone: a snapshot of one attempt, sent only to the device that owns it. [revision] rises
 * from 1 with every real change of a known job; a [VoiceMemoStage.UNKNOWN] reply carries 0 and answers
 * a query — it never overrides a result the phone already stored. [retryable] says a user-triggered
 * retry may help; it never authorises an automatic one.
 */
@Serializable
@SerialName("pocket/memo.state")
data class VoiceMemoState(
    val memoId: String,
    val attemptId: String,
    val revision: Long,
    val stage: String,
    val transcript: String? = null,
    val result: VoiceMemoResult? = null,
    val metrics: VoiceMemoMetrics = VoiceMemoMetrics(),
    val errorCode: String? = null,
    val retryable: Boolean = false,
    val expiresAtMs: Long? = null,
) : ToPhone

/**
 * Every field is defaulted on the wire: a result that lacks one must still DECODE, so the snapshot's transcript
 * arrives and [VoiceMemoValidation.validateResult] can say what is wrong. A required field would instead fail the
 * whole Envelope — the phone would lose the transcript with it and wait for a frame that was silently dropped.
 */
@Serializable
data class VoiceMemoResult(
    val schemaVersion: Int = 1,
    val title: String = "",
    val summary: String = "",
    val todos: List<VoiceMemoTodoSuggestion> = emptyList(),
    val language: String = "",
)

@Serializable
data class VoiceMemoTodoSuggestion(val text: String = "", val suggestedTarget: String? = null)

/** Daemon-side timings in milliseconds; null = not measured (never 0 for "unknown"). New members may be added;
 *  the type of an existing one never changes — a telemetry field must not be able to sink a result frame. */
@Serializable
data class VoiceMemoMetrics(
    val audioDurationMs: Long? = null,
    val queueMs: Long? = null,
    val transcribeMs: Long? = null,
    val summarizeMs: Long? = null,
    val coldStart: Boolean? = null,
)

/** Shape checks shared by both ends. Each returns a [VoiceMemoError] code, or null when the input is valid. */
object VoiceMemoValidation {
    private val uuid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val sha256Hex = Regex("^[0-9a-f]{64}$")
    private val languageTag = Regex("^[A-Za-z0-9-]{1,35}$")

    fun isId(value: String): Boolean = uuid.matches(value)
    fun isSha256(value: String): Boolean = sha256Hex.matches(value)
    fun isLanguageTag(value: String): Boolean = languageTag.matches(value)

    /** Unicode code points, so the JVM and iOS agree on a string holding surrogate pairs. */
    fun codePoints(text: String): Int {
        var count = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) i++
            i++
            count++
        }
        return count
    }

    fun validateIds(memoId: String, attemptId: String): String? =
        if (isId(memoId) && isId(attemptId)) null else VoiceMemoError.INVALID_INPUT

    /** [advertisedAgents] is what this daemon announced in [DaemonInfo.voiceMemoAgents]. */
    fun validateStart(start: VoiceMemoStart, advertisedAgents: Collection<String>): String? {
        validateIds(start.memoId, start.attemptId)?.let { return it }
        if (start.model != null) return VoiceMemoError.INVALID_INPUT
        if (start.agent == VOICE_MEMO_AGENT_NONE) {
            // organising is the whole point of re-submitting a transcript
            if (start.inputKind == VoiceMemoInputKind.TRANSCRIPT) return VoiceMemoError.INVALID_INPUT
        } else if (start.agent !in advertisedAgents) return VoiceMemoError.AGENT_UNAVAILABLE
        if (start.locale != null && !isLanguageTag(start.locale)) return VoiceMemoError.INVALID_INPUT
        if (!isSha256(start.sha256)) return VoiceMemoError.INVALID_INPUT
        return when (start.inputKind) {
            VoiceMemoInputKind.AUDIO -> validateAudioStart(start)
            VoiceMemoInputKind.TRANSCRIPT -> validateTranscriptStart(start)
            else -> VoiceMemoError.INVALID_INPUT
        }
    }

    private fun validateAudioStart(start: VoiceMemoStart): String? {
        if (start.transcript != null) return VoiceMemoError.INVALID_INPUT
        if (start.mediaType != VoiceMemoLimits.AUDIO_MEDIA_TYPE) return VoiceMemoError.AUDIO_INVALID
        val duration = start.durationMs ?: return VoiceMemoError.INVALID_INPUT
        if (duration <= 0) return VoiceMemoError.INVALID_INPUT
        if (duration > VoiceMemoLimits.MAX_AUDIO_DURATION_MS) return VoiceMemoError.AUDIO_TOO_LONG
        val bytes = start.byteLength ?: return VoiceMemoError.INVALID_INPUT
        if (bytes <= 0 || bytes > VoiceMemoLimits.MAX_AUDIO_BYTES) return VoiceMemoError.INVALID_INPUT
        val chunks = start.chunkCount ?: return VoiceMemoError.INVALID_INPUT
        if (chunks != VoiceMemoLimits.chunkCountFor(bytes) || chunks > VoiceMemoLimits.MAX_CHUNKS) return VoiceMemoError.INVALID_INPUT
        return null
    }

    private fun validateTranscriptStart(start: VoiceMemoStart): String? {
        if (start.mediaType != null || start.byteLength != null || start.chunkCount != null) return VoiceMemoError.INVALID_INPUT
        val text = start.transcript ?: return VoiceMemoError.INVALID_INPUT
        return validateTranscript(text)
    }

    fun validateTranscript(text: String): String? {
        if (text.isBlank()) return VoiceMemoError.EMPTY_TRANSCRIPT
        if (text.length > VoiceMemoLimits.MAX_TRANSCRIPT_UTF8_BYTES) return VoiceMemoError.TRANSCRIPT_TOO_LONG
        if (codePoints(text) > VoiceMemoLimits.MAX_TRANSCRIPT_CODE_POINTS) return VoiceMemoError.TRANSCRIPT_TOO_LONG
        if (text.encodeToByteArray().size > VoiceMemoLimits.MAX_TRANSCRIPT_UTF8_BYTES) return VoiceMemoError.TRANSCRIPT_TOO_LONG
        return null
    }

    /** Expected raw size of chunk [index] of an upload of [byteLength] bytes, or null when out of range. */
    fun expectedChunkBytes(byteLength: Long, index: Int): Int? {
        val count = VoiceMemoLimits.chunkCountFor(byteLength)
        if (index < 0 || index >= count) return null
        return if (index < count - 1) VoiceMemoLimits.CHUNK_BYTES
        else (byteLength - (count - 1).toLong() * VoiceMemoLimits.CHUNK_BYTES).toInt()
    }

    fun validateTodoText(text: String): String? =
        if (text.isBlank() || codePoints(text) > VoiceMemoLimits.MAX_TODO_CODE_POINTS) VoiceMemoError.INVALID_RESULT else null

    fun validateResult(result: VoiceMemoResult): String? {
        if (result.schemaVersion != 1) return VoiceMemoError.INVALID_RESULT
        if (result.title.isBlank() || codePoints(result.title) > VoiceMemoLimits.MAX_TITLE_CODE_POINTS) return VoiceMemoError.INVALID_RESULT
        if (result.summary.isBlank() || codePoints(result.summary) > VoiceMemoLimits.MAX_SUMMARY_CODE_POINTS) return VoiceMemoError.INVALID_RESULT
        if (result.todos.size > VoiceMemoLimits.MAX_TODOS) return VoiceMemoError.INVALID_RESULT
        if (!isLanguageTag(result.language)) return VoiceMemoError.INVALID_RESULT
        for (todo in result.todos) {
            validateTodoText(todo.text)?.let { return it }
            val hint = todo.suggestedTarget
            if (hint != null && codePoints(hint) > VoiceMemoLimits.MAX_TARGET_HINT_CODE_POINTS) return VoiceMemoError.INVALID_RESULT
        }
        return null
    }

    /**
     * The phone's check of an incoming snapshot: `ready` needs a valid result and a transcript, `degraded`
     * a transcript and an error. Null = usable; otherwise the phone keeps what it has and reports
     * `incompatible_response` instead of reading a missing field as "delete".
     */
    fun validateState(state: VoiceMemoState): String? {
        validateIds(state.memoId, state.attemptId)?.let { return it }
        if (state.revision < 0) return VoiceMemoError.INVALID_INPUT
        return when (state.stage) {
            VoiceMemoStage.READY -> {
                val result = state.result ?: return VoiceMemoError.INVALID_RESULT
                if (state.transcript.isNullOrBlank()) return VoiceMemoError.INVALID_RESULT
                validateResult(result)
            }
            VoiceMemoStage.DEGRADED ->
                if (state.transcript.isNullOrBlank() || state.errorCode == null) VoiceMemoError.INVALID_RESULT else null
            VoiceMemoStage.TRANSCRIBED ->
                if (state.transcript.isNullOrBlank()) VoiceMemoError.INVALID_RESULT else null
            in VoiceMemoStage.known -> null
            else -> VoiceMemoError.UNSUPPORTED
        }
    }
}

object VoiceMemoIds {
    /** A random RFC 4122 v4 UUID in canonical lowercase form. */
    fun newId(random: Random = Random.Default): String {
        val b = random.nextBytes(16)
        b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte()
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
        val hex = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }
}

object VoiceMemoHash {
    private val hasher by lazy { CryptographyProvider.Default.get(SHA256).hasher() }

    fun sha256Hex(bytes: ByteArray): String =
        hasher.hashBlocking(bytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
