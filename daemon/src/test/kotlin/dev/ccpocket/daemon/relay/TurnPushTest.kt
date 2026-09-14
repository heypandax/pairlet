package dev.ccpocket.daemon.relay

import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #382: a finished turn (complete / error / usage limit) pushes to the phone whenever the desktop's
 * "notify my phone" switch ([dev.ccpocket.daemon.DaemonPrefs.pushEnabled]) is on — no longer skipped because
 * the desktop App (LAN) or any other device is online. Bursts on one session are coalesced for a short window.
 */
class TurnPushTest {

    private val wd = Path.of("/home/u/proj/cc-pocket")
    private val limit = "Claude AI usage limit reached|1720000000"

    // ---- turnPushFor: the switch is the only gate ----

    @Test
    fun enabled_turn_push_is_urgent_and_keeps_the_task_complete_channel() {
        val p = assertNotNull(PushPolicy.turnPushFor(pushEnabled = true, wd, "sid1", "All done.", error = null))
        assertTrue(p.urgent, "urgent = bypass the relay's online check (#382)")
        assertNull(p.kind, "kind stays null → Android task_complete channel")
        assertEquals("All done.", p.body)
        assertEquals("sid1", p.sessionId)
    }

    @Test
    fun error_and_limit_turn_pushes_are_urgent_too() {
        val e = assertNotNull(PushPolicy.turnPushFor(true, wd, "sid1", null, "agent process ended (exit 137)"))
        assertTrue(e.urgent); assertNull(e.kind); assertTrue(e.title.startsWith("Session error"))
        val l = assertNotNull(PushPolicy.turnPushFor(true, wd, "sid1", null, limit))
        assertTrue(l.urgent); assertNull(l.kind); assertTrue(l.title.startsWith("Usage limit hit"))
    }

    @Test
    fun disabled_switch_pushes_nothing() {
        assertNull(PushPolicy.turnPushFor(pushEnabled = false, wd, "sid1", "done", null))
        assertNull(PushPolicy.turnPushFor(pushEnabled = false, wd, "sid1", null, "boom"))
        assertNull(PushPolicy.turnPushFor(pushEnabled = false, wd, "sid1", null, limit))
    }

    @Test
    fun turn_kind_classification() {
        assertEquals(TurnKind.COMPLETE, PushPolicy.turnKindOf(null))
        assertEquals(TurnKind.ERROR, PushPolicy.turnKindOf("turn failed"))
        assertEquals(TurnKind.LIMIT, PushPolicy.turnKindOf(limit))
    }

    // ---- coalescing ----

    private class Clock(var now: Long = 1_000_000L)

    private fun gate(clock: Clock) = TurnPushCoalescer(windowMs = 30_000L, clock = { clock.now })

    @Test
    fun same_kind_within_window_pushes_once_then_again_after_window() {
        val c = Clock(); val g = gate(c)
        assertIs<TurnPushDecision.Queued>(g.decide(true, wd, "sid1", "one", null))
        c.now += 10_000
        assertIs<TurnPushDecision.Coalesced>(g.decide(true, wd, "sid1", "two", null))
        c.now += 19_999
        assertIs<TurnPushDecision.Coalesced>(g.decide(true, wd, "sid1", "three", null))
        c.now += 1 // 30s since the last PUSHED one
        assertIs<TurnPushDecision.Queued>(g.decide(true, wd, "sid1", "four", null))
    }

    @Test
    fun error_is_not_swallowed_by_an_earlier_complete_but_complete_after_error_is() {
        val c = Clock(); val g = gate(c)
        assertIs<TurnPushDecision.Queued>(g.decide(true, wd, "sid1", "done", null))
        c.now += 1_000
        val err = g.decide(true, wd, "sid1", null, "boom")
        assertIs<TurnPushDecision.Queued>(err)
        assertEquals(TurnKind.ERROR, err.kind)
        c.now += 1_000
        assertIs<TurnPushDecision.Coalesced>(g.decide(true, wd, "sid1", "done again", null))
        c.now += 1_000
        assertIs<TurnPushDecision.Coalesced>(g.decide(true, wd, "sid1", null, "boom again"))
        c.now += 1_000
        assertIs<TurnPushDecision.Queued>(g.decide(true, wd, "sid1", null, limit), "limit outranks error")
        c.now += 1_000
        assertIs<TurnPushDecision.Coalesced>(g.decide(true, wd, "sid1", null, "boom 3"))
    }

    @Test
    fun different_sessions_and_workdirs_do_not_coalesce() {
        val c = Clock(); val g = gate(c)
        assertIs<TurnPushDecision.Queued>(g.decide(true, wd, "sid1", "a", null))
        assertIs<TurnPushDecision.Queued>(g.decide(true, wd, "sid2", "b", null))
        assertIs<TurnPushDecision.Queued>(g.decide(true, Path.of("/other"), "sid1", "c", null))
    }

    @Test
    fun clock_stepping_backwards_counts_as_window_elapsed() {
        val c = Clock(); val g = gate(c)
        assertIs<TurnPushDecision.Queued>(g.decide(true, wd, "sid1", "a", null))
        c.now -= 3_600_000 // wall clock jumped back an hour
        assertIs<TurnPushDecision.Queued>(g.decide(true, wd, "sid1", "b", null), "a negative age must not coalesce")
        c.now += 1_000
        assertIs<TurnPushDecision.Coalesced>(g.decide(true, wd, "sid1", "c", null), "window re-armed at the new time")
    }

