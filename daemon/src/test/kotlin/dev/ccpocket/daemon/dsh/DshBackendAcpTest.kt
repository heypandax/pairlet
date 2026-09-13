package dev.ccpocket.daemon.dsh

import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.PermissionMode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives [DshBackend] with synthetic ACP JSON-RPC lines (no real `dsh` binary) to lock down the
 * behaviors probe-verified against dsh 0.1.2-rc.1 by `scripts/probe-dsh-acp.py` — the release that
 * replaced the `web` profile's local API this backend used to speak.
 *
 * Every frame below is a REAL shape copied out of the probe log. Request ids are deterministic
 * (idSeq starts at 1: initialize = 1, session open = 2, then config writes / prompts in order).
 */
class DshBackendAcpTest {

    @AfterTest
    fun tearDown() = DshCatalog.clearForTest()

    private val configOptions = """
        [{"id":"model","name":"Model","type":"select",
          "currentValue":"[\"deepseek-official\",\"deepseek-v4-flash\"]",
          "options":[{"group":"deepseek-official","name":"DeepSeek","options":[
            {"value":"[\"deepseek-official\",\"deepseek-v4-flash\"]","name":"DeepSeek-V4-Flash"},
            {"value":"[\"deepseek-official\",\"deepseek-v4-pro\"]","name":"DeepSeek-V4-Pro"}]}]},
         {"id":"reasoning_effort","name":"Reasoning effort","type":"select","currentValue":"high",
          "options":[{"value":"off","name":"Off"},{"value":"high","name":"High"},{"value":"max","name":"Max"}]}]
    """.trimIndent().replace("\n", "")

    private fun update(body: String, session: String = SESSION) =
        """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"$session","update":$body}}"""

    /** attach + handshake through to a live session; [w] collects every line the backend writes, [injected]
     *  every line it feeds back into its own pump, and [initialize] is dsh's `initialize` result. */
    private suspend fun ready(
        w: MutableList<String>,
        events: MutableList<AgentEvent> = mutableListOf(),
        spec: AgentSpec = AgentSpec(Path.of("/repo"), mode = PermissionMode.DEFAULT),
        injected: MutableList<String> = mutableListOf(),
        initialize: String = NO_CAPABILITIES,
    ): DshBackend {
        val b = DshBackend(null)
        b.attach(AgentIo(writeLine = { w += it }, emit = {}, inject = { injected += it }), spec)
        b.parse("""{"jsonrpc":"2.0","id":1,"result":$initialize}""") // → session/new (id 2)
        events += b.parse(
            """{"jsonrpc":"2.0","id":2,"result":{"sessionId":"$SESSION","configOptions":$configOptions}}""",
        )
        return b
    }

    /** Relaunch [b] onto a fresh process and walk its handshake, answering the ids it really wrote. */
    private suspend fun reattach(
        b: DshBackend,
        w: MutableList<String>,
        injected: MutableList<String>,
        initialize: String,
    ) {
        b.attach(
            AgentIo(writeLine = { w += it }, emit = {}, inject = { injected += it }),
            AgentSpec(Path.of("/repo"), mode = PermissionMode.DEFAULT),
        )
        b.parse("""{"jsonrpc":"2.0","id":${idOf(w.last())},"result":$initialize}""") // → session/new
        b.parse(
            """{"jsonrpc":"2.0","id":${idOf(w.last())},"result":{"sessionId":"$SESSION","configOptions":$configOptions}}""",
        )
    }

