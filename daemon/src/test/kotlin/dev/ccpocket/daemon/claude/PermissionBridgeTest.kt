package dev.ccpocket.daemon.claude

import dev.ccpocket.protocol.Decision
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PermissionVerdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PermissionBridgeTest {

    @Test
    fun default_asks_then_allow_writes_control_response() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val written = mutableListOf<String>()
        val emitted = mutableListOf<Frame>()
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, scope, { written += it }, { emitted += it }, mutableSetOf())

        b.onControlRequest(ClaudeEvent.ControlRequest("r1", "Bash", buildJsonObject { put("command", "echo hi") }))
        val ask = emitted.single()
        assertIs<PermissionAsk>(ask)
        assertEquals("r1", ask.askId)
        assertEquals("Bash", ask.tool)

        b.onVerdict(PermissionVerdict("c1", "r1", Decision.ALLOW))
        val resp = written.single()
        assertTrue("\"behavior\":\"allow\"" in resp, resp)
        assertTrue("\"request_id\":\"r1\"" in resp, resp)
        assertTrue("\"subtype\":\"success\"" in resp, resp)
        scope.cancel()
    }

    @Test
    fun deny_writes_deny_with_message() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val written = mutableListOf<String>()
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, scope, { written += it }, { }, mutableSetOf())

        b.onControlRequest(ClaudeEvent.ControlRequest("r2", "Bash", null))
        b.onVerdict(PermissionVerdict("c1", "r2", Decision.DENY, message = "nope"))
        val resp = written.single()
        assertTrue("\"behavior\":\"deny\"" in resp, resp)
        assertTrue("nope" in resp, resp)
        scope.cancel()
    }

    @Test
    fun bypass_mode_allows_without_asking() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val written = mutableListOf<String>()
        val emitted = mutableListOf<Frame>()
        val b = PermissionBridge("c1", PermissionMode.BYPASS_PERMISSIONS, scope, { written += it }, { emitted += it }, mutableSetOf())

        b.onControlRequest(ClaudeEvent.ControlRequest("r3", "Bash", null))
        assertTrue(emitted.isEmpty())
        assertTrue("\"behavior\":\"allow\"" in written.single())
        scope.cancel()
    }
}
