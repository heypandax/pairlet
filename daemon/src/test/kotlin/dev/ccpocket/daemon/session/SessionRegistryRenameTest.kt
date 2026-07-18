package dev.ccpocket.daemon.session

import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentBackendFactory
import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.disk.LiveProcesses
import dev.ccpocket.daemon.disk.ProjectPaths
import dev.ccpocket.daemon.disk.TranscriptScanner
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
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

    /** Spawns a real (silent) process — no stdout means no init, so [Conversation.sessionId] stays null
     *  and the convo is findable only by its resume anchor. Records the CLI-arm rename call. */
    private class SilentSpawnBackend : AgentBackend {
        @Volatile var renameAsked: String? = null
        override val kind = AgentKind.CLAUDE
        override fun processBuilder(spec: AgentSpec): ProcessBuilder = ProcessBuilder("sh", "-c", "sleep 30")
        override suspend fun attach(io: AgentIo, spec: AgentSpec) {}
        override suspend fun parse(line: String): List<AgentEvent> = emptyList()
        override suspend fun sendPrompt(text: String, images: List<ImageData>) {}
        override suspend fun interrupt() {}
        override suspend fun renameSession(title: String): Boolean { renameAsked = title; return true }
        override suspend fun respondPermission(
            askId: String, allow: Boolean, remember: Boolean,
            originalInput: JsonObject?, updatedInput: String?, denyMessage: String?,
        ) {}
        override fun applySettings(mode: PermissionMode?, model: String?, effort: String?) = true
        override suspend fun onProcessEnded(sessionId: String?) {}
        override fun transcriptDir(workdir: String): Path = Path.of(workdir)
        override fun listSessions(workdir: String): List<SessionSummary> = emptyList()
        override fun replayHistory(workdir: String, sessionId: String): List<HistoryMessage> = emptyList()
        override fun resumeContextTokens(workdir: String, sessionId: String): Long? = null
    }

    @Test
    fun pre_init_spawned_convo_takes_the_cli_arm_not_a_disk_append() = runBlocking {
        // A take-over spawns EAGERLY, but the CLI's init (which carries the sessionId) hasn't landed yet —
        // in that window the convo is only findable by its resume anchor (the same shape open() reattaches
        // by). The old sessionId-only match fell through to "idle transcript" and appended under our own
        // child's pen; the rename must go THROUGH the live CLI instead.
        if (System.getProperty("os.name").lowercase().contains("win")) return@runBlocking // sh-based stub
        val root = Files.createTempDirectory("ccp-reg-root")
        val backend = SilentSpawnBackend()
        val scope = CoroutineScope(Dispatchers.Default)
        val reg = SessionRegistry(
            scope,
            backends = mapOf(AgentKind.CLAUDE to AgentBackendFactory { backend }),
            processProbe = { _, _ -> LiveProcesses.ExternalClaude.ABSENT }, // old code would call this + append
            projectsRoot = root,
        )
        val f = seedTranscript(root, "sid-pre")
        val before = f.readText()
        try {
            val convoId = reg.open(OpenSession(workdir = workdir, resumeId = "sid-pre", takeOver = true), { })
            assertTrue(convoId.isNotEmpty(), "the take-over open must spawn a conversation")

            assertNull(reg.renameSession(workdir, "sid-pre", "Named pre-init"), "the CLI arm acks the rename")

            assertEquals("Named pre-init", backend.renameAsked, "the rename must route through the live CLI")
            assertEquals(before, f.readText(), "no daemon-side append while our own child holds the file")
        } finally {
            reg.closeAll()
            scope.cancel()
        }
    }
}
