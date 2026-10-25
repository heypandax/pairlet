package dev.ccpocket.daemon.dsh

import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.protocol.PermissionMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A model switch in a RUNNING dsh session (`session/set_config_option`, live — no relaunch) must never let a prompt
 * run on the model being left: a plugin's provider bills a different account than the official one. So the switch
 * shuts the prompt gate synchronously, on the command path, and only dsh's ANSWER — with a read-back that names the
 * new model — opens it again. A switch that is refused, never answered or not confirmed leaves the process refusing
 * every prompt until the next pick, which relaunches; a turn already running (sent before the switch) is left alone.
 *
 * Frames are the real ACP shapes (see [DshBackendAcpTest]): initialize = 1, session/new = 2; the switch's writes go
 * out from the backend's own scope, so the tests wait for them.
 */
class DshModelSwitchTest {

    @AfterTest
    fun tearDown() = DshCatalog.clearForTest()

    /** dsh's opaque model value, as it sits inside a JSON string. */
    private fun value(provider: String, model: String) = """[\"$provider\",\"$model\"]"""

    /** Two providers (the official route and a plugin's) offering the same model name, on [current] / [effort]. */
    private fun catalogue(current: String = value(OFFICIAL, FLASH), effort: String = "high") = """
        [{"id":"model","type":"select","currentValue":"$current",
          "options":[{"group":"$OFFICIAL","name":"DeepSeek","options":[
            {"value":"${value(OFFICIAL, FLASH)}","name":"DeepSeek-V4-Flash"},
            {"value":"${value(OFFICIAL, PRO)}","name":"DeepSeek-V4-Pro"}]},
           {"group":"$QODER","name":"Qoder","options":[
            {"value":"${value(QODER, PRO)}","name":"DeepSeek-V4-Pro"}]}]},
         {"id":"reasoning_effort","type":"select","currentValue":"$effort",
          "options":[{"value":"off","name":"Off"},{"value":"high","name":"High"},{"value":"max","name":"Max"}]}]
    """.trimIndent().replace("\n", "")

    private val w = CopyOnWriteArrayList<String>()
    private val injected = CopyOnWriteArrayList<String>()

    private suspend fun open(model: String? = null, options: String = catalogue()): DshBackend {
        val b = DshBackend(null, configTimeoutMs = 5_000)
        b.attach(
            AgentIo(writeLine = { w += it }, emit = {}, inject = { injected += it }),
            AgentSpec(Path.of("/repo"), model = model, mode = PermissionMode.DEFAULT),
        )
        b.parse("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}""")
        b.parse("""{"jsonrpc":"2.0","id":2,"result":{"sessionId":"s1","configOptions":$options}}""")
        return b
    }

