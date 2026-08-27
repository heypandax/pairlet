package dev.ccpocket.daemon.conversation

import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.SessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Issue #320 — [AgentEvent.RuntimeMeta] is the ONLY way a non-Claude backend can name the model, effort and
 * context window it is really running under, because those facts do not exist yet when its init frame is
 * emitted. This pins what the phone actually receives: the announce, not just the event.
 *
 * The backend is a stub on purpose. What the dsh wire looks like is [dev.ccpocket.daemon.dsh.DshRuntimeMetaTest]'s
 * job; what the CONVERSATION does with the result is this one's, and the two must not be able to fail together.
 */
class ConversationRuntimeMetaTest {

    private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")

    /**
     * Turns marker lines into events. The stream is gated on the prompt (`read go`) for the same reason
     * [ConversationContinuationGraceTest] gates its own: the process is launched BEFORE `executing` is
     * armed, so an ungated `cat` can race its whole script past the pump.
     */
    private class MetaBackend(private val script: Path) : AgentBackend {
        override val kind = AgentKind.DSH
        private var io: AgentIo? = null
        override fun processBuilder(spec: AgentSpec): ProcessBuilder =
            ProcessBuilder("sh", "-c", "read go; cat '${script.absolutePathString()}'; sleep 30")
        override suspend fun attach(io: AgentIo, spec: AgentSpec) { this.io = io }
        override suspend fun parse(line: String): List<AgentEvent> = when (line.trim()) {
            // dsh's init is synthetic and carries no model — exactly the shape that left the header on "default"
            "init" -> listOf(AgentEvent.SessionInit(SID, "/tmp", model = null))
            "meta" -> listOf(AgentEvent.RuntimeMeta(model = MODEL, effort = "high", contextWindow = 1_000_000))
            // a DIFFERENT window: the terminator the duplicate-suppression assertion counts up to
            "narrow" -> listOf(AgentEvent.RuntimeMeta(contextWindow = 200_000))
            else -> listOf(AgentEvent.Ignored(line))
        }
        override suspend fun sendPrompt(text: String, images: List<ImageData>) { io?.writeLine("go") }
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

    private fun harness(
        lines: List<String>,
        openModel: String?,
        until: (List<Frame>) -> Boolean,
        body: (List<SessionLive>) -> Unit,
    ) = runBlocking {
        val script = Files.createTempDirectory("ccp-meta-fx").resolve("stream.txt")
            .apply { writeText(lines.joinToString("\n") + "\n") }
        val frames = ArrayList<Frame>()
        val scope = CoroutineScope(Dispatchers.Default)
        val convo = Conversation(
            convoId = "cMeta", initialWorkdir = Files.createTempDirectory("ccp-meta"),
            initialMode = PermissionMode.DEFAULT,
            initialSink = { f -> synchronized(frames) { frames.add(f) } },
            parentScope = scope, backend = MetaBackend(script),
        )
        try {
            convo.open(resumeId = null, model = openModel)
            convo.sendPrompt("go") // lazy start (issue #61): this is what launches the scripted process
            withTimeout(10_000) { while (!until(synchronized(frames) { frames.toList() })) delay(20) }
            body(synchronized(frames) { frames.filterIsInstance<SessionLive>() })
        } finally {
            convo.close()
            scope.cancel()
        }
    }

    @Test
    fun runtime_metadata_reaches_the_announce_and_re_announces_only_when_it_changes() {
        if (isWindows()) return
        harness(
            lines = listOf("init", "meta", "meta", "narrow"),
            openModel = null,
            until = { fs -> fs.any { it is SessionLive && it.contextWindow == 200_000L } },
        ) { lives ->
            // the regression baseline: the init announce really does carry nothing — this is the state the
            // phone was stuck in for the session's whole life before #320
            val first = lives.first()
            assertNull(first.model)
            assertNull(first.contextWindow)
            // …and the metadata frame corrects all three fields at once
            val corrected = lives.filter { it.contextWindow == 1_000_000L }
            assertEquals(MODEL, corrected.first().model)
            assertEquals("high", corrected.first().effort)
            // exactly once, though `meta` arrived twice: dsh restates its model on EVERY assistant/message,
            // and an unconditional re-announce would push one identical SessionLive per step, over the relay
            assertEquals(1, corrected.size, "an unchanged RuntimeMeta must not re-announce")
        }
    }

    /** The user's pick outranks the backend's echo — the same rule `switchModel` enforces by clearing the
     *  backfill. A session opened on a chosen model must not silently become whatever answered. */
    @Test
    fun an_explicitly_chosen_model_survives_the_backends_own_report() {
        if (isWindows()) return
        harness(
            lines = listOf("init", "meta"),
            openModel = "deepseek-reasoner",
            until = { fs -> fs.any { it is SessionLive && it.contextWindow == 1_000_000L } },
        ) { lives ->
            val announced = lives.last { it.contextWindow == 1_000_000L }
            assertEquals("deepseek-reasoner", announced.model) // NOT MODEL — the choice stands
            assertEquals(1_000_000L, announced.contextWindow) // the window still lands: nobody chose one
        }
    }

    private companion object {
        const val SID = "dsh-sid"
        const val MODEL = "deepseek-v4-flash"
    }
}
