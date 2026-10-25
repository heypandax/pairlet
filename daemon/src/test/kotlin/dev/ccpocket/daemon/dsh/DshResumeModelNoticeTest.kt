package dev.ccpocket.daemon.dsh

import com.github.luben.zstd.Zstd
import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.protocol.PermissionMode
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A resumed dsh session with no requested model runs on whatever `session/resume` reports — dsh's current
 * selection, which need not be the provider the session last ran on (probe 0.2.1-rc.3). The transcript's model is
 * history, so it is never re-applied and never blocks; but when the two differ the chat says so, because a silent
 * switch of provider is how a reply ends up billed to another account.
 */
class DshResumeModelNoticeTest {

    @AfterTest
    fun tearDown() = DshCatalog.clearForTest()

    private val root: Path = Files.createTempDirectory("dsh-resume-notice").also { it.toFile().deleteOnExit() }

    /** One session laid out the way dsh writes it; its last answer came from [provider]/[model]. */
    private fun transcript(provider: String, model: String) {
        val dir = root.resolve(DshPaths.projectKey(CWD)).resolve(DshPaths.encodeSessionId(SESSION))
        dir.createDirectories()
        val header =
            """{"type":"session","version":0,"id":"$SESSION","cwd":"$CWD","createdAt":1700000000000,"delegationDepth":0}""" + "\n"
        val events =
            """{"type":"assistant/message","seq":3,"time":3,"data":{"turn":1,"step":1,"message":{"role":"assistant",""" +
                """"content":[{"type":"text","text":"hi"}],"source":{"kind":"model","provider":"$provider","model":"$model"},"id":"m1"}}}""" + "\n"
        Files.write(dir.resolve("session.jsonl.zstd"), Zstd.compress(header.toByteArray()) + Zstd.compress(events.toByteArray()))
    }

    private val catalogue = """
        [{"id":"model","type":"select","currentValue":"[\"deepseek-official\",\"deepseek-v4-pro\"]",
          "options":[{"group":"deepseek-official","name":"DeepSeek","options":[
            {"value":"[\"deepseek-official\",\"deepseek-v4-pro\"]","name":"DeepSeek-V4-Pro"}]},
           {"group":"qoder","name":"Qoder","options":[
            {"value":"[\"qoder\",\"deepseek-v4-pro\"]","name":"DeepSeek-V4-Pro"}]}]}]
    """.trimIndent().replace("\n", "")

    private val w = mutableListOf<String>()

    private suspend fun resume(model: String? = null): List<AgentEvent> {
        val b = DshBackend(null).apply { bindStoreRootForTest(root) }
        b.attach(
            AgentIo(writeLine = { w += it }, emit = {}, inject = {}),
            AgentSpec(Path.of(CWD), resumeId = SESSION, model = model, mode = PermissionMode.DEFAULT),
        )
        b.parse("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}""")
        return b.parse("""{"jsonrpc":"2.0","id":2,"result":{"configOptions":$catalogue}}""").also { b.onProcessEnded(SESSION) }
    }

    @Test
    fun `a resume that came back on another provider says so and changes nothing`() = runBlocking {
        transcript("qoder", "deepseek-v4-pro")
        val events = resume()
        val init = events.filterIsInstance<AgentEvent.SessionInit>().single()
        assertEquals("deepseek-official/deepseek-v4-pro", init.model, "the header names what really runs")
        val notice = events.filterIsInstance<AgentEvent.AssistantText>().single().text
        assertTrue("qoder/deepseek-v4-pro" in notice && "deepseek-official/deepseek-v4-pro" in notice, notice)
        assertTrue("pick qoder/deepseek-v4-pro" in notice, notice)
        assertTrue(events.indexOf(init) < events.indexOfFirst { it is AgentEvent.AssistantText }, "after the announcement")
        assertTrue(w.none { """"method":"session/set_config_option"""" in it }, "history is never re-applied")
        assertTrue(events.none { it is AgentEvent.TurnResult }, "and never blocks the session")
    }

    @Test
    fun `no notice when the session resumed on the model it last ran on`() = runBlocking {
        transcript("deepseek-official", "deepseek-v4-pro")
        assertTrue(resume().none { it is AgentEvent.AssistantText })
    }

    /** A model the user asked for is a request, applied as such — the history comparison is not made. */
    @Test
    fun `no notice when the resume carries a requested model`() = runBlocking {
        transcript("qoder", "deepseek-v4-pro")
        val events = resume(model = "qoder/deepseek-v4-pro")
        assertTrue(events.none { it is AgentEvent.AssistantText }, events.toString())
        assertTrue(w.single { """"method":"session/set_config_option"""" in it }.contains("""[\"qoder\",\"deepseek-v4-pro\"]"""))
    }

    private companion object {
        const val CWD = "/work/dsh-resume-notice"
        const val SESSION = "session-resume-notice-1"
    }
}
