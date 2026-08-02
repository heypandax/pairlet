package dev.ccpocket.daemon.disk

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The daemon-side archive store (issue #202): archive/restore is idempotent, hostile ids never steer a
 *  map key, the stored workdir stays lossless (dirKey can't be inverted), and pruning keeps the key set
 *  equal to "projects that have an archived session" — the invariant the cross-project view enumerates on. */
class SessionArchiveTest {

    private fun tempFile() = Files.createTempFile("ccp-archive", ".json").toFile().also { it.delete() }

    @Test
    fun archive_then_restore_round_trips() {
        val f = tempFile()
        val wd = "/Users/panda/proj"

        assertFalse(SessionArchive.isArchived(wd, "sid-1", f))
        assertTrue(SessionArchive.setArchived(wd, "sid-1", true, f))
        assertTrue(SessionArchive.isArchived(wd, "sid-1", f))
        assertEquals(setOf("sid-1"), SessionArchive.archivedIds(wd, f))

        assertTrue(SessionArchive.setArchived(wd, "sid-1", false, f))
        assertFalse(SessionArchive.isArchived(wd, "sid-1", f))
        assertTrue(SessionArchive.archivedIds(wd, f).isEmpty())
    }

    @Test
    fun repeating_a_state_is_a_successful_no_op() {
        val f = tempFile()
        val wd = "/w"
        assertTrue(SessionArchive.setArchived(wd, "sid", true, f))
        assertTrue(SessionArchive.setArchived(wd, "sid", true, f), "archiving twice is not an error")
        assertTrue(SessionArchive.setArchived(wd, "nope", false, f), "restoring something never archived is fine")
        assertEquals(setOf("sid"), SessionArchive.archivedIds(wd, f))
    }

    @Test
    fun a_hostile_session_id_is_rejected_before_it_steers_a_key() {
        val f = tempFile()
        val wd = "/w"
        assertFalse(SessionArchive.setArchived(wd, "../../etc/passwd", true, f))
        assertFalse(SessionArchive.setArchived(wd, "a b", true, f))
        assertFalse(SessionArchive.setArchived(wd, "", true, f))
        assertTrue(SessionArchive.archivedIds(wd, f).isEmpty())
    }

    @Test
    fun projects_are_isolated() {
        val f = tempFile()
        SessionArchive.setArchived("/proj/a", "sid-1", true, f)
        SessionArchive.setArchived("/proj/b", "sid-2", true, f)

        assertEquals(setOf("sid-1"), SessionArchive.archivedIds("/proj/a", f))
        assertEquals(setOf("sid-2"), SessionArchive.archivedIds("/proj/b", f))
        assertTrue(SessionArchive.archivedIds("/proj/c", f).isEmpty())
    }

    @Test
    fun the_entry_keeps_the_real_workdir_because_dirKey_is_lossy() {
        val f = tempFile()
        // dirKey mangles every non-alphanumeric to '-', so it can NOT be inverted back into a path —
        // the cross-project view needs the original to re-list the project.
        val wd = "/Users/panda/my_proj.v2"
        SessionArchive.setArchived(wd, "sid-1", true, f)

        assertEquals(wd, SessionArchive.all(f).single().workdir)
        assertTrue(ProjectPaths.dirKey(wd) in f.readText(), "partitioned by dirKey like the group store")
    }

    @Test
    fun restoring_the_last_session_prunes_the_project_entry() {
        val f = tempFile()
        SessionArchive.setArchived("/proj/a", "sid-1", true, f)
        SessionArchive.setArchived("/proj/a", "sid-2", true, f)
        SessionArchive.setArchived("/proj/b", "sid-3", true, f)
        assertEquals(2, SessionArchive.all(f).size)

        SessionArchive.setArchived("/proj/a", "sid-1", false, f)
        assertEquals(2, SessionArchive.all(f).size, "the project still has one archived session")

        SessionArchive.setArchived("/proj/a", "sid-2", false, f)
        // the invariant the enumeration relies on: a project with nothing archived is not in the store,
        // so the cross-project view never re-lists (and re-scans) a workdir for nothing
        assertEquals(listOf("/proj/b"), SessionArchive.all(f).map { it.workdir })
    }

    @Test
    fun all_reports_archived_at_for_ordering() {
        val f = tempFile()
        SessionArchive.setArchived("/proj/a", "sid-1", true, f, now = 1_000)
        SessionArchive.setArchived("/proj/a", "sid-2", true, f, now = 2_000)

        assertEquals(mapOf("sid-1" to 1_000L, "sid-2" to 2_000L), SessionArchive.all(f).single().sessions)
    }

    @Test
    fun persistence_survives_reload() {
        val f = tempFile()
        val wd = "/persisted/proj"
        SessionArchive.setArchived(wd, "sid", true, f)

        // defeat the in-memory snapshot by touching another file first, then re-read f
        val other = tempFile()
        SessionArchive.setArchived("/other", "x", true, other)

        assertTrue(SessionArchive.isArchived(wd, "sid", f))
        assertFalse(SessionArchive.isArchived(wd, "x", f), "the two stores never read each other")
    }

    @Test
    fun a_corrupt_file_starts_empty_instead_of_throwing() {
        val f = tempFile()
        f.writeText("{ not json")
        assertTrue(SessionArchive.archivedIds("/w", f).isEmpty())
        // and it recovers: a write over the garbage works
        assertTrue(SessionArchive.setArchived("/w", "sid", true, f))
        assertTrue(SessionArchive.isArchived("/w", "sid", f))
    }
}
