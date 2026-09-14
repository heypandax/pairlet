package dev.ccpocket.app.pins

import dev.ccpocket.protocol.ProjectPin
import dev.ccpocket.protocol.ProjectPinErrors
import dev.ccpocket.protocol.ProjectPinOp
import dev.ccpocket.protocol.ProjectPinResolution
import dev.ccpocket.protocol.ProjectPinsSnapshot
import dev.ccpocket.protocol.ProjectPinsState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The client's pure pin rules (issue #362): the pending overlay, correlated acknowledgement, revision and
 * incarnation handling, the discontinuity rebase that never replays uncertain intent, alias identities, and the
 * one-time legacy migration shapes. No I/O, no transport — every rule is pinned on documents alone.
 */
class ProjectPinReducerTest {

    private var tokens = 0
    private fun token() = "stream-" + (++tokens).toString().padStart(16, '0')

    /** A daemon-like opaque identity per canonical project. */
    private fun key(path: String) = "K" + path.removePrefix("/alias").hashCode().toUInt().toString(16)

    private fun snap(rev: Long, vararg paths: String, inc: String = "inc-1") =
        ProjectPinsSnapshot(inc, rev, paths.map { ProjectPin(it, key(it)) })

    private fun reply(doc: PinStateDoc, ack: Long?, snapshot: ProjectPinsSnapshot?, error: String? = null) =
        ProjectPinsState("sub", requestId = "r", streamId = doc.stream.id, ackSeq = ack, snapshot = snapshot, error = error)

    private fun fresh() = ProjectPinReducer.fresh(token())
    private fun enq(doc: PinStateDoc, path: String, pinned: Boolean = true) =
        (ProjectPinReducer.enqueue(doc, path, pinned) as ProjectPinReducer.Edit.Applied).doc
    private fun visible(doc: PinStateDoc) = ProjectPinReducer.visible(doc, synced = true)
    private fun apply(doc: PinStateDoc, r: ProjectPinsState, device: String = "devA") =
        ProjectPinReducer.applyReply(doc, r, fetchContext(doc), device, ::token)

    private fun fetchContext(doc: PinStateDoc) = PinRequestContext(PinRequestKind.FETCH, doc.stream.id, "sub", "r", null, emptyList())
    private fun mutationContext(doc: PinStateDoc, ops: List<ProjectPinOp>, inc: String? = doc.stream.incarnation) =
        PinRequestContext(PinRequestKind.MUTATION, doc.stream.id, "sub", "r", inc, ops)

    /** The first correlated fetch reply: binds the stream to the device and store. */
    private fun fetched(doc: PinStateDoc = fresh(), s: ProjectPinsSnapshot = snap(0), device: String = "devA") =
        apply(doc, reply(doc, 0, s), device).doc

    private fun sent(doc: PinStateDoc) = ProjectPinReducer.markSent(doc, doc.stream.nextSeq - 1)

    @Test
    fun the_first_fetch_binds_the_stream_to_its_device_and_store() {
        val doc = fetched(s = snap(3, "/a"))
        assertEquals("devA", doc.stream.deviceId)
        assertEquals("inc-1", doc.stream.incarnation)
        assertEquals(listOf("/a"), visible(doc))
    }

    @Test
    fun pending_intent_overlays_the_snapshot_and_a_broadcast_cannot_erase_it() {
        var doc = fetched(s = snap(1, "/a"))
        doc = enq(doc, "/b")
        doc = enq(doc, "/a", pinned = false)
        assertEquals(listOf("/b"), visible(doc))
        doc = ProjectPinReducer.applyPush(doc, snap(2, "/c", "/a")).doc
        assertEquals(listOf("/b", "/c"), visible(doc), "a newer broadcast keeps /a unpinned and /b pinned until acknowledged")
        assertEquals(listOf(1L, 2L), doc.pending.map { it.seq })
    }

