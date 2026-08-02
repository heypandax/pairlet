package dev.ccpocket.daemon.handoff

import dev.ccpocket.daemon.handoff.HandoffRegistry.HandoffOutcome
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.HandoffAccess
import dev.ccpocket.protocol.HandoffBrief
import dev.ccpocket.protocol.HandoffKind
import dev.ccpocket.protocol.HandoffResult
import dev.ccpocket.protocol.HandoffStatus
import dev.ccpocket.protocol.SessionControllerLease
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Handoff state machine (SESSION-HANDOFF.md §12.1): every legal transition, the illegal ones,
 * duplicate accept/return, the CAS races (cancel-vs-accept, recall-vs-return, expire-vs-accept),
 * clock-driven expiry, and restart recovery — all on an injected clock, no real sleeps.
 */
class HandoffRegistryTest {

    private class Harness {
        var now = 1_000_000_000_000L
        val path: File = createTempDirectory("ccp-handoff").resolve("handoffs.json").toFile()
        var registry = HandoffRegistry(HandoffStore.load(path), clock = { now })

        /** Simulate a daemon restart: a fresh store re-read from disk + a fresh registry. */
        fun restart() {
            registry = HandoffRegistry(HandoffStore.load(path), clock = { now })
        }

        fun create(sessionId: String = "sess-1", initiator: String = "devOwner") = registry.create(
            sourceSessionId = sessionId, workdir = "/w", agent = AgentKind.CLAUDE,
            initiatorDeviceId = initiator, kind = HandoffKind.REVIEW,
            access = HandoffAccess.REVIEW_READ_ONLY, brief = HandoffBrief(request = "review this"),
            expiresInSec = 3600,
        )

        fun ok(outcome: HandoffOutcome) = (outcome as HandoffOutcome.Ok).handoff
    }

    // ---- every legal transition ------------------------------------------

    @Test
    fun the_full_happy_path_waiting_accept_return_complete() {
        val h = Harness()
        val created = h.ok(h.create())
        assertEquals(HandoffStatus.WAITING, created.status)
        assertNull(h.registry.activeFor("sess-1")!!.second, "WAITING must hold NO lease (§5.3 inv 2)")

        val accepted = h.ok(h.registry.accept(created.id, "devFrank", deviceLabel = "Frank"))
        assertEquals(HandoffStatus.IN_PROGRESS, accepted.status)
        assertEquals("devFrank", accepted.recipientDeviceId)
        val lease = h.registry.activeFor("sess-1")!!.second
        assertNotNull(lease, "IN_PROGRESS must hold the lease (§5.3 inv 1)")
        assertEquals("devFrank", lease.controllerDeviceId)
        assertEquals(created.id, lease.handoffId)

        val returned = h.ok(h.registry.returnHandoff(created.id, "devFrank", HandoffResult(summary = "LGTM")))
        assertEquals(HandoffStatus.RETURNED, returned.status)
        assertEquals("devFrank", returned.result!!.returnedByDeviceId, "the daemon stamps the returner")
        assertEquals(h.now, returned.result!!.returnedAt)
        assertNull(h.registry.activeFor("sess-1")!!.second, "leaving IN_PROGRESS deletes the lease")

        val completed = h.ok(h.registry.complete(created.id, "devOwner"))
        assertEquals(HandoffStatus.COMPLETED, completed.status)
        assertNull(h.registry.activeFor("sess-1"), "a terminal handoff frees the session")
    }

    @Test
    fun decline_cancel_recall_each_settle_their_state() {
        val h = Harness()
        val a = h.ok(h.create(sessionId = "sA"))
        assertEquals(HandoffStatus.DECLINED, h.ok(h.registry.decline(a.id, "devFrank", "busy")).status)

        val b = h.ok(h.create(sessionId = "sB"))
        assertEquals(HandoffStatus.CANCELLED, h.ok(h.registry.cancel(b.id, "devOwner")).status)

        val c = h.ok(h.create(sessionId = "sC"))
        h.registry.accept(c.id, "devFrank")
        assertEquals(HandoffStatus.RECALLED, h.ok(h.registry.recall(c.id, "devOwner")).status)
        assertNull(h.registry.activeFor("sC"), "RECALLED is terminal — lease gone, session free")
    }

    // ---- illegal transitions & actor checks ------------------------------

