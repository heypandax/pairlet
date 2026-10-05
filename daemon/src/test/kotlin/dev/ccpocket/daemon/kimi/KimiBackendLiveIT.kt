package dev.ccpocket.daemon.kimi

import dev.ccpocket.daemon.acp.AcpLiveHarness
import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.protocol.PermissionMode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * LIVE, against the REAL `kimi acp` (Kimi Code CLI) — the launch path, handshake, session open/load, the
 * prompt FIFO, approvals, cancel and sub-agent traffic, driven through [KimiBackend] and the shared
 * [dev.ccpocket.daemon.acp.AcpClient] with nothing else of the daemon running.
 *
 * OFF BY DEFAULT. It needs a working `kimi` and a model it can call; the model is whatever the kimi config
 * says, so point `KIMI_CODE_HOME` at a THROWAWAY home (never the real one — the tests create sessions):
 *
 * ```
 *   # account-free: a scripted local model (see the script's header for the trigger words)
 *   python3 scripts/acp-mock-model.py --port 18931 &
 *   # a throwaway KIMI_CODE_HOME whose config.toml has an `openai` provider at http://127.0.0.1:18931/v1
 *   CC_POCKET_KIMI_LIVE=1 KIMI_CODE_HOME=/tmp/kimi-live-home CC_POCKET_KIMI_BIN=~/.kimi-code/bin/kimi \
 *     JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :daemon:test --tests 'dev.ccpocket.daemon.kimi.KimiBackendLiveIT'
 * ```
 *
 * The prompts carry the mock's trigger words (PONG, SLOW, BASHME, AGENTME, BGAGENT) inside an instruction a real
 * model can follow too. Set `CC_POCKET_ACP_LIVE_LOG=<dir>` to keep every wire line for inspection.
 */
@EnabledIfEnvironmentVariable(named = "CC_POCKET_KIMI_LIVE", matches = "1")
class KimiBackendLiveIT {
    private val workdir: Path = createTempDirectory("cc-pocket-kimi-live")
    private val open = ArrayList<AcpLiveHarness>()

    @AfterEach
    fun cleanup() {
        open.forEach { runCatching { it.close() } }
        workdir.toFile().deleteRecursively()
    }

    private fun bin(): String = System.getenv("CC_POCKET_KIMI_BIN")
        ?: File(System.getenv("HOME"), ".kimi-code/bin/kimi").path

    private suspend fun launch(
        name: String,
        resumeId: String? = null,
        model: String? = null,
        mode: PermissionMode = PermissionMode.DEFAULT,
    ): AcpLiveHarness {
        check(System.getenv("KIMI_CODE_HOME") != null) { "set KIMI_CODE_HOME to a throwaway kimi home" }
        val spec = AgentSpec(workdir = workdir, resumeId = resumeId, model = model, mode = mode)
        return AcpLiveHarness(KimiBackend(bin()), spec, "kimi-$name").also { open += it }.start()
    }

    private suspend fun AcpLiveHarness.opened(): AgentEvent.SessionInit {
        val init = await<AgentEvent.SessionInit>(60_000)
        assertNotNull(init, "no session opened; stderr: ${stderrText().takeLast(500)}")
        return init
    }

    private fun report(h: AcpLiveHarness, what: String) {
        println("[kimi-live] $what: session/new→first line ${h.msToFirstLineAfter("session/new")} ms, " +
            "session/load→first line ${h.msToFirstLineAfter("session/load")} ms, " +
            "initialize→answer ${h.msToResponses("initialize").firstOrNull()?.second} ms, " +
            "update sessionIds=${h.updateSessionIds().mapKeys { it.key?.take(20) }}")
    }

    @Test
    fun a_new_session_answers_and_settles() = runBlocking {
        val h = launch("new")
        val init = h.opened()
        h.backend.sendPrompt("Reply with exactly one word: PONG. Do not use any tool.", emptyList())
        val turn = h.turn()
        h.dump("new"); report(h, "new")
        val result = assertNotNull(turn.result, "the turn never settled: ${turn.text}")
        assertTrue(!result.isError, "turn failed: ${turn.text}")
        assertTrue(turn.text.contains("pong", ignoreCase = true), "unexpected reply: ${turn.text}")
        // the consumption receipt precedes the result (fact 2 of the ACP design)
        assertTrue(turn.events.any { it is AgentEvent.UserReplay }, "no consumption receipt")
        assertTrue(init.sessionId?.startsWith("session_") == true, init.sessionId)
    }

