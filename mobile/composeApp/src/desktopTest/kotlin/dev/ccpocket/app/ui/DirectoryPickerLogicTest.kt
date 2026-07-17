package dev.ccpocket.app.ui

import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.protocol.DirectoryEntry
import dev.ccpocket.protocol.PathEntries
import dev.ccpocket.protocol.PathEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The "open a project folder" picker's pure logic (issue #152): dirs-only + dot-folder filtering, the
 * out-of-order listing guard, the '/'-joined subPath algebra, the raw-"~" OpenSession workdir the
 * daemon expands, badge/recents matching against the flat project list (both host separators), and
 * the guest-view detection that keeps the browser owner-only client-side.
 */
class DirectoryPickerLogicTest {

    private fun listing(subPath: String, vararg entries: PathEntry, ok: Boolean = true) =
        PathEntries(workdir = "~", subPath = subPath, entries = entries.toList(), ok = ok)

    private fun dir(path: String, name: String = path.substringAfterLast('/').substringAfterLast('\\'), sharedBy: String? = null) =
        DirectoryEntry(path = path, name = name, isDir = true, hasSessions = true, sharedBy = sharedBy)

    // ── rows ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun rows_keep_visible_directories_only() {
        val l = listing("", PathEntry("src", true), PathEntry(".git", true), PathEntry("README.md", false))
        assertEquals(listOf("src"), browseRows(l, "")!!.map { it.name })
    }

    @Test
    fun a_stale_or_foreign_listing_reads_as_loading() {
        val l = listing("src", PathEntry("main", true))
        assertNull(browseRows(l, ""), "a reply for another subPath must not render (out-of-order drilling)")
        assertNull(browseRows(null, ""), "no reply yet = loading")
        val atCompletion = PathEntries(workdir = "/Users/x/proj", subPath = "", entries = listOf(PathEntry("a", true)))
        assertNull(browseRows(atCompletion, ""), "an @-completion reply (real workdir) must never leak into the browser")
    }

    @Test
    fun an_out_of_order_stale_root_reply_must_not_clobber_the_fresh_child_listing() {
        // drill root→"src" fast enough that both requests are in flight and the CHILD's reply lands
        // first, the root's stale reply after. Folding the stale one in would strand the picker on the
        // skeleton forever: browseRows keys on subPath and no further request is pending to repair it.
        var held: PathEntries? = null
        val lastSub = "src" // what the repo recorded when it sent the latest request
        held = PocketRepository.foldBrowseReply(held, listing("src", PathEntry("main", true)), lastSub)
        assertEquals(listOf("main"), browseRows(held, "src")!!.map { it.name }, "fresh child reply renders")
        held = PocketRepository.foldBrowseReply(held, listing("", PathEntry("src", true)), lastSub)
        assertEquals("src", held?.subPath, "the late root reply must be dropped, not folded in")
        assertEquals(listOf("main"), browseRows(held, "src")!!.map { it.name }, "child rows survive the stale arrival")
    }

    @Test
    fun a_failed_listing_is_an_error_only_for_its_own_subPath() {
        assertTrue(browseFailed(listing("src", ok = false), "src"))
        assertFalse(browseFailed(listing("src", ok = false), ""), "an old failure must not mark the level we navigated back to")
        assertFalse(browseFailed(null, "src"))
    }

    // ── subPath algebra ───────────────────────────────────────────────────────────────────────────

    @Test
    fun join_parent_and_crumbs_agree() {
        assertEquals("src", browseJoin("", "src"))
        assertEquals("src/app", browseJoin("src", "app"))
        assertEquals("src", browseParentOf("src/app"))
        assertEquals("", browseParentOf("src"))
        assertEquals("", browseParentOf(""))
        assertEquals(listOf("~"), browseCrumbsOf(""))
        assertEquals(listOf("~", "src", "app"), browseCrumbsOf("src/app"))
    }

    @Test
    fun the_open_workdir_is_the_raw_tilde_form() {
        assertEquals("~", browseWorkdirOf(""))
        assertEquals("~/src/app", browseWorkdirOf("src/app"))
    }

    // ── badges + recents against the flat project list ───────────────────────────────────────────

    @Test
    fun home_is_inferred_from_project_paths_and_subPaths_map_to_native_absolutes() {
        val dirs = listOf(dir("/Users/alex/code/relay-server"))
        assertEquals("/Users/alex", browseHomeAbs(dirs))
        assertEquals("/Users/alex/code/relay-server", browseAbsOf("/Users/alex", "code/relay-server"))
        assertEquals("/Users/alex", browseAbsOf("/Users/alex", ""))
        assertNull(browseAbsOf(null, "code"), "no inferable home (fresh machine) → no abs mapping, badges just stay off")
        // a Windows daemon's paths keep their native separator
        assertEquals("C:\\Users\\alex\\dev\\app", browseAbsOf("C:\\Users\\alex", "dev/app"))
    }

    @Test
    fun the_history_badge_matches_known_projects_at_the_browsed_location() {
        val dirs = listOf(dir("/Users/alex/code/relay-server"))
        assertEquals(dirs[0], browseProjectAt(dirs, "/Users/alex", "code/relay-server"))
        assertNull(browseProjectAt(dirs, "/Users/alex", "code"))
        assertNull(browseProjectAt(dirs, null, "code/relay-server"))
    }

    @Test
    fun recents_are_home_projects_with_their_subPaths_capped_and_off_home_skipped() {
        val dirs = listOf(
            dir("/Users/alex/code/a"), dir("/Volumes/ext/b"), dir("/Users/alex/c"),
            dir("/Users/alex/d1"), dir("/Users/alex/d2"), dir("/Users/alex/d3"), dir("/Users/alex/d4"),
        )
        val recents = browseRecents(dirs, "/Users/alex")
        assertEquals(listOf("code/a", "c", "d1", "d2", "d3"), recents.map { it.second }, "cap 5, order kept, off-home dropped")
        assertTrue(browseRecents(dirs, null).isEmpty())
        // Windows separators map into the picker's '/' keys
        assertEquals(listOf("dev/app"), browseRecents(listOf(dir("C:\\Users\\alex\\dev\\app")), "C:\\Users\\alex").map { it.second })
    }

    // ── guest view ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun guest_view_is_all_rows_stamped_and_nothing_else() {
        assertTrue(isGuestDirView(listOf(dir("/s/root", sharedBy = "panda"))))
        assertFalse(isGuestDirView(listOf(dir("/s/root", sharedBy = "panda"), dir("/Users/alex/mine"))))
        assertFalse(isGuestDirView(emptyList()), "an empty (still-loading / fresh) list must not read as guest")
    }
}