    @Test
    fun default_clock_is_monotonic() {
        // a default coalescer must not read the wall clock: two decides back-to-back still coalesce, which
        // they would also do on a wall clock — so assert the source instead
        val src = listOf(
            File("src/main/kotlin/dev/ccpocket/daemon/relay/TurnPushCoalescer.kt"),
            File("daemon/src/main/kotlin/dev/ccpocket/daemon/relay/TurnPushCoalescer.kt"),
        ).first { it.isFile }.readText()
        assertFalse("currentTimeMillis" in src, "default clock must be monotonic (nanoTime)")
        assertTrue("nanoTime" in src)
        val g = TurnPushCoalescer()
        assertIs<TurnPushDecision.Queued>(g.decide(true, wd, "sid1", "a", null))
        assertIs<TurnPushDecision.Coalesced>(g.decide(true, wd, "sid1", "b", null))
    }

    @Test
    fun missing_session_id_never_coalesces() {
        val c = Clock(); val g = gate(c)
        assertIs<TurnPushDecision.Queued>(g.decide(true, wd, null, "a", null))
        c.now += 1_000
        assertIs<TurnPushDecision.Queued>(g.decide(true, wd, null, "b", null))
        assertIs<TurnPushDecision.Queued>(g.decide(true, wd, "", "c", null))
        assertEquals(0, g.trackedCount(), "sid-less pushes are not tracked")
    }

    @Test
    fun expired_entries_are_pruned_past_64() {
        val c = Clock(); val g = gate(c)
        repeat(64) { assertIs<TurnPushDecision.Queued>(g.decide(true, wd, "sid$it", "a", null)) }
        assertEquals(64, g.trackedCount())
        c.now += 30_000
        assertIs<TurnPushDecision.Queued>(g.decide(true, wd, "fresh", "a", null))
        assertEquals(1, g.trackedCount(), "all 64 expired entries dropped, only the fresh one kept")
        // live (unexpired) entries are never pruned
        repeat(64) { g.decide(true, wd, "live$it", "a", null) }
        assertEquals(65, g.trackedCount())
    }

    @Test
    fun disabled_is_reported_and_does_not_arm_the_window() {
        val c = Clock(); val g = gate(c)
        assertIs<TurnPushDecision.Disabled>(g.decide(false, wd, "sid1", "a", null))
        // switching back on: the first push must not be swallowed by the silenced one
        assertIs<TurnPushDecision.Queued>(g.decide(true, wd, "sid1", "b", null))
    }

    @Test
    fun queued_push_is_urgent() {
        val d = assertIs<TurnPushDecision.Queued>(gate(Clock()).decide(true, wd, "sid1", "a", null))
        assertTrue(d.push.urgent)
        assertNull(d.push.kind)
    }

    // ---- log line: redacted ----

    @Test
    fun log_lines_carry_kind_and_short_sid_but_no_content_or_path() {
        val c = Clock(); val g = gate(c)
        val secret = "SECRET-FINAL-TEXT"
        val q = g.decide(true, wd, "0123456789abcdef", secret, null)
        val line = TurnPushCoalescer.logLine(q, "0123456789abcdef")
        assertEquals("turn-push kind=complete sid=01234567 → queued to relay", line)
        c.now += 1
        assertEquals(
            "turn-push kind=error sid=01234567 → queued to relay",
            TurnPushCoalescer.logLine(g.decide(true, wd, "0123456789abcdef", null, "boom $secret"), "0123456789abcdef"),
        )
        assertEquals(
            "turn-push kind=complete sid=01234567 → coalesced",
            TurnPushCoalescer.logLine(g.decide(true, wd, "0123456789abcdef", secret, null), "0123456789abcdef"),
        )
        assertEquals(
            "turn-push kind=limit sid=- → not pushed (pushEnabled=false)",
            TurnPushCoalescer.logLine(g.decide(false, wd, null, null, limit), null),
        )
        for (l in listOf(line)) {
            assertFalse(secret in l); assertFalse(wd.toString() in l); assertFalse("cc-pocket" in l)
        }
    }

    // ---- structure: the RelayClient hook no longer consults presence ----

    @Test
    fun relay_client_turn_hook_does_not_read_presence() {
        val src = listOf(
            File("src/main/kotlin/dev/ccpocket/daemon/relay/RelayClient.kt"),
            File("daemon/src/main/kotlin/dev/ccpocket/daemon/relay/RelayClient.kt"),
        ).first { it.isFile }.readText()
        val start = src.indexOf("core.registry.pushHook = PushHook")
        val end = src.indexOf("core.registry.askPushHook", start)
        assertTrue(start >= 0 && end > start, "turn push hook block not found")
        val block = src.substring(start, end)
        assertFalse("peerOnline" in block, block)
        assertFalse("lanConnected" in block, block)
        assertTrue("pushEnabled" in block, block)
        assertTrue("turnPushes.decide" in block, block)
    }
}