    @Test
    fun session_load_replays_silently_then_answers() = runBlocking {
        val first = launch("load-a")
        val sid = first.opened().sessionId
        first.backend.sendPrompt("Reply with exactly one word: PONG. Do not use any tool.", emptyList())
        assertNotNull(first.turn().result)
        first.close(); open.remove(first)

        val h = launch("load-b", resumeId = sid)
        val before = ArrayList<AgentEvent>()
        val init = h.await<AgentEvent.SessionInit>(60_000, before)
        assertNotNull(init, "session/load never answered; stderr: ${h.stderrText().takeLast(500)}")
        assertEquals(sid, init.sessionId)
        val load = h.outbound("session/load").single()
        val answer = h.lines.first { !it.outbound && it.id == load.id && it.method == null }
        val replayed = h.lines.count { !it.outbound && it.method == "session/update" && it.nanos in load.nanos..answer.nanos }
        val leaked = before.filter { it is AgentEvent.AssistantText || it is AgentEvent.UserReplay || it is AgentEvent.AssistantToolUse }
        println("[kimi-live] load: $replayed history updates replayed inside session/load; leaked as output: $leaked")
        assertTrue(leaked.isEmpty(), "history leaked as new output: $leaked")
        // anything trailing after the load answer must not be history either
        val trailing = h.drain(1_500).filter { it is AgentEvent.AssistantText || it is AgentEvent.UserReplay }
        assertTrue(trailing.isEmpty(), "history trailed the load answer: $trailing")

        h.backend.sendPrompt("Reply with exactly one word: PONG. Do not use any tool.", emptyList())
        val turn = h.turn()
        h.dump("load"); report(h, "load")
        assertTrue(turn.result?.isError == false && turn.text.contains("pong", ignoreCase = true), "after load: ${turn.text}")
    }

    @Test
    fun a_prompt_sent_mid_turn_waits_for_the_turn_to_settle() = runBlocking {
        val h = launch("queue")
        h.opened()
        h.backend.sendPrompt("SLOW: count slowly from 1 to 40, one number per line. Do not use any tool.", emptyList())
        assertNotNull(h.await<AgentEvent.AssistantText>(60_000), "the slow turn produced no text")
        h.backend.sendPrompt("Reply with exactly one word: PONG. Do not use any tool.", emptyList())
        assertEquals(1, h.outbound("session/prompt").size, "the second prompt was written while a turn ran")
        val one = h.turn()
        assertTrue(one.result?.isError == false, "first turn: ${one.text}")
        val two = h.turn()
        h.dump("queue"); report(h, "queue")
        assertTrue(two.result?.isError == false && two.text.contains("pong", ignoreCase = true), "second turn: ${two.text}")
        val prompts = h.outbound("session/prompt")
        assertEquals(2, prompts.size)
        val firstAnswer = h.lines.first { !it.outbound && it.method == null && it.id == prompts[0].id }
        assertTrue(prompts[1].nanos >= firstAnswer.nanos, "the queued prompt went out before the first turn settled")
    }

    @Test
    fun approvals_once_then_always() = runBlocking {
        val h = launch("approve")
        h.opened()
        val asks = ArrayList<AgentEvent.ControlRequest>()
        h.backend.sendPrompt("BASHME: run the shell command `echo approved-run` with the Bash tool, then say done.", emptyList())
        val once = h.turn { asks += it; h.backend.respondPermission(it.requestId, true, false, it.input, null, null) }
        h.backend.sendPrompt("BASHME: run the shell command `echo approved-run` with the Bash tool again, then say done.", emptyList())
        val always = h.turn { asks += it; h.backend.respondPermission(it.requestId, true, true, it.input, null, null) }
        h.backend.sendPrompt("BASHME: run the shell command `echo approved-run` with the Bash tool a third time, then say done.", emptyList())
        val third = h.turn { asks += it; h.backend.respondPermission(it.requestId, true, false, it.input, null, null) }
        h.dump("approve"); report(h, "approve")
        val answers = h.lines.filter { it.outbound && it.method == null && it.text.contains("optionId") }.map { it.text }
        println("[kimi-live] approve: asks=${asks.map { it.toolName to it.input }} answers=$answers")
        for (t in listOf(once, always, third)) assertTrue(t.result?.isError == false, "turn failed: ${t.text}")
        assertTrue(once.events.any { it is AgentEvent.ToolResult && !it.isError }, "the approved call did not run")
        assertTrue(answers[0].contains("\"approve_once\""), answers[0])
        assertTrue(answers[1].contains("\"approve_always\""), answers[1])
        assertEquals(2, asks.size, "remembered approval asked again")
    }

