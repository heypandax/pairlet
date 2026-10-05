package dev.ccpocket.daemon.dsh

import dev.ccpocket.daemon.acp.AcpLiveHarness
import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.protocol.PermissionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * LIVE end-to-end against the REAL `dsh` binary — the one test that proves the whole launch path
 * (argv, stdin wiring, handshake, session open, config write, a real turn) rather than a transcript of
 * it.
 *
 * OFF BY DEFAULT: it spawns Node, needs DeepSeek credentials and spends a (tiny) real inference. Run it
 * deliberately, and always after a dsh upgrade alongside `scripts/probe-dsh-acp.py`:
 *
 * ```
 *   CC_POCKET_DSH_LIVE=1 JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
 *     ./gradlew :daemon:test --tests 'dev.ccpocket.daemon.dsh.DshBackendLiveIT'
 * ```
 *
 * The daemon test JVM's `user.home` is `build/test-home`, so the in-process [DshPaths] would look there:
 * export `DSH_HOME` explicitly (the spawned dsh inherits the real `HOME` either way). Without DeepSeek
 * credentials, or to keep sessions out of the real store, run it account-free against a scripted local
 * model and a throwaway home holding a copy of `~/.dsh/profiles`:
 *
 * ```
 *   python3 scripts/acp-mock-model.py --port 18931 &
 *   mkdir -p /tmp/dsh-live-home && cp -R ~/.dsh/profiles /tmp/dsh-live-home/
 *   CC_POCKET_DSH_LIVE=1 DSH_HOME=/tmp/dsh-live-home DEEPSEEK_API_KEY=sk-mock DEEPSEEK_BASE_URL=http://127.0.0.1:18931 \
 *     JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :daemon:test --tests 'dev.ccpocket.daemon.dsh.DshBackendLiveIT'
 * ```
 *
 * `CC_POCKET_ACP_LIVE_LOG=<dir>` keeps every wire line of the harness-driven cases.
 */
@EnabledIfEnvironmentVariable(named = "CC_POCKET_DSH_LIVE", matches = "1")
class DshBackendLiveIT {

    @Test
    fun a_real_dsh_session_opens_switches_model_and_answers_a_turn() = runBlocking {
        val workdir = createTempDirectory("cc-pocket-dsh-live")
        val backend = DshBackend(null)
        val spec = AgentSpec(
            workdir = workdir,
            mode = PermissionMode.DEFAULT,
            model = "deepseek-v4-pro", // exercises the opaque-value join before the first turn
            effort = "high",
        )
        val process = backend.processBuilder(spec).start()
        val events = Channel<AgentEvent>(Channel.UNLIMITED)
        val lines = Channel<String>(Channel.UNLIMITED)

        val writer = process.outputStream.bufferedWriter()
        val io = AgentIo(
            writeLine = { line -> synchronized(writer) { writer.write(line); writer.write("\n"); writer.flush() } },
            emit = {},
            inject = { line -> lines.send(line) },
        )
        // The daemon's pump, in miniature: ONE ordered channel of lines, parsed in arrival order.
        val pump = launch(Dispatchers.IO) {
            for (line in lines) backend.parse(line).forEach { events.send(it) }
        }
        val reader = launch(Dispatchers.IO) {
            process.inputStream.bufferedReader().forEachLine { runBlocking { lines.send(it) } }
        }
        val stderr = launch(Dispatchers.IO) { process.errorStream.bufferedReader().forEachLine { } }

        try {
            backend.attach(io, spec)
            val init = awaitEvent<AgentEvent.SessionInit>(events, 120_000)
            assertTrue(init?.sessionId?.isNotBlank() == true, "no session id — is dsh >= 0.1.2-rc.1?")

            backend.sendPrompt("Reply with exactly: hello-from-cc-pocket. Do not use any tool.", emptyList())
            val text = StringBuilder()
            var settled = false
            while (!settled) {
                when (val e = withTimeoutOrNull(300_000) { events.receive() }) {
                    is AgentEvent.AssistantText -> text.append(e.text)
                    is AgentEvent.TurnResult -> {
                        assertTrue(!e.isError, "the turn failed: $text")
                        settled = true
                    }
                    null -> error("the turn never settled; text so far: $text")
                    else -> {}
                }
            }
            assertTrue(text.contains("hello-from-cc-pocket"), "unexpected reply: $text")
            // the model the user asked for is the model dsh reports back, not the one we requested
            assertEquals("deepseek-v4-pro", backend.liveModelForTest())
        } finally {
            reader.cancel(); pump.cancel(); stderr.cancel()
            lines.close(); events.close()
            runCatching { process.destroyForcibly() }
            runCatching { workdir.toFile().deleteRecursively() }
            DshCatalog.clearForTest()
        }
    }

    // ---- the ACP behaviour the shared AcpClient owns, against the real dsh (audit 2026-10-04) ----

