package dev.ccpocket.app.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.ccpocket.app.assertPresent
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.desktop.ARCHIVE_REFUSAL_TAG
import dev.ccpocket.app.desktop.DesktopApp
import dev.ccpocket.app.desktop.DesktopModel
import dev.ccpocket.app.desktop.FakeDesktopStore
import dev.ccpocket.app.desktop.RepoDesktopModel
import dev.ccpocket.app.desktop.SeedDesktopModel
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.archive_session
import dev.ccpocket.app.resources.archive_toast_archive_failed
import dev.ccpocket.app.resources.archive_toast_archived
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.SetSessionArchived
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `archive_failed` stays out of the open chat (UnscopedErrorRoutingTest) and still reaches the user, through the
 * feedback each platform already gives a list action: the phone's archive toast, the desktop's inline row refusal
 * (the rename refusal's grammar — the desktop shows no toast for archiving).
 */
@OptIn(ExperimentalTestApi::class)
class ArchiveRefusalUiTest {
    private val refusal = PocketError("archive_failed", "could not update the archive for this session")

    private fun repo(scope: CoroutineScope, sent: MutableList<Any> = mutableListOf()) = PocketRepository(scope).apply {
        paired.value = PairedDaemon(relay = "wss://test", accountId = "acct", daemonPub = "pk", deviceId = "dev", credential = "c")
        onSendForTest = { sent += it }
    }

    @Test
    fun phoneToastStatesTheRefusalAndRetriesTheSameVerb() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Any>()
        val r = repo(scope, sent)
        r.setSessionArchived("/w", "sid-9", archived = true, title = "Fix relay")
        setContent { PocketTheme { ArchiveToastBar(r) } }
        assertPresent(str(Res.string.archive_toast_archived))

        r.receiveForTest(refusal)
        waitForIdle()
        assertPresent(str(Res.string.archive_toast_archive_failed))
        assertFalse(present(str(Res.string.archive_toast_archived)), "no success receipt for an archive that failed")

        sent.clear()
        onNode(androidx.compose.ui.test.hasText(str(Res.string.archive_session))).performClick() // retry, not the reverse verb
        assertEquals(listOf<Any>(SetSessionArchived("/w", "sid-9", archived = true, fromArchiveView = false)), sent.filterIsInstance<SetSessionArchived>())
        scope.cancel()
    }

    @Test
    fun desktopModelReadsTheRefusalForTheRowThatAsked() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val r = repo(scope)
        val model = RepoDesktopModel(r, scope, store = FakeDesktopStore())
        r.setSessionArchived("/w", "sid-9", archived = true, title = "Fix relay")
        assertNull(model.archiveRefused("sid-9"), "the optimistic receipt is not a refusal")

        r.receiveForTest(refusal)
        assertEquals(true, model.archiveRefused("sid-9"))
        assertNull(model.archiveRefused("sid-other"), "only the row that asked")

        model.dismissArchiveError()
        assertNull(model.archiveRefused("sid-9"))
        scope.cancel()
    }

    @Test
    fun desktopSidebarRowShowsTheRefusalInline() = runComposeUiTest {
        val refusedId = mutableStateOf<String?>(null)
        val seed = SeedDesktopModel()
        val target = seed.sessions.first().sessionId
        val model = object : DesktopModel by seed {
            override fun archiveRefused(sessionId: String): Boolean? = if (sessionId == refusedId.value) true else null
            override fun dismissArchiveError() { refusedId.value = null }
        }
        setContent { PocketTheme { DesktopApp(model) } }
        assertTrue(onAllNodes(hasTestTag(ARCHIVE_REFUSAL_TAG)).fetchSemanticsNodes().isEmpty())

        refusedId.value = target
        waitForIdle()
        assertPresent(str(Res.string.archive_toast_archive_failed))

        onNodeWithTag(ARCHIVE_REFUSAL_TAG).performClick()
        waitForIdle()
        assertTrue(onAllNodes(hasTestTag(ARCHIVE_REFUSAL_TAG)).fetchSemanticsNodes().isEmpty(), "a click dismisses it")
    }
}