    private fun configWrites() = w.filter { """"method":"session/set_config_option"""" in it }
    private fun prompts() = w.filter { """"method":"session/prompt"""" in it }
    private fun idOf(line: String) = Json.parseToJsonElement(line).jsonObject.getValue("id").jsonPrimitive.long
    private fun answer(id: Long, options: String) = """{"jsonrpc":"2.0","id":$id,"result":{"configOptions":$options}}"""
    private fun refusal(id: Long) = """{"jsonrpc":"2.0","id":$id,"error":{"code":-32602,"message":"unknown model option"}}"""

    /** The switch's writes leave from the backend scope: wait for the [n]th. */
    private suspend fun awaitConfigWrite(n: Int = 1): String {
        val deadline = System.currentTimeMillis() + 5_000
        while (configWrites().size < n && System.currentTimeMillis() < deadline) delay(10)
        assertTrue(configWrites().size >= n, "no config write #$n within 5 s: $w")
        return configWrites()[n - 1]
    }

    private suspend fun awaitInjected(n: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (injected.size < n && System.currentTimeMillis() < deadline) delay(10)
        assertTrue(injected.size >= n, "nothing was injected within 5 s: $injected")
    }

    @Test
    fun `a prompt sent right after a model switch waits for dsh to confirm the new model`() = runBlocking {
        val b = open()
        assertEquals(false, b.applySettings(mode = null, model = "$QODER/$PRO", effort = null), "a live switch, no relaunch")
        b.sendPrompt("p", emptyList())
        val write = awaitConfigWrite()
        assertTrue(value(QODER, PRO) in write, write)
        assertTrue(prompts().isEmpty(), "held until dsh answers the switch")

        val events = b.parse(answer(idOf(write), catalogue(current = value(QODER, PRO))))
        assertEquals("$QODER/$PRO", events.filterIsInstance<AgentEvent.RuntimeMeta>().last().model, "the header follows the read-back")
        assertTrue(prompts().single().contains(""""text":"p""""), "released once the new model is confirmed")
        b.onProcessEnded("s1")
    }

    @Test
    fun `a refused switch refuses the prompt held behind it and every later one, and the next pick relaunches`() = runBlocking {
        val b = open()
        b.applySettings(mode = null, model = "$QODER/$PRO", effort = null)
        b.sendPrompt("p", emptyList())
        val events = b.parse(refusal(idOf(awaitConfigWrite())))
        assertEquals("p", events.filterIsInstance<AgentEvent.UserReplay>().single().text)
        val text = events.filterIsInstance<AgentEvent.AssistantText>().single().text
        assertTrue("did not switch the DeepSeek Harness session to the chosen model" in text && "unknown model option" in text, text)
        assertTrue(events.filterIsInstance<AgentEvent.TurnResult>().single().isError)
        assertTrue(prompts().isEmpty(), "nothing ran on the model being left")

        b.sendPrompt("later", emptyList())
        awaitInjected(1)
        val later = b.parse(injected.single())
        assertEquals("later", later.filterIsInstance<AgentEvent.UserReplay>().single().text)
        assertTrue(later.filterIsInstance<AgentEvent.TurnResult>().single().isError)
        assertTrue(prompts().isEmpty())

        assertTrue(b.applySettings(mode = null, model = "$OFFICIAL/$FLASH", effort = null), "the next pick relaunches")
        assertEquals(1, configWrites().size, "…and writes nothing to the process that refused the switch")
        b.onProcessEnded("s1")
    }

    /** dsh answered, but its read-back still names the old model: the switch did not happen, whatever the answer said. */
    @Test
    fun `a switch the read-back does not confirm is refused like a failed one`() = runBlocking {
        val b = open()
        b.applySettings(mode = null, model = "$QODER/$PRO", effort = null)
        b.sendPrompt("p", emptyList())
        val events = b.parse(answer(idOf(awaitConfigWrite()), catalogue(current = value(OFFICIAL, FLASH))))
        val text = events.filterIsInstance<AgentEvent.AssistantText>().single().text
        assertTrue("still reports $OFFICIAL/$FLASH" in text, text)
        assertEquals("p", events.filterIsInstance<AgentEvent.UserReplay>().single().text)
        assertTrue(prompts().isEmpty())
        b.onProcessEnded("s1")
    }

    /**
     * A switch made while a turn runs: that turn was sent BEFORE the switch and finishes undisturbed — the failure
     * ends no turn — and the prompt queued behind it is refused only once it has settled, never in its middle.
     */
    @Test
    fun `a switch that fails while a turn runs leaves that turn alone and refuses what queued behind it after it`() = runBlocking {
        val b = open()
        b.sendPrompt("t", emptyList())
        val running = idOf(prompts().single())
        b.applySettings(mode = null, model = "$QODER/$PRO", effort = null)
        b.sendPrompt("p", emptyList())

        val failed = b.parse(refusal(idOf(awaitConfigWrite())))
        assertTrue(failed.none { it is AgentEvent.TurnResult || it is AgentEvent.UserReplay }, "the running turn goes on: $failed")
        assertTrue("did not switch" in assertIs<AgentEvent.AssistantText>(failed.single()).text)

        val settled = b.parse("""{"jsonrpc":"2.0","id":$running,"result":{"stopReason":"end_turn"}}""")
        assertEquals(listOf("t", "p"), settled.filterIsInstance<AgentEvent.UserReplay>().map { it.text }, "in turn order")
        assertEquals(listOf(false, true), settled.filterIsInstance<AgentEvent.TurnResult>().map { it.isError })
        assertEquals(1, prompts().size, "p never went out")
        b.onProcessEnded("s1")
    }

    /**
     * A model this session does not offer: nothing to write, and nothing may run on the current model either — a send
     * while a turn runs rides THIS process (Conversation relaunches only for an idle turn). So the process refuses from
     * now, and the switch asks for a relaunch.
     */
    @Test
    fun `switching to a model the session does not offer refuses from now and relaunches`() = runBlocking {
        val b = open()
        assertTrue(b.applySettings(mode = null, model = "workbuddy/$PRO", effort = null), "the next idle prompt relaunches")
        assertTrue(configWrites().isEmpty(), "no write for a model dsh does not offer")
        b.sendPrompt("p", emptyList())
        awaitInjected(1)
        val events = b.parse(injected.single())
        assertEquals("p", events.filterIsInstance<AgentEvent.UserReplay>().single().text)
        val text = events.filterIsInstance<AgentEvent.AssistantText>().single().text
        assertTrue("offers no model workbuddy/$PRO" in text, text)
        assertTrue(prompts().isEmpty())
        b.onProcessEnded("s1")
    }

    /** An effort level does not change who is billed: a refused one is reported and the held prompt goes out. */
    @Test
    fun `a refused effort switch is reported and lets the held prompt go`() = runBlocking {
        val b = open()
        b.applySettings(mode = null, model = null, effort = "max")
        b.sendPrompt("p", emptyList())
        val write = awaitConfigWrite()
        assertTrue(""""configId":"reasoning_effort"""" in write)
        assertTrue(prompts().isEmpty(), "held until answered, like any switch")
        b.parse("""{"jsonrpc":"2.0","id":${idOf(write)},"error":{"code":-32602,"message":"unknown effort"}}""")
        assertTrue(prompts().single().contains(""""text":"p""""))
        awaitInjected(1)
        assertTrue("could not switch the reasoning effort" in assertIs<AgentEvent.AssistantText>(b.parse(injected.single()).single()).text)
        b.onProcessEnded("s1")
    }

    /** One write at a time, in order: the prompt waits for the LAST switch, never just the first one's answer. */
    @Test
    fun `two switches in a row hold the prompt until the second is confirmed`() = runBlocking {
        val b = open()
        b.applySettings(mode = null, model = "$QODER/$PRO", effort = null)
        b.applySettings(mode = null, model = "$OFFICIAL/$PRO", effort = null)
        b.sendPrompt("p", emptyList())
        val first = awaitConfigWrite()
        assertTrue(value(QODER, PRO) in first)
        delay(50)
        assertEquals(1, configWrites().size, "the second waits for the first's answer")

        b.parse(answer(idOf(first), catalogue(current = value(QODER, PRO))))
        val second = configWrites().getOrNull(1) ?: error("the second write goes out on the first's answer: $w")
        assertTrue(value(OFFICIAL, PRO) in second)
        assertTrue(prompts().isEmpty(), "not released on the first answer")

        b.parse(answer(idOf(second), catalogue(current = value(OFFICIAL, PRO))))
        assertTrue(prompts().single().contains(""""text":"p""""))
        b.onProcessEnded("s1")
    }

    /** Back to the model the session reports while a switch is still pending: "already on it" is about to stop being
     *  true, so the return trip is written too, and the last request wins. */
    @Test
    fun `switching back while a switch is pending is written too`() = runBlocking {
        val b = open()
        b.applySettings(mode = null, model = "$QODER/$PRO", effort = null)
        b.applySettings(mode = null, model = "$OFFICIAL/$FLASH", effort = null)
        val first = awaitConfigWrite()
        b.parse(answer(idOf(first), catalogue(current = value(QODER, PRO))))
        assertTrue(value(OFFICIAL, FLASH) in (configWrites().getOrNull(1) ?: error("no return write: $w")))
        b.onProcessEnded("s1")
    }

    /** A launch write whose read-back does not name the model fails the open, like a refused one. */
    @Test
    fun `a launch model the read-back does not confirm fails the open`() = runBlocking {
        val b = open(model = "$QODER/$PRO")
        b.sendPrompt("hello", emptyList())
        val events = b.parse(answer(idOf(configWrites().single()), catalogue(current = value(OFFICIAL, FLASH))))
        assertEquals("hello", events.filterIsInstance<AgentEvent.UserReplay>().single().text)
        assertTrue("still reports" in events.filterIsInstance<AgentEvent.AssistantText>().single().text)
        assertTrue(prompts().isEmpty())
        b.onProcessEnded("s1")
    }

    /**
     * A provider-qualified id that is no longer offered is never re-matched by NAME: here the requested provider
     * `a` is gone, and provider `b` happens to offer a model literally named `a/model`.
     */
    @Test
    fun `a missing provider id is refused, not migrated to a provider whose model shares its text`() = runBlocking {
        val options = """
            [{"id":"model","type":"select","currentValue":"${value("b", "other")}",
              "options":[{"group":"b","name":"B","options":[
                {"value":"${value("b", "other")}","name":"Other"},
                {"value":"${value("b", "a/model")}","name":"A Model"}]}]}]
        """.trimIndent().replace("\n", "")
        val b = open(model = "a/model", options = options)
        b.sendPrompt("hello", emptyList())
        assertTrue(configWrites().isEmpty(), "nothing is written for provider b")
        assertTrue(prompts().isEmpty())
        b.onProcessEnded("s1")
    }

    private companion object {
        const val OFFICIAL = "deepseek-official"
        const val QODER = "qoder"
        const val FLASH = "deepseek-v4-flash"
        const val PRO = "deepseek-v4-pro"
    }
}
