package dev.ccpocket.app.desktop

import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.Sessions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals

/** Real inbound frame handling, without a transport or the demo's generated session replies. */
class RepoDesktopModelSessionIdentityTest {
    private val dir = "/project/a"
    private fun row(id: String, title: String = "Continue", branch: String? = null) =
        SessionSummary(id, title, title, 1, dir, 1L, gitBranch = branch)

    private fun withModel(block: (PocketRepository, RepoDesktopModel) -> Unit) {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val repo = PocketRepository(scope)
            repo.demoMode.value = true // disable persisted working-set writes; replies below are explicit
            repo.paired.value = PairedDaemon("wss://test", "acct-test", "pk", "dev", "cred")
            val model = RepoDesktopModel(repo, scope, store = FakeDesktopStore())
            block(repo, model)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun unlistedSessionWithAnExistingTitleAppearsBeforeRefreshAndMergesById() = withModel { repo, model ->
        val old = row("old")
        repo.receiveForTest(Sessions(dir, listOf(old)))
        repo.receiveForTest(SessionLive("convo-new", dir, sessionId = "new", title = old.title))

        // SessionLive knows the new ID before the transcript scanner has persisted/listed it.
        assertEquals(listOf("new", "old"), model.sessionGroups.single().sessions.map { it.sessionId })
        assertEquals("new", model.selectedSessionId)

        // The later list response (also returned by manual refresh) replaces the synthesized row.
        repo.receiveForTest(Sessions(dir, listOf(row("new"), old)))
        assertEquals(listOf("new", "old"), model.sessionGroups.single().sessions.map { it.sessionId })
        assertEquals("new", model.selectedSessionId)
    }

    @Test
    fun listedSessionUsesItsIdEvenWhenAnotherRowHasTheSameTitle() = withModel { repo, model ->
        repo.receiveForTest(Sessions(dir, listOf(row("old", branch = "old-branch"), row("new", branch = "new-branch"))))
        repo.receiveForTest(SessionLive("convo-new", dir, sessionId = "new", title = "Continue"))
        assertEquals("new", model.selectedSessionId)
        assertEquals("new-branch", model.chatBranch)
        assertEquals(listOf("old", "new"), model.sessions.map { it.sessionId })
    }

    @Test
    fun renamedLiveTitleStillResolvesTheListedSessionById() = withModel { repo, model ->
        repo.receiveForTest(Sessions(dir, listOf(row("new", title = "Old title", branch = "feature"))))
        repo.receiveForTest(SessionLive("convo-new", dir, sessionId = "new", title = "New title"))
        assertEquals("new", model.selectedSessionId)
        assertEquals("feature", model.chatBranch)
        assertEquals(listOf("new"), model.sessions.map { it.sessionId })
    }

    @Test
    fun olderPeerWithoutSessionIdRetainsTitleFallback() = withModel { repo, model ->
        repo.receiveForTest(Sessions(dir, listOf(row("old", branch = "feature"))))
        repo.receiveForTest(SessionLive("convo-old", dir, title = "Continue"))
        assertEquals("old", model.selectedSessionId)
        assertEquals("feature", model.chatBranch)
    }

    @Test
    fun unlistedOpenSessionDoesNotAppearInAnotherProjectsList() = withModel { repo, model ->
        repo.receiveForTest(Sessions(dir, listOf(row("old"))))
        repo.receiveForTest(SessionLive("convo-new", "/project/b", sessionId = "new", title = "Continue"))
        assertEquals(listOf("old"), model.sessionGroups.single().sessions.map { it.sessionId })
    }

    @Test
    fun matchingIdInAnotherProjectDoesNotSupplyTheOpenChatsMetadata() = withModel { repo, model ->
        repo.receiveForTest(Sessions(dir, listOf(row("same-id", branch = "other-project-branch"))))
        repo.receiveForTest(SessionLive("convo-new", "/project/b", sessionId = "same-id", title = "Continue"))
        assertEquals(null, model.chatBranch)
    }
}
