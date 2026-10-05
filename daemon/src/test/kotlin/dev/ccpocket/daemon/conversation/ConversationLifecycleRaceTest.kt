package dev.ccpocket.daemon.conversation

import dev.ccpocket.daemon.agent.AgentProcessMode
import dev.ccpocket.daemon.agent.AgentPromptDelivery
import dev.ccpocket.daemon.conversation.Conversation.LifecyclePoint
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.Decision
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionVerdict
import dev.ccpocket.protocol.PromptAck
import dev.ccpocket.protocol.StreamPiece
import dev.ccpocket.protocol.TurnDone
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Deterministic reproductions of the lifecycle races in design-conversation-lifecycle §1/§7 (T1, T2, T4, T7,
 * T8) whose fixes live OUTSIDE the low-risk steps (S0/S1/S3/S6/S7): they need the conversation lifecycle
 * lock (S4) or generation isolation (S5). Each test parks one transition on [Conversation.lifecycleProbe]
 * (or a parse gate), drives the competing call, then releases it — no sleeps decide the interleaving.
 *
 * They are DISABLED on purpose: on the current code every one of them fails, which is the evidence the
 * race is real. The step that fixes a race re-enables its test as the acceptance check. Each test is
 * written so the FIXED code passes it too (a waiter that can no longer reach a probe is bounded by a
 * timeout, never a hang).
 */
class ConversationLifecycleRaceTest {

    private fun <T> withGrace(ms: String, body: () -> T): T {
        System.setProperty(Conversation.RELAUNCH_GRACE_PROP, ms)
        return try { body() } finally { System.clearProperty(Conversation.RELAUNCH_GRACE_PROP) }
    }

    /**
     * T1 / D1 — Codex-shaped stdin one-shot. The clean-exit branch drops the dead handle (`proc = null`) and
     * only THEN decides whether to relaunch for unconsumed prompts. A prompt B that lazy-starts process X in
     * that window is also in the ledger, so the pump starts Y and re-injects B: B runs twice.
     */
    @Test
    fun stdin_one_shot_boundary_does_not_run_a_racing_prompt_twice() = runBlocking {
        if (LifecycleHarness.isWindows()) return@runBlocking
        val oneTurn = "IFS= read -r line; printf 'init:s1\\nuser:%s\\nresult\\n' \"\$line\"; " +
            "while IFS= read -r x; do :; done; exit 0"
        val backend = LifecycleBackend(
            kind = AgentKind.CODEX,
            processMode = AgentProcessMode.ONE_SHOT_TURN,
            promptDelivery = AgentPromptDelivery.STDIN_REPLAY,
            relaunchOnSettings = false,
        ) { index, _ -> if (index == 0) "IFS= read -r line; printf 'init:s1\\nuser:%s\\nresult\\n' \"\$line\"; exit 0" else oneTurn }
        val h = LifecycleHarness(backend, "cT1")
        val gate = ProbeGate(LifecyclePoint.ONE_SHOT_AFTER_NULL)
        h.convo.lifecycleProbe = gate::onProbe
        // X must not prove B consumed before the old pump decides — hold X's echo of B
        val xEcho = backend.gateParse("user:B")
        try {
            h.convo.open(resumeId = null, model = null)
            h.convo.sendPrompt("A", promptId = "a")
            withTimeoutOrNull(10_000) { gate.reached.await() } ?: error("one-shot boundary never reached")
            h.convo.sendPrompt("B", promptId = "b") // proc == null → lazy start X carrying B
            gate.release.complete(Unit)
            // bug: the pump relaunches Y for the "unconsumed" B and re-injects it; fixed: it sees X, does nothing
            withTimeoutOrNull(2_000) { while (backend.sends.count { it.second == "B" } < 2) delay(10) }
            xEcho.complete(Unit)
            delay(300)
            assertEquals(1, backend.sends.count { it.second == "B" }, "B must reach exactly one process: ${backend.sends}")
        } finally {
            gate.release.complete(Unit)
            xEcho.complete(Unit)
            h.close()
        }
    }

