package dev.ccpocket.app.ui

import dev.ccpocket.protocol.PathEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Locks the composer @-file token/path math (issue #75): token boundaries, dir/leaf split (Windows-safe),
 *  prefix filter, and the drill-in-vs-file insertion. */
class AtCompleteTest {

    @Test
    fun token_starts_at_at_after_whitespace_or_start() {
        assertEquals("src/Ma", atTokenAt("look at @src/Ma", 15)?.query)
        assertEquals("", atTokenAt("@", 1)?.query)
        assertEquals("foo", atTokenAt("hi @foo", 7)?.query)
    }

    @Test
    fun not_a_token_when_at_follows_non_space_or_query_has_space() {
        assertNull(atTokenAt("email a@b.com", 13)) // '@' follows a letter
        assertNull(atTokenAt("@one two", 8))       // cursor past a space
        assertNull(atTokenAt("no ampersand", 5))   // no '@' at all
    }

    @Test
    fun token_is_relative_to_the_cursor_not_the_end() {
        // caret sits right after "@sr"; the trailing "c/x" is not part of the active query
        val t = atTokenAt("@src/x", 3)
        assertEquals("sr", t?.query)
        assertEquals(0, t?.at)
        assertEquals(3, t?.end)
    }

    @Test
    fun dir_and_leaf_split_on_the_daemon_separator() {
        assertEquals("src/app" to "Ma", atDirOf("src/app/Ma", '/') to atLeafOf("src/app/Ma", '/'))
        assertEquals("" to "Ma", atDirOf("Ma", '/') to atLeafOf("Ma", '/'))
        // a Windows daemon's '\' separator: '/' is then a normal filename char, untouched
        assertEquals("""src\app""" to "Ma", atDirOf("""src\app\Ma""", '\\') to atLeafOf("""src\app\Ma""", '\\'))
    }

    @Test
    fun matches_are_a_case_insensitive_prefix_filter() {
        val entries = listOf(PathEntry("Main.kt", false), PathEntry("main", true), PathEntry("README.md", false))
        assertEquals(listOf("Main.kt", "main"), atMatches(entries, "ma").map { it.name })
        assertEquals(entries, atMatches(entries, "")) // empty leaf = everything
    }

    @Test
    fun insert_drills_into_dirs_and_completes_files() {
        val sep = '/'
        // at the root: a folder pick appends the separator (drill-in); a file pick is the bare name
        assertEquals("src/", atInsertText("", PathEntry("src", true), sep))
        assertEquals("README.md", atInsertText("", PathEntry("README.md", false), sep))
        // nested: the dir prefix is preserved
        assertEquals("src/app/Main.kt", atInsertText("src/app", PathEntry("Main.kt", false), sep))
        // a Windows daemon's '\' separator: dir prefix "src" + folder "app" drills to "src\app\"
        assertEquals("""src\app\""", atInsertText("src", PathEntry("app", true), '\\'))
    }
}
