package dev.ccpocket.daemon.zcode

import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.PermissionMode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Exact envelopes/events captured from the official ZCode 3.7.6 app-server probe. */
class ZCodeBackendTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun idOf(line: String): String = (json.parseToJsonElement(line) as JsonObject)["id"].toString().trim('"')

    /** Matches the full `"method":"x"` token: `session/setMode` is a prefix of `session/setModel`. */
    private fun List<String>.withMethod(method: String): String = first { "\"method\":\"$method\"" in it }
    private fun List<String>.hasMethod(method: String): Boolean = any { "\"method\":\"$method\"" in it }
    private fun List<String>.methods(): List<String> =
        mapNotNull { (json.parseToJsonElement(it) as JsonObject)["method"]?.toString()?.trim('"') }

    private suspend fun ready(writes: MutableList<String>): ZCodeBackend {
        val backend = ZCodeBackend(null, executable = { Path.of("/fake/zcode") })
        backend.attach(AgentIo({ writes += it }, {}), AgentSpec(Path.of("/repo")))
        backend.parse(
            """{"id":"ccp-1","result":{"session":{"sessionId":"sess_1","model":{"providerId":"zai","modelId":"glm-5"},"workspace":{"workspacePath":"/repo","workspaceKey":"/repo"}}}}""",
        )
        return backend
    }

    @Test
    fun `strict protocol omits jsonrpc and answers runtime preferences`() = runBlocking {
        val writes = mutableListOf<String>()
        val backend = ZCodeBackend(null, executable = { Path.of("/fake/zcode") })
        backend.attach(AgentIo({ writes += it }, {}), AgentSpec(Path.of("/repo")))
        assertTrue(writes.first().contains("\"method\":\"session/create\""))
        assertFalse(writes.first().contains("jsonrpc"))
        backend.parse(
            """{"id":"server-1","method":"session/requestRuntimePreferences","params":{"sessionId":"sess_1","scope":"runtime-materialization"}}""",
        )
        val reply = json.parseToJsonElement(writes.last()) as JsonObject
        assertEquals("preflight-v1", ((reply["result"] as JsonObject)["modelContextBudgetStrategy"]).toString().trim('"'))
        assertFalse(writes.last().contains("jsonrpc"))
    }

    @Test
    fun `new session validates explicit effort against the create snapshot before setting it`() = runBlocking {
        val writes = mutableListOf<String>()
        val backend = ZCodeBackend(null, executable = { Path.of("/fake/zcode") })
        backend.attach(
            AgentIo({ writes += it }, {}),
            AgentSpec(Path.of("/repo"), effort = "high"),
        )

        val create = writes.single()
        assertTrue("\"method\":\"session/create\"" in create)
        assertFalse("thoughtLevel" in create)
        val openId = (json.parseToJsonElement(create) as JsonObject)["id"].toString().trim('"')
        backend.parse(
            """{"id":"$openId","result":{"session":{"sessionId":"sess_1"},"settings":{"thoughtLevel":{"enabled":true,"defaultLevel":"medium","available":[{"value":"low"},{"value":"high"}]}}}}""",
        )

        val setter = writes.single { "\"method\":\"session/setThoughtLevel\"" in it }
        val params = (json.parseToJsonElement(setter) as JsonObject)["params"] as JsonObject
        assertEquals("high", params["thoughtLevel"].toString().trim('"'))
    }

    @Test
    fun `new session safely skips an explicit effort absent from advertised capabilities`() = runBlocking {
        val writes = mutableListOf<String>()
        val backend = ZCodeBackend(null, executable = { Path.of("/fake/zcode") })
        backend.attach(
            AgentIo({ writes += it }, {}),
            AgentSpec(Path.of("/repo"), effort = "xhigh"),
        )
        val openId = (json.parseToJsonElement(writes.single()) as JsonObject)["id"].toString().trim('"')
        backend.parse(
            """{"id":"$openId","result":{"session":{"sessionId":"sess_1"},"settings":{"thoughtLevel":{"enabled":true,"defaultLevel":"medium","available":[{"value":"low"},{"value":"medium"},{"value":"high"}]}}}}""",
        )

        assertFalse(writes.any { "\"method\":\"session/setThoughtLevel\"" in it })
    }

    @Test
    fun `turn receipt streams output and fifo flushes only on terminal event`() = runBlocking {
        val writes = mutableListOf<String>()
        val backend = ready(writes)
        backend.sendPrompt("first", emptyList())
        backend.sendPrompt("second", emptyList())
        assertEquals(1, writes.count { "\"method\":\"session/send\"" in it })
        backend.parse("""{"id":"ccp-3","result":{"accepted":true,"sessionId":"sess_1"}}""")
        assertEquals(1, writes.count { "\"method\":\"session/send\"" in it })

        val started = backend.parse(
            """{"method":"session/event","params":{"type":"turn.started","payload":{"input":"first"},"sessionId":"sess_1"}}""",
        )
        assertEquals("first", assertIs<AgentEvent.UserReplay>(started.single()).text)
        val text = backend.parse(
            """{"method":"session/event","params":{"type":"model.streaming","payload":{"kind":"text_delta","delta":"PONG"},"sessionId":"sess_1"}}""",
        )
        assertEquals("PONG", assertIs<AgentEvent.AssistantText>(text.single()).text)
        val completed = backend.parse(
            """{"method":"session/event","params":{"type":"turn.completed","payload":{"response":"PONG","resultType":"success","usage":{"inputTokens":5,"outputTokens":1,"cacheReadTokens":2,"cacheWriteTokens":3}},"sessionId":"sess_1"}}""",
        )
        val result = assertIs<AgentEvent.TurnResult>(completed.single())
        assertEquals(5, result.usage?.inputTokens)
        assertEquals(2, writes.count { "\"method\":\"session/send\"" in it })
        assertTrue(writes.last { "\"method\":\"session/send\"" in it }.contains("second"))
    }

    @Test
    fun `cancel during session create is deferred until the opening prompt is written`() = runBlocking {
        val writes = mutableListOf<String>()
        val backend = ZCodeBackend(null, executable = { Path.of("/fake/zcode") })
        backend.attach(AgentIo({ writes += it }, {}), AgentSpec(Path.of("/repo")))
        backend.sendPrompt("do work", emptyList())
        backend.interrupt()
        assertFalse(writes.any { "\"method\":\"session/stop\"" in it })

        backend.parse(
            """{"id":"ccp-1","result":{"session":{"sessionId":"sess_1","workspace":{"workspacePath":"/repo","workspaceKey":"/repo"}}}}""",
        )

        val methods = writes.mapNotNull { line ->
            ((json.parseToJsonElement(line) as JsonObject)["method"]?.toString()?.trim('"'))
        }
        assertEquals(
            listOf("session/create", "session/subscribe", "session/send", "session/stop"),
            methods,
        )
    }

    @Test
    fun `image prompts use the official app server attachment shape`() = runBlocking {
        val writes = mutableListOf<String>()
        val backend = ready(writes)
        backend.sendPrompt("inspect", listOf(ImageData("image/png", "aGVsbG8=")))

        val envelope = json.parseToJsonElement(writes.last { "\"method\":\"session/send\"" in it }) as JsonObject
        val params = envelope["params"] as JsonObject
        val attachment = (params["attachments"] as kotlinx.serialization.json.JsonArray).single() as JsonObject
        assertEquals("image", attachment["kind"].toString().trim('"'))
        assertEquals("attachment-1", attachment["filename"].toString().trim('"'))
        assertEquals("image/png", attachment["mimeType"].toString().trim('"'))
        assertEquals("aGVsbG8=", attachment["dataBase64"].toString().trim('"'))
        assertEquals("5", attachment["sizeBytes"].toString())
    }

    @Test
    fun `effort default restores the concrete snapshot default on resume`() = runBlocking {
        val writes = mutableListOf<String>()
        val backend = ZCodeBackend(null, executable = { Path.of("/fake/zcode") })
        backend.attach(
            AgentIo({ writes += it }, {}),
            AgentSpec(Path.of("/repo"), resumeId = "sess_1", effort = "high"),
        )
        assertTrue(backend.applyEffort(null))

        writes.clear()
        backend.attach(
            AgentIo({ writes += it }, {}),
            AgentSpec(Path.of("/repo"), resumeId = "sess_1", effort = null),
        )
        val openId = (json.parseToJsonElement(writes.single()) as JsonObject)["id"].toString().trim('"')
        backend.parse(
            """{"id":"$openId","result":{"session":{"sessionId":"sess_1","workspace":{"workspacePath":"/repo","workspaceKey":"/repo"}},"settings":{"thoughtLevel":{"enabled":true,"current":"high","defaultLevel":"medium","available":[{"value":"low"},{"value":"medium"},{"value":"high"}]}}}}""",
        )

        val reset = writes.last { "\"method\":\"session/setThoughtLevel\"" in it }
        val params = (json.parseToJsonElement(reset) as JsonObject)["params"] as JsonObject
        assertEquals("medium", params["thoughtLevel"].toString().trim('"'))
        assertFalse(reset.contains("\"thoughtLevel\":\"default\""))
    }

    @Test
    fun `effort reset waits for a changed model snapshot`() = runBlocking {
        val writes = mutableListOf<String>()
        val backend = ZCodeBackend(null, executable = { Path.of("/fake/zcode") })
        backend.attach(
            AgentIo({ writes += it }, {}),
            AgentSpec(Path.of("/repo"), resumeId = "sess_1", effort = "high"),
        )
        assertTrue(backend.applyEffort(null))

        writes.clear()
        backend.attach(
            AgentIo({ writes += it }, {}),
            AgentSpec(Path.of("/repo"), resumeId = "sess_1", model = "zai/glm-5"),
        )
        val openId = (json.parseToJsonElement(writes.single()) as JsonObject)["id"].toString().trim('"')
        backend.parse(
            """{"id":"$openId","result":{"session":{"sessionId":"sess_1","model":{"providerId":"old","modelId":"old-model"}},"settings":{"thoughtLevel":{"enabled":true,"defaultLevel":"low","available":[{"value":"low"}]}}}}""",
        )
        assertFalse(writes.any { "\"method\":\"session/setThoughtLevel\"" in it })
        val setModel = writes.single { "\"method\":\"session/setModel\"" in it }
        val setModelId = (json.parseToJsonElement(setModel) as JsonObject)["id"].toString().trim('"')

        backend.parse(
            """{"id":"$setModelId","result":{"session":{"sessionId":"sess_1","model":{"providerId":"zai","modelId":"glm-5"}},"settings":{"thoughtLevel":{"enabled":true,"defaultLevel":"high","available":[{"value":"medium"},{"value":"high"}]}}}}""",
        )
        val reset = writes.last { "\"method\":\"session/setThoughtLevel\"" in it }
        val params = (json.parseToJsonElement(reset) as JsonObject)["params"] as JsonObject
        assertEquals("high", params["thoughtLevel"].toString().trim('"'))
    }

    @Test
    fun `explicit effort waits for changed model capabilities and skips an unsupported value`() = runBlocking {
        val writes = mutableListOf<String>()
        val backend = ZCodeBackend(null, executable = { Path.of("/fake/zcode") })
        backend.attach(
            AgentIo({ writes += it }, {}),
            AgentSpec(Path.of("/repo"), resumeId = "sess_1", model = "zai/glm-5", effort = "high"),
        )
        val openId = (json.parseToJsonElement(writes.single()) as JsonObject)["id"].toString().trim('"')
        backend.parse(
            """{"id":"$openId","result":{"session":{"sessionId":"sess_1","model":{"providerId":"old","modelId":"old-model"}},"settings":{"thoughtLevel":{"enabled":true,"defaultLevel":"low","available":[{"value":"high"}]}}}}""",
        )

        assertFalse(writes.any { "\"method\":\"session/setThoughtLevel\"" in it })
        val setModel = writes.single { "\"method\":\"session/setModel\"" in it }
        val setModelId = (json.parseToJsonElement(setModel) as JsonObject)["id"].toString().trim('"')
        backend.parse(
            """{"id":"$setModelId","result":{"session":{"sessionId":"sess_1","model":{"providerId":"zai","modelId":"glm-5"}},"settings":{"thoughtLevel":{"enabled":true,"defaultLevel":"medium","available":[{"value":"low"},{"value":"medium"}]}}}}""",
        )

        assertFalse(writes.any { "\"method\":\"session/setThoughtLevel\"" in it })
    }

    @Test
    fun `explicit effort after model switch uses read fallback and advertised value only`() = runBlocking {
        val writes = mutableListOf<String>()
        val backend = ZCodeBackend(null, executable = { Path.of("/fake/zcode") })
        backend.attach(
            AgentIo({ writes += it }, {}),
            AgentSpec(Path.of("/repo"), resumeId = "sess_1", model = "zai/glm-5", effort = "high"),
        )
        val openId = (json.parseToJsonElement(writes.single()) as JsonObject)["id"].toString().trim('"')
        backend.parse(
            """{"id":"$openId","result":{"session":{"sessionId":"sess_1"},"settings":{"thoughtLevel":{"enabled":true,"available":[{"value":"high"}]}}}}""",
        )
        val setModelId = (json.parseToJsonElement(writes.single { "\"method\":\"session/setModel\"" in it }) as JsonObject)["id"].toString().trim('"')

        backend.parse("""{"id":"$setModelId","result":{"session":{"sessionId":"sess_1"}}}""")
        assertFalse(writes.any { "\"method\":\"session/setThoughtLevel\"" in it })
        val readId = (json.parseToJsonElement(writes.single { "\"method\":\"session/read\"" in it }) as JsonObject)["id"].toString().trim('"')
        backend.parse(
            """{"id":"$readId","result":{"session":{"sessionId":"sess_1"},"settings":{"thoughtLevel":{"enabled":true,"available":[{"value":"medium"},{"value":"high"}]}}}}""",
        )

        val setter = writes.single { "\"method\":\"session/setThoughtLevel\"" in it }
        assertTrue("\"thoughtLevel\":\"high\"" in setter)
    }

    @Test
    fun `effort reset skips a concrete default not present in available levels`() = runBlocking {
        val writes = mutableListOf<String>()
        val backend = ZCodeBackend(null, executable = { Path.of("/fake/zcode") })
        backend.attach(
            AgentIo({ writes += it }, {}),
            AgentSpec(Path.of("/repo"), resumeId = "sess_1", effort = "high"),
        )
        assertTrue(backend.applyEffort(null))

        writes.clear()
        backend.attach(AgentIo({ writes += it }, {}), AgentSpec(Path.of("/repo"), resumeId = "sess_1"))
        val openId = (json.parseToJsonElement(writes.single()) as JsonObject)["id"].toString().trim('"')
        backend.parse(
            """{"id":"$openId","result":{"session":{"sessionId":"sess_1"},"settings":{"thoughtLevel":{"enabled":true,"defaultLevel":"xhigh","available":[{"value":"low"},{"value":"high"}]}}}}""",
        )

        assertFalse(writes.any { "\"method\":\"session/setThoughtLevel\"" in it })
        assertFalse(writes.any { "\"thoughtLevel\":\"default\"" in it })
    }

    @Test
    fun `effort default sends no invalid setter when thought levels are disabled`() = runBlocking {
        val writes = mutableListOf<String>()
        val backend = ZCodeBackend(null, executable = { Path.of("/fake/zcode") })
        backend.attach(
            AgentIo({ writes += it }, {}),
            AgentSpec(Path.of("/repo"), resumeId = "sess_1", effort = "high"),
        )
        assertTrue(backend.applyEffort(null))

        writes.clear()
        backend.attach(AgentIo({ writes += it }, {}), AgentSpec(Path.of("/repo"), resumeId = "sess_1"))
        val openId = (json.parseToJsonElement(writes.single()) as JsonObject)["id"].toString().trim('"')
        backend.parse(
            """{"id":"$openId","result":{"session":{"sessionId":"sess_1"},"settings":{"thoughtLevel":{"enabled":false,"available":[]}}}}""",
        )

        assertFalse(writes.any { "\"method\":\"session/setThoughtLevel\"" in it })
        assertFalse(writes.any { "\"method\":\"session/read\"" in it })
    }

    @Test
    fun `resumed first prompt waits for the changed model and its thought level setter`() = runBlocking {
        val writes = mutableListOf<String>()
        val backend = ZCodeBackend(null, executable = { Path.of("/fake/zcode") })
        backend.attach(
            AgentIo({ writes += it }, {}),
            AgentSpec(Path.of("/repo"), resumeId = "sess_1", model = "zai/glm-5", effort = "high"),
        )
        backend.sendPrompt("first", emptyList())
        val openId = idOf(writes.single())
        backend.parse(
            """{"id":"$openId","result":{"session":{"sessionId":"sess_1","model":{"providerId":"old","modelId":"old-model"}},"settings":{"thoughtLevel":{"enabled":true,"defaultLevel":"low","available":[{"value":"low"}]}}}}""",
        )
        assertFalse(writes.hasMethod("session/send"))

        backend.parse(
            """{"id":"${idOf(writes.withMethod("session/setModel"))}","result":{"session":{"sessionId":"sess_1","model":{"providerId":"zai","modelId":"glm-5"}},"settings":{"thoughtLevel":{"enabled":true,"defaultLevel":"medium","available":[{"value":"medium"},{"value":"high"}]}}}}""",
        )
        // The thought level for the NEW model is only now on the wire; the prompt must still wait for it.
        assertTrue(writes.hasMethod("session/setThoughtLevel"))
        assertFalse(writes.hasMethod("session/send"))

        backend.parse("""{"id":"${idOf(writes.withMethod("session/setMode"))}","result":{}}""")
        assertFalse(writes.hasMethod("session/send"))

        backend.parse("""{"id":"${idOf(writes.withMethod("session/setThoughtLevel"))}","result":{}}""")
        assertEquals(
            listOf("session/resume", "session/subscribe", "session/setModel", "session/setMode", "session/setThoughtLevel", "session/send"),
            writes.methods(),
        )
        assertTrue(writes.last().contains("first"))
    }

    @Test
    fun `resumed first prompt waits for setters even when the effort is unsupported`() = runBlocking {
        val writes = mutableListOf<String>()
        val backend = ZCodeBackend(null, executable = { Path.of("/fake/zcode") })
        backend.attach(
            AgentIo({ writes += it }, {}),
            AgentSpec(Path.of("/repo"), resumeId = "sess_1", model = "zai/glm-5", effort = "xhigh"),
        )
        backend.sendPrompt("first", emptyList())
        val openId = idOf(writes.single())
        backend.parse("""{"id":"$openId","result":{"session":{"sessionId":"sess_1","model":{"providerId":"old","modelId":"old-model"}}}}""")

        backend.parse(
            """{"id":"${idOf(writes.withMethod("session/setModel"))}","result":{"session":{"sessionId":"sess_1"},"settings":{"thoughtLevel":{"enabled":true,"defaultLevel":"medium","available":[{"value":"low"},{"value":"medium"}]}}}}""",
        )
        // Skipping an unsupported level is a settled outcome, not a reason to hold the prompt forever…
        assertFalse(writes.hasMethod("session/setThoughtLevel"))
        assertFalse(writes.hasMethod("session/send"))

        backend.parse("""{"id":"${idOf(writes.withMethod("session/setMode"))}","result":{}}""")
        assertEquals(
            listOf("session/resume", "session/subscribe", "session/setModel", "session/setMode", "session/send"),
            writes.methods(),
        )
    }

    @Test
    fun `resumed first prompt waits for default effort restoration`() = runBlocking {
        val writes = mutableListOf<String>()
        val backend = ZCodeBackend(null, executable = { Path.of("/fake/zcode") })
        backend.attach(
            AgentIo({ writes += it }, {}),
            AgentSpec(Path.of("/repo"), resumeId = "sess_1", effort = "high"),
        )
        assertTrue(backend.applyEffort(null))

        writes.clear()
        backend.attach(AgentIo({ writes += it }, {}), AgentSpec(Path.of("/repo"), resumeId = "sess_1"))
        backend.sendPrompt("first", emptyList())
        val openId = idOf(writes.single())
        backend.parse(
            """{"id":"$openId","result":{"session":{"sessionId":"sess_1"},"settings":{"thoughtLevel":{"enabled":true,"current":"high","defaultLevel":"medium","available":[{"value":"medium"},{"value":"high"}]}}}}""",
        )
        assertTrue(writes.hasMethod("session/setThoughtLevel"))
        assertFalse(writes.hasMethod("session/send"))

        backend.parse("""{"id":"${idOf(writes.withMethod("session/setMode"))}","result":{}}""")
        assertFalse(writes.hasMethod("session/send"))
        backend.parse("""{"id":"${idOf(writes.withMethod("session/setThoughtLevel"))}","result":{}}""")
        assertEquals(
            listOf("session/resume", "session/subscribe", "session/setMode", "session/setThoughtLevel", "session/send"),
            writes.methods(),
        )
    }

    @Test
    fun `resumed first prompt waits for a mode change`() = runBlocking {
        val writes = mutableListOf<String>()
        val backend = ZCodeBackend(null, executable = { Path.of("/fake/zcode") })
        backend.attach(
            AgentIo({ writes += it }, {}),
            AgentSpec(Path.of("/repo"), resumeId = "sess_1", mode = PermissionMode.PLAN),
        )
        backend.sendPrompt("first", emptyList())
        val openId = idOf(writes.single())
        backend.parse("""{"id":"$openId","result":{"session":{"sessionId":"sess_1"}}}""")

        val setMode = writes.withMethod("session/setMode")
        assertTrue("\"mode\":\"plan\"" in setMode)
        assertFalse(writes.hasMethod("session/send"))

        backend.parse("""{"id":"${idOf(setMode)}","result":{}}""")
        assertEquals(
            listOf("session/resume", "session/subscribe", "session/setMode", "session/send"),
            writes.methods(),
        )
    }

    @Test
    fun `cancel during the settings barrier still cancels the opening turn`() = runBlocking {
        val writes = mutableListOf<String>()
        val backend = ZCodeBackend(null, executable = { Path.of("/fake/zcode") })
        backend.attach(
            AgentIo({ writes += it }, {}),
            AgentSpec(Path.of("/repo"), resumeId = "sess_1", effort = "high"),
        )
        backend.sendPrompt("do work", emptyList())
        backend.interrupt()
        val openId = idOf(writes.single())
        backend.parse(
            """{"id":"$openId","result":{"session":{"sessionId":"sess_1"},"settings":{"thoughtLevel":{"enabled":true,"defaultLevel":"medium","available":[{"value":"high"}]}}}}""",
        )
        // Startup is still settling: stopping here would cancel an idle session and let the turn run after.
        assertFalse(writes.hasMethod("session/stop"))
        assertFalse(writes.hasMethod("session/send"))

        backend.parse("""{"id":"${idOf(writes.withMethod("session/setMode"))}","result":{}}""")
        assertFalse(writes.hasMethod("session/stop"))
        backend.parse("""{"id":"${idOf(writes.withMethod("session/setThoughtLevel"))}","result":{}}""")

        assertEquals(
            listOf("session/resume", "session/subscribe", "session/setMode", "session/setThoughtLevel", "session/send", "session/stop"),
            writes.methods(),
        )
    }

    @Test
    fun `a rejected startup setter releases the barrier instead of wedging the prompt`() = runBlocking {
        val writes = mutableListOf<String>()
        val backend = ZCodeBackend(null, executable = { Path.of("/fake/zcode") })
        backend.attach(
            AgentIo({ writes += it }, {}),
            AgentSpec(Path.of("/repo"), resumeId = "sess_1", model = "zai/glm-5", effort = "high"),
        )
        backend.sendPrompt("first", emptyList())
        val openId = idOf(writes.single())
        backend.parse("""{"id":"$openId","result":{"session":{"sessionId":"sess_1","model":{"providerId":"old","modelId":"old-model"}}}}""")

        backend.parse(
            """{"id":"${idOf(writes.withMethod("session/setModel"))}","error":{"code":-32602,"message":"Invalid params — model"}}""",
        )
        assertFalse(writes.hasMethod("session/send"))
        backend.parse("""{"id":"${idOf(writes.withMethod("session/setMode"))}","error":{"code":-32602,"message":"Invalid params — mode"}}""")

        assertEquals(
            listOf("session/resume", "session/subscribe", "session/setModel", "session/setMode", "session/send"),
            writes.methods(),
        )
        assertTrue(writes.last().contains("first"))
    }

    @Test
    fun `new session first prompt waits for the create thought level setter`() = runBlocking {
        val writes = mutableListOf<String>()
        val backend = ZCodeBackend(null, executable = { Path.of("/fake/zcode") })
        backend.attach(AgentIo({ writes += it }, {}), AgentSpec(Path.of("/repo"), effort = "high"))
        backend.sendPrompt("first", emptyList())
        val openId = idOf(writes.single())
        backend.parse(
            """{"id":"$openId","result":{"session":{"sessionId":"sess_1"},"settings":{"thoughtLevel":{"enabled":true,"defaultLevel":"medium","available":[{"value":"medium"},{"value":"high"}]}}}}""",
        )
        assertTrue(writes.hasMethod("session/setThoughtLevel"))
        assertFalse(writes.hasMethod("session/send"))

        backend.parse("""{"id":"${idOf(writes.withMethod("session/setThoughtLevel"))}","result":{}}""")
        assertEquals(
            listOf("session/create", "session/subscribe", "session/setThoughtLevel", "session/send"),
            writes.methods(),
        )
    }

    @Test
    fun `permission verdict returns the option response object not its option id`() = runBlocking {
        val writes = mutableListOf<String>()
        val backend = ready(writes)
        val events = backend.parse(
            """{"id":"server-3","method":"interaction/requestPermission","params":{"requestId":"perm_1","toolName":"Bash","input":{"command":"rm x"},"options":[{"kind":"allow_once","optionId":"once","response":{"decision":"allow","reason":"Approved once"}},{"kind":"allow_always","optionId":"project","response":{"decision":"allow","permissionUpdates":[{"type":"addRules"}],"reason":"Approved for this project"}},{"kind":"deny","optionId":"deny","response":{"decision":"deny","reason":"Denied"}}]}}""",
        )
        val ask = assertIs<AgentEvent.ControlRequest>(events.single())
        backend.respondPermission(ask.requestId, allow = true, remember = true, null, null, null)
        val response = json.parseToJsonElement(writes.last()) as JsonObject
        val result = response["result"] as JsonObject
        assertEquals("allow", result["decision"].toString().trim('"'))
        assertTrue(result.containsKey("permissionUpdates"))
        assertFalse(result.containsKey("optionId"))
    }

    @Test
    fun `request user input uses shared question card and returns strict zcode answer`() = runBlocking {
        val writes = mutableListOf<String>()
        val backend = ready(writes)
        val events = backend.parse(
            """{"id":"server-q","method":"interaction/requestUserInput","params":{"input":{"questions":[{"question":"Review this implementation plan."}]},"prompt":"Approve plan?","requestId":"question_1","questions":[{"header":"Plan","question":"Review this implementation plan.","options":[{"value":"approve","label":"Approve","description":"start implementation"}],"multiSelect":false}],"schema":{"interaction":"plan_approval"}}}""",
        )
        val ask = assertIs<AgentEvent.ControlRequest>(events.single())
        assertEquals("AskUserQuestion", ask.toolName)
        assertTrue(ask.input?.get("questions") != null)
        backend.respondPermission(
            ask.requestId, allow = true, remember = false, originalInput = ask.input,
            updatedInput = """{"questions":[],"answers":{"Review this implementation plan.":"Approve"}}""", denyMessage = null,
        )
        val response = json.parseToJsonElement(writes.last()) as JsonObject
        val result = response["result"] as JsonObject
        assertEquals("accept", result["action"].toString().trim('"'))
        assertEquals("approve", (((result["content"] as JsonObject)["answers"] as JsonObject)["Review this implementation plan."]).toString().trim('"'))
        assertFalse(writes.last().contains("jsonrpc"))
    }

    @Test
    fun `subscription header request fails with strict actionable response`() = runBlocking {
        val writes = mutableListOf<String>()
        val backend = ready(writes)
        val events = backend.parse(
            """{"id":"server-h","method":"interaction/requestProviderRuntimeHeaders","params":{"requestId":"h1","sessionId":"sess_1","workspace":{"workspacePath":"/repo","workspaceKey":"/repo"},"modelRef":{"providerId":"builtin:zai-start-plan","modelId":"glm-5"},"providerId":"builtin:zai-start-plan","reason":"model-request"}}""",
        )
        assertTrue(events.isEmpty())
        val response = json.parseToJsonElement(writes.last()) as JsonObject
        val result = response["result"] as JsonObject
        assertEquals("false", result["headersApplied"].toString())
        assertTrue(result["errorMessage"].toString().contains("ZCode Desktop Start Plan credentials"))
        assertFalse(writes.last().contains("jsonrpc"))
    }

    @Test
    fun `official model tool stream opens populated card`() = runBlocking {
        val writes = mutableListOf<String>()
        val backend = ready(writes)
        backend.parse("""{"method":"session/event","params":{"type":"model.streaming","payload":{"kind":"tool_input_start","toolCallId":"toolu_1","toolName":"Bash"}}}""")
        backend.parse("""{"method":"session/event","params":{"type":"model.streaming","payload":{"kind":"tool_input_delta","toolCallId":"toolu_1","delta":"{\\\"command\\\":\\\"echo hi\\\"}"}}}""")
        val events = backend.parse("""{"method":"session/event","params":{"type":"model.streaming","payload":{"kind":"tool_call","toolCallId":"toolu_1","toolName":"Bash","input":{"command":"echo hi"}}}}""")
        val tool = assertIs<AgentEvent.AssistantToolUse>(events.single())
        assertEquals("Bash", tool.name)
        assertEquals("echo hi", tool.input?.get("command").toString().trim('"'))
    }

    @Test
    fun `provider completion emits last-call occupancy separate from summed turn usage`() = runBlocking {
        val backend = ready(mutableListOf())
        val events = backend.parse(
            """{"method":"session/event","params":{"type":"session.updated","payload":{"type":"model_request_completed","usage":{"inputTokens":5,"outputTokens":1,"cacheReadTokens":2,"cacheWriteTokens":3}}}}""",
        )
        val usage = assertIs<AgentEvent.AssistantUsage>(events.single())
        assertEquals(5, usage.inputTokens)
        assertEquals(2, usage.cacheReadInputTokens)
        assertEquals(3, usage.cacheCreationInputTokens)
    }

    @Test
    fun `permission-denied batch closes the open tool card`() = runBlocking {
        val backend = ready(mutableListOf())
        backend.parse("""{"method":"session/event","params":{"type":"model.streaming","payload":{"kind":"tool_call","toolCallId":"toolu_1","toolName":"Bash","input":{"command":"rm x"}}}}""")
        val events = backend.parse(
            """{"method":"session/event","params":{"type":"tool.updated","payload":{"kind":"batch","toolCallIds":["toolu_1"],"successCount":0,"errorCount":1}}}""",
        )
        val result = assertIs<AgentEvent.ToolResult>(events.single())
        assertTrue(result.isError)
        assertEquals("toolu_1", result.toolUseId)
    }
}
