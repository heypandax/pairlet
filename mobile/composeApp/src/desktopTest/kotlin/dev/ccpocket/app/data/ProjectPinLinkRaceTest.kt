package dev.ccpocket.app.data

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ccpocket.app.net.PinDispatchFence
import dev.ccpocket.app.net.PinEnqueueResult
import dev.ccpocket.app.net.ScopedOutbox
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.pairing.Pairing
import dev.ccpocket.app.pins.FileProjectPinPersistence
import dev.ccpocket.app.pins.PinScopeKey
import dev.ccpocket.app.pins.ProjectPinRegistry
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ProjectPin
import dev.ccpocket.protocol.ProjectPinErrors
import dev.ccpocket.protocol.ProjectPinOp
import dev.ccpocket.protocol.ProjectPinResolution
import dev.ccpocket.protocol.ProjectPinsSnapshot
import dev.ccpocket.protocol.ProjectPinsState
import dev.ccpocket.protocol.SyncProjectPins
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.yield
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The races the pin link must win (issue #362), with pin frames queued into a real [ScopedOutbox] and written only
 * when that outbox's actual writer loop runs — so a switch, a re-pair or a refusal can land between queuing and
 * writing, exactly where the old suspending send could not tell.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class ProjectPinLinkRaceTest {

    private val dir: File = Files.createTempDirectory("ccp-pin-race").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val registry = seeded(ProjectPinRegistry(FileProjectPinPersistence(dir)))
    private val a = binding("acct-a")
    private var savedActive: String? = null

    @BeforeTest
    fun setUp() {
        savedActive = Pairing.activeAccount()
        PinIssueBoard.shared.clearForTest()
    }

    @AfterTest
    fun tearDown() {
        Pairing.setActive(savedActive)
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun binding(id: String, credential: String = "c-$id") =
        PairedDaemon(relay = "wss://127.0.0.1:9", accountId = id, daemonPub = "pk-$id", deviceId = "dev-$id", credential = credential)

    private fun seeded(reg: ProjectPinRegistry) = reg.apply { initializeAuthorityOnce { listOf(binding("acct-a"), binding("acct-b")) } }

    /** A stand-in E2E connection: its pin queue is a real [ScopedOutbox]; [write] runs the real writer loop. */
    private class Connection {
        val outbox = ScopedOutbox()
        var gen = 1
        var full = false
        var fullCount = 0
        val queued = mutableListOf<SyncProjectPins>()
        val written = mutableListOf<Frame>()

        /** The queue as a DaemonInfo on connection [gen] captures it. */
        fun writer(): (SyncProjectPins, PinDispatchFence) -> PinEnqueueResult {
            val captured = gen
            return { frame, fence ->
                if (full) PinEnqueueResult.FULL.also { fullCount++ }
                else outbox.tryEnqueuePin(frame, fence, captured) { gen }.also { if (it == PinEnqueueResult.ACCEPTED) queued += frame }
            }
        }

        /** Connection [writerGen]'s writer takes what it can until the outbox is empty or the writer dies. */
        fun write(writerGen: Int = gen) = runBlocking {
            val job = launch { runCatching { outbox.runWriter(writerGen, { writerGen == gen }) { written += it } } }
            repeat(3) { yield() }
            job.cancel()
        }
    }

    private fun repo(target: PairedDaemon, conn: Connection, reg: ProjectPinRegistry = registry, on: CoroutineScope = scope) =
        PocketRepository(on, pinnedTo = target, projectPinRegistry = reg).apply {
            useRelay = true
            onSendForTest = {}
            pinWriterForTest = conn.writer()
        }

    private fun key(path: String) = "K" + path.hashCode().toUInt().toString(16)

    private fun snap(rev: Long, vararg paths: String, inc: String = "inc-1") =
        ProjectPinsSnapshot(inc, rev, paths.map { ProjectPin(it, key(it)) })

    private fun ack(request: SyncProjectPins, ackSeq: Long?, snapshot: ProjectPinsSnapshot?, error: String? = null, resolutions: List<ProjectPinResolution>? = null) =
        ProjectPinsState(request.subscriptionId, request.requestId, request.streamId, ackSeq, snapshot, error, null, resolutions)

    private fun doc(reg: ProjectPinRegistry = registry) = reg.scope(PinScopeKey.Owner("acct-a")).document()

    private fun PocketRepository.synced(conn: Connection, snapshot: ProjectPinsSnapshot = snap(0), ackSeq: Long = 0): SyncProjectPins {
        receiveForTest(DaemonInfo(supportsProjectPins = true))
        val fetch = conn.queued.last()
        assertTrue(fetch.ops.isEmpty())
        receiveForTest(ack(fetch, ackSeq, snapshot))
        return fetch
    }

    @Test
    fun pin_frames_queued_for_one_computer_are_never_written_after_switching_to_another() {
        val conn = Connection()
        val b = binding("acct-b")
        val primary = PocketRepository(scope, projectPinRegistry = registry).apply { useRelay = true; onSendForTest = {}; paired.value = a }
        primary.switchDaemon(a)
        primary.togglePin("/a/one")
        primary.pinWriterForTest = conn.writer()
        primary.receiveForTest(DaemonInfo(supportsProjectPins = true))
        val aFetch = conn.queued.single()
        conn.write()
        assertEquals(listOf<Frame>(aFetch), conn.written)
        primary.receiveForTest(ack(aFetch, 0, snap(0)))
        val aFlush = conn.queued.last()
        assertEquals(listOf(ProjectPinOp(1, "/a/one", true)), aFlush.ops) // queued on A's connection, not yet written

        primary.switchDaemon(b) // the switch lands between queuing and writing
        conn.gen = 2
        conn.write(1) // A's superseded writer takes the frame: dropped, not handed on
        conn.outbox.prepareFor(2)
        primary.pinWriterForTest = conn.writer()
        primary.receiveForTest(DaemonInfo(supportsProjectPins = true)) // B's own connection advertises
        conn.write(2)

        val written = conn.written.filterIsInstance<SyncProjectPins>()
        assertTrue(written.none { f -> f.ops.any { it.path == "/a/one" } }, "A's flush never reached any connection")
        assertEquals(listOf(aFetch), written.filter { it.subscriptionId == aFetch.subscriptionId })
        val bFetch = written.last()
        assertNotEquals(aFetch.subscriptionId, bFetch.subscriptionId)
        assertNotEquals(aFetch.streamId, bFetch.streamId, "B's own stream")
        assertTrue(bFetch.ops.isEmpty())
    }

    @Test
    fun a_replaced_binding_retires_its_controller_which_can_neither_update_nor_send_nor_reacquire() {
        val conn = Connection()
        val old = repo(a, conn)
        old.togglePin("/p/one")
        old.synced(conn)
        val flush = conn.queued.last()
        assertEquals(1, flush.ops.single().seq)

        registry.refreshAfterPairingChange { listOf(binding("acct-a", credential = "c-new"), binding("acct-b")) }
        val frozen = doc()
        val queued = conn.queued.size
        old.togglePin("/p/two")
        old.receiveForTest(ack(flush, 1, snap(1, "/p/one")))
        old.receiveForTest(DaemonInfo(supportsProjectPins = true))
        assertEquals(frozen, doc(), "no update from the stale controller: not an edit, not an ACK")
        assertEquals(queued, conn.queued.size, "and no frame")
        assertEquals(PinIssueKind.PAIRING_CHANGED, old.projectPinIssueNotice.value?.kind)
        assertNull(registry.acquireLease(a), "the stale binding cannot reacquire")

        val conn2 = Connection()
        val current = repo(binding("acct-a", credential = "c-new"), conn2)
        current.togglePin("/p/three")
        assertEquals(listOf(1L, 2L), doc().pending.map { it.seq }, "the current binding continues the one sequence")
        current.receiveForTest(DaemonInfo(supportsProjectPins = true))
        assertTrue(conn2.queued.single().ops.isEmpty(), "and starts with its own fetch")
        old.togglePin("/p/four")
        assertEquals(2, doc().pending.size, "the retired controller still cannot write")
        assertEquals(queued, conn.queued.size)
    }

    @Test
    fun an_unfamiliar_push_holds_every_mutation_until_the_new_fetch_and_older_replies_do_not_reopen_it() {
        val conn = Connection()
        val r = repo(a, conn)
        val firstFetch = r.synced(conn)
        r.togglePin("/p/1")
        val f1 = conn.queued.last()
        assertEquals(1, f1.ops.single().seq)

        r.receiveForTest(ProjectPinsState(firstFetch.subscriptionId, snapshot = snap(5, inc = "inc-2")))
        val refetch = conn.queued.last()
        assertTrue(refetch.ops.isEmpty(), "an unfamiliar store asks for a fetch")
        val count = conn.queued.size

        r.togglePin("/p/2")
        assertEquals(count, conn.queued.size, "no mutation while the fetch is out")
        r.receiveForTest(ack(f1, 1, snap(1, "/p/1")))
        assertEquals(count, conn.queued.size, "an older mutation reply neither reopens the barrier nor flushes")
        r.receiveForTest(ack(firstFetch, 0, snap(9, "/ghost")))
        assertEquals(count, conn.queued.size, "an older fetch reply speaks for nothing")

        r.receiveForTest(ack(refetch, 0, snap(0, inc = "inc-2")))
        assertEquals(count + 1, conn.queued.size)
        val resumed = conn.queued.last()
        assertEquals(listOf(ProjectPinOp(1, "/p/2", true)), resumed.ops, "never-sent intent moves to the new stream")
        assertEquals("inc-2", resumed.expectedIncarnation)
    }

    @Test
    fun a_refusal_carrying_a_newer_revision_starts_no_listener_driven_resend_in_either_controller() {
        val c1 = Connection()
        val c2 = Connection()
        val r1 = repo(a, c1)
        val r2 = repo(a, c2)
        r1.synced(c1)
        r2.synced(c2)
        r1.togglePin("/p/1")
        val f1 = c1.queued.last()
        assertEquals(1, f1.ops.single().seq)
        val f2 = c2.queued.last() // r2 heard the edit too and has its own copy in flight
        assertEquals(f1.ops, f2.ops)

        r1.receiveForTest(ack(f1, 0, snap(7, "/elsewhere"), error = ProjectPinErrors.CAPACITY))
        assertEquals(7, doc().accepted?.revision, "the refusal's newer revision was published to both controllers")
        // r2's own in-flight copy is answered after the refusal: its slot frees and the shared latch must hold it
        r2.receiveForTest(ack(f2, 0, snap(7, "/elsewhere")))
        assertEquals(1, c1.queued.count { it.ops.isNotEmpty() }, "r1 does not resend")
        assertEquals(1, c2.queued.count { it.ops.isNotEmpty() }, "r2 does not resend into the refusal either")

        r2.togglePin("/p/2") // an explicit edit is the trigger
        assertEquals(listOf(1L, 2L), c2.queued.last().ops.map { it.seq })
        assertEquals(1, c1.queued.count { it.ops.isNotEmpty() }, "r1 stays blocked on its own refusal until its own trigger")
    }

    @Test
    fun an_ack_lost_restart_clears_the_intent_and_a_lookup_replay_learns_the_current_identity_without_a_commit() {
        val conn = Connection()
        val r = repo(a, conn)
        r.synced(conn)
        r.togglePin("/alias/one") // the daemon commits it as /real/one; the reply is lost with the app
        val sentOp = conn.queued.last().ops.single()

        val reg2 = seeded(ProjectPinRegistry(FileProjectPinPersistence(dir)))
        val conn2 = Connection()
        val r2 = repo(a, conn2, reg2)
        val fetch = r2.synced(conn2, snap(1, "/real/one"), ackSeq = 1)
        assertEquals(listOf("/real/one"), r2.pinnedPaths, "the acknowledged intent left the overlay")
        assertTrue(doc(reg2).pending.isEmpty())
        val lookup = conn2.queued.last()
        assertEquals(listOf(sentOp), lookup.ops, "the original operation with its original sequence")
        assertEquals(fetch.streamId, lookup.streamId)
        assertEquals("inc-1", lookup.expectedIncarnation)
        val nextSeq = doc(reg2).stream.nextSeq

        r2.receiveForTest(ack(lookup, 1, snap(2, "/real/one"), resolutions = listOf(ProjectPinResolution(1, "/alias/one", key("/real/one")))))
        assertEquals(nextSeq, doc(reg2).stream.nextSeq, "no sequence allocated")
        assertEquals(2, conn2.queued.size, "nothing further to send")
        assertTrue(r2.isPinned("/alias/one"), "the resolved spelling is the pinned project")

        r2.togglePin("/alias/one")
        assertEquals(listOf(ProjectPinOp(nextSeq, "/alias/one", false)), doc(reg2).pending, "an unpin of that project — not a second pin")
        assertTrue(r2.pinnedPaths.isEmpty())
    }

    /** An ACK-lost restart: the acknowledged pin of /alias/one awaits its identity, and its lookup is the last queued
     *  frame of the returned repository's connection. */
    private fun lookupAfterRestart(): Triple<PocketRepository, Connection, ProjectPinRegistry> {
        val conn = Connection()
        repo(a, conn).apply { synced(conn); togglePin("/alias/one") }
        val reg2 = seeded(ProjectPinRegistry(FileProjectPinPersistence(dir)))
        val conn2 = Connection()
        val r2 = repo(a, conn2, reg2)
        r2.synced(conn2, snap(1, "/real/one"), ackSeq = 1)
        return Triple(r2, conn2, reg2)
    }

    @Test
    fun foreground_allows_one_more_lookup_after_an_unresolved_reply_while_churn_sends_nothing() {
        val (r, conn, reg) = lookupAfterRestart()
        val lookup = conn.queued.last()
        val op = lookup.ops.single()
        r.receiveForTest(ack(lookup, 1, snap(1, "/real/one"))) // answered, but nothing resolved
        assertEquals(listOf(op), doc(reg).resolutionPending, "still awaiting its identity")
        r.receiveForTest(ProjectPinsState(lookup.subscriptionId, snapshot = snap(2, "/real/one"))) // listener churn
        val settled = conn.queued.size
        assertEquals(2, settled, "no lookup spin without a trigger")

        r.sessionActive.value = true
        r.connected.value = true
        r.onAppForeground()
        val refetch = conn.queued.last()
        assertEquals(settled + 1, conn.queued.size)
        assertTrue(refetch.ops.isEmpty(), "foreground fetches first")
        r.receiveForTest(ack(refetch, 1, snap(2, "/real/one")))
        assertEquals(settled + 2, conn.queued.size)
        val again = conn.queued.last()
        assertEquals(listOf(op), again.ops, "one more lookup, with the original sequence")
        assertEquals("inc-1", again.expectedIncarnation)

        r.receiveForTest(ack(again, 1, snap(2, "/real/one"))) // still nothing resolved
        r.receiveForTest(ProjectPinsState(lookup.subscriptionId, snapshot = snap(3, "/real/one")))
        assertEquals(settled + 2, conn.queued.size, "and only one")
    }

    @Test
    fun a_dismissed_lookup_refusal_outlives_a_working_reply_that_resolves_nothing_and_ends_when_its_lookup_is_resolved() {
        val (r, conn, reg) = lookupAfterRestart()
        val lookup = conn.queued.last()
        val op = lookup.ops.single()
        r.receiveForTest(ack(lookup, 1, snap(1, "/real/one"), error = ProjectPinErrors.CAPACITY))
        val first = r.projectPinIssueNotice.value
        assertEquals(PinIssueKind.CAPACITY, first?.kind)
        r.dismissProjectPinIssue(first!!)

        /** Retry: a working fetch, after which the same lookup goes out once more. */
        fun resendLookup(): SyncProjectPins {
            r.retryProjectPins()
            val fetch = conn.queued.last()
            assertTrue(fetch.ops.isEmpty())
            r.receiveForTest(ack(fetch, 1, snap(1, "/real/one")))
            return conn.queued.last().also { assertEquals(listOf(op), it.ops, "the lookup again, with its original sequence") }
        }

        r.receiveForTest(ack(resendLookup(), 1, snap(1, "/real/one"))) // working, but resolutions = null
        assertEquals(listOf(op), doc(reg).resolutionPending, "the lookup work is still undone")
        assertEquals(first.id, PinIssueBoard.shared.occurrenceIdForTest(first.scope, PinIssueKind.CAPACITY), "a reply that resolves nothing is no recovery")
        assertNull(r.projectPinIssueNotice.value)

        r.receiveForTest(ack(resendLookup(), 1, snap(1, "/real/one"), error = ProjectPinErrors.CAPACITY))
        assertTrue(r.projectPinSyncIssue.value != null)
        assertNull(r.projectPinIssueNotice.value, "the same lookup refused again is still that dismissed occurrence")
        assertEquals(first.id, PinIssueBoard.shared.occurrenceIdForTest(first.scope, PinIssueKind.CAPACITY))

        r.receiveForTest(ack(resendLookup(), 1, snap(2, "/real/one"), resolutions = listOf(ProjectPinResolution(1, "/alias/one", key("/real/one")))))
        assertTrue(doc(reg).resolutionPending.isEmpty())
        assertTrue(r.isPinned("/alias/one"))
        assertNull(PinIssueBoard.shared.occurrenceIdForTest(first.scope, PinIssueKind.CAPACITY), "a real mapping of that lookup ends it")
        assertNull(r.projectPinSyncIssue.value)
    }

    @Test
    fun a_learned_alias_recomposes_a_composed_pin_query_while_the_visible_list_stays_the_same() = runComposeUiTest {
        val (r, conn, _) = lookupAfterRestart()
        val lookup = conn.queued.last()
        val seen = mutableListOf<Boolean>()
        setContent {
            val pinned = r.isPinned("/alias/one")
            SideEffect { seen += pinned }
        }
        waitForIdle()
        assertEquals(listOf(false), seen)
        val visible = r.pinnedPaths.toList()

        r.receiveForTest(ack(lookup, 1, snap(2, "/real/one"), resolutions = listOf(ProjectPinResolution(1, "/alias/one", key("/real/one")))))
        waitForIdle()
        assertEquals(visible, r.pinnedPaths.toList(), "the mapping alone changed")
        assertEquals(listOf(false, true), seen, "the composed query re-read once, and only because of it")
    }

    @Test
    fun an_incarnation_mismatch_fetches_first_and_quarantines_only_the_sent_unacknowledged_intent() {
        val conn = Connection()
        val r = repo(a, conn)
        r.synced(conn)
        r.togglePin("/p/1")
        val f1 = conn.queued.last()
        assertEquals("inc-1", f1.expectedIncarnation)
        r.receiveForTest(ack(f1, 1, snap(1, "/p/1"), resolutions = listOf(ProjectPinResolution(1, "/p/1", key("/p/1")))))
        r.togglePin("/p/2")
        val f2 = conn.queued.last()
        assertEquals("inc-1", f2.expectedIncarnation)

        r.receiveForTest(ack(f2, null, snap(0, inc = "inc-2"), error = ProjectPinErrors.INCARNATION_MISMATCH))
        assertEquals("inc-1", doc().stream.incarnation, "the refusal's snapshot is never rebased onto")
        assertEquals(listOf(2L), doc().pending.map { it.seq })
        val fetch = conn.queued.last()
        assertTrue(fetch.ops.isEmpty(), "a fetch barrier instead")
        r.togglePin("/p/3")
        assertEquals(fetch, conn.queued.last(), "nothing leaves while fetching")

        r.receiveForTest(ack(fetch, 0, snap(0, inc = "inc-2")))
        assertEquals(listOf("/p/2"), doc().quarantined.map { it.path }, "only the sent, unacknowledged intent is set aside")
        val resumed = conn.queued.last()
        assertEquals(listOf(ProjectPinOp(1, "/p/3", true)), resumed.ops)
        assertEquals("inc-2", resumed.expectedIncarnation)
        assertEquals(PinIssueKind.RETAINED_LOCALLY, r.projectPinIssueNotice.value?.kind)
    }

    @Test
    fun a_push_or_an_uncorrelated_reply_acknowledges_and_resolves_nothing_whatever_it_carries() {
        val conn = Connection()
        val r = repo(a, conn)
        r.synced(conn)
        r.togglePin("/p/1")
        val f1 = conn.queued.last()
        val rows = listOf(ProjectPinResolution(1, "/p/1", key("/real")))
        r.receiveForTest(ProjectPinsState(f1.subscriptionId, streamId = f1.streamId, ackSeq = 1, snapshot = snap(1, "/real"), resolutions = rows))
        r.receiveForTest(ProjectPinsState(f1.subscriptionId, "req-unknown-0123456789", f1.streamId, 1, snap(2, "/real"), null, null, rows))
        assertEquals(listOf(ProjectPinOp(1, "/p/1", true)), doc().pending, "no ACK from a push or another request's reply")
        assertTrue("/p/1" !in doc().aliases, "no identity either")
        assertEquals(2, conn.queued.size)
    }

    @Test
    fun a_full_outbox_retries_a_bounded_number_of_times_on_a_delay_and_never_reconnects() {
        val scheduler = TestCoroutineScheduler()
        val timed = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        try {
            val conn = Connection()
            val r = repo(a, conn, on = timed)
            r.synced(conn)
            conn.full = true
            r.togglePin("/p/1")
            assertEquals(1, conn.fullCount)
            repeat(6) {
                scheduler.advanceTimeBy(ProjectPinLink.RETRY_DELAY_MS + 1)
                scheduler.runCurrent()
            }
            assertEquals(4, conn.fullCount, "three delayed retries, then it waits for a trigger")
            assertEquals(listOf(ProjectPinOp(1, "/p/1", true)), doc().pending, "the intent is untouched")
            assertEquals(0, r.transportLaunches, "a full queue never drives the reconnect path")

            conn.full = false
            r.togglePin("/p/2")
            assertEquals(listOf(1L, 2L), conn.queued.last().ops.map { it.seq })
        } finally {
            timed.cancel()
        }
    }
}
