package dev.ccpocket.daemon.conversation

import dev.ccpocket.daemon.agent.AgentProcessMode
import dev.ccpocket.daemon.agent.AgentPromptDelivery
import dev.ccpocket.daemon.conversation.Conversation.LifecyclePoint
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.StreamPiece
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

    private companion object {
        /** Keep a one-shot child alive (no further output) until its stdin closes. */
        const val SILENT_TAIL = "while IFS= read -r x; do :; done"
    }
}
