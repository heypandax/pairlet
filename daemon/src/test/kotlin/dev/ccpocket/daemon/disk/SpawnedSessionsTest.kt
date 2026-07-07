package dev.ccpocket.daemon.disk

import java.nio.file.Files
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

    @Test
    fun boot_sweep_unhides_stale_journaled_transcripts_and_keeps_fresh_ones() {
        val root = Files.createTempDirectory("ccp-spawned")
        val projects = root.resolve("projects").also(Files::createDirectories)
        val journal = root.resolve("spawned-sessions.tsv").toFile()

        // a crash-stranded transcript (old mtime) and a freshly-written one (maybe a live terminal claude)
        val stale = projects.resolve("s-old.jsonl").apply { writeText(hidden) }
        Files.setLastModifiedTime(stale, FileTime.fromMillis(System.currentTimeMillis() - 60 * 60_000))
        val fresh = projects.resolve("s-new.jsonl").apply { writeText(hidden) }

        SpawnedSessions.note("/w", "s-old", journal)
        SpawnedSessions.note("/w", "s-new", journal)
        SpawnedSessions.note("/w", "s-gone", journal) // transcript deleted meanwhile
        SpawnedSessions.note("/w", "s-old", journal) // idempotent — no duplicate line
        assertEquals(3, journal.readLines().count { it.isNotBlank() })

        val unhidden = SpawnedSessions.sweepAtBoot(journal, dirFor = { projects })

        assertEquals(1, unhidden)
        assertFalse("sdk-cli" in stale.readText()) // rewritten for the resume pickers
        assertTrue("sdk-cli" in fresh.readText())  // fresh file untouched — not safe to rewrite
        // journal keeps ONLY the fresh entry for the next boot
        assertEquals(listOf("/w\ts-new"), journal.readLines().filter { it.isNotBlank() })

        // second sweep with the file now stale finishes the job and drops the journal
        Files.setLastModifiedTime(fresh, FileTime.fromMillis(System.currentTimeMillis() - 60 * 60_000))
        assertEquals(1, SpawnedSessions.sweepAtBoot(journal, dirFor = { projects }))
        assertFalse(journal.exists())
    }
}