    private fun prompts(w: List<String>) = w.filter { """"method":"session/prompt"""" in it }

    /** The `prompt` array of a written `session/prompt`, parsed — exactly the blocks dsh would receive. */
    private fun contentOf(request: String): JsonArray =
        Json.parseToJsonElement(request).jsonObject.getValue("params").jsonObject.getValue("prompt").jsonArray

    private fun idOf(request: String): Long =
        Json.parseToJsonElement(request).jsonObject.getValue("id").jsonPrimitive.content.toLong()

    /** ACP ContentBlocks: the text block (when given), then every image in order. */
    private fun blocks(text: String?, vararg images: ImageData): JsonArray = buildJsonArray {
        text?.let { addJsonObject { put("type", "text"); put("text", it) } }
        images.forEach { addJsonObject { put("type", "image"); put("data", it.base64); put("mimeType", it.mediaType) } }
    }

    @Test
    fun `session open announces the model and effort dsh actually reports`() = runBlocking {
        val w = mutableListOf<String>()
        val events = mutableListOf<AgentEvent>()
        ready(w, events)
        assertTrue(w.any { """"method":"session/new"""" in it }, "a fresh conversation creates a session")
        val init = assertIs<AgentEvent.SessionInit>(events.first())
        assertEquals(SESSION, init.sessionId)
        assertEquals("deepseek-v4-flash", init.model, "the bare id, never dsh's opaque selection value")
        val meta = assertIs<AgentEvent.RuntimeMeta>(events[1])
        assertEquals("deepseek-v4-flash", meta.model)
        assertEquals("high", meta.effort)
    }

    @Test
    fun `a resume opens the recorded session instead of creating one`() = runBlocking<Unit> {
        val w = mutableListOf<String>()
        val b = DshBackend(null)
        b.attach(
            AgentIo(writeLine = { w += it }, emit = {}),
            AgentSpec(Path.of("/repo"), resumeId = "old-session", mode = PermissionMode.DEFAULT),
        )
        b.parse("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}""")
        val open = w.last()
        assertTrue(""""method":"session/resume"""" in open, "a resume must not mint a new session")
        assertTrue(""""sessionId":"old-session"""" in open)
        // session/resume answers WITHOUT a sessionId — the id we sent is the session.
        val events = b.parse("""{"jsonrpc":"2.0","id":2,"result":{"configOptions":$configOptions}}""")
        assertEquals("old-session", assertIs<AgentEvent.SessionInit>(events.first()).sessionId)
    }

    /**
     * The model write carries dsh's OPAQUE value under `configId` (a bare id is rejected with
     * `unknown model option`, and `optionId` is a zod error) — and the opening prompt waits for it, so
     * the first turn cannot run on the model the user did not pick.
     */
    @Test
    fun `a launch model is applied through set_config_option before the first prompt`() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(
            w,
            spec = AgentSpec(Path.of("/repo"), model = "deepseek-v4-pro", effort = "max", mode = PermissionMode.DEFAULT),
        )
        b.sendPrompt("hello", emptyList())
        val write = w.last { """"method":"session/set_config_option"""" in it }
        assertTrue(""""configId":"model"""" in write, "the param is configId, not optionId")
        assertTrue("""deepseek-official\",\"deepseek-v4-pro""" in write, "the value is dsh's opaque pair")
        assertTrue(w.none { """"method":"session/prompt"""" in it }, "the prompt waits for the config write")

        // the model write settles → the effort write goes out (levels are a property of the model)
        b.parse(
            """{"jsonrpc":"2.0","id":3,"result":{"configOptions":$configOptions}}""",
        )
        val effortWrite = w.last { """"method":"session/set_config_option"""" in it }
        assertTrue(""""configId":"reasoning_effort"""" in effortWrite && """"value":"max"""" in effortWrite)
        assertTrue(w.none { """"method":"session/prompt"""" in it }, "still gated behind the last write")

        // the last write settles → the gate opens
        b.parse("""{"jsonrpc":"2.0","id":4,"result":{"configOptions":$configOptions}}""")
        assertTrue(w.any { """"method":"session/prompt"""" in it && "hello" in it })
    }

    /** A refused preference must not hold the conversation hostage. */
    @Test
    fun `a rejected config write still opens the prompt gate`() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w, spec = AgentSpec(Path.of("/repo"), effort = "xhigh", mode = PermissionMode.DEFAULT))
        b.sendPrompt("hello", emptyList())
        b.parse(
            """{"jsonrpc":"2.0","id":3,"error":{"code":-32602,"message":"unknown reasoning effort: xhigh"}}""",
        )
        assertTrue(w.any { """"method":"session/prompt"""" in it && "hello" in it })
    }

    /** dsh refuses a second prompt with `-32602 a prompt is already in flight`, so the FIFO lives here. */
    @Test
    fun `mid-turn prompts queue and flush on settle`() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        b.sendPrompt("first", emptyList())
        b.sendPrompt("second", emptyList())
        b.sendPrompt("third", emptyList())
        assertEquals(1, w.count { """"method":"session/prompt"""" in it })
        b.parse("""{"jsonrpc":"2.0","id":3,"result":{"stopReason":"end_turn"}}""")
        assertEquals(2, w.count { """"method":"session/prompt"""" in it })
        assertTrue("second" in w.last { """"method":"session/prompt"""" in it })
        b.parse("""{"jsonrpc":"2.0","id":4,"result":{"stopReason":"cancelled"}}""")
        assertEquals(3, w.count { """"method":"session/prompt"""" in it })
        assertTrue("third" in w.last { """"method":"session/prompt"""" in it })
    }

    /** There is no `user_message_chunk` on this wire: the settle IS the consumption receipt (issue #122). */
    @Test
    fun `prompt settle synthesizes the UserReplay receipt before the TurnResult`() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        b.sendPrompt("first", emptyList())
        val events = b.parse("""{"jsonrpc":"2.0","id":3,"result":{"stopReason":"end_turn"}}""")
        val replay = events.indexOfFirst { it is AgentEvent.UserReplay }
        val result = events.indexOfFirst { it is AgentEvent.TurnResult }
        assertTrue(replay in 0 until result, "the ledger must settle before the turn result is judged")
        assertEquals("first", (events[replay] as AgentEvent.UserReplay).text)
    }

    @Test
    fun `an errored prompt settles its receipt and flushes the queue`() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        b.sendPrompt("first", emptyList())
        b.sendPrompt("second", emptyList())
        val events = b.parse("""{"jsonrpc":"2.0","id":3,"error":{"code":-32603,"message":"boom"}}""")
        assertEquals("first", (events.first { it is AgentEvent.UserReplay } as AgentEvent.UserReplay).text)
        assertTrue(events.any { it is AgentEvent.TurnResult && it.isError })
        assertEquals(2, w.count { """"method":"session/prompt"""" in it }, "an error must not stall the FIFO")
    }

    @Test
    fun `chunks stream as text and thinking`() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        val text = b.parse(
            update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"hi"}}"""),
        )
        assertEquals("hi", assertIs<AgentEvent.AssistantText>(text.single()).text)
        val thought = b.parse(
            update("""{"sessionUpdate":"agent_thought_chunk","content":{"type":"text","text":"hmm"}}"""),
        )
        assertEquals("hmm", assertIs<AgentEvent.AssistantThinking>(thought.single()).text)
    }

    /** `usage_update` is the ONLY place the context window and its occupancy reach the live wire (#320). */
    @Test
    fun `usage_update carries the window and the occupancy`() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        val events = b.parse(update("""{"sessionUpdate":"usage_update","used":16192,"size":1000000}"""))
        assertEquals(1_000_000L, events.filterIsInstance<AgentEvent.RuntimeMeta>().single().contextWindow)
        val usage = events.filterIsInstance<AgentEvent.AssistantUsage>().single()
        assertEquals(16192L, usage.inputTokens)
        // `used` is a TOTAL: splitting it across the cache columns would double count downstream.
        assertNull(usage.cacheReadInputTokens)
        assertNull(usage.cacheCreationInputTokens)
    }

    /**
     * A permission request names only `toolCall.toolCallId` — the card's subject comes from the
     * `tool_call` update seen earlier in the turn, and the answer uses dsh's OWN option id.
     */
    @Test
    fun `a permission request is enriched from the tool call and answered with the offered option`() =
        runBlocking {
            val w = mutableListOf<String>()
            val b = ready(w)
            b.parse(
                update(
                    """{"sessionUpdate":"tool_call","toolCallId":"call_1","title":"write","status":"in_progress",
                       "rawInput":{"file_path":"a.txt","content":"ok"}}""".trimIndent().replace("\n", ""),
                ),
            )
            val ask = assertIs<AgentEvent.ControlRequest>(
                b.parse(
                    """{"jsonrpc":"2.0","id":0,"method":"session/request_permission","params":{
                       "sessionId":"$SESSION","toolCall":{"toolCallId":"call_1"},
                       "options":[{"optionId":"allow-once","name":"Allow once","kind":"allow_once"},
                                  {"optionId":"reject-once","name":"Reject","kind":"reject_once"}]}}"""
                        .trimIndent().replace("\n", ""),
                ).single(),
            )
            assertEquals("write", ask.toolName)
            assertEquals("a.txt", ask.input?.str("file_path"))

            b.respondPermission(ask.requestId, allow = true, remember = true, null, null, null)
            val answer = w.last()
            // remember has no counterpart (dsh offers no allow_always), so it can never invent one
            assertTrue(""""optionId":"allow-once"""" in answer, answer)
            assertTrue(""""outcome":"selected"""" in answer)
        }

    @Test
    fun `a rejection picks the reject option and an unknown ask is ignored`() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        b.parse(
            """{"jsonrpc":"2.0","id":7,"method":"session/request_permission","params":{
               "sessionId":"$SESSION","toolCall":{"toolCallId":"call_2"},
               "options":[{"optionId":"allow-once","kind":"allow_once"},
                          {"optionId":"reject-once","kind":"reject_once"}]}}""".trimIndent().replace("\n", ""),
        )
        b.respondPermission("7", allow = false, remember = false, null, null, null)
        assertTrue(""""optionId":"reject-once"""" in w.last())
        val before = w.size
        b.respondPermission("7", allow = true, remember = false, null, null, null) // already answered
        assertEquals(before, w.size, "a stale answer must not be written twice")
    }

    /** Updates stamped with somebody else's session (a sub-agent's) must never enter this chat. */
    @Test
    fun `another sessions update is dropped`() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        val events = b.parse(
            update(
                """{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"not mine"}}""",
                session = "someone-else",
            ),
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `interrupt cancels through the ACP notification`() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        b.interrupt()
        val last = w.last()
        assertTrue(""""method":"session/cancel"""" in last)
        assertTrue(""""id"""" !in last, "session/cancel is a notification — an id would await a response")
    }

    /** A mode change is baked into the process environment; a model change is not. */
    @Test
    fun `only a permission-mode change forces a relaunch`() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        assertTrue(b.applySettings(mode = PermissionMode.BYPASS_PERMISSIONS, model = null, effort = null))
        assertTrue(!b.applySettings(mode = null, model = "deepseek-v4-pro", effort = null))
    }

    /** The ACP surface has no rename: answering true would report a title dsh never wrote. */
    @Test
    fun `rename is refused rather than faked`() = runBlocking {
        val w = mutableListOf<String>()
        assertTrue(!ready(w).renameSession("new title"))
    }

    // ---- images (issue #377) ----

    @Test
    fun `advertised image input sends every image as an ACP image block, in order, after the text`() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w, initialize = IMAGES_ADVERTISED)
        b.sendPrompt("what changed?", listOf(PNG, JPEG))
        assertEquals(blocks("what changed?", PNG, JPEG), contentOf(prompts(w).single()))
        // the settle still replays the TEXT — what Conversation's prompt ledger matches on, images or not
        val events = b.parse("""{"jsonrpc":"2.0","id":3,"result":{"stopReason":"end_turn"}}""")
        assertEquals("what changed?", events.filterIsInstance<AgentEvent.UserReplay>().single().text)
    }

    @Test
    fun `an image-only prompt sends no empty text block`() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w, initialize = IMAGES_ADVERTISED)
        b.sendPrompt("", listOf(PNG))
        assertEquals(blocks(null, PNG), contentOf(prompts(w).single()))
    }

    /** dsh fails a whole prompt on an image block it did not advertise, and the text alone would lose the image
     *  silently — so the prompt is refused: visibly, exactly once, without holding up what was sent after it. */
    @Test
    fun `without advertised image input the prompt is refused rather than sent as text`() = runBlocking {
        for (initialize in listOf(NO_CAPABILITIES, IMAGES_NOT_ADVERTISED)) {
            val w = mutableListOf<String>()
            val injected = mutableListOf<String>()
            val b = ready(w, injected = injected, initialize = initialize)
            b.sendPrompt("what changed?", listOf(PNG))
            b.sendPrompt("and now?", emptyList())
            assertTrue(prompts(w).isEmpty(), "neither its text nor the prompt behind it goes out first: $initialize")

            val events = b.parse(injected.single()) // the pump reads the refusal back
            assertEquals(3, events.size, events.toString())
            assertEquals("what changed?", assertIs<AgentEvent.UserReplay>(events[0]).text)
            assertTrue(assertIs<AgentEvent.AssistantText>(events[1]).text.startsWith("⚠️ not sent"))
            assertTrue(assertIs<AgentEvent.TurnResult>(events[2]).isError)
            assertTrue(b.parse(injected.single()).isEmpty(), "a refusal settles exactly once")

            assertEquals(blocks("and now?"), contentOf(prompts(w).single()), "the next prompt runs, as plain text")
            assertTrue(
                (w + injected + events.map { it.toString() }).none { PNG.base64 in it },
                "the image bytes go nowhere",
            )
        }
    }

    @Test
    fun `a refused image prompt in the queue settles in order and the prompt behind it still runs`() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w) // no image input advertised
        b.sendPrompt("first", emptyList())
        b.sendPrompt("look at this", listOf(PNG))
        b.sendPrompt("third", emptyList())

        val events = b.parse("""{"jsonrpc":"2.0","id":3,"result":{"stopReason":"end_turn"}}""")
        assertEquals(listOf("first", "look at this"), events.filterIsInstance<AgentEvent.UserReplay>().map { it.text })
        assertEquals(listOf(false, true), events.filterIsInstance<AgentEvent.TurnResult>().map { it.isError })
        val sent = prompts(w)
        assertEquals(2, sent.size, "the refused prompt is never written")
        assertEquals(blocks("third"), contentOf(sent.last()))

        val next = b.parse("""{"jsonrpc":"2.0","id":${idOf(sent.last())},"result":{"stopReason":"end_turn"}}""")
        assertEquals("third", next.filterIsInstance<AgentEvent.UserReplay>().single().text)
    }

    @Test
    fun `prompts held before the session opens keep their images and their order`() = runBlocking {
        val w = mutableListOf<String>()
        val b = DshBackend(null)
        b.attach(AgentIo(writeLine = { w += it }, emit = {}), AgentSpec(Path.of("/repo"), mode = PermissionMode.DEFAULT))
        b.sendPrompt("one", listOf(PNG))
        b.sendPrompt("two", listOf(JPEG, PNG))
        b.parse("""{"jsonrpc":"2.0","id":1,"result":$IMAGES_ADVERTISED}""")
        assertTrue(prompts(w).isEmpty(), "nothing goes out before the session is open")
        b.parse("""{"jsonrpc":"2.0","id":2,"result":{"sessionId":"$SESSION","configOptions":$configOptions}}""")
        assertEquals(blocks("one", PNG), contentOf(prompts(w).single()))
        b.parse("""{"jsonrpc":"2.0","id":3,"result":{"stopReason":"end_turn"}}""")
        assertEquals(blocks("two", JPEG, PNG), contentOf(prompts(w).last()))
    }

    /** The gate that waits for the launch model releases refusals too — as events on that config answer. */
    @Test
    fun `an image prompt held behind the launch model write is refused when the gate opens`() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w, spec = AgentSpec(Path.of("/repo"), model = "deepseek-v4-pro", mode = PermissionMode.DEFAULT))
        b.sendPrompt("look", listOf(PNG))
        b.sendPrompt("then this", emptyList())
        val events = b.parse("""{"jsonrpc":"2.0","id":3,"result":{"configOptions":$configOptions}}""")
        assertEquals("look", events.filterIsInstance<AgentEvent.UserReplay>().single().text)
        assertTrue(events.filterIsInstance<AgentEvent.TurnResult>().single().isError)
        assertEquals(blocks("then this"), contentOf(prompts(w).single()))
    }

    /** dsh answers the image question per connection: a relaunch must never inherit the previous process's answer. */
    @Test
    fun `every process answers the image question for itself`() = runBlocking {
        val w = mutableListOf<String>()
        val injected = mutableListOf<String>()
        val b = ready(w, injected = injected, initialize = IMAGES_ADVERTISED)
        b.sendPrompt("before", listOf(PNG))
        assertEquals(blocks("before", PNG), contentOf(prompts(w).single()))

        reattach(b, w, injected, initialize = NO_CAPABILITIES)
        b.sendPrompt("after", listOf(PNG))
        assertEquals(1, prompts(w).size, "the relaunched process advertised nothing, so nothing new is written")
        assertTrue(b.parse(injected.single()).any { it is AgentEvent.TurnResult && it.isError })

        reattach(b, w, injected, initialize = IMAGES_ADVERTISED)
        b.sendPrompt("again", listOf(JPEG))
        assertEquals(blocks("again", JPEG), contentOf(prompts(w).last()))
    }

    private companion object {
        const val SESSION = "744ff28d-161c-4186-9f61-82e1de14e6fe"

        /** The handshake every other test here answers with: no `agentCapabilities` at all. */
        const val NO_CAPABILITIES = """{"protocolVersion":1}"""

        /** dsh-v0.1.5-rc.1's `initialize` result shape, with and without image input. */
        const val IMAGES_ADVERTISED =
            """{"protocolVersion":1,"agentCapabilities":{"promptCapabilities":{"image":true,"audio":false,"embeddedContext":false}}}"""
        const val IMAGES_NOT_ADVERTISED =
            """{"protocolVersion":1,"agentCapabilities":{"promptCapabilities":{"image":false,"audio":false,"embeddedContext":false}}}"""

        /** Synthetic, canonical Base64: the 8-byte PNG signature and a JPEG SOI + APP0 marker. */
        val PNG = ImageData("image/png", "iVBORw0KGgo=")
        val JPEG = ImageData("image/jpeg", "/9j/4AAQ")
    }
}
