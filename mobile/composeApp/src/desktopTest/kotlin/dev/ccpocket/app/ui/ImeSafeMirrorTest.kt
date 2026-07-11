package dev.ccpocket.app.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Locks the [ImeSafeMirror] reconcile/park state machine — the ONE composer mirror shared by mobile
 * [ComposerField] and the desktop ChatPane, so every case here binds both platforms.
 *
 * Covers the PARK mechanism's original job (#93/#86: an external write racing a live IME composition
 * must survive and land when composing ends) AND the #118 boundary bug it introduced (a park made stale
 * by the field re-syncing upstream must NOT ambush the next no-composition commit — "打逗号整段清空").
 */
class ImeSafeMirrorTest {

    /** A live IME composition: the whole field is marked text (a pinyin token mid-input). */
    private fun composing(text: String) =
        TextFieldValue(text, TextRange(text.length), composition = TextRange(0, text.length))

    /** A committed value with no composition — what a direct-commit punctuation (，。！) delivers. */
    private fun committed(text: String) = TextFieldValue(text, TextRange(text.length))

    // ── #118: stale park must not resurrect on a no-composition commit ────────────────────────────

    @Test
    fun stale_park_from_resynced_upstream_does_not_roll_back_on_punctuation() {
        var upstream = ""
        val m = ImeSafeMirror("").also { it.onExternalChange = { s -> upstream = s } }

        // 1) user starts composing pinyin; the field owns a live composition and upstream tracks it
        m.onValueChange(composing("ni"))            // field="ni"(comp), upstream="ni"
        m.reconcile(upstream)                       // in sync — nothing to park
        assertNull(m.parked)

        // 2) an external write (clear-on-send / per-session draft #88) is seen a frame LATE while the
        //    composition is still live: a streaming recompose reconciles the field against the stale
        //    lagging upstream ("") and PARKS it
        m.reconcile("")                             // field="ni"(comp) != "" && composing → park ""
        assertEquals("", m.parked)

        // 3) the user keeps composing; onValueChange echoes the field back up, so upstream re-converges
        //    on the field and the mismatch vanishes — the now-orphaned park MUST be dropped here (#118 fix)
        m.onValueChange(composing("nih"))           // field="nih"(comp), upstream="nih"
        m.reconcile(upstream)                       // field.text=="nih"==value → re-synced
        assertNull(m.parked, "a park orphaned by the field re-syncing upstream must not linger")

        // 4) the user direct-commits a punctuation "，" (composition==null): it must APPEND, not
        //    resurrect the stale "" snapshot and wipe the field
        m.onValueChange(committed("nih，"))
        assertEquals("nih，", m.field.text, "punctuation must append, not roll back to the stale snapshot")
        assertEquals("nih，", upstream)
        assertNull(m.parked)
    }

    @Test
    fun in_sync_reconcile_drops_a_stale_park_without_touching_the_field() {
        val m = ImeSafeMirror("abc")
        m.parked = "STALE"
        m.reconcile("abc")                          // field.text=="abc"==value → re-synced
        assertNull(m.parked, "reconcile must clear a stale park the instant mirror and upstream agree")
        assertEquals("abc", m.field.text)           // and must leave the field alone
    }

    // ── #108: an iOS candidate commit is TWO onValueChange calls in ONE event-loop turn ───────────

    @Test
    fun echo_supersedes_a_stale_park_before_a_same_turn_commit() {
        var upstream = ""
        val m = ImeSafeMirror("").also { it.onExternalChange = { s -> upstream = s } }

        // iOS pinyin keyboard typing an English word: the composition IS the space-segmented marked text
        m.onValueChange(composing("c l a u d e"))   // field owns the composition, upstream tracks it
        // a recompose reconciles against a stale lagging upstream (draft re-key / clear-on-send) — PARK
        m.reconcile("")
        assertEquals("", m.parked)

        // the user taps the "claude" candidate: setMarkedText (echo) + insertText (commit) arrive back to
        // back with NO recompose between — reconcile never gets a chance to drop the now-stale park
        m.onValueChange(composing("c l a u d e"))   // echo: upstream re-converges on the field
        assertNull(m.parked, "the echo re-converges upstream — a parked write is superseded and must drop")
        m.onValueChange(committed("claude"))        // the commit must land as chosen…
        assertEquals("claude", m.field.text, "…not roll back to the stale parked snapshot")
        assertEquals("claude", upstream)
    }

    // ── #93/#86 regression: a genuine pending external write must STILL survive a composition ──────

    @Test
    fun parked_external_write_still_lands_when_the_composition_ends() {
        var upstream = "nihao"
        val m = ImeSafeMirror("nihao").also { it.onExternalChange = { s -> upstream = s } }
        m.field = composing("nihao")                // a live composition owns the field

        // clear-on-send (or a slash/@ completion) writes upstream while composing — PARK it
        m.reconcile("")                             // field="nihao"(comp) != "" && composing → park ""
        assertEquals("", m.parked)
        // streaming keeps recomposing with the SAME still-pending write (field never re-syncs), so the
        // park must survive — this is exactly what skip-only lost and PARK was built to keep (#93/#86)
        m.reconcile("")
        assertEquals("", m.parked, "a still-pending external write must stay parked across recomposes")

        // the composition ends (pinyin resolves) with no composition — the parked clear LANDS
        m.onValueChange(committed("你好"))
        assertEquals("", m.field.text, "the parked external write must land when composing ends (#93/#86)")
        assertEquals("", upstream)
        assertNull(m.parked)
    }

    // ── baseline reconcile paths (unchanged behavior) ─────────────────────────────────────────────

    @Test
    fun external_write_while_idle_applies_immediately_with_the_caret_at_the_end() {
        val m = ImeSafeMirror("abc")                // field="abc", composition==null
        m.reconcile("/review ")                     // not composing → apply immediately (slash completion)
        assertEquals("/review ", m.field.text)
        assertEquals(TextRange("/review ".length), m.field.selection, "external writes land the caret at the end")
        assertNull(m.parked)
    }

    @Test
    fun punctuation_commit_appends_normally_when_nothing_is_parked() {
        var upstream = "nih"
        val m = ImeSafeMirror("nih").also { it.onExternalChange = { s -> upstream = s } }
        m.field = composing("nih")
        m.reconcile("nih")                          // in sync, nothing parked
        m.onValueChange(committed("nih，"))
        assertEquals("nih，", m.field.text)
        assertEquals("nih，", upstream)
        assertNull(m.parked)
    }
}
