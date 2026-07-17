package dev.ccpocket.daemon.claude

import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Claude wire format produced by the backend: control_response (allow/deny), the user turn frame, and
 *  the interrupt control_request. The binary path is never executed here — only the encoders are exercised. */
class ClaudeBackendTest {
    private fun backendWithCapture(written: MutableList<String>): ClaudeBackend =
        ClaudeBackend(claudeBin = "/nonexistent/claude").also {
            runBlocking { it.attach(AgentIo(writeLine = { line -> written += line }, emit = {}), AgentSpec(Path.of("/x"))) }
        }

    @Test
    fun allow_writes_control_response_allow() = runBlocking {
        val written = mutableListOf<String>()
        val b = backendWithCapture(written)
        b.respondPermission("r1", allow = true, remember = false, originalInput = buildJsonObject { put("command", "echo hi") }, updatedInput = null, denyMessage = null)
        val resp = written.single()
        assertTrue("\"behavior\":\"allow\"" in resp, resp)
        assertTrue("\"request_id\":\"r1\"" in resp, resp)
        assertTrue("\"subtype\":\"success\"" in resp, resp)
    }

    @Test
    fun deny_writes_control_response_deny_with_message() = runBlocking {
        val written = mutableListOf<String>()
        val b = backendWithCapture(written)
        b.respondPermission("r2", allow = false, remember = false, originalInput = null, updatedInput = null, denyMessage = "nope")
        val resp = written.single()
        assertTrue("\"behavior\":\"deny\"" in resp, resp)
        assertTrue("nope" in resp, resp)
    }

    @Test
    fun send_prompt_writes_user_frame() = runBlocking {
        val written = mutableListOf<String>()
        val b = backendWithCapture(written)
        b.sendPrompt("hello", emptyList())
        val frame = written.single()
        assertTrue("\"type\":\"user\"" in frame, frame)
        assertTrue("hello" in frame, frame)
    }

    @Test
    fun interrupt_writes_control_request_interrupt() = runBlocking {
        val written = mutableListOf<String>()
        val b = backendWithCapture(written)
        b.interrupt()
        val frame = written.single()
        assertTrue("\"type\":\"control_request\"" in frame, frame)
        assertTrue("\"subtype\":\"interrupt\"" in frame, frame)
    }

    // ── session rename over the live wire (issue #158): probed on 2.1.210 — the CLI appends its own
    //    custom-title record, then acks {"subtype":"success"} on the SAME request_id ─────────────────

    @Test
    fun rename_session_writes_the_control_request_and_a_success_ack_returns_true() = runBlocking {
        val written = mutableListOf<String>()
        val b = backendWithCapture(written)
        // UNDISPATCHED: runs single-threaded up to the ack await, so `written` already has the frame
        val result = async(start = CoroutineStart.UNDISPATCHED) { b.renameSession("Auth refactor") }

        val frame = written.single()
        assertTrue("\"type\":\"control_request\"" in frame, frame)
        assertTrue("\"subtype\":\"rename_session\"" in frame, frame)
        assertTrue("\"title\":\"Auth refactor\"" in frame, frame)
        val reqId = Regex("\"request_id\":\"([^\"]+)\"").find(frame)!!.groupValues[1]

        // the CLI's ack arrives on the stdout pump — parse() settles the waiter, StreamParser stays quiet
        b.parse("""{"type":"control_response","response":{"subtype":"success","request_id":"$reqId"}}""")
        assertTrue(result.await(), "a success ack must complete the rename true")
    }

    @Test
    fun rename_session_rejection_returns_false() = runBlocking {
        val written = mutableListOf<String>()
        val b = backendWithCapture(written)
        val result = async(start = CoroutineStart.UNDISPATCHED) { b.renameSession("x") }

        val reqId = Regex("\"request_id\":\"([^\"]+)\"").find(written.single())!!.groupValues[1]
        // an older CLI without the subtype answers an error ack — the rename must fail honestly
        b.parse("""{"type":"control_response","response":{"subtype":"error","request_id":"$reqId","error":"rename_session is not supported in this context"}}""")
        assertFalse(result.await())
    }

    @Test
    fun rename_session_without_live_io_returns_false() = runBlocking {
        val b = ClaudeBackend(claudeBin = "/nonexistent/claude") // never attached — no process
        assertFalse(b.renameSession("x"))
    }

    @Test
    fun relaunch_fails_an_inflight_rename_instead_of_stranding_it() = runBlocking {
        val written = mutableListOf<String>()
        val b = backendWithCapture(written)
        val result = async(start = CoroutineStart.UNDISPATCHED) { b.renameSession("x") }
        // the process relaunches before the ack — attach() must fail the orphaned waiter promptly
        b.attach(AgentIo(writeLine = { }, emit = {}), AgentSpec(Path.of("/x")))
        assertFalse(result.await())
    }
}