    @Test
    fun all_illegal_transitions_are_refused() {
        val h = Harness()
        val hf = h.ok(h.create())
        // WAITING: return/recall/complete are illegal
        assertIs<HandoffOutcome.Refused>(h.registry.returnHandoff(hf.id, "devFrank"))
        assertIs<HandoffOutcome.Refused>(h.registry.recall(hf.id, "devOwner"))
        assertIs<HandoffOutcome.Refused>(h.registry.complete(hf.id, "devOwner"))

        h.registry.accept(hf.id, "devFrank")
        // IN_PROGRESS: accept/decline/cancel/complete are illegal
        assertIs<HandoffOutcome.Refused>(h.registry.accept(hf.id, "devOther"))
        assertIs<HandoffOutcome.Refused>(h.registry.decline(hf.id, "devFrank"))
        assertIs<HandoffOutcome.Refused>(h.registry.cancel(hf.id, "devOwner"))
        assertIs<HandoffOutcome.Refused>(h.registry.complete(hf.id, "devOwner"))

        h.registry.returnHandoff(hf.id, "devFrank")
        // RETURNED: accept/decline/cancel/recall/return are illegal
        assertIs<HandoffOutcome.Refused>(h.registry.accept(hf.id, "devOther"))
        assertIs<HandoffOutcome.Refused>(h.registry.decline(hf.id, "devFrank"))
        assertIs<HandoffOutcome.Refused>(h.registry.cancel(hf.id, "devOwner"))
        assertIs<HandoffOutcome.Refused>(h.registry.recall(hf.id, "devOwner"))
        assertIs<HandoffOutcome.Refused>(h.registry.returnHandoff(hf.id, "devFrank"))

        h.registry.complete(hf.id, "devOwner")
        // COMPLETED (terminal): everything is illegal
        assertIs<HandoffOutcome.Refused>(h.registry.accept(hf.id, "devFrank"))
        assertIs<HandoffOutcome.Refused>(h.registry.complete(hf.id, "devOwner"))
        // unknown id
        assertIs<HandoffOutcome.Refused>(h.registry.accept("nope", "devFrank"))
    }

    @Test
    fun actor_identity_is_enforced_per_transition() {
        val h = Harness()
        val hf = h.ok(h.create())
        assertIs<HandoffOutcome.Refused>(h.registry.accept(hf.id, "devOwner"), "initiator cannot self-accept")
        assertIs<HandoffOutcome.Refused>(h.registry.cancel(hf.id, "devFrank"), "only the initiator cancels")
        assertIs<HandoffOutcome.Refused>(h.registry.decline(hf.id, "devOwner"), "the initiator cancels, not declines")

        h.registry.accept(hf.id, "devFrank")
        assertIs<HandoffOutcome.Refused>(h.registry.recall(hf.id, "devFrank"), "only the initiator recalls")
        assertIs<HandoffOutcome.Refused>(
            h.registry.returnHandoff(hf.id, "devOther"),
            "only the lease controller returns — a non-controller device must be refused",
        )
        h.registry.returnHandoff(hf.id, "devFrank")
        assertIs<HandoffOutcome.Refused>(h.registry.complete(hf.id, "devFrank"), "only the initiator completes")
    }

    @Test
    fun duplicate_accept_and_duplicate_return_are_refused() {
        val h = Harness()
        val hf = h.ok(h.create())
        assertIs<HandoffOutcome.Ok>(h.registry.accept(hf.id, "devFrank"))
        assertIs<HandoffOutcome.Refused>(h.registry.accept(hf.id, "devFrank"), "second accept — same device")
        assertIs<HandoffOutcome.Refused>(h.registry.accept(hf.id, "devMallory"), "second accept — competing device")
        assertIs<HandoffOutcome.Ok>(h.registry.returnHandoff(hf.id, "devFrank"))
        assertIs<HandoffOutcome.Refused>(h.registry.returnHandoff(hf.id, "devFrank"), "double return")
    }

    // ---- creation constraints --------------------------------------------

    @Test
    fun one_non_terminal_handoff_per_session() {
        val h = Harness()
        val first = h.ok(h.create())
        val second = h.create()
        assertIs<HandoffOutcome.Refused>(second)
        assertEquals("handoff_exists", second.code)
        // a DIFFERENT session is fine
        assertIs<HandoffOutcome.Ok>(h.create(sessionId = "sess-2"))
        // once the first settles terminal, the session is free again
        h.registry.cancel(first.id, "devOwner")
        assertIs<HandoffOutcome.Ok>(h.create())
    }

