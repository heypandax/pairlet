package dev.ccpocket.daemon.dsh

import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.protocol.PermissionMode
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The model a NEW dsh session announces when the user picked one other than dsh's default.
 *
 * The conversation adopts the model of the session's [AgentEvent.SessionInit] and, once it has one, no longer
 * takes a model from a later [AgentEvent.RuntimeMeta]. So the init has to name the model the session is on
 * AFTER the launch write — announcing it at `session/new` named dsh's default instead, the header kept it for the
 * session's whole life, and the next relaunch baked it in place of the user's pick.
 *
 * Frames are the real 0.1.2 shapes (see [DshBackendAcpTest]); ids: initialize = 1, session/new = 2, writes from 3.
 */
class DshLaunchAnnounceTest {

    @AfterTest
    fun tearDown() = DshCatalog.clearForTest()

    private fun configOptions(model: String) = """
        [{"id":"model","name":"Model","type":"select",
          "currentValue":"[\"deepseek-official\",\"$model\"]",
          "options":[{"group":"deepseek-official","name":"DeepSeek","options":[
            {"value":"[\"deepseek-official\",\"deepseek-v4-flash\"]","name":"DeepSeek-V4-Flash"},
            {"value":"[\"deepseek-official\",\"deepseek-v4-pro\"]","name":"DeepSeek-V4-Pro"}]}]},
         {"id":"reasoning_effort","name":"Reasoning effort","type":"select","currentValue":"high",
          "options":[{"value":"off","name":"Off"},{"value":"high","name":"High"},{"value":"max","name":"Max"}]}]
    """.trimIndent().replace("\n", "")

    private val w = mutableListOf<String>()
    private val injected = mutableListOf<String>()

    private suspend fun open(model: String?): Pair<DshBackend, List<AgentEvent>> {
        val b = DshBackend(null)
        b.attach(
            AgentIo(writeLine = { w += it }, emit = {}, inject = { injected += it }),
            AgentSpec(Path.of("/repo"), model = model, mode = PermissionMode.DEFAULT),
        )
        b.parse("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}""")
        return b to b.parse(
            """{"jsonrpc":"2.0","id":2,"result":{"sessionId":"s1","configOptions":${configOptions("deepseek-v4-flash")}}}""",
        )
    }

