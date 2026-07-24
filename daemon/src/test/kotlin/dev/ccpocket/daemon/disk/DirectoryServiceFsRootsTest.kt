package dev.ccpocket.daemon.disk

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Filesystem-root enumeration (issue #176): the daemon-side source of the picker's root switcher.
 * Portable assertions only — the set of roots differs per OS ("/" on Unix, drive letters on Windows) —
 * but the invariants the app relies on hold everywhere: at least one root, every entry an absolute
 * path, and the user's home reachable under one of them (so a switch-then-drill can always come back).
 */
class DirectoryServiceFsRootsTest {

    private val svc = DirectoryService()

    @Test
    fun roots_are_nonempty_absolute_and_cover_the_home_dir() {
        val roots = svc.listFsRoots()
        assertTrue(roots.isNotEmpty(), "every machine has at least one filesystem root")
        roots.forEach { r ->
            assertTrue(Path.of(r).isAbsolute, "a root must be an absolute path, got: $r")
        }
        val home = Path.of(System.getProperty("user.home")).toAbsolutePath()
        assertTrue(
            roots.any { home.startsWith(Path.of(it)) },
            "the home dir must sit under one of the reported roots ($roots vs $home)",
        )
    }

    @Test
    fun each_root_is_directly_listable_as_a_browse_workdir() {
        // the switcher sends the root back as a plain ListPathEntries workdir — the existing surface
        // must accept it as-is (this is what makes old daemons serve root browsing too)
        val roots = svc.listFsRoots()
        assertTrue(
            roots.any { svc.listPathEntries(it, "", 50) != null },
            "at least one enumerated root must be listable via the ordinary workdir path ($roots)",
        )
    }
}
