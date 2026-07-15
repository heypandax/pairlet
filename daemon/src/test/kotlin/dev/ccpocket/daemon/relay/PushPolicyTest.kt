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
 * push gate (bridge #91 always / owner sessions by watcher + presence).
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

    // ---- ask push gate ----

    @Test
    fun bridge_ask_always_pushes_urgent() {
        // even watched + peer online: the bridge can't see the ask, and an online phone may be elsewhere
        val p = PushPolicy.askPush(wd, "sid1", origin = "ci-bot", tool = "Run command", watched = true, peerOnline = true, lanConnected = true)
        assertNotNull(p)
        assertTrue(p.urgent)
        assertEquals("Approval needed — ci-bot", p.title)
        assertTrue("Run command" in p.body)
    }

    @Test
    fun owner_ask_with_no_watcher_pushes_urgent() {
        // nobody attached to the conversation: the card reached no client — urgent so the relay's
        // interactive-socket suppression can't swallow it while the phone sits in a different session
        val p = PushPolicy.askPush(wd, "sid1", origin = null, tool = "Edit file", watched = false, peerOnline = true, lanConnected = false)
        assertNotNull(p)
        assertTrue(p.urgent)
        assertEquals("Approval needed — cc-pocket", p.title)
    }

    @Test
    fun owner_ask_watched_but_phone_gone_pushes_non_urgent() {
        // a locked phone leaves a stale sink attached — push, but let the relay re-check live sockets
        val p = PushPolicy.askPush(wd, "sid1", origin = null, tool = "Run command", watched = true, peerOnline = false, lanConnected = false)
        assertNotNull(p)
        assertFalse(p.urgent)
    }

    @Test
    fun owner_ask_with_a_present_watcher_is_suppressed() {
        // an attached, present client got the ask card on the data plane — no lock-screen double alert
        assertNull(PushPolicy.askPush(wd, "sid1", origin = null, tool = "Run command", watched = true, peerOnline = true, lanConnected = false))
        assertNull(PushPolicy.askPush(wd, "sid1", origin = null, tool = "Run command", watched = true, peerOnline = false, lanConnected = true))
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
