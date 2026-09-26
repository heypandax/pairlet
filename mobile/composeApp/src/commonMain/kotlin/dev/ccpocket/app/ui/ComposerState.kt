package dev.ccpocket.app.ui

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange

/**
 * The composer's SINGLE source of truth — a [TextFieldState] plus one slot for an external write that
 * must wait for a live IME composition to end.
 *
 * ## Lineage
 *
 * The first shape hoisted the text as a String and reconciled a `TextFieldValue` mirror against it on
 * EVERY recomposition (ImeSafeMirror, #93/#86 → #118 → #108). ChatScreen recomposes continuously
 * (streaming, pulses, timers), so telling a genuine external write from a stale echo of the field itself
 * was a per-frame judgement call, and every misjudgement shipped as a bug — #118 "打逗号整段清空" was a
 * stale parked snapshot landing on the first no-composition commit (a direct-commit punctuation).
 *
 * The second shape (cfab391e) made a `TextFieldValue` the one state and routed every external write
 * through explicit methods. That removed the reconcile lottery, but it still rode the legacy
 * `BasicTextField(value, onValueChange)` round trip: the IME edits a buffer, the field reports a
 * `TextFieldValue`, we store it, recomposition hands it back, and the field's `EditProcessor.reset`
 * re-syncs its buffer AND composition against what we handed back. Every edit crossed that seam
 * twice, and each crossing was a place for the composition to diverge from what the platform IME
 * believed. `BasicTextField(TextFieldState)` was evaluated and REJECTED at the time because on
 * CMP 1.7.3 its iOS CJK support was worse than the legacy field's (composite input fixed in 1.8.0,
 * #1984/#1692; macOS "Pinyin - Simplified" commit bug fixed in 1.11.0, #2763).
 *
 * ## This shape (CMP ≥ 1.12)
 *
 * The [TextFieldState] IS the buffer the IME edits. There is no value round trip: user/IME edits land
 * in the state directly, and the app only ever writes through [setText]/[clear]/[update] — each an
 * explicit `state.edit { }` at the moment a write actually happens (completion tap, clear-on-send,
 * stopTurn refill, draft adopt). Nothing re-pushes on recomposition, so nothing stale exists to land.
 *
 * One rule survives from the earlier shapes, because the state API keeps the hazard: a programmatic
 * edit that changes content while the IME owns a composition makes `TextFieldState` restart the IME
 * (`restartIme = contentChanged && composition != null`), which drops the marked text and commits the
 * raw pinyin letters (#93/#86's signature). So an explicit write that arrives mid-composition is held
 * in [pending] and lands when the composition ends. The WRITE wins over the composition's own outcome
 * — a send-tap's clear beats a late candidate commit, because the user acted on the text as it stood
 * at the tap. The host composable observes the composition through [LandPendingWrites]; a pending is
 * genuine-only and one-shot, and any caret-precise [update] supersedes it (#118's stale-park class
 * cannot recur: there is no reconcile to re-park anything).
 */
class ComposerState(initial: String = "") {
    /** Render this (and only this) in `BasicTextField(state = …)`; mutate via [setText]/[clear]/[update]. */
    val state = TextFieldState(initial, TextRange(initial.length))

    /** An explicit write that arrived mid-IME-composition — lands when the composition ends. */
    internal var pending: String? = null

    /** The composer's current text — what sends, drafts and the slash/@ completers read. Snapshot-backed. */
    val text: String get() = state.text.toString()

    /** The caret/selection, for caret-precise edits (desktop shift+Enter, @-file completion). */
    val selection: TextRange get() = state.selection

    /** True while the IME owns a composition (CJK pinyin as marked text — Gboard keeps even Latin words composing). */
    val composing: Boolean get() = state.composition != null

    /**
     * Explicit whole-text write — clear-on-send, slash/@ completion (mobile), stopTurn refill,
     * draft adopt on a real session switch. Applies immediately with the caret at the end; while a
     * live IME composition owns the field it waits in [pending] and lands when the composition ends
     * (#93/#86: rebuilding mid-composition commits the raw pinyin letters). A write that matches the
     * field is already converged — it only clears any leftover pending (#118's re-sync rule).
     */
    fun setText(value: String) {
        if (text == value) {
            pending = null
        } else if (!composing) {
            pending = null
            state.setTextAndPlaceCursorAtEnd(value)
        } else {
            pending = value
        }
    }

    fun clear() = setText("")

    /**
     * Direct caret-precise write (desktop shift+Enter newline, @-file completion) — the caller
     * places the selection itself. Supersedes any [pending] write: the text that pending was aimed
     * at no longer exists, and letting it linger would ambush the next no-composition commit (#118).
     */
    fun update(newText: String, caret: TextRange) {
        pending = null
        state.edit {
            replace(0, length, newText)
            selection = TextRange(caret.start.coerceIn(0, length), caret.end.coerceIn(0, length))
        }
    }

    /**
     * Lands the [pending] write if the composition has ended. Driven by [LandPendingWrites]; safe to
     * call any time (a no-op while composing or with nothing pending).
     */
    fun landPending() {
        if (composing) return
        val landing = pending ?: return
        pending = null
        state.setTextAndPlaceCursorAtEnd(landing)
    }
}

/**
 * Host-side half of the pending rule: watches the state's composition and lands a held write the
 * moment the IME releases the field. Install once next to the `BasicTextField` that renders [composer].
 */
@Composable
fun LandPendingWrites(composer: ComposerState) {
    LaunchedEffect(composer) {
        snapshotFlow { composer.composing }.collect { composing -> if (!composing) composer.landPending() }
    }
}
