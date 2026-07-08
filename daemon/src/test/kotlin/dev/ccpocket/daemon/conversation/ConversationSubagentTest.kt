package dev.ccpocket.daemon.conversation

import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.claude.StreamParser
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.StreamPiece
import dev.ccpocket.protocol.ToolEvent
import dev.ccpocket.protocol.ToolPhase
import kotlinx.coroutines.CoroutineScope
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end pump check for the sub-agent card lifecycle (issue #77): a stub backend whose "agent
 * process" is `cat` over a recorded stream-json fixture (the shapes probed off claude 2.1.204 on
 * 07-08), parsed by the REAL StreamParser and pumped through the REAL Conversation. Asserts the
 * ToolEvents the phone builds its Task card from: tagged START/inner-progress/RESULT-with-report,
 * and that the sub-agent's inner monologue never leaks into the main chat.
 */
class ConversationSubagentTest {

    // the wire shapes the probe recorded: main-chain Agent tool_use → sub-agent internals tagged with
    // parent_tool_use_id (its text, its Bash call, its Bash result) → the main-chain tool_result whose
    // content is the report + the CLI's "agentId: …" continuation plumbing → the turn result
    private val fixture = listOf(
        """{"type":"system","subtype":"init","session_id":"s77","cwd":"/tmp","model":"claude-sonnet-5"}""",
        """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"a1","name":"Agent","input":{"description":"add two numbers","subagent_type":"general-purpose","prompt":"add them","run_in_background":false}}]}}""",
        """{"type":"assistant","parent_tool_use_id":"a1","message":{"content":[{"type":"text","text":"inner monologue"}]}}""",
        """{"type":"assistant","parent_tool_use_id":"a1","message":{"content":[{"type":"tool_use","id":"b1","name":"Bash","input":{"command":"expr 2 + 3"}}]}}""",
        """{"type":"user","parent_tool_use_id":"a1","message":{"content":[{"type":"tool_result","tool_use_id":"b1","content":"5"}]}}""",
        """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"a1","content":[{"type":"text","text":"5"},{"type":"text","text":"agentId: a05 (use SendMessage)"}]}]}}""",
        """{"type":"assistant","message":{"content":[{"type":"text","text":"the answer is 5"}]}}""",
        """{"type":"result","subtype":"success","is_error":false,"result":"the answer is 5","usage":{"input_tokens":10,"output_tokens":5}}""",
    )

    private class StubBackend(private val script: Path) : AgentBackend {
        override val kind = AgentKind.CLAUDE
        override fun processBuilder(spec: AgentSpec) = ProcessBuilder("cat", script.absolutePathString())
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

    @Test
    fun subagent_run_streams_grouped_tool_events_and_no_inner_text() = runBlocking {
        if (System.getProperty("os.name").lowercase().contains("win")) return@runBlocking // fixture runs via `cat`
        val dir = Files.createTempDirectory("ccp-convo")
        val script = dir.resolve("stream.jsonl").apply { writeText(fixture.joinToString("\n") + "\n") }
        val frames = ArrayList<Frame>()
        val scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val convo = Conversation(
            convoId = "c77", initialWorkdir = dir, initialMode = PermissionMode.DEFAULT,
            initialSink = { f -> synchronized(frames) { frames.add(f) } },
            parentScope = scope, backend = StubBackend(script),
        )
        try {
            // takeOver spawns eagerly — the `cat` process replays the fixture through the real pump
            convo.open(resumeId = null, model = null, takeOver = true)
            withTimeout(10_000) {
                while (synchronized(frames) { frames.none { it is dev.ccpocket.protocol.TurnDone } }) delay(20)
            }
        } finally {
            convo.close()
            scope.cancel()
        }

        val tools = synchronized(frames) { frames.filterIsInstance<ToolEvent>() }
        // ① the Agent card: START with its tool_use id and the readable "<type>: <description>" label
        val start = tools.first { it.tool == "Agent" && it.phase == ToolPhase.START }
        assertEquals("a1", start.toolUseId)
        assertEquals(null, start.parentToolUseId)
        assertEquals("general-purpose: add two numbers", start.inputPreview)
        // ② the inner Bash call is parent-tagged (the client folds it into the card as progress)
        val inner = tools.first { it.tool == "Bash" }
        assertEquals("a1", inner.parentToolUseId)
        // ③ RESULT patches the card: ok + the report, with the agentId plumbing tail stripped
        val result = tools.first { it.phase == ToolPhase.RESULT }
        assertEquals("Agent", result.tool)
        assertEquals("a1", result.toolUseId)
        assertEquals(true, result.ok)
        assertEquals("5", result.output)
        assertEquals(1, tools.count { it.phase == ToolPhase.RESULT }) // plain tools stay START-only
        // ④ the sub-agent's inner monologue never renders as the MAIN agent speaking
        val texts = synchronized(frames) {
            frames.filterIsInstance<AssistantChunk>().map { it.piece }.filterIsInstance<StreamPiece.Text>().map { it.text }
        }
        assertTrue(texts.none { "inner monologue" in it }, texts.toString())
        assertTrue(texts.any { "the answer is 5" in it }, texts.toString())
    }
}
