package dev.ccpocket.daemon.conversation

import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.claude.StreamParser
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.StreamPiece
import dev.ccpocket.protocol.TurnDone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.absolutePathString
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The CLI session-lock heal (claude ≥2.1 refuses a bare `--resume <id>` of a session any LIVE process
 * has registered — exit 1 at startup, stderr-only hint to add --fork-session; probed on 2.1.204, see
 * scripts/probe-claude-wire.py `lock`). A stub backend plays the refused launch as a `sh -c "… >&2;
 * exit 1"` and the healed fork as `cat` over a success fixture, through the REAL Conversation pump.
 */
class ConversationSessionLockTest {

    private val lockStderr =
        "Error: Session held-1 is currently running as a background agent (bg). " +
            "Use claude agents to find and attach to it, or add --fork-session to branch off a copy."

    private val fixture = listOf(
        """{"type":"system","subtype":"init","session_id":"s-forked","cwd":"/tmp","model":"claude-sonnet-5"}""",
        """{"type":"assistant","message":{"content":[{"type":"text","text":"healed reply"}]}}""",
        """{"type":"result","subtype":"success","is_error":false,"result":"healed reply","usage":{"input_tokens":1,"output_tokens":1}}""",
    )

    /** Launches refuse (exit 1 + [stderr]) until [refuseLaunches] have happened, then replay [script]. */
    private class LockingBackend(
        private val script: Path,
        private val stderr: String,
        private val refuseLaunches: Int,
    ) : AgentBackend {
        val specs = CopyOnWriteArrayList<AgentSpec>()
        val prompts = CopyOnWriteArrayList<String>()
        override val kind = AgentKind.CLAUDE
        override fun processBuilder(spec: AgentSpec): ProcessBuilder {
            specs.add(spec)
            // single quotes: the real message contains backticks, which sh would substitute inside double quotes
            return if (specs.size <= refuseLaunches) ProcessBuilder("sh", "-c", "echo '$stderr' 1>&2; exit 1")
            // replay the fixture then stay alive on a real claude's schedule (it idles on stdin after the
            // result; a bare `cat` exiting would fire a spurious process_exited the assertions must not see)
            else ProcessBuilder("sh", "-c", "cat '${script.absolutePathString()}'; sleep 30")
        }
        override suspend fun attach(io: AgentIo, spec: AgentSpec) {}
        override suspend fun parse(line: String): List<AgentEvent> = StreamParser.parse(line)
        override suspend fun sendPrompt(text: String, images: List<ImageData>) { prompts.add(text) }
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

    private fun runPrompt(backend: LockingBackend, until: (List<Frame>) -> Boolean): List<Frame> = runBlocking {
        val dir = Files.createTempDirectory("ccp-lock")
        val frames = ArrayList<Frame>()
        val scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val convo = Conversation(
            convoId = "cLock", initialWorkdir = dir, initialMode = PermissionMode.DEFAULT,
            initialSink = { f -> synchronized(frames) { frames.add(f) } },
            parentScope = scope, backend = backend,
        )
        try {
            convo.open(resumeId = "held-1", model = null) // plain open: lazy — the first prompt launches
            convo.sendPrompt("hi")
            withTimeout(10_000) {
                while (!until(synchronized(frames) { frames.toList() })) delay(20)
            }
            delay(200) // settle: catch any extra launch/frames a buggy second retry would produce
        } finally {
            convo.close()
            scope.cancel()
        }
        synchronized(frames) { frames.toList() }
    }

    @Test
    fun lock_refusal_relaunches_forked_and_resends_prompt() {
        if (System.getProperty("os.name").lowercase().contains("win")) return // stubs run via sh/cat
        val script = Files.createTempDirectory("ccp-lock-fx").resolve("stream.jsonl")
            .apply { writeText(fixture.joinToString("\n") + "\n") }
        val backend = LockingBackend(script, lockStderr, refuseLaunches = 1)

        val frames = runPrompt(backend) { fs -> fs.any { it is TurnDone } }

        // one refused bare resume, then exactly one forked retry on the same anchor
        assertEquals(listOf(false, true), backend.specs.map { it.forkSession }, backend.specs.toString())
        assertEquals(listOf("held-1", "held-1"), backend.specs.map { it.resumeId })
        // the refused process took the prompt with it — the fresh one gets it again
        assertEquals(listOf("hi", "hi"), backend.prompts.toList())
        // healed, not errored: the phone sees the fork notice + the real reply, no process_exited
        assertTrue(frames.none { it is PocketError }, frames.filterIsInstance<PocketError>().toString())
        val texts = frames.filterIsInstance<AssistantChunk>().map { it.piece }
            .filterIsInstance<StreamPiece.Text>().joinToString("") { it.text }
        assertTrue("forked copy" in texts, texts)
        assertTrue("healed reply" in texts, texts)
    }

    @Test
    fun non_lock_death_still_surfaces_process_exited() {
        if (System.getProperty("os.name").lowercase().contains("win")) return
        val script = Files.createTempDirectory("ccp-lock-fx").resolve("stream.jsonl")
            .apply { writeText(fixture.joinToString("\n") + "\n") }
        val backend = LockingBackend(script, "Error: No conversation found with session ID: held-1", refuseLaunches = 1)

        val frames = runPrompt(backend) { fs -> fs.any { it is PocketError } }

        assertEquals(1, backend.specs.size, backend.specs.toString()) // no heal for an unrelated death
        val err = frames.filterIsInstance<PocketError>().single()
        assertEquals("process_exited", err.code)
        assertTrue("No conversation found" in (err.message))
    }

    /** Issue #328: the refusal hint is not always the LAST stderr line. A last-line-only reader lost
     *  the heal (and the phone got a meaningless "Node.js v…" as the whole reason) whenever the
     *  runtime printed anything after it; the tail scan must still find the marker. */
    @Test
    fun lock_refusal_buried_above_later_stderr_still_heals() {
        if (System.getProperty("os.name").lowercase().contains("win")) return
        val script = Files.createTempDirectory("ccp-lock-fx").resolve("stream.jsonl")
            .apply { writeText(fixture.joinToString("\n") + "\n") }
        // multi-line death: the marker, then two more lines ending in a bare Node version footer
        val buried = "$lockStderr\nnode:internal/process/esm_loader:34\nNode.js v24.16.0"
        val backend = LockingBackend(script, buried, refuseLaunches = 1)

        val frames = runPrompt(backend) { fs -> fs.any { it is TurnDone } }

        assertEquals(listOf(false, true), backend.specs.map { it.forkSession }, backend.specs.toString())
        assertTrue(frames.none { it is PocketError }, frames.filterIsInstance<PocketError>().toString())
    }

    /** Issue #328: a non-lock death's process_exited must carry the real cause from the middle of a
     *  multi-line dump, not merely the runtime footer that happened to be printed last. */
    @Test
    fun process_exited_carries_the_real_cause_from_a_multi_line_crash() {
        if (System.getProperty("os.name").lowercase().contains("win")) return
        val script = Files.createTempDirectory("ccp-lock-fx").resolve("stream.jsonl")
            .apply { writeText(fixture.joinToString("\n") + "\n") }
        // no apostrophes: the stub interpolates this into a single-quoted sh string
        val crash = "node:internal/fs/utils:355\n" +
            "Error: ENOENT: no such file or directory, open /tmp/gone.json\n" +
            "    at Object.openSync (node:fs:596:3)\n" +
            "Node.js v24.16.0"
        val backend = LockingBackend(script, crash, refuseLaunches = 1)

        val frames = runPrompt(backend) { fs -> fs.any { it is PocketError } }

        val err = frames.filterIsInstance<PocketError>().first()
        assertEquals("process_exited", err.code)
        assertTrue("ENOENT: no such file or directory" in err.message, err.message)
        assertTrue("Node.js v24.16.0" !in err.message, err.message)
        assertTrue(err.message.length <= Conversation.MAX_EXIT_SUMMARY_CHARS, err.message)
    }

    /** The cleartext push copy is unchanged by the richer summary: a usage-limit death still words the
     *  push (it carries the reset epoch the phone needs), any other death stays generic — the multi-line
     *  stderr must never leak onto the unsealed TEXT plane. */
    @Test
    fun usage_limit_wording_survives_a_multi_line_summary() {
        val summary = "agent process ended (exit 1) — node:internal/x:1\n" +
            "Claude AI usage limit reached|1751990400\nNode.js v24.16.0"
        assertTrue(dev.ccpocket.daemon.relay.PushPolicy.isUsageLimit(summary))
        assertEquals(1751990400_000L, dev.ccpocket.daemon.relay.PushPolicy.usageLimitResetAtMs(summary))
        assertEquals(false, dev.ccpocket.daemon.relay.PushPolicy.isUsageLimit("agent process ended (exit 1)"))
    }

    @Test
    fun lock_refusal_heals_at_most_once() {
        if (System.getProperty("os.name").lowercase().contains("win")) return
        val script = Files.createTempDirectory("ccp-lock-fx").resolve("stream.jsonl")
            .apply { writeText(fixture.joinToString("\n") + "\n") }
        // the forked retry is refused too (can't happen per the CLI's own check — guard the loop anyway)
        val backend = LockingBackend(script, lockStderr, refuseLaunches = 2)

        val frames = runPrompt(backend) { fs -> fs.any { it is PocketError } }

        assertEquals(2, backend.specs.size, backend.specs.toString()) // refused + one retry, never a third
        assertTrue(frames.filterIsInstance<PocketError>().any { it.code == "process_exited" })
    }
}
