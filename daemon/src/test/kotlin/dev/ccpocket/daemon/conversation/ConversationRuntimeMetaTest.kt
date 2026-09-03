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
import kotlinx.coroutines.TimeoutCancellationException
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
    private class MetaBackend(private val script: Path, private val disk: Disk = Disk()) : AgentBackend {
        /**
         * What the RESUMED transcript is supposed to contain — the three `resume*` hooks, stubbed.
         * [gate], when set, holds every hook until the test releases it: that is how a multi-MB transcript
         * parse finishing AFTER the first live frame is reproduced deterministically.
         */
        class Disk(
            val model: String? = null,
            val window: Long? = null,
            val effort: String? = null,
            val gate: java.util.concurrent.CountDownLatch? = null,
        )

        private fun <T> onDisk(read: () -> T): T {
            disk.gate?.await()
            return read()
        }

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
        override fun resumeModel(workdir: String, sessionId: String): String? = onDisk { disk.model }
        override fun resumeContextWindow(workdir: String, sessionId: String): Long? = onDisk { disk.window }
        override fun resumeEffort(workdir: String, sessionId: String): String? = onDisk { disk.effort }
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

    // ---- the RESUME half: what a reopened session announces before it has run a turn ----

    /** A conversation opened on [RESUMED] with [disk] standing in for its transcript. A plain open launches
     *  NOTHING (lazy start, issue #61) — which is exactly the state this half of #320 is about: a session
     *  the user reopened and has not spoken to yet. */
    private class Resumed(
        val convo: Conversation,
        private val raw: ArrayList<Frame>,
        private val scope: CoroutineScope,
    ) {
        fun lives(): List<SessionLive> = synchronized(raw) { raw.filterIsInstance<SessionLive>() }

        /** The announce [pick] selects, waited for rather than sampled: every emit here is a coroutine
         *  hop behind the call that caused it. */
        private suspend fun await(what: String, pick: (List<SessionLive>) -> SessionLive?): SessionLive =
            try {
                withTimeout(10_000) {
                    var hit: SessionLive? = null
                    while (hit == null) {
                        hit = pick(lives())
                        if (hit == null) delay(20)
                    }
                    hit
                }
            } catch (_: TimeoutCancellationException) {
                throw AssertionError("timed out waiting for $what; announces so far: ${lives()}")
            }

        /** The newest announce satisfying [pred]. */
        suspend fun awaitLive(what: String, pred: (SessionLive) -> Boolean): SessionLive =
            await(what) { it.lastOrNull(pred) }

        /**
         * The SECOND announce satisfying [pred] — which for a resume is the seeded one.
         *
         * Since issue #340 `open()` announces TWICE: a SPARSE frame emitted before any transcript read
         * (so the phone's 8s open deadline never contains a disk access), then the SEEDED re-announce
         * carrying what those reads recovered. Every assertion below about what a RESUMED session
         * announces means the second frame; [awaitLive]'s newest-match would settle on the sparse one
         * whenever it samples while the reads are still running — which under a gated read is always.
         */
        suspend fun awaitSeededOpen(what: String, pred: (SessionLive) -> Boolean): SessionLive =
            await(what) { it.filter(pred).getOrNull(1) }

        suspend fun shutdown() {
            convo.close()
            scope.cancel()
        }
    }

    private fun resumed(disk: MetaBackend.Disk, script: List<String> = emptyList()): Resumed {
        val file = Files.createTempDirectory("ccp-resume-fx").resolve("stream.txt")
            .apply { writeText(script.joinToString("\n") + "\n") }
        val frames = ArrayList<Frame>()
        val scope = CoroutineScope(Dispatchers.Default)
        val convo = Conversation(
            convoId = "cResume", initialWorkdir = Files.createTempDirectory("ccp-resume"),
            initialMode = PermissionMode.DEFAULT,
            initialSink = { f -> synchronized(frames) { frames.add(f) } },
            parentScope = scope, backend = MetaBackend(file, disk),
        )
        return Resumed(convo, frames, scope)
    }

    /**
     * (a) THE BUG. A dsh session names its model, level and window only on the `request/context` and
     * `request/header` frames of a RUNNING request, so a session the user merely reopened had nothing to
     * announce and its header read
     * "default" with no denominator until it happened to run another turn.
     */
    @Test
    fun a_resumed_session_announces_the_model_window_and_effort_its_transcript_recorded() {
        if (isWindows()) return
        runBlocking {
            val fx = resumed(MetaBackend.Disk(model = MODEL, window = 1_000_000, effort = "high"))
            try {
                fx.convo.open(resumeId = RESUMED, model = null)
                val live = fx.awaitSeededOpen("the seeded resume announce") { it.sessionId == RESUMED }
                assertEquals(MODEL, live.model)
                assertEquals(1_000_000L, live.contextWindow)
                assertEquals("high", live.effort)
            } finally {
                fx.shutdown()
            }
        }
    }

    /** (b) The honest negative: a transcript that recorded none of the three resumes as UNKNOWN — no guessed
     *  window, no config-default level — and the FIRST live frame then fills it in. */
    @Test
    fun a_transcript_with_no_metadata_stays_unknown_until_the_live_wire_speaks() {
        if (isWindows()) return
        runBlocking {
            val fx = resumed(MetaBackend.Disk(), script = listOf("init", "meta"))
            try {
                fx.convo.open(resumeId = RESUMED, model = null)
                val opened = fx.awaitSeededOpen("the seeded resume announce") { it.sessionId == RESUMED }
                assertNull(opened.model, "nothing on disk said which model — inventing one is worse than blank")
                assertNull(opened.contextWindow)
                assertNull(opened.effort)

                fx.convo.sendPrompt("go") // lazy start: this is what launches the scripted process
                val corrected = fx.awaitLive("the live correction") { it.contextWindow == 1_000_000L }
                assertEquals(MODEL, corrected.model)
                assertEquals("high", corrected.effort)
            } finally {
                fx.shutdown()
            }
        }
    }

    /**
     * (c) THE RACE, and the reason the backfill takes a lock at all. The open-time read can be a multi-MB
     * transcript parse, so the first live `request/context` may well land while it is still running — and
     * what it eventually returns is by construction the OLDER of the two. A stale window/level/model must
     * never overwrite the live one it arrived after. [MetaBackend.Disk.gate] pins that ordering instead of
     * hoping for it.
     */
    @Test
    fun a_slow_transcript_read_can_never_overwrite_a_live_value_that_already_landed() {
        if (isWindows()) return
        runBlocking {
            val gate = java.util.concurrent.CountDownLatch(1)
            val fx = resumed(
                // deliberately ALL stale: yesterday's model, a narrower window, a lower level
                MetaBackend.Disk(model = "deepseek-v4", window = 200_000, effort = "low", gate = gate),
                script = listOf("init", "meta"),
            )
            try {
                fx.convo.open(resumeId = RESUMED, model = null)
                fx.convo.sendPrompt("go")
                // the live wire speaks first…
                fx.awaitLive("the live metadata") { it.contextWindow == 1_000_000L }
                // …and only then does the transcript parse come back with its older answer
                gate.countDown()
                val announced = fx.awaitSeededOpen("the post-backfill announce") { it.sessionId == RESUMED }
                assertEquals(1_000_000L, announced.contextWindow, "the live window must survive the backfill")
                assertEquals("high", announced.effort)
                assertEquals(MODEL, announced.model)
                // and no announce anywhere in the session ever carried the stale readings
                val stale = fx.lives().filter {
                    it.contextWindow == 200_000L || it.effort == "low" || it.model == "deepseek-v4"
                }
                assertEquals(emptyList(), stale, "a stale transcript value must never reach the phone")
            } finally {
                gate.countDown() // never leave a hook blocked if an assertion threw first
                fx.shutdown()
            }
        }
    }

    private companion object {
        const val SID = "dsh-sid"
        const val MODEL = "deepseek-v4-flash"
        const val RESUMED = "dsh-resumed-sid"
    }
}
