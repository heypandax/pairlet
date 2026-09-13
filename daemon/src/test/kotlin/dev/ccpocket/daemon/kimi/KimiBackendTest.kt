package dev.ccpocket.daemon.kimi

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Drives [KimiBackend] with synthetic ACP JSON-RPC lines (no real `kimi` binary) to lock down the
 * probe-verified 0.34.0 behaviors (2026-08-08): the mid-turn prompt FIFO (ACP has no stdin queue —
 * `-32600 turn.agent_busy`), the streamed-cumulative tool input (no `rawInput` on `tool_call`), the
 * `rawOutput` string result, and the permission card's content-text description.
 * Request ids are deterministic (idSeq starts at 1: initialize=1, session/new=2, first prompt=3, …).
 */
class KimiBackendTest {

    private fun update(sessionUpdate: String) =
        """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","update":$sessionUpdate}}"""

    /** attach + handshake through to a live session "s1"; [w] collects every line the backend writes, [injected]
     *  every line it feeds back into its own pump, and [initialize] is kimi's `initialize` result. */
    private suspend fun ready(
        w: MutableList<String>,
        injected: MutableList<String> = mutableListOf(),
        initialize: String = NO_CAPABILITIES,
    ): KimiBackend {
        val b = KimiBackend(null)
        b.attach(
            AgentIo(writeLine = { w += it }, emit = {}, inject = { injected += it }),
            AgentSpec(Path.of("/repo"), mode = PermissionMode.DEFAULT),
        )
        b.parse("""{"jsonrpc":"2.0","id":1,"result":$initialize}""")                   // → session/new (id 2)
        b.parse("""{"jsonrpc":"2.0","id":2,"result":{"sessionId":"s1"}}""")             // session live
        return b
    }

    /** Relaunch [b] onto a fresh process and walk its handshake, answering the ids it really wrote. */
    private suspend fun reattach(b: KimiBackend, w: MutableList<String>, injected: MutableList<String>, initialize: String) {
        b.attach(
            AgentIo(writeLine = { w += it }, emit = {}, inject = { injected += it }),
            AgentSpec(Path.of("/repo"), mode = PermissionMode.DEFAULT),
        )
        b.parse("""{"jsonrpc":"2.0","id":${idOf(w.last())},"result":$initialize}""")  // → session/new
        b.parse("""{"jsonrpc":"2.0","id":${idOf(w.last())},"result":{"sessionId":"s1"}}""")
    }

    private fun prompts(w: List<String>) = w.filter { "\"session/prompt\"" in it }

