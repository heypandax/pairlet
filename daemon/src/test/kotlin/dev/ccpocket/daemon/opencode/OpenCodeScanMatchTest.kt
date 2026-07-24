package dev.ccpocket.daemon.opencode

import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #184: opencode records its own spelling of a session's directory (tilde / trailing separators /
 * symlinked forms). The scan filter must use [dev.ccpocket.daemon.disk.ProjectPaths.canonicalKey] — the
 * SAME key the directory list merges rows by — because the merged project row hands the backends its
 * realpath'd workdir; a weaker string compare here deduped the row but lost its opencode sessions (the
 * "tap the row, only New session left" half of the bug).
 */
class OpenCodeScanMatchTest {

    private val tmp = Files.createTempDirectory("ccp-ocdb")
    private val work = Files.createTempDirectory("ccp-ocwork")
    private val link = work.parent.resolve("${work.fileName}-lnk").also { Files.createSymbolicLink(it, work) }

    @AfterTest
    fun cleanup() {
        Files.deleteIfExists(link)
        tmp.toFile().deleteRecursively()
        work.toFile().deleteRecursively()
    }

    /** A minimal opencode.db with one session recorded under [directory] (real schema columns the scan reads). */
    private fun db(directory: String): Connection {
        val conn = DriverManager.getConnection("jdbc:sqlite:${tmp.resolve("opencode.db")}")
        conn.createStatement().use {
            it.executeUpdate(
                "CREATE TABLE IF NOT EXISTS session (id TEXT PRIMARY KEY, title TEXT, directory TEXT, model TEXT, " +
                    "cost REAL, tokens_input INTEGER, tokens_output INTEGER, time_created INTEGER, time_updated INTEGER, time_archived INTEGER)",
            )
            it.executeUpdate("CREATE TABLE IF NOT EXISTS message (id TEXT PRIMARY KEY, session_id TEXT)")
        }
        conn.prepareStatement("INSERT INTO session (id, title, directory, time_created, time_updated) VALUES (?,?,?,?,?)").use {
            it.setString(1, "ses_1"); it.setString(2, "hello"); it.setString(3, directory)
            it.setLong(4, 1L); it.setLong(5, 2L)
            it.executeUpdate()
        }
        return conn
    }

    @Test
    fun scan_matches_a_variant_spelled_directory_against_the_realpathd_workdir() {
        // recorded: symlinked + trailing slash; asked: the realpath (what the merged row resolves to)
        val s = OpenCodeTranscriptScanner.scan(work.toRealPath().toString(), db("$link/")).single()
        assertEquals("ses_1", s.sessionId)
        assertEquals("hello", s.title)
    }

    @Test
    fun scan_still_excludes_a_genuinely_different_directory() {
        val other = Files.createTempDirectory("ccp-ocother")
        try {
            assertTrue(OpenCodeTranscriptScanner.scan(other.toString(), db(work.toString())).isEmpty())
        } finally {
            other.toFile().deleteRecursively()
        }
    }
}
