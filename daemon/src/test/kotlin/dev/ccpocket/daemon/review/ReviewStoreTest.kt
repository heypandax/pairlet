package dev.ccpocket.daemon.review

import dev.ccpocket.protocol.ReviewRequest
import dev.ccpocket.protocol.ReviewStatus
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ReviewStore] + [ReviewFiles]: crash-safe roundtrip, owner-only permissions, corruption that fails
 * closed WITHOUT destroying evidence, and the two deliberately different bounds (terminal history is
 * pruned; the active set refuses instead).
 */
class ReviewStoreTest {

    private val tmp = Files.createTempDirectory("ccp-review-store").toFile()

    private fun row(id: String, status: ReviewStatus = ReviewStatus.QUEUED, createdAt: Long = 0) = ReviewRequest(
        id = id, recipientDeviceId = "devB", title = "t", status = status, revision = 1, createdAt = createdAt,
    )

    @Test
    fun roundtrips_through_a_fresh_load() {
        val path = tmp.resolve("rt.json")
        ReviewStore.load(path).apply {
            put(row("rr_1"))
            put(row("rr_2", ReviewStatus.CLOSED), key = "k1")
        }
        val reloaded = ReviewStore.load(path)
        assertEquals(setOf("rr_1", "rr_2"), reloaded.all().mapTo(HashSet()) { it.id })
        assertTrue(reloaded.wasApplied("rr_2", "k1"), "an idempotency key must survive a restart")
        assertFalse(reloaded.wasApplied("rr_2", "k2"))
        assertFalse(reloaded.wasApplied("rr_2", ""), "a blank key is never 'already applied'")
    }

    @Test
    fun the_file_is_owner_only_and_leaves_no_temp_behind() {
        val path = tmp.resolve("perms.json")
        ReviewStore.load(path).put(row("rr_1"))
        val perms = runCatching { Files.getPosixFilePermissions(path.toPath()) }.getOrNull()
        if (perms != null) { // POSIX only; Windows ACLs inherit the profile dir
            assertEquals(PosixFilePermissions.fromString("rw-------"), perms)
        }
        assertTrue(tmp.listFiles().orEmpty().none { it.name.startsWith(".perms.json.") && it.name.endsWith(".tmp") })
    }

    /** A torn/garbage file must never become authoritative state — and must never be silently
     *  overwritten either, or the requests in it are gone for good. */
    @Test
    fun a_corrupt_file_is_quarantined_and_the_store_starts_empty() {
        val path = tmp.resolve("corrupt.json")
        path.writeText("""{"v":1,"requests":[{"id":"rr_1",""") // truncated mid-write
        val store = ReviewStore.load(path)
        assertTrue(store.all().isEmpty(), "undecodable content must not be half-loaded")
        val quarantine = tmp.resolve("corrupt.json.corrupt")
        assertTrue(quarantine.exists(), "the unreadable bytes must be kept for recovery")
        assertTrue(quarantine.readText().startsWith("""{"v":1"""))
        // and the store is usable from here on
        store.put(row("rr_new"))
        assertEquals(listOf("rr_new"), ReviewStore.load(path).all().map { it.id })
    }

    @Test
    fun terminal_history_is_pruned_oldest_first_and_active_rows_never_are() {
        val path = tmp.resolve("prune.json")
        val store = ReviewStore.load(path)
        // one more terminal row than the cap, plus a live one that must survive regardless
        repeat(ReviewStore.MAX_HISTORY + 5) { i -> store.put(row("closed_$i", ReviewStatus.CLOSED, createdAt = i.toLong())) }
        store.put(row("live", ReviewStatus.DELIVERED, createdAt = 1))
        store.pruneHistory()
        val kept = store.all().map { it.id }
        assertEquals(ReviewStore.MAX_HISTORY + 1, kept.size)
        assertTrue("live" in kept, "a non-terminal row is never pruned, however old")
        assertFalse("closed_0" in kept, "the OLDEST terminal row goes first")
        assertTrue("closed_${ReviewStore.MAX_HISTORY + 4}" in kept, "the newest terminal rows stay")
    }

    @Test
    fun pruning_drops_the_idempotency_keys_of_pruned_rows_only() {
        val path = tmp.resolve("keys.json")
        val store = ReviewStore.load(path)
        repeat(ReviewStore.MAX_HISTORY + 1) { i ->
            store.put(row("closed_$i", ReviewStatus.CLOSED, createdAt = i.toLong()), key = "k$i")
        }
        store.pruneHistory()
        assertFalse(store.wasApplied("closed_0", "k0"), "a pruned row's keys go with it")
        assertTrue(store.wasApplied("closed_${ReviewStore.MAX_HISTORY}", "k${ReviewStore.MAX_HISTORY}"))
    }

    @Test
    fun activeCount_counts_only_non_terminal_rows() {
        val store = ReviewStore.load(tmp.resolve("active.json"))
        store.put(row("a", ReviewStatus.QUEUED))
        store.put(row("b", ReviewStatus.RESPONDED)) // NOT terminal: the sender still has to close it
        store.put(row("c", ReviewStatus.CANCELLED))
        assertEquals(2, store.activeCount())
    }

    @Test
    fun replaceAll_keeps_only_the_surviving_rows_keys() {
        val store = ReviewStore.load(tmp.resolve("replace.json"))
        store.put(row("a"), key = "ka")
        store.put(row("b"), key = "kb")
        store.replaceAll(listOf(row("a")))
        assertNotNull(store.byId("a"))
        assertNull(store.byId("b"))
        assertTrue(store.wasApplied("a", "ka"))
        assertFalse(store.wasApplied("b", "kb"))
    }

    @Test
    fun a_failed_write_does_not_publish_the_prospective_state_in_memory() {
        val parentIsAFile = tmp.resolve("not-a-directory").apply { writeText("block writes below me") }
        val store = ReviewStore.load(parentIsAFile.resolve("reviews.json"))
        assertFalse(store.put(row("rr_never_durable")))
        assertNull(store.byId("rr_never_durable"), "a false-success row must not become authoritative in memory")
    }
}
