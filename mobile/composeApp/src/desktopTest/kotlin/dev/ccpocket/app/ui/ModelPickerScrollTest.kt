package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.present
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ModelsList
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SessionLive
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Issue #366 — a gateway catalog runs to 200 model rows, and the model picker used to lay every one of
 * them out in a plain Column: past one screenful the sheet grew off the top of the display, so the last
 * rows (and the scrim, the only way out on iOS) became unreachable.
 *
 * What is pinned here is the fix's contract, not its pixels: under a BOUNDED host the picker caps itself
 * and scrolls inside that cap, so the LAST model in the list can be scrolled to and read; under an
 * UNBOUNDED host it must still compose (weight()/heightIn would measure at zero there — QuestionCard
 * #150), just without a cap.
 */
@OptIn(ExperimentalTestApi::class)
class ModelPickerScrollTest {

    private val convo = "c-model"
    private val gateway = "https://gateway.example.com/v1"

    /** 60 gateway ids: far more than a 500pt host can show, and each one uniquely named. */
    private val gatewayModels = (1..60).map { "vendor-model-$it" }
    private val lastModel = gatewayModels.last()

    private fun account() = PairedDaemon(
        relay = "wss://test.invalid", accountId = "acct-model", daemonPub = "pub",
        deviceId = "dev", credential = "cred", hostName = "alex-macbook",
    )

    /** A live Claude session on a third-party gateway — that is the case with the long catalog (#167/#366). */
    private fun PocketRepository.seedGatewayCatalog() {
        receiveForTest(
            SessionLive(
                convoId = convo, workdir = "/Users/alex/code/cc-pocket", sessionId = "s1",
                mode = PermissionMode.DEFAULT, executing = false, model = "claude-sonnet-4-5",
                agent = AgentKind.CLAUDE,
            ),
        )
        // DaemonInfo carries this on the wire; the picker only reads the state it lands in.
        gatewayBaseUrl.value = gateway
        receiveForTest(
            ModelsList(
                agent = AgentKind.CLAUDE, models = listOf("claude-sonnet-4-5"),
                gatewayModels = gatewayModels, gatewayModelsSource = "gateway",
            ),
        )
    }

    /** Compose the picker inside [host] — the whole point of the test is WHICH constraints it gets. */
    private fun picker(
        host: @androidx.compose.runtime.Composable (content: @androidx.compose.runtime.Composable () -> Unit) -> Unit,
        assertions: SkikoComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(W, H) {
        mainClock.autoAdvance = true // performScrollTo rides a real animation
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                val scope = rememberCoroutineScope()
                val repo = remember { PocketRepository(scope, account()).apply { seedGatewayCatalog() } }
                PocketTheme {
                    host { ModelPicker(repo, onBack = {}, onDone = {}) }
                }
            }
        }
        waitForIdle()
        assertions()
    }

    @Test
    fun theLastGatewayModelIsReachableInsideABoundedHost() = picker(
        host = { content -> Box(Modifier.fillMaxWidth().height(500.dp)) { content() } },
    ) {
        assertTrue(present(gatewayModels.first()), "the list renders at all")
        // the row that used to sit off-screen: scrollable into view, and actually displayed once there
        onAllNodes(hasText(lastModel)).onFirst().performScrollTo().assertIsDisplayed()
    }

    @Test
    fun anUnboundedHostStillComposesTheWholeList() = picker(
        // verticalScroll hands the picker infinite max height — the case where a cap/weight would
        // measure at zero and blank the sheet instead of merely leaving it uncapped
        host = { content -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) { content() } },
    ) {
        assertTrue(present(gatewayModels.first()), "the first row survives an unbounded host")
        assertTrue(present(lastModel), "…and so does the last one — nothing measured at zero")
    }

    private companion object {
        const val W = 402
        const val H = 874
    }
}
