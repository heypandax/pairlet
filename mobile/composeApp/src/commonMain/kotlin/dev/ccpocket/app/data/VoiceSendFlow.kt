package dev.ccpocket.app.data

import dev.ccpocket.protocol.TextEdit
import dev.ccpocket.protocol.TranscriptRefineError

/** Where a capture started: the computer and the composer it belongs to. Frozen at [VoiceSendFlow.Event.Start] —
 *  text that cannot be sent always lands here, never in whatever conversation is open at callback time. */
data class VoiceOrigin(val computerId: String, val composerKey: String)

/** Why text landed in the composer instead of being sent; the host turns it into the reason line. */
enum class VoiceComposerReason { TIMEOUT, NOT_ADOPTED, UNAVAILABLE, DISCONNECTED, NOT_SENT, REVIEW }

/** Why a send-bar capture can no longer send (docs/design/VOICE-INPUT-V2-REVIEW.md §11 invariants). */
enum class VoiceDowngrade { Disconnected, LeftConversation, Backgrounded, ComposerChanged, EligibilityLost }

/**
 * Voice input v2 — the one owner of "send this dictation, or hand it to the composer"
 * (docs/design/VOICE-INPUT-V2-REVIEW.md §3–4, §10 A1–A3/A7/A8, §11; §11 wins where they differ).
 *
 * Pure: no coroutines, clock, Compose state or platform API. The host owns the recorder, the timers (the 10 s refine
 * deadline, the 700 ms highlight hold) and all I/O, reports what happened as [Event]s and carries out the returned
 * [Effect]s. One capture at a time; an event for an unknown or finished capture returns nothing.
 *
 * Safety rules the tests pin down:
 *  - the only send authorisation is the user's ✓ on a send bar while eligible ([Event.TapSend]); the recogniser
 *    ending by itself, the 90 s cap, a retry after a failed transcription never authorise anything;
 *  - the daemon must also say so: only a [Event.Refined] with `ok && autoSend` and a fresh eligibility check sends;
 *  - [Effect.SubmitSend] is emitted at most once per capture;
 *  - edit, deadline, any [VoiceDowngrade] or the cap before submitting make the capture edit-only for good;
 *  - after submitting, the app's own send recovery owns the message: only a refused submit lands text again.
 */
class VoiceSendFlow {

    sealed interface Event {
        /** A recording began. [sendBar] = the bar was presented as a send bar; both fields are frozen. */
        data class Start(val captureId: String, val origin: VoiceOrigin, val sendBar: Boolean) : Event
        /** ✓ on a send bar; [eligibleNow] is the host's check at tap time. Not eligible / not a send bar = [TapDone]. */
        data class TapSend(val eligibleNow: Boolean) : Event
        /** ✓ on today's bar: the text goes to the composer, no reason line. */
        data object TapDone : Event
        /** The leading "keep the text and edit" control. */
        data object TapEdit : Event
        /** Today's ✕: discard. */
        data object TapCancel : Event
        /** Latest non-final hypothesis (iOS live dictation). */
        data class Partial(val text: String) : Event
        /** Final transcript from either engine, tapped or not (the native recogniser can end by itself). */
        data class TranscriptFinal(val text: String) : Event
        /** Transcription failed; the host shows today's failure UI. A later retry result never sends. */
        data object TranscriptFailed : Event
        /** The 90 s limit: same as [TapEdit]. */
        data object CapReached : Event
        /** The daemon's answer to [Effect.RequestRefine]; [eligibleNow] is the host's fresh check. */
        data class Refined(
            val captureId: String,
            val ok: Boolean,
            val text: String,
            val edits: List<TextEdit>,
            val error: String?,
            val autoSend: Boolean,
            val eligibleNow: Boolean,
        ) : Event
        /** The phone's own refine deadline, started by the host on [Effect.RequestRefine]. */
        data class RefineDeadline(val captureId: String) : Event
        /** The highlight hold started by [Effect.ShowPreview] ended. */
        data class HoldElapsed(val captureId: String, val eligibleNow: Boolean) : Event
        data class Downgrade(val cause: VoiceDowngrade) : Event
        /** What the host's send call returned for [Effect.SubmitSend]. */
        data class SendResult(val captureId: String, val accepted: Boolean) : Event
    }

    sealed interface Effect {
        data class RequestRefine(val captureId: String, val text: String) : Effect
        data class CancelRefine(val captureId: String) : Effect
        data class ShowRefining(val original: String) : Effect
        /** Show [text] with [ranges] highlighted; the host starts the hold timer. */
        data class ShowPreview(val text: String, val ranges: List<IntRange>) : Effect
        data class SubmitSend(val captureId: String, val text: String) : Effect
        /** Append [text] to [origin]'s composer, cursor at the end; [reason] null = no reason line. */
        data class ToComposer(val origin: VoiceOrigin, val text: String, val reason: VoiceComposerReason?) : Effect
        data object Discard : Effect
        data object NoSpeech : Effect
        data object TranscribeFailed : Effect
        /** The capture is over; the host may clear its voice state. Always the last effect of a transition. */
        data class Finished(val captureId: String) : Effect
    }

