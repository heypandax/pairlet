package dev.ccpocket.daemon.acp

import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [AcpClient] on its own, behind a minimal [AcpClient.Host] — the protocol half both ACP backends share.
 * Request ids are deterministic: initialize = 1, session open = 2, then prompts in order.
 */
class AcpClientTest {
    private val w = CopyOnWriteArrayList<String>()
    private val injected = CopyOnWriteArrayList<String>()
    private val updates = mutableListOf<JsonObject>()

    private fun client(
        resume: AcpClient.Resume = AcpClient.Resume.RESUME,
        timeoutMs: Long = 30_000,
    ): AcpClient {
        lateinit var c: AcpClient
        c = AcpClient(
            AcpClient.Config(
                tag = "test",
                productName = "Test Agent",
                resume = resume,
                stageHandshake = "HANDSHAKE",
                stageNew = "NEW",
                stageResume = "RESUME",
                handshakeTimeoutMs = timeoutMs,
                handshakeHint = { "no answer" },
                describeError = { it?.get("message")?.jsonPrimitive?.content ?: "error" },
            ),
            LoggerFactory.getLogger("AcpClientTest"),
            object : AcpClient.Host {
                override suspend fun onSessionOpened(sessionId: String, result: JsonObject?) =
                    listOf(AgentEvent.SessionInit(sessionId, "/repo", null)) + c.openPromptGate(bind = sessionId)
                override fun onUpdate(update: JsonObject): List<AgentEvent> {
                    updates += update
                    return listOf(AgentEvent.Ignored("update"))
                }
                override fun permissionCard(params: JsonObject?) = "Tool" to buildJsonObject { put("description", "x") }
            },
        )
        return c
    }

    private suspend fun AcpClient.start(resumeId: String? = null) =
        attach(AgentIo(writeLine = { w += it }, emit = {}, inject = { injected += it }), "/repo", resumeId)

    private suspend fun AcpClient.open(sessionId: String = "s1"): List<AgentEvent> {
        parse("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}""")
        return parse("""{"jsonrpc":"2.0","id":2,"result":{"sessionId":"$sessionId"}}""")
    }

    private fun method(line: String) = Json.parseToJsonElement(line).jsonObject["method"]?.jsonPrimitive?.content
    private fun prompts() = w.filter { method(it) == "session/prompt" }

    @Test
    fun `the handshake initializes, then opens a fresh session`() = runBlocking {
        val c = client()
        c.start()
        val init = Json.parseToJsonElement(w.single()).jsonObject
        assertEquals("initialize", init["method"]?.jsonPrimitive?.content)
        assertEquals(
            """{"protocolVersion":1,"clientCapabilities":{"fs":{"readTextFile":false,"writeTextFile":false}}}""",
            init["params"].toString(),
        )
        val events = c.open()
        assertEquals("session/new", method(w[1]))
        assertEquals("s1", assertIs<AgentEvent.SessionInit>(events.single()).sessionId)
        assertEquals("s1", c.sessionId)
    }

    @Test
    fun `a resume uses the configured method and keeps the id it sent`() = runBlocking {
        for (resume in AcpClient.Resume.entries) {
            w.clear()
            val c = client(resume = resume)
            c.start(resumeId = "old")
            c.parse("""{"jsonrpc":"2.0","id":1,"result":{}}""")
            assertEquals(resume.method, method(w.last()))
            val events = c.parse("""{"jsonrpc":"2.0","id":2,"result":{}}""")
            assertEquals("old", assertIs<AgentEvent.SessionInit>(events.single()).sessionId)
        }
    }

    @Test
    fun `a rejected handshake names the stage, settles the waiting prompt and refuses later ones`() = runBlocking {
        val c = client()
        c.start()
        c.sendPrompt("hello", emptyList())
        val events = c.parse("""{"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"bad version"}}""")
        assertEquals("hello", assertIs<AgentEvent.UserReplay>(events[0]).text)
        assertEquals("⚠️ HANDSHAKE: bad version", assertIs<AgentEvent.AssistantText>(events[1]).text)
        assertTrue(assertIs<AgentEvent.TurnResult>(events[2]).isError)
        assertTrue(w.none { method(it) == "session/new" }, "a dead handshake opens nothing")

        c.sendPrompt("later", emptyList())
        val later = c.parse(injected.single())
        assertEquals("later", assertIs<AgentEvent.UserReplay>(later[0]).text)
        assertTrue(prompts().isEmpty())
    }

