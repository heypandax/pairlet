package dev.ccpocket.daemon.disk

import dev.ccpocket.protocol.RewindMode
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * issue #282: the lineage ledger, and the [TranscriptScanner] stamping that reads it.
 *
 * This journal is the ONLY record that a branch has a parent — probed on CLI 2.1.228, a forked
 * transcript mentions its origin nowhere. So the guarantees worth pinning are: an edge is written once,
 * a corrupt or hostile line can never steer a lookup, and losing the file degrades to "unrelated
 * sessions" rather than to a wrong answer.
 */
class RewindLineageTest {

    private fun ledger() = Files.createTempDirectory("ccp-lineage").resolve("rewind-lineage.tsv").toFile()

    @Test
    fun an_edge_is_written_once_and_reads_back() {
        val f = ledger()
        RewindLineage.note("parent-1", "child-1", 12, RewindMode.REWIND, f)
        RewindLineage.note("parent-1", "child-1", 12, RewindMode.REWIND, f)
        // a relaunch re-reporting the same forked id must not add a second line — two edges for one child
        // would make the fold depend on file order
        RewindLineage.note("parent-1", "child-1", 99, RewindMode.FORK, f)

        val entries = RewindLineage.entries(f)
        assertEquals(1, entries.size, entries.toString())
        assertEquals(RewindLineage.Entry("parent-1", "child-1", 12, RewindMode.REWIND), entries.single())
    }

    @Test
    fun a_session_cannot_be_its_own_parent() {
        val f = ledger()
        // an in-place resume reports the SAME id it resumed — not a branch, and recording it would make a
        // session fold itself out of the list
        RewindLineage.note("same", "same", 1, RewindMode.REWIND, f)
        assertTrue(RewindLineage.entries(f).isEmpty())
    }

    @Test
    fun an_unknown_mode_never_enters_the_ledger() {
        val f = ledger()
        RewindLineage.note("p", "c", 1, "rewind-files", f)
        assertTrue(RewindLineage.entries(f).isEmpty(), "an unrecognised mode would fold rows unpredictably")
    }

    @Test
    fun ids_are_pattern_checked_on_the_way_in() {
        val f = ledger()
        // a tab would forge a second column; a path would aim a later lookup at another project's file
        RewindLineage.note("p\tinjected\tfake", "c", 1, RewindMode.REWIND, f)
        RewindLineage.note("p", "../../etc/passwd", 1, RewindMode.REWIND, f)
        RewindLineage.note("p", "c d", 1, RewindMode.REWIND, f)
        RewindLineage.note("p", "x".repeat(65), 1, RewindMode.REWIND, f)
        assertTrue(RewindLineage.entries(f).isEmpty(), RewindLineage.entries(f).toString())
    }

    @Test
    fun a_hostile_or_corrupt_line_is_skipped_on_read_too() {
        val f = ledger()
        f.parentFile.mkdirs()
        f.writeText(
            listOf(
                "good-parent\tgood-child\t7\trewind",
                "../../etc\tc2\t1\trewind", // planted directly in the file, bypassing note()
                "p3\tc3\tnot-a-number\tfork",
                "p4\tc4\t1\tsomething-else",
                "too\tfew\tcolumns",
                "",
            ).joinToString("\n") + "\n",
        )
        val entries = RewindLineage.entries(f)
        assertEquals(listOf("good-child"), entries.map { it.childSid })
    }

    @Test
    fun a_missing_ledger_is_empty_not_an_error() {
        val f = Files.createTempDirectory("ccp-lineage-none").resolve("nope.tsv").toFile()
        assertTrue(RewindLineage.entries(f).isEmpty())
        assertTrue(RewindLineage.byChild(f).isEmpty())
    }

    @Test
    fun byChild_keeps_the_oldest_edge_when_a_child_somehow_has_two() {
        val f = ledger()
        f.parentFile.mkdirs()
        f.writeText("p1\tc\t1\trewind\np2\tc\t2\tfork\n")
        assertEquals("p1", RewindLineage.byChild(f)["c"]?.parentSid, "the answer must be stable across reads")
    }

    @Test
    fun the_scanner_stamps_the_child_row_and_leaves_the_parent_clean() {
        // The daemon writes the pointer ONCE, on the child. Deriving "this one was superseded" from a peer
        // is what lets the fold work without a second write to a row the scan may not even reach.
        val dir = Files.createTempDirectory("ccp-lineage-scan")
        for (sid in listOf("aaaa1111", "bbbb2222")) {
            dir.resolve("$sid.jsonl").writeText(
                """{"type":"user","uuid":"u1","parentUuid":null,"cwd":"/x","message":{"role":"user","content":"hi"}}""" + "\n",
            )
        }
        val f = ledger()
        RewindLineage.note("aaaa1111", "bbbb2222", 3, RewindMode.REWIND, f)

        val rows = Files.newDirectoryStream(dir, "*.jsonl").use { it.toList() }
            .mapNotNull { TranscriptScanner.summarize(it) }
        val lineage = RewindLineage.byChild(f)
        val stamped = rows.map { s -> lineage[s.sessionId]?.let { e -> s.copy(rewindOf = e.parentSid) } ?: s }

        assertEquals("aaaa1111", stamped.first { it.sessionId == "bbbb2222" }.rewindOf)
        assertNull(stamped.first { it.sessionId == "aaaa1111" }.rewindOf)
        assertTrue(stamped.all { it.forkedFrom == null }, "a rewind must not also read as a fork")
    }
}
