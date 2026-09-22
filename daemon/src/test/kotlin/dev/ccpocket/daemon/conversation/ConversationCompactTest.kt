package dev.ccpocket.daemon.conversation

import dev.ccpocket.daemon.agent.*
import dev.ccpocket.daemon.claude.ClaudeBackend
import dev.ccpocket.protocol.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/** Fixture subprocess only: exercises the real pump without a provider call or another daemon. */
class ConversationCompactTest {
    @Test fun boundaryImmediatelyReplacesUsageAndAggregateResultCannotUndoIt() = runBlocking {
        if (System.getProperty("os.name").lowercase().contains("win")) return@runBlocking
        val dir = Files.createTempDirectory("compact-pump")
        val script = dir.resolve("events")
        Files.writeString(script, "init\nbefore\ncompact\nresult\nunknown\nresult\nafter\nresult\nsummary\n")
        val frames = CopyOnWriteArrayList<Frame>()
        val provider = ClaudeBackend()
        val backend = object : AgentBackend by provider {
            override fun processBuilder(spec: AgentSpec) = ProcessBuilder("sh", "-c", "cat \"\$1\"; sleep 30", "fixture", script.toString())
            override suspend fun attach(io: AgentIo, spec: AgentSpec) {}
            override suspend fun sendPrompt(text: String, images: List<ImageData>) {}
            override suspend fun parse(line: String): List<AgentEvent> = listOf(when (line) {
                "init" -> AgentEvent.SessionInit("fixture", dir.toString(), "claude-fixture")
                "before" -> AgentEvent.AssistantUsage(191000, null, null)
                "compact" -> AgentEvent.CompactBoundary(12000)
                "unknown" -> AgentEvent.CompactBoundary(null)
                "after" -> AgentEvent.AssistantUsage(13000, null, null, 7)
                "summary" -> AgentEvent.CompactSummary("中\u0001\"\\".repeat(200_000))
                else -> AgentEvent.TurnResult(null, TokenUsage(999999, 88888), false)
            })
        }
        val scope = CoroutineScope(Dispatchers.Default)
        val convo = Conversation("compact", dir, PermissionMode.DEFAULT,
            initialSink = { frames += it }, parentScope = scope, backend = backend)
        try {
            convo.open(resumeId = null, model = null)
            convo.sendPrompt("fixture")
            withTimeout(10_000) { while (frames.filterIsInstance<SessionLive>().none { it.compactSummary != null }) delay(10) }
            val results = frames.filterIsInstance<TurnDone>()
            assertEquals(3, results.size)
            assertNull(results[0].usage, "pre-compact aggregate must not overwrite 12k")
            assertNull(results[1].usage, "unknown post-compact usage must not resurrect old values")
            assertEquals(13007L, results[2].usage?.contextTokens)
            val announcements = frames.filterIsInstance<SessionLive>()
            assertTrue(announcements.any { it.contextUsed == 12000L && it.contextUsedAuthoritative })
            assertTrue(announcements.any { it.contextUsed == null && it.contextUsedAuthoritative })
            assertTrue(announcements.any { it.contextUsed == 13007L })
            val summary = announcements.single { it.compactSummary != null }
            assertTrue(PocketJson.encodeToString(Envelope("fixture", 0, body = summary)).toByteArray().size < 3_100_000)
            assertTrue(frames.none { it is AssistantChunk }, "summary must never count as assistant output on old clients")
            val reattached = CopyOnWriteArrayList<Frame>()
            convo.reattach({ reattached += it })
            assertEquals(13007L, reattached.filterIsInstance<SessionLive>().last().contextUsed)
            assertTrue(reattached.filterIsInstance<SessionLive>().all { it.compactSummary == null })
        } finally {
            convo.close(); scope.cancel(); dir.toFile().deleteRecursively()
        }
    }
}