    @Test
    fun `a failed resume names the resume stage and a session without id is a dead end`() = runBlocking {
        val c = client()
        c.start(resumeId = "gone")
        c.parse("""{"jsonrpc":"2.0","id":1,"result":{}}""")
        val events = c.parse("""{"jsonrpc":"2.0","id":2,"error":{"message":"no such session"}}""")
        assertEquals("⚠️ RESUME: no such session", assertIs<AgentEvent.AssistantText>(events[0]).text)
        assertTrue(w.none { method(it) == "session/new" }, "no replacement session")

        val fresh = client()
        fresh.start()
        fresh.parse("""{"jsonrpc":"2.0","id":1,"result":{}}""") // every client numbers its own requests
        val noId = fresh.parse("""{"jsonrpc":"2.0","id":2,"result":{}}""")
        assertEquals("⚠️ NEW: test did not return a session id", assertIs<AgentEvent.AssistantText>(noId[0]).text)
    }

    @Test
    fun `a handshake that never answers settles the waiting prompt after the watchdog`() = runBlocking {
        val c = client(timeoutMs = 100)
        c.start()
        c.sendPrompt("anyone?", emptyList())
        val deadline = System.currentTimeMillis() + 5_000
        while (injected.isEmpty() && System.currentTimeMillis() < deadline) delay(20)
        val events = c.parse(injected.single())
        assertEquals("anyone?", assertIs<AgentEvent.UserReplay>(events[0]).text)
        assertEquals("⚠️ HANDSHAKE: no answer", assertIs<AgentEvent.AssistantText>(events[1]).text)
    }

    @Test
    fun `the watchdog stays quiet after an answer and after the process ended`() = runBlocking {
        val answered = client(timeoutMs = 100)
        answered.start()
        answered.open()
        val ended = client(timeoutMs = 100)
        ended.start()
        ended.processEnded()
        delay(400)
        assertTrue(injected.isEmpty(), "no failure: $injected")
    }

    private suspend fun awaitInjected(): String {
        val deadline = System.currentTimeMillis() + 5_000
        while (injected.isEmpty() && System.currentTimeMillis() < deadline) delay(20)
        return injected.single()
    }

    @Test
    fun `a session open that never answers settles the waiting prompt after the watchdog`() = runBlocking {
        val c = client(timeoutMs = 100)
        c.start()
        c.sendPrompt("hello?", emptyList())
        c.parse("""{"jsonrpc":"2.0","id":1,"result":{}}""") // → session/new, never answered
        val events = c.parse(awaitInjected())
        assertEquals("hello?", assertIs<AgentEvent.UserReplay>(events[0]).text)
        assertEquals("⚠️ NEW: no answer to `session/new` within 0s", assertIs<AgentEvent.AssistantText>(events[1]).text)
        assertTrue(assertIs<AgentEvent.TurnResult>(events[2]).isError)
        assertTrue(prompts().isEmpty())
    }

    @Test
    fun `a resume that keeps streaming is not accused, and silence is`() = runBlocking {
        val c = client(resume = AcpClient.Resume.LOAD, timeoutMs = 400)
        c.start(resumeId = "old")
        c.parse("""{"jsonrpc":"2.0","id":1,"result":{}}""") // → session/load
        val replay = """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"old","update":{"sessionUpdate":"x"}}}"""
        repeat(20) { c.parse(replay); delay(50) } // 1s of history replay, never 400ms of silence
        assertTrue(injected.isEmpty(), "a talking agent is not hung: $injected")
        val events = c.parse(awaitInjected())
        assertTrue(assertIs<AgentEvent.AssistantText>(events[0]).text.startsWith("⚠️ RESUME: no answer to `session/load`"))
    }

    @Test
    fun `a session open answering after the watchdog gave up is taken after all`() = runBlocking {
        val c = client(timeoutMs = 100)
        c.start()
        c.parse("""{"jsonrpc":"2.0","id":1,"result":{}}""")
        c.parse(awaitInjected()) // the watchdog's error turn
        val opened = c.parse("""{"jsonrpc":"2.0","id":2,"result":{"sessionId":"s1"}}""")
        assertEquals("s1", assertIs<AgentEvent.SessionInit>(opened.single()).sessionId)
        c.sendPrompt("now?", emptyList())
        assertTrue("now?" in prompts().single(), "the late session takes prompts normally")
    }

