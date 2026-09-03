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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Issue #340 — a session must be announced LIVE *before* its transcript is read, not after.
 *
 * Incident shape: the phone waits for exactly one [SessionLive] after tapping a session and declares
 * "couldn't open — the computer didn't respond" 8s later. [Conversation.open]'s announce used to sit at
 * the END of the seed chain (title, model, default model, window, effort, occupancy, degraded streak),
 * so every one of those reads was inside that deadline: a large transcript, a busy disk, a loaded daemon
 * or a jittery relay pushed the round trip past 8s for a session that was in fact opening fine. The
 * daemon's own `open → convo` line is stamped at REGISTRATION, so it read "instant" throughout and
 * pointed every investigation away from the gap.
 *
 * The fix is purely an ordering one — no wire change, the seeded re-announce backfills the same fields
 * the phone already reconciles field-by-field — so this test is an ordering one: the seed read is held
 * on a latch and the announce must already be out.
 */
class ConversationOpenAnnounceOrderTest {

    private fun win() = System.getProperty("os.name").lowercase().contains("win")

    /** Every seed read is instant except [resumeTitle], which blocks on [gate] — standing in for the
     *  multi-MB parse (or the busy disk) that used to hold the whole announce hostage. */
    private class TitleGateBackend(private val gate: CountDownLatch) : AgentBackend {
        override val kind = AgentKind.CLAUDE
        override fun processBuilder(spec: AgentSpec): ProcessBuilder = ProcessBuilder("sh", "-c", "sleep 30")
        override suspend fun attach(io: AgentIo, spec: AgentSpec) {}
        override suspend fun parse(line: String): List<AgentEvent> = listOf(AgentEvent.Ignored(line))
        override suspend fun sendPrompt(text: String, images: List<ImageData>) {}
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
        override fun resumeTitle(workdir: String, sessionId: String): String? {
            gate.await()
            return TITLE
        }
    }

    @Test
    fun the_session_is_announced_live_before_the_transcript_is_read() {
        if (win()) return
        runBlocking {
            val gate = CountDownLatch(1)
            // COPY-ON-WRITE: assertions iterate this while the open coroutine appends to it
            val frames = CopyOnWriteArrayList<Frame>()
            val scope = CoroutineScope(Dispatchers.Default)
            val dir = Files.createTempDirectory("ccp-open-order")
            val convo = Conversation(
                convoId = "cOrder", initialWorkdir = dir, initialMode = PermissionMode.DEFAULT,
                initialSink = { f -> frames += f },
                parentScope = scope, backend = TitleGateBackend(gate),
            )
            suspend fun awaitLives(n: Int): List<SessionLive> = withTimeout(10_000) {
                var lives = frames.filterIsInstance<SessionLive>()
                while (lives.size < n) {
                    delay(20)
                    lives = frames.filterIsInstance<SessionLive>()
                }
                lives
            }
            try {
                convo.open(resumeId = SID, model = null) // lazy open — announces, spawns nothing

                // THE FIX: the announce lands while the read is still blocked. Reaching this line at all
                // is the assertion — before #340 the only announce was behind [gate] and this timed out.
                val early = awaitLives(1).single()
                assertEquals(SID, early.sessionId)
                assertEquals(dir.toString(), early.workdir)
                assertNull(early.title, "the pre-read announce cannot know a title yet — that is the point")

                // …and it really is the seed chain holding the second one, not two instant emits: nothing
                // more may reach the phone while the read is blocked.
                delay(200)
                assertEquals(
                    1, frames.filterIsInstance<SessionLive>().size,
                    "the seeded re-announce must still be behind the transcript read",
                )

                gate.countDown()

                // the seeded re-announce backfills what the read recovered, over the same frame type the
                // phone already reconciles field-by-field (zero wire change)
                val seeded = awaitLives(2)[1]
                assertEquals(SID, seeded.sessionId)
                assertEquals(TITLE, seeded.title)
            } finally {
                gate.countDown() // never leave the hook blocked if an assertion threw first
                convo.close()
                scope.cancel()
                dir.toFile().deleteRecursively()
            }
        }
    }

    private companion object {
        const val SID = "order-resumed-sid"
        const val TITLE = "Workflow allowlist tool check"
    }
}