    @Test
    fun cancel_settles_the_running_turn() = runBlocking {
        val h = launch("cancel")
        h.opened()
        h.backend.sendPrompt("SLOW: count slowly from 1 to 40, one number per line. Do not use any tool.", emptyList())
        assertNotNull(h.await<AgentEvent.AssistantText>(60_000))
        val t0 = System.nanoTime()
        h.backend.interrupt()
        val turn = h.turn(30_000)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val prompt = h.outbound("session/prompt").single()
        val answer = h.lines.firstOrNull { !it.outbound && it.method == null && it.id == prompt.id }
        println("[kimi-live] cancel: settled ${ms} ms after session/cancel; answer=${answer?.text}")
        assertNotNull(turn.result, "the cancelled turn never settled")
        assertTrue(answer?.text?.contains("cancelled") == true, "stopReason: ${answer?.text}")
        h.backend.sendPrompt("Reply with exactly one word: PONG. Do not use any tool.", emptyList())
        val after = h.turn()
        h.dump("cancel"); report(h, "cancel")
        assertTrue(after.result?.isError == false && after.text.contains("pong", ignoreCase = true), "after cancel: ${after.text}")
    }

    /**
     * The user's model and Plan reach kimi before the first turn (kimi ignores `modelId`/`modeId` on
     * `session/new`; only `session/set_config_option` switches them), on a fresh session and again after
     * `session/load` (kimi keeps a loaded session's model but not its mode).
     *
     * Needs a SECOND model alias in the throwaway config.toml, other than `default_model`:
     * `CC_POCKET_KIMI_LIVE_MODEL=<alias>`. With the scripted model, also set `CC_POCKET_ACP_MOCK_LOG=<its --log>`
     * and `CC_POCKET_KIMI_LIVE_UPSTREAM=<that alias's model = …>` to assert what the MODEL END received.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "CC_POCKET_KIMI_LIVE_MODEL", matches = ".+")
    fun the_chosen_model_and_plan_reach_kimi_on_new_and_load() = runBlocking {
        val alias = System.getenv("CC_POCKET_KIMI_LIVE_MODEL")
        val startedAt = System.currentTimeMillis() / 1000.0
        val h = launch("model-plan", model = alias, mode = PermissionMode.PLAN)
        val init = h.opened()
        assertEquals(alias, init.model, "the header names what kimi reports after the write")
        val writes = h.msToResponses("session/set_config_option")
        println("[kimi-live] model-plan: writes=${writes.map { it.first.text to it.second }}")
        assertTrue(writes.any { "\"configId\":\"model\"" in it.first.text && "\"value\":\"$alias\"" in it.first.text }, "no model write")
        assertTrue(writes.any { "\"configId\":\"mode\"" in it.first.text && "\"value\":\"plan\"" in it.first.text }, "no plan write")
        assertTrue(writes.all { it.second != null }, "a write went unanswered")
        assertTrue(h.lines.any { !it.outbound && "\"current_mode_update\"" in it.text && "\"plan\"" in it.text }, "kimi never said plan")
        assertTrue(h.outbound("session/new").single().text.let { "modelId" !in it && "modeId" !in it })

        h.backend.sendPrompt("Reply with exactly one word: PONG. Do not use any tool.", emptyList())
        val turn = h.turn()
        assertTrue(turn.result?.isError == false && turn.text.contains("pong", ignoreCase = true), "turn: ${turn.text}")
        // the prompt went out only after every write was answered
        val prompt = h.outbound("session/prompt").first()
        val lastAnswer = writes.maxOf { w -> h.lines.first { !it.outbound && it.method == null && it.id == w.first.id }.nanos }
        assertTrue(prompt.nanos >= lastAnswer, "the prompt overtook a config write")

        // Plan is not read-only on kimi: a Bash call still asks, and runs once approved.
        val asks = ArrayList<AgentEvent.ControlRequest>()
        h.backend.sendPrompt("BASHME: run the shell command `echo approved-run` with the Bash tool, then say done.", emptyList())
        val bash = h.turn { asks += it; h.backend.respondPermission(it.requestId, true, false, it.input, null, null) }
        val ran = bash.events.filterIsInstance<AgentEvent.ToolResult>()
        println("[kimi-live] plan bash: asks=${asks.map { it.toolName to it.input }} results=${ran.map { it.isError to it.content?.take(80) }}")
        assertEquals(1, asks.size, "plan asked ${asks.size} times")
        h.dump("model-plan"); report(h, "model-plan")
        val sid = init.sessionId
        h.close(); open.remove(h)

        // the same writes after session/load
        val l = launch("model-plan-load", resumeId = sid, model = alias, mode = PermissionMode.PLAN)
        assertEquals(sid, l.opened().sessionId)
        val loadWrites = l.outbound("session/set_config_option").map { it.text }
        println("[kimi-live] after load: writes=$loadWrites")
        assertTrue(loadWrites.any { "\"configId\":\"mode\"" in it && "\"value\":\"plan\"" in it }, "plan not rewritten after load")
        l.backend.sendPrompt("Reply with exactly one word: PONG. Do not use any tool.", emptyList())
        assertTrue(l.turn().result?.isError == false)
        l.dump("model-plan-load")

        // what the model end received
        val mockLog = System.getenv("CC_POCKET_ACP_MOCK_LOG")
        val upstream = System.getenv("CC_POCKET_KIMI_LIVE_UPSTREAM")
        if (mockLog != null && upstream != null) {
            val requests = File(mockLog).readLines().filter { "openai model=" in it }
                .filter { it.substringBefore(' ').toDoubleOrNull()?.let { t -> t >= startedAt } == true }
            val models = requests.map { it.substringAfter("model=").substringBefore(' ') }
            println("[kimi-live] model end saw: $models")
            assertTrue(models.isNotEmpty(), "the scripted model saw no request")
            assertTrue(models.all { it == upstream }, "a request used another model: $models")
        }
    }

    /**
     * What PLAN means on kimi once `plan` is really written: the model asks for one read, one file write and one
     * plain command; every ask is approved and the disk says what happened (2.1.1, 2026-10-04): the read runs
     * without asking, the file write is refused by kimi itself ("You may only write to the current plan file"),
     * and the command ASKS and then RUNS — so kimi's plan is not read-only, which is what the app's Kimi plan
     * description has to say. Account-free with `scripts/acp-mock-model.py` (READFILE / WRITEFILE / RUNCMD).
     */
    @Test
    fun plan_mode_reads_refuses_edits_and_asks_before_commands() = runBlocking {
        val note = workdir.resolve("note.txt").also { it.writeText("the secret word is mango\n") }
        val written = workdir.resolve("plan-written.txt")
        val ran = workdir.resolve("plan-cmd.txt")
        val h = launch("plan", mode = PermissionMode.PLAN)
        h.opened()
        assertTrue(h.lines.any { !it.outbound && "\"current_mode_update\"" in it.text && "\"plan\"" in it.text }, "not in plan")
        val asked = LinkedHashMap<String, List<String>>()
        for ((label, prompt) in listOf(
            "read" to "READFILE $note then say done.",
            "write" to "WRITEFILE $written then say done.",
            "command" to "RUNCMD $ran then say done.",
        )) {
            val asks = ArrayList<String>()
            h.backend.sendPrompt(prompt, emptyList())
            val turn = h.turn { asks += it.toolName; h.backend.respondPermission(it.requestId, true, false, it.input, null, null) }
            asked[label] = asks
            val results = turn.events.filterIsInstance<AgentEvent.ToolResult>().map { it.isError to it.content?.take(120) }
            println("[kimi-live] plan $label: asks=$asks results=$results")
        }
        h.dump("plan"); report(h, "plan")
        println("[kimi-live] plan: written exists=${written.toFile().exists()} command ran=${ran.toFile().exists()}")
        assertTrue(asked.getValue("read").isEmpty(), "a read asked: $asked")
        assertTrue(asked.getValue("write").isEmpty() && !written.toFile().exists(), "plan let kimi edit a file: $asked")
        assertEquals(listOf("Bash"), asked.getValue("command"), "a command under plan must ask")
        assertTrue(ran.toFile().exists(), "the approved command did not run — plan would then be read-only after all")
    }