    @Test
    fun `prompts before the session open are buffered and released in order, one at a time`() = runBlocking {
        val c = client()
        c.start()
        c.sendPrompt("one", emptyList())
        c.sendPrompt("two", emptyList())
        assertTrue(prompts().isEmpty(), "nothing goes out before the session opens")
        c.open()
        assertTrue("one" in prompts().single())
        c.sendPrompt("three", emptyList())
        assertEquals(1, prompts().size, "one prompt in flight")

        val settled = c.parse("""{"jsonrpc":"2.0","id":3,"result":{"stopReason":"end_turn"}}""")
        assertEquals("one", assertIs<AgentEvent.UserReplay>(settled[0]).text, "the receipt precedes the result")
        assertTrue(!assertIs<AgentEvent.TurnResult>(settled[1]).isError)
        assertTrue("two" in prompts().last())
        val failed = c.parse("""{"jsonrpc":"2.0","id":4,"error":{"message":"boom"}}""")
        assertEquals(listOf("two"), failed.filterIsInstance<AgentEvent.UserReplay>().map { it.text })
        assertTrue("three" in prompts().last(), "an error does not stall the FIFO")
        val refusal = c.parse("""{"jsonrpc":"2.0","id":5,"result":{"stopReason":"refusal"}}""")
        assertTrue(assertIs<AgentEvent.TurnResult>(refusal[1]).isError)
    }

    @Test
    fun `an image prompt is refused unless initialize advertised images`() = runBlocking {
        val c = client()
        c.start()
        c.open()
        c.sendPrompt("look", listOf(dev.ccpocket.protocol.ImageData("image/png", "AA==")))
        val events = c.parse(injected.single())
        assertTrue(assertIs<AgentEvent.AssistantText>(events[1]).text.contains("Test Agent did not advertise image input"))
        assertTrue(prompts().isEmpty())
    }

    @Test
    fun `interrupt sends session cancel as a notification, and only with a session`() = runBlocking {
        val c = client()
        c.start()
        c.interrupt()
        assertEquals(1, w.size, "no session yet — nothing to cancel")
        c.open()
        c.interrupt()
        val cancel = Json.parseToJsonElement(w.last()).jsonObject
        assertEquals("session/cancel", cancel["method"]?.jsonPrimitive?.content)
        assertTrue("id" !in cancel, "a notification carries no id")
        assertEquals("""{"sessionId":"s1"}""", cancel["params"].toString())
    }

    @Test
    fun `an unknown server request is declined with -32601 and a permission request becomes a card`() = runBlocking {
        val c = client()
        c.start()
        c.open()
        assertTrue(c.parse("""{"jsonrpc":"2.0","id":9,"method":"fs/read_text_file","params":{}}""").isEmpty())
        assertEquals(
            """{"jsonrpc":"2.0","id":9,"error":{"code":-32601,"message":"not supported by cc-pocket"}}""",
            w.last(),
        )
        val ask = c.parse(
            """{"jsonrpc":"2.0","id":10,"method":"session/request_permission","params":{"options":[{"optionId":"ok","kind":"allow_once"}]}}""",
        ).single()
        assertEquals("10", assertIs<AgentEvent.ControlRequest>(ask).requestId)
        assertTrue(c.respondPermission("10", allow = true, remember = false))
        assertTrue(""""optionId":"ok"""" in w.last())
    }

    @Test
    fun `a history-replaying load drops its updates until it answers`() = runBlocking {
        val c = client(resume = AcpClient.Resume.LOAD)
        c.start(resumeId = "old")
        c.parse("""{"jsonrpc":"2.0","id":1,"result":{}}""")
        val update = """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"old","update":{"sessionUpdate":"x"}}}"""
        assertTrue(c.parse(update).isEmpty(), "historical replay is dropped")
        c.parse("""{"jsonrpc":"2.0","id":2,"result":{}}""")
        assertEquals(1, c.parse(update).size, "live updates flow once the load answered")
    }

    @Test
    fun `another session's updates are dropped, unstamped and own ones flow`() = runBlocking {
        fun update(session: String?) = """{"jsonrpc":"2.0","method":"session/update","params":{""" +
            (session?.let { """"sessionId":"$it",""" } ?: "") + """"update":{"sessionUpdate":"x"}}}"""
        val c = client()
        c.start()
        c.open()
        assertTrue(c.parse(update("other")).isEmpty())
        assertEquals(1, c.parse(update("s1")).size)
        assertEquals(1, c.parse(update(null)).size, "an unstamped update is not judged")
    }

    @Test
    fun `a relaunch forgets the previous process's failure and in-flight prompts`() = runBlocking {
        val c = client()
        c.start()
        c.parse("""{"jsonrpc":"2.0","id":1,"error":{"message":"dead"}}""")
        c.start()
        c.parse("""{"jsonrpc":"2.0","id":2,"result":{}}""")
        c.parse("""{"jsonrpc":"2.0","id":3,"result":{"sessionId":"s2"}}""")
        c.sendPrompt("again", emptyList())
        assertTrue("again" in prompts().single())
        assertTrue(c.parse("""{"jsonrpc":"2.0","id":99,"result":{}}""").isEmpty(), "an unknown id settles nothing")
        assertTrue(c.parse("not json").single() is AgentEvent.Unparseable)
    }
}