    @Test
    fun unknown_kind_or_access_fails_closed_at_create() {
        val h = Harness()
        val badKind = h.registry.create(
            sourceSessionId = "s", workdir = "/w", agent = AgentKind.CLAUDE, initiatorDeviceId = "devOwner",
            kind = HandoffKind.UNKNOWN, access = HandoffAccess.REVIEW_READ_ONLY, brief = HandoffBrief(request = "x"),
        )
        assertIs<HandoffOutcome.Refused>(badKind)
        assertEquals("unknown_kind", badKind.code)
        val badAccess = h.registry.create(
            sourceSessionId = "s", workdir = "/w", agent = AgentKind.CLAUDE, initiatorDeviceId = "devOwner",
            kind = HandoffKind.REVIEW, access = HandoffAccess.UNKNOWN, brief = HandoffBrief(request = "x"),
        )
        assertIs<HandoffOutcome.Refused>(badAccess)
        assertEquals("unknown_access", badAccess.code)
    }

    /**
     * §6 of the implementation review: v1 implements exactly REVIEW + REVIEW_READ_ONLY. CONTINUE /
     * CONTINUE_SCOPED exist on the wire but nothing enforces their edit scope yet, so a raw client must
     * not be able to mint one — the daemon answers a machine-readable `handoff_not_supported` (distinct
     * from the UNKNOWN "update the daemon" codes) on BOTH ends: create, and accept of a row some other
     * build wrote.
     */
    @Test
    fun v1_refuses_the_defined_but_unimplemented_authorization_combinations() {
        val h = Harness()
        for ((kind, access) in listOf(
            HandoffKind.CONTINUE to HandoffAccess.CONTINUE_SCOPED,
            HandoffKind.CONTINUE to HandoffAccess.REVIEW_READ_ONLY,
            HandoffKind.REVIEW to HandoffAccess.CONTINUE_SCOPED,
        )) {
            val out = h.registry.create(
                sourceSessionId = "s-$kind-$access", workdir = "/w", agent = AgentKind.CLAUDE,
                initiatorDeviceId = "devOwner", kind = kind, access = access,
                brief = HandoffBrief(request = "carry this forward"), allowedRoots = listOf("/w/src"),
            )
            assertIs<HandoffOutcome.Refused>(out, "$kind/$access must be refused until it is implemented")
            assertEquals("handoff_not_supported", out.code)
        }
        // the one implemented combination still passes
        assertIs<HandoffOutcome.Ok>(h.create())
    }

    @Test
    fun an_unimplemented_combination_persisted_by_another_build_is_never_accepted() {
        val h = Harness()
        val ok = h.ok(h.create())
        // simulate a row a NEWER build (or a hand-edited handoffs.json) left in the store
        HandoffStore.load(h.path).putHandoff(ok.copy(kind = HandoffKind.CONTINUE, access = HandoffAccess.CONTINUE_SCOPED))
        h.restart()
        val out = h.registry.accept(ok.id, "devFrank")
        assertIs<HandoffOutcome.Refused>(out)
        assertEquals("handoff_not_supported", out.code)
        assertNull(h.registry.activeFor("sess-1")!!.second, "a refused accept mints no lease")
    }

    // ---- §5.4 graceful recall (state machine half) -------------------------

    @Test
    fun a_pending_recall_holds_the_lease_locks_everyone_out_and_then_settles() {
        val h = Harness()
        val created = h.ok(h.create())
        h.registry.accept(created.id, "devFrank")
        val guard = HandoffGuard(h.registry)

        val pending = h.ok(h.registry.markRecallPending(created.id, "devOwner"))
        assertEquals(HandoffStatus.IN_PROGRESS, pending.status, "control only moves at the stable point")
        assertTrue(pending.recallPending, "both sides must see the hand-back in flight")
        val lease = h.registry.activeFor("sess-1")!!.second
        assertNotNull(lease, "the lease outlives the request — nothing else may grab the session meanwhile")
        assertTrue(lease.recallRequested)
        // NOBODY drives in that window — not the controller, not the initiator
        for (device in listOf("devFrank", "devOwner")) {
            val deny = guard.canDrive("sess-1", device, h.now) as HandoffGuard.Verdict.Deny
            assertEquals(HandoffGuard.DenyReason.RECALL_PENDING, deny.reason, "$device must be locked out")
        }

        val recalled = h.ok(h.registry.settleRecall(created.id))
        assertEquals(HandoffStatus.RECALLED, recalled.status)
        assertFalse(recalled.recallPending, "the in-flight marker is cleared by the settle")
        assertFalse(recalled.recallIncomplete, "a clean stop is not marked incomplete")
        assertNull(h.registry.activeFor("sess-1"), "terminal — the session is free and the lease is gone")
        assertIs<HandoffGuard.Verdict.Allow>(guard.canDrive("sess-1", "devOwner", h.now))
    }