    private val workdirs = ArrayList<java.nio.file.Path>()
    private val harnesses = ArrayList<AcpLiveHarness>()

    @AfterEach
    fun cleanupHarnesses() {
        harnesses.forEach { runCatching { it.close() } }
        workdirs.forEach { runCatching { it.toFile().deleteRecursively() } }
        DshCatalog.clearForTest()
    }

    private fun scratch(): java.nio.file.Path = createTempDirectory("cc-pocket-dsh-live").also { workdirs.add(it) }

    private suspend fun launch(
        backend: DshBackend,
        spec: AgentSpec,
        name: String,
        process: (() -> Process)? = null,
    ): AcpLiveHarness {
        val h = if (process == null) AcpLiveHarness(backend, spec, "dsh-$name")
        else AcpLiveHarness(backend, spec, "dsh-$name", process)
        harnesses += h
        return h.start()
    }

    private fun AcpLiveHarness.timings(what: String) {
        val config = msToResponses("session/set_config_option").map { (req, ms) ->
            val p = req.json?.get("params") as? kotlinx.serialization.json.JsonObject
            "${(p?.get("configId") as? JsonPrimitive)?.contentOrNull}=${ms}ms"
        }
        println("[dsh-live] $what: initialize→answer ${msToResponses("initialize").firstOrNull()?.second} ms, " +
            "session/new→first line ${msToFirstLineAfter("session/new")} ms, " +
            "session/resume→first line ${msToFirstLineAfter("session/resume")} ms, config writes $config")
    }

    /** Launch-time model/effort land BEFORE the opening prompt is written, even when the prompt was sent before
     *  the session existed — and each write answers well inside the 30 s config watchdog. */
    @Test
    fun the_launch_config_chain_gates_the_opening_prompt() = runBlocking {
        val spec = AgentSpec(workdir = scratch(), mode = PermissionMode.DEFAULT, model = "deepseek-v4-pro", effort = "max")
        val backend = DshBackend(null)
        val h = launch(backend, spec, "config")
        // sent at once: it has to wait behind the handshake, the session open AND the config chain
        backend.sendPrompt("Reply with exactly: config-ok. Do not use any tool.", emptyList())
        val turn = h.turn(120_000)
        h.dump("config"); h.timings("config")
        assertTrue(turn.result?.isError == false, "turn failed: ${turn.text}")
        assertTrue(turn.text.contains("config-ok"), "unexpected reply: ${turn.text}")
        val writes = h.msToResponses("session/set_config_option")
        assertEquals(2, writes.size, "expected a model write then an effort write")
        val prompt = h.outbound("session/prompt").single()
        val lastAnswer = h.lines.last { !it.outbound && it.method == null && it.id == writes.last().first.id }
        assertTrue(prompt.nanos >= lastAnswer.nanos, "the opening prompt went out before the config chain settled")
        assertTrue(writes.all { (_, ms) -> ms != null && ms < 30_000 }, "a config write was slow: $writes")
        assertEquals("deepseek-v4-pro", backend.liveModelForTest())
        assertTrue(turn.events.none { it is AgentEvent.AssistantText && it.text.contains("⚠️") }, "a config warning surfaced")
    }

    @Test
    fun a_session_resumes_and_answers() = runBlocking {
        val workdir = scratch()
        val first = launch(DshBackend(null), AgentSpec(workdir = workdir), "resume-a")
        val sid = awaitEvent<AgentEvent.SessionInit>(first.events, 60_000)?.sessionId
        assertTrue(sid != null, "no session")
        first.backend.sendPrompt("Reply with exactly: first-turn. Do not use any tool.", emptyList())
        assertTrue(first.turn().result?.isError == false)
        first.close(); harnesses.remove(first)

        val h = launch(DshBackend(null), AgentSpec(workdir = workdir, resumeId = sid), "resume-b")
        val before = ArrayList<AgentEvent>()
        val init = h.await<AgentEvent.SessionInit>(60_000, before)
        assertEquals(sid, init?.sessionId, "session/resume did not reopen the session")
        assertTrue(before.none { it is AgentEvent.AssistantText || it is AgentEvent.UserReplay }, "history leaked: $before")
        h.backend.sendPrompt("Reply with exactly: resumed-ok. Do not use any tool.", emptyList())
        val turn = h.turn()
        h.dump("resume"); h.timings("resume")
        assertTrue(turn.result?.isError == false && turn.text.contains("resumed-ok"), "after resume: ${turn.text}")
    }

