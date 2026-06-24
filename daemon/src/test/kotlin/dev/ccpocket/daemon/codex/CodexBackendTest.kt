package dev.ccpocket.daemon.codex

import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.protocol.PermissionMode
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Drives [CodexBackend] with synthetic app-server JSON-RPC lines (no real `codex` binary) to lock down the
 * handshake sequencing, the buffered-first-turn race fix, delta streaming, the approval round-trip, and the
 * Claude-mode → Codex-policy mapping. Request ids are deterministic (idSeq starts at 1).
 */
class CodexBackendTest {
    private fun initResponse(id: Int) =
        """{"id":$id,"result":{"userAgent":"x","codexHome":"/h","platformFamily":"unix","platformOs":"macos"}}"""

    private fun threadStartResponse(id: Int, threadId: String) =
        """{"id":$id,"result":{"thread":{"id":"$threadId","sessionId":"sess-1"},"model":"gpt-5.1-codex"}}"""

    /** attach + handshake to a live thread "thr-1". Leaves `w` holding every line the backend wrote. */
    private suspend fun ready(w: MutableList<String>, mode: PermissionMode = PermissionMode.DEFAULT): CodexBackend {
        val b = CodexBackend(null)
        b.attach(AgentIo(writeLine = { w += it }, emit = {}), AgentSpec(Path.of("/repo"), mode = mode))
        b.parse(initResponse(1))          // → initialized + thread/start (id 2)
        b.parse(threadStartResponse(2, "thr-1"))
        return b
    }

    @Test
    fun attach_sends_initialize_then_initialized_and_thread_start() = runBlocking {
        val w = mutableListOf<String>()
        ready(w)
        assertTrue("\"method\":\"initialize\"" in w[0], w[0])
        assertTrue(w.any { "\"method\":\"initialized\"" in it })
        assertTrue(w.any { "\"method\":\"thread/start\"" in it })
    }

    @Test
    fun first_prompt_buffers_until_thread_ready_then_turn_start() = runBlocking {
        val w = mutableListOf<String>()
        val b = CodexBackend(null)
        b.attach(AgentIo({ w += it }, {}), AgentSpec(Path.of("/repo"), mode = PermissionMode.DEFAULT))
        b.sendPrompt("hello world", emptyList())
        assertTrue(w.none { "turn/start" in it }, "turn must not start before the thread is ready")
        b.parse(initResponse(1))
        val ev = b.parse(threadStartResponse(2, "thr-1"))
        assertIs<AgentEvent.SessionInit>(ev.single())
        assertEquals("thr-1", (ev.single() as AgentEvent.SessionInit).sessionId)
        val turn = w.last { "turn/start" in it }
        assertTrue("hello world" in turn, turn)
        assertTrue("\"threadId\":\"thr-1\"" in turn, turn)
        // DEFAULT = the "Balanced" Codex preset → ask when needed, edits inside the workspace
        assertTrue("\"approvalPolicy\":\"on-request\"" in turn, turn)
        assertTrue("\"workspaceWrite\"" in turn, turn)
    }

