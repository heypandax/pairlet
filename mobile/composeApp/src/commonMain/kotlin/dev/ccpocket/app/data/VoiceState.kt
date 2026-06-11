package dev.ccpocket.app.data

import org.jetbrains.compose.resources.StringResource

/** Composer voice flow (design states S1–S5; S6 is the separate mic-permission sheet flag). */
sealed interface VoiceState {
    /** S1 — normal composer. */
    data object Idle : VoiceState

    /** S2 — waveform + timer; on iOS the live transcript streams alongside. */
    data class Recording(val elapsedMs: Long) : VoiceState

    /** S3 — capture done, waiting for the transcript; on success it is SENT directly (no S4 review). */
    data object Transcribing : VoiceState

    /**
     * S5 — error chip + retry mic (retry re-sends the kept audio, or re-records when none is kept).
     * [detail] (daemon- or OS-provided text) wins over the localized [res] fallback when present.
     */
    data class Failed(val res: StringResource, val detail: String? = null) : VoiceState
}