    @Test
    fun an_acknowledgement_clears_exactly_what_it_acknowledges() {
        var doc = fetched()
        doc = sent(enq(enq(enq(doc, "/b"), "/c"), "/b", pinned = false))
        val out = apply(doc, reply(doc, 2, snap(2, "/c", "/b")))
        assertEquals(listOf(3L), out.doc.pending.map { it.seq })
        assertEquals(2, out.doc.stream.ackedSeq)
        assertEquals(listOf("/c"), visible(out.doc))
        assertFalse(out.blocked)
    }

    @Test
    fun a_lost_acknowledgement_is_recovered_by_the_next_fetch_without_a_resend() {
        var doc = sent(enq(enq(fetched(), "/a"), "/b"))
        // the commit landed, its reply did not; the next generation's fetch reports the cursor
        doc = apply(doc, reply(doc, 2, snap(2, "/b", "/a"))).doc
        assertTrue(doc.pending.isEmpty())
        assertTrue(ProjectPinReducer.batch(doc).isEmpty(), "nothing is resent")
        assertEquals(listOf("/b", "/a"), visible(doc))
    }

    @Test
    fun a_newer_push_before_an_older_ack_keeps_the_newer_view_while_the_ack_still_clears() {
        var doc = sent(enq(fetched(s = snap(1)), "/a"))
        doc = ProjectPinReducer.applyPush(doc, snap(3, "/x", "/a")).doc // committed /a at 2, a sibling's /x at 3
        val out = apply(doc, reply(doc, 1, snap(2, "/a")))
        assertEquals(3, out.doc.accepted?.revision, "the stale snapshot does not overwrite the newer view")
        assertTrue(out.doc.pending.isEmpty(), "…but its acknowledgement still clears the completed intent")
        assertEquals(listOf("/x", "/a"), visible(out.doc))
    }

    @Test
    fun a_push_never_adopts_an_unfamiliar_store_and_stale_pushes_change_nothing() {
        val doc = fetched(s = snap(5, "/a"))
        val foreign = ProjectPinReducer.applyPush(doc, snap(9, inc = "inc-2"))
        assertTrue(foreign.refetch)
        assertEquals(doc, foreign.doc)
        val stale = ProjectPinReducer.applyPush(doc, snap(4))
        assertFalse(stale.refetch)
        assertEquals(doc, stale.doc)
        val unbound = ProjectPinReducer.applyPush(fresh(), snap(1, "/a"))
        assertFalse(unbound.refetch, "before any correlated fetch the reply on its way is authoritative")
        assertNull(unbound.doc.accepted)
    }

    @Test
    fun a_new_store_incarnation_sets_aside_what_may_have_been_sent_and_keeps_what_never_left() {
        var doc = sent(enq(enq(fetched(), "/a"), "/b"))
        doc = enq(doc, "/c") // queued after the last send: certainly never left this device
        val oldStream = doc.stream.id
        val out = apply(doc, ProjectPinsState("sub", "r", oldStream, 0, snap(0, inc = "inc-2")))
        assertNotEquals(oldStream, out.doc.stream.id)
        assertEquals("inc-2", out.doc.stream.incarnation)
        assertEquals(listOf(ProjectPinOp(1, "/c", true)), out.doc.pending, "never-sent intent moves to seq 1 of the new stream")
        assertEquals(listOf("/a", "/b"), out.doc.quarantined.map { it.path })
        assertEquals(PinSyncIssue.RetainedLocally(2), out.issue)
        assertEquals(listOf("/c", "/b", "/a"), visible(out.doc), "retained intent stays visible on this device")
        assertEquals(listOf("/c"), ProjectPinReducer.batch(out.doc).map { it.path }, "…but only /c will ever be sent")
    }

