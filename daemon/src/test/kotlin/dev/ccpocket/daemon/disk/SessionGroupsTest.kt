package dev.ccpocket.daemon.disk

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionGroupsTest {

    private fun tempFile() = Files.createTempFile("ccp-groups", ".json").toFile().also { it.delete() }

    @Test
    fun create_assign_groupOf_and_ordering() {
        val f = tempFile()
        val wd = "/Users/panda/proj"

        val a = SessionGroups.create(wd, "Feature", f)
        val b = SessionGroups.create(wd, "Bugs", f)
        assertNotNull(a); assertNotNull(b)
        assertEquals(0, a.order); assertEquals(1, b.order)
        assertTrue(a.id != b.id)

        // insertion order preserved
        assertEquals(listOf("Feature", "Bugs"), SessionGroups.groupsFor(wd, f).map { it.name })

        assertTrue(SessionGroups.assign(wd, "sid-1", a.id, f))
        assertEquals(a.id, SessionGroups.groupOf(wd, "sid-1", f))
        assertNull(SessionGroups.groupOf(wd, "sid-unknown", f))
    }

    @Test
    fun rename_and_blank_name_rejected() {
        val f = tempFile()
        val wd = "/w"
        val g = SessionGroups.create(wd, "  Trimmed  ", f)!!
        assertEquals("Trimmed", g.name) // trimmed

        assertNull(SessionGroups.create(wd, "   ", f)) // blank rejected
        assertTrue(SessionGroups.rename(wd, g.id, "New name", f))
        assertEquals("New name", SessionGroups.groupsFor(wd, f).single().name)
        assertFalse(SessionGroups.rename(wd, g.id, "  ", f))          // blank rejected
        assertFalse(SessionGroups.rename(wd, "nope", "X", f))         // missing group
    }

    @Test
    fun assign_out_and_reassign() {
        val f = tempFile()
        val wd = "/w"
        val g = SessionGroups.create(wd, "G", f)!!
        SessionGroups.assign(wd, "s", g.id, f)
        assertEquals(g.id, SessionGroups.groupOf(wd, "s", f))
        // move out of any group
        assertTrue(SessionGroups.assign(wd, "s", null, f))
        assertNull(SessionGroups.groupOf(wd, "s", f))
        // assigning to a non-existent group is refused
        assertFalse(SessionGroups.assign(wd, "s", "ghost", f))
    }

    @Test
    fun delete_group_falls_back_its_sessions_but_keeps_others() {
        val f = tempFile()
        val wd = "/w"
        val g1 = SessionGroups.create(wd, "One", f)!!
        val g2 = SessionGroups.create(wd, "Two", f)!!
        SessionGroups.assign(wd, "a", g1.id, f)
        SessionGroups.assign(wd, "b", g1.id, f)
        SessionGroups.assign(wd, "c", g2.id, f)

        assertTrue(SessionGroups.delete(wd, g1.id, f))
        // g1's sessions fall back to ungrouped; g2's assignment untouched
        assertNull(SessionGroups.groupOf(wd, "a", f))
        assertNull(SessionGroups.groupOf(wd, "b", f))
        assertEquals(g2.id, SessionGroups.groupOf(wd, "c", f))
        assertEquals(listOf("Two"), SessionGroups.groupsFor(wd, f).map { it.name })
        assertFalse(SessionGroups.delete(wd, "already-gone", f))
    }

    @Test
    fun inherit_copies_membership_on_fork() {
        val f = tempFile()
        val wd = "/w"
        val g = SessionGroups.create(wd, "G", f)!!
        SessionGroups.assign(wd, "parent", g.id, f)

        // forked child inherits the parent's group
        assertTrue(SessionGroups.inherit(wd, "parent", "child", f))
        assertEquals(g.id, SessionGroups.groupOf(wd, "child", f))

        // parent unchanged
        assertEquals(g.id, SessionGroups.groupOf(wd, "parent", f))

        // an ungrouped parent inherits nothing
        assertFalse(SessionGroups.inherit(wd, "loner", "loner-child", f))
        assertNull(SessionGroups.groupOf(wd, "loner-child", f))

        // inherit into a group that was since deleted is a no-op
        SessionGroups.assign(wd, "p2", g.id, f)
        SessionGroups.delete(wd, g.id, f)
        assertFalse(SessionGroups.inherit(wd, "p2", "c2", f))
    }

    @Test
    fun persistence_survives_reload() {
        val f = tempFile()
        val wd = "/persisted/proj"
        val g = SessionGroups.create(wd, "Persisted", f)!!
        SessionGroups.assign(wd, "sid", g.id, f)

        // force a fresh read by touching another file first (defeats the in-memory snapshot), then re-read f
        val other = tempFile()
        SessionGroups.create("/other", "x", other)
        assertEquals("Persisted", SessionGroups.groupsFor(wd, f).single().name)
        assertEquals(g.id, SessionGroups.groupOf(wd, "sid", f))

        // the file is real JSON keyed by the project dir-key
        val raw = f.readText()
        assertTrue(ProjectPaths.dirKey(wd) in raw, raw)
    }

    @Test
    fun projects_are_partitioned_by_dirKey() {
        val f = tempFile()
        val a = SessionGroups.create("/proj/a", "GA", f)!!
        SessionGroups.create("/proj/b", "GB", f)
        // a group under /proj/a is invisible to /proj/b
        assertEquals(listOf("GA"), SessionGroups.groupsFor("/proj/a", f).map { it.name })
        assertEquals(listOf("GB"), SessionGroups.groupsFor("/proj/b", f).map { it.name })
        assertNull(SessionGroups.groupOf("/proj/b", "x", f))
        // assigning under the wrong project can't see the other's group id
        assertFalse(SessionGroups.assign("/proj/b", "s", a.id, f))
    }

    @Test
    fun malformed_sessionId_is_rejected() {
        val f = tempFile()
        val wd = "/w"
        val g = SessionGroups.create(wd, "G", f)!!
        assertFalse(SessionGroups.assign(wd, "bad id with spaces", g.id, f))
        assertFalse(SessionGroups.assign(wd, "../escape", g.id, f))
    }
}
