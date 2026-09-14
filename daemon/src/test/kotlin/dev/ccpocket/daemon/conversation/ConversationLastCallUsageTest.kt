package dev.ccpocket.daemon.conversation

import dev.ccpocket.daemon.agent.*
import dev.ccpocket.daemon.zcode.ZCodeBackend
import dev.ccpocket.protocol.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

/** Local stdout fixture → real ZCode parser → Conversation → TurnDone. No provider process. */
class ConversationLastCallUsageTest {
    @Test
    fun last_call_output_zero_and_legacy_fallback_survive_turn_boundaries() = runBlocking {
        if (System.getProperty("os.name").lowercase().contains("win")) return@runBlocking
        val dir = Files.createTempDirectory("last-call-usage")
        fun call(output: Long?) = """{"method":"session/event","params":{"type":"session.updated","payload":{"type":"model_request_completed","usage":{"inputTokens":100,"cacheReadTokens":20,"cacheWriteTokens":10${output?.let { ",\"outputTokens\":$it" }.orEmpty()}}}}}"""
        val result = """{"method":"session/event","params":{"type":"turn.completed","payload":{"resultType":"success","usage":{"inputTokens":9999,"outputTokens":50}}}}"""
        val stream = dir.resolve("stream.jsonl")
        Files.writeString(stream, listOf("init", call(40), call(7), result, call(0), result, call(null), result, result).joinToString("\n", postfix = "\n"))
        val continueTurns = CompletableDeferred<Unit>()
        val parser = ZCodeBackend(null)
        val backend = object : AgentBackend by parser {
            override fun processBuilder(spec: AgentSpec) = ProcessBuilder("sh", "-c", "cat \"\$1\"; sleep 30", "fixture", stream.toString())
            override suspend fun attach(io: AgentIo, spec: AgentSpec) {}
            override suspend fun sendPrompt(text: String, images: List<ImageData>) {}
            override suspend fun parse(line: String): List<AgentEvent> {
                if (line == call(0)) continueTurns.await()
                return if (line == "init") listOf(AgentEvent.SessionInit("fixture-session", dir.toString(), "fixture-model"))
                else parser.parse(line)
            }
        }
        val frames = CopyOnWriteArrayList<Frame>()
        val scope = CoroutineScope(Dispatchers.Default)
        val convo = Conversation("last-call", dir, PermissionMode.DEFAULT,
            initialSink = { frames += it }, parentScope = scope, backend = backend)
        try {
            convo.open(resumeId = null, model = null)
            convo.sendPrompt("fixture")
            withTimeout(10_000) { while (frames.filterIsInstance<TurnDone>().isEmpty()) delay(10) }
            val reattached = CopyOnWriteArrayList<Frame>()
            convo.reattach({ reattached += it })
            assertEquals(137L, reattached.filterIsInstance<SessionLive>().last().contextUsed,
                "reconnect seeds the latest request footprint before the next turn")
            continueTurns.complete(Unit)
            withTimeout(10_000) { while (frames.filterIsInstance<TurnDone>().size < 4) delay(10) }
            val usage = frames.filterIsInstance<TurnDone>().map { it.usage!! }
            assertEquals(listOf(7L, 0L, 50L, 50L), usage.map { it.outputTokens })
            assertEquals(listOf(137L, 130L, 180L, 10049L), usage.map { it.contextTokens },
                "last-call output wins, explicit zero stays zero, unknown keeps legacy fallback, completed turn clears the last call")
        } finally {
            convo.close()
            scope.cancel()
            dir.toFile().deleteRecursively()
        }
    }
}
