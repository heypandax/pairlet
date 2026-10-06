package dev.ccpocket.daemon.conversation

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.SessionLive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.appendText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * docs/design/DOTS-SESSION-OBSERVABILITY.md §4.5: a Codex resume continues in a NEW rollout. The observer follows the
 * logical id's newest file and, on the switch, restarts from a FULL window — a cursor from the old file is a line
 * number that would truncate the new file's history. A truncated/replaced file restarts the same way.
 */
class ObserveSessionFileSwitchTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tmp: Path = Files.createTempDirectory("ccp-observe-switch")

    private class Capture : OutboundSink {
        val frames = CopyOnWriteArrayList<Frame>()
        override suspend fun emit(frame: Frame) { frames += frame }
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        tmp.toFile().deleteRecursively()
    }

    private fun rollout(name: String, vararg userTexts: String): Path {
        val f = tmp.resolve(name)
        f.writeText("""{"timestamp":"2026-10-06T03:00:00.000Z","type":"session_meta","payload":{"id":"sid","cwd":"/repo"}}""" + "\n")
        userTexts.forEach { f.appendText(userLine(it)) }
        return f
    }

    private fun userLine(text: String) =
        """{"timestamp":"2026-10-06T03:00:01.000Z","type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"$text"}]}}""" + "\n"

    @Test
    fun switching_to_the_resume_rollout_replays_a_full_window_then_deltas() = runBlocking<Unit> {
        val first = rollout("rollout-2026-10-05T20-00-00-sid.jsonl", "one", "two")
        var current = first
        val c = Capture()
        val obs = ObserveSession(
            "convo", "/repo", "sid", first, c, scope, agent = AgentKind.CODEX, sinceSeq = 0,
            fileResolver = { current }, tickMs = 50,
        )
        obs.start()
        withTimeout(5_000) { while (c.frames.none { it is ConvoHistory }) delay(20) }
        val initial = c.frames.filterIsInstance<ConvoHistory>().last()
        assertFalse(initial.delta)
        assertEquals(listOf("one", "two"), initial.messages.map { it.text })

        // a later append on the same file is a delta
        first.appendText(userLine("three"))
        withTimeout(5_000) { while (c.frames.filterIsInstance<ConvoHistory>().size < 2) delay(20) }
        val delta = c.frames.filterIsInstance<ConvoHistory>().last()
        assertTrue(delta.delta)
        assertEquals(listOf("three"), delta.messages.map { it.text })

        // Codex resumes: a NEW rollout for the same logical id with the full history re-recorded (4 user turns)
        val resumed = rollout("rollout-2026-10-05T21-00-00-sid_resume.jsonl", "one", "two", "three", "four")
        current = resumed
        withTimeout(5_000) { while (c.frames.filterIsInstance<ConvoHistory>().size < 3) delay(20) }
        val switched = c.frames.filterIsInstance<ConvoHistory>().last()
        assertFalse(switched.delta, "a file switch restarts from a full window, never a delta against old line numbers")
        assertEquals(listOf("one", "two", "three", "four"), switched.messages.map { it.text })

        // and appends on the NEW file are deltas again
        resumed.appendText(userLine("five"))
        withTimeout(5_000) { while (c.frames.filterIsInstance<ConvoHistory>().size < 4) delay(20) }
        val after = c.frames.filterIsInstance<ConvoHistory>().last()
        assertTrue(after.delta)
        assertEquals(listOf("five"), after.messages.map { it.text })
        assertNotNull(c.frames.filterIsInstance<SessionLive>().lastOrNull())
        obs.close()
    }

    @Test
    fun idle_ticks_do_not_re_announce_an_unchanged_snapshot() = runBlocking<Unit> {
        val f = rollout("rollout-2026-10-05T20-00-00-sid3.jsonl", "one")
        f.appendText("""{"timestamp":"2026-10-06T03:00:02.000Z","type":"event_msg","payload":{"type":"task_started","turn_id":"t"}}""" + "\n")
        val c = Capture()
        // a far-past clock: the snapshot is STALE from the first tick and stays so — nothing to re-announce
        val obs = ObserveSession(
            "convo", "/repo", "sid3", f, c, scope, agent = AgentKind.CODEX, sinceSeq = 0, fileResolver = null, tickMs = 20,
            observationCapable = true, clock = { 1_900_000_000_000L },
        )
        obs.start()
        withTimeout(5_000) { while (c.frames.none { it is ConvoHistory }) delay(20) }
        delay(600) // ~30 idle ticks
        val lives = c.frames.filterIsInstance<SessionLive>()
        assertTrue(lives.size <= 2, "idle ticks re-announced ${lives.size} times")
        assertNotNull(lives.first().observation?.progress)
        obs.close()
    }

    @Test
    fun a_shrunken_file_restarts_from_a_full_window() = runBlocking<Unit> {
        val f = rollout("rollout-2026-10-05T20-00-00-sid2.jsonl", "one", "two", "three")
        val c = Capture()
        val obs = ObserveSession("convo", "/repo", "sid2", f, c, scope, agent = AgentKind.CODEX, sinceSeq = 0, fileResolver = null, tickMs = 50)
        obs.start()
        withTimeout(5_000) { while (c.frames.none { it is ConvoHistory }) delay(20) }
        // replaced by a shorter file (a rewrite): line numbers past the cut mean nothing any more
        rollout("rollout-2026-10-05T20-00-00-sid2.jsonl", "only")
        withTimeout(5_000) { while (c.frames.filterIsInstance<ConvoHistory>().size < 2) delay(20) }
        val again = c.frames.filterIsInstance<ConvoHistory>().last()
        assertFalse(again.delta)
        assertEquals(listOf("only"), again.messages.map { it.text })
        obs.close()
    }
}