    /** The `prompt` array of a written `session/prompt`, parsed — exactly the blocks kimi would receive. */
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
    fun `mid-turn prompt is FIFO-queued and flushed on settle, never errored`() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        b.sendPrompt("first", emptyList())
        assertEquals(1, w.count { "\"session/prompt\"" in it }, "first prompt goes straight out")
        b.sendPrompt("second", emptyList())
        b.sendPrompt("third", emptyList())
        assertEquals(1, w.count { "\"session/prompt\"" in it }, "mid-turn prompts must queue (ACP -32600 otherwise)")
        // turn settles → oldest queued flushes
        b.parse("""{"jsonrpc":"2.0","id":3,"result":{"stopReason":"end_turn"}}""")
        assertEquals(2, w.count { "\"session/prompt\"" in it })
        assertTrue(w.last { "\"session/prompt\"" in it }.contains("second"))
        // next settle → last queued flushes
        b.parse("""{"jsonrpc":"2.0","id":4,"result":{"stopReason":"cancelled"}}""")
        assertEquals(3, w.count { "\"session/prompt\"" in it })
        assertTrue(w.last { "\"session/prompt\"" in it }.contains("third"))
    }

    @Test
    fun `queued prompt flushes even when the in-flight one errors`() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        b.sendPrompt("first", emptyList())
        b.sendPrompt("second", emptyList())
        val events = b.parse("""{"jsonrpc":"2.0","id":3,"error":{"code":-32001,"message":"auth required"}}""")
        assertTrue(events.any { it is AgentEvent.TurnResult && it.isError })
        assertEquals(2, w.count { "\"session/prompt\"" in it }, "an error must not stall the FIFO")
    }

    // kimi's live stream has NO user_message_chunk (probe 0.34.0) — the backend synthesizes the consumption
    // receipt at prompt settle so Conversation's ledger settles (otherwise every relaunch re-RUNS all past
    // prompts and task grants never end at the turn boundary).
    @Test
    fun `prompt settle synthesizes the UserReplay receipt before the TurnResult`() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        b.sendPrompt("first", emptyList())
        b.sendPrompt("second", emptyList())
        val events = b.parse("""{"jsonrpc":"2.0","id":3,"result":{"stopReason":"end_turn"}}""")
        val replay = events.indexOfFirst { it is AgentEvent.UserReplay }
        val result = events.indexOfFirst { it is AgentEvent.TurnResult }
        assertTrue(replay in 0 until result, "UserReplay must settle the ledger before TurnResult checks it")
        assertEquals("first", (events[replay] as AgentEvent.UserReplay).text)
        // the flushed queued prompt settles with ITS OWN text
        val next = b.parse("""{"jsonrpc":"2.0","id":4,"result":{"stopReason":"end_turn"}}""")
        assertEquals("second", (next.first { it is AgentEvent.UserReplay } as AgentEvent.UserReplay).text)
    }

    @Test
    fun `an errored prompt still settles its receipt — no relaunch redelivery loop`() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        b.sendPrompt("first", emptyList())
        val events = b.parse("""{"jsonrpc":"2.0","id":3,"error":{"code":-32001,"message":"auth required"}}""")
        assertEquals("first", (events.first { it is AgentEvent.UserReplay } as AgentEvent.UserReplay).text)
        assertTrue(events.any { it is AgentEvent.TurnResult && it.isError })
    }

    @Test
    fun `tool start waits for the streamed input and result carries rawOutput`() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        // tool_call: pending, NO rawInput (probe 0.34.0) → nothing emitted yet
        val ev0 = b.parse(update("""{"sessionUpdate":"tool_call","toolCallId":"0:tool_A","title":"Bash","kind":"execute","status":"pending","content":[{"type":"content","content":{"type":"text","text":""}}]}"""))
        assertTrue(ev0.isEmpty(), "no START before the input is known (empty card bug)")
        // in_progress: input JSON streams in cumulatively, still incomplete → nothing
        val ev1 = b.parse(update("""{"sessionUpdate":"tool_call_update","toolCallId":"0:tool_A","status":"in_progress","content":[{"type":"content","content":{"type":"text","text":"{\"command\":\"echo"}}]}"""))
        assertTrue(ev1.isEmpty())
        // in_progress: input completes → START with the parsed input
        val ev2 = b.parse(update("""{"sessionUpdate":"tool_call_update","toolCallId":"0:tool_A","status":"in_progress","content":[{"type":"content","content":{"type":"text","text":"{\"command\":\"echo hi\"}"}}]}"""))
        val start = ev2.single() as? AgentEvent.AssistantToolUse
        assertNotNull(start)
        assertEquals("Bash", start.name)
        assertEquals("\"echo hi\"", start.input?.get("command").toString())
        // settled: rawOutput is a plain STRING → ToolResult
        val ev3 = b.parse(update("""{"sessionUpdate":"tool_call_update","toolCallId":"0:tool_A","status":"completed","content":[{"type":"content","content":{"type":"text","text":"hi\n"}}],"rawOutput":"hi\n"}"""))
        val result = ev3.single() as? AgentEvent.ToolResult
        assertNotNull(result)
        assertEquals("0:tool_A", result.toolUseId)
        assertEquals("hi\n", result.content)
        assertTrue(!result.isError)
    }

    @Test
    fun `settle without a complete input still opens the card (title fallback) then fails`() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        b.parse(update("""{"sessionUpdate":"tool_call","toolCallId":"0:tool_B","title":"Read","kind":"read","status":"pending"}"""))
        val events = b.parse(update("""{"sessionUpdate":"tool_call_update","toolCallId":"0:tool_B","status":"failed","content":[{"type":"content","content":{"type":"text","text":"\"README.md\" does not exist."}}],"rawOutput":"\"README.md\" does not exist."}"""))
        assertIs<AgentEvent.AssistantToolUse>(events[0])
        assertEquals("Read", (events[0] as AgentEvent.AssistantToolUse).name)
        val result = events[1] as? AgentEvent.ToolResult
        assertNotNull(result)
        assertTrue(result.isError)
        assertEquals("\"README.md\" does not exist.", result.content)
    }

    @Test
    fun `permission card surfaces the content-text description`() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        val events = b.parse(
            """{"jsonrpc":"2.0","id":0,"method":"session/request_permission","params":{"sessionId":"s1","options":[{"optionId":"approve_once","name":"Approve once","kind":"allow_once"},{"optionId":"reject","name":"Reject","kind":"reject_once"}],"toolCall":{"toolCallId":"0:tool_A","title":"Bash","content":[{"type":"content","content":{"type":"text","text":"Requesting approval to Running: echo hi"}}]}}}""",
        )
        val ask = events.single() as? AgentEvent.ControlRequest
        assertNotNull(ask)
        assertEquals("Bash", ask.toolName)
        assertEquals("Requesting approval to Running: echo hi", ask.input?.get("description").toString().trim('"'))
        b.respondPermission(ask.requestId, allow = true, remember = false, null, null, null)
        assertTrue(w.last().contains("approve_once"), w.last())
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

    // the text alone would lose the image silently — so the prompt is refused: visibly, exactly once, and
    // without holding up what was sent after it
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

    // a quick follow-up (or a relaunch re-injecting two prompts) used to overwrite the single buffered first turn
    @Test
    fun `every prompt sent before the session opens goes out, in order, with its images`() = runBlocking {
        val w = mutableListOf<String>()
        val b = KimiBackend(null)
        b.attach(AgentIo(writeLine = { w += it }, emit = {}), AgentSpec(Path.of("/repo"), mode = PermissionMode.DEFAULT))
        b.sendPrompt("one", listOf(PNG))
        b.sendPrompt("two", listOf(JPEG, PNG))
        b.parse("""{"jsonrpc":"2.0","id":1,"result":$IMAGES_ADVERTISED}""")
        assertTrue(prompts(w).isEmpty(), "nothing goes out before the session is open")
        val opened = b.parse("""{"jsonrpc":"2.0","id":2,"result":{"sessionId":"s1"}}""")
        assertIs<AgentEvent.SessionInit>(opened.single())
        assertEquals(blocks("one", PNG), contentOf(prompts(w).single()))
        b.parse("""{"jsonrpc":"2.0","id":3,"result":{"stopReason":"end_turn"}}""")
        assertEquals(blocks("two", JPEG, PNG), contentOf(prompts(w).last()))
    }

    // kimi answers the image question per process: a relaunch must never inherit the previous process's answer
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
        /** The handshake every other test here answers with: no `agentCapabilities` at all. */
        const val NO_CAPABILITIES = """{"protocolVersion":1}"""

        /** kimi-cli's ACP `initialize` result shape, with and without image input. */
        const val IMAGES_ADVERTISED =
            """{"protocolVersion":1,"agentCapabilities":{"loadSession":true,"promptCapabilities":{"embeddedContext":true,"image":true,"audio":false}}}"""
        const val IMAGES_NOT_ADVERTISED =
            """{"protocolVersion":1,"agentCapabilities":{"loadSession":true,"promptCapabilities":{"embeddedContext":true,"image":false,"audio":false}}}"""

        /** Synthetic, canonical Base64: the 8-byte PNG signature and a JPEG SOI + APP0 marker. */
        val PNG = ImageData("image/png", "iVBORw0KGgo=")
        val JPEG = ImageData("image/jpeg", "/9j/4AAQ")
    }
}
