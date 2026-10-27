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
import kotlin.test.assertNotNull
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
            // a PRE-PROVIDER bare name, as an older app saved it: exercises the unique-name migration and the
            // opaque-value join before the first turn
            model = "deepseek-v4-pro",
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
            // the model the user asked for is the model dsh reports back, not the one we requested — named with its provider
            assertEquals("deepseek-official/deepseek-v4-pro", backend.liveModelForTest())
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
        harnesses.forEach { runCatching { it.dump("final") } }
        harnesses.forEach { runCatching { it.close() } }
        workdirs.forEach { runCatching { it.toFile().deleteRecursively() } }
        DshCatalog.clearForTest()
    }

    private fun scratch(): java.nio.file.Path = createTempDirectory("cc-pocket-dsh-live").also { workdirs.add(it) }

    private fun escalationPrompt(path: java.nio.file.Path): String =
        "ESCALATECMD $path : run the shell command `echo approved-run > $path`. " +
            "Use the bash tool, not a file-write tool. If the sandbox denies it, request approval to " +
            "retry the exact command using sandbox_permissions=danger-full-access (justification: probe). " +
            "If the user rejects approval, stop without retrying. Otherwise say done."

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

    /** A NEW session with a non-default model announces the model dsh reports AFTER the launch write — the
     *  conversation keeps the init's model, so announcing dsh's default at `session/new` pinned it (2026-10-04). */
    @Test
    fun a_new_session_announces_the_model_it_was_switched_to() = runBlocking {
        val backend = DshBackend(null)
        val h = launch(backend, AgentSpec(workdir = scratch(), mode = PermissionMode.DEFAULT, model = PRO), "announce")
        val init = awaitEvent<AgentEvent.SessionInit>(h.events, 60_000)
        val open = h.lines.firstOrNull { !it.outbound && it.id == h.outbound("session/new").single().id }?.text
        println("[dsh-live] announce: SessionInit.model=${init?.model} live=${backend.liveModelForTest()} " +
            "session/new currentValue=${open?.substringAfter("\"currentValue\":")?.take(50)}")
        assertEquals(PRO, init?.model, "the header was told another model than the session runs")
    }

    /**
     * DSH's read-only sandbox allows explicit, user-approved escalation. A real model can retry a denied
     * plain write with escalation, unlike the scripted model. Reject these asks and check the disk;
     * separately prove that an explicit escalation is rejected/allowed according to the user's answer.
     */
    @Test
    fun plan_mode_blocks_writes_without_approval_and_honours_escalation_decisions() = runBlocking {
        val workdir = scratch()
        val note = workdir.resolve("note.txt").also { it.toFile().writeText("the secret word is mango\n") }
        val written = workdir.resolve("plan-written.txt")
        val ran = workdir.resolve("plan-cmd.txt")
        val escalated = workdir.resolve("approved.txt")
        val h = launch(DshBackend(null), AgentSpec(workdir = workdir, mode = PermissionMode.PLAN), "plan")
        assertTrue(awaitEvent<AgentEvent.SessionInit>(h.events, 60_000) != null, "no session")
        val asks = ArrayList<String>()
        val reject: suspend (AgentEvent.ControlRequest) -> Unit = {
            asks += it.toolName
            h.backend.respondPermission(it.requestId, false, false, it.input, null, null)
        }
        for ((label, prompt) in listOf(
            "read" to "READFILE $note then say done.",
            "write" to "WRITEFILE $written then say done.",
            "command" to "RUNCMD $ran then say done.",
        )) {
            val before = asks.size
            val wireStart = h.lines.size
            h.backend.sendPrompt("$prompt If permission is rejected, stop; do not retry or use another tool.", emptyList())
            val turn = h.turn(120_000, reject)
            assertNotNull(turn.result, "$label never settled")
            // DSH deliberately hides live tool cards; inspect the real ACP result, not an event
            // the backend does not promise to emit. History rendering is verified separately.
            if (label == "read") assertTrue(h.lines.drop(wireStart).any {
                !it.outbound && "\"tool_call_update\"" in it.text && "mango" in it.text && "\"completed\"" in it.text
            }, "read-only must still allow reading the file")
            val settled = h.lines.filter {
                !it.outbound && "\"tool_call_update\"" in it.text && ("\"completed\"" in it.text || "\"failed\"" in it.text)
            }.lastOrNull()?.text
            println("[dsh-live] plan $label: asks=${asks.drop(before)} turnError=${turn.result.isError} settled=${settled?.take(400)}")
        }
        println("[dsh-live] plan: written exists=${written.toFile().exists()} command ran=${ran.toFile().exists()} asks=$asks")
        assertTrue(!written.toFile().exists(), "plan let dsh write a file")
        assertTrue(!ran.toFile().exists(), "plan let dsh run a command that writes")
        val escalate = escalationPrompt(escalated)
        asks.clear()
        h.backend.sendPrompt(escalate, emptyList())
        val denied = h.turn(120_000, reject)
        assertNotNull(denied.result, "rejected escalation never settled")
        assertEquals(listOf("bash"), asks, "explicit escalation must ask")
        assertTrue(!escalated.toFile().exists(), "rejected escalation must not write")

        // A fresh session isolates the allow branch. Real models correctly remember the rejection
        // and may refuse to ask again for the exact operation in the same conversation.
        val allowing = launch(DshBackend(null), AgentSpec(workdir = workdir, mode = PermissionMode.PLAN), "plan-allow")
        assertNotNull(allowing.await<AgentEvent.SessionInit>(60_000))
        asks.clear()
        allowing.backend.sendPrompt(escalate, emptyList())
        val escalation = allowing.turn(120_000) {
            asks += it.toolName
            assertTrue(!escalated.toFile().exists(), "the command ran before approval")
            allowing.backend.respondPermission(it.requestId, true, false, it.input, null, null)
        }
        h.dump("plan"); allowing.dump("plan-allow")
        println("[dsh-live] plan escalation: asks=$asks turnError=${escalation.result?.isError}")
        assertEquals(listOf("bash"), asks, "an escalation under plan must ask")
        assertTrue(escalation.result?.isError == false)
        assertEquals("approved-run", escalated.toFile().readText().trim(), "approved command must run")
    }

    /** Launch-time model/effort land BEFORE the opening prompt is written, even when the prompt was sent before
     *  the session existed — and each write answers well inside the 30 s config watchdog. */
    @Test
    fun the_launch_config_chain_gates_the_opening_prompt() = runBlocking {
        val spec = AgentSpec(workdir = scratch(), mode = PermissionMode.DEFAULT, model = PRO, effort = "max")
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
        assertEquals(PRO, backend.liveModelForTest())
        assertTrue(turn.events.none { it is AgentEvent.AssistantText && it.text.contains("⚠️") }, "a config warning surfaced")
    }

    /**
     * A model switch in a RUNNING session: the prompt sent right after it goes out only once the real dsh has
     * answered the write and its read-back names the new model — never on the model being left, which may bill
     * another provider's account. And every id the session's catalogue hands out names its provider.
     */
    @Test
    fun a_live_model_switch_holds_the_next_prompt_until_dsh_confirms_it() = runBlocking {
        val backend = DshBackend(null)
        val h = launch(backend, AgentSpec(workdir = scratch(), mode = PermissionMode.DEFAULT), "switch")
        assertTrue(awaitEvent<AgentEvent.SessionInit>(h.events, 60_000) != null, "the session never opened")
        val ids = DshCatalog.current()?.models?.map { it.id }.orEmpty()
        println("[dsh-live] switch: catalogue ids $ids")
        assertTrue(ids.isNotEmpty() && ids.all { it.startsWith("deepseek-official/") }, "an id without its provider: $ids")

        assertEquals(false, backend.applySettings(mode = null, model = PRO, effort = null), "a live switch, no relaunch")
        backend.sendPrompt("Reply with exactly: switched-ok. Do not use any tool.", emptyList())
        val turn = h.turn(120_000)
        h.dump("switch"); h.timings("switch")
        assertTrue(turn.result?.isError == false, "turn failed: ${turn.text}")
        assertTrue(turn.text.contains("switched-ok"), "unexpected reply: ${turn.text}")
        val write = h.outbound("session/set_config_option").single()
        val answer = h.lines.first { !it.outbound && it.method == null && it.id == write.id }
        val prompt = h.outbound("session/prompt").single()
        assertTrue(prompt.nanos >= answer.nanos, "the prompt went out before dsh answered the switch")
        assertEquals(PRO, backend.liveModelForTest())
    }

    /**
     * A switch to a model this dsh does not offer — a plugin's provider missing from the `acp` profile: the prompt
     * after it is refused with the reason, and nothing reaches dsh at all, on the old model or any other.
     */
    @Test
    fun a_switch_to_a_model_dsh_does_not_offer_refuses_the_next_prompt() = runBlocking {
        val backend = DshBackend(null)
        val h = launch(backend, AgentSpec(workdir = scratch(), mode = PermissionMode.DEFAULT), "switch-missing")
        assertTrue(awaitEvent<AgentEvent.SessionInit>(h.events, 60_000) != null, "the session never opened")
        assertEquals(true, backend.applySettings(mode = null, model = "qoder/deepseek-v4-pro", effort = null), "asks for a relaunch")
        backend.sendPrompt("Reply with exactly: must-not-run. Do not use any tool.", emptyList())
        val turn = h.turn(30_000)
        h.dump("switch-missing")
        println("[dsh-live] switch-missing: ${turn.text.take(300)}")
        assertTrue(turn.result?.isError == true, "the prompt was not refused: ${turn.events}")
        assertTrue(turn.text.contains("offers no model qoder/deepseek-v4-pro"), turn.text)
        assertTrue(h.outbound("session/prompt").isEmpty(), "a prompt reached dsh")
        assertTrue(h.outbound("session/set_config_option").isEmpty(), "a write reached dsh")
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

        // Read what the real CLI persisted, including its compressed transcript generation. The ACP
        // resume itself does not replay history; Pairlet must discover and render the disk separately.
        val persisted = assertNotNull(DshTranscriptScanner.find(sid, workdir.toString()))
        val history = DshTranscriptReplay.slice(persisted.file, null)
        assertEquals("complete", history.quality, history.readError)
        assertTrue(history.messages.any {
            it.role == dev.ccpocket.protocol.ChatRole.ASSISTANT && it.text.contains("first-turn")
        }, "the persisted first reply was lost")

        val h = launch(DshBackend(null), AgentSpec(workdir = workdir, resumeId = sid), "resume-b")
        val before = ArrayList<AgentEvent>()
        val init = h.await<AgentEvent.SessionInit>(60_000, before)
        assertEquals(sid, init?.sessionId, "session/resume did not reopen the session")
        assertTrue(before.none { it is AgentEvent.AssistantText || it is AgentEvent.UserReplay }, "history leaked: $before")
        h.backend.sendPrompt("Reply with exactly: resumed-ok. Do not use any tool.", emptyList())
        val turn = h.turn()
        h.dump("resume"); h.timings("resume")
        assertTrue(turn.result?.isError == false && turn.text.contains("resumed-ok"), "after resume: ${turn.text}")
        h.close(); harnesses.remove(h)
        val resumedFile = assertNotNull(DshTranscriptScanner.find(sid, workdir.toString())).file
        val resumedHistory = DshTranscriptReplay.slice(resumedFile, null)
        assertEquals("complete", resumedHistory.quality, resumedHistory.readError)
        for (reply in listOf("first-turn", "resumed-ok")) {
            assertTrue(resumedHistory.messages.any {
                it.role == dev.ccpocket.protocol.ChatRole.ASSISTANT && it.text.contains(reply)
            }, "the persisted reply $reply was lost after resume")
        }
    }

    @Test
    fun approval_usage_and_cancel_pending_approval() = runBlocking {
        val backend = DshBackend(null)
        // workspace-write also grants OS temp directories. Use the isolated test home instead,
        // outside both the session workspace and OS temp, and remove only our own generated directory.
        val testHome = java.nio.file.Files.createDirectories(java.nio.file.Path.of(System.getProperty("user.home")))
        val outside = createTempDirectory(testHome, "dsh-live-outside").also { workdirs.add(it) }
        val approvedFile = outside.resolve("approved.txt")
        val cancelledFile = outside.resolve("cancelled.txt")
        val h = launch(backend, AgentSpec(workdir = scratch()), "approve")
        awaitEvent<AgentEvent.SessionInit>(h.events, 60_000)
        val asks = ArrayList<AgentEvent.ControlRequest>()
        // dsh asks only for an escalation out of the sandbox; the scripted model requests one
        backend.sendPrompt(escalationPrompt(approvedFile), emptyList())
        val approved = h.turn {
            asks += it
            assertTrue(!approvedFile.toFile().exists(), "write happened before approval")
            backend.respondPermission(it.requestId, true, true, it.input, null, null)
        }
        assertTrue(approved.result?.isError == false, "approval turn: ${approved.text}")
        assertEquals(1, asks.size, "expected exactly one permission ask")
        assertEquals("approved-run", approvedFile.toFile().readText().trim())
        val answer = h.lines.single { it.outbound && it.method == null && it.text.contains("optionId") }.text
        assertTrue(answer.contains("\"allow-once\""), "remember must still answer dsh's only allow option: $answer")
        val usage = approved.events.filterIsInstance<AgentEvent.AssistantUsage>()
        val window = approved.events.filterIsInstance<AgentEvent.RuntimeMeta>().mapNotNull { it.contextWindow }
        assertTrue(usage.isNotEmpty() && window.isNotEmpty(), "no usage_update reached the events: ${approved.events}")

        // Hold the next turn at an actual approval boundary. A model can finish "count slowly"
        // before a fixed delay elapses; that tested response speed rather than cancellation.
        backend.sendPrompt(escalationPrompt(cancelledFile), emptyList())
        assertNotNull(h.await<AgentEvent.ControlRequest>(60_000), "no pending approval to cancel")
        assertTrue(h.outbound("session/prompt").size == 2 && h.lines.none { !it.outbound && it.id == h.outbound("session/prompt")[1].id && it.method == null },
            "the approval turn ended before the cancel")
        val t0 = System.nanoTime()
        backend.interrupt()
        val cancelled = h.turn(30_000)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val interrupted = h.outbound("session/prompt")[1]
        val stop = h.lines.firstOrNull { !it.outbound && it.method == null && it.id == interrupted.id }?.text
        h.dump("approve"); h.timings("approve")
        println("[dsh-live] approve: ask=${asks.map { it.toolName to it.input }} answer=$answer usage=${usage.last()} " +
            "window=${window.last()}; cancel settled in $ms ms with $stop")
        assertTrue(cancelled.result != null && stop?.contains("cancelled") == true, "cancel: $stop")
        assertTrue(!cancelledFile.toFile().exists(), "cancelled command must not write")
        backend.sendPrompt("Reply with exactly: after-cancel. Do not use any tool.", emptyList())
        val next = h.turn()
        assertTrue(next.result?.isError == false && next.text.contains("after-cancel"),
            "the session must remain usable after cancelling an approval")
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

    private companion object {
        /** dsh's official route, as the catalogue names it on the wire (`provider/model`). */
        const val PRO = "deepseek-official/deepseek-v4-pro"
    }

    private suspend inline fun <reified T : AgentEvent> awaitEvent(
        events: Channel<AgentEvent>,
        timeoutMs: Long,
    ): T? = withTimeoutOrNull(timeoutMs) {
        for (e in events) if (e is T) return@withTimeoutOrNull e
        null
    }
}
