package dev.ccpocket.daemon.claude

import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path
import kotlin.test.Test
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
}
