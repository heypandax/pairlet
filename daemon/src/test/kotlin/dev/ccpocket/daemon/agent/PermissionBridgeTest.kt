package dev.ccpocket.daemon.agent

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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The provider-neutral bridge: it translates a ControlRequest to a PermissionAsk and routes the verdict to
 *  the backend's [respond] callback. The wire format of that response is the backend's job (see ClaudeBackendTest). */
class PermissionBridgeTest {
    private data class Resp(val askId: String, val allow: Boolean, val remember: Boolean, val updated: String?, val deny: String?)

    @Test
    fun default_asks_then_allow_routes_to_respond() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val responses = mutableListOf<Resp>()
        val emitted = mutableListOf<Frame>()
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, scope, { emitted += it }, mutableSetOf(),
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) })

        b.onControlRequest(AgentEvent.ControlRequest("r1", "Bash", buildJsonObject { put("command", "echo hi") }))
        val ask = emitted.single()
        assertIs<PermissionAsk>(ask)
        assertEquals("r1", ask.askId)
        assertEquals("Bash", ask.tool)

        b.onVerdict(PermissionVerdict("c1", "r1", Decision.ALLOW, remember = true))
        val r = responses.single()
        assertEquals("r1", r.askId)
        assertTrue(r.allow)
        assertTrue(r.remember)
        scope.cancel()
    }

    @Test
    fun deny_routes_with_message() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val responses = mutableListOf<Resp>()
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, scope, { }, mutableSetOf(),
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) })

        b.onControlRequest(AgentEvent.ControlRequest("r2", "Bash", null))
        b.onVerdict(PermissionVerdict("c1", "r2", Decision.DENY, message = "nope"))
        val r = responses.single()
        assertFalse(r.allow)
        assertEquals("nope", r.deny)
        scope.cancel()
    }

    @Test
    fun bypass_mode_allows_without_asking() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val responses = mutableListOf<Resp>()
        val emitted = mutableListOf<Frame>()
        val b = PermissionBridge("c1", PermissionMode.BYPASS_PERMISSIONS, scope, { emitted += it }, mutableSetOf(),
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) })

        b.onControlRequest(AgentEvent.ControlRequest("r3", "Bash", null))
        assertTrue(emitted.isEmpty())
        assertTrue(responses.single().allow)
        scope.cancel()
    }

    @Test
    fun askUserQuestion_carries_questions_and_merges_answers_into_updatedInput() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val responses = mutableListOf<Resp>()
        val emitted = mutableListOf<Frame>()
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, scope, { emitted += it }, mutableSetOf(),
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) })

        val input = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"questions":[{"question":"Which color?","header":"Color","multiSelect":false,
                "options":[{"label":"Red","description":"r"},{"label":"Blue","description":"b"}]}]}""",
        ) as kotlinx.serialization.json.JsonObject
        b.onControlRequest(AgentEvent.ControlRequest("q1", "AskUserQuestion", input))

        val ask = emitted.single()
        assertIs<PermissionAsk>(ask)
        assertEquals("Which color?", ask.questions?.single()?.question) // phone gets the structured card
        assertEquals(listOf("Red", "Blue"), ask.questions?.single()?.options?.map { it.label })

        b.onVerdict(PermissionVerdict("c1", "q1", Decision.ALLOW, answers = mapOf("Which color?" to "Red")))
        val r = responses.single()
        assertTrue(r.allow)
        assertTrue(r.updated!!.contains(""""Which color?":"Red""""))  // answers merged into updatedInput
        assertTrue(r.updated!!.contains("questions"))                 // original input preserved
        scope.cancel()
    }

    @Test
    fun askUserQuestion_still_asks_under_bypass_and_ignores_remembered_rules() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val responses = mutableListOf<Resp>()
        val emitted = mutableListOf<Frame>()
        // a stale "always allow" for AskUserQuestion must not swallow questions either
        val rules = mutableSetOf("AskUserQuestion")
        val b = PermissionBridge("c1", PermissionMode.BYPASS_PERMISSIONS, scope, { emitted += it }, rules,
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) })

        b.onControlRequest(AgentEvent.ControlRequest("q2", "AskUserQuestion", null))
        assertEquals(1, emitted.size) // asked the phone despite bypass + remembered rule
        assertTrue(responses.isEmpty())
        scope.cancel()
    }

    @Test
    fun resurfacePending_reemits_open_ask_only_until_answered() = runBlocking {
        // issue #55: a reattaching phone (backgrounded when the live PermissionAsk fired — plan mode surfaces the
        // AskUserQuestion minutes after a premature `result`) must be re-shown the still-open card, and NOT one it
        // already answered. resurfacePending re-emits exactly the open asks, to the reattaching sink only.
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val responses = mutableListOf<Resp>()
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, scope, { }, mutableSetOf(),
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) })

        val input = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"questions":[{"question":"Which color?","header":"Color","multiSelect":false,
                "options":[{"label":"Red","description":"r"},{"label":"Blue","description":"b"}]}]}""",
        ) as kotlinx.serialization.json.JsonObject
        b.onControlRequest(AgentEvent.ControlRequest("q1", "AskUserQuestion", input))
        assertTrue(b.hasPending()) // reaper must spare a conversation blocked on this

        // reattach #1: the card is re-surfaced verbatim (same askId + structured questions) to the new sink
        val reattached = mutableListOf<Frame>()
        b.resurfacePending { reattached += it }
        val re = reattached.single()
        assertIs<PermissionAsk>(re)
        assertEquals("q1", re.askId)
        assertEquals("Which color?", re.questions?.single()?.question)

        // once answered it leaves [pending] — a later reattach must NOT re-show a card the user already handled
        b.onVerdict(PermissionVerdict("c1", "q1", Decision.ALLOW, answers = mapOf("Which color?" to "Red")))
        assertFalse(b.hasPending())
        val reattachedAgain = mutableListOf<Frame>()
        b.resurfacePending { reattachedAgain += it }
        assertTrue(reattachedAgain.isEmpty())
        scope.cancel()
    }

    @Test
    fun remembered_rule_auto_allows_next_matching_request() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val responses = mutableListOf<Resp>()
        val emitted = mutableListOf<Frame>()
        val rules = mutableSetOf<String>()
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, scope, { emitted += it }, rules,
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) })

        // first "git status" → ask, allow+remember adds the "git status" rule
        b.onControlRequest(AgentEvent.ControlRequest("r1", "Bash", buildJsonObject { put("command", "git status") }))
        b.onVerdict(PermissionVerdict("c1", "r1", Decision.ALLOW, remember = true))
        // second identical command → no new ask, auto-allowed
        b.onControlRequest(AgentEvent.ControlRequest("r2", "Bash", buildJsonObject { put("command", "git status -s") }))
        assertEquals(1, emitted.size) // only the first asked
        assertTrue(responses.last().allow)
        scope.cancel()
    }
}