    /** Where the current capture stands — for the host to mirror into `VoiceState`, so waiting stays visible. */
    enum class Phase {
        /** Recording; no completion chosen yet. */
        RECORDING,
        /** A completion was chosen (or a failure shown); waiting for the final transcript. */
        AWAITING_FINAL,
        /** [Effect.RequestRefine] is outstanding. */
        REFINING,
        /** The refined text is shown highlighted until [Event.HoldElapsed]. */
        HOLDING,
        /** [Effect.SubmitSend] was emitted; waiting for [Event.SendResult]. */
        SUBMITTED,
    }

    private enum class Intent { NONE, SEND, DONE, EDIT }

    private class Capture(val id: String, val origin: VoiceOrigin, val sendBar: Boolean) {
        var phase = Phase.RECORDING
        var intent = Intent.NONE
        /** Permanently edit-only: nothing re-authorises sending. */
        var editOnly = false
        /** A transcription failure was shown; any later transcript is a retry result. */
        var failed = false
        /** [Effect.RequestRefine] was emitted and has not been answered — only then is a cancel worth sending. */
        var refineOutstanding = false
        var partial: String? = null
        var original: String? = null
        var refined: String? = null
        var submitted: String? = null
    }

    private var current: Capture? = null
    private val usedIds = HashSet<String>()

    /** The current capture's phase, or null when there is none. */
    val phase: Phase? get() = current?.phase

    /** The current capture's id, or null. */
    val captureId: String? get() = current?.id

    fun on(event: Event): List<Effect> {
        if (event is Event.Start) return start(event)
        val c = current ?: return emptyList()
        return when (event) {
            is Event.Start -> emptyList() // handled above
            is Event.TapSend ->
                if (c.sendBar && event.eligibleNow && !c.editOnly) choose(c, Intent.SEND) else choose(c, Intent.DONE)
            Event.TapDone -> choose(c, Intent.DONE)
            Event.TapEdit, Event.CapReached -> edit(c)
            Event.TapCancel -> if (c.phase == Phase.SUBMITTED) emptyList() else finish(c, cancelIfRefining(c) + Effect.Discard)
            is Event.Partial -> { if (c.original == null) c.partial = event.text; emptyList() }
            is Event.TranscriptFinal -> final(c, event.text)
            Event.TranscriptFailed -> {
                if (c.original != null || c.failed) emptyList()
                else {
                    c.failed = true; c.editOnly = true
                    if (c.phase == Phase.RECORDING) c.phase = Phase.AWAITING_FINAL
                    listOf(Effect.TranscribeFailed)
                }
            }
            is Event.Refined -> if (event.captureId != c.id || c.phase != Phase.REFINING) emptyList() else refined(c, event)
            is Event.RefineDeadline -> deadline(c, event.captureId)
            is Event.HoldElapsed -> {
                val text = c.refined
                if (event.captureId != c.id || c.phase != Phase.HOLDING || text == null) emptyList()
                else if (event.eligibleNow && !c.editOnly) submit(c, text)
                else toComposer(c, text, null)
            }
            is Event.Downgrade -> downgrade(c, event.cause)
            is Event.SendResult -> {
                val text = c.submitted
                if (event.captureId != c.id || c.phase != Phase.SUBMITTED || text == null) emptyList()
                else if (event.accepted) finish(c, emptyList())
                else toComposer(c, text, VoiceComposerReason.NOT_SENT)
            }
        }
    }

    private fun start(e: Event.Start): List<Effect> {
        // A reused id (a replayed Start, or one of a finished capture) must not re-arm a capture that may have sent.
        if (!usedIds.add(e.captureId)) return emptyList()
        // One capture at a time: a new Start supersedes an unfinished one, which can then never send. Text it
        // already had is kept, not dropped; a submitted one stays with the app's send recovery.
        val out = current?.let { old ->
            val text = old.refined ?: old.original
            if (text != null && old.phase != Phase.SUBMITTED) toComposer(old, text, null)
            else cancelIfRefining(old) + Effect.Finished(old.id)
        } ?: emptyList()
        current = Capture(e.captureId, e.origin, e.sendBar)
        return out
    }

    /** A completion tap. Only the first one counts; a later one (a second ✓, ✓ after the cap) changes nothing. */
    private fun choose(c: Capture, intent: Intent): List<Effect> {
        if (c.intent != Intent.NONE || c.phase != Phase.RECORDING) return emptyList()
        c.intent = intent
        if (intent != Intent.SEND) c.editOnly = true
        c.phase = Phase.AWAITING_FINAL
        return emptyList() // the final transcript follows; it lands or refines then
    }