    @Test
    fun an_unclean_settle_is_recorded_honestly_and_a_return_that_wins_the_race_stands() {
        val h = Harness()
        val first = h.ok(h.create())
        h.registry.accept(first.id, "devFrank")
        h.registry.markRecallPending(first.id, "devOwner")
        val incomplete = h.ok(h.registry.settleRecall(first.id, incomplete = true))
        assertTrue(incomplete.recallIncomplete, "a timeout / unkillable background work must be visible in the state")

        // recall-vs-return: the recipient returned before the interrupted turn settled — that transition
        // stands (control IS back with the owner), the recall's settle refuses instead of overwriting it
        val second = h.ok(h.create(sessionId = "sess-2"))
        h.registry.accept(second.id, "devFrank")
        h.registry.markRecallPending(second.id, "devOwner")
        val returned = h.ok(h.registry.returnHandoff(second.id, "devFrank", HandoffResult(summary = "stopping here")))
        assertEquals(HandoffStatus.RETURNED, returned.status)
        assertFalse(returned.recallPending, "leaving IN_PROGRESS clears the in-flight marker")
        val late = h.registry.settleRecall(second.id)
        assertIs<HandoffOutcome.Refused>(late)
        assertEquals("not_in_progress", late.code)
        assertEquals(HandoffStatus.RETURNED, h.registry.byId(second.id)?.status)
    }

    @Test
    fun a_recall_in_flight_at_shutdown_settles_at_the_next_boot() {
        val h = Harness()
        val created = h.ok(h.create())
        h.registry.accept(created.id, "devFrank")
        h.registry.markRecallPending(created.id, "devOwner") // …and the daemon dies right here
        h.restart()
        // the interrupted turn died with the daemon, so control comes back — locked-for-everyone is not
        // an acceptable resting state — and the row admits nobody saw the turn stop
        val recovered = assertNotNull(h.registry.byId(created.id))
        assertEquals(HandoffStatus.RECALLED, recovered.status)
        assertTrue(recovered.recallIncomplete, "the stop was never observed — say so")
        assertFalse(recovered.recallPending)
        assertNull(h.registry.activeFor("sess-1"), "the lease is gone with it")
    }

    @Test
    fun only_the_initiator_may_arm_a_recall() {
        val h = Harness()
        val created = h.ok(h.create())
        h.registry.accept(created.id, "devFrank")
        // the recipient cannot recall itself out of its own obligations, and neither can a bystander
        for (device in listOf("devFrank", "devSomeoneElse")) {
            val stolen = h.registry.markRecallPending(created.id, device)
            assertIs<HandoffOutcome.Refused>(stolen, "$device must not arm a recall")
            assertEquals("not_allowed", stolen.code)
        }
        assertFalse(h.registry.byId(created.id)!!.recallPending, "a refused arm leaves the row untouched")
        assertFalse(h.registry.activeFor("sess-1")!!.second!!.recallRequested)
    }

    @Test
    fun review_handoffs_never_carry_allowed_roots() {
        val h = Harness()
        val created = h.ok(
            h.registry.create(
                sourceSessionId = "s", workdir = "/w", agent = AgentKind.CLAUDE, initiatorDeviceId = "devOwner",
                kind = HandoffKind.REVIEW, access = HandoffAccess.REVIEW_READ_ONLY,
                brief = HandoffBrief(request = "x"), allowedRoots = listOf("/w/src"),
            ),
        )
        assertTrue(created.allowedRoots.isEmpty(), "REVIEW is read-only — roots would imply an edit scope")
    }

    // ---- concurrency: CAS races ------------------------------------------

