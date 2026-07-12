package dev.ccpocket.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * The composer's SINGLE source of truth — successor to the retired ImeSafeMirror reconcile/park
 * tower (#93/#86 → #118 → #108).
 *
 * The old shape hoisted the text as a String (ChatScreen's `input`, the desktop model's `composer`)
 * and reconciled the field against it on EVERY recomposition. ChatScreen recomposes continuously
 * (streaming, pulses, timers), so telling a genuine external write from a stale echo of the field
 * itself was a per-frame judgement call: reconcile parked, the next IME event dropped the park, the
 * next frame re-parked — which one won a live pinyin composition was a frame-timing lottery, and
 * every misjudgement shipped as a bug (#118 "打逗号整段清空", #108 a park landing on a candidate
 * commit). Here the [TextFieldValue] itself is the one state: there is no upstream copy and nothing
 * re-pushes on recomposition. The field changes only through [onValueChange] (the user/IME) or an
 * explicit call ([setText]/[clear]/[update]) at the moment a write actually happens — completion
 * tap, clear-on-send, stopTurn refill, draft adopt. The stale-echo ambiguity is gone by construction.
 *
 * One piece of the old machinery survives, shrunk to a single slot: an explicit write that arrives
 * while the IME owns a live composition (CJK pinyin as marked text — Gboard keeps even Latin words
 * composing) must not rebuild the field mid-composition (that commits the raw letters, #93/#86), so
 * it is held in [pending] and lands the instant the composition ends. The WRITE wins over the
 * composition's own outcome — a send-tap's clear beats a late candidate commit, because the user
 * acted on the text as it stood at the tap. Unlike the old park, a pending can only ever be a
 * genuine external write; reconcile's stale snapshots no longer exist to ambush anything.
 *
 * Why not `BasicTextField(TextFieldState)`: evaluated and REJECTED on CMP 1.7.3. iOS support there
 * is 1.7.0's "basic support" — composite (CJK) input was fixed only in 1.8.0 (#1984, #1692), and the
 * magnifier, native tap/long-tap behavior and floating cursor only ARRIVED in 1.8.0; the macOS
 * "Pinyin - Simplified" commit bug in the state field was fixed only in CMP 1.11.0. On this version
 * the state API regresses exactly the CJK surfaces this class protects. Once CMP is upgraded past
 * those fixes, [setText]/[clear]/[update] map 1:1 onto `TextFieldState.edit { }` and this class
 * collapses into a thin alias.
 */
class ComposerState(initial: String = "") {
    /** Render this (and only this) in the BasicTextField; mutate via [onValueChange]/[update]. */
    var field by mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
        private set

    /** An explicit write that arrived mid-IME-composition — lands when the composition ends. */
    internal var pending: String? = null

    /** The composer's current text — what sends, drafts and the slash/@ completers read.
     *  (`this.` qualifies past Kotlin's backing-field keyword inside an accessor.) */
    val text: String get() = this.field.text

    /** Pass as the BasicTextField's onValueChange: every user/IME edit flows through here. */
    fun onValueChange(new: TextFieldValue) {
        val landing = pending?.takeIf { new.composition == null }
        if (landing != null) {
            pending = null
            field = TextFieldValue(landing, TextRange(landing.length))
        } else {
            field = new
        }
    }

    /**
     * Explicit whole-text write — clear-on-send, slash/@ completion (mobile), stopTurn refill,
     * draft adopt on a real session switch. Applies immediately with the caret at the end; while a
     * live IME composition owns the field it waits in [pending] and lands when the composition ends
     * (#93/#86: rebuilding mid-composition commits the raw pinyin letters). A write that matches the
     * field is already converged — it only clears any leftover pending (mirrors the old reconcile's
     * re-sync rule, #118).
     */
    fun setText(value: String) {
        if (field.text == value) {
            pending = null
        } else if (field.composition == null) {
            pending = null
            field = TextFieldValue(value, TextRange(value.length))
        } else {
            pending = value
        }
    }

    fun clear() = setText("")

    /**
     * Direct caret-precise write (desktop shift+Enter newline, @-file completion) — the caller
     * places the selection itself. Supersedes any [pending] write: the text that pending was aimed
     * at no longer exists, and letting it linger would ambush the next no-composition commit —
     * exactly the stale-park bug class this refactor retires (#118).
     */
    fun update(new: TextFieldValue) {
        pending = null
        field = new
    }
}
