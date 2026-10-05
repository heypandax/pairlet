package dev.ccpocket.daemon.kimi

import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.protocol.PermissionMode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The model and mode the user picked for a Kimi session reach `kimi acp` (kimi 2.1.1 ignores `modelId` /
 * `modeId` on `session/new`; only `session/set_config_option` switches them). The writes go out after the
 * session opens and BEFORE the opening prompt — the prompt waits for the last write's answer — and the model
 * the header is told is the one kimi's answer reports, never the request.
 *
 * Every `configOptions` below is the real 2.1.1 shape (probe 2026-10-04 against a scripted local model).
 * Request ids are deterministic: initialize = 1, session open = 2, then config writes / prompts in order.
 */
class KimiLaunchConfigTest {

    private val w = CopyOnWriteArrayList<String>()
    private val injected = CopyOnWriteArrayList<String>()

    private fun configOptions(model: String = "mock-a", mode: String = "default") = """
        [{"type":"select","id":"model","name":"Model","category":"model","currentValue":"$model",
          "options":[{"value":"mock-a","name":"mock-1"},{"value":"mock-b","name":"mock-2"}]},
         {"type":"select","id":"mode","name":"Mode","category":"mode","currentValue":"$mode",
          "options":[{"value":"default","name":"Default"},{"value":"plan","name":"Plan"},
                     {"value":"auto","name":"Auto"},{"value":"yolo","name":"YOLO"}]}]
    """.trimIndent().replace("\n", "")

    /** attach + handshake; returns the backend and the events the session-open answer produced. */
    private suspend fun open(
        model: String? = null,
        mode: PermissionMode = PermissionMode.DEFAULT,
        resumeId: String? = null,
        current: String = configOptions(),
    ): Pair<KimiBackend, List<AgentEvent>> {
        val b = KimiBackend(null)
        b.attach(
            AgentIo(writeLine = { w += it }, emit = {}, inject = { injected += it }),
            AgentSpec(Path.of("/repo"), resumeId = resumeId, model = model, mode = mode),
        )
        b.parse("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}""")
        val answer = if (resumeId == null) """{"sessionId":"s1","configOptions":$current}""" else """{"configOptions":$current}"""
        return b to b.parse("""{"jsonrpc":"2.0","id":2,"result":$answer}""")
    }

