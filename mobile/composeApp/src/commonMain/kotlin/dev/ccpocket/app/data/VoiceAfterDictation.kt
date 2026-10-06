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

/**
 * Which recording bar a capture shows (README "后续决定"). Decided at [PocketRepository.startVoice] and frozen for
 * that capture: it may only fall from [EDIT_SEND] to [EDIT_DONE] when the capture can no longer send, never rise.
 */
enum class VoiceBarMode {
    /** COMPOSE, or SEND without the disclosure accepted: today's bar, item for item — ✕ cancel, ✓ done. */
    LEGACY,

    /** SEND and this capture can send: keyboard "finish and edit" on the left, the send arrow "finish and send". */
    EDIT_SEND,

    /** SEND but this capture cannot send (no refiner for the session's agent, offline, a draft or attachment…):
     *  keyboard "finish and edit" on the left, today's ✓ "done" on the right. */
    EDIT_DONE,
}

/** What the composer on screen holds right now: its live text and whether an IME composition is open — never the
 *  400 ms-debounced persisted draft (review §10 item 7). */
data class ComposerProbe(val text: String, val composing: Boolean)