    @Test
    fun re_pairing_never_replays_an_ack_lost_pin_that_a_sibling_unpinned_meanwhile() {
        var doc = sent(enq(fetched(), "/x")) // pinned, sent, committed at rev 1 — the ACK was lost
        // a sibling unpinned /x (rev 2); this device re-pairs and fetches under its NEW transport identity
        val out = apply(doc, reply(doc, 0, snap(2)), device = "devA-repaired")
        doc = out.doc
        assertEquals("devA-repaired", doc.stream.deviceId)
        assertTrue(ProjectPinReducer.batch(doc).isEmpty(), "the uncertain pin is never sent again automatically")
        assertEquals(listOf("/x"), visible(doc), "it is retained locally only")
        assertEquals(PinSyncIssue.RetainedLocally(1), out.issue)

        doc = enq(doc, "/x", pinned = false) // the user re-confirms: an explicit action supersedes the quarantine
        assertTrue(doc.quarantined.isEmpty())
        assertEquals(emptyList(), visible(doc))
        doc = enq(doc, "/x", pinned = true) // …and a new explicit pin may win once committed
        assertEquals(listOf(ProjectPinOp(1, "/x", false), ProjectPinOp(2, "/x", true)), ProjectPinReducer.batch(doc))
    }

    @Test
    fun a_sequence_gap_refusal_rebases_instead_of_sending_into_the_gap() {
        var doc = fetched()
        repeat(6) { doc = enq(doc, "/p$it") }
        doc = sent(doc)
        doc = apply(doc, reply(doc, 5, snap(5))).doc
        assertEquals(listOf(6L), doc.pending.map { it.seq })
        val out = apply(doc, reply(doc, 3, snap(5), error = ProjectPinErrors.SEQUENCE_GAP))
        assertTrue(out.doc.pending.isEmpty())
        assertEquals(listOf("/p5"), out.doc.quarantined.map { it.path })
        assertNotEquals(doc.stream.id, out.doc.stream.id)
    }

    @Test
    fun an_ack_beyond_anything_allocated_is_never_trusted() {
        val doc = sent(enq(fetched(), "/a"))
        val out = apply(doc, reply(doc, 5, snap(9, "/a")))
        assertNotEquals(doc.stream.id, out.doc.stream.id, "restored-from-older-copy state starts a new stream")
        assertEquals(listOf("/a"), out.doc.quarantined.map { it.path })
    }

    @Test
    fun a_lower_ack_from_a_stale_reply_never_regresses_the_cursor() {
        var doc = sent(enq(enq(enq(enq(fetched(), "/a"), "/b"), "/c"), "/d"))
        doc = apply(doc, reply(doc, 3, snap(3))).doc
        val out = apply(doc, reply(doc, 1, snap(1)))
        assertEquals(3, out.doc.stream.ackedSeq)
        assertEquals(listOf(4L), out.doc.pending.map { it.seq })
        assertEquals(3, out.doc.accepted?.revision)
    }

    @Test
    fun refusals_keep_every_unacknowledged_change_and_block_flushing() {
        var doc = sent(enq(enq(enq(enq(fetched(), "/a"), "/b"), "/c"), "/d"))
        val capacity = apply(doc, reply(doc, 2, snap(2, "/b", "/a"), error = ProjectPinErrors.CAPACITY))
        assertEquals(listOf(3L, 4L), capacity.doc.pending.map { it.seq })
        assertTrue(capacity.blocked)
        assertEquals(PinSyncIssue.Refused(ProjectPinErrors.CAPACITY, null), capacity.issue)

        doc = capacity.doc
        val storage = apply(doc, reply(doc, null, null, error = ProjectPinErrors.STORE_CORRUPT))
        assertEquals(doc, storage.doc, "a storage refusal acknowledges and adopts nothing")
        assertTrue(storage.blocked)
    }

    @Test
    fun enqueue_is_contiguous_validated_and_bounded() {
        var doc = fresh()
        assertEquals(ProjectPinReducer.Edit.Invalid, ProjectPinReducer.enqueue(doc, "/a\nb", true))
        repeat(ProjectPinReducer.MAX_PENDING) { doc = enq(doc, "/p$it") }
        assertEquals((1L..ProjectPinReducer.MAX_PENDING).toList(), doc.pending.map { it.seq })
        assertEquals(ProjectPinReducer.Edit.OutboxFull, ProjectPinReducer.enqueue(doc, "/one-more", true))
    }

