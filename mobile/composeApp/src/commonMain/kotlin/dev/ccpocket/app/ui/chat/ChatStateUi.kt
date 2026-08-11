package dev.ccpocket.app.ui.chat

import dev.ccpocket.app.ui.session.StateAction
import dev.ccpocket.app.ui.session.StateMark
import dev.ccpocket.app.ui.session.StateTone
import dev.ccpocket.app.ui.session.SurfaceState
import dev.ccpocket.app.ui.session.action
import dev.ccpocket.app.ui.session.mark
import dev.ccpocket.app.ui.session.tone
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.isQuestion

/**
 * Chat's pinned state block — pure, Compose-free, unit-testable (Mobile UI 2.0 · A Master Core v1).
 *
 * Shares [SurfaceState] with the Sessions list on purpose: the two surfaces must name the same session's
 * state identically in the same frame, and one ladder is the only way to guarantee that.
 */
data class ChatStateUi(
    val state: SurfaceState,
    /** The real ask title behind an intervention. Never a client-authored summary; null when there is none. */
    val detail: String?,
    /**
     * A genuinely streaming turn UNDER an open approval/question — the design's demoted qualifying line
     * ("Chat never shows Running alone while an approval is outstanding"). Never the lead, never inferred.
     */
    val alsoRunning: Boolean,
) {
    val mark: StateMark get() = state.mark
    val tone: StateTone get() = state.tone

    /** The intervention's own surface owns the decision; this only routes the user there. */
    val action: StateAction? get() = state.action
}

/**
 * Select the ONE state Chat pins under its header, from current facts only.
 *
 * Returns null when nothing needs a pinned block: reading back history is not a state, so no
 * "Complete"/"New result" block is invented to fill the slot — and a streaming turn ALONE pins nothing
 * either. The composer already writes execution where the user acts (the "sends will queue" note plus the
 * Stop control), so a second full-width "Running" band above the stream said the same thing twice while
 * costing the transcript a row. Running survives in the vocabulary as the demoted [ChatStateUi.alsoRunning]
 * qualifier under an intervention, and on the Sessions rows. `Failure` is the daemon-backed
 * [sessionDegraded] flag — nothing here is derived from transcript content or elapsed time.
 */
fun chatStateUi(
    pendingAsk: PermissionAsk?,
    sessionDegraded: Boolean,
    streaming: Boolean,
): ChatStateUi? = when {
    pendingAsk != null && !pendingAsk.isQuestion ->
        ChatStateUi(SurfaceState.APPROVAL, pendingAsk.title.takeIf { it.isNotBlank() }, alsoRunning = streaming)
    pendingAsk != null ->
        ChatStateUi(SurfaceState.ANSWER, pendingAsk.title.takeIf { it.isNotBlank() }, alsoRunning = streaming)
    sessionDegraded -> ChatStateUi(SurfaceState.FAILURE, detail = null, alsoRunning = false)
    else -> null
}
