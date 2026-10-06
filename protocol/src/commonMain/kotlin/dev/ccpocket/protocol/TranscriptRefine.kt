package dev.ccpocket.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── voice input v2: refine a dictated transcript before sending it (docs/design/VOICE-INPUT-REFINE-SEND.md §5) ──
//
// After dictation the phone may ask the paired computer to proofread the transcript with the owner's OWN coding
// agent, run one-shot and tool-less. The model only proposes a replacement list; the daemon validates it against
// the original text and answers with the checked result, never with free model text.
//
// Both directions are capability-gated, deny-by-omission — an unknown frame TYPE is not covered by
// `ignoreUnknownKeys`, so neither side relies on the other skipping it:
//  - the daemon lists the agents whose refiner it can launch right now in [DaemonInfo.transcriptRefineAgents];
//    empty (or absent: an older daemon) means "no refiner", and the phone keeps landing dictation in the composer;
//  - a connection declares [ClientCaps.supportsTranscriptRefine]; the daemon neither runs a refine for a
//    connection that did not, nor sends it a [TranscriptRefined].
// Cancelling reuses [AudioCancel] with the capture's id: the daemon drops a running refine and sends nothing.
//
// The vocabularies are Strings, never enums: an already-shipped peer hard-fails a whole Envelope on an unknown enum
// value, and both [TranscriptRefined.error] and the agent names are expected to grow.

/** Fixed limits, shared by the phone and the daemon so they cannot drift. */
object TranscriptRefineLimits {
    /** Longest [TranscriptRefine.text] a daemon refines, in UTF-16 code units (Kotlin `String.length`). Anything
     *  longer is answered [TranscriptRefineError.UNAVAILABLE] without starting a model — a phone may skip the
     *  round trip itself. */
    const val MAX_TEXT_CHARS = 4_000
}

/** [TranscriptRefined.error] vocabulary. A code this build does not know reads as [FAILED]. */
object TranscriptRefineError {
    /** No refiner for this request: neither the conversation's agent nor [TranscriptRefine.agentHint] has one that
     *  can launch here, its CLI is missing, or the text is longer than [TranscriptRefineLimits.MAX_TEXT_CHARS]. */
    const val UNAVAILABLE = "unavailable"

    /** The model answered, but its replacement list failed the daemon's checks; nothing of it is returned. */
    const val INVALID = "invalid"

    /** The refiner did not answer within the daemon's hard limit. */
    const val TIMEOUT = "timeout"

    /** The refiner ran and failed: non-zero exit, unreadable output, output outside the schema. */
    const val FAILED = "failed"

    /** A newer [TranscriptRefine] for the same conversation replaced this one. */
    const val SUPERSEDED = "superseded"
}

/**
 * phone -> daemon: proofread this dictated text. [captureId] identifies the dictation (the same id its audio
 * travelled under, when it went through the computer's whisper) and binds the reply and any [AudioCancel] to it.
 * One refine runs per conversation: a new request replaces the running one, which is answered
 * [TranscriptRefineError.SUPERSEDED].
 *
 * [locale] is the phone's UI language tag; it picks the language of the refiner's instructions (a tag starting with
 * `zh` → Chinese, anything else → English). [agentHint] is the phone's DEFAULT agent as an [AgentKind] wire name; the
 * daemon follows the conversation's own agent and consults the hint only when that agent has no refiner here.
 *
 * Sent only to a daemon that listed at least one agent in [DaemonInfo.transcriptRefineAgents], on a connection that
 * declared [ClientCaps.supportsTranscriptRefine]. An older daemon cannot decode the type and drops it, so the phone
 * keeps its own deadline either way.
 */
@Serializable
@SerialName("pocket/transcript.refine")
data class TranscriptRefine(
    val convoId: String,
    val captureId: String,
    val text: String,
    val locale: String? = null,
    val agentHint: String? = null,
) : ToDaemon

/**
 * daemon -> phone: the single answer to a [TranscriptRefine], sent only to the connection that asked.
 *
 * ok=true: [text] is the refined text — the original with every edit applied — and [edits] the replacements in
 * the order they occur in the original, each `from` having appeared exactly once there. An empty [edits] means the
 * model found nothing to correct and [text] equals the original. [edits] exist for highlighting only; [text] is the
 * whole truth.
 *
 * ok=false: [error] is a [TranscriptRefineError] code and [text] is empty — the phone falls back to the transcript
 * it already holds. [agent] names the [AgentKind] wire name whose refiner handled the request, also on most
 * failures; null when none was chosen.
 *
 * [autoSend]: the corrected text passed the daemon's auto-send gate; false (also from a daemon that predates the
 * field) means the phone puts the text in the composer.
 */
@Serializable
@SerialName("pocket/transcript.refined")
data class TranscriptRefined(
    val convoId: String,
    val captureId: String,
    val ok: Boolean,
    val text: String = "",
    val edits: List<TextEdit> = emptyList(),
    val agent: String? = null,
    val error: String? = null,
    val autoSend: Boolean = false,
) : ToPhone

/** One replacement: the fragment [from] of the original text becomes [to]. */
@Serializable
data class TextEdit(val from: String, val to: String)
