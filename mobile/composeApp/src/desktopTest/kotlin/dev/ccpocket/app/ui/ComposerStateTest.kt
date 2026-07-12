package dev.ccpocket.app.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Locks the [ComposerState] single-source-of-truth contract — successor to the retired ImeSafeMirror
 * reconcile/park state machine (ImeSafeMirrorTest retired with it). One implementation backs every
 * composer (mobile ComposerField and the desktop ChatPane), so each case here binds both platforms.
 *
 * The invariants the old park tower existed for, restated against explicit writes:
 *  - a genuine external write racing a live IME composition still survives and lands when the
 *    composition ends (#93/#86 — Gboard keeps even Latin words composing, so clear-on-send and
 *    completion taps must not be lost mid-composition);
 *  - it lands DETERMINISTICALLY: the write wins over the composition's own outcome. The old tower
 *    resolved this per frame (reconcile re-parked, the IME echo dropped the park), so which side won
 *    was a frame-timing lottery — the "偶发" behind #118's punctuation wipes;
 *  - nothing stale can linger to ambush a later no-composition commit (#118 "打逗号整段清空", #108
 *    a park landing on an iOS candidate commit): a pending is genuine-only and one-shot, and any
 *    caret-precise [ComposerState.update] supersedes it.
 */
class ComposerStateTest {

    /** A live IME composition: the whole field is marked text (a pinyin token mid-input). */
    private fun composing(text: String) =
        TextFieldValue(text, TextRange(text.length), composition = TextRange(0, text.length))

    /** A committed value with no composition — what a direct-commit punctuation (，。！) delivers. */
    private fun committed(text: String) = TextFieldValue(text, TextRange(text.length))

    // ── explicit writes while idle (slash/@ completion, stopTurn refill, draft adopt) ─────────────

    @Test
    fun explicit_write_while_idle_applies_immediately_with_the_caret_at_the_end() {
        val s = ComposerState("abc")
        s.setText("/review ")
        assertEquals("/review ", s.text)
        assertEquals(TextRange("/review ".length), s.field.selection, "external writes land the caret at the end")
        assertNull(s.pending)
    }

    @Test
    fun clear_empties_the_field_and_the_selection() {
        val s = ComposerState("draft text")
        s.clear()
        assertEquals("", s.text)
        assertEquals(TextRange(0), s.field.selection)
    }

    // ── #93/#86: a write racing a live composition lands when the composition ends ────────────────

    @Test
    fun clear_on_send_during_a_composition_pends_and_lands_when_composing_ends() {
        val s = ComposerState()
        s.onValueChange(composing("nihao"))    // the IME owns the field (pinyin as marked text)
        s.clear()                              // send tap: rebuilding mid-composition commits raw letters (#93)
        assertEquals("nihao", s.text, "a live composition holds the field until it ends")
        assertEquals("", s.pending)
        s.onValueChange(committed("你好"))     // the pinyin resolves — the pending clear LANDS
        assertEquals("", s.text, "clear-on-send must clear even when the send raced a composition")
        assertNull(s.pending)
    }

    @Test
    fun a_pending_write_survives_further_composing_instead_of_a_frame_lottery() {
        // iOS pinyin typing an English word (#108's stage): the space-segmented marked text keeps
        // composing across events, then a candidate tap delivers echo + commit back to back.
        val s = ComposerState()
        s.onValueChange(composing("c l a"))
        s.clear()                              // send raced the composition — genuine write pends
        s.onValueChange(composing("c l a u")) // user keeps typing: the write must HOLD, not race
        s.onValueChange(composing("c l a u d e"))
        assertEquals("", s.pending, "a genuine external write survives composing events")
        s.onValueChange(committed("claude"))  // candidate commit ends the composition —
        assertEquals("", s.text, "— and the send's clear wins: the user acted on the text at the tap")
        assertNull(s.pending)
    }

    // ── #118/#108: nothing stale can ambush a later commit ────────────────────────────────────────

    @Test
    fun a_landed_pending_is_one_shot_and_a_later_punctuation_appends_normally() {
        val s = ComposerState()
        s.onValueChange(composing("ni"))
        s.clear()
        s.onValueChange(committed("你"))       // composition ends → the clear lands, one-shot
        assertEquals("", s.text)
        assertNull(s.pending)
        s.onValueChange(committed("，"))       // #118's signature move: a direct-commit punctuation
        assertEquals("，", s.text, "nothing stale is left to roll the field back")
    }

    @Test
    fun a_converged_write_drops_its_pending_without_touching_a_live_composition() {
        val s = ComposerState()
        s.onValueChange(composing("nihao"))
        s.setText("")                          // pends
        s.setText("nihao")                    // a later write converges on the field's own text
        assertNull(s.pending, "a converged write leaves nothing pending to ambush the next commit")
        assertEquals(TextRange(0, 5), s.field.composition, "and must not rebuild the live composition")
        s.onValueChange(committed("nihao，"))
        assertEquals("nihao，", s.text, "punctuation appends normally — no rollback (#118)")
    }

    @Test
    fun a_caret_precise_update_supersedes_a_pending_write() {
        // Desktop shift+Enter while a send's clear is still pending: the text that clear was aimed
        // at no longer exists — letting it linger would wipe the newline'd draft on the next commit,
        // exactly the stale-park ambush class this refactor retires (#118).
        val s = ComposerState()
        s.onValueChange(composing("plan"))
        s.clear()
        s.update(TextFieldValue("plan\n", TextRange(5)))
        assertNull(s.pending, "a caret-precise update supersedes the pending write")
        s.onValueChange(committed("plan\nb"))
        assertEquals("plan\nb", s.text, "the superseded write must not resurrect")
    }

    // ── caret-precise writes (desktop shift+Enter, @-file completion) ─────────────────────────────

    @Test
    fun update_places_the_caret_exactly_where_the_caller_says() {
        val s = ComposerState("hello world")
        // shift+Enter with the caret after "hello": ChatPane splices the newline and sets the caret
        s.update(TextFieldValue("hello\n world", TextRange(6)))
        assertEquals("hello\n world", s.text)
        assertEquals(TextRange(6), s.field.selection)
    }

    // ── plain typing flows through untouched ──────────────────────────────────────────────────────

    @Test
    fun user_edits_pass_through_when_nothing_is_pending() {
        val s = ComposerState()
        s.onValueChange(committed("h"))
        s.onValueChange(committed("hi"))
        assertEquals("hi", s.text)
        assertNull(s.pending)
        s.onValueChange(composing("hi p"))
        assertEquals("hi p", s.text)
        assertEquals(TextRange(0, 4), s.field.composition)
    }
}
