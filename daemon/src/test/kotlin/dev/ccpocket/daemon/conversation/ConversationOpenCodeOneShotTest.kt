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

    private class OneShotBackend(private val scripts: List<List<String>>) : AgentBackend {
        val specs = CopyOnWriteArrayList<AgentSpec>()
        override val kind = AgentKind.OPENCODE
        override val processMode = AgentProcessMode.ONE_SHOT_TURN
        override val promptDelivery = AgentPromptDelivery.INITIAL_ARG_ONE_SHOT

        override fun processBuilder(spec: AgentSpec): ProcessBuilder {
            specs.add(spec)
            val lines = scripts[minOf(specs.size, scripts.size) - 1]
            val f = Files.createTempDirectory("ccp-opencode-one-shot").resolve("stream.jsonl")
                .apply { writeText(lines.joinToString("\n") + "\n") }
            return ProcessBuilder("sh", "-c", "cat '${f.absolutePathString()}'")
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
