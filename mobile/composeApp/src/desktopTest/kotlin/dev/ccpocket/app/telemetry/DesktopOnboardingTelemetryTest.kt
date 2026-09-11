package dev.ccpocket.app.telemetry

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.desktop.ConnectPanel
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.theme.PocketTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Desktop had no install-guide exposure event at all: the mobile OnboardingScreen never renders here, so a
 * desktop user who opened the app and left looked identical to one who never launched it. The first-run
 * screen on desktop is the pairing form, and onboarding_shown belongs to a GENUINE first run only —
 * re-entering the same form to add a second computer is not onboarding.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopOnboardingTelemetryTest {
    private val seen = mutableListOf<TelEvent>()

    @BeforeTest fun tap() { seen.clear(); telemetryTap = { e, _ -> synchronized(seen) { seen += e } } }
    @AfterTest fun untap() { telemetryTap = null }

    private fun repo() = PocketRepository(CoroutineScope(Dispatchers.Unconfined)).also { it.pairedList.clear() }
    private fun paired() = PairedDaemon(relay = "wss://relay.test", accountId = "acct", daemonPub = "pub", deviceId = "dev", credential = "cred")

    @Test fun firstRunPairingFormReportsOnboardingShownOnce() = runComposeUiTest {
        val r = repo()
        setContent { PocketTheme { Box(Modifier.requiredSize(400.dp, 600.dp)) { ConnectPanel(r) } } }
        waitForIdle()
        assertEquals(1, synchronized(seen) { seen.count { it == TelEvent.OnboardingShown } })
    }

    @Test fun addingASecondComputerIsNotOnboarding() = runComposeUiTest {
        val r = repo().also { it.pairedList.add(paired()); it.addingDevice.value = true }
        setContent { PocketTheme { Box(Modifier.requiredSize(400.dp, 600.dp)) { ConnectPanel(r) } } }
        waitForIdle()
        assertEquals(0, synchronized(seen) { seen.count { it == TelEvent.OnboardingShown } })
    }
}
