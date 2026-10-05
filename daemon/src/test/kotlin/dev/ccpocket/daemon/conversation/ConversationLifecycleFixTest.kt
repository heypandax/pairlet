package dev.ccpocket.daemon.conversation

import dev.ccpocket.daemon.agent.AgentProcessMode
import dev.ccpocket.daemon.agent.AgentPromptDelivery
import dev.ccpocket.daemon.conversation.Conversation.LifecyclePoint
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.StreamPiece
import dev.ccpocket.protocol.PromptAck
import kotlin.test.assertFalse
import dev.ccpocket.protocol.TurnDone
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The lock-free stopgaps of lifecycle design S3, each pinned by an interleaving forced through
 * [Conversation.lifecycleProbe]. Unlike [ConversationLifecycleRaceTest] these are fixed in place: every test
 * here failed on the code before its fix.
 */
class ConversationLifecycleFixTest {

    private fun chunk(h: LifecycleHarness, text: String) =
        h.frames.any { it is AssistantChunk && (it.piece as? StreamPiece.Text)?.text == text }

    // ── S3(a): re-check process identity after the suspension, not only before it ──────────────────────

    /** T3 / D3 — the death branch waits for the OS exit; a /clear that replaced the process meanwhile must
     *  survive the branch resuming (no orphaned process, no stale process_exited, no third spawn). */
    @Test
    fun a_late_death_branch_leaves_the_replacement_process_alone() = runBlocking {
        if (LifecycleHarness.isWindows()) return@runBlocking
        val backend = LifecycleBackend { index, _ ->
            if (index == 0) "IFS= read -r l; printf 'init:s1\\nuser:%s\\n' \"\$l\"; exit 1" else LifecycleBackend.ECHO_TURNS
        }
        val h = LifecycleHarness(backend, "cS3a")
        val gate = ProbeGate(LifecyclePoint.DEATH_AFTER_AWAIT_EXIT)
        h.convo.lifecycleProbe = gate::onProbe
        try {
            h.convo.open(resumeId = null, model = null)
            h.convo.sendPrompt("go", promptId = "go")
            withTimeoutOrNull(10_000) { gate.reached.await() } ?: error("death branch never reached")
            h.convo.sendPrompt("/clear", promptId = "clear") // stops the dead process, launches #1
            assertEquals(2, backend.specs.size)
            gate.release.complete(Unit)
            delay(300)
            assertTrue(h.convo.hasLiveProcess(), "the replacement must still be the conversation's process")
            assertTrue(h.framesOf<PocketError>().none { it.code == "process_exited" }, h.frames.toString())
            h.convo.sendPrompt("after", promptId = "after")
            h.await(what = "the prompt on the replacement") { backend.sends.any { it == 1 to "after" } }
            assertEquals(2, backend.specs.size, "no third process: ${backend.specs}")
        } finally {
            gate.release.complete(Unit)
            h.close()
        }
    }