    @Test
    fun approval_usage_and_cancel() = runBlocking {
        val backend = DshBackend(null)
        val h = launch(backend, AgentSpec(workdir = scratch()), "approve")
        awaitEvent<AgentEvent.SessionInit>(h.events, 60_000)
        val asks = ArrayList<AgentEvent.ControlRequest>()
        // dsh asks only for an escalation out of the sandbox; the scripted model requests one
        backend.sendPrompt(
            "BASHME: run `echo approved-run` with the bash tool using sandbox_permissions=danger-full-access " +
                "(justification: probe), then say done.",
            emptyList(),
        )
        val approved = h.turn { asks += it; backend.respondPermission(it.requestId, true, true, it.input, null, null) }
        assertTrue(approved.result?.isError == false, "approval turn: ${approved.text}")
        assertEquals(1, asks.size, "expected exactly one permission ask")
        val answer = h.lines.single { it.outbound && it.method == null && it.text.contains("optionId") }.text
        assertTrue(answer.contains("\"allow-once\""), "remember must still answer dsh's only allow option: $answer")
        val usage = approved.events.filterIsInstance<AgentEvent.AssistantUsage>()
        val window = approved.events.filterIsInstance<AgentEvent.RuntimeMeta>().mapNotNull { it.contextWindow }
        assertTrue(usage.isNotEmpty() && window.isNotEmpty(), "no usage_update reached the events: ${approved.events}")

        backend.sendPrompt("SLOW: count slowly from 1 to 40, one number per line. Do not use any tool.", emptyList())
        // dsh sends a message's text in ONE chunk when it ends, so there is no first chunk to wait for
        kotlinx.coroutines.delay(3_000)
        assertTrue(h.outbound("session/prompt").size == 2 && h.lines.none { !it.outbound && it.id == h.outbound("session/prompt")[1].id && it.method == null },
            "the slow turn ended before the cancel")
        val t0 = System.nanoTime()
        backend.interrupt()
        val cancelled = h.turn(30_000)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val slow = h.outbound("session/prompt")[1]
        val stop = h.lines.firstOrNull { !it.outbound && it.method == null && it.id == slow.id }?.text
        h.dump("approve"); h.timings("approve")
        println("[dsh-live] approve: ask=${asks.map { it.toolName to it.input }} answer=$answer usage=${usage.last()} " +
            "window=${window.last()}; cancel settled in $ms ms with $stop")
        assertTrue(cancelled.result != null && stop?.contains("cancelled") == true, "cancel: $stop")
    }

    /**
     * 3400c062: the SAME backend instance launching a second process (a relaunch) still opens the session
     * normally — and its handshake watchdog guards that process too. Before the fix the first process's
     * session-open id survived the relaunch, so a later process that never answered `initialize` hung forever.
     */
    @Test
    fun a_relaunched_process_is_guarded_by_the_handshake_watchdog() = runBlocking {
        val workdir = scratch()
        val backend = DshBackend(null, handshakeTimeoutMs = 8_000)
        val first = launch(backend, AgentSpec(workdir = workdir), "relaunch-1")
        val sid = awaitEvent<AgentEvent.SessionInit>(first.events, 60_000)?.sessionId
        backend.sendPrompt("Reply with exactly: one. Do not use any tool.", emptyList())
        assertTrue(first.turn().result?.isError == false)
        first.close(); harnesses.remove(first)

        // a healthy relaunch on the same instance: opens, answers, and the watchdog never misfires
        val second = launch(backend, AgentSpec(workdir = workdir, resumeId = sid), "relaunch-2")
        assertEquals(sid, second.await<AgentEvent.SessionInit>(60_000)?.sessionId)
        backend.sendPrompt("Reply with exactly: two. Do not use any tool.", emptyList())
        val two = second.turn()
        assertTrue(two.result?.isError == false && two.text.contains("two"), "second process: ${two.text}")
        val quiet = second.drain(10_000) // past the 8 s watchdog
        assertTrue(quiet.none { it is AgentEvent.TurnResult || (it is AgentEvent.AssistantText && it.text.contains("handshake")) },
            "the watchdog misfired on a healthy relaunch: $quiet")
        second.dump("relaunch-2"); second.timings("relaunch-2")
        second.close(); harnesses.remove(second)

        // a third process on the same instance that never answers `initialize`
        val silent = launch(backend, AgentSpec(workdir = workdir, resumeId = sid), "relaunch-3") {
            ProcessBuilder("sleep", "120").start()
        }
        backend.sendPrompt("Reply with exactly: three.", emptyList())
        val t0 = System.nanoTime()
        val stranded = silent.turn(30_000)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("[dsh-live] relaunch-3: silent process settled in $ms ms: ${stranded.text.take(200)}")
        assertTrue(stranded.result?.isError == true, "the silent relaunch was not bounded: ${stranded.events}")
        assertTrue(stranded.text.contains("never completed its handshake"), stranded.text)
    }

    private suspend inline fun <reified T : AgentEvent> awaitEvent(
        events: Channel<AgentEvent>,
        timeoutMs: Long,
    ): T? = withTimeoutOrNull(timeoutMs) {
        for (e in events) if (e is T) return@withTimeoutOrNull e
        null
    }
}
