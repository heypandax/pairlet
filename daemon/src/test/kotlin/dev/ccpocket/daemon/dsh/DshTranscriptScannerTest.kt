package dev.ccpocket.daemon.dsh

import com.github.luben.zstd.Zstd
import dev.ccpocket.protocol.AgentKind
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Session discovery over a synthetic `~/.dsh/sessions` tree.
 *
 * The load-bearing property is that membership is decided by the HEADER's `cwd`, not by the directory
 * name — because the directory name collides. [twoDifferentCwdsShareADirectory] is the test that would
 * catch a regression to naive directory-name matching.
 */
class DshTranscriptScannerTest {

    private fun store(): Path =
        Files.createTempDirectory("dsh-store-test").also { it.toFile().deleteOnExit() }

    /** Write one session into the store the way dsh lays it out. */
    private fun session(
        root: Path,
        cwd: String?,
        id: String,
        version: Int = 0,
        origin: String? = null,
        events: String = "",
    ): Path {
        val projectDir = root.resolve(if (cwd == null) "_no-cwd" else DshPaths.projectKey(cwd))
        val dir = projectDir.resolve(DshPaths.encodeSessionId(id))
        dir.createDirectories()
        val header = buildString {
            append("""{"type":"session","version":$version,"id":"$id"""")
            if (cwd != null) append(""","cwd":"$cwd"""")
            append(""","createdAt":1700000000000,"delegationDepth":0""")
            if (origin != null) append(""","origin":"$origin"""")
            append("}\n")
        }
        val file = dir.resolve("session.jsonl.zstd")
        val bytes = Zstd.compress(header.toByteArray()) +
            (if (events.isEmpty()) ByteArray(0) else Zstd.compress(events.toByteArray()))
        Files.write(file, bytes)
        return dir
    }

    private fun userMsg(text: String, seq: Int) =
        """{"type":"user/message","seq":$seq,"time":1,"data":{"id":"m$seq","role":"user","content":[{"type":"text","text":"$text"}],"source":"user"}}""" + "\n"

    @Test
    fun finds_sessions_for_the_requested_cwd() {
        val root = store()
        session(root, "/work/alpha", "session-a", events = userMsg("alpha work", 1))
        session(root, "/work/beta", "session-b", events = userMsg("beta work", 1))

        val rows = DshTranscriptScanner.scan("/work/alpha", root)
        assertEquals(1, rows.size)
        assertEquals("session-a", rows[0].sessionId)
        assertEquals("/work/alpha", rows[0].cwd)
        assertEquals(AgentKind.DSH, rows[0].agent)
        assertEquals("alpha work", rows[0].title) // no title event → first user message
    }

    /**
     * THE COLLISION CASE. `/work/a/b` and `/work/a-b` normalize to the SAME project directory, so both
     * sessions physically sit side by side. Only the header's cwd can tell them apart.
     */
    @Test
    fun twoDifferentCwdsShareADirectory() {
        val root = store()
        val dirA = session(root, "/work/a/b", "session-slash", events = userMsg("slash", 1))
        val dirB = session(root, "/work/a-b", "session-dash", events = userMsg("dash", 1))
        assertEquals(dirA.parent, dirB.parent, "precondition: the two cwds must collide into one directory")

        assertEquals(listOf("session-slash"), DshTranscriptScanner.scan("/work/a/b", root).map { it.sessionId })
        assertEquals(listOf("session-dash"), DshTranscriptScanner.scan("/work/a-b", root).map { it.sessionId })
    }

    @Test
    fun subagent_sessions_are_hidden_from_the_list() {
        val root = store()
        session(root, "/work/alpha", "session-main", events = userMsg("main", 1))
        session(root, "/work/alpha", "session-sub", origin = "subagent", events = userMsg("inner", 1))

        assertEquals(listOf("session-main"), DshTranscriptScanner.scan("/work/alpha", root).map { it.sessionId })
    }

    @Test
    fun sessions_in_an_unknown_format_version_are_skipped_entirely() {
        val root = store()
        session(root, "/work/alpha", "session-future", version = 1, events = userMsg("x", 1))
        assertTrue(DshTranscriptScanner.scan("/work/alpha", root).isEmpty())
    }

    @Test
    fun a_directory_with_no_transcript_file_is_ignored() {
        val root = store()
        root.resolve(DshPaths.projectKey("/work/alpha")).resolve("session-empty").createDirectories()
        assertTrue(DshTranscriptScanner.scan("/work/alpha", root).isEmpty())
    }

    @Test
    fun cwdsByNewest_reports_every_project_and_skips_the_no_cwd_bucket() {
        val root = store()
        session(root, "/work/alpha", "session-a")
        session(root, "/work/beta", "session-b")
        session(root, null, "session-nocwd") // sessions started outside a project have nothing to list

        val cwds = DshTranscriptScanner.cwdsByNewest(root)
        assertEquals(setOf("/work/alpha", "/work/beta"), cwds.keys)
        assertTrue(cwds.values.all { it > 0 })
    }

    @Test
    fun find_locates_a_session_by_id_and_a_missing_one_returns_null() {
        val root = store()
        session(root, "/work/alpha", "session-a", events = userMsg("hi", 1))

        val found = DshTranscriptScanner.find("session-a", "/work/alpha", root)
        assertNotNull(found)
        assertEquals("/work/alpha", found.header.cwd)
        // and it is findable WITHOUT the cwd hint too — the hint is only a search-order optimization
        assertNotNull(DshTranscriptScanner.find("session-a", null, root))
        assertNull(DshTranscriptScanner.find("session-missing", "/work/alpha", root))
    }

    @Test
    fun an_absent_store_lists_nothing_rather_than_throwing() {
        val missing = store().resolve("never-created")
        assertTrue(DshTranscriptScanner.scan("/work/alpha", missing).isEmpty())
        assertTrue(DshTranscriptScanner.cwdsByNewest(missing).isEmpty())
    }
}
