package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.*
import kotlinx.coroutines.*
import kotlin.test.*

class CompactContextTest {
    @Test fun authoritativeNullClearsOldUsageAndSummaryDoesNotSettlePendingPrompt() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val repo = PocketRepository(scope).apply {
                paired.value = PairedDaemon("wss://test", "acct-test", "pk", "dev", "cred")
                convoId.value = "c"
                receiveForTest(SessionLive("c", "/w", "s", contextUsed = 191000))
                messages.add(ChatItem.User("pending", pending = true, promptId = "p"))
            }
            assertEquals(191000L, repo.contextUsed.value)
            // Old metadata frames remain seed-only, even if their seed is stale.
            repo.receiveForTest(SessionLive("c", "/w", "s", contextUsed = 180000))
            assertEquals(191000L, repo.contextUsed.value)
            repo.receiveForTest(SessionLive("c", "/w", "s", contextUsedAuthoritative = true, compactSummary = "summary"))
            assertNull(repo.contextUsed.value)
            assertTrue(repo.messages.filterIsInstance<ChatItem.User>().first { it.promptId == "p" }.pending)
            assertTrue(repo.messages.filterIsInstance<ChatItem.User>().last().compactSummary)
            repo.receiveForTest(SessionLive("c", "/w", "s", contextUsed = 12000, contextUsedAuthoritative = true))
            assertEquals(12000L, repo.contextUsed.value)
            repo.receiveForTest(TurnDone("c", usage = TokenUsage(13000, 7)))
            assertEquals(13007L, repo.contextUsed.value)
        } finally { scope.cancel() }
    }
}
