package dev.ccpocket.daemon.session

import dev.ccpocket.daemon.agent.AgentBackendFactory
import dev.ccpocket.daemon.conversation.LifecycleBackend
import dev.ccpocket.daemon.conversation.LifecycleHarness
import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.TurnDone
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Disabled
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Registry-side reproductions of design-conversation-lifecycle T10/T11 (D10: the two-phase open and the
 * tombstone-less close). Both need the single-flight open + close tombstone of S8, so they are DISABLED
 * until that step lands; on the current code each of them fails. The seams they use
 * ([SessionRegistry.beforeColdInsert], [SessionRegistry.beforeReapClose]) are null in production.
 */
class SessionRegistryLifecycleRaceTest {

    private val frames = CopyOnWriteArrayList<Frame>()
    private val sink = OutboundSink { frames += it }

    /** T10 / D10 — two opens of the same cold resume id both miss the live lookup and both create. */
    @Test
    @Disabled("待 S8：同一持久身份的打开合并为一次（opening 表）")
    fun two_concurrent_opens_of_one_session_create_one_conversation() = runBlocking {
        if (LifecycleHarness.isWindows()) return@runBlocking
        val backend = LifecycleBackend { _, _ -> LifecycleBackend.ECHO_TURNS }
        val scope = CoroutineScope(Dispatchers.Default)
        val registry = SessionRegistry(scope, backends = mapOf(AgentKind.CLAUDE to AgentBackendFactory { backend }))
        val hits = AtomicInteger()
        val firstParked = CompletableDeferred<Unit>()
        val secondArrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        registry.beforeColdInsert = {
            if (hits.incrementAndGet() == 1) { firstParked.complete(Unit); release.await() } else secondArrived.complete(Unit)
        }
        val workdir = Files.createTempDirectory("ccp-t10").toString()
        try {
            val first = async { registry.open(OpenSession(workdir, resumeId = "K-t10"), sink) }
            withTimeout(10_000) { firstParked.await() }
            val second = async { registry.open(OpenSession(workdir, resumeId = "K-t10"), sink) }
            // fixed code makes the second open wait on the first instead of reaching the insert — bound it
            withTimeoutOrNull(1_500) { secondArrived.await() }
            release.complete(Unit)
            assertEquals(first.await(), second.await(), "one persistent session, one live conversation")
        } finally {
            release.complete(Unit)
            registry.closeAll()
            scope.cancel()
        }
    }

    /** T11 / D10 — the reaper has removed a conversation but not yet closed it (its process still runs).
     *  A re-open in that window finds no live match and spawns a second writer on the same session. */
    @Test
    @Disabled("待 S8：回收/关闭登记墓碑，重开等旧进程真正退出")
    fun a_reopen_during_reap_waits_for_the_old_process() = runBlocking {
        if (LifecycleHarness.isWindows()) return@runBlocking
        val script = "while IFS= read -r line; do printf 'init:K-t11\\nuser:%s\\nresult\\n' \"\$line\"; done; sleep 30"
        val backend = LifecycleBackend { _, _ -> script }
        val scope = CoroutineScope(Dispatchers.Default)
        val registry = SessionRegistry(scope, backends = mapOf(AgentKind.CLAUDE to AgentBackendFactory { backend }))
        val parked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        registry.beforeReapClose = { parked.complete(Unit); release.await() }
        val workdir = Files.createTempDirectory("ccp-t11").toString()
        try {
            val first = registry.open(OpenSession(workdir, resumeId = "K-t11"), sink)
            registry.sendPrompt(SendPrompt(first, "one"))
            withTimeout(10_000) { while (frames.none { it is TurnDone }) delay(10) }
            delay(20) // strictly past the idle window below
            val reaper = launch { registry.reapIdle(idleMs = 1) }
            withTimeout(10_000) { parked.await() }
            // the old conversation is out of the map but its process is alive: a re-open now must not spawn
            val reopen = async {
                val id = registry.open(OpenSession(workdir, resumeId = "K-t11"), sink)
                registry.sendPrompt(SendPrompt(id, "two"))
            }
            withTimeoutOrNull(1_500) { reopen.await() }
            assertEquals(1, backend.specs.size, "no second process while the first is still being closed")
            release.complete(Unit)
            reaper.join()
            reopen.await()
        } finally {
            release.complete(Unit)
            registry.closeAll()
            scope.cancel()
        }
    }
}
