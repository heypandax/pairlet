package dev.ccpocket.daemon.review

import dev.ccpocket.protocol.ArtifactKind
import dev.ccpocket.protocol.ArtifactRef
import dev.ccpocket.protocol.ReviewBrief
import dev.ccpocket.protocol.ReviewRequest
import dev.ccpocket.protocol.ReviewResult
import dev.ccpocket.protocol.ReviewStatus
import dev.ccpocket.protocol.ReviewVerdict
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ReviewRequest state machine (REVIEW-REQUEST.md §8): legal and illegal transitions, the recipient
 * binding, revision monotonicity, idempotency, daemon-stamped identity, expiry, and the two things a
 * replay must never do — duplicate a result or regress a status.
 */
class ReviewRegistryTest {

    private val tmp = Files.createTempDirectory("ccp-review-reg").toFile()
    private var now = 1_000L
    private var seq = 0

    private fun registry(name: String = "reviews.json") = ReviewRegistry(
        ReviewStore.load(tmp.resolve(name)),
        clock = { now },
        newId = { "rr_${seq++}" },
    )

    private val artifact = ArtifactRef(
        kind = ArtifactKind.MERGE_REQUEST, url = "https://git.example.com/t/r/-/merge_requests/42",
    )

    private fun ReviewRegistry.newRequest(recipient: String = "devB"): ReviewRequest {
        val out = create(
            senderDeviceId = "devA", senderLabel = "Panda",
            recipientDeviceId = recipient, recipientLabel = "Frank",
            title = "the ACK path", brief = ReviewBrief(request = "check the retry race"),
            artifacts = listOf(artifact),
        )
        return assertOk(out).request
    }

    private fun assertOk(out: ReviewRegistry.Outcome): ReviewRegistry.Outcome.Ok {
        assertTrue(out is ReviewRegistry.Outcome.Ok, "expected Ok, got $out")
        return out
    }

    private fun assertRefused(out: ReviewRegistry.Outcome, code: String): ReviewRegistry.Outcome.Refused {
        assertTrue(out is ReviewRegistry.Outcome.Refused, "expected a refusal, got $out")
        assertEquals(code, out.code)
        return out
    }

    // ---- creation ---------------------------------------------------------

    @Test
    fun create_starts_QUEUED_at_revision_one() {
        val r = registry().newRequest()
        assertEquals(ReviewStatus.QUEUED, r.status)
        assertEquals(1, r.revision)
        assertEquals("devA", r.senderDeviceId)
        assertEquals("devB", r.recipientDeviceId)
        assertTrue(r.id.startsWith("rr_"))
        assertNotNull(r.expiresAt, "a request must have a default cut-off, not hang around forever")
    }

    @Test
    fun create_refuses_an_empty_recipient_an_empty_artifact_list_and_a_blank_request() {
        val reg = registry()
        assertRefused(
            reg.create("devA", null, "", null, "t", ReviewBrief(request = "x"), listOf(artifact)),
            "review_no_recipient",
        )
        assertRefused(reg.create("devA", null, "devB", null, "t", ReviewBrief(request = "x"), emptyList()), "review_no_artifact")
        assertRefused(reg.create("devA", null, "devB", null, "t", ReviewBrief(request = " "), listOf(artifact)), "review_invalid")
    }

    /** Unknown artifact kinds and non-http URLs are refused at the door — never stored to be discovered
     *  as un-preparable by a reviewer later. */
    @Test
    fun create_refuses_artifacts_this_build_cannot_describe() {
        val reg = registry()
        assertRefused(
            reg.create("devA", null, "devB", null, "t", ReviewBrief(request = "x"), listOf(ArtifactRef(kind = ArtifactKind.UNKNOWN, url = "https://x"))),
            "review_invalid",
        )
        assertRefused(
            reg.create("devA", null, "devB", null, "t", ReviewBrief(request = "x"), listOf(ArtifactRef(ArtifactKind.DOCUMENT_URL, url = "file:///etc/passwd"))),
            "review_invalid",
        )
        assertRefused(
            reg.create("devA", null, "devB", null, "t", ReviewBrief(request = "x"), listOf(ArtifactRef(ArtifactKind.COMMIT_RANGE, repo = "r", base = "a"))),
            "review_invalid",
        )
    }