    @Test
    fun intent_on_a_spelling_the_daemon_has_since_renamed_still_means_the_same_project() {
        val real = ProjectPin("/real", "K1")
        var doc = fetched(s = ProjectPinsSnapshot("inc-1", 1, listOf(real)))
        doc = enq(doc, "/real", pinned = false)
        // the daemon re-canonicalized: same identity, different display path
        doc = ProjectPinReducer.applyPush(doc, ProjectPinsSnapshot("inc-1", 2, listOf(ProjectPin("/link", "K1")))).doc
        assertEquals(emptyList(), visible(doc), "the queued unpin still hides the one project")
        assertEquals(mapOf("/real" to "K1"), doc.aliases)

        var pinDoc = fetched(s = ProjectPinsSnapshot("inc-1", 2, listOf(ProjectPin("/link", "K1"))))
        pinDoc = pinDoc.copy(aliases = mapOf("/real" to "K1"))
        pinDoc = enq(pinDoc, "/real")
        assertEquals(listOf("/link"), visible(pinDoc), "pin intent on the old spelling is not a second row")

        val acked = apply(doc, reply(doc, 1, snap(3)))
        assertEquals(mapOf("/real" to "K1"), acked.doc.aliases, "a learned key outlives the acknowledgement of its intent")
    }

    @Test
    fun a_claimed_legacy_list_becomes_operations_oldest_first_with_its_marker_in_the_same_document() {
        val doc = ProjectPinReducer.claimLegacy(fresh(), listOf("/a", "/b", "/c", "/b", "bad\u0000"), "d1")
        assertEquals(listOf("/c", "/b", "/a"), doc.pending.map { it.path })
        val bad = doc.legacy!!.fallback.single() // carried whole but never sent: the wire cannot carry it
        assertTrue(bad.startsWith("bad"))
        assertEquals(LegacyPins("d1", claimed = false, fallback = listOf(bad)), doc.legacy)
        assertEquals(PinSyncIssue.LegacyNotMigrated(1), ProjectPinReducer.migrationIssue(doc))
        assertEquals(listOf("/a", "/b", "/c"), visible(doc), "the old order survives the daemon's newest-first prepends")
    }

    @Test
    fun a_legacy_fallback_stays_local_until_this_computers_listing_proves_a_path() {
        var doc = fetched(s = snap(1, "/x"))
        doc = ProjectPinReducer.holdLegacyFallback(doc, listOf("/a", "/b", "/c"), "d2")
        assertTrue(doc.pending.isEmpty(), "nothing is sent for an unproven path")
        assertEquals(listOf("/x", "/a", "/b", "/c"), visible(doc))

        doc = ProjectPinReducer.claimProvenFallback(doc, setOf("/b", "/zzz"))
        assertEquals(listOf(ProjectPinOp(1, "/b", true)), doc.pending)
        assertEquals(listOf("/a", "/c"), doc.legacy?.fallback)
        assertEquals(ProjectPinReducer.claimProvenFallback(doc, setOf("/b")), doc, "a path is claimed once")

        doc = enq(doc, "/a", pinned = false)
        assertEquals(listOf("/c"), doc.legacy?.fallback, "an explicit action supersedes the fallback entry")
        assertEquals(listOf("/b", "/x", "/c"), visible(doc))
    }

    @Test
    fun a_local_scope_is_a_plain_list_and_pinning_again_keeps_the_place() {
        var doc = fresh()
        fun set(path: String, pinned: Boolean) { doc = (ProjectPinReducer.setLocal(doc, path, pinned) as ProjectPinReducer.Edit.Applied).doc }
        set("/a", true); set("/b", true); set("/a", true)
        assertEquals(listOf("/b", "/a"), ProjectPinReducer.visible(doc, synced = false))
        set("/b", false)
        assertEquals(listOf("/a"), ProjectPinReducer.visible(doc, synced = false))
        assertTrue(doc.pending.isEmpty() && doc.stream.nextSeq == 1L, "a local scope never builds an outbox")
    }

