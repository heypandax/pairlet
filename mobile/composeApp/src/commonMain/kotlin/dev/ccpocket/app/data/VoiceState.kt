package dev.ccpocket.app.data

import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.voice_transcribe_failed
import org.jetbrains.compose.resources.StringResource

/** Composer voice flow (design states S1–S5; S6 is the separate mic-permission sheet flag). */
sealed interface VoiceState {
    /** S1 — normal composer. */
    data object Idle : VoiceState

    /** S2 — waveform + timer; on iOS the live transcript streams alongside. */
    data class Recording(val elapsedMs: Long) : VoiceState

    /** S3 — capture done, waiting for the transcript; on success it is APPENDED to the composer for the
     *  user to review/edit and send explicitly (issue #221 — no auto-send). */
    data object Transcribing : VoiceState

    /**
     * S3′ — the computer has not answered within the ordinary window. NOT a failure: the capture is still
     * outstanding, a transcript that arrives now is used as usual, and the mic offers a retry meanwhile. Only
     * the final give-up turns this into [Failed]. Remote (daemon whisper) engine only.
     */
    data object StillWaiting : VoiceState

    /**
     * Voice input v2, send bar (docs/design/VOICE-INPUT-V2-REVIEW.md §11): ✓ was "finish and send" and the final
     * transcript is in; the computer is refining it before it is sent. [original] is the dictated text (shown above
     * the bar), [agent] the wire name of the session's agent doing the refine. Busy, like recording.
     */
    data class Refining(val original: String, val agent: String) : VoiceState

    /**
     * Voice input v2, send bar: the refined [text] with the corrected fragments at [ranges] (UTF-16 indices into
     * [text]) highlighted, held briefly before it is sent. Busy, like recording.
     */
    data class Preview(val text: String, val ranges: List<IntRange>) : VoiceState

    /**
     * S5 — error chip + retry mic (retry re-sends the kept audio, or re-records when none is kept).
     * [detail] (daemon- or OS-provided text) wins over the localized [res] fallback when present.
     */
    data class Failed(val res: StringResource, val detail: String? = null) : VoiceState {
        /** Match existing daemon diagnostics too, without requiring a protocol/daemon upgrade. */
        val setupIssue: VoiceSetupIssue?
            get() = if (res != Res.string.voice_transcribe_failed) null else when {
                detail?.startsWith("whisper-cli not found") == true -> VoiceSetupIssue.TRANSCRIBER
                detail?.startsWith("whisper model missing") == true -> VoiceSetupIssue.MODEL
                detail?.startsWith("no audio converter found") == true -> VoiceSetupIssue.CONVERTER
                else -> null
            }
    }
}

enum class VoiceSetupIssue { TRANSCRIBER, MODEL, CONVERTER }
