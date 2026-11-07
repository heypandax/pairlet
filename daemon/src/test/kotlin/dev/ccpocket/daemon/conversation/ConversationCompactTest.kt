package dev.ccpocket.daemon.conversation

import dev.ccpocket.daemon.agent.*
import dev.ccpocket.daemon.claude.ClaudeBackend
import dev.ccpocket.daemon.codex.CodexBackend
import dev.ccpocket.protocol.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
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
    @Test fun nativeCompactWaitsForCompletionAndResumesWithoutProcessError() = runBlocking {
        exerciseNativeCompact(reject = false)
    }

    @Test fun rejectedNativeCompactSettlesWithItsRealErrorOnly() = runBlocking {
        exerciseNativeCompact(reject = true)
    }

    @Test fun rejectedCompactCannotClearAConcurrentPromptsExecutingState() = runBlocking {
        if (LifecycleHarness.isWindows()) return@runBlocking
        val provider = LifecycleBackend(supportsNativeCompact = true) { _, _ -> LifecycleBackend.SILENT }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val backend = object : AgentBackend by provider {
            override suspend fun compact(): Boolean {
                entered.complete(Unit)
                release.await()
                return false
            }
        }
        val dir = Files.createTempDirectory("compact-reservation")
        val scope = CoroutineScope(Dispatchers.Default)
        val convo = Conversation("compact-reservation", dir, PermissionMode.DEFAULT, {}, scope, backend)
        try {
            convo.open(resumeId = null, model = null)
            val compact = async { convo.sendPrompt("/compact", promptId = "compact") }
            withTimeout(10_000) { entered.await() }
            // UNDISPATCHED drives the competing send to its first suspension: it must wait for the
            // lifecycle lock, not enter the backend while compact's accept/refuse is undecided.
            val prompt = async(start = CoroutineStart.UNDISPATCHED) { convo.sendPrompt("next", promptId = "next") }
            assertTrue(provider.sends.isEmpty(), "the competing prompt must not overtake compact reservation")
            release.complete(Unit)
            withTimeout(10_000) { compact.await(); prompt.await() }
            assertEquals(listOf("next"), provider.sends.map { it.second })
            assertTrue(convo.isExecuting(), "a refused compact must not settle the next prompt")
        } finally {
            release.complete(Unit)
            convo.close(); scope.cancel(); dir.toFile().deleteRecursively()
        }
    }

    @Test fun compactDoesNotRelaunchAndSettleAnUnconsumedPrompt() = runBlocking {
        if (LifecycleHarness.isWindows()) return@runBlocking
        val backend = LifecycleBackend(supportsNativeCompact = true) { _, _ -> "exit 0" }
        val h = LifecycleHarness(backend)
        try {
            h.convo.open(resumeId = null, model = null)
            h.convo.sendPrompt("lost", promptId = "lost")
            h.await { h.framesOf<PocketError>().any { it.code == "process_exited" } }
            assertTrue(h.convo.hasQueuedPrompts())
            h.convo.sendPrompt("/compact", promptId = "compact")
            assertEquals(1, backend.specs.size, "compact must not re-inject a prompt then clear its work state")
            assertTrue(h.convo.hasQueuedPrompts())
            assertTrue(h.framesOf<TurnDone>().last().finalText.orEmpty().contains("pending message"))
        } finally {
            h.close(); h.workdir.toFile().deleteRecursively()
        }
    }

    private suspend fun exerciseNativeCompact(reject: Boolean) {
        if (System.getProperty("os.name").lowercase().contains("win")) return
        val dir = Files.createTempDirectory("codex-compact-pump")
        val release = dir.resolve("complete")
        val frames = CopyOnWriteArrayList<Frame>()
        val requests = CopyOnWriteArrayList<String>()
        val cleanExits = AtomicInteger()
        val provider = CodexBackend(null)
        var accepted = CompletableDeferred<Unit>()
        val script = """
            worker=''
            trap '[ -z "${'$'}worker" ] || kill "${'$'}worker" 2>/dev/null || true' EXIT
            while IFS= read -r line; do
                id=${'$'}(printf '%s' "${'$'}line" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
                case "${'$'}line" in
                    *'"method":"initialize"'*) printf '{"id":%s,"result":{}}\n' "${'$'}id" ;;
                    *'"method":"thread/start"'*|*'"method":"thread/resume"'*)
                        printf '{"id":%s,"result":{"thread":{"id":"fixture-compact"},"model":"fixture"}}\n' "${'$'}id" ;;
                    *'"method":"thread/compact/start"'*)
                        if [ "${'$'}2" = true ]; then
                            printf '{"id":%s,"error":{"code":-32601,"message":"compact unsupported"}}\n' "${'$'}id"
                        else
                            printf '{"id":%s,"result":{}}\n' "${'$'}id"
                            (
                                while [ ! -f "${'$'}1" ]; do sleep 0.02; done
                                printf '%s\n' '{"method":"turn/started","params":{"threadId":"fixture-compact","turn":{"id":"tc"}}}'
                                printf '%s\n' '{"method":"item/completed","params":{"threadId":"fixture-compact","turnId":"tc","item":{"id":"ic","type":"contextCompaction"}}}'
                                printf '%s\n' '{"method":"turn/completed","params":{"threadId":"fixture-compact","turn":{"id":"tc","status":"completed"}}}'
                            ) &
                            worker=${'$'}!
                        fi ;;
                esac
            done
        """.trimIndent()
        val backend = object : AgentBackend by provider {
            override fun processBuilder(spec: AgentSpec) =
                ProcessBuilder("sh", "-c", script, "fixture", release.toString(), reject.toString())
            override suspend fun attach(io: AgentIo, spec: AgentSpec) = provider.attach(
                AgentIo(writeLine = { requests += it; io.writeLine(it) }, emit = io.emit,
                    inject = io.inject, requestProcessExit = io.requestProcessExit), spec,
            )
            override suspend fun parse(line: String): List<AgentEvent> {
                val result = provider.parse(line)
                // IDs are monotonic across processes: the most recent control request identifies its ack.
                val request = requests.lastOrNull { "thread/compact/start" in it }
                val id = request?.let { Regex("\\\"id\\\":(\\d+)").find(it)?.groupValues?.get(1) }
                if (id != null && line.startsWith("{\"id\":$id,")) accepted.complete(Unit)
                return result
            }
        }
        val scope = CoroutineScope(Dispatchers.Default)
        val convo = Conversation("native-compact", dir, PermissionMode.DEFAULT,
            initialSink = { frames += it }, parentScope = scope, backend = backend)
        convo.lifecycleProbe = { if (it == Conversation.LifecyclePoint.ONE_SHOT_AFTER_NULL) cleanExits.incrementAndGet() }
        try {
            convo.open(resumeId = null, model = null)
            repeat(2) { index ->
                accepted = CompletableDeferred()
                Files.deleteIfExists(release)
                convo.sendPrompt("/compact", promptId = "compact-$index")
                withTimeout(10_000) { accepted.await() }
                if (!reject) {
                    assertTrue(convo.isExecuting(), "accepted compaction must remain protected as active work")
                    assertEquals(index, frames.filterIsInstance<TurnDone>().size)
                    assertEquals(index, frames.filterIsInstance<SessionLive>().count { it.compactSummary != null },
                        "acceptance must not announce success")
                    Files.writeString(release, "complete")
                }
                withTimeout(10_000) {
                    while (cleanExits.get() <= index && frames.none { it is PocketError }) delay(10)
                }
                assertTrue(frames.none { it is PocketError }, frames.toString())
                assertEquals(index + 1, cleanExits.get())
                assertEquals(index + 1, frames.filterIsInstance<TurnDone>().size)
                val done = frames.filterIsInstance<TurnDone>().last()
                val cards = frames.filterIsInstance<SessionLive>().filter { it.compactSummary != null }
                assertEquals(if (reject) 0 else index + 1, cards.size)
                if (reject) assertTrue(done.error.orEmpty().contains("compact unsupported"), done.toString())
                else {
                    assertNull(done.error)
                    assertEquals("Context compacted.", done.finalText)
                    assertEquals(done.finalText, cards.last().compactSummary)
                    assertTrue(frames.indexOf(cards.last()) < frames.indexOf(done))
                }
                assertFalse(convo.isExecuting())
            }
            assertEquals(2, requests.count { "thread/compact/start" in it })
            assertTrue(requests.any { "thread/resume" in it })
            assertTrue(requests.none { "\"method\":\"turn/start\"" in it }, requests.toString())
        } finally {
            convo.close(); scope.cancel(); dir.toFile().deleteRecursively()
        }
    }

}