    private fun race(threads: Int, body: (Int) -> HandoffOutcome): List<HandoffOutcome> {
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val futures = (0 until threads).map { i ->
            pool.submit<HandoffOutcome> {
                ready.countDown(); go.await()
                body(i)
            }
        }
        ready.await(); go.countDown()
        return futures.map { it.get() }.also { pool.shutdown() }
    }

    @Test
    fun of_many_racing_accepts_exactly_one_wins() {
        val h = Harness()
        val hf = h.ok(h.create())
        val outcomes = race(8) { i -> h.registry.accept(hf.id, "dev-$i") }
        assertEquals(1, outcomes.count { it is HandoffOutcome.Ok }, "CAS: exactly one accept wins (§5.3 inv 5)")
        assertEquals(7, outcomes.count { it is HandoffOutcome.Refused })
        val (after, lease) = h.registry.activeFor("sess-1")!!
        assertEquals(HandoffStatus.IN_PROGRESS, after.status)
        assertEquals(after.recipientDeviceId, lease!!.controllerDeviceId, "the winner IS the controller")
    }

    @Test
    fun cancel_vs_accept_exactly_one_wins() {
        repeat(16) { round ->
            val h = Harness()
            val hf = h.ok(h.create())
            val outcomes = race(2) { i ->
                if (i == 0) h.registry.cancel(hf.id, "devOwner") else h.registry.accept(hf.id, "devFrank")
            }
            assertEquals(1, outcomes.count { it is HandoffOutcome.Ok }, "round $round: one side must win")
            val status = h.registry.byId(hf.id)!!.status
            assertTrue(status == HandoffStatus.CANCELLED || status == HandoffStatus.IN_PROGRESS, "round $round: $status")
            if (status == HandoffStatus.CANCELLED) {
                assertNull(h.registry.activeFor("sess-1"), "cancelled → no lease, session free")
            }
        }
    }

    @Test
    fun recall_vs_return_exactly_one_wins() {
        repeat(16) { round ->
            val h = Harness()
            val hf = h.ok(h.create())
            h.registry.accept(hf.id, "devFrank")
            val outcomes = race(2) { i ->
                if (i == 0) h.registry.recall(hf.id, "devOwner")
                else h.registry.returnHandoff(hf.id, "devFrank", HandoffResult(summary = "done"))
            }
            assertEquals(1, outcomes.count { it is HandoffOutcome.Ok }, "round $round: one side must win")
            val status = h.registry.byId(hf.id)!!.status
            assertTrue(status == HandoffStatus.RECALLED || status == HandoffStatus.RETURNED, "round $round: $status")
            assertNull(
                h.registry.activeFor("sess-1")?.second,
                "round $round: either way the lease is gone the instant IN_PROGRESS ends",
            )
        }
    }

    @Test
    fun expire_vs_accept_resolves_to_expiry_never_a_late_grab() {
        val h = Harness()
        val hf = h.ok(h.create()) // expires in 3600s
        h.now += 3600_000L + 1
        val late = h.registry.accept(hf.id, "devFrank")
        assertIs<HandoffOutcome.Refused>(late, "an accept past the deadline must lose to expiry")
        assertEquals(HandoffStatus.EXPIRED, h.registry.byId(hf.id)!!.status)
        assertNull(h.registry.activeFor("sess-1"), "EXPIRED is terminal — session free, no lease")
    }

    // ---- clock-driven expiry ---------------------------------------------

    @Test
    fun waiting_expires_by_sweep_and_the_transition_is_reported() {
        val h = Harness()
        val hf = h.ok(h.create())
        assertTrue(h.registry.sweep().isEmpty(), "nothing due yet")
        h.now += 3600_000L // exactly at the deadline (expiresAt <= now fires)
        val changed = h.registry.sweep()
        assertEquals(hf.id, changed.single().id)
        assertEquals(HandoffStatus.EXPIRED, changed.single().status)
        assertTrue(h.registry.sweep().isEmpty(), "a settled handoff never re-fires")
    }

    @Test
    fun an_expired_lease_recalls_the_handoff() {
        val h = Harness()
        val hf = h.ok(h.create())
        h.registry.accept(hf.id, "devFrank", leaseTtlMs = HandoffRegistry.MIN_LEASE_TTL_MS)
        assertTrue(h.registry.sweep().isEmpty())
        h.now += HandoffRegistry.MIN_LEASE_TTL_MS
        val changed = h.registry.sweep()
        assertEquals(HandoffStatus.RECALLED, changed.single().status)
        assertNull(h.registry.activeFor("sess-1"), "lease timeout → RECALLED, control back to the owner")
    }

