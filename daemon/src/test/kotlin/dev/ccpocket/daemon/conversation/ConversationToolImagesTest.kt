package dev.ccpocket.daemon.conversation

import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.claude.StreamParser
import dev.ccpocket.daemon.media.ImageThumbnail
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.ToolEvent
import dev.ccpocket.protocol.ToolPhase
import dev.ccpocket.protocol.TurnDone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.io.path.absolutePathString
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #332 through the REAL pump: a tool that returned a screenshot gets the one RESULT frame the
 * phone needs to hang a thumbnail on, and every other tool stays START-only exactly as before.
 *
 * That "exactly as before" is the load-bearing half. Ordinary tools have never had a RESULT on this
 * wire, and the App's own RESULT handler stamps `ok` onto the card — so emitting one for every Bash
 * call would turn today's neutral transcript into a wall of ✓ on already-shipped clients. The picture
 * is the whole justification for breaking that silence, so no picture means no frame.
 */
class ConversationToolImagesTest {

    /** A small real PNG the thumbnailer can actually decode — a fake payload would be dropped. */
    private fun pngBase64(): String {
        val img = BufferedImage(200, 120, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 120) for (x in 0 until 200) img.setRGB(x, y, (x shl 16) or (y shl 8) or 0x30)
        val bos = ByteArrayOutputStream()
        ImageIO.write(img, "png", bos)
        return Base64.getEncoder().encodeToString(bos.toByteArray())
    }

    private fun imageBlock(b64: String) =
        """{"type":"image","source":{"type":"base64","media_type":"image/png","data":"$b64"}}"""

    private class StubBackend(private val script: Path) : AgentBackend {
        override val kind = AgentKind.CLAUDE
        override fun processBuilder(spec: AgentSpec) = ProcessBuilder("cat", script.absolutePathString())
        override suspend fun attach(io: AgentIo, spec: AgentSpec) {}
        override suspend fun parse(line: String): List<AgentEvent> = StreamParser.parse(line)
        override suspend fun sendPrompt(text: String, images: List<ImageData>) {}
        override suspend fun interrupt() {}
        override suspend fun respondPermission(
            askId: String, allow: Boolean, remember: Boolean,
            originalInput: JsonObject?, updatedInput: String?, denyMessage: String?,
        ) {}
        override fun applySettings(mode: PermissionMode?, model: String?, effort: String?) = true
        override suspend fun onProcessEnded(sessionId: String?) {}
        override fun transcriptDir(workdir: String): Path = Path.of(workdir)
        override fun listSessions(workdir: String): List<SessionSummary> = emptyList()
        override fun replayHistory(workdir: String, sessionId: String): List<HistoryMessage> = emptyList()
        override fun resumeContextTokens(workdir: String, sessionId: String): Long? = null
    }

    /** Run [lines] through a real Conversation and return every ToolEvent the phone would have seen. */
    private fun pump(lines: List<String>): List<ToolEvent> = runBlocking {
        if (System.getProperty("os.name").lowercase().contains("win")) return@runBlocking emptyList()
        val dir = Files.createTempDirectory("ccp-convo-img")
        val script = dir.resolve("stream.jsonl").apply { writeText(lines.joinToString("\n") + "\n") }
        val frames = ArrayList<Frame>()
        val scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val convo = Conversation(
            convoId = "c332", initialWorkdir = dir, initialMode = PermissionMode.DEFAULT,
            initialSink = { f -> synchronized(frames) { frames.add(f) } },
            parentScope = scope, backend = StubBackend(script),
        )
        try {
            convo.open(resumeId = null, model = null, takeOver = true)
            withTimeout(15_000) {
                while (synchronized(frames) { frames.none { it is TurnDone } }) delay(20)
            }
            synchronized(frames) { frames.filterIsInstance<ToolEvent>().toList() }
        } finally {
            convo.close()
            scope.cancel()
        }
    }

    private val header = """{"type":"system","subtype":"init","session_id":"s332","cwd":"/tmp","model":"claude-sonnet-5"}"""
    private val footer = """{"type":"result","subtype":"success","is_error":false,"result":"ok","usage":{"input_tokens":1,"output_tokens":1}}"""

    @Test
    fun a_tool_that_returned_a_screenshot_gets_a_RESULT_with_thumbnails() {
        val events = pump(
            listOf(
                header,
                """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"s1","name":"browser_take_screenshot","input":{"filename":"page.png"}}]}}""",
                """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"s1","content":[{"type":"text","text":"Took it"},${imageBlock(pngBase64())}]}]}}""",
                footer,
            ),
        )
        if (events.isEmpty()) return // windows: the fixture is replayed via `cat`

        assertEquals(listOf(ToolPhase.START, ToolPhase.RESULT), events.map { it.phase })
        val result = events.last()
        assertEquals("s1", result.toolUseId)
        assertEquals("browser_take_screenshot", result.tool, "the RESULT must name the tool for old clients")
        assertEquals(true, result.ok, "ok is a REAL boolean — an old App renders it as the card's status chip")
        assertEquals(1, result.images.size)
        assertTrue(result.images[0].base64.length <= ImageThumbnail.MAX_BASE64_BYTES, "must be a thumbnail, not the original")
        assertEquals("Took it", result.output)
    }

