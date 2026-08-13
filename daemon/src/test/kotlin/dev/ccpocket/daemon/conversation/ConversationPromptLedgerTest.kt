package dev.ccpocket.daemon.conversation

import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.claude.StreamParser
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.PromptAck
import dev.ccpocket.protocol.SessionSummary
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #122 (prompt swallowing) — the unconsumed-prompt ledger, dedupe release, and the settings
 * relaunch continuation grace, exercised through the REAL Conversation + pump. A scripted backend
 * plays each successive launch as a stub OS process (`sh`/`cat` over stream-json fixtures, the
 * shapes probed off claude 2.1.204); prompts are only PROVEN consumed by a `--replay-user-messages`
 * user echo, which is exactly what the fixtures do (or pointedly don't) contain.
 */
class ConversationPromptLedgerTest {

    private fun win() = System.getProperty("os.name").lowercase().contains("win")

    /** Plays launch N as the Nth ProcessBuilder recipe (the last one repeats if exceeded). */
    private class ScriptedBackend(private val launches: List<() -> ProcessBuilder>) : AgentBackend {
        val specs = CopyOnWriteArrayList<AgentSpec>()
        val prompts = CopyOnWriteArrayList<String>()
        override val kind = AgentKind.CLAUDE
        override fun processBuilder(spec: AgentSpec): ProcessBuilder {
            specs.add(spec)
            return launches[minOf(specs.size, launches.size) - 1]()
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

    private fun dyingProcess(afterMs: Long = 400): () -> ProcessBuilder =
        { ProcessBuilder("sh", "-c", "sleep ${afterMs / 1000.0}; exit 7") }

    private fun idleProcess(): () -> ProcessBuilder = { ProcessBuilder("sh", "-c", "sleep 30") }

    /** A process that replays [lines] then idles on a real claude's schedule (never exits mid-test). */
    private fun fixtureProcess(lines: List<String>): () -> ProcessBuilder {
        val f = Files.createTempDirectory("ccp-ledger-fx").resolve("stream.jsonl")
            .apply { writeText(lines.joinToString("\n") + "\n") }
        return { ProcessBuilder("sh", "-c", "cat '${f.absolutePathString()}'; sleep 30") }
    }

    /** Emit each phase, then wait before the next one. Lets tests inspect the real Conversation state in
     *  the otherwise stdout-silent gap between one TurnResult and a queued turn's consumption replay. */
    private fun phasedFixtureProcess(phases: List<Pair<List<String>, Long>>): () -> ProcessBuilder {
        val root = Files.createTempDirectory("ccp-ledger-phases")
        val command = phases.mapIndexed { index, (lines, delayMs) ->
            val file = root.resolve("$index.jsonl").apply { writeText(lines.joinToString("\n") + "\n") }
            "cat '${file.absolutePathString()}'; sleep ${delayMs / 1000.0}"
        }.joinToString("; ") + "; sleep 30"
        return { ProcessBuilder("sh", "-c", command) }
    }

    private fun withConvo(backend: ScriptedBackend, body: suspend (Conversation, () -> List<Frame>) -> Unit) =
        runBlocking {
            val dir = Files.createTempDirectory("ccp-ledger")
            val frames = ArrayList<Frame>()
            val scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
            val convo = Conversation(
                convoId = "cLedger", initialWorkdir = dir, initialMode = PermissionMode.DEFAULT,
                initialSink = { f -> synchronized(frames) { frames.add(f) } },
                parentScope = scope, backend = backend,
            )
            try {
                convo.open(resumeId = null, model = null) // plain lazy open — the first prompt launches
                body(convo) { synchronized(frames) { frames.toList() } }
            } finally {
                convo.close()
                scope.cancel()
            }
        }

    private suspend fun await(frames: () -> List<Frame>, cond: (List<Frame>) -> Boolean) {
        withTimeout(10_000) { while (!cond(frames())) delay(20) }
    }

    // ---- ③ ledger + spawn re-injection ----

    @Test
    fun crash_respawn_reinjects_unconsumed_prompts_in_order() {
        if (win()) return
        // launch 1 dies without ever consuming anything (no user replay on stdout); launch 2 idles
        val backend = ScriptedBackend(listOf(dyingProcess(), idleProcess()))
        withConvo(backend) { convo, frames ->
            convo.sendPrompt("P1", promptId = "p1")
            convo.sendPrompt("P2", promptId = "p2")
            await(frames) { fs -> fs.any { it is PocketError && it.code == "process_exited" } }
            // the next prompt respawns — and the fresh process is re-handed P1+P2 first, in order
            convo.sendPrompt("P3", promptId = "p3")
            await(frames) { backend.prompts.size >= 5 }
            assertEquals(listOf("P1", "P2", "P1", "P2", "P3"), backend.prompts.toList())
            assertEquals(2, backend.specs.size, backend.specs.toString())
        }
    }

    @Test
    fun replayed_prompt_is_settled_and_never_reinjected() {
        if (win()) return
        // launch 1 REPLAYS P1 (the CLI's consumption receipt) before finishing the turn;
        // launch 2 (the settings relaunch) must NOT receive P1 again
        val backend = ScriptedBackend(
            listOf(
                fixtureProcess(
                    listOf(
                        """{"type":"system","subtype":"init","session_id":"s122","cwd":"/tmp","model":"claude-sonnet-5"}""",
                        """{"type":"user","message":{"role":"user","content":"P1"}}""",
                        """{"type":"assistant","message":{"content":[{"type":"text","text":"ok"}]}}""",
                        """{"type":"result","subtype":"success","is_error":false,"result":"ok","usage":{"input_tokens":1,"output_tokens":1}}""",
                    ),
                ),
                idleProcess(),
            ),
        )
        System.setProperty("ccpocket.relaunch.graceMs", "0")
        try {
            withConvo(backend) { convo, frames ->
                convo.sendPrompt("P1", promptId = "p1")
                await(frames) { fs -> fs.any { it is TurnDone } }
                convo.switchModel("opus") // arms the deferred relaunch (claude bakes flags at launch)
                convo.sendPrompt("P2", promptId = "p2")
                await(frames) { backend.prompts.size >= 2 }
                delay(200) // settle: a buggy re-injection would append a third prompt
                assertEquals(2, backend.specs.size, backend.specs.toString()) // the relaunch DID happen
                assertEquals(listOf("P1", "P2"), backend.prompts.toList()) // …but P1 was consumed, not re-sent
            }
        } finally {
            System.clearProperty("ccpocket.relaunch.graceMs")
        }
    }

    // ---- ④ dedupe release for a lost prompt ----

    @Test
    fun lost_promptId_retry_reruns_instead_of_hollow_reack() {
        if (win()) return
        val backend = ScriptedBackend(listOf(dyingProcess(), idleProcess()))
        withConvo(backend) { convo, frames ->
            convo.sendPrompt("P1", promptId = "p1")
            await(frames) { fs -> fs.any { it is PocketError && it.code == "process_exited" } }
            // the App's turn-stalled auto-resend (#104): same promptId again. The write was lost
            // (no replay, process gone) — it must RE-RUN, not vanish behind an idempotent re-ack.
            convo.sendPrompt("P1", promptId = "p1")
            await(frames) { backend.prompts.size >= 2 }
            assertEquals(listOf("P1", "P1"), backend.prompts.toList())
            assertEquals(2, frames().count { it is PromptAck && it.promptId == "p1" })
            assertEquals(2, backend.specs.size, backend.specs.toString()) // retry respawned the agent
        }
    }

    @Test
    fun queued_promptId_retry_on_live_process_stays_a_reack() {
        if (win()) return
        // the process is alive and simply hasn't consumed the prompt yet (mid-turn queue) — a retry
        // must NOT double-run it (issue #66 semantics are preserved for the live case)
        val backend = ScriptedBackend(listOf(idleProcess()))
        withConvo(backend) { convo, frames ->
            convo.sendPrompt("P1", promptId = "p1")
            await(frames) { fs -> fs.any { it is PromptAck } }
            convo.sendPrompt("P1", promptId = "p1")
            await(frames) { fs -> fs.count { it is PromptAck && it.promptId == "p1" } >= 2 }
            delay(100)
            assertEquals(listOf("P1"), backend.prompts.toList()) // one run, two receipts
            assertEquals(1, backend.specs.size)
        }
    }

    @Test
    fun queuedPromptKeepsAuthoritativeWorkAcrossThePriorTurnsResult() {
        if (win()) return
        val init = """{"type":"system","subtype":"init","session_id":"s-queued","cwd":"/tmp","model":"claude-sonnet-5"}"""
        val firstReplay = """{"type":"user","message":{"role":"user","content":"P1"}}"""
        val firstChunk = """{"type":"assistant","message":{"content":[{"type":"text","text":"working"}]}}"""
        val firstResult = """{"type":"result","subtype":"success","is_error":false,"result":"first","usage":{"input_tokens":1,"output_tokens":1}}"""
        val secondReplay = """{"type":"user","message":{"role":"user","content":"P2"}}"""
        val secondChunk = """{"type":"assistant","message":{"content":[{"type":"text","text":"second"}]}}"""
        val secondResult = """{"type":"result","subtype":"success","is_error":false,"result":"second","usage":{"input_tokens":1,"output_tokens":1}}"""
        val backend = ScriptedBackend(
            listOf(
                phasedFixtureProcess(
                    listOf(
                        listOf(init, firstReplay, firstChunk) to 500L,
                        listOf(firstResult) to 1_000L,
                        listOf(secondReplay, secondChunk, secondResult) to 0L,
                    ),
                ),
            ),
        )

        withConvo(backend) { convo, frames ->
            convo.sendPrompt("P1", promptId = "p1")
            await(frames) { fs -> fs.any { it is dev.ccpocket.protocol.AssistantChunk } }
            convo.sendPrompt("P2", promptId = "p2")
            await(frames) { fs -> fs.count { it is TurnDone } == 1 }

            assertTrue(
                convo.hasAuthoritativeTurnWork(),
                "the queued prompt shields the gap after the previous turn result",
            )
            delay(300)
            assertTrue(convo.hasAuthoritativeTurnWork(), "stdout silence before queued consumption is still work")

            await(frames) { fs -> fs.count { it is TurnDone } == 2 }
            assertFalse(convo.hasAuthoritativeTurnWork(), "the queued turn's own result settles immediately")
        }
    }

    // ---- ⑤ settings relaunch continuation grace ----

    @Test
    fun phantom_early_result_defers_settings_relaunch_within_grace() {
        if (win()) return
        // fable's failure shape: an early `result` lands while the turn is actually still running
        // (executing flips false; the continuation may stay SILENT for a long thinking stretch).
        // A model switch + immediate next message inside the grace window must ride the CURRENT
        // process — killing it would take the in-flight turn (and the CLI's queue) with it.
        val backend = ScriptedBackend(
            listOf(
                fixtureProcess(
                    listOf(
                        """{"type":"system","subtype":"init","session_id":"s122g","cwd":"/tmp","model":"claude-fable-5"}""",
                        """{"type":"user","message":{"role":"user","content":"P1"}}""",
                        """{"type":"result","subtype":"success","is_error":false,"result":"phantom done","usage":{"input_tokens":1,"output_tokens":1}}""",
                    ),
                ),
                idleProcess(),
            ),
        )
        System.setProperty("ccpocket.relaunch.graceMs", "60000")
        try {
            withConvo(backend) { convo, frames ->
                convo.sendPrompt("P1", promptId = "p1")
                await(frames) { fs -> fs.any { it is TurnDone } } // the phantom result landed
                convo.switchModel("opus") // arms pendingRelaunch
                convo.sendPrompt("P2", promptId = "p2")
                await(frames) { backend.prompts.size >= 2 }
                delay(200)
                assertEquals(1, backend.specs.size, backend.specs.toString()) // NO relaunch — no killed turn
                assertEquals(listOf("P1", "P2"), backend.prompts.toList()) // P2 rides the live process
            }
        } finally {
            System.clearProperty("ccpocket.relaunch.graceMs")
        }
    }

    @Test
    fun settings_relaunch_still_lands_after_grace() {
        if (win()) return
        // sanity guard for the guard: with the grace elapsed (0ms) the deferred relaunch fires on the
        // next idle send — a model switch must still reach the CLI in bounded time (issue #84 intact)
        val backend = ScriptedBackend(
            listOf(
                fixtureProcess(
                    listOf(
                        """{"type":"system","subtype":"init","session_id":"s122h","cwd":"/tmp","model":"claude-sonnet-5"}""",
                        """{"type":"user","message":{"role":"user","content":"P1"}}""",
                        """{"type":"result","subtype":"success","is_error":false,"result":"done","usage":{"input_tokens":1,"output_tokens":1}}""",
                    ),
                ),
                idleProcess(),
            ),
        )
        System.setProperty("ccpocket.relaunch.graceMs", "0")
        try {
            withConvo(backend) { convo, frames ->
                convo.sendPrompt("P1", promptId = "p1")
                await(frames) { fs -> fs.any { it is TurnDone } }
                convo.switchModel("opus")
                convo.sendPrompt("P2", promptId = "p2")
                await(frames) { backend.prompts.size >= 2 }
                assertEquals(2, backend.specs.size, backend.specs.toString()) // relaunch-then-send fired
                assertTrue(backend.specs[1].model == "opus", backend.specs.toString())
                assertEquals(listOf("P1", "P2"), backend.prompts.toList())
            }
        } finally {
            System.clearProperty("ccpocket.relaunch.graceMs")
        }
    }
}
