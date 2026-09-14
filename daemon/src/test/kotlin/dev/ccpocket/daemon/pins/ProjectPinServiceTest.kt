package dev.ccpocket.daemon.pins

import dev.ccpocket.protocol.PROJECT_PINS_MAX
import dev.ccpocket.protocol.PROJECT_PINS_MAX_OPS
import dev.ccpocket.protocol.PROJECT_PIN_PATH_MAX_CHARS
import dev.ccpocket.protocol.ProjectPinErrors
import dev.ccpocket.protocol.ProjectPinOp
import dev.ccpocket.protocol.ProjectPinResolution
import dev.ccpocket.protocol.ProjectPinsSnapshot
import dev.ccpocket.protocol.SyncProjectPins
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The daemon half of project-pin sync (issue #362), against temp files only: SET semantics, per-(device,
 * stream) deduplication, commit-order conflicts, all-or-nothing batches, the incarnation fence, durability across
 * a restart, canonical aliases resolved afresh, bounded refusals, storage failures that never read as success —
 * split by whether the file could already have been replaced — and the fan-out's isolation.
 */
class ProjectPinServiceTest {

    private val dir: File = createTempDirectory("ccp-pins").toFile()
    private val storeFile = File(dir, "project-pins.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private val inc = "inc-0123456789abcdef"

    private fun service(
        store: ProjectPinStore = FileProjectPinStore(storeFile),
        keyOf: (String) -> String = ::canonicalPinKey,
        deviceStillPaired: (String) -> Boolean = { true },
        maxCursors: Int = ProjectPinService.MAX_CURSORS,
        deliveryTimeoutMs: Long = ProjectPinService.DELIVERY_TIMEOUT_MS,
        newIncarnation: () -> String = { inc },
    ) = ProjectPinService(store, scope, keyOf, deviceStillPaired, maxCursors, deliveryTimeoutMs, newIncarnation)

    private val sub = "sub-0123456789abcdef"
    private val streamA = "streamA-0123456789"
    private val streamB = "streamB-0123456789"
    private var requests = 0

    /** A request queued against the store incarnation every [service] here mints. */
    private fun req(stream: String, vararg ops: ProjectPinOp) = SyncProjectPins("r${requests++}", sub, stream, ops.toList(), expectedIncarnation = inc)
    private fun pin(seq: Long, path: String) = ProjectPinOp(seq, path, pinned = true)
    private fun unpin(seq: Long, path: String) = ProjectPinOp(seq, path, pinned = false)

    /** Paths that do not exist: canonical identity degrades to string normalization, touching no real file. */
    private fun p(name: String) = "/nonexistent-ccp-pins/$name"

    /** The wire identity the daemon reports for [path] as this machine resolves it now. */
    private fun wireKeyOf(path: Any) = ProjectPinService.wireKey(canonicalPinKey(path.toString()))

    private fun realMove(source: Path, target: Path) {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun ProjectPinService.Outcome.paths() = assertNotNull(reply.snapshot, "expected a snapshot: $reply").pins.map { it.path }

    private suspend fun ProjectPinService.fetch(device: String, stream: String = streamA) = sync(device, req(stream))

    @Test
    fun two_clients_offline_additions_both_survive_newest_commit_first() = runBlocking {
        val svc = service()
        val a = svc.sync("devA", req(streamA, pin(1, p("a"))))
        val b = svc.sync("devB", req(streamB, pin(1, p("b"))))
        assertNull(a.reply.error); assertNull(b.reply.error)
        assertEquals(1, a.reply.ackSeq); assertEquals(1, b.reply.ackSeq)
        assertEquals(listOf(p("b"), p("a")), b.paths())
        assertEquals(2, b.reply.snapshot?.revision)
        assertNotNull(b.changed, "a list change is offered to the other subscribers")
        assertEquals(listOf(ProjectPinResolution(1, p("a"), wireKeyOf(p("a")))), a.reply.resolutions)
    }

    @Test
    fun same_project_conflicts_resolve_by_daemon_commit_order_in_both_orders() = runBlocking {
        val first = service(store = FileProjectPinStore(File(dir, "one.json")))
        first.sync("devA", req(streamA, pin(1, p("x"))))
        assertEquals(emptyList(), first.sync("devB", req(streamB, unpin(1, p("x")))).paths())

        val second = service(store = FileProjectPinStore(File(dir, "two.json")))
        second.sync("devB", req(streamB, unpin(1, p("x"))))
        assertEquals(listOf(p("x")), second.sync("devA", req(streamA, pin(1, p("x")))).paths())
    }

    @Test
    fun a_replayed_acknowledged_pin_cannot_resurrect_after_a_sibling_unpins_it() = runBlocking {
        val svc = service()
        assertEquals(1, svc.sync("devA", req(streamA, pin(1, p("x")))).reply.ackSeq)
        svc.sync("devB", req(streamB, unpin(1, p("x"))))

        val replay = svc.sync("devA", req(streamA, pin(1, p("x"))))
        assertNull(replay.reply.error)
        assertEquals(1, replay.reply.ackSeq, "the replay is acknowledged as already committed")
        assertEquals(emptyList(), replay.paths(), "…and does not pin the project again")
        assertNull(replay.changed)
        assertEquals(listOf(ProjectPinResolution(1, p("x"), wireKeyOf(p("x")))), replay.reply.resolutions, "a duplicate still resolves its path")
    }

    @Test
    fun a_new_offline_pin_committed_after_the_unpin_wins() = runBlocking {
        val svc = service()
        svc.sync("devA", req(streamA, pin(1, p("x"))))
        svc.sync("devB", req(streamB, unpin(1, p("x"))))
        val later = svc.sync("devA", req(streamA, pin(1, p("x")), pin(2, p("x"))))
        assertEquals(2, later.reply.ackSeq)
        assertEquals(listOf(p("x")), later.paths())
    }

    @Test
    fun pinning_an_already_pinned_project_is_idempotent_and_does_not_reorder() = runBlocking {
        val svc = service()
        svc.sync("devA", req(streamA, pin(1, p("a")), pin(2, p("b"))))
        val again = svc.sync("devB", req(streamB, pin(1, p("a"))))
        assertEquals(listOf(p("b"), p("a")), again.paths())
        assertEquals(1, again.reply.snapshot?.revision, "no list change, no revision bump")
        assertNull(again.changed)
        val repinned = svc.sync("devB", req(streamB, unpin(2, p("a")), pin(3, p("a"))))
        assertEquals(listOf(p("a"), p("b")), repinned.paths(), "an explicit unpin then pin moves it to the top")
    }

    @Test
    fun sequence_gaps_and_out_of_order_batches_commit_nothing() = runBlocking {
        val svc = service()
        val gap = svc.sync("devA", req(streamA, pin(2, p("a"))))
        assertEquals(ProjectPinErrors.SEQUENCE_GAP, gap.reply.error)
        assertEquals(0, gap.reply.ackSeq)
        assertEquals(emptyList(), gap.paths(), "a readable store still reports its authoritative list")
        assertNull(gap.reply.resolutions, "a refusal resolves nothing")

        assertEquals(ProjectPinErrors.INVALID_REQUEST, svc.sync("devA", req(streamA, pin(1, p("a")), pin(3, p("b")))).reply.error)
        assertEquals(ProjectPinErrors.INVALID_REQUEST, svc.sync("devA", req(streamA, pin(2, p("a")), pin(1, p("b")))).reply.error)
        assertEquals(ProjectPinErrors.INVALID_REQUEST, svc.sync("devA", req(streamA, pin(0, p("a")))).reply.error)

        assertEquals(2, svc.sync("devA", req(streamA, pin(1, p("a")), pin(2, p("b")))).reply.ackSeq, "nothing above was committed")
        val overlapping = svc.sync("devA", req(streamA, pin(2, p("b")), pin(3, p("c")), pin(4, p("d"))))
        assertEquals(4, overlapping.reply.ackSeq, "an overlapping batch deduplicates its old prefix")
        assertEquals(listOf(p("d"), p("c"), p("b"), p("a")), overlapping.paths())
        assertEquals(listOf(2L, 3L, 4L), overlapping.reply.resolutions?.map { it.seq }, "one row per submitted operation, in order")
        assertEquals(ProjectPinErrors.SEQUENCE_GAP, svc.sync("devA", req(streamA, pin(6, p("e")))).reply.error)
    }

    @Test
    fun sequence_numbers_cannot_wrap_and_an_exhausted_revision_refuses_changes() = runBlocking {
        val svc = service()
        val wrapping = svc.sync("devA", req(streamA, pin(Long.MAX_VALUE, p("a")), pin(Long.MIN_VALUE, p("b"))))
        assertEquals(ProjectPinErrors.INVALID_REQUEST, wrapping.reply.error, "Long.MAX_VALUE + 1 is not a successor")
        assertEquals(0, svc.fetch("devA").reply.ackSeq)

        val exhausted = service(store = MemoryProjectPinStore(PinStoreState(incarnation = inc, revision = Long.MAX_VALUE)))
        val refused = exhausted.sync("devA", req(streamA, pin(1, p("a"))))
        assertEquals(ProjectPinErrors.STORE_UNAVAILABLE, refused.reply.error)
        assertEquals(0, refused.reply.ackSeq, "nothing of the batch was acknowledged")
        assertNull(refused.reply.resolutions)
        assertNull(refused.changed)
        val unchanged = exhausted.fetch("devA")
        assertNull(unchanged.reply.error, "a request that changes nothing still works")
        assertEquals(Long.MAX_VALUE, unchanged.reply.snapshot?.revision)
        assertEquals(emptyList(), unchanged.paths())
        assertEquals(0, unchanged.reply.ackSeq, "…and the refused batch left no cursor behind")
    }

    @Test
    fun one_invalid_operation_refuses_the_whole_batch() = runBlocking {
        val svc = service()
        val refused = svc.sync("devA", req(streamA, pin(1, p("a")), pin(2, "/bad path")))
        assertEquals(ProjectPinErrors.INVALID_PATH, refused.reply.error)
        val accepted = svc.sync("devA", req(streamA, pin(1, p("a"))))
        assertEquals(1, accepted.reply.ackSeq, "seq 1 was not consumed by the refused batch")
        assertEquals(listOf(p("a")), accepted.paths())
    }

    @Test
    fun a_batch_from_another_incarnation_is_refused_whole_and_a_fetch_needs_none() = runBlocking {
        val before = service().fetch("devA").reply.snapshot!!.incarnation

        // the store is reset (moved aside, daemon restarted) while a seq-1 batch queued against the old one is delayed
        storeFile.delete()
        val reset = service(newIncarnation = { "inc-reset-0123456789" })
        val delayed = reset.sync("devA", SyncProjectPins("r-late", sub, streamA, listOf(pin(1, p("a"))), expectedIncarnation = before))
        assertEquals(ProjectPinErrors.INCARNATION_MISMATCH, delayed.reply.error)
        assertEquals("r-late", delayed.reply.requestId)
        assertEquals(streamA, delayed.reply.streamId)
        assertEquals("inc-reset-0123456789", delayed.reply.snapshot?.incarnation, "the refusal shows the store as it is now")
        assertNull(delayed.reply.ackSeq, "no cursor of the new store speaks for that batch")
        assertNull(delayed.reply.resolutions)
        assertNull(delayed.changed)
        val unnamed = reset.sync("devA", SyncProjectPins("r-none", sub, streamA, listOf(pin(1, p("a")))))
        assertEquals(ProjectPinErrors.INCARNATION_MISMATCH, unnamed.reply.error, "a batch naming no incarnation is never guessed into one")

        val fetched = reset.sync("devA", SyncProjectPins("r-fetch", sub, streamA))
        assertNull(fetched.reply.error, "a fetch may omit the incarnation")
        assertEquals(0, fetched.reply.ackSeq, "neither refusal created a cursor…")
        assertEquals(emptyList(), fetched.paths(), "…or a pin")
        val fresh = reset.sync("devA", SyncProjectPins("r-new", sub, streamA, listOf(pin(1, p("b"))), expectedIncarnation = "inc-reset-0123456789"))
        assertEquals(1, fresh.reply.ackSeq, "an explicit new action under the new incarnation starts its stream over")
        assertEquals(listOf(p("b")), fresh.paths())
    }

    @Test
    fun a_request_the_transport_no_longer_admits_reads_and_changes_nothing() = runBlocking {
        val svc = service()
        val stale = svc.sync("devA", req(streamA, pin(1, p("a")))) { false }
        assertEquals(ProjectPinErrors.SUBSCRIPTION_STALE, stale.reply.error)
        assertNull(stale.reply.snapshot)
        assertNull(stale.changed)
        assertFalse(storeFile.exists(), "refused before the store was even read, let alone created")
        assertEquals(0, svc.fetch("devA").reply.ackSeq)
    }

    @Test
    fun a_restart_keeps_list_revision_incarnation_and_every_cursor() = runBlocking {
        val before = service()
        before.sync("devA", req(streamA, pin(1, p("a")), pin(2, p("b"))))
        val committed = before.sync("devB", req(streamB, unpin(1, p("a")))).reply.snapshot!!

        val after = service() // same file, fresh process state
        val fetched = after.fetch("devA")
        assertEquals(committed, fetched.reply.snapshot)
        assertEquals(2, fetched.reply.ackSeq, "the cursor survived the restart")
        assertNull(fetched.reply.resolutions, "a fetch resolves no paths")
        val replay = after.sync("devA", req(streamA, pin(1, p("a")), pin(2, p("b"))))
        assertEquals(listOf(p("b")), replay.paths(), "a replay after restart is still deduplicated")
        assertNull(replay.changed)
    }

    @Test
    fun a_missing_store_is_created_once_so_the_incarnation_never_flaps() = runBlocking {
        var minted = 0
        val mint = { "inc-${++minted}-0123456789" }
        val first = service(newIncarnation = mint).fetch("devA").reply.snapshot!!
        assertTrue(storeFile.exists(), "the fresh store is persisted before it is reported")
        val second = service(newIncarnation = mint).fetch("devB").reply.snapshot!!
        assertEquals(first.incarnation, second.incarnation)
        assertEquals(1, minted, "the second instance read the persisted incarnation instead of minting one")
        assertEquals(0, second.revision)
    }

    @Test
    fun spellings_of_one_directory_are_one_pin_and_either_spelling_unpins_it() = runBlocking {
        val real = Files.createDirectories(dir.toPath().resolve("real-project"))
        val link = Files.createSymbolicLink(dir.toPath().resolve("link-project"), real)
        val svc = service()
        svc.sync("devA", req(streamA, pin(1, real.toString())))
        val viaLink = svc.sync("devB", req(streamB, pin(1, link.toString())))
        assertEquals(listOf(real.toString()), viaLink.paths(), "the alias is the same project: kept once, display path preserved")
        assertEquals(1, viaLink.reply.snapshot?.revision)
        assertEquals(viaLink.reply.snapshot!!.pins.single().key, viaLink.reply.resolutions!!.single().key, "the alias resolves to that row")
        assertEquals(emptyList(), svc.sync("devB", req(streamB, unpin(2, link.toString()))).paths())

        // the daemon, not the client, expands `~`
        val home = System.getProperty("user.home")
        svc.sync("devA", req(streamA, pin(2, "~/nonexistent-ccp-pins-home")))
        assertEquals(listOf("~/nonexistent-ccp-pins-home"), svc.sync("devB", req(streamB, pin(3, "$home/nonexistent-ccp-pins-home"))).paths())
    }

    @Test
    fun resolutions_report_how_each_requested_path_resolves_now_even_for_a_duplicate_replay(): Unit = runBlocking {
        val first = Files.createDirectories(dir.toPath().resolve("first"))
        val second = Files.createDirectories(dir.toPath().resolve("second"))
        val link = Files.createSymbolicLink(dir.toPath().resolve("current"), first)
        val svc = service()
        val committed = svc.sync("devA", req(streamA, pin(1, link.toString()), pin(2, p("plain"))))
        assertEquals(
            listOf(ProjectPinResolution(1, link.toString(), wireKeyOf(first)), ProjectPinResolution(2, p("plain"), wireKeyOf(p("plain")))),
            committed.reply.resolutions,
        )

        Files.delete(link)
        Files.createSymbolicLink(link, second) // retargeted after the commit — the process-wide path memo must not answer
        val replay = svc.sync("devA", req(streamA, pin(1, link.toString())))
        assertNull(replay.reply.error)
        assertEquals(2, replay.reply.ackSeq, "a pure duplicate")
        assertEquals(
            listOf(ProjectPinResolution(1, link.toString(), wireKeyOf(second))), replay.reply.resolutions,
            "today's resolution of the requested path, never a claim about what the old commit resolved to",
        )
        assertEquals(wireKeyOf(second), replay.reply.snapshot!!.pins.single { it.path == link.toString() }.key, "the stored row follows too")
        assertNotNull(replay.changed, "the re-resolved row is itself a durable change")
    }

    @Test
    fun an_alias_change_collapses_rows_and_queued_intent_on_either_spelling_still_applies() = runBlocking {
        val keys = mutableMapOf(p("one") to "k1", p("two") to "k2")
        val svc = service(keyOf = { keys[it] ?: it })
        svc.sync("devA", req(streamA, pin(1, p("one")), pin(2, p("two"))))
        keys[p("one")] = "k2" // the machine now resolves both spellings to one directory

        val reconciled = svc.fetch("devB", streamB)
        assertEquals(listOf(p("two")), reconciled.paths(), "one visible project, the newest row's display path kept")
        assertEquals(2, reconciled.reply.snapshot?.revision)
        assertNotNull(reconciled.changed, "the collapse itself is broadcast")
        assertEquals(emptyList(), svc.sync("devA", req(streamA, unpin(3, p("one")))).paths(), "an unpin on the other spelling removes it")
    }

    @Test
    fun a_relative_path_never_resolves_against_the_daemon_working_directory() {
        val cwd = System.getProperty("user.dir")
        assertTrue(canonicalPinKey("some-project").startsWith("rel:"))
        assertNotEquals(canonicalPinKey("$cwd/some-project"), canonicalPinKey("some-project"))
    }

    @Test
    fun malformed_or_oversized_requests_are_refused_explicitly() = runBlocking {
        val svc = service()
        val tooMany = (1..PROJECT_PINS_MAX_OPS + 1).map { pin(it.toLong(), p("n$it")) }.toTypedArray()
        assertEquals(ProjectPinErrors.INVALID_REQUEST, svc.sync("devA", req(streamA, *tooMany)).reply.error)
        assertEquals(ProjectPinErrors.INVALID_PATH, svc.sync("devA", req(streamA, pin(1, "/" + "x".repeat(PROJECT_PIN_PATH_MAX_CHARS)))).reply.error)
        assertEquals(ProjectPinErrors.INVALID_PATH, svc.sync("devA", req(streamA, pin(1, "  "))).reply.error)
        assertEquals(ProjectPinErrors.INVALID_REQUEST, svc.sync("devA", req("short", pin(1, p("a")))).reply.error)
        val badSub = svc.sync("devA", SyncProjectPins("r", "no", streamA, emptyList()))
        assertEquals(ProjectPinErrors.INVALID_REQUEST, badSub.reply.error)
        assertEquals("", badSub.reply.subscriptionId, "an invalid identifier is never echoed back")
        assertEquals(ProjectPinErrors.INVALID_REQUEST, svc.sync(" ", req(streamA)).reply.error)
        assertEquals(emptyList(), svc.fetch("devA").paths(), "none of the refusals committed anything")
    }

    @Test
    fun capacity_is_judged_on_the_result_of_the_whole_batch() = runBlocking {
        val svc = service()
        val first = (1..PROJECT_PINS_MAX_OPS).map { pin(it.toLong(), p("n$it")) }.toTypedArray()
        val rest = (PROJECT_PINS_MAX_OPS + 1..PROJECT_PINS_MAX).map { pin(it.toLong(), p("n$it")) }.toTypedArray()
        svc.sync("devA", req(streamA, *first))
        assertEquals(PROJECT_PINS_MAX.toLong(), svc.sync("devA", req(streamA, *rest)).reply.ackSeq)

        val over = svc.sync("devA", req(streamA, pin(PROJECT_PINS_MAX + 1L, p("extra"))))
        assertEquals(ProjectPinErrors.CAPACITY, over.reply.error)
        assertEquals(PROJECT_PINS_MAX.toLong(), over.reply.ackSeq, "the refusal still reports what IS committed")

        val swap = svc.sync("devA", req(streamA, pin(PROJECT_PINS_MAX + 1L, p("extra")), unpin(PROJECT_PINS_MAX + 2L, p("n1"))))
        assertNull(swap.reply.error, "a batch that ends within the limit is accepted")
        assertEquals(PROJECT_PINS_MAX, swap.paths().size)
        assertEquals(p("extra"), swap.paths().first())
    }

    @Test
    fun a_write_that_fails_before_the_rename_acknowledges_nothing_and_stays_retryable() = runBlocking {
        var failMoves = false
        val files = DurablePinFiles(atomicMove = { source, target -> if (failMoves) throw IOException("refused") else realMove(source, target) })
        val svc = service(store = FileProjectPinStore(storeFile, files))
        val incarnation = svc.fetch("devA").reply.snapshot!!.incarnation

        failMoves = true
        val failed = svc.sync("devA", req(streamA, pin(1, p("a"))))
        assertEquals(ProjectPinErrors.STORE_UNAVAILABLE, failed.reply.error)
        assertNull(failed.reply.snapshot, "a storage failure never looks like a list")
        assertNull(failed.reply.ackSeq)
        assertNull(failed.reply.resolutions)
        assertNull(failed.changed, "nothing is broadcast")

        failMoves = false
        val fetched = svc.fetch("devA")
        assertEquals(emptyList(), fetched.paths())
        assertEquals(0, fetched.reply.ackSeq)
        assertEquals(incarnation, fetched.reply.snapshot?.incarnation)
        assertEquals(1, svc.sync("devA", req(streamA, pin(1, p("a")))).reply.ackSeq, "seq 1 is still free")
        assertEquals(listOf(p("a")), service().fetch("devB", streamB).paths(), "…and now durable")
    }

    @Test
    fun a_save_whose_outcome_is_unknown_blocks_this_instance_and_a_restart_reads_what_landed() = runBlocking {
        var failDirectorySync = false
        var moves = 0
        val files = DurablePinFiles(
            atomicMove = { source, target -> moves++; realMove(source, target) },
            forceDirectory = { if (failDirectorySync) throw IOException("directory fsync failed") },
        )
        val svc = service(store = FileProjectPinStore(storeFile, files))
        assertNull(svc.sync("devA", req(streamA, pin(1, p("a")))).reply.error)

        failDirectorySync = true
        val unknown = svc.sync("devA", req(streamA, pin(2, p("b"))))
        assertEquals(ProjectPinErrors.STORE_UNAVAILABLE, unknown.reply.error)
        assertNull(unknown.reply.snapshot)
        assertNull(unknown.reply.ackSeq, "never acknowledged as durable")
        assertNull(unknown.reply.resolutions)
        assertNull(unknown.changed)
        val landed = storeFile.readBytes() // renamed into place: the file may well hold the unacknowledged commit

        failDirectorySync = false
        val movesBefore = moves
        listOf(svc.fetch("devA"), svc.sync("devA", req(streamA, pin(2, p("b")))), svc.sync("devB", req(streamB, pin(1, p("c"))))).forEach {
            assertEquals(ProjectPinErrors.STORE_UNAVAILABLE, it.reply.error, "blocked for the rest of this instance")
            assertNull(it.reply.snapshot)
            assertNull(it.reply.ackSeq)
        }
        assertEquals(movesBefore, moves, "a blocked instance never replaces the file again")
        assertContentEquals(landed, storeFile.readBytes())

        val restarted = service() // a fresh instance reads the whole file, never a stale cache
        val fetched = restarted.fetch("devA")
        assertEquals(listOf(p("b"), p("a")), fetched.paths())
        assertEquals(2, fetched.reply.ackSeq, "…with the cursor that landed in the same document")
    }

    @Test
    fun a_store_that_cannot_be_created_is_unavailable_never_empty() = runBlocking {
        val svc = service(store = FileProjectPinStore(storeFile, DurablePinFiles(atomicMove = { _, _ -> throw IOException("refused") })))
        val reply = svc.fetch("devA").reply
        assertEquals(ProjectPinErrors.STORE_UNAVAILABLE, reply.error)
        assertNull(reply.snapshot)
        assertFalse(storeFile.exists())
    }

    @Test
    fun a_corrupt_or_future_store_is_refused_and_left_byte_for_byte_untouched() = runBlocking {
        val garbage = "{\"v\":1,\"incarnation\":".encodeToByteArray()
        storeFile.writeBytes(garbage)
        val svc = service()
        listOf(svc.fetch("devA"), svc.sync("devA", req(streamA, pin(1, p("a"))))).forEach {
            assertEquals(ProjectPinErrors.STORE_CORRUPT, it.reply.error)
            assertNull(it.reply.snapshot)
        }
        assertContentEquals(garbage, storeFile.readBytes())

        val future = """{"v":2,"incarnation":"abc","revision":3,"pins":[],"cursors":[]}"""
        storeFile.writeText(future)
        assertEquals(ProjectPinErrors.STORE_CORRUPT, service().fetch("devA").reply.error, "a newer daemon's file is not ours to rewrite")
        assertEquals(future, storeFile.readText())

        val duplicate = """{"v":1,"incarnation":"abc","revision":1,"pins":[{"path":"/a","key":"k"},{"path":"/b","key":"k"}],"cursors":[]}"""
        storeFile.writeText(duplicate)
        assertEquals(ProjectPinErrors.STORE_CORRUPT, service().fetch("devA").reply.error)

        storeFile.delete() // the owner moved it aside: the next request starts a fresh store without a restart
        assertNull(svc.fetch("devA").reply.error)
    }

    @Test
    fun a_device_naming_another_devices_stream_only_touches_its_own_partition() = runBlocking {
        val svc = service()
        svc.sync("devA", req(streamA, pin(1, p("a"))))
        val forged = svc.sync("devB", req(streamA, pin(1, p("x")), pin(2, p("y"))))
        assertEquals(2, forged.reply.ackSeq, "devB's own cursor for that stream name, not devA's")
        // devA's next real operation is NOT swallowed as a duplicate of devB's seq 2
        val genuine = svc.sync("devA", req(streamA, unpin(2, p("a"))))
        assertEquals(2, genuine.reply.ackSeq)
        assertEquals(listOf(p("y"), p("x")), genuine.paths())
        assertEquals(0, svc.fetch("devA", streamB).reply.ackSeq, "devA has no cursor for a stream name it never used")
    }

    @Test
    fun stream_capacity_reclaims_only_unpaired_devices_and_otherwise_refuses() = runBlocking {
        val paired = mutableSetOf("devA", "devB", "devC", "devD")
        val svc = service(deviceStillPaired = { it in paired }, maxCursors = 2)
        svc.sync("devA", req(streamA, pin(1, p("a"))))
        svc.sync("devB", req(streamB, pin(1, p("b"))))

        paired -= "devA" // revoked: its cursor can never be used again
        assertNull(svc.sync("devC", req("streamC-0123456789", pin(1, p("c")))).reply.error)

        val refused = svc.sync("devD", req("streamD-0123456789", pin(1, p("d"))))
        assertEquals(ProjectPinErrors.STREAM_CAPACITY, refused.reply.error, "a live device's dedup state is never evicted")
        assertEquals(2, svc.sync("devB", req(streamB, pin(2, p("b2")))).reply.ackSeq, "existing streams keep working")
    }

    // ---- fan-out ----

    private fun snap(rev: Long) = ProjectPinsSnapshot("inc", rev, emptyList())

    @Test
    fun subscribers_receive_offers_and_the_requester_is_excluded() = runBlocking {
        val svc = service()
        val a = Channel<ProjectPinsSnapshot>(Channel.UNLIMITED)
        val b = Channel<ProjectPinsSnapshot>(Channel.UNLIMITED)
        svc.attach("A") { a.send(it) }
        svc.attach("B") { b.send(it) }
        svc.broadcast(snap(1), exceptKey = "A")
        assertEquals(1, withTimeout(5_000) { b.receive() }.revision)
        assertNull(withTimeoutOrNull(200) { a.receive() })

        svc.detach("B")
        svc.broadcast(snap(2))
        assertNull(withTimeoutOrNull(200) { b.receive() }, "a detached subscriber receives nothing more")
        assertEquals(2, withTimeout(5_000) { a.receive() }.revision)
    }

    @Test
    fun a_stuck_subscriber_delays_only_itself_and_never_a_commit() = runBlocking {
        val svc = service(deliveryTimeoutMs = 300)
        val fast = Channel<ProjectPinsSnapshot>(Channel.UNLIMITED)
        svc.attach("stuck") { awaitCancellation() }
        svc.attach("fast") { fast.send(it) }

        val committed = withTimeout(5_000) { svc.sync("devA", req(streamA, pin(1, p("a")))) }
        svc.broadcast(committed.changed!!)
        assertEquals(1, withTimeout(5_000) { fast.receive() }.revision)
        val next = withTimeout(5_000) { svc.sync("devA", req(streamA, pin(2, p("b")))) }
        svc.broadcast(next.changed!!)
        assertEquals(2, withTimeout(5_000) { fast.receive() }.revision)
    }

    @Test
    fun a_backlog_conflates_to_the_newest_snapshot() = runBlocking {
        val svc = service()
        val gate = CompletableDeferred<Unit>()
        val seen = Channel<Long>(Channel.UNLIMITED)
        svc.attach("slow") { seen.send(it.revision); gate.await() }
        svc.broadcast(snap(1))
        assertEquals(1, withTimeout(5_000) { seen.receive() })
        (2L..5L).forEach { svc.broadcast(snap(it)) }
        svc.broadcast(snap(3)) // an older offer landing late never replaces a newer one
        gate.complete(Unit)
        assertEquals(5, withTimeout(5_000) { seen.receive() })
        assertNull(withTimeoutOrNull(300) { seen.receive() }, "revisions 2-4 were superseded, not queued")
    }

    // ---- the durable-write primitive ----

    @Test
    fun a_write_into_an_unwritable_directory_fails_explicitly_and_leaves_no_temp_file() {
        val locked = Files.createDirectories(dir.toPath().resolve("locked")).toFile()
        val target = File(locked, "project-pins.json")
        val files = DurablePinFiles()
        assertEquals(PinStoreWrite.Durable, files.replace(target, "one".encodeToByteArray()))
        Files.setPosixFilePermissions(locked.toPath(), PosixFilePermissions.fromString("r-x------"))
        try {
            assertEquals(PinStoreWrite.UnchangedFailure, files.replace(target, "two".encodeToByteArray()))
            assertEquals("one", target.readText(), "the previous document is intact")
        } finally {
            Files.setPosixFilePermissions(locked.toPath(), PosixFilePermissions.fromString("rwx------"))
        }
        assertEquals(listOf("project-pins.json"), locked.list()!!.toList(), "no temp file left behind")
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(target.toPath())))
    }