    @Test
    fun create_refuses_oversize_text_rather_than_truncating_it() {
        val reg = registry()
        val out = reg.create(
            "devA", null, "devB", null, "t",
            ReviewBrief(request = "x".repeat(ReviewLimits.MAX_TEXT + 1)), listOf(artifact),
        )
        assertRefused(out, "review_invalid")
        assertTrue(reg.list().isEmpty(), "a refused create must leave nothing behind")
    }

    @Test
    fun create_fails_closed_when_the_active_queue_is_full_instead_of_evicting() {
        val reg = registry("full.json")
        repeat(ReviewStore.MAX_ACTIVE) { reg.newRequest() }
        assertRefused(
            reg.create("devA", null, "devB", null, "t", ReviewBrief(request = "x"), listOf(artifact)),
            "review_store_full",
        )
        assertEquals(ReviewStore.MAX_ACTIVE, reg.list().size, "nothing pending may be dropped to make room")
    }

    @Test
    fun create_reports_persistence_failure_and_keeps_the_ledger_empty() {
        val parentIsAFile = tmp.resolve("blocked-parent").apply { writeText("x") }
        val reg = ReviewRegistry(ReviewStore.load(parentIsAFile.resolve("reviews.json")), clock = { now })
        val out = reg.create("devA", null, "devB", null, "t", ReviewBrief(request = "review it"), listOf(artifact))
        assertRefused(out, "review_persist_failed")
        assertTrue(reg.list().isEmpty())
    }

    @Test
    fun result_requires_a_known_verdict_and_non_blank_summary() {
        val reg = registry()
        val r = reg.newRequest()
        reg.markDelivered(r.id, "devB", "d")
        assertRefused(reg.respond(r.id, "devB", ReviewResult(summary = "text"), "u"), "review_invalid")
        assertRefused(reg.respond(r.id, "devB", ReviewResult(ReviewVerdict.COMMENT, "  "), "b"), "review_invalid")
    }

    // ---- the recipient plane ----------------------------------------------

    @Test
    fun the_happy_path_advances_the_revision_exactly_once_per_transition() {
        val reg = registry()
        val r = reg.newRequest()
        val delivered = assertOk(reg.markDelivered(r.id, "devB", "k1")).request
        assertEquals(ReviewStatus.DELIVERED, delivered.status)
        assertEquals(2, delivered.revision)
        val ack = assertOk(reg.acknowledge(r.id, "devB", "k2")).request
        assertEquals(3, ack.revision)
        val started = assertOk(reg.start(r.id, "devB", "k3")).request
        assertEquals(ReviewStatus.IN_PROGRESS, started.status)
        val responded = assertOk(reg.respond(r.id, "devB", ReviewResult(ReviewVerdict.APPROVE, "lgtm"), "k4")).request
        assertEquals(ReviewStatus.RESPONDED, responded.status)
        assertEquals(5, responded.revision)
        val closed = assertOk(reg.close(r.id)).request
        assertEquals(ReviewStatus.CLOSED, closed.status)
        assertEquals(6, closed.revision)
    }

    /** A light review answers straight from DELIVERED — acknowledge/start are optional (§8). */
    @Test
    fun respond_may_skip_acknowledge_and_start() {
        val reg = registry()
        val r = reg.newRequest()
        reg.markDelivered(r.id, "devB", "d")
        val out = assertOk(reg.respond(r.id, "devB", ReviewResult(verdict = ReviewVerdict.APPROVE, summary = "fine"), "r")).request
        assertEquals(ReviewStatus.RESPONDED, out.status)
    }

    @Test
    fun the_daemon_stamps_responder_identity_over_whatever_the_draft_claimed() {
        val reg = registry()
        val r = reg.newRequest()
        reg.markDelivered(r.id, "devB", "d")
        now = 9_999
        val forged = ReviewResult(ReviewVerdict.COMMENT, "s", respondedByDeviceId = "someone-else", respondedAt = 1)
        val out = assertOk(reg.respond(r.id, "devB", forged, "r")).request
        assertEquals("devB", out.result!!.respondedByDeviceId, "a client-declared identity is never trusted")
        assertEquals(9_999, out.result!!.respondedAt)
    }

    @Test
    fun only_the_addressed_device_may_act_and_a_foreign_id_gets_the_same_answer_as_a_missing_one() {
        val reg = registry()
        val r = reg.newRequest(recipient = "devB")
        assertRefused(reg.markDelivered(r.id, "devC", "k"), "review_not_allowed")
        val missing = assertRefused(reg.markDelivered("rr_nope", "devC", "k"), "review_not_allowed")
        val foreign = assertRefused(reg.acknowledge(r.id, "devC", "k"), "review_not_allowed")
        assertEquals(missing.message, foreign.message, "no probe oracle: the two must be indistinguishable")
        assertRefused(reg.markDelivered(r.id, "", "k"), "review_not_allowed")
    }

