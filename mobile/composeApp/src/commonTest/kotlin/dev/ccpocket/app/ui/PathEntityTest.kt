package dev.ccpocket.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The share-side classification/normalization layer (issue #116). Runs on every target: the recognizer
 * leans on the same Kotlin/Native-sensitive regexes as [PathLinkTest], so this doubles as an engine pin.
 */
class PathEntityTest {

    // a stand-in for the desktop exists() gate: "openable" only for paths under this machine's cwd
    private val localCwd = "/Users/me/project"
    private fun localOpen(p: String): Boolean =
        p.startsWith(localCwd) || !p.startsWith('/') && !p.startsWith('~') && p[1] != ':'

    private fun kinds(text: String, cwd: String? = localCwd, canOpen: (String) -> Boolean = ::localOpen) =
        recognizeEntities(text, cwd, canOpen)

    @Test
    fun classifies_open_copy_and_url_in_one_line() {
        // an openable local path, a remote-machine path (copy-only), and a URL — the three-entity mix
        val text = "edited src/render/measure.ts, fixtures at /srv/ci/wrap.json, notes https://example.com/page here"
        val e = kinds(text)
        assertEquals(3, e.size)
        assertEquals(EntityKind.OPEN, e[0].kind)
        assertEquals("src/render/measure.ts", e[0].display)
        assertEquals(EntityKind.COPY, e[1].kind)
        assertEquals("/srv/ci/wrap.json", e[1].display)
        assertEquals(EntityKind.URL, e[2].kind)
        assertEquals("https://example.com/page", e[2].display)
    }

    @Test
    fun relative_path_copies_as_absolute_under_cwd() {
        val e = kinds("see docs/design/notes.md for details").single()
        assertEquals(EntityKind.OPEN, e.kind)
        assertEquals("docs/design/notes.md", e.display)
        assertEquals("/Users/me/project/docs/design/notes.md", e.copyValue) // resolved, not verbatim
    }

    @Test
    fun absolute_and_home_paths_copy_verbatim() {
        assertEquals("/var/data/out.log", kinds("at /var/data/out.log now").single().copyValue)
        assertEquals("~/notes/todo.md", kinds("kept in ~/notes/todo.md today").single().copyValue)
    }

    @Test
    fun relative_path_without_cwd_copies_verbatim() {
        val e = kinds("open docs/readme.md please", cwd = null).single()
        assertEquals("docs/readme.md", e.copyValue) // nothing to resolve against, so unchanged
    }

    @Test
    fun windows_relative_base_joins_with_backslash() {
        val e = kinds("open docs/readme.md please", cwd = "C:\\Users\\me\\project").single()
        assertEquals("C:\\Users\\me\\project\\docs/readme.md", e.copyValue)
    }

    @Test
    fun openability_follows_the_machine_predicate() {
        // phone side: no path is openable — every recognised path is copy-only, but URLs still open
        val text = "edited docs/readme.md and see https://example.com/page"
        val phone = recognizeEntities(text, localCwd, canOpen = { false })
        assertEquals(EntityKind.COPY, phone.first { it.display == "docs/readme.md" }.kind)
        assertEquals(EntityKind.URL, phone.first { it.kind == EntityKind.URL }.kind)
    }

    @Test
    fun url_clean_value_strips_glued_punctuation() {
        // the recognised URL and its copy value both exclude the trailing sentence comma
        val e = kinds("docs at https://example.com/a/b?q=1, and more").single()
        assertEquals(EntityKind.URL, e.kind)
        assertEquals("https://example.com/a/b?q=1", e.display)
        assertEquals("https://example.com/a/b?q=1", e.copyValue)
    }

    @Test
    fun a_url_is_labelled_once_not_also_as_a_path() {
        // the slashes inside a URL must not spawn a second path entity over the same span
        val e = kinds("open https://example.com/dir/file.md now")
        assertEquals(1, e.size)
        assertEquals(EntityKind.URL, e.single().kind)
    }
}
