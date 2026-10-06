package dev.ccpocket.app.data

/**
 * Voice input v2 (docs/design/VOICE-INPUT-V2-REVIEW.md §11 "已确认"): what ✓ does after dictation. Per device,
 * SecureStore `voice_after_dictation`; absent or anything but `send` reads [COMPOSE] — today's behaviour, the
 * default for new and existing users alike. [SEND] needs the one-time disclosure first
 * ([PocketRepository.acknowledgeVoiceRefineDisclosure]).
 */
enum class VoiceAfterDictation {
    /** ✓ puts the transcript in the composer, unrefined: exactly today's recording bar. */
    COMPOSE,

    /** ✓ on an eligible capture has the computer refine the transcript, and sends it when the daemon allows. */
    SEND;

    internal val wire: String get() = if (this == SEND) "send" else "compose"

    companion object {
        internal fun from(stored: String?): VoiceAfterDictation = if (stored == "send") SEND else COMPOSE
    }
}