    // ---- idempotency + replay ---------------------------------------------

    @Test
    fun a_repeated_idempotency_key_returns_the_applied_row_without_moving_the_revision() {
        val reg = registry()
        val r = reg.newRequest()
        val first = assertOk(reg.markDelivered(r.id, "devB", "same-key"))
        assertTrue(first.changed)
        val second = assertOk(reg.acknowledge(r.id, "devB", "same-key"))
        assertFalse(second.changed, "a replayed key must not be applied a second time")
        assertEquals(first.request.revision, second.request.revision)
        assertEquals(ReviewStatus.DELIVERED, second.request.status, "…and must not move the status either")
    }

    @Test
    fun a_repeat_of_the_status_the_row_already_holds_is_a_no_op_even_without_a_key() {
        val reg = registry()
        val r = reg.newRequest()
        assertOk(reg.markDelivered(r.id, "devB", ""))
        val again = assertOk(reg.markDelivered(r.id, "devB", ""))
        assertFalse(again.changed)
        assertEquals(2, again.request.revision)
    }

    @Test
    fun a_duplicate_respond_never_overwrites_the_stored_result() {
        val reg = registry()
        val r = reg.newRequest()
        reg.markDelivered(r.id, "devB", "d")
        assertOk(reg.respond(r.id, "devB", ReviewResult(ReviewVerdict.COMMENT, "the real review"), "r1"))
        val dup = assertOk(reg.respond(r.id, "devB", ReviewResult(ReviewVerdict.COMMENT, "a stale retry"), "r2"))
        assertFalse(dup.changed)
        assertEquals("the real review", dup.request.result!!.summary)
    }

    // ---- illegal transitions ----------------------------------------------

    @Test
    fun the_transition_table_matches_the_design() {
        // QUEUED cannot skip delivery
        assertFalse(ReviewRegistry.canTransition(ReviewStatus.QUEUED, ReviewStatus.ACKNOWLEDGED))
        assertFalse(ReviewRegistry.canTransition(ReviewStatus.QUEUED, ReviewStatus.RESPONDED))
        assertTrue(ReviewRegistry.canTransition(ReviewStatus.QUEUED, ReviewStatus.DELIVERED))
        // work has started: the sender can no longer withdraw it
        assertFalse(ReviewRegistry.canTransition(ReviewStatus.IN_PROGRESS, ReviewStatus.CANCELLED))
        assertTrue(ReviewRegistry.canTransition(ReviewStatus.ACKNOWLEDGED, ReviewStatus.CANCELLED))
        // RESPONDED only closes
        assertTrue(ReviewRegistry.canTransition(ReviewStatus.RESPONDED, ReviewStatus.CLOSED))
        assertFalse(ReviewRegistry.canTransition(ReviewStatus.RESPONDED, ReviewStatus.DECLINED))
        assertFalse(ReviewRegistry.canTransition(ReviewStatus.RESPONDED, ReviewStatus.EXPIRED))
        // terminal and UNKNOWN are absorbing
        listOf(ReviewStatus.CLOSED, ReviewStatus.DECLINED, ReviewStatus.CANCELLED, ReviewStatus.EXPIRED, ReviewStatus.UNKNOWN)
            .forEach { from ->
                ReviewStatus.entries.forEach { to ->
                    assertFalse(ReviewRegistry.canTransition(from, to), "$from must not transition to $to")
                }
            }
        ReviewStatus.entries.forEach { from ->
            assertFalse(ReviewRegistry.canTransition(from, ReviewStatus.UNKNOWN), "nothing may become UNKNOWN")
        }
    }

    @Test
    fun an_illegal_transition_is_refused_and_leaves_the_row_alone() {
        val reg = registry()
        val r = reg.newRequest()
        assertRefused(reg.acknowledge(r.id, "devB", "k"), "review_bad_transition") // still QUEUED
        assertEquals(ReviewStatus.QUEUED, reg.byId(r.id)!!.status)
        assertEquals(1, reg.byId(r.id)!!.revision)
    }

