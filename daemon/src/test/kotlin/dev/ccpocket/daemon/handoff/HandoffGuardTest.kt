package dev.ccpocket.daemon.handoff

import dev.ccpocket.daemon.handoff.HandoffGuard.DenyReason
import dev.ccpocket.daemon.handoff.HandoffGuard.Verdict
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.HandoffAccess
import dev.ccpocket.protocol.HandoffBrief
import dev.ccpocket.protocol.HandoffKind
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The pure drive gate (SESSION-HANDOFF.md §5.3 items 2/3): who may SendPrompt / CancelTurn / answer /
 * verdict per handoff state — WAITING locks everyone out, IN_PROGRESS admits only the lease holder,
 * RETURNED denies only the recipient, terminal states free the session, "returned then send" refuses.
 */
class HandoffGuardTest {

    private class Harness {
        var now = 1_000_000_000_000L
        val path: File = createTempDirectory("ccp-handoff-guard").resolve("handoffs.json").toFile()
        val registry = HandoffRegistry(HandoffStore.load(path), clock = { now })
        val guard = HandoffGuard(registry)

        fun create(sessionId: String = "sess-1") = (
            registry.create(
                sourceSessionId = sessionId, workdir = "/w", agent = AgentKind.CLAUDE,
                initiatorDeviceId = "devOwner", kind = HandoffKind.REVIEW,
                access = HandoffAccess.REVIEW_READ_ONLY, brief = HandoffBrief(request = "review"),
                initiatorLabel = "Panda", expiresInSec = 3600,
            ) as HandoffRegistry.HandoffOutcome.Ok
            ).handoff

        fun verdict(deviceId: String, sessionId: String = "sess-1") = guard.canDrive(sessionId, deviceId, now)
    }

    private fun assertDenied(v: Verdict, reason: DenyReason) {
        assertIs<Verdict.Deny>(v)
        assertEquals(reason, v.reason)
        assertTrue(v.message.isNotBlank(), "a denial must explain itself")
    }

    @Test
    fun no_handoff_means_the_gate_is_inert() {
        val h = Harness()
        assertIs<Verdict.Allow>(h.verdict("devOwner", sessionId = "never-handed-off"))
        assertIs<Verdict.Allow>(h.verdict("devAnything", sessionId = "never-handed-off"))
    }

    @Test
    fun waiting_locks_everyone_out_including_the_initiator() {
        val h = Harness()
        h.create()
        assertDenied(h.verdict("devOwner"), DenyReason.WAITING_LOCKED)
        assertDenied(h.verdict("devFrank"), DenyReason.WAITING_LOCKED)
        assertDenied(h.verdict("devRandom"), DenyReason.WAITING_LOCKED)
    }

    @Test
    fun in_progress_admits_only_the_lease_controller() {
        val h = Harness()
        val hf = h.create()
        h.registry.accept(hf.id, "devFrank")
        assertIs<Verdict.Allow>(h.verdict("devFrank"))
        assertDenied(h.verdict("devOwner"), DenyReason.NOT_CONTROLLER)
        assertDenied(h.verdict("devRandom"), DenyReason.NOT_CONTROLLER)
    }

    @Test
    fun an_outrun_lease_denies_even_the_controller_until_the_sweep_recalls() {
        val h = Harness()
        val hf = h.create()
        h.registry.accept(hf.id, "devFrank", leaseTtlMs = HandoffRegistry.MIN_LEASE_TTL_MS)
        // the gate is asked with a NOW past the lease deadline while the registry hasn't swept yet —
        // it must fail closed rather than honor a dead lease
        val late = h.registry.activeFor("sess-1")!!.second!!.leaseExpiresAt
        assertDenied(h.guard.canDrive("sess-1", "devFrank", late), DenyReason.LEASE_INVALID)
        // once the registry clock catches up, the sweep settles RECALLED and the session frees
        h.now = late
        assertIs<Verdict.Allow>(h.verdict("devOwner"))
        assertIs<Verdict.Allow>(h.verdict("devFrank"), "post-recall the gate is inert; credential caps take over")
    }

    @Test
    fun returned_denies_the_recipient_and_frees_the_initiator_side() {
        val h = Harness()
        val hf = h.create()
        h.registry.accept(hf.id, "devFrank")
        h.registry.returnHandoff(hf.id, "devFrank")
        // "归还后发送" (§12.1): the recipient who just returned may NOT keep driving
        assertDenied(h.verdict("devFrank"), DenyReason.RETURNED_TO_INITIATOR)
        assertIs<Verdict.Allow>(h.verdict("devOwner"))
        assertIs<Verdict.Allow>(h.verdict("devOwnerDesktop"), "any owner-side device drives again")
    }

    @Test
    fun every_terminal_state_frees_the_session() {
        val h = Harness()
        // cancelled
        val a = h.create(sessionId = "sA")
        h.registry.cancel(a.id, "devOwner")
        assertIs<Verdict.Allow>(h.verdict("devOwner", sessionId = "sA"))
        // declined
        val b = h.create(sessionId = "sB")
        h.registry.decline(b.id, "devFrank")
        assertIs<Verdict.Allow>(h.verdict("devOwner", sessionId = "sB"))
        // recalled
        val c = h.create(sessionId = "sC")
        h.registry.accept(c.id, "devFrank")
        h.registry.recall(c.id, "devOwner")
        assertIs<Verdict.Allow>(h.verdict("devOwner", sessionId = "sC"))
        // completed
        val d = h.create(sessionId = "sD")
        h.registry.accept(d.id, "devFrank")
        h.registry.returnHandoff(d.id, "devFrank")
        h.registry.complete(d.id, "devOwner")
        assertIs<Verdict.Allow>(h.verdict("devOwner", sessionId = "sD"))
        assertIs<Verdict.Allow>(h.verdict("devFrank", sessionId = "sD"))
    }

    @Test
    fun waiting_expiry_unlocks_the_session_through_the_sweep() {
        val h = Harness()
        h.create()
        assertDenied(h.verdict("devOwner"), DenyReason.WAITING_LOCKED)
        h.now += 3600_000L
        assertIs<Verdict.Allow>(h.verdict("devOwner"), "EXPIRED settles on read — the owner types again")
    }
}
