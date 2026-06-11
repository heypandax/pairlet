package dev.ccpocket.app.voice

import kotlinx.coroutines.flow.Flow

/** Events from on-device streaming dictation (the iOS SFSpeechRecognizer engine). */
sealed interface DictationEvent {
    /** A live hypothesis: [final] is the stable prefix (primary color), [partial] the still-changing tail (muted). */
    data class Partial(val final: String, val partial: String) : DictationEvent

    /** 0..1 volume envelope sample — drives the waveform, same scale as [VoiceRecorder.levels]. */
    data class Level(val level: Float) : DictationEvent

    /** The recognizer's final text; the flow completes right after. */
    data class Final(val text: String) : DictationEvent

    /**
     * Recognition failed mid-stream; the flow completes right after. UI lands on S5 (retry → daemon path).
     * [kind] picks the localized fallback text; [message] is an OS-provided (already localized) detail shown verbatim.
     */
    data class Error(val kind: DictationFail, val message: String? = null) : DictationEvent
}

/** Why native dictation failed — mapped to a localized string by the UI layer. */
enum class DictationFail { UNAVAILABLE, AUDIO_ENGINE, RECOGNITION }

/**
 * On-device streaming dictation. [available] is false on Android/desktop (P0) and on iOS when the
 * recognizer can't serve the current locale — callers then fall back to [VoiceRecorder] + daemon whisper.
 */
expect object NativeDictation {
    val available: Boolean

    /** Starts capture; emits [DictationEvent]s until Final/Error. Throws [VoicePermissionDenied]. */
    fun start(): Flow<DictationEvent>

    /** Stop listening and ask for the final result (the flow then emits [DictationEvent.Final] and ends). */
    suspend fun stop()

    /** Abort without a result. */
    fun cancel()
}