    /** T1' / D1' — the argv (OpenCode-shaped) variant: the drain pops the racing prompt's ledger entry and
     *  launches it a second time. */
    @Test
    fun argv_one_shot_boundary_does_not_launch_a_racing_prompt_twice() = runBlocking {
        if (LifecycleHarness.isWindows()) return@runBlocking
        val backend = LifecycleBackend(
            kind = AgentKind.CLAUDE, // not OPENCODE: keeps the startup watchdog out of the picture
            processMode = AgentProcessMode.ONE_SHOT_TURN,
            promptDelivery = AgentPromptDelivery.INITIAL_ARG_ONE_SHOT,
        ) { index, _ -> if (index == 0) "printf 'init:s1\\nresult\\n'; exit 0" else "printf 'init:x\\nresult\\n'; exit 0" }
        val h = LifecycleHarness(backend, "cT1b")
        val gate = ProbeGate(LifecyclePoint.ONE_SHOT_AFTER_NULL)
        h.convo.lifecycleProbe = gate::onProbe
        val xInit = backend.gateParse("init:x") // X's init is its consumption receipt — hold it
        try {
            h.convo.open(resumeId = null, model = null)
            h.convo.sendPrompt("A", promptId = "a")
            withTimeoutOrNull(10_000) { gate.reached.await() } ?: error("one-shot boundary never reached")
            h.convo.sendPrompt("B", promptId = "b")
            gate.release.complete(Unit)
            withTimeoutOrNull(2_000) { while (backend.specs.size < 3) delay(10) }
            xInit.complete(Unit)
            delay(300)
            assertEquals(1, backend.specs.count { it.initialPrompt == "B" }, backend.specs.toString())
        } finally {
            gate.release.complete(Unit)
            xInit.complete(Unit)
            h.close()
        }
    }

    /** T2 / D2 — two clients send the first message of a lazily opened session at once: both observe
     *  `proc == null` and each spawns its own process on the same session. */
    @Test
    fun two_first_prompts_share_one_process() = runBlocking {
        if (LifecycleHarness.isWindows()) return@runBlocking
        val backend = LifecycleBackend { _, _ -> LifecycleBackend.ECHO_TURNS }
        val h = LifecycleHarness(backend, "cT2")
        val gate = ProbeGate(LifecyclePoint.AFTER_SPAWN_DECISION, times = 1)
        h.convo.lifecycleProbe = gate::onProbe
        try {
            h.convo.open(resumeId = null, model = null)
            val first = launch { h.convo.sendPrompt("A", promptId = "a") }
            withTimeoutOrNull(10_000) { gate.reached.await() } ?: error("spawn decision never reached")
            val second = async { h.convo.sendPrompt("B", promptId = "b") }
            // fixed code makes B wait for A's launch instead of deciding on its own — bound that wait
            withTimeoutOrNull(1_500) { second.await() }
            gate.release.complete(Unit)
            first.join(); second.await()
            assertEquals(1, backend.specs.size, "one session, one process: ${backend.specs}")
        } finally {
            gate.release.complete(Unit)
            h.close()
        }
    }

    /** T4 / D4 — stopProcess does not fence the old pump: a line the old process emitted before it died is
     *  still handled after /clear cleared the turn, re-arming `executing` and painting a stale chunk after
     *  the wiped transcript. */
    @Test
    fun old_pump_output_after_clear_does_not_resurrect_the_turn() = runBlocking {
        if (LifecycleHarness.isWindows()) return@runBlocking
        val backend = LifecycleBackend { index, _ ->
            if (index == 0) "IFS= read -r line; printf 'init:s1\\nuser:%s\\nsay:late\\n' \"\$line\"; while IFS= read -r x; do :; done"
            else LifecycleBackend.SILENT
        }
        val h = LifecycleHarness(backend, "cT4")
        val late = backend.gateParse("say:late")
        h.convo.lifecycleProbe = { p ->
            if (p == LifecyclePoint.STOP_AFTER_SHUTDOWN && !late.isCompleted) {
                // the stop is between shutdown and dropping the handle: let the old pump handle its last line
                late.complete(Unit)
                withTimeoutOrNull(2_000) { while (lateChunks(h).isEmpty()) delay(10) }
            }
        }
        try {
            h.convo.open(resumeId = null, model = null)
            h.convo.sendPrompt("go", promptId = "go")
            withTimeoutOrNull(10_000) { backend.parseHeld.getValue("say:late").await() } ?: error("late line never parsed")
            h.convo.sendPrompt("/clear", promptId = "clear")
            delay(200)
            assertFalse(h.convo.isExecuting(), "/clear left no turn running")
            val wipe = h.frames.indexOfFirst { it is ConvoHistory && it.messages.isEmpty() }
            assertTrue(wipe >= 0, "the transcript wipe went out")
            assertTrue(
                h.frames.drop(wipe).none { it is AssistantChunk && (it.piece as? StreamPiece.Text)?.text == "late" },
                "no old-process output after the wipe: ${h.frames}",
            )
        } finally {
            late.complete(Unit)
            h.close()
        }
    }

