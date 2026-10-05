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

    @Test
    fun `a new session announces the model it was switched to, not dsh's default`() = runBlocking {
        val (b, opened) = open(model = "deepseek-v4-pro")
        val early = opened.filterIsInstance<AgentEvent.SessionInit>().map { it.model }
        assertTrue(early.none { it == "deepseek-v4-flash" }, "announced dsh's default before the write: $early")
        val settled = b.parse("""{"jsonrpc":"2.0","id":3,"result":{"configOptions":${configOptions("deepseek-v4-pro")}}}""")
        val init = (opened + settled).filterIsInstance<AgentEvent.SessionInit>().single()
        assertEquals("s1", init.sessionId)
        assertEquals("deepseek-v4-pro", init.model)
        b.onProcessEnded("s1")
    }

    @Test
    fun `a refused launch write announces the model dsh really runs`() = runBlocking {
        val (b, opened) = open(model = "deepseek-v4-pro")
        val settled = b.parse("""{"jsonrpc":"2.0","id":3,"error":{"code":-32602,"message":"unknown model option"}}""")
        assertEquals("deepseek-v4-flash", (opened + settled).filterIsInstance<AgentEvent.SessionInit>().single().model)
        b.onProcessEnded("s1")
    }

    @Test
    fun `no pick announces at once, as before`() = runBlocking {
        val (b, opened) = open(model = null)
        assertEquals("deepseek-v4-flash", opened.filterIsInstance<AgentEvent.SessionInit>().single().model)
        b.onProcessEnded("s1")
    }
}
