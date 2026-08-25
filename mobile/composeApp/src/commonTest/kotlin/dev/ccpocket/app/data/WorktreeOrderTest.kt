package dev.ccpocket.app.data

import dev.ccpocket.protocol.WorktreeEntry
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Display order for the worktree list (#294 真机反馈): a fresh checkout must surface, not sink. Main
 * stays the anchor on top; linked checkouts run newest-first; rows an older daemon (or a filesystem
 * without creation time) answered with createdAt=0 keep git's own registration order among themselves.
 */
class WorktreeOrderTest {

    private fun wt(path: String, createdAt: Long = 0, main: Boolean = false) =
        WorktreeEntry(path = path, isMain = main, createdAt = createdAt)

    @Test
    fun newest_linked_checkout_comes_first_and_main_stays_on_top() {
        val ordered = worktreeDisplayOrder(
            listOf(
                wt("/repo", createdAt = 100, main = true),
                wt("/wt/old", createdAt = 200),
                wt("/wt/new", createdAt = 900),
            ),
        )
        assertEquals(listOf("/repo", "/wt/new", "/wt/old"), ordered.map { it.path })
    }

    @Test
    fun rows_without_a_creation_time_keep_gits_registration_order() {
        // an old daemon answers every row 0 — the list must look exactly as it always did
        val asAnswered = listOf(wt("/repo", main = true), wt("/wt/a"), wt("/wt/b"), wt("/wt/c"))
        assertEquals(asAnswered, worktreeDisplayOrder(asAnswered))
    }

    @Test
    fun a_stamped_row_rises_above_unstamped_ones_without_reshuffling_them() {
        val ordered = worktreeDisplayOrder(
            listOf(wt("/repo", main = true), wt("/wt/a"), wt("/wt/b"), wt("/wt/fresh", createdAt = 500)),
        )
        assertEquals(listOf("/repo", "/wt/fresh", "/wt/a", "/wt/b"), ordered.map { it.path })
    }
}
