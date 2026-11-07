package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.*
import kotlinx.coroutines.*
import kotlin.test.*

class CompactContextTest {
    @Test fun codexCompactionCardSurvivesFullHistoryReplayWithoutDoubling() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val repo = PocketRepository(scope).apply {
                paired.value = PairedDaemon("wss://test", "acct-test", "pk", "dev", "cred")
                convoId.value = "c"
                receiveForTest(SessionLive("c", "/w", "s", agent = AgentKind.CODEX))
                receiveForTest(ConvoHistory("c", listOf(HistoryMessage(ChatRole.USER, "before", seq = 1)), lastSeq = 1))
            }
            repo.receiveForTest(SessionLive("c", "/w", "s", agent = AgentKind.CODEX,
                compactSummary = "Context compacted."))
            repo.receiveForTest(TurnDone("c", finalText = "Context compacted."))
            assertEquals(1, repo.messages.filterIsInstance<ChatItem.User>().count { it.compactSummary })
            for (delta in listOf(true, false, false)) {
                val history = buildList {
                    if (!delta) add(HistoryMessage(ChatRole.USER, "before", seq = 1))
                    add(HistoryMessage(ChatRole.USER, "Context compacted.", compactSummary = true))
                }
                repo.receiveForTest(ConvoHistory("c", history, lastSeq = 2, delta = delta))
                val cards = repo.messages.filterIsInstance<ChatItem.User>().filter { it.compactSummary }
                assertEquals(1, cards.size)
                assertEquals("Context compacted.", cards.single().text)
                assertNull(cards.single().seq)
            }
        } finally { scope.cancel() }
    }

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
