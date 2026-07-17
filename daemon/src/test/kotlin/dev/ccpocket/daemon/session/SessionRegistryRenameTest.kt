package dev.ccpocket.daemon.session

import dev.ccpocket.daemon.disk.LiveProcesses
import dev.ccpocket.daemon.disk.ProjectPaths
import dev.ccpocket.daemon.disk.TranscriptScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * issue #158: [SessionRegistry.renameSession] picks the writer by who holds the transcript —
 * these cover the DISK arm (idle append) and the refusal arms (external live writer / no
 * transcript / blank title). The live-convo arm is the [dev.ccpocket.daemon.claude.ClaudeBackend]
 * control_request, covered in ClaudeBackendTest.
 */
class SessionRegistryRenameTest {

    private val workdir = "/tmp/ccp-rename-reg"

    private fun registry(root: Path, probe: LiveProcesses.ExternalClaude): SessionRegistry =
        SessionRegistry(
            CoroutineScope(Dispatchers.Default),
            backends = emptyMap(),
            processProbe = { _, _ -> probe },
            projectsRoot = root,
        )

    /** A transcript for [workdir]'s dirKey under [root] — created AFTER the registry so its fresh
     *  mtime clears the restart-amnesia gate and the stubbed probe decides the verdict. */
    private fun seedTranscript(root: Path, sessionId: String): Path {
        val dir = Files.createDirectories(root.resolve(ProjectPaths.dirKey(workdir)))
        val f = dir.resolve("$sessionId.jsonl")
        f.writeText("""{"type":"user","message":{"role":"user","content":"hi"},"cwd":"$workdir"}""" + "\n")
        return f
    }

    @Test
    fun idle_transcript_gets_the_append_and_the_new_title() = runBlocking {
        val root = Files.createTempDirectory("ccp-reg-root")
        val reg = registry(root, probe = LiveProcesses.ExternalClaude.ABSENT)
        val f = seedTranscript(root, "sid-idle")

        assertNull(reg.renameSession(workdir, "sid-idle", "  Named by hand  "))

        val s = assertNotNull(TranscriptScanner.summarize(f))
        assertEquals("Named by hand", s.title, "trimmed title must win via the custom-title record")
    }

    @Test
    fun external_live_writer_is_refused_and_the_file_untouched() = runBlocking {
        val root = Files.createTempDirectory("ccp-reg-root")
        val reg = registry(root, probe = LiveProcesses.ExternalClaude.PRESENT)
        val f = seedTranscript(root, "sid-live") // fresh mtime + PRESENT probe = a terminal claude is writing

        val err = reg.renameSession(workdir, "sid-live", "New name")

        assertNotNull(err)
        assertTrue("live in another client" in err, err)
        assertFalse("custom-title" in f.readText(), "a refused rename must not append")
    }

    @Test
    fun missing_transcript_is_a_clear_error() = runBlocking {
        val root = Files.createTempDirectory("ccp-reg-root")
        val reg = registry(root, probe = LiveProcesses.ExternalClaude.ABSENT)

        val err = reg.renameSession(workdir, "sid-none", "x")

        assertNotNull(err)
        assertTrue("no transcript" in err, err)
    }

    @Test
    fun blank_title_is_rejected_before_any_io() = runBlocking {
        val root = Files.createTempDirectory("ccp-reg-root")
        val reg = registry(root, probe = LiveProcesses.ExternalClaude.ABSENT)

        assertEquals("title must not be empty", reg.renameSession(workdir, "sid-any", "   "))
    }
}
