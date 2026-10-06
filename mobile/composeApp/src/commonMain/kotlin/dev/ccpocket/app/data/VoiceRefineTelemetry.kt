package dev.ccpocket.app.data

/**
 * Vocabulary of the `voice_refine` event (docs/observability/EVENT-CATALOG.md §9): one [REQUESTED] when a dictation
 * asks the computer for a refine, then exactly one outcome when that dictation is over. Fixed values only — never the
 * text, the capture id, a raw duration or a raw count — and every value stays inside the analytics ingress's
 * character set (`AnalyticsCatalog.stringValue`), so no bucket needs a `<`.
 */
internal object VoiceRefineTelemetry {
    const val REQUESTED = "requested"
    const val AUTO_SENT = "auto_sent"
    const val DISCARDED = "discarded"

    /** The dictation landed in the composer, named by its reason line; no reason line = the user's own edit, or
     *  another way the capture stopped being sendable (leaving, the app going away, an attachment…). */
    fun toComposer(reason: VoiceComposerReason?): String = when (reason) {
        null -> "to_composer_edit"
        VoiceComposerReason.TIMEOUT -> "to_composer_timeout"
        VoiceComposerReason.NOT_ADOPTED -> "to_composer_not_adopted"
        VoiceComposerReason.UNAVAILABLE -> "to_composer_unavailable"
        VoiceComposerReason.REVIEW -> "to_composer_review"
        VoiceComposerReason.DISCONNECTED -> "to_composer_disconnected"
        VoiceComposerReason.NOT_SENT -> "to_composer_not_sent"
    }

    /** Every value `outcome` can take. */
    val OUTCOMES: List<String> =
        listOf(REQUESTED, AUTO_SENT) + VoiceComposerReason.entries.map(::toComposer) + toComposer(null) + DISCARDED

    /** How many corrections the daemon applied. */
    fun editsBucket(edits: Int): String = when {
        edits <= 0 -> "0"
        edits == 1 -> "1"
        edits <= 3 -> "2-3"
        else -> "4+"
    }

    val EDITS: List<String> = listOf("0", "1", "2-3", "4+")

    /** The phone's refine budget ran out before the computer answered. */
    const val LATENCY_TIMEOUT = "timeout"

    /** ✓ "finish and send" → the computer's answer. An answer past 10 s still counts when the refine itself beat its
     *  budget: that budget starts at the final transcript, and a whisper capture's transcription comes first. */
    fun latencyBucket(ms: Long): String = when {
        ms < 3_000 -> "0-2999"
        ms < 6_000 -> "3000-5999"
        ms < 10_000 -> "6000-9999"
        else -> "10000+"
    }

    val LATENCIES: List<String> = listOf("0-2999", "3000-5999", "6000-9999", "10000+", LATENCY_TIMEOUT)
}