    private fun lateChunks(h: LifecycleHarness) =
        h.frames.filter { it is AssistantChunk && (it.piece as? StreamPiece.Text)?.text == "late" }

    /** T7 / D8 — the backend is one instance across process generations. A permission ask raised by the OLD
     *  process (buffered output handled after a settings relaunch) is answered through
     *  `backend::respondPermission`, which writes to whatever io is CURRENT — the new process. */
    @Test
    fun an_old_generation_ask_is_never_answered_into_the_new_process() = withGrace("0") {
        runBlocking {
            if (LifecycleHarness.isWindows()) return@runBlocking
            val backend = LifecycleBackend { index, _ ->
                if (index == 0) "IFS= read -r line; printf 'init:s1\\nuser:%s\\nresult\\nask:late\\n' \"\$line\"; while IFS= read -r x; do :; done"
                else LifecycleBackend.ECHO_TURNS
            }
            val h = LifecycleHarness(backend, "cT7")
            val ask = backend.gateParse("ask:late")
            try {
                h.convo.open(resumeId = null, model = null)
                h.convo.sendPrompt("one", promptId = "one")
                h.await(what = "first turn") { h.framesOf<TurnDone>().isNotEmpty() }
                withTimeoutOrNull(10_000) { backend.parseHeld.getValue("ask:late").await() } ?: error("late ask never parsed")
                h.convo.switchEffort("low") // arms the next-turn relaunch
                h.convo.sendPrompt("two", promptId = "two")
                h.await(what = "relaunch") { backend.ios.size == 2 }
                ask.complete(Unit)
                val raised = withTimeoutOrNull(2_000) {
                    while (h.framesOf<PermissionAsk>().none { it.askId == "late" }) delay(10)
                    true
                } ?: false
                if (raised) {
                    h.approvals.onVerdict(PermissionVerdict("cT7", "late", Decision.ALLOW))
                    delay(300)
                }
                assertTrue(
                    backend.responses.none { it.first == "late" && it.third != 0 },
                    "an answer to process 0's ask must never be written into process 1: ${backend.responses}",
                )
            } finally {
                ask.complete(Unit)
                h.close()
            }
        }
    }

    /** T8 / D12 — a model switch inside the lazy-start window is lost: the spec was already built with the
     *  old model, and `recordPendingSettings` still sees no process, so it only announces and never arms the
     *  next-turn relaunch. */
    @Test
    fun a_model_switch_during_the_lazy_launch_is_not_lost() = withGrace("0") {
        runBlocking {
            if (LifecycleHarness.isWindows()) return@runBlocking
            val backend = LifecycleBackend { _, _ -> LifecycleBackend.ECHO_TURNS }
            val h = LifecycleHarness(backend, "cT8")
            val gate = ProbeGate(LifecyclePoint.AFTER_SPAWN_DECISION)
            h.convo.lifecycleProbe = gate::onProbe
            try {
                h.convo.open(resumeId = null, model = "old")
                val first = launch { h.convo.sendPrompt("one", promptId = "one") }
                withTimeoutOrNull(10_000) { gate.reached.await() } ?: error("spawn decision never reached")
                val switched = async { h.convo.switchModel("new") }
                withTimeoutOrNull(1_500) { switched.await() } // fixed code waits for the launch — bound it
                gate.release.complete(Unit)
                first.join(); switched.await()
                h.await(what = "first turn") { h.framesOf<TurnDone>().isNotEmpty() }
                h.convo.sendPrompt("two", promptId = "two")
                h.await(what = "second ack") { h.framesOf<PromptAck>().any { it.promptId == "two" } }
                assertTrue(
                    backend.specs.any { it.model == "new" },
                    "the switch must reach a launch (either the first one or the next relaunch): ${backend.specs}",
                )
            } finally {
                gate.release.complete(Unit)
                h.close()
            }
        }
    }
}
