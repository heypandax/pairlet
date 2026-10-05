package dev.ccpocket.daemon.dsh

import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.protocol.PermissionMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * dsh applies the launch-time model/effort through `session/set_config_option` BEFORE the prompt gate opens,
 * and the gate opened only on the last write's answer. A write that never answered therefore held the
 * opening prompt forever — no error, no terminal state. The chain is now bounded like the handshake: a
 * launch-time write that times out fails the session open with a stage error (never "carry on on the default
 * model", which would let the user believe their pick took effect); a user-driven switch that times out is
 * reported and the session keeps working.
 */
class DshConfigTimeoutTest {

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

    private val w = CopyOnWriteArrayList<String>()
    private val injected = CopyOnWriteArrayList<String>()

    /** attach + handshake: initialize = 1, session/new = 2; launch config writes follow from id 3. */
    private suspend fun open(model: String? = null): DshBackend {
        val b = DshBackend(null, configTimeoutMs = TIMEOUT_MS)
        b.attach(
            AgentIo(writeLine = { w += it }, emit = {}, inject = { injected += it }),
            AgentSpec(Path.of("/repo"), model = model, mode = PermissionMode.DEFAULT),
        )
        b.parse("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}""")
        b.parse("""{"jsonrpc":"2.0","id":2,"result":{"sessionId":"$SESSION","configOptions":$configOptions}}""")
        return b
    }

    private suspend fun awaitInjected(n: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (injected.size < n && System.currentTimeMillis() < deadline) delay(20)
        assertTrue(injected.size >= n, "nothing was injected within 5 s: $injected")
    }

    private fun prompts() = w.filter { """"method":"session/prompt"""" in it }

    @Test
    fun `a launch model write that never answers fails the open instead of hanging or using the default`() = runBlocking {
        val b = open(model = "deepseek-v4-pro")
        b.sendPrompt("hello", emptyList())
        assertTrue(w.any { """"method":"session/set_config_option"""" in it }, "the launch model write went out")

        awaitInjected(1)
        val events = b.parse(injected.single())
        assertEquals("hello", assertIs<AgentEvent.UserReplay>(events[0]).text, "the waiting prompt settles")
        val text = assertIs<AgentEvent.AssistantText>(events[1]).text
        assertTrue("could not apply the chosen model settings" in text, text)
        assertTrue("did not answer" in text && "deepseek-v4-pro" in text, text)
        assertTrue(assertIs<AgentEvent.TurnResult>(events[2]).isError)
        assertTrue(prompts().isEmpty(), "nothing may run on a model the user did not pick")

        // a late answer changes nothing, and a later prompt is refused with the same stage
        assertTrue(b.parse("""{"jsonrpc":"2.0","id":3,"result":{"configOptions":$configOptions}}""").none { it is AgentEvent.TurnResult })
        b.sendPrompt("again", emptyList())
        awaitInjected(2)
        val again = b.parse(injected[1])
        assertEquals("again", assertIs<AgentEvent.UserReplay>(again[0]).text)
        assertTrue("could not apply the chosen model settings" in assertIs<AgentEvent.AssistantText>(again[1]).text)
        assertTrue(prompts().isEmpty())
        b.onProcessEnded(SESSION)
    }

    @Test
    fun `answers in time keep the config watchdog quiet`() = runBlocking {
        val b = open(model = "deepseek-v4-pro")
        b.sendPrompt("hello", emptyList())
        b.parse("""{"jsonrpc":"2.0","id":3,"result":{"configOptions":$configOptions}}""")
        assertTrue(prompts().any { "hello" in it }, "the gate opened on the answer")
        delay(TIMEOUT_MS * 4)
        assertTrue(injected.isEmpty(), "no timeout after an answered write: $injected")
        b.onProcessEnded(SESSION)
    }

    @Test
    fun `a user model switch that never answers is reported and the session keeps working`() = runBlocking {
        val b = open()
        b.applySettings(mode = null, model = "deepseek-v4-pro", effort = null)
        awaitInjected(1)
        assertTrue(b.parse(injected[0]).none { it is AgentEvent.TurnResult }, "a switch timeout ends no turn")
        awaitInjected(2)
        val notice = assertIs<AgentEvent.AssistantText>(b.parse(injected[1]).single()).text
        assertTrue("could not switch the model" in notice && "did not answer" in notice, notice)

        b.sendPrompt("still here?", emptyList())
        assertTrue(prompts().any { "still here?" in it }, "the session is not failed by a user switch")
        b.onProcessEnded(SESSION)
    }

    private companion object {
        const val SESSION = "744ff28d-161c-4186-9f61-82e1de14e6fe"
        const val TIMEOUT_MS = 100L
    }
}