    @Test
    fun a_failure_before_the_rename_is_unchanged_and_one_after_it_is_indeterminate() {
        val target = File(dir, "phases.json").apply { writeText("one") }

        val refusedMove = DurablePinFiles(atomicMove = { _, _ -> throw IOException("refused") })
        assertEquals(PinStoreWrite.UnchangedFailure, refusedMove.replace(target, "two".encodeToByteArray()))
        assertEquals("one", target.readText())

        val failedFileSync = DurablePinFiles(forceFile = { throw IOException("fsync failed") })
        assertEquals(PinStoreWrite.UnchangedFailure, failedFileSync.replace(target, "two".encodeToByteArray()))
        assertEquals("one", target.readText())

        val failedDirectorySync = DurablePinFiles(forceDirectory = { throw IOException("directory fsync failed") })
        assertEquals(PinStoreWrite.IndeterminateFailure, failedDirectorySync.replace(target, "two".encodeToByteArray()))
        assertEquals("two", target.readText(), "renamed into place, and never rolled back over the target")

        val ambiguousMove = DurablePinFiles(atomicMove = { source, dest -> realMove(source, dest); throw IOException("reply lost") })
        assertEquals(PinStoreWrite.IndeterminateFailure, ambiguousMove.replace(target, "three".encodeToByteArray()))
        assertEquals("three", target.readText())

        assertEquals(listOf("phases.json"), dir.list()!!.filter { "phases" in it }, "no attempt left a temp file behind")
    }

    @Test
    fun a_directory_created_for_the_store_is_synced_into_its_parent_or_not_kept() {
        val synced = mutableListOf<Path>()
        val recording = DurablePinFiles(forceDirectory = { synced.add(it) }) // Path is itself Iterable<Path>: no `+=`
        assertEquals(PinStoreWrite.Durable, recording.replace(File(dir, "a/b/project-pins.json"), "x".encodeToByteArray()))
        val root = dir.toPath().toAbsolutePath()
        assertEquals(listOf(root, root.resolve("a"), root.resolve("a/b")), synced, "each new entry's parent, then the file's own directory")

        val failing = DurablePinFiles(forceDirectory = { throw IOException("directory fsync failed") })
        assertEquals(PinStoreWrite.UnchangedFailure, failing.replace(File(dir, "c/project-pins.json"), "y".encodeToByteArray()))
        assertFalse(File(dir, "c").exists(), "a directory whose entry was not synced is removed, so a retry syncs it again")
    }
}