    // ---- complete legacy migration ----

    @Test
    fun a_legacy_list_larger_than_the_outbox_is_kept_whole_and_drains_oldest_first_as_acks_free_room() {
        val newestFirst = (ProjectPinReducer.MAX_PENDING downTo 0).map { "/p$it" } // 2049 entries, /p0 the oldest
        var doc = ProjectPinReducer.claimLegacy(fetched(), newestFirst, "big")
        assertEquals((0 until ProjectPinReducer.MAX_PENDING).map { "/p$it" }, doc.pending.map { it.path })
        assertEquals(LegacyPins("big", claimed = false, fallback = listOf("/p2048"), eligible = listOf("/p2048")), doc.legacy)
        assertEquals(doc, ProjectPinReducer.advanceLegacy(doc), "no room: a repeated attempt changes nothing")

        doc = sent(doc)
        doc = apply(doc, reply(doc, 1, snap(1))).doc
        assertEquals(ProjectPinOp(2049, "/p2048", true), doc.pending.last(), "next contiguous seq, in the acknowledging transition")
        assertEquals(LegacyPins("big", claimed = true), doc.legacy, "claimed once nothing is left; the digest stays")
    }

    @Test
    fun legacy_entries_the_wire_cannot_carry_are_retained_but_never_sent_or_listed() {
        val bad = "/badpath"
        val doc = ProjectPinReducer.claimLegacy(fresh(), listOf("/a", bad, "/b"), "d")
        assertEquals(listOf("/b", "/a"), doc.pending.map { it.path })
        assertEquals(LegacyPins("d", claimed = false, fallback = listOf(bad)), doc.legacy)
        assertEquals(PinSyncIssue.LegacyNotMigrated(1), ProjectPinReducer.migrationIssue(doc))
        assertEquals(listOf("/a", "/b"), visible(doc))
    }

    @Test
    fun an_ambiguous_fallback_drains_only_proven_paths_as_room_allows_and_keeps_the_proof() {
        var doc = fetched()
        repeat(ProjectPinReducer.MAX_PENDING - 1) { doc = enq(doc, "/q$it") }
        doc = ProjectPinReducer.holdLegacyFallback(doc, listOf("/a", "/b", "/c"), "d")
        doc = ProjectPinReducer.claimProvenFallback(doc, setOf("/a", "/c"))
        assertEquals(ProjectPinOp(2048, "/c", true), doc.pending.last(), "oldest proven first")
        assertEquals(listOf("/a", "/b"), doc.legacy?.fallback)
        assertEquals(listOf("/a"), doc.legacy?.eligible, "the proof outlives the full outbox")

        doc = sent(doc)
        doc = apply(doc, reply(doc, 1, snap(1))).doc
        assertEquals(ProjectPinOp(2049, "/a", true), doc.pending.last())
        assertEquals(LegacyPins("d", claimed = false, fallback = listOf("/b")), doc.legacy, "the unproven path stays local")
    }

    // ---- correlated resolutions and lookup-only work ----

    private fun mutated(
        doc: PinStateDoc,
        ops: List<ProjectPinOp>,
        ack: Long,
        s: ProjectPinsSnapshot,
        resolutions: List<ProjectPinResolution>?,
        error: String? = null,
        context: PinRequestContext = mutationContext(doc, ops),
    ) = ProjectPinReducer.applyReply(
        doc, ProjectPinsState("sub", "r", doc.stream.id, ack, s, error, null, resolutions), context, "devA", ::token,
    )

