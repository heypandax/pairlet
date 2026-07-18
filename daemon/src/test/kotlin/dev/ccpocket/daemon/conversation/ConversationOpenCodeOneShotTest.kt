package dev.ccpocket.daemon.conversation

import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentProcessMode
import dev.ccpocket.daemon.agent.AgentPromptDelivery
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.opencode.OpenCodeStreamParser
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.PromptAck
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.TurnDone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.absolutePathString
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationOpenCodeOneShotTest {
    private fun win() = System.getProperty("os.name").lowercase().contains("win")

    /** [shell] overrides the per-launch shell command (index, stream file) — lets a test emit the first
     *  line then stall (long-turn shape) or hang with zero output (startup-hang shape). Default: cat. */
    private class OneShotBackend(
        private val scripts: List<List<String>>,
        private val shell: ((Int, Path) -> String)? = null,
    ) : AgentBackend {
        val specs = CopyOnWriteArrayList<AgentSpec>()
        override val kind = AgentKind.OPENCODE
        override val processMode = AgentProcessMode.ONE_SHOT_TURN
        override val promptDelivery = AgentPromptDelivery.INITIAL_ARG_ONE_SHOT

        override fun processBuilder(spec: AgentSpec): ProcessBuilder {
            specs.add(spec)
            val idx = minOf(specs.size, scripts.size) - 1
            val lines = scripts[idx]
            val f = Files.createTempDirectory("ccp-opencode-one-shot").resolve("stream.jsonl")
                .apply { writeText(lines.joinToString("\n") + "\n") }
            val cmd = shell?.invoke(idx, f) ?: "cat '${f.absolutePathString()}'"
            return ProcessBuilder("sh", "-c", cmd)
        }

        override suspend fun attach(io: AgentIo, spec: AgentSpec) {}
        override suspend fun parse(line: String): List<AgentEvent> = OpenCodeStreamParser.parse(line)
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

    private fun okTurn(sessionId: String, text: String = "ok") = listOf(
        """{"type":"step_start","sessionID":"$sessionId","part":{"sessionID":"$sessionId","type":"step-start"}}""",
        """{"type":"text","sessionID":"$sessionId","part":{"type":"text","text":"$text"}}""",
        """{"type":"step_finish","sessionID":"$sessionId","part":{"reason":"stop","type":"step-finish","tokens":{"input":1,"output":1}}}""",
    )

    private fun startedOnly(sessionId: String) = listOf(
        """{"type":"step_start","sessionID":"$sessionId","part":{"sessionID":"$sessionId","type":"step-start"}}""",
    )

    private suspend fun await(cond: () -> Boolean) {
        withTimeout(8_000) { while (!cond()) delay(20) }
    }

    @Test
    fun clean_one_shot_exit_after_turn_result_is_not_process_exited() = runBlocking {
        if (win()) return@runBlocking
        val frames = CopyOnWriteArrayList<Frame>()
        val scope = CoroutineScope(Dispatchers.Default)
        val backend = OneShotBackend(listOf(okTurn("ses_open_1")))
        val convo = Conversation("cOpen", Files.createTempDirectory("ccp-open"), PermissionMode.DEFAULT, { frames.add(it) }, scope, backend)
        try {
            convo.open(resumeId = null, model = null)
            convo.sendPrompt("hello", promptId = "p1")
            await { frames.any { it is TurnDone } }
            delay(300)
            assertFalse(frames.any { it is PocketError && it.code == "process_exited" }, frames.toString())
            assertEquals(1, frames.count { it is PromptAck && it.promptId == "p1" })
        } finally {
            convo.close()
            scope.cancel()
        }
    }

    @Test
    fun next_prompt_relaunches_with_real_opencode_session_id() = runBlocking {
        if (win()) return@runBlocking
        val frames = CopyOnWriteArrayList<Frame>()
        val scope = CoroutineScope(Dispatchers.Default)
        val backend = OneShotBackend(listOf(okTurn("ses_open_1"), okTurn("ses_open_1", "again")))
        val convo = Conversation("cOpen", Files.createTempDirectory("ccp-open"), PermissionMode.DEFAULT, { frames.add(it) }, scope, backend)
        try {
            convo.open(resumeId = null, model = null)
            convo.sendPrompt("first", promptId = "p1")
            await { frames.count { it is TurnDone } >= 1 }
            delay(300)
            convo.sendPrompt("second", promptId = "p2")
            await { frames.count { it is TurnDone } >= 2 }
            assertEquals(null, backend.specs[0].resumeId)
            assertEquals("ses_open_1", backend.specs[1].resumeId)
        } finally {
            convo.close()
            scope.cancel()
        }
    }

    @Test
    fun completed_one_shot_prompt_id_retry_is_reacked_without_rerun() = runBlocking {
        if (win()) return@runBlocking
        val frames = CopyOnWriteArrayList<Frame>()
        val scope = CoroutineScope(Dispatchers.Default)
        val backend = OneShotBackend(listOf(okTurn("ses_open_1")))
        val convo = Conversation("cOpen", Files.createTempDirectory("ccp-open"), PermissionMode.DEFAULT, { frames.add(it) }, scope, backend)
        try {
            convo.open(resumeId = null, model = null)
            convo.sendPrompt("hello", promptId = "p1")
            await { frames.any { it is TurnDone } }
            delay(300)
            convo.sendPrompt("hello", promptId = "p1")
            await { frames.count { it is PromptAck && it.promptId == "p1" } >= 2 }
            assertEquals(1, backend.specs.size)
        } finally {
            convo.close()
            scope.cancel()
        }
    }

    /** Watchdog regression (review P0): a turn STREAMING past the startup window must never be killed —
     *  the guard is startup-only (zero stdout), not a turn-length cap. */
    @Test
    fun long_turn_streaming_past_watchdog_window_is_not_killed() = runBlocking {
        if (win()) return@runBlocking
        System.setProperty(Conversation.OPENCODE_WATCHDOG_PROP, "300")
        try {
            val frames = CopyOnWriteArrayList<Frame>()
            val scope = CoroutineScope(Dispatchers.Default)
            // first line (step_start) immediately, the rest well after the 300ms watchdog window
            val backend = OneShotBackend(listOf(okTurn("ses_open_1"))) { _, f ->
                "head -n 1 '${f.absolutePathString()}'; sleep 1.2; tail -n +2 '${f.absolutePathString()}'"
            }
            val convo = Conversation("cOpen", Files.createTempDirectory("ccp-open"), PermissionMode.DEFAULT, { frames.add(it) }, scope, backend)
            try {
                convo.open(resumeId = null, model = null)
                convo.sendPrompt("hello", promptId = "p1")
                await { frames.any { it is TurnDone } }
                assertFalse(frames.any { it is PocketError && it.code == "opencode_startup_timeout" }, frames.toString())
            } finally {
                convo.close()
                scope.cancel()
            }
        } finally {
            System.clearProperty(Conversation.OPENCODE_WATCHDOG_PROP)
        }
    }

    /** The hang the watchdog exists for: a process that is alive but never prints gets killed + reported. */
    @Test
    fun zero_stdout_hang_is_killed_by_watchdog() = runBlocking {
        if (win()) return@runBlocking
        System.setProperty(Conversation.OPENCODE_WATCHDOG_PROP, "300")
        try {
            val frames = CopyOnWriteArrayList<Frame>()
            val scope = CoroutineScope(Dispatchers.Default)
            val backend = OneShotBackend(listOf(okTurn("ses_open_1"))) { _, _ -> "sleep 30" }
            val convo = Conversation("cOpen", Files.createTempDirectory("ccp-open"), PermissionMode.DEFAULT, { frames.add(it) }, scope, backend)
            try {
                convo.open(resumeId = null, model = null)
                convo.sendPrompt("hello", promptId = "p1")
                await { frames.any { it is PocketError && it.code == "opencode_startup_timeout" } }
                assertTrue(frames.any { it is PocketError && it.code == "opencode_startup_timeout" })
            } finally {
                convo.close()
                scope.cancel()
            }
        } finally {
            System.clearProperty(Conversation.OPENCODE_WATCHDOG_PROP)
        }
    }

    /** Cold-resume regression (review P0): tapping a DISK session (openedResumeId set, no live sessionId)
     *  must resume that opencode session — not silently fork a fresh one. */
    @Test
    fun cold_resume_anchors_on_opened_resume_id() = runBlocking {
        if (win()) return@runBlocking
        val frames = CopyOnWriteArrayList<Frame>()
        val scope = CoroutineScope(Dispatchers.Default)
        val backend = OneShotBackend(listOf(okTurn("ses_disk_1")))
        val convo = Conversation("cOpen", Files.createTempDirectory("ccp-open"), PermissionMode.DEFAULT, { frames.add(it) }, scope, backend)
        try {
            convo.open(resumeId = "ses_disk_1", model = null)
            convo.sendPrompt("hello again", promptId = "p1")
            await { frames.any { it is TurnDone } }
            assertEquals("ses_disk_1", backend.specs[0].resumeId)
            assertEquals(false, backend.specs[0].forkSession)
        } finally {
            convo.close()
            scope.cancel()
        }
    }

    /** Mid-turn sends queue (same receipt contract as the stdin mid-turn queue) and drain into the next
     *  one-shot spawn after the running turn completes — never bounced with an error. */
    @Test
    fun midturn_prompt_queues_and_runs_after_current_turn() = runBlocking {
        if (win()) return@runBlocking
        val frames = CopyOnWriteArrayList<Frame>()
        val scope = CoroutineScope(Dispatchers.Default)
        val backend = OneShotBackend(listOf(okTurn("ses_open_1"), okTurn("ses_open_1", "second answer"))) { idx, f ->
            // first launch stalls mid-turn so the second prompt lands while it runs; later launches just cat
            if (idx == 0) "head -n 1 '${f.absolutePathString()}'; sleep 1.2; tail -n +2 '${f.absolutePathString()}'"
            else "cat '${f.absolutePathString()}'"
        }
        val convo = Conversation("cOpen", Files.createTempDirectory("ccp-open"), PermissionMode.DEFAULT, { frames.add(it) }, scope, backend)
        try {
            convo.open(resumeId = null, model = null)
            convo.sendPrompt("first", promptId = "p1")
            await { frames.any { it is PromptAck && it.promptId == "p1" } }
            convo.sendPrompt("second", promptId = "p2") // lands mid-turn (first launch is still sleeping)
            await { frames.count { it is TurnDone } >= 2 }
            assertFalse(frames.any { it is PocketError }, frames.toString())
            assertEquals(1, frames.count { it is PromptAck && it.promptId == "p2" })
            assertEquals(2, backend.specs.size)
            assertEquals("second", backend.specs[1].initialPrompt)
            assertEquals("ses_open_1", backend.specs[1].resumeId) // queued turn continues the SAME session
        } finally {
            convo.close()
            scope.cancel()
        }
    }

    @Test
    fun one_shot_exit_without_turn_result_still_surfaces_error() = runBlocking {
        if (win()) return@runBlocking
        val frames = CopyOnWriteArrayList<Frame>()
        val scope = CoroutineScope(Dispatchers.Default)
        val backend = OneShotBackend(listOf(startedOnly("ses_open_1")))
        val convo = Conversation("cOpen", Files.createTempDirectory("ccp-open"), PermissionMode.DEFAULT, { frames.add(it) }, scope, backend)
        try {
            convo.open(resumeId = null, model = null)
            convo.sendPrompt("hello", promptId = "p1")
            await { frames.any { it is PocketError && it.code == "process_exited" } }
            assertTrue(frames.any { it is PocketError && it.code == "process_exited" })
        } finally {
            convo.close()
            scope.cancel()
        }
    }
}
