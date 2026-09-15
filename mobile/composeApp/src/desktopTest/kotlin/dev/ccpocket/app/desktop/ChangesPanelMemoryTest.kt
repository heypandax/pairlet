package dev.ccpocket.app.desktop

import dev.ccpocket.app.data.DemoData
import dev.ccpocket.app.data.PocketRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The docked Changes panel remembers, PER CONVERSATION, the file in its viewer and the expanded tree
 * directories. The repo closes its viewer on every session switch, so without this memory the panel on
 * A came back blank after a detour through B.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChangesPanelMemoryTest {

    private fun withDemoModel(block: (PocketRepository, RepoDesktopModel) -> Unit) {
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler))
        val repo = PocketRepository(scope)
        repo.enterDemo()
        repo.clearDraft(DemoData.LIVE_SESSION_ID)
        val model = RepoDesktopModel(repo, scope, store = FakeDesktopStore())
        repo.openSession(DemoData.LIVE_DIR)
        scheduler.runCurrent()
        try { block(repo, model) } finally { scope.cancel(); repo.clearDraft(DemoData.LIVE_SESSION_ID) }
    }

    @Test
    fun viewedFileIsRestoredForItsOwnConversationOnly() = withDemoModel { repo, m ->
        val keyA = m.conversationKey!!
        m.selectChangedFile("/proj/a/README.md")
        assertEquals("/proj/a/README.md", m.selectedChangedPath)

        // what every session switch does to the repo's viewer
        repo.closeFileViewer()
        assertNull(m.selectedChangedPath)

        // another conversation has nothing to restore — A's file must not leak over it
        repo.sessionKey.value = "conversation-B"
        assertFalse(m.restoreChangedFile())
        assertNull(m.selectedChangedPath)

        // back on A the remembered file is re-opened
        repo.sessionKey.value = keyA
        assertTrue(m.restoreChangedFile())
        assertEquals("/proj/a/README.md", m.selectedChangedPath)
    }

    @Test
    fun expandedDirectoriesAreKeptPerConversation() = withDemoModel { repo, m ->
        val keyA = m.conversationKey!!
        m.expandedDirs()["docs"] = Unit
        repo.sessionKey.value = "conversation-B"
        assertTrue(m.expandedDirs().isEmpty(), "B starts with its own, empty tree state")
        repo.sessionKey.value = keyA
        assertEquals(setOf("docs"), m.expandedDirs().keys)
    }
}