    private fun configWrites() = w.filter { """"method":"session/set_config_option"""" in it }

    private fun prompts() = w.filter { """"method":"session/prompt"""" in it }

    @Test
    fun `a new session announces the model it was switched to, not dsh's default`() = runBlocking {
        val (b, opened) = open(model = "deepseek-official/deepseek-v4-pro")
        val early = opened.filterIsInstance<AgentEvent.SessionInit>().map { it.model }
        assertTrue(early.none { it == "deepseek-official/deepseek-v4-flash" }, "announced dsh's default before the write: $early")
        val settled = b.parse("""{"jsonrpc":"2.0","id":3,"result":{"configOptions":${configOptions("deepseek-v4-pro")}}}""")
        val init = (opened + settled).filterIsInstance<AgentEvent.SessionInit>().single()
        assertEquals("s1", init.sessionId)
        assertEquals("deepseek-official/deepseek-v4-pro", init.model)
        b.onProcessEnded("s1")
    }

    /** An id a phone saved before ids named their provider still opens — when exactly one provider offers it. */
    @Test
    fun `a unique pre-provider bare id migrates to its provider-qualified model`() = runBlocking {
        val (b, _) = open(model = "deepseek-v4-pro")
        assertTrue(configWrites().single().contains("""[\"deepseek-official\",\"deepseek-v4-pro\"]"""), configWrites().toString())
        val settled = b.parse("""{"jsonrpc":"2.0","id":3,"result":{"configOptions":${configOptions("deepseek-v4-pro")}}}""")
        assertEquals("deepseek-official/deepseek-v4-pro", settled.filterIsInstance<AgentEvent.SessionInit>().single().model)
        b.onProcessEnded("s1")
    }

    /**
     * Before: a refused launch write still opened the gate, so the opening turn ran on dsh's default — another
     * provider, another account. Now the open fails: nothing is announced as the running model and nothing is sent.
     */
    @Test
    fun `a refused launch model write fails the open instead of running on the default`() = runBlocking {
        val (b, opened) = open(model = "deepseek-official/deepseek-v4-pro")
        val settled = b.parse("""{"jsonrpc":"2.0","id":3,"error":{"code":-32602,"message":"unknown model option"}}""")
        val events = opened + settled
        assertEquals(null, events.filterIsInstance<AgentEvent.SessionInit>().single().model, "nothing is known to run")
        val text = events.filterIsInstance<AgentEvent.AssistantText>().single().text
        assertTrue("could not apply the chosen model settings" in text && "refused" in text, text)
        assertTrue(events.filterIsInstance<AgentEvent.TurnResult>().single().isError)
        b.sendPrompt("hello", emptyList())
        assertTrue(prompts().isEmpty(), "nothing may run on a model the user did not pick")
        assertTrue(b.applySettings(mode = null, model = "deepseek-official/deepseek-v4-flash", effort = null),
            "a new pick after a failed open relaunches")
        assertEquals(1, configWrites().size, "…and writes nothing to the process that refused to start")
        b.onProcessEnded("s1")
    }

    @Test
    fun `a model the session does not offer blocks the open and sends nothing`() = runBlocking {
        val (b, opened) = open(model = "qoder/claude-sonnet")
        assertTrue(configWrites().isEmpty(), "no write for a model dsh does not offer")
        assertEquals(null, opened.filterIsInstance<AgentEvent.SessionInit>().single().model)
        val text = opened.filterIsInstance<AgentEvent.AssistantText>().single().text
        assertTrue("did not start the DeepSeek Harness session on the chosen model" in text, text)
        assertTrue("qoder/claude-sonnet" in text && "deepseek-official/deepseek-v4-pro" in text, text)
        b.sendPrompt("hello", emptyList())
        assertTrue(prompts().isEmpty(), "the turn must not run on dsh's default provider")
        b.onProcessEnded("s1")
    }

    /** A plugin provider offering the SAME model name as the official route: the picked provider is the one written. */
    @Test
    fun `a same-named model is selected by its provider, and a bare name for it is refused as ambiguous`() = runBlocking {
        val twoProviders = """
            [{"id":"model","type":"select","currentValue":"[\"deepseek-official\",\"deepseek-v4-pro\"]",
              "options":[{"group":"deepseek-official","name":"DeepSeek","options":[
                {"value":"[\"deepseek-official\",\"deepseek-v4-pro\"]","name":"DeepSeek-V4-Pro"}]},
               {"group":"qoder","name":"Qoder","options":[
                {"value":"[\"qoder\",\"deepseek-v4-pro\"]","name":"DeepSeek-V4-Pro"}]}]}]
        """.trimIndent().replace("\n", "")
        suspend fun openOn(model: String): Pair<DshBackend, List<AgentEvent>> {
            w.clear()
            val b = DshBackend(null)
            b.attach(
                AgentIo(writeLine = { w += it }, emit = {}, inject = { injected += it }),
                AgentSpec(Path.of("/repo"), model = model, mode = PermissionMode.DEFAULT),
            )
            b.parse("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}""")
            return b to b.parse("""{"jsonrpc":"2.0","id":2,"result":{"sessionId":"s1","configOptions":$twoProviders}}""")
        }

        val (picked, _) = openOn("qoder/deepseek-v4-pro")
        assertTrue(configWrites().single().contains("""[\"qoder\",\"deepseek-v4-pro\"]"""), configWrites().toString())
        picked.onProcessEnded("s1")

        val (bare, opened) = openOn("deepseek-v4-pro")
        assertTrue(configWrites().isEmpty(), "an ambiguous bare name must not pick a provider")
        val text = opened.filterIsInstance<AgentEvent.AssistantText>().single().text
        assertTrue("more than one provider" in text && "qoder/deepseek-v4-pro" in text, text)
        bare.sendPrompt("hello", emptyList())
        assertTrue(prompts().isEmpty())
        bare.onProcessEnded("s1")
    }

    @Test
    fun `no pick announces at once, as before`() = runBlocking {
        val (b, opened) = open(model = null)
        assertEquals("deepseek-official/deepseek-v4-flash", opened.filterIsInstance<AgentEvent.SessionInit>().single().model)
        b.onProcessEnded("s1")
    }
}
