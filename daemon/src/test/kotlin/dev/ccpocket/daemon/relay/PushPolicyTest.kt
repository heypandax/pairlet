package dev.ccpocket.daemon.relay

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure push gate + copy decisions behind the relay client's notify hooks (issue #138):
 * usage-limit detection on turn-error text, the three turn-push flavors, and the permission-ask
 * push (bridge #91 / owner #138 — switch-gated only, since issue #382 was applied to asks).
 */
class PushPolicyTest {

    private val wd = Path.of("/home/u/proj/cc-pocket")

    // ---- usage-limit matching (pattern-based; see the provenance note in PushPolicy) ----

    @Test
    fun known_limit_wordings_match() {
        // the classic `claude -p` result text when the subscription window is exhausted
        assertTrue(PushPolicy.isUsageLimit("Claude AI usage limit reached|1720000000"))
        // newer interactive-banner wordings
        assertTrue(PushPolicy.isUsageLimit("5-hour limit reached ∙ resets 3am"))
        assertTrue(PushPolicy.isUsageLimit("Weekly limit reached ∙ resets Thursday"))
        assertTrue(PushPolicy.isUsageLimit("Session limit reached ∙ resets 11pm"))
        // raw API 429 shapes
        assertTrue(PushPolicy.isUsageLimit("""API Error: 429 {"type":"error","error":{"type":"rate_limit_error","message":"..."}}"""))
        assertTrue(PushPolicy.isUsageLimit("Rate limit reached, please wait"))
        // extra-usage balance / Codex wording
        assertTrue(PushPolicy.isUsageLimit("You are out of extra usage"))
        assertTrue(PushPolicy.isUsageLimit("You've hit your usage limit."))
    }

    @Test
    fun ordinary_errors_do_not_match() {
        assertFalse(PushPolicy.isUsageLimit(null))
        assertFalse(PushPolicy.isUsageLimit("turn failed"))
        assertFalse(PushPolicy.isUsageLimit("agent process ended (exit 1) — Error: bad session id"))
        // errors that merely mention a *different* kind of limit must not read as a usage limit
        assertFalse(PushPolicy.isUsageLimit("Prompt is too long: exceeds the context limit"))
        assertFalse(PushPolicy.isUsageLimit("frame size limit exceeded (4 MiB)"))
        assertFalse(PushPolicy.isUsageLimit("Request exceeds size limits"))
    }

    // ---- turn push copy ----

    @Test
    fun clean_turn_keeps_the_original_copy() {
        val p = PushPolicy.turnPush(wd, "sid1", "All done.\nDetails below.", error = null)
        assertEquals("cc-pocket", p.title)
        assertEquals("All done.", p.body)
        assertEquals(wd.toString(), p.workdir)
        assertEquals("sid1", p.sessionId)
        assertFalse(p.urgent)
    }

    @Test
    fun clean_turn_without_text_says_turn_complete() {
        assertEquals("Turn complete", PushPolicy.turnPush(wd, null, null, null).body)
    }

    @Test
    fun error_turn_is_worded_as_a_failure() {
        val p = PushPolicy.turnPush(wd, "sid1", finalText = null, error = "agent process ended (exit 137)")
        assertEquals("Session error — cc-pocket", p.title)
        assertTrue(p.body.startsWith("Turn stopped: agent process ended"), "got: ${p.body}")
        assertTrue(p.body.length <= 140)
    }

    @Test
    fun limit_hit_gets_its_own_title() {
        val p = PushPolicy.turnPush(wd, "sid1", finalText = null, error = "Claude AI usage limit reached|1720000000")
        assertEquals("Usage limit hit — cc-pocket", p.title)
        assertTrue("usage limit reached" in p.body.lowercase(), "got: ${p.body}")
    }

    // ---- ask push (issue #382 applied to asks: presence is not an input any more) ----

    @Test
    fun bridge_ask_pushes_urgent_as_approval() {
        val p = PushPolicy.askPushFor(pushEnabled = true, wd, "sid1", origin = "ci-bot", tool = "Run command")
        assertNotNull(p)
        assertTrue(p.urgent)
        assertEquals("approval", p.kind)
        assertEquals("Approval needed — ci-bot", p.title)
        assertTrue("Run command" in p.body)
        assertEquals("sid1", p.sessionId)
        assertEquals(wd.toString(), p.workdir)
    }

    @Test
    fun owner_ask_pushes_urgent_regardless_of_watchers_or_presence() {
        // The old gate suppressed an owner ask whenever a client was attached to the conversation AND the relay
        // peer or a LAN client was online — the desktop App satisfies both around the clock, so owner asks never
        // pushed at all. The policy no longer takes `watched` / peerOnline / lanConnected: the compile-time absence
        // is the guarantee, and `urgent` keeps the relay's own interactive-device check from swallowing the push
        // while the phone or the desktop is attached in a different session.
        val p = PushPolicy.askPushFor(pushEnabled = true, wd, "sid1", origin = null, tool = "Edit file")
        assertNotNull(p)
        assertTrue(p.urgent, "an owner ask must bypass the relay's interactive-device gate")
        assertEquals("approval", p.kind)
        assertEquals("Approval needed — cc-pocket", p.title)
        assertEquals("Edit file is waiting for your decision", p.body)
    }

    @Test
    fun switch_off_pushes_no_ask_at_all() {
        // prefs.pushEnabled is the ONLY gate — bridge and owner alike, exactly like turnPushFor
        assertNull(PushPolicy.askPushFor(pushEnabled = false, wd, "sid1", origin = null, tool = "Run command"))
        assertNull(PushPolicy.askPushFor(pushEnabled = false, wd, "sid1", origin = "ci-bot", tool = "Run command"))
    }

    @Test
    fun ask_push_carries_the_anchor_it_is_given_before_a_session_exists() {
        // a request-level bridge approval can land before the first turn mints a session id: the conversation
        // passes its convoId as the routing anchor and the push must carry it untouched
        assertEquals("convo-anchor", PushPolicy.askPush(wd, "convo-anchor", origin = "ci-bot", tool = "Run command").sessionId)
    }

    // ---- usage-limit reset-moment parse (issue #137: TurnDone.usageLimitResetAt) ----

    @Test
    fun limit_reset_epoch_parses_seconds_to_millis() {
        assertEquals(1_720_000_000_000L, PushPolicy.usageLimitResetAtMs("Claude AI usage limit reached|1720000000"))
        // already-millis stays as-is (a peer that sends 13 digits)
        assertEquals(1_720_000_000_000L, PushPolicy.usageLimitResetAtMs("usage limit reached|1720000000000"))
    }

    @Test
    fun limit_reset_epoch_absent_or_not_a_limit_yields_null() {
        // a limit hit whose wording carries no epoch — the button just doesn't show
        assertNull(PushPolicy.usageLimitResetAtMs("5-hour limit reached ∙ resets 3am"))
        // an epoch-looking number in a NON-limit error must not light the button
        assertNull(PushPolicy.usageLimitResetAtMs("turn failed |1720000000"))
        assertNull(PushPolicy.usageLimitResetAtMs(null))
        // a pipe followed by a too-short number is not an epoch
        assertNull(PushPolicy.usageLimitResetAtMs("Claude AI usage limit reached|42"))
    }
}
