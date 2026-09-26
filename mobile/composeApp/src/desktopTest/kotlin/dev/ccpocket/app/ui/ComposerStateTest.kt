package dev.ccpocket.app.ui

import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the [ComposerState] single-source-of-truth contract on its `TextFieldState` shape — successor
 * to the `TextFieldValue` round trip (cfab391e) and, before it, the retired ImeSafeMirror reconcile/park
 * state machine. One implementation backs every composer (mobile ComposerField and the desktop
 * ChatPane), so each case here binds both platforms.
 *
 * The invariants restated against the state API:
 *  - a genuine external write racing a live IME composition still survives and lands when the
 *    composition ends (#93/#86 — a programmatic edit mid-composition makes `TextFieldState` restart
 *    the IME, which commits the raw pinyin letters; Gboard keeps even Latin words composing, so
 *    clear-on-send and completion taps must not be lost mid-composition);
 *  - it lands DETERMINISTICALLY: the write wins over the composition's own outcome;
 *  - nothing stale can linger to ambush a later no-composition commit (#118 "打逗号整段清空", #108
 *    a park landing on an iOS candidate commit): a pending is genuine-only and one-shot, and any
 *    caret-precise [ComposerState.update] supersedes it.
 *
 * User/IME edits are driven through the state's own user-edit path ([typeAsUser]) so the composition
 * bookkeeping under test is the real one, not a stand-in; the composition setters are foundation-
 * internal, hence reflection. [ComposerState.landPending] is what [LandPendingWrites] calls whenever
 * it observes the composition end — the tests call it at exactly those points.
 */
class ComposerStateTest {

    // ── the IME's side of the seam, via TextFieldState.editAsUser (foundation-internal) ─────────

    private val editAsUser = TextFieldState::class.java.methods.single { it.name == "editAsUser\$foundation" }
    private val setComposition = TextFieldBuffer::class.java.methods.single { it.name == "setComposition\$foundation" }
    private val commitComposition = TextFieldBuffer::class.java.methods.single { it.name == "commitComposition\$foundation" }
    private val mergeUndo = Class.forName("androidx.compose.foundation.text.input.internal.undo.TextFieldEditUndoBehavior")
        .enumConstants.single { (it as Enum<*>).name == "MergeIfPossible" }

    /** A user/IME edit: replaces the whole buffer, then marks [composition] (or commits, when null). */
    private fun ComposerState.typeAsUser(text: String, composition: TextRange?) {
        val block: (TextFieldBuffer) -> Unit = { buf ->
            buf.replace(0, buf.length, text)
            buf.placeCursorBeforeCharAt(text.length)
            if (composition == null) commitComposition.invoke(buf)
            else setComposition.invoke(buf, composition.start, composition.end, null)
        }
        editAsUser.invoke(state, null, true, mergeUndo, block)
    }

    /** A live IME composition: the whole field is marked text (a pinyin token mid-input). */
    private fun ComposerState.composing(text: String) = typeAsUser(text, TextRange(0, text.length))

    /** A committed value with no composition — what a direct-commit punctuation (，。！) delivers. */
    private fun ComposerState.committed(text: String) = typeAsUser(text, null)

    // ── explicit writes while idle (slash/@ completion, stopTurn refill, draft adopt) ─────────────

    @Test
    fun explicit_write_while_idle_applies_immediately_with_the_caret_at_the_end() {
        val s = ComposerState("abc")
        s.setText("/review ")
        assertEquals("/review ", s.text)
        assertEquals(TextRange("/review ".length), s.selection, "external writes land the caret at the end")
        assertNull(s.pending)
    }

    @Test
    fun clear_empties_the_field_and_the_selection() {
        val s = ComposerState("draft text")
        s.clear()
        assertEquals("", s.text)
        assertEquals(TextRange(0), s.selection)
    }

    // ── the harness itself: the state's real composition bookkeeping is what we drive ─────────────

    @Test
    fun a_user_edit_marks_and_commits_the_composition_on_the_real_state() {
        val s = ComposerState()
        s.composing("nihao")
        assertTrue(s.composing, "marked text puts the IME in charge of the field")
        assertEquals(TextRange(0, 5), s.state.composition)
        s.committed("你好")
        assertFalse(s.composing)
        assertEquals("你好", s.text)
    }

    // ── #93/#86: a write racing a live composition lands when the composition ends ────────────────

