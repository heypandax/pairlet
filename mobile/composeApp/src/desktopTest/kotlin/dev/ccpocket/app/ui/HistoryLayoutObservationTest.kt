package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.data.ConnPhase
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.*

/** A history arriving after an unchanged viewport was measured still needs a placement receipt. */
@OptIn(ExperimentalTestApi::class)
class HistoryLayoutObservationTest {
    @Test
    fun newlyCreatedChatCompletesItsEmptyHistoryLayout() = runDesktopComposeUiTest(402, 874) {
        mainClock.autoAdvance = false
        lateinit var repo: PocketRepository
        var request: OpenSession? = null
        setContent {
            val scope = rememberCoroutineScope()
            repo = remember {
                PocketRepository(scope, PairedDaemon("wss://test.invalid", "layout-test", "pub", "device", "credential")).apply {
                    onSendForTest = { if (it is OpenSession) request = it }
                }
            }
            PocketTheme { ChatScreen(repo) }
        }
        waitForIdle()
        runOnIdle {
            repo.phase.value = ConnPhase.Ready
            repo.receiveForTest(DaemonInfo(supportedAgents = listOf("codex"), supportsDiagnostics = true))
            repo.openSession("/test/observability-layout", agent = AgentKind.CODEX)
        }
        mainClock.advanceTimeBy(64)
        waitForIdle()
        runOnIdle {
            val context = assertNotNull(request?.diagnostic)
            repo.receiveForTest(SessionLive("layout-convo", "/test/observability-layout", "layout-session", agent = AgentKind.CODEX, diagnostic = context))
            repo.receiveForTest(HistoryComplete("layout-convo", context, quality = "not_required"))
            assertNotNull(repo.historyLayoutToken.value)
        }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        runOnIdle { assertNull(repo.historyLayoutToken.value, "the real chat must acknowledge an empty visible history") }
    }

    @Test
    fun historyArrivingInAnUnchangedViewportGetsAReceipt() = runComposeUiTest {
        val token = mutableStateOf<String?>(null)
        val receipts = mutableListOf<String>()
        setContent {
            Box(Modifier.size(120.dp).observeHistoryLayout({ token.value }) {
                receipts += it
                token.value = null
            })
        }
        waitForIdle()
        runOnIdle { token.value = "history-a" }
        mainClock.advanceTimeBy(64)
        waitForIdle()
        runOnIdle { assertEquals(listOf("history-a"), receipts) }
    }

    @Test
    fun hiddenViewportWaitsUntilItIsActuallyPlaced() = runComposeUiTest {
        val token = mutableStateOf<String?>("history-a")
        val visible = mutableStateOf(false)
        val receipts = mutableListOf<String>()
        setContent {
            Box(Modifier.size(if (visible.value) 120.dp else 0.dp).observeHistoryLayout({ token.value }) {
                receipts += it
                token.value = null
            })
        }
        mainClock.advanceTimeBy(64)
        waitForIdle()
        runOnIdle { assertEquals(emptyList(), receipts); visible.value = true }
        mainClock.advanceTimeBy(64)
        waitForIdle()
        runOnIdle { assertEquals(listOf("history-a"), receipts) }
    }
}