    /** "Keep the text and edit" (or the 90 s cap): edit-only for good, before submitting. */
    private fun edit(c: Capture): List<Effect> = when (c.phase) {
        Phase.RECORDING, Phase.AWAITING_FINAL -> {
            if (c.intent == Intent.NONE || c.intent == Intent.SEND) c.intent = Intent.EDIT
            c.editOnly = true
            c.phase = Phase.AWAITING_FINAL
            emptyList()
        }
        Phase.REFINING -> toComposer(c, c.original!!, null)
        Phase.HOLDING -> toComposer(c, c.refined ?: c.original!!, null)
        Phase.SUBMITTED -> emptyList()
    }

    private fun final(c: Capture, raw: String): List<Effect> {
        if (c.original != null) return emptyList() // a duplicate final
        val text = raw.trim()
        if (text.isBlank()) return finish(c, listOf(Effect.NoSpeech))
        c.original = text
        // Sends only on the user's own ✓ on a send bar; the recogniser ending by itself (intent NONE), an edit,
        // a downgrade or a retry after a failure all land the text in the composer like today.
        if (c.intent != Intent.SEND || c.editOnly || c.failed) return toComposer(c, text, null)
        c.phase = Phase.REFINING
        c.refineOutstanding = true
        return listOf(Effect.RequestRefine(c.id, text), Effect.ShowRefining(text))
    }

    private fun refined(c: Capture, e: Event.Refined): List<Effect> {
        val original = c.original!!
        c.refineOutstanding = false // answered: nothing left to cancel
        if (!e.ok) return toComposer(c, original, failureReason(e.error))
        // §11: an answer whose highlight cannot be rebuilt is not adopted at all.
        val ranges = refineHighlightRanges(original, e.edits, e.text)
        if (ranges == null || e.text.isBlank()) return toComposer(c, original, VoiceComposerReason.NOT_ADOPTED)
        if (!e.autoSend) return toComposer(c, e.text, VoiceComposerReason.REVIEW)
        if (!e.eligibleNow || c.editOnly) return toComposer(c, e.text, null)
        if (ranges.isEmpty()) return submit(c, e.text)
        c.refined = e.text
        c.phase = Phase.HOLDING
        return listOf(Effect.ShowPreview(e.text, ranges))
    }

    private fun deadline(c: Capture, id: String): List<Effect> {
        if (id != c.id) return emptyList()
        return when (c.phase) {
            Phase.REFINING, Phase.HOLDING -> toComposer(c, c.original!!, VoiceComposerReason.TIMEOUT)
            else -> emptyList()
        }
    }

    private fun downgrade(c: Capture, cause: VoiceDowngrade): List<Effect> {
        if (c.phase == Phase.SUBMITTED) return emptyList() // the app's send recovery owns the message now
        c.editOnly = true
        val original = c.original
        return when (cause) {
            VoiceDowngrade.LeftConversation, VoiceDowngrade.Disconnected -> {
                // The capture is abandoned: keep whatever text exists, never claim text that does not.
                val reason = if (cause == VoiceDowngrade.Disconnected) VoiceComposerReason.DISCONNECTED else null
                val partial = c.partial?.trim()?.takeIf { it.isNotBlank() }
                when {
                    original != null -> toComposer(c, original, reason)
                    partial != null -> toComposer(c, partial, reason)
                    else -> finish(c, listOf(Effect.Discard))
                }
            }
            VoiceDowngrade.Backgrounded, VoiceDowngrade.ComposerChanged, VoiceDowngrade.EligibilityLost ->
                // Without text yet the capture carries on edit-only; its final transcript lands later.
                if (original != null) toComposer(c, original, null) else emptyList()
        }
    }

    private fun submit(c: Capture, text: String): List<Effect> {
        c.submitted = text
        c.refined = null
        c.phase = Phase.SUBMITTED
        return listOf(Effect.SubmitSend(c.id, text))
    }

    private fun toComposer(c: Capture, text: String, reason: VoiceComposerReason?): List<Effect> =
        finish(c, cancelIfRefining(c) + Effect.ToComposer(c.origin, text, reason))

    private fun cancelIfRefining(c: Capture): List<Effect> =
        if (c.refineOutstanding) listOf(Effect.CancelRefine(c.id)) else emptyList()

    private fun finish(c: Capture, effects: List<Effect>): List<Effect> {
        if (current === c) current = null
        return effects + Effect.Finished(c.id)
    }

    companion object {
        /** [TranscriptRefineError] code → reason line. Unknown future codes read as "could not refine". */
        fun failureReason(error: String?): VoiceComposerReason = when (error) {
            TranscriptRefineError.INVALID -> VoiceComposerReason.NOT_ADOPTED
            TranscriptRefineError.TIMEOUT -> VoiceComposerReason.TIMEOUT
            else -> VoiceComposerReason.UNAVAILABLE
        }
    }
}