    /** A mode switch on an open session reaches kimi at once — no relaunch — and leaving Plan for Full access puts
     *  kimi back on `default` (never `yolo`). */
    @Test
    fun a_mid_session_mode_switch_reaches_kimi_without_a_relaunch() = runBlocking {
        val h = launch("switch")
        h.opened()
        suspend fun awaitMode(mode: String): Boolean {
            val deadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < deadline) {
                if (h.lines.any { !it.outbound && "\"current_mode_update\"" in it.text && "\"currentModeId\":\"$mode\"" in it.text }) return true
                kotlinx.coroutines.delay(50)
            }
            return false
        }
        assertEquals(false, h.backend.applySettings(PermissionMode.PLAN, null, null), "a mode switch is not a relaunch")
        assertTrue(awaitMode("plan"), "kimi never reported plan")
        h.backend.sendPrompt("Reply with exactly one word: PONG. Do not use any tool.", emptyList())
        assertTrue(h.turn().result?.isError == false)
        assertEquals(false, h.backend.applySettings(PermissionMode.BYPASS_PERMISSIONS, null, null))
        assertTrue(awaitMode("default"), "kimi stayed in plan after leaving it")
        h.dump("switch"); report(h, "switch")
        val writes = h.outbound("session/set_config_option").map { it.text }
        println("[kimi-live] switch: writes=$writes updates=${h.lines.filter { !it.outbound && "current_mode_update" in it.text }.map { it.text.takeLast(60) }}")
        assertTrue(writes.none { "yolo" in it }, "$writes")
        assertEquals(1, h.outbound("initialize").size, "the process was not relaunched")
    }

    /** The case 547f7651 (drop `session/update`s stamped with another session's id) has to get right. */
    @Test
    fun sub_agent_traffic_carries_the_main_session_id() = runBlocking {
        workdir.resolve("note.txt").writeText("the secret word is mango\n")
        val h = launch("subagent")
        val sid = h.opened().sessionId
        h.backend.sendPrompt(
            "AGENTME: use the Agent tool to launch one sub-agent whose task is: SUBTASK read note.txt and report " +
                "the secret word. Then reply with the secret word.", emptyList(),
        )
        val fg = h.turn(240_000)
        assertTrue(fg.result?.isError == false, "foreground sub-agent turn: ${fg.text}")
        h.backend.sendPrompt(
            "BGAGENT: use the Agent tool with run_in_background=true to launch one sub-agent whose task is: SUBBASH run " +
                "`echo sub-bash-ran` with Bash, then SUBTASK report the secret word in note.txt. Do not wait for it; reply launched.",
            emptyList(),
        )
        val asks = ArrayList<AgentEvent.ControlRequest>()
        val allowAndRecord: suspend (AgentEvent.ControlRequest) -> Unit =
            { asks += it; h.backend.respondPermission(it.requestId, true, false, it.input, null, null) }
        val bg = h.turn(240_000, allowAndRecord)
        assertTrue(bg.result?.isError == false, "background sub-agent turn: ${bg.text}")
        // The background sub-agent keeps working after the turn: its asks may land inside the turn or after it
        // (with a real model it was after), and kimi runs an automatic follow-up turn when it finishes.
        val later = ArrayList<AgentEvent>()
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            val e = h.await<AgentEvent>(1_000) ?: continue
            later += e
            if (e is AgentEvent.ControlRequest) allowAndRecord(e)
        }
        h.dump("subagent"); report(h, "subagent")
        val ids = h.updateSessionIds()
        println("[kimi-live] subagent: main=$sid sub-agent asks=${asks.map { it.toolName to it.input }} " +
            "events-after-turn=${later.map { it::class.simpleName }}")
        assertEquals(setOf<String?>(sid), ids.keys, "a session/update carried another session id: $ids")
    }
}