    @Test
    fun agent_message_delta_streams_as_text() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        val ev = b.parse("""{"method":"item/agentMessage/delta","params":{"threadId":"thr-1","turnId":"t1","itemId":"i1","delta":"Hi"}}""")
        assertEquals(AgentEvent.AssistantText("Hi"), ev.single())
    }

    @Test
    fun completed_agent_message_not_duplicated_after_deltas() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        b.parse("""{"method":"item/agentMessage/delta","params":{"itemId":"i1","delta":"Hi"}}""")
        val ev = b.parse("""{"method":"item/completed","params":{"item":{"type":"agentMessage","id":"i1","text":"Hi there"}}}""")
        assertTrue(ev.isEmpty(), "final must not re-emit once deltas streamed the message") // text was already streamed
    }

    @Test
    fun command_approval_becomes_control_request_and_decision_is_written() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        val ev = b.parse(
            """{"id":"req-7","method":"item/commandExecution/requestApproval","params":{"itemId":"i2","startedAtMs":1,"threadId":"thr-1","turnId":"t1","command":"rm -rf build","cwd":"/repo"}}""",
        )
        val cr = ev.single()
        assertIs<AgentEvent.ControlRequest>(cr)
        assertEquals("req-7", cr.requestId)
        assertEquals("Bash", cr.toolName) // synthesized so ToolMetadata gives a "Run command" title + danger flag

        b.respondPermission("req-7", allow = true, remember = false, originalInput = null, updatedInput = null, denyMessage = null)
        val resp = w.last()
        assertTrue("\"id\":\"req-7\"" in resp, resp) // string id echoed verbatim
        assertTrue("\"decision\":\"accept\"" in resp, resp)
    }

    @Test
    fun remember_maps_to_accept_for_session() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        b.parse("""{"id":9,"method":"item/commandExecution/requestApproval","params":{"itemId":"i3","startedAtMs":1,"threadId":"thr-1","turnId":"t1","command":"ls"}}""")
        b.respondPermission("9", allow = true, remember = true, originalInput = null, updatedInput = null, denyMessage = null)
        val resp = w.last()
        assertTrue("\"id\":9" in resp, resp) // integer id echoed as integer
        assertTrue("\"decision\":\"acceptForSession\"" in resp, resp)
    }

    @Test
    fun deny_writes_decline() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        b.parse("""{"id":"a1","method":"item/commandExecution/requestApproval","params":{"itemId":"i4","startedAtMs":1,"threadId":"thr-1","turnId":"t1","command":"curl evil"}}""")
        b.respondPermission("a1", allow = false, remember = false, originalInput = null, updatedInput = null, denyMessage = "no")
        assertTrue("\"decision\":\"decline\"" in w.last(), w.last())
    }

    @Test
    fun token_usage_then_turn_completed_emits_turn_result() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        b.parse("""{"method":"thread/tokenUsage/updated","params":{"threadId":"thr-1","tokenUsage":{"total":{"inputTokens":10,"outputTokens":5,"cachedInputTokens":2}}}}""")
        val ev = b.parse("""{"method":"turn/completed","params":{"threadId":"thr-1","turn":{"id":"t1","status":"completed"}}}""")
        val tr = ev.single()
        assertIs<AgentEvent.TurnResult>(tr)
        assertEquals(10, tr.inputTokens)
        assertEquals(5, tr.outputTokens)
        assertEquals(2, tr.cacheReadInputTokens)
        assertTrue(!tr.isError)
    }

    @Test
    fun command_execution_started_surfaces_tool_use() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        val ev = b.parse("""{"method":"item/started","params":{"item":{"type":"commandExecution","id":"c1","command":"go build","cwd":"/repo","status":"inProgress"}}}""")
        val tu = ev.single()
        assertIs<AgentEvent.AssistantToolUse>(tu)
        assertEquals("Bash", tu.name)
    }

    @Test
    fun plan_mode_maps_to_read_only_and_bypass_to_never() = runBlocking {
        val wPlan = mutableListOf<String>()
        ready(wPlan, PermissionMode.PLAN).sendPrompt("x", emptyList())
        val planTurn = wPlan.last { "turn/start" in it }
        assertTrue("\"readOnly\"" in planTurn, planTurn)

        val wBypass = mutableListOf<String>()
        ready(wBypass, PermissionMode.BYPASS_PERMISSIONS).sendPrompt("x", emptyList())
        val bypassTurn = wBypass.last { "turn/start" in it }
        assertTrue("\"approvalPolicy\":\"never\"" in bypassTurn, bypassTurn)
        assertTrue("\"dangerFullAccess\"" in bypassTurn, bypassTurn)
    }

    @Test
    fun file_change_approval_carries_the_diff() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        // the fileChange item (with its diff) arrives before the approval request references it by itemId
        b.parse("""{"method":"item/started","params":{"item":{"type":"fileChange","id":"f1","status":"inProgress","changes":[{"path":"src/A.kt","diff":"-old line\n+new line"}]}}}""")
        val ev = b.parse("""{"id":"ap1","method":"item/fileChange/requestApproval","params":{"itemId":"f1","startedAtMs":1,"threadId":"thr-1","turnId":"t1"}}""")
        val cr = ev.single()
        assertIs<AgentEvent.ControlRequest>(cr)
        assertEquals("Edit", cr.toolName)
        assertTrue("+new line" in (cr.diff ?: ""), cr.diff ?: "<null>") // diff is a typed field, for the phone's diff view
        assertTrue("src/A.kt" in cr.input.toString(), cr.input.toString())
    }
}