    /** D3' — the OpenCode startup watchdog kills a silent process; a /clear + next prompt that replaced it
     *  while the kill was in progress must not have its handle nulled or its turn cleared. */
    @Test
    fun a_late_watchdog_leaves_the_replacement_process_alone() = runBlocking {
        if (LifecycleHarness.isWindows()) return@runBlocking
        System.setProperty(Conversation.OPENCODE_WATCHDOG_PROP, "300")
        val backend = LifecycleBackend(
            kind = AgentKind.OPENCODE,
            processMode = AgentProcessMode.ONE_SHOT_TURN,
            promptDelivery = AgentPromptDelivery.INITIAL_ARG_ONE_SHOT,
        ) { index, _ -> if (index == 0) "exec sleep 30" else "printf 'init:s2\\nsay:working\\n'; $SILENT_TAIL" }
        val h = LifecycleHarness(backend, "cS3aw")
        val gate = ProbeGate(LifecyclePoint.WATCHDOG_AFTER_EXIT)
        h.convo.lifecycleProbe = gate::onProbe
        try {
            h.convo.open(resumeId = null, model = null)
            h.convo.sendPrompt("first", promptId = "first") // hangs with no stdout → the watchdog fires
            withTimeoutOrNull(10_000) { gate.reached.await() } ?: error("watchdog never reached its kill")
            h.convo.sendPrompt("/clear", promptId = "clear") // drops the handle (OpenCode defers its launch)
            h.convo.sendPrompt("second", promptId = "second") // lazy start of the replacement
            h.await(what = "replacement output") { chunk(h, "working") }
            gate.release.complete(Unit)
            delay(300)
            assertTrue(h.convo.hasLiveProcess(), "the replacement must still be the conversation's process")
            assertTrue(h.convo.isExecuting(), "the replacement's turn must still be running")
            assertTrue(h.framesOf<PocketError>().none { it.code == "opencode_startup_timeout" }, h.frames.toString())
        } finally {
            gate.release.complete(Unit)
            h.close()
            System.clearProperty(Conversation.OPENCODE_WATCHDOG_PROP)
        }
    }

    /** D3'' — pumpCrashed drops the handle, then suspends in the shutdown. A prompt in that window lazily
     *  starts a new process; the crash handler resuming afterwards must not clear that process's running
     *  turn, drop its permission bridge (its pending ask) or tell the user the session stopped. */
    @Test
    fun a_late_pump_crash_settles_only_its_own_process() = runBlocking {
        if (LifecycleHarness.isWindows()) return@runBlocking
        val backend = LifecycleBackend(endThrowsOnce = true) { index, _ ->
            if (index == 0) "IFS= read -r l; printf 'init:s1\\nuser:%s\\n' \"\$l\"; exit 3"
            else "IFS= read -r l; printf 'user:%s\\nsay:working\\nask:q1\\n' \"\$l\"; $SILENT_TAIL"
        }
        val h = LifecycleHarness(backend, "cS3c")
        val gate = ProbeGate(LifecyclePoint.PUMP_CRASHED_AFTER_SHUTDOWN)
        h.convo.lifecycleProbe = gate::onProbe
        try {
            h.convo.open(resumeId = null, model = null)
            h.convo.sendPrompt("one", promptId = "one") // #0 dies; its death cleanup throws → pumpCrashed
            withTimeoutOrNull(10_000) { gate.reached.await() } ?: error("pumpCrashed never reached")
            h.convo.sendPrompt("two", promptId = "two") // proc == null → lazy start of #1
            h.await(what = "the new process's ask") { h.convo.hasPendingAsk() && chunk(h, "working") }
            gate.release.complete(Unit)
            delay(300)
            assertTrue(h.convo.isExecuting(), "the new process's turn must still be running")
            assertTrue(h.convo.hasPendingAsk(), "the new process's pending ask must still be visible")
            assertTrue(h.convo.hasLiveProcess())
            assertTrue(
                h.framesOf<PocketError>().none { "internal daemon error" in it.message },
                "no stale 'session stopped' for a session that is running: ${h.framesOf<PocketError>()}",
            )
        } finally {
            gate.release.complete(Unit)
            h.close()
        }
    }

    // ── S3(b): a closed conversation never spawns again ───────────────────────────────────────────────


    /** T5 / D5 — a sender still holding the conversation after close() (reaper / closeIfIdle raced it) used
     *  to lazily start a process on the cancelled scope (no IO pumps: a leaked, never-read CLI) and ack the
     *  prompt. Now the launch is refused through the ordinary "agent failed to start" path. */
    @Test
    fun a_prompt_after_close_spawns_nothing_and_is_not_acked() = runBlocking {
        if (LifecycleHarness.isWindows()) return@runBlocking
        val backend = LifecycleBackend { _, _ -> LifecycleBackend.ECHO_TURNS }
        val h = LifecycleHarness(backend, "cS3b")
        try {
            h.convo.open(resumeId = null, model = null)
            h.convo.close()
            h.convo.sendPrompt("late", promptId = "late")
            assertEquals(0, backend.specs.size, "no process for a closed conversation")
            assertTrue(h.framesOf<PromptAck>().none { it.promptId == "late" }, "no receipt for a prompt nobody runs")
        } finally {
            h.close()
        }
    }