    @Test
    fun a_terminal_row_never_moves_again() {
        val reg = registry()
        val r = reg.newRequest()
        reg.markDelivered(r.id, "devB", "d")
        assertOk(reg.decline(r.id, "devB", "not my module", "x"))
        assertEquals("not my module", reg.byId(r.id)!!.declineReason)
        assertRefused(reg.respond(r.id, "devB", ReviewResult(ReviewVerdict.COMMENT, "s"), "y"), "review_bad_transition")
        assertRefused(reg.close(r.id), "review_bad_transition")
        assertRefused(reg.cancel(r.id), "review_bad_transition")
    }

    // ---- owner plane ------------------------------------------------------

    @Test
    fun cancel_works_before_work_starts_and_not_after() {
        val reg = registry()
        val a = reg.newRequest()
        assertEquals(ReviewStatus.CANCELLED, assertOk(reg.cancel(a.id)).request.status)

        val b = reg.newRequest()
        reg.markDelivered(b.id, "devB", "d")
        reg.acknowledge(b.id, "devB", "a")
        assertEquals(ReviewStatus.CANCELLED, assertOk(reg.cancel(b.id)).request.status)

        val c = reg.newRequest()
        reg.markDelivered(c.id, "devB", "d")
        reg.start(c.id, "devB", "s")
        assertRefused(reg.cancel(c.id), "review_bad_transition")
    }

    @Test
    fun close_only_applies_to_a_responded_request() {
        val reg = registry()
        val r = reg.newRequest()
        assertRefused(reg.close(r.id), "review_bad_transition")
        assertRefused(reg.close("rr_nope"), "review_not_found")
    }

    // ---- expiry -----------------------------------------------------------

    @Test
    fun expiry_settles_pending_states_and_spares_a_returned_result() {
        val reg = registry()
        val queued = reg.newRequest()
        val responded = reg.newRequest()
        reg.markDelivered(responded.id, "devB", "d")
        reg.respond(responded.id, "devB", ReviewResult(ReviewVerdict.APPROVE, "done"), "r")

        now = (queued.expiresAt ?: 0) + 1
        val changed = reg.sweep(now)
        assertEquals(listOf(queued.id), changed.map { it.id })
        assertEquals(ReviewStatus.EXPIRED, reg.byId(queued.id)!!.status)
        assertEquals(ReviewStatus.RESPONDED, reg.byId(responded.id)!!.status, "a returned result must survive expiry")
    }

    @Test
    fun a_read_settles_expiry_first_so_an_outrun_request_can_never_be_acted_on() {
        val reg = registry()
        val r = reg.newRequest()
        now = (r.expiresAt ?: 0) + 1
        assertEquals(ReviewStatus.EXPIRED, reg.byId(r.id)!!.status)
        assertRefused(reg.markDelivered(r.id, "devB", "k"), "review_bad_transition")
    }

    @Test
    fun expiry_bumps_the_revision_so_the_recipient_mirror_notices() {
        val reg = registry()
        val r = reg.newRequest()
        now = (r.expiresAt ?: 0) + 1
        assertEquals(r.revision + 1, reg.byId(r.id)!!.revision)
    }

    // ---- reads ------------------------------------------------------------

    @Test
    fun list_filters_by_recipient_and_status_but_does_not_treat_per_row_revision_as_a_global_cursor() {
        val reg = registry()
        val mine = reg.newRequest(recipient = "devB")
        val theirs = reg.newRequest(recipient = "devC")
        reg.markDelivered(mine.id, "devB", "d")

        assertEquals(setOf(mine.id, theirs.id), reg.list().mapTo(HashSet()) { it.id })
        assertEquals(listOf(mine.id), reg.list(recipientDeviceId = "devB").map { it.id })
        assertEquals(listOf(theirs.id), reg.list(recipientDeviceId = "devC").map { it.id })
        assertEquals(listOf(mine.id), reg.list(status = ReviewStatus.DELIVERED).map { it.id })
        assertEquals(
            setOf(mine.id, theirs.id), reg.list(sinceRevision = 2).mapTo(HashSet()) { it.id },
            "a new row begins at revision 1 and must not disappear behind another row's revision",
        )
    }

    @Test
    fun ids_are_random_enough_not_to_collide() {
        val ids = (0 until 500).map { ReviewRegistry.randomRequestId() }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.startsWith("rr_") })
        assertNull(ids.firstOrNull { it.length > ReviewLimits.MAX_ID })
    }
}
