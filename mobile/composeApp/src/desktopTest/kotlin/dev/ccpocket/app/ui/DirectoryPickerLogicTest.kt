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
 * The "open a project folder" picker's pure logic (issue #152, #176): dirs-only + dot-folder filtering,
 * the out-of-order listing guard, the '/'-joined subPath algebra over an ANCHOR that may be "~" or a
 * filesystem root ("/", "C:\") (#176 root switcher), the native-separator workdir join (never a doubled
 * root separator), badge/recents matching against the flat project list (both host separators, off-home
 * projects now reachable), and the guest-view detection that keeps the browser owner-only client-side.
 */
class DirectoryPickerLogicTest {

    private fun listing(subPath: String, vararg entries: PathEntry, anchor: String = "~", ok: Boolean = true) =
        PathEntries(workdir = anchor, subPath = subPath, entries = entries.toList(), ok = ok)

    private fun dir(path: String, name: String = path.substringAfterLast('/').substringAfterLast('\\'), sharedBy: String? = null) =
        DirectoryEntry(path = path, name = name, isDir = true, hasSessions = true, sharedBy = sharedBy)

    // ── rows ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun rows_keep_visible_directories_only() {
        val l = listing("", PathEntry("src", true), PathEntry(".git", true), PathEntry("README.md", false))
        assertEquals(listOf("src"), browseRows(l, "~", "")!!.map { it.name })
    }

    @Test
    fun a_stale_or_foreign_listing_reads_as_loading() {
        val l = listing("src", PathEntry("main", true))
        assertNull(browseRows(l, "~", ""), "a reply for another subPath must not render (out-of-order drilling)")
        assertNull(browseRows(null, "~", ""), "no reply yet = loading")
        val atCompletion = PathEntries(workdir = "/Users/x/proj", subPath = "", entries = listOf(PathEntry("a", true)))
        assertNull(browseRows(atCompletion, "~", ""), "an @-completion reply (real workdir) must never leak into the browser")
    }

    @Test
    fun rows_render_for_the_current_anchor_only() {
        // #176: after a root switch the anchor is a filesystem root — a listing only renders under the
        // anchor it was requested for, so a stale home (or other-root) reply can't leak in.
        val atRoot = listing("", PathEntry("Windows", true), PathEntry("pagefile.sys", false), anchor = "C:\\")
        assertEquals(listOf("Windows"), browseRows(atRoot, "C:\\", "")!!.map { it.name })
        assertNull(browseRows(atRoot, "~", ""), "a root listing must not render under the home anchor")
        assertNull(browseRows(atRoot, "D:\\", ""), "a listing for another root must not render")
    }

    @Test
    fun an_out_of_order_stale_root_reply_must_not_clobber_the_fresh_child_listing() {
        // drill root→"src" fast enough that both requests are in flight and the CHILD's reply lands
        // first, the root's stale reply after. Folding the stale one in would strand the picker on the
        // skeleton forever: browseRows keys on subPath and no further request is pending to repair it.
        var held: PathEntries? = null
        val lastSub = "src" // what the repo recorded when it sent the latest request
        held = PocketRepository.foldBrowseReply(held, listing("src", PathEntry("main", true)), lastSub)
        assertEquals(listOf("main"), browseRows(held, "~", "src")!!.map { it.name }, "fresh child reply renders")
        held = PocketRepository.foldBrowseReply(held, listing("", PathEntry("src", true)), lastSub)
        assertEquals("src", held?.subPath, "the late root reply must be dropped, not folded in")
        assertEquals(listOf("main"), browseRows(held, "~", "src")!!.map { it.name }, "child rows survive the stale arrival")
    }

    @Test
    fun a_failed_listing_is_an_error_only_for_its_own_subPath() {
        assertTrue(browseFailed(listing("src", ok = false), "~", "src"))
        assertFalse(browseFailed(listing("src", ok = false), "~", ""), "an old failure must not mark the level we navigated back to")
        assertFalse(browseFailed(listing("src", ok = false), "C:\\", "src"), "a home failure is not a root-anchor failure")
        assertFalse(browseFailed(null, "~", "src"))
    }

    // ── subPath algebra ───────────────────────────────────────────────────────────────────────────

    @Test
    fun join_parent_and_crumbs_agree() {
        assertEquals("src", browseJoin("", "src"))
        assertEquals("src/app", browseJoin("src", "app"))
        assertEquals("src", browseParentOf("src/app"))
        assertEquals("", browseParentOf("src"))
        assertEquals("", browseParentOf(""))
        assertEquals(listOf("~"), browseCrumbsOf("~", ""))
        assertEquals(listOf("~", "src", "app"), browseCrumbsOf("~", "src/app"))
    }

    @Test
    fun crumbs_label_a_filesystem_root_anchor_by_its_trimmed_root() {
        // #176: the switcher's root segment shows the root trimmed of its trailing separator
        assertEquals(listOf("C:", "dev", "app"), browseCrumbsOf("C:\\", "dev/app"))
        assertEquals(listOf("/", "opt"), browseCrumbsOf("/", "opt"))
        assertEquals(listOf("/"), browseCrumbsOf("/", ""))
    }

    @Test
    fun the_open_workdir_is_the_raw_tilde_form() {
        assertEquals("~", browseWorkdirOf("~", ""))
        assertEquals("~/src/app", browseWorkdirOf("~", "src/app"))
    }

    @Test
    fun a_root_anchored_workdir_uses_native_separators_and_never_doubles_the_root() {
        // #176 headline bug guard: "C:\" + "src" must be "C:\src", NOT "C:\/src"; "/" + "opt" is "/opt"
        assertEquals("C:\\src\\app", browseWorkdirOf("C:\\", "src/app"))
        assertEquals("C:\\", browseWorkdirOf("C:\\", ""), "the root itself is a valid workdir")
        assertEquals("/opt/x", browseWorkdirOf("/", "opt/x"))
        assertEquals("/", browseWorkdirOf("/", ""))
    }

    // ── badges + recents against the flat project list ───────────────────────────────────────────

    @Test
    fun home_is_inferred_from_project_paths_and_subPaths_map_to_native_absolutes() {
        val dirs = listOf(dir("/Users/alex/code/relay-server"))
        assertEquals("/Users/alex", browseHomeAbs(dirs))
        assertEquals("/Users/alex/code/relay-server", browseAbsOf("/Users/alex", "~", "code/relay-server"))
        assertEquals("/Users/alex", browseAbsOf("/Users/alex", "~", ""))
        assertNull(browseAbsOf(null, "~", "code"), "no inferable home (fresh machine) → no abs mapping, badges just stay off")
        // a Windows daemon's paths keep their native separator
        assertEquals("C:\\Users\\alex\\dev\\app", browseAbsOf("C:\\Users\\alex", "~", "dev/app"))
        // #176: a filesystem-root anchor resolves without any home inference
        assertEquals("C:\\dev\\app", browseAbsOf(null, "C:\\", "dev/app"))
        assertEquals("/opt/x", browseAbsOf(null, "/", "opt/x"))
    }

    @Test
    fun the_history_badge_matches_known_projects_at_the_browsed_location() {
        val dirs = listOf(dir("/Users/alex/code/relay-server"))
        assertEquals(dirs[0], browseProjectAt(dirs, "/Users/alex", "~", "code/relay-server"))
        assertNull(browseProjectAt(dirs, "/Users/alex", "~", "code"))
        assertNull(browseProjectAt(dirs, null, "~", "code/relay-server"))
        // #176: a badge matches when browsing under a filesystem-root anchor too
        val win = listOf(dir("C:\\dev\\app"))
        assertEquals(win[0], browseProjectAt(win, null, "C:\\", "dev/app"))
    }

    @Test
    fun the_filesystem_root_of_a_path_is_the_drive_or_slash() {
        // #176: recents derive an off-home project's anchor from the path itself (no daemon roots needed)
        assertEquals("/", fsRootOf("/opt/x"))
        assertEquals("/", fsRootOf("/"))
        assertEquals("C:\\", fsRootOf("C:\\dev\\app"))
        assertEquals("C:\\", fsRootOf("C:\\"))
        assertEquals("D:\\", fsRootOf("D:\\proj"))
        assertNull(fsRootOf("relative/path"))
        assertNull(fsRootOf(""))
    }

    @Test
    fun a_path_maps_to_home_when_under_it_else_to_its_filesystem_root() {
        assertEquals("~" to "code/a", anchorOf("/Users/alex/code/a", "/Users/alex"))
        assertEquals("/" to "opt/x", anchorOf("/opt/x", "/Users/alex"))
        assertEquals("/" to "Volumes/ext/b", anchorOf("/Volumes/ext/b", "/Users/alex"))
        assertEquals("~" to "dev/app", anchorOf("C:\\Users\\alex\\dev\\app", "C:\\Users\\alex"))
        assertEquals("D:\\" to "proj", anchorOf("D:\\proj", "C:\\Users\\alex"), "off-home on another drive anchors at that drive")
        assertNull(anchorOf("relative/x", null))
    }

    @Test
    fun recents_include_off_home_projects_with_their_anchor_jumps() {
        // #176: off-home projects are no longer skipped — each recent carries the (anchor, subPath) that
        // reaches it, so a tap can switch the browse root as well as the level. Order/cap unchanged.
        val dirs = listOf(
            dir("/Users/alex/code/a"), dir("/Volumes/ext/b"), dir("/Users/alex/c"),
            dir("/Users/alex/d1"), dir("/Users/alex/d2"), dir("/Users/alex/d3"), dir("/Users/alex/d4"),
        )
        val recents = browseRecents(dirs, "/Users/alex")
        assertEquals(listOf("code/a", "Volumes/ext/b", "c", "d1", "d2"), recents.map { it.subPath }, "cap 5, order kept")
        assertEquals(listOf("~", "/", "~", "~", "~"), recents.map { it.anchor }, "off-home 'b' anchors at the fs root")
        assertTrue(recents.any { it.entry.path == "/Volumes/ext/b" }, "the off-home project is now reachable (was skipped pre-#176)")
        // with no inferable home, everything still resolves to its filesystem root rather than dropping
        val nullHome = browseRecents(dirs, null)
        assertEquals(5, nullHome.size)
        assertTrue(nullHome.all { it.anchor == "/" }, "these sample paths all sit under the Unix root")
        // Windows separators map into the picker's '/' keys
        assertEquals(listOf("dev/app"), browseRecents(listOf(dir("C:\\Users\\alex\\dev\\app")), "C:\\Users\\alex").map { it.subPath })
    }

    // ── guest view ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun guest_view_is_all_rows_stamped_and_nothing_else() {
        assertTrue(isGuestDirView(listOf(dir("/s/root", sharedBy = "panda"))))
        assertFalse(isGuestDirView(listOf(dir("/s/root", sharedBy = "panda"), dir("/Users/alex/mine"))))
        assertFalse(isGuestDirView(emptyList()), "an empty (still-loading / fresh) list must not read as guest")
    }
}