    // ── S3(c): a launch that fails after the process started rolls it back ──────────────────────────────


    /** T6 / D5 — `attach` throwing used to leave `proc` pointing at a live, pump-less process: the next
     *  prompt was queued into it and never ran. Now the process is stopped and the next prompt respawns. */
    @Test
    fun a_failed_attach_rolls_the_process_back() = runBlocking {
        if (LifecycleHarness.isWindows()) return@runBlocking
        val pidFile = Files.createTempDirectory("ccp-s3c").resolve("pid")
        val backend = LifecycleBackend(attachThrowsAt = setOf(0)) { index, _ ->
            if (index == 0) "echo \$\$ > '$pidFile'; $SILENT_TAIL" else LifecycleBackend.ECHO_TURNS
        }
        val h = LifecycleHarness(backend, "cS3c1")
        try {
            h.convo.open(resumeId = null, model = null)
            h.convo.sendPrompt("one", promptId = "one")
            assertTrue(h.framesOf<PocketError>().any { it.code == "agent_unavailable" }, h.frames.toString())
            assertFalse(h.convo.hasLiveProcess(), "the failed launch must not stay the conversation's process")
            h.await(what = "pid file") { Files.exists(pidFile) && Files.readString(pidFile).isNotBlank() }
            val pid = Files.readString(pidFile).trim().toLong()
            h.await(what = "the orphan to exit") { ProcessHandle.of(pid).map { it.isAlive }.orElse(false) == false }
            h.convo.sendPrompt("two", promptId = "two")
            h.await(what = "the retry to run") { backend.sends.any { it == 1 to "two" } }
            assertEquals(2, backend.specs.size)
        } finally {
            h.close()
        }
    }


    /** T5 (in-flight) — close() lands while a launch is between "process started" and "handle published":
     *  the close finds nothing to stop. The launch itself must notice, fail (no hollow receipt for a prompt
     *  nobody will run) and stop what it started rather than publish it on a closed conversation. */
    @Test
    fun a_close_during_launch_fails_the_launch_and_stops_its_process() = runBlocking {
        if (LifecycleHarness.isWindows()) return@runBlocking
        val pidFile = Files.createTempDirectory("ccp-s3c").resolve("pid")
        val backend = LifecycleBackend { _, _ -> "echo \$\$ > '$pidFile'; $SILENT_TAIL" }
        val h = LifecycleHarness(backend, "cS3c2")
        val gate = ProbeGate(LifecyclePoint.LAUNCH_AFTER_START)
        h.convo.lifecycleProbe = gate::onProbe
        try {
            h.convo.open(resumeId = null, model = null)
            val sending = launch { h.convo.sendPrompt("one", promptId = "one") }
            withTimeoutOrNull(10_000) { gate.reached.await() } ?: error("launch never reached")
            h.convo.close()
            gate.release.complete(Unit)
            sending.join()
            h.await(what = "pid file") { Files.exists(pidFile) && Files.readString(pidFile).isNotBlank() }
            val pid = Files.readString(pidFile).trim().toLong()
            h.await(what = "the raced process to exit") { ProcessHandle.of(pid).map { it.isAlive }.orElse(false) == false }
            assertTrue(h.framesOf<PromptAck>().none { it.promptId == "one" }, "no receipt for a prompt nobody runs")
        } finally {
            gate.release.complete(Unit)
            h.close()
        }
    }

    private companion object {
        /** Keep a one-shot child alive (no further output) until its stdin closes. */
        const val SILENT_TAIL = "while IFS= read -r x; do :; done"
    }
}