    @Test
    fun requestRecall_marks_the_lease_without_transitioning() {
        val h = Harness()
        val hf = h.ok(h.create())
        h.registry.accept(hf.id, "devFrank")
        assertTrue(h.registry.requestRecall("sess-1"))
        val (still, lease) = h.registry.activeFor("sess-1")!!
        assertEquals(HandoffStatus.IN_PROGRESS, still.status, "recall-requested is a marker, not a transition")
        assertTrue(lease!!.recallRequested)
        assertFalse(h.registry.requestRecall("no-such-session"))
    }

    // ---- restart recovery (§5.4) -----------------------------------------

    @Test
    fun restart_restores_a_waiting_handoff_and_an_in_progress_lease() {
        val h = Harness()
        val w = h.ok(h.create(sessionId = "sW"))
        val p = h.ok(h.create(sessionId = "sP"))
        h.registry.accept(p.id, "devFrank")

        h.restart()

        assertEquals(HandoffStatus.WAITING, h.registry.byId(w.id)!!.status)
        val (restored, lease) = h.registry.activeFor("sP")!!
        assertEquals(HandoffStatus.IN_PROGRESS, restored.status)
        assertEquals("devFrank", lease!!.controllerDeviceId, "the lease survives the restart with its holder")
        // and the machine keeps working after recovery: the recipient can still return
        assertIs<HandoffOutcome.Ok>(h.registry.returnHandoff(p.id, "devFrank"))
    }

    @Test
    fun restart_settles_what_the_clock_outran_while_the_daemon_was_down() {
        val h = Harness()
        val w = h.ok(h.create(sessionId = "sW"))
        val p = h.ok(h.create(sessionId = "sP"))
        h.registry.accept(p.id, "devFrank", leaseTtlMs = HandoffRegistry.MIN_LEASE_TTL_MS)

        h.now += 24 * 3600_000L // the daemon was down for a day
        h.restart()

        assertEquals(HandoffStatus.EXPIRED, h.registry.byId(w.id)!!.status, "WAITING outlived its deadline → EXPIRED")
        assertEquals(HandoffStatus.RECALLED, h.registry.byId(p.id)!!.status, "the lease died → RECALLED")
        assertNull(h.registry.activeFor("sW"))
        assertNull(h.registry.activeFor("sP"))
    }

    @Test
    fun restart_fails_closed_on_an_in_progress_handoff_whose_lease_is_missing() {
        val h = Harness()
        val p = h.ok(h.create(sessionId = "sP"))
        h.registry.accept(p.id, "devFrank")
        // corrupt the persisted state: drop the lease behind the registry's back
        HandoffStore.load(h.path).removeLease("sP")

        h.restart()

        assertEquals(
            HandoffStatus.RECALLED, h.registry.byId(p.id)!!.status,
            "an unprovable lease must NOT be re-minted — recall to the owner (fail closed)",
        )
    }

    @Test
    fun restart_drops_an_orphan_lease() {
        val h = Harness()
        val p = h.ok(h.create(sessionId = "sP"))
        h.registry.accept(p.id, "devFrank")
        h.ok(h.registry.recall(p.id, "devOwner"))
        // corrupt the persisted state: resurrect a lease for the already-terminal handoff
        HandoffStore.load(h.path).putLease(
            SessionControllerLease(
                sessionId = "sP", handoffId = p.id, controllerDeviceId = "devFrank",
                acquiredAt = h.now, leaseExpiresAt = h.now + 3600_000,
            ),
        )

        h.restart()

        assertNull(h.registry.activeFor("sP"), "terminal handoff + zombie lease → the lease is pruned at boot")
        assertTrue(HandoffStore.load(h.path).leases().isEmpty(), "the orphan is gone from disk too")
    }

    @Test
    fun a_corrupt_store_file_loads_empty_never_crashes() {
        val h = Harness()
        h.path.parentFile.mkdirs()
        h.path.writeText("{ this is not json")
        h.restart()
        assertTrue(h.registry.list().isEmpty())
        assertIs<HandoffOutcome.Ok>(h.create(), "the registry stays usable after a corrupt load")
    }
}