    @Test
    fun only_rows_naming_exactly_one_submitted_seq_and_path_teach_a_key() {
        val doc = sent(enq(enq(enq(fetched(), "/a"), "/b"), "/c"))
        val ops = ProjectPinReducer.batch(doc)
        val rows = listOf(
            ProjectPinResolution(1, "/a", "KA"),
            ProjectPinResolution(2, "/not-b", "KB"),
            ProjectPinResolution(3, "/c", "KC1"), ProjectPinResolution(3, "/c", "KC2"),
            ProjectPinResolution(9, "/z", "KZ"),
        )
        val out = mutated(doc, ops, 3, snap(3, "/c", "/b", "/a"), rows)
        assertEquals(mapOf("/a" to "KA"), out.doc.aliases)
        assertTrue(out.doc.pending.isEmpty())
        assertEquals(listOf(2L, 3L), out.doc.resolutionPending.map { it.seq }, "unresolved acknowledged ops wait for lookup only")
        assertTrue(ProjectPinReducer.batch(out.doc).isEmpty(), "…and are never fresh intent")
        assertEquals(4, out.doc.stream.nextSeq)

        assertTrue(mutated(doc, ops, 3, snap(3), rows, context = fetchContext(doc)).doc.aliases.isEmpty(), "a fetch grants nothing")
        assertTrue(mutated(doc, ops, 3, snap(3), rows, error = ProjectPinErrors.CAPACITY).doc.aliases.isEmpty(), "a refusal grants nothing")
        assertTrue(
            mutated(doc, ops, 3, snap(3), rows, context = mutationContext(doc, ops, inc = "inc-other")).doc.aliases.isEmpty(),
            "a request that named another incarnation grants nothing",
        )
        assertEquals(doc, mutated(doc, ops, 3, snap(3), rows, context = mutationContext(doc, ops).copy(requestId = "other")).doc)
    }

    @Test
    fun a_lookup_replay_learns_the_current_resolution_and_never_becomes_a_new_operation() {
        var doc = sent(enq(fetched(), "/a"))
        doc = apply(doc, reply(doc, 1, snap(1, "/a"))).doc // the fetch proves the commit but carries no resolution
        assertEquals(listOf(ProjectPinOp(1, "/a", true)), doc.resolutionPending)
        assertTrue(doc.pending.isEmpty(), "the acknowledged pin left the overlay")
        val lookup = ProjectPinReducer.lookupBatch(doc)
        assertEquals(listOf(ProjectPinOp(1, "/a", true)), lookup, "resent with its original seq")

        // deduplicated replay: the daemon reports how "/a" resolves NOW, not what it meant when first committed
        val out = mutated(doc, lookup, 1, snap(2, "/a"), listOf(ProjectPinResolution(1, "/a", "Knew")))
        assertEquals("Knew", out.doc.aliases["/a"])
        assertTrue(out.doc.resolutionPending.isEmpty() && out.doc.pending.isEmpty())
        assertEquals(doc.stream.nextSeq, out.doc.stream.nextSeq, "no sequence number allocated")

        val unresolved = mutated(doc, lookup, 1, snap(2, "/a"), null)
        assertEquals(doc.resolutionPending, unresolved.doc.resolutionPending, "no resolutions: the uncertainty stays, nothing retries")
        assertFalse(unresolved.blocked)
    }

    @Test
    fun lookup_work_is_bounded_by_evicting_the_oldest_and_its_batches_stop_at_gaps() {
        var doc = fetched()
        repeat(ProjectPinReducer.MAX_PENDING) { doc = enq(doc, "/p$it") }
        doc = sent(doc)
        doc = apply(doc, reply(doc, 2048, snap(1))).doc
        assertEquals(ProjectPinReducer.MAX_LOOKUP, doc.resolutionPending.size)
        doc = sent(enq(doc, "/last"))
        val out = apply(doc, reply(doc, 2049, snap(2)))
        assertEquals(PinSyncIssue.AliasResolutionLimited, out.issue)
        assertEquals((2L..2049L).toList(), out.doc.resolutionPending.map { it.seq })
        assertTrue(out.doc.pending.isEmpty())
        assertFalse(out.blocked, "limited lookup knowledge never blocks progress")

        assertEquals(64, ProjectPinReducer.lookupBatch(out.doc).size)
        val gapped = out.doc.copy(resolutionPending = listOf(5L, 6L, 7L, 9L).map { ProjectPinOp(it, "/g$it", true) })
        assertEquals(listOf(5L, 6L, 7L), ProjectPinReducer.lookupBatch(gapped).map { it.seq })
    }