    @Test
    fun a_failed_screenshot_result_reports_ok_false_not_null() {
        val events = pump(
            listOf(
                header,
                """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"s2","name":"browser_take_screenshot","input":{}}]}}""",
                """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"s2","is_error":true,"content":[${imageBlock(pngBase64())}]}]}}""",
                footer,
            ),
        )
        if (events.isEmpty()) return
        assertEquals(false, events.single { it.phase == ToolPhase.RESULT }.ok)
    }

    @Test
    fun ordinary_tools_without_images_stay_START_only() {
        val events = pump(
            listOf(
                header,
                """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"b1","name":"Bash","input":{"command":"ls"}}]}}""",
                """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"b1","content":"a\nb"}]}}""",
                """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"b2","name":"Read","input":{"file_path":"/tmp/x.txt"}}]}}""",
                """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"b2","content":"hello"}]}}""",
                footer,
            ),
        )
        if (events.isEmpty()) return
        assertEquals(listOf(ToolPhase.START, ToolPhase.START), events.map { it.phase })
        assertTrue(events.none { it.images.isNotEmpty() })
    }

    @Test
    fun an_image_bearing_tool_does_not_give_its_neighbours_a_RESULT() {
        // the mixed turn: only the screenshot breaks the START-only rule, and only for itself
        val events = pump(
            listOf(
                header,
                """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"b1","name":"Bash","input":{"command":"ls"}}]}}""",
                """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"b1","content":"a"}]}}""",
                """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"s1","name":"Read","input":{"file_path":"/tmp/a.png"}}]}}""",
                """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"s1","content":[${imageBlock(pngBase64())}]}]}}""",
                """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"b2","name":"Bash","input":{"command":"pwd"}}]}}""",
                """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"b2","content":"/tmp"}]}}""",
                footer,
            ),
        )
        if (events.isEmpty()) return
        val results = events.filter { it.phase == ToolPhase.RESULT }
        assertEquals(1, results.size, "exactly one RESULT — the one that carried a picture")
        assertEquals("s1", results.single().toolUseId)
        assertEquals(3, events.count { it.phase == ToolPhase.START })
    }

    @Test
    fun an_undecodable_image_emits_no_RESULT_at_all() {
        // fail-soft all the way through: nothing to show means the card stays exactly as it was
        val events = pump(
            listOf(
                header,
                """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"s1","name":"Read","input":{"file_path":"/tmp/a.webp"}}]}}""",
                """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"s1","content":[{"type":"image","source":{"type":"base64","media_type":"image/webp","data":"bm90IGFuIGltYWdl"}}]}]}}""",
                footer,
            ),
        )
        if (events.isEmpty()) return
        assertEquals(listOf(ToolPhase.START), events.map { it.phase })
    }

    @Test
    fun a_result_INSIDE_a_subagent_never_gets_its_own_row() {
        // an inner tool folds into the parent's card as a child count; a thumbnail row for it would be
        // a main-chain row the sub-agent was supposed to hide
        val events = pump(
            listOf(
                header,
                """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"a1","name":"Task","input":{"subagent_type":"general-purpose","description":"look"}}]}}""",
                """{"type":"assistant","parent_tool_use_id":"a1","message":{"content":[{"type":"tool_use","id":"i1","name":"Read","input":{"file_path":"/tmp/a.png"}}]}}""",
                """{"type":"user","parent_tool_use_id":"a1","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"i1","content":[${imageBlock(pngBase64())}]}]}}""",
                """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"a1","content":[{"type":"text","text":"done"}]}]}}""",
                footer,
            ),
        )
        if (events.isEmpty()) return
        val results = events.filter { it.phase == ToolPhase.RESULT }
        assertEquals(1, results.size, "only the sub-agent card settles")
        assertEquals("a1", results.single().toolUseId)
        assertEquals(emptyList(), results.single().images)
    }

    @Test
    fun a_subagent_whose_own_result_carries_an_image_still_settles_exactly_once() {
        // the sub-agent card takes its report through its OWN RESULT path; the image emitter must not
        // add a second, duplicate RESULT for the same tool_use id
        val events = pump(
            listOf(
                header,
                """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"a1","name":"Task","input":{"subagent_type":"general-purpose","description":"shoot"}}]}}""",
                """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"a1","content":[{"type":"text","text":"done"},${imageBlock(pngBase64())}]}]}}""",
                footer,
            ),
        )
        if (events.isEmpty()) return
        val results = events.filter { it.phase == ToolPhase.RESULT }
        assertEquals(1, results.size, "a sub-agent must never get two RESULT frames")
        assertEquals("done", results.single().output)
    }
}
