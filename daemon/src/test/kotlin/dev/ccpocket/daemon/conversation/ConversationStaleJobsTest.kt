package dev.ccpocket.daemon.conversation

import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.claude.StreamParser
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.BackgroundJobs
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.JobStatus
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.SessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The stale-job clock heuristic vs a LIVE agent (issue #105, second casualty path). A quiet backgrounded
 * task emits NO stream events between `task_started` and its completion `task_*` — a 20-minute build is
 * silent past STALE_JOB_MS, and clock-settling it KILLED dropped the conversation's reaper shield: the
 * idle reaper then destroyed the process tree, the still-running build with it, and the CLI's
 * auto-continuation turn (probed on 2.1.206: a completed bg task starts a new turn unprompted) never got
 * to run. While the agent process is alive its own task events are authoritative — the clock only settles
 * jobs whose event source is DEAD and can no longer report.
 */
class ConversationStaleJobsTest {

    private val init = """{"type":"system","subtype":"init","session_id":"s-stale","cwd":"/tmp","model":"claude-sonnet-5"}"""
    private val bgToolUse =
        """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"bg1","name":"Bash","input":{"command":"sleep 600 && make build","run_in_background":true}}]}}"""

    private class ScriptedBackend(private val script: Path, private val thenExit: Boolean) : AgentBackend {
        override val kind = AgentKind.CLAUDE
        override fun processBuilder(spec: AgentSpec): ProcessBuilder =
            if (thenExit) ProcessBuilder("sh", "-c", "cat '${script.absolutePathString()}'")
            else ProcessBuilder("sh", "-c", "cat '${script.absolutePathString()}'; sleep 30")
        override suspend fun attach(io: AgentIo, spec: AgentSpec) {}
        override suspend fun parse(line: String): List<AgentEvent> = StreamParser.parse(line)
        override suspend fun sendPrompt(text: String, images: List<ImageData>) {}
        override suspend fun interrupt() {}
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

    private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")

    private fun harness(
        thenExit: Boolean,
        until: (List<Frame>) -> Boolean,
        body: suspend (Conversation) -> Unit,
    ) = runBlocking {
        val script = Files.createTempDirectory("ccp-stale-fx").resolve("stream.jsonl")
            .apply { writeText(listOf(init, bgToolUse).joinToString("\n") + "\n") }
        val dir = Files.createTempDirectory("ccp-stale")
        val frames = ArrayList<Frame>()
        val scope = CoroutineScope(Dispatchers.Default)
        val convo = Conversation(
            convoId = "cStale", initialWorkdir = dir, initialMode = PermissionMode.DEFAULT,
            initialSink = { f -> synchronized(frames) { frames.add(f) } },
            parentScope = scope, backend = ScriptedBackend(script, thenExit),
        )
        try {
            convo.open(resumeId = null, model = null)
            convo.sendPrompt("kick off the build") // lazy start: launches the scripted process
            withTimeout(10_000) {
                while (!until(synchronized(frames) { frames.toList() })) delay(20)
            }
            body(convo)
        } finally {
            convo.close()
            scope.cancel()
        }
    }

    private fun sawRunningJob(fs: List<Frame>): Boolean =
        fs.filterIsInstance<BackgroundJobs>().any { b -> b.jobs.any { it.status == JobStatus.RUNNING } }

    @Test
    fun live_agent_keeps_its_quiet_background_job_off_the_stale_clock() {
        if (isWindows()) return // stubs run via sh/cat
        harness(thenExit = false, until = ::sawRunningJob) { convo ->
            delay(300) // the job is now "stale" on any tiny clock — but the agent process is alive
            assertFalse(convo.reapStaleJobs(staleMs = 1), "a live agent's bg job must never be clock-settled")
            assertTrue(convo.hasBackgroundWork(), "the reaper shield must hold while the agent tracks its own task")
        }
    }

    @Test
    fun dead_agent_stale_background_job_is_settled() {
        if (isWindows()) return
        // the case the heuristic exists for: the event source died — its completion can never arrive
        harness(thenExit = true, until = { fs -> sawRunningJob(fs) && fs.any { it is PocketError } }) { convo ->
            delay(300)
            assertTrue(convo.reapStaleJobs(staleMs = 1), "a dead agent's forever-RUNNING job must settle")
            assertFalse(convo.hasBackgroundWork(), "settled ghost job must release the reaper shield")
        }
    }
}