    @Test
    fun an_incarnation_mismatch_or_stale_subscription_applies_nothing_and_asks_for_a_fetch() {
        val doc = sent(enq(fetched(s = snap(1, "/a")), "/b"))
        val ops = ProjectPinReducer.batch(doc)
        for (code in listOf(ProjectPinErrors.INCARNATION_MISMATCH, ProjectPinErrors.SUBSCRIPTION_STALE)) {
            val out = mutated(doc, ops, 1, snap(5, "/x", inc = "inc-2"), listOf(ProjectPinResolution(1, "/b", "KB")), error = code)
            assertEquals(doc, out.doc, code)
            assertTrue(out.refetch && out.blocked, code)
        }
        val foreign = mutated(doc, ops, 1, snap(5, inc = "inc-2"), null)
        assertEquals(doc, foreign.doc, "only a fetch may move the stream to another store")
        assertTrue(foreign.refetch)
    }

    @Test
    fun a_refused_fetch_from_another_store_or_device_moves_nothing_and_only_success_rebases() {
        val doc = sent(enq(fetched(s = snap(1, "/a")), "/b")).copy(aliases = mapOf("/a" to "KA"))
        for (code in listOf(ProjectPinErrors.STORE_CORRUPT, ProjectPinErrors.CAPACITY)) {
            val foreignStore = apply(doc, reply(doc, null, snap(0, inc = "inc-2"), error = code))
            assertEquals(doc, foreignStore.doc, "$code: a refused fetch adopts no incarnation")
            assertEquals(PinSyncIssue.Refused(code, null), foreignStore.issue)
            assertTrue(foreignStore.blocked, code)
            assertFalse(foreignStore.refetch, "$code: a refusal is not answered with another fetch")

            val foreignDevice = apply(doc, reply(doc, null, snap(1, "/a"), error = code), device = "devB")
            assertEquals(doc, foreignDevice.doc, "$code: a refused fetch proves no new device")
            assertEquals(PinSyncIssue.Refused(code, null), foreignDevice.issue)
            assertTrue(foreignDevice.blocked && !foreignDevice.refetch, code)
        }

        for ((device, inc) in listOf("devA" to "inc-2", "devB" to "inc-1")) {
            val ok = apply(doc, reply(doc, 0, snap(0, inc = inc)), device = device)
            assertNotEquals(doc.stream.id, ok.doc.stream.id, "$device/$inc: a successful fetch does rebase")
            assertEquals(device, ok.doc.stream.deviceId)
            assertEquals(inc, ok.doc.stream.incarnation)
            assertEquals(listOf("/b"), ok.doc.quarantined.map { it.path })
            assertTrue(ok.doc.aliases.isEmpty())
            assertFalse(ok.blocked)
        }
    }

    @Test
    fun a_fetch_from_another_store_forgets_what_was_learned_about_the_old_one() {
        var doc = sent(enq(fetched(s = snap(1, "/a")), "/b"))
        doc = mutated(doc, ProjectPinReducer.batch(doc), 1, snap(2, "/b", "/a"), listOf(ProjectPinResolution(1, "/b", "KB"))).doc
        assertEquals("KB", doc.aliases["/b"])
        doc = doc.copy(resolutionPending = listOf(ProjectPinOp(1, "/b", true)))
        val out = apply(doc, ProjectPinsState("sub", "r", doc.stream.id, 0, snap(0, inc = "inc-2")))
        assertTrue(out.doc.aliases.isEmpty())
        assertTrue(out.doc.resolutionPending.isEmpty(), "acknowledged ops are never quarantined or replayed into the new stream")
        assertTrue(out.doc.quarantined.isEmpty() && out.doc.pending.isEmpty())
    }
}
