package dev.ccpocket.daemon.disk

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpawnedSessionsTest {

    private val hidden = listOf(
        """{"type":"user","entrypoint":"sdk-cli","promptSource":"sdk","message":{"role":"user","content":"hi"}}""",
        """{"type":"assistant","entrypoint":"sdk-cli","message":{"content":[{"type":"text","text":"ok"}]}}""",
    ).joinToString("\n")

    private fun fixture(): Triple<Path, File, File> {
        val root = Files.createTempDirectory("ccp-spawned")
        val projects = root.resolve("projects").also(Files::createDirectories)
        return Triple(projects, root.resolve("spawned-sessions.tsv").toFile(), root.resolve("spawned-sessions.d").toFile())
    }

    private fun Path.hiddenTranscript(sid: String, staleBy: Long = 60 * 60_000): Path =
        resolve("$sid.jsonl").apply {
            writeText(hidden)
            Files.setLastModifiedTime(this, FileTime.fromMillis(System.currentTimeMillis() - staleBy))
        }

    /** No live processes anywhere — the pure-disk scenarios must not depend on this machine's lsof. */
    private val noExternal = { _: String, _: Path -> LiveProcesses.ExternalClaude.ABSENT }

    @Test
    fun sweep_unhides_stale_journaled_transcripts_and_keeps_fresh_ones() = runBlocking {
        val (projects, journal, dropIns) = fixture()

        // a crash-stranded transcript (old mtime) and a freshly-written one (maybe a live terminal claude)
        val stale = projects.hiddenTranscript("s-old")
        val fresh = projects.resolve("s-new.jsonl").apply { writeText(hidden) }

        SpawnedSessions.note("/w", "s-old", journal)
        SpawnedSessions.note("/w", "s-new", journal)
        SpawnedSessions.note("/w", "s-gone", journal) // transcript deleted meanwhile
        SpawnedSessions.note("/w", "s-old", journal) // idempotent — no duplicate line
        assertEquals(3, journal.readLines().count { it.isNotBlank() })

        val unhidden = SpawnedSessions.sweep(journal, dropIns, dirFor = { projects }, probe = noExternal)

        assertEquals(1, unhidden)
        assertFalse("sdk-cli" in stale.readText()) // rewritten for the resume pickers
        assertTrue("sdk-cli" in fresh.readText())  // fresh file untouched — not safe to rewrite
        // journal keeps ONLY the fresh entry for the next pass
        assertEquals(listOf("/w\ts-new"), journal.readLines().filter { it.isNotBlank() })

        // a later periodic pass with the file now stale finishes the job and drops the journal
        Files.setLastModifiedTime(fresh, FileTime.fromMillis(System.currentTimeMillis() - 60 * 60_000))
        assertEquals(1, SpawnedSessions.sweep(journal, dropIns, dirFor = { projects }, probe = noExternal))
        assertFalse(journal.exists())
    }

    /** issue #216 ②'s safety wall: the PERIODIC sweep runs while this daemon's own conversations are
     *  live — their claudes hold the journaled files (invisible to the external probe: they're our
     *  children), and rewriting under them drops concurrent appends (the d8fa0da regression class). */
    @Test
    fun periodic_sweep_never_touches_a_transcript_held_by_a_live_conversation() = runBlocking {
        val (projects, journal, dropIns) = fixture()
        val heldFile = projects.hiddenTranscript("s-live") // stale mtime: idle between turns, but the process lives
        val strandedFile = projects.hiddenTranscript("s-crashed")
        SpawnedSessions.note("/w", "s-live", journal)
        SpawnedSessions.note("/w", "s-crashed", journal)

        val unhidden = SpawnedSessions.sweep(
            journal, dropIns, dirFor = { projects },
            held = { sid -> sid == "s-live" }, probe = noExternal,
        )

        assertEquals(1, unhidden)
        assertTrue("sdk-cli" in heldFile.readText(), "a live conversation's transcript must never be rewritten")
        assertFalse("sdk-cli" in strandedFile.readText(), "the crash leftover converges without waiting for a reboot")
        // the held entry stays journaled — its own process end (or a later sweep) finishes the job
        assertEquals(listOf("/w\ts-live"), journal.readLines().filter { it.isNotBlank() })
    }

    /** An EXTERNAL claude (terminal resume, sibling spawner's live process) attached to the file wins:
     *  PRESENT keeps the entry; UNKNOWN (Windows / lsof failure) falls back to the mtime verdict alone,
     *  else Windows would never unhide anything. */
    @Test
    fun sweep_spares_transcripts_an_external_claude_is_attached_to() = runBlocking {
        val (projects, journal, dropIns) = fixture()
        val busyFile = projects.hiddenTranscript("s-terminal")
        val freeFile = projects.hiddenTranscript("s-free")
        SpawnedSessions.note("/w", "s-terminal", journal)
        SpawnedSessions.note("/w", "s-free", journal)

        val unhidden = SpawnedSessions.sweep(
            journal, dropIns, dirFor = { projects },
            probe = { _, file ->
                if (file.fileName.toString().startsWith("s-terminal")) LiveProcesses.ExternalClaude.PRESENT
                else LiveProcesses.ExternalClaude.UNKNOWN // quiet + unheld: mtime verdict alone suffices
            },
        )

        assertEquals(1, unhidden)
        assertTrue("sdk-cli" in busyFile.readText())
        assertFalse("sdk-cli" in freeFile.readText())
        assertEquals(listOf("/w\ts-terminal"), journal.readLines().filter { it.isNotBlank() })
    }

    /** issue #216 ④: a sibling spawner (cc-connect and friends) drops its own journal into
     *  spawned-sessions.d/ and gets the same unhide protection — with the same validation walls. */
    @Test
    fun drop_in_spawner_journals_are_swept_with_the_same_guards() = runBlocking {
        val (projects, journal, dropIns) = fixture()
        val ours = projects.hiddenTranscript("s-ours")
        val theirs = projects.hiddenTranscript("s-connect")
        SpawnedSessions.note("/w", "s-ours", journal)
        dropIns.mkdirs()
        File(dropIns, "cc-connect.tsv").writeText(
            listOf(
                "/w\ts-connect",
                "/w\t../../etc/passwd", // hostile id — must be dropped without ever steering a rewrite
                "not a journal line",
            ).joinToString("\n") + "\n",
        )

        val unhidden = SpawnedSessions.sweep(journal, dropIns, dirFor = { projects }, probe = noExternal)

        assertEquals(2, unhidden)
        assertFalse("sdk-cli" in ours.readText())
        assertFalse("sdk-cli" in theirs.readText())
        assertFalse(journal.exists())
        assertFalse(File(dropIns, "cc-connect.tsv").exists(), "a fully-settled drop-in journal is pruned away")
    }
}