    @Test
    fun clear_on_send_during_a_composition_pends_and_lands_when_composing_ends() {
        val s = ComposerState()
        s.composing("nihao")                   // the IME owns the field (pinyin as marked text)
        s.clear()                              // send tap: an edit mid-composition restarts the IME (#93)
        assertEquals("nihao", s.text, "a live composition holds the field until it ends")
        assertEquals(TextRange(0, 5), s.state.composition, "and the composition itself is untouched")
        assertEquals("", s.pending)
        s.landPending()                        // the host observes: still composing → nothing lands
        assertEquals("nihao", s.text)
        s.committed("你好")                    // the pinyin resolves —
        s.landPending()                        // — the host observes the composition end: the clear LANDS
        assertEquals("", s.text, "clear-on-send must clear even when the send raced a composition")
        assertNull(s.pending)
    }

    @Test
    fun a_pending_write_survives_further_composing_instead_of_a_frame_lottery() {
        // iOS pinyin typing an English word (#108's stage): the space-segmented marked text keeps
        // composing across events, then a candidate tap delivers the commit.
        val s = ComposerState()
        s.composing("c l a")
        s.clear()                              // send raced the composition — genuine write pends
        s.composing("c l a u")                 // user keeps typing: the write must HOLD, not race
        s.landPending()
        s.composing("c l a u d e")
        s.landPending()
        assertEquals("", s.pending, "a genuine external write survives composing events")
        assertEquals("c l a u d e", s.text)
        s.committed("claude")                  // candidate commit ends the composition —
        s.landPending()
        assertEquals("", s.text, "— and the send's clear wins: the user acted on the text at the tap")
        assertNull(s.pending)
    }

    // ── #118/#108: nothing stale can ambush a later commit ────────────────────────────────────────

    @Test
    fun a_landed_pending_is_one_shot_and_a_later_punctuation_appends_normally() {
        val s = ComposerState()
        s.composing("ni")
        s.clear()
        s.committed("你")                      // composition ends → the clear lands, one-shot
        s.landPending()
        assertEquals("", s.text)
        assertNull(s.pending)
        s.committed("，")                      // #118's signature move: a direct-commit punctuation
        s.landPending()
        assertEquals("，", s.text, "nothing stale is left to roll the field back")
    }

    @Test
    fun a_converged_write_drops_its_pending_without_touching_a_live_composition() {
        val s = ComposerState()
        s.composing("nihao")
        s.setText("")                          // pends
        s.setText("nihao")                     // a later write converges on the field's own text
        assertNull(s.pending, "a converged write leaves nothing pending to ambush the next commit")
        assertEquals(TextRange(0, 5), s.state.composition, "and must not rebuild the live composition")
        s.committed("nihao，")
        s.landPending()
        assertEquals("nihao，", s.text, "punctuation appends normally — no rollback (#118)")
    }

    @Test
    fun a_caret_precise_update_supersedes_a_pending_write() {
        // Desktop shift+Enter while a send's clear is still pending: the text that clear was aimed
        // at no longer exists — letting it linger would wipe the newline'd draft on the next commit,
        // exactly the stale-park ambush class this refactor retires (#118).
        val s = ComposerState()
        s.composing("plan")
        s.clear()
        s.update("plan\n", TextRange(5))
        assertNull(s.pending, "a caret-precise update supersedes the pending write")
        s.committed("plan\nb")
        s.landPending()
        assertEquals("plan\nb", s.text, "the superseded write must not resurrect")
    }

    @Test
    fun plain_typing_never_lands_anything_when_nothing_is_pending() {
        val s = ComposerState()
        s.committed("h")
        s.landPending()
        s.committed("hi")
        s.landPending()
        assertEquals("hi", s.text)
        assertNull(s.pending)
        s.composing("hi p")
        assertEquals("hi p", s.text)
        assertEquals(TextRange(0, 4), s.state.composition)
    }

    // ── caret-precise writes (desktop shift+Enter, @-file completion) ─────────────────────────────

    @Test
    fun update_places_the_caret_exactly_where_the_caller_says() {
        val s = ComposerState("hello world")
        // shift+Enter with the caret after "hello": ChatPane splices the newline and sets the caret
        s.update("hello\n world", TextRange(6))
        assertEquals("hello\n world", s.text)
        assertEquals(TextRange(6), s.selection)
    }

    @Test
    fun update_clamps_a_caret_beyond_the_new_text() {
        val s = ComposerState("hello world")
        s.update("hi", TextRange(40))
        assertEquals("hi", s.text)
        assertEquals(TextRange(2), s.selection)
    }
}