    private fun writes() = w.filter { """"method":"session/set_config_option"""" in it }
    private fun prompts() = w.filter { """"method":"session/prompt"""" in it }

    private fun param(line: String, key: String): String =
        Json.parseToJsonElement(line).jsonObject.getValue("params").jsonObject.getValue(key).jsonPrimitive.content

    private fun idOf(line: String): Long = Json.parseToJsonElement(line).jsonObject.getValue("id").jsonPrimitive.content.toLong()

    @Test
    fun `a chosen model is written before the first prompt and the prompt waits for its answer`() = runBlocking {
        val (b, opened) = open(model = "mock-b")
        assertTrue(opened.none { it is AgentEvent.SessionInit }, "the header waits for kimi's read-back: $opened")
        b.sendPrompt("hello", emptyList())
        val write = writes().single()
        assertEquals("s1", param(write, "sessionId"))
        assertEquals("model", param(write, "configId"))
        assertEquals("mock-b", param(write, "value"))
        assertTrue(prompts().isEmpty(), "the prompt waits for the model write")

        val events = b.parse("""{"jsonrpc":"2.0","id":${idOf(write)},"result":{"configOptions":${configOptions(model = "mock-b")}}}""")
        val init = events.filterIsInstance<AgentEvent.SessionInit>().single()
        assertEquals("s1", init.sessionId)
        assertEquals("mock-b", init.model, "the model kimi reports after the write")
        assertTrue(prompts().single().contains("hello"))
        assertTrue(w.indexOf(prompts().single()) > w.indexOf(write), "the prompt went out after the write")
    }

    @Test
    fun `no chosen model writes no model and announces the model kimi runs`() = runBlocking {
        val (b, opened) = open(model = null)
        assertTrue(writes().isEmpty(), "nothing to write: ${writes()}")
        assertEquals("mock-a", opened.filterIsInstance<AgentEvent.SessionInit>().single().model)
        b.sendPrompt("hello", emptyList())
        assertTrue(prompts().single().contains("hello"))
    }

    @Test
    fun `a chosen model kimi is already on is not written again`() = runBlocking {
        val (_, opened) = open(model = "mock-a")
        assertTrue(writes().isEmpty(), "already on it: ${writes()}")
        assertEquals("mock-a", opened.filterIsInstance<AgentEvent.SessionInit>().single().model)
    }

    @Test
    fun `plan is written as kimi's plan mode, after the model`() = runBlocking {
        val (b, _) = open(model = "mock-b", mode = PermissionMode.PLAN)
        b.sendPrompt("hello", emptyList())
        val model = writes().single()
        assertEquals("model", param(model, "configId"))
        b.parse("""{"jsonrpc":"2.0","id":${idOf(model)},"result":{"configOptions":${configOptions(model = "mock-b")}}}""")
        assertTrue(prompts().isEmpty(), "still gated behind the mode write")
        val mode = writes().last()
        assertEquals("mode", param(mode, "configId"))
        assertEquals("plan", param(mode, "value"))
        val events = b.parse(
            """{"jsonrpc":"2.0","id":${idOf(mode)},"result":{"configOptions":${configOptions(model = "mock-b", mode = "plan")}}}""",
        )
        assertEquals("mock-b", events.filterIsInstance<AgentEvent.SessionInit>().single().model)
        assertTrue(prompts().single().contains("hello"))
    }

    @Test
    fun `default on a default session writes nothing`() = runBlocking {
        open(mode = PermissionMode.DEFAULT)
        assertTrue(writes().isEmpty(), "${writes()}")
    }

    @Test
    fun `bypass never becomes kimi's yolo`() = runBlocking {
        val (_, opened) = open(mode = PermissionMode.BYPASS_PERMISSIONS)
        assertTrue(writes().isEmpty(), "bypass stays with the daemon's permission bridge: ${writes()}")
        assertTrue(w.none { "yolo" in it || "\"auto\"" in it }, "$w")
        assertTrue(opened.any { it is AgentEvent.SessionInit })
    }

    @Test
    fun `a resumed session gets the same writes after session load`() = runBlocking {
        // kimi keeps a loaded session's model but NOT its mode (probe 2.1.1): plan has to be written again
        val (b, opened) = open(model = "mock-a", mode = PermissionMode.PLAN, resumeId = "s-old",
            current = configOptions(model = "mock-b", mode = "default"))
        assertTrue(w.any { """"method":"session/load"""" in it }, "a resume loads, never creates")
        assertTrue(opened.none { it is AgentEvent.SessionInit })
        b.sendPrompt("again", emptyList())
        val model = writes().single()
        assertEquals("s-old", param(model, "sessionId"))
        assertEquals("mock-a", param(model, "value"))
        b.parse("""{"jsonrpc":"2.0","id":${idOf(model)},"result":{"configOptions":${configOptions(model = "mock-a")}}}""")
        val mode = writes().last()
        assertEquals("plan", param(mode, "value"))
        assertTrue(prompts().isEmpty())
        val events = b.parse(
            """{"jsonrpc":"2.0","id":${idOf(mode)},"result":{"configOptions":${configOptions(model = "mock-a", mode = "plan")}}}""",
        )
        val init = events.filterIsInstance<AgentEvent.SessionInit>().single()
        assertEquals("s-old", init.sessionId)
        assertEquals("mock-a", init.model)
        assertTrue(prompts().single().contains("again"))
    }

    /** Same as dsh: a refused LAUNCH write is quiet (no chat message before the first turn), the chain still
     *  opens the gate, and the header shows the model kimi is really on — not the one that was refused. */
    @Test
    fun `a refused model write keeps the session usable and reports the model kimi really runs`() = runBlocking {
        val (b, _) = open(model = "nope", mode = PermissionMode.PLAN)
        b.sendPrompt("hello", emptyList())
        val model = writes().single()
        val events = b.parse(
            """{"jsonrpc":"2.0","id":${idOf(model)},"error":{"code":-32603,"message":"Internal error",""" +
                """"data":{"details":"Model \"nope\" is not configured in config.toml."}}}""",
        )
        assertTrue(events.none { it is AgentEvent.TurnResult }, "a refused preference fails no turn: $events")
        // the chain moves on to the mode write
        val mode = writes().last()
        assertEquals("plan", param(mode, "value"))
        val settled = b.parse(
            """{"jsonrpc":"2.0","id":${idOf(mode)},"result":{"configOptions":${configOptions(mode = "plan")}}}""",
        )
        assertEquals("mock-a", settled.filterIsInstance<AgentEvent.SessionInit>().single().model, "never the refused id")
        assertTrue(prompts().single().contains("hello"), "the gate opened")
        assertTrue(injected.isEmpty(), "a launch-time refusal stays quiet, like dsh's: $injected")
    }

    // ---- mid-session mode switch: written at once on an open session, never a relaunch ----

    private suspend fun awaitWrites(n: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (writes().size < n && System.currentTimeMillis() < deadline) kotlinx.coroutines.delay(10)
        assertEquals(n, writes().size, "writes: ${writes()}")
    }

    private suspend fun answer(b: KimiBackend, write: String, mode: String) =
        b.parse("""{"jsonrpc":"2.0","id":${idOf(write)},"result":{"configOptions":${configOptions(mode = mode)}}}""")

    @Test
    fun `switching an open session to plan writes plan at once without a relaunch`() = runBlocking {
        val (b, _) = open()
        assertEquals(false, b.applySettings(mode = PermissionMode.PLAN, model = null, effort = null), "no relaunch")
        awaitWrites(1)
        val write = writes().single()
        assertEquals("s1", param(write, "sessionId"))
        assertEquals("mode", param(write, "configId"))
        assertEquals("plan", param(write, "value"))
        answer(b, write, "plan")
        // the prompt is not held by a mid-session write
        b.sendPrompt("hello", emptyList())
        assertTrue(prompts().single().contains("hello"))
        b.onProcessEnded("s1")
    }

    @Test
    fun `switching back to default writes default`() = runBlocking {
        val (b, _) = open(mode = PermissionMode.PLAN, current = configOptions(mode = "plan"))
        assertTrue(writes().isEmpty())
        assertEquals(false, b.applySettings(mode = PermissionMode.DEFAULT, model = null, effort = null))
        awaitWrites(1)
        assertEquals("default", param(writes().single(), "value"))
        b.onProcessEnded("s1")
    }

    @Test
    fun `leaving plan for full access writes kimi back to default, never yolo`() = runBlocking {
        val (b, _) = open(mode = PermissionMode.PLAN, current = configOptions(mode = "plan"))
        assertEquals(false, b.applySettings(mode = PermissionMode.BYPASS_PERMISSIONS, model = null, effort = null))
        awaitWrites(1)
        assertEquals("default", param(writes().single(), "value"))
        assertTrue(w.none { "yolo" in it }, "$w")
        b.onProcessEnded("s1")
    }

    @Test
    fun `a switch before the session opens is only recorded and the launch writes carry it once`() = runBlocking {
        val b = KimiBackend(null)
        b.attach(
            AgentIo(writeLine = { w += it }, emit = {}, inject = { injected += it }),
            AgentSpec(Path.of("/repo"), mode = PermissionMode.DEFAULT),
        )
        b.parse("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}""")
        assertEquals(false, b.applySettings(mode = PermissionMode.PLAN, model = null, effort = null))
        kotlinx.coroutines.delay(100)
        assertTrue(writes().isEmpty(), "nothing to write to before the session exists: ${writes()}")
        b.parse("""{"jsonrpc":"2.0","id":2,"result":{"sessionId":"s1","configOptions":${configOptions()}}}""")
        val launch = writes().single()
        assertEquals("plan", param(launch, "value"))
        answer(b, launch, "plan")
        kotlinx.coroutines.delay(100)
        assertEquals(1, writes().size, "written once, by the launch chain: ${writes()}")
        b.onProcessEnded("s1")
    }

    @Test
    fun `a switch while the launch writes are in flight is caught up once they settle`() = runBlocking {
        val (b, _) = open(model = "mock-b")
        val launch = writes().single()
        assertEquals(false, b.applySettings(mode = PermissionMode.PLAN, model = null, effort = null))
        kotlinx.coroutines.delay(100)
        assertEquals(1, writes().size, "not written over the launch chain: ${writes()}")
        b.parse("""{"jsonrpc":"2.0","id":${idOf(launch)},"result":{"configOptions":${configOptions(model = "mock-b")}}}""")
        assertEquals("plan", param(writes().last(), "value"))
        b.onProcessEnded("s1")
    }

    /** Same as dsh: a launch write that never answers fails the open with a stage error and settles the waiting
     *  prompt — never "carry on on kimi's default", which would let the user believe their pick took effect. */
    @Test
    fun `a model write that never answers fails the open instead of running on kimi's default`() = runBlocking {
        val b = KimiBackend(null, configTimeoutMs = 100)
        b.attach(
            AgentIo(writeLine = { w += it }, emit = {}, inject = { injected += it }),
            AgentSpec(Path.of("/repo"), model = "mock-b", mode = PermissionMode.DEFAULT),
        )
        b.parse("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}""")
        b.parse("""{"jsonrpc":"2.0","id":2,"result":{"sessionId":"s1","configOptions":${configOptions()}}}""")
        b.sendPrompt("hello", emptyList())
        assertEquals("mock-b", param(writes().single(), "value"))

        val deadline = System.currentTimeMillis() + 5_000
        while (injected.isEmpty() && System.currentTimeMillis() < deadline) kotlinx.coroutines.delay(20)
        val events = b.parse(injected.single())
        // the session is still announced — WITHOUT a model, so the user's pick survives into the next launch
        val init = events.filterIsInstance<AgentEvent.SessionInit>().single()
        assertEquals("s1", init.sessionId)
        assertEquals(null, init.model)
        assertEquals("hello", events.filterIsInstance<AgentEvent.UserReplay>().single().text, "the waiting prompt settles")
        val text = events.filterIsInstance<AgentEvent.AssistantText>().single().text
        assertTrue("could not apply the chosen model and mode" in text && "did not answer" in text && "mock-b" in text, text)
        assertTrue(events.filterIsInstance<AgentEvent.TurnResult>().single().isError)
        assertTrue(prompts().isEmpty(), "nothing may run on a model the user did not pick")

        // a late answer changes nothing
        val late = b.parse("""{"jsonrpc":"2.0","id":3,"result":{"configOptions":${configOptions(model = "mock-b")}}}""")
        assertTrue(late.none { it is AgentEvent.TurnResult || it is AgentEvent.SessionInit }, "$late")
        assertTrue(prompts().isEmpty())
        b.onProcessEnded("s1")
    }
}
