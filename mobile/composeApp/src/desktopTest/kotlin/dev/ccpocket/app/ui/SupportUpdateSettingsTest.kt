package dev.ccpocket.app.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.update.VersionStatus
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.*

@OptIn(ExperimentalTestApi::class)
class SupportUpdateSettingsTest {
    @Test fun daemon_update_is_sent_only_after_explicit_confirmation() = runComposeUiTest {
        val sent = mutableListOf<Frame>()
        lateinit var repo: PocketRepository
        setContent {
            val scope = rememberCoroutineScope()
            repo = remember { PocketRepository(scope).also {
                it.onSendForTest = { frame -> sent += frame }
                it.paired.value = PairedDaemon("wss://test", "settings-update", "pk", "device", "credential")
                it.connected.value = true
                it.receiveForTest(DaemonInfo(daemonVersion = "1.0.0", latestVersion = "999.0.0", daemonUpdateMode = "remote"))
            } }
            PocketTheme { SettingsScreen(repo, onBack = {}) }
        }
        val category = runBlocking { getString(Res.string.settings_cat_support) }
        onAllNodes(hasText(category)).onFirst().performClick()
        onNodeWithText(runBlocking { getString(Res.string.updates_daemon) }).performScrollTo().performClick()
        runOnIdle { assertEquals(0, sent.filterIsInstance<DaemonUpdateStart>().size) }
        val confirm = runBlocking { getString(Res.string.updates_daemon_confirm) }
        onNodeWithText(confirm).assertIsEnabled().performClick()
        runOnIdle { assertEquals(1, sent.filterIsInstance<DaemonUpdateStart>().size) }
        onNodeWithText(confirm).assertIsNotEnabled()
        runOnIdle {
            repo.receiveForTest(DaemonUpdateStatus(sent.filterIsInstance<DaemonUpdateStart>().single().requestId, "busy"))
        }
        onNodeWithText(runBlocking { getString(Res.string.updates_daemon_busy) }).assertExists()
    }

    @Test fun app_version_update_notice_opens_confirmation() = runComposeUiTest {
        setContent {
            val scope = rememberCoroutineScope()
            val repo = remember { PocketRepository(scope).also {
                it.versionStatus.value = VersionStatus("1.0.0", "1.0.0", "999.0.0", "pairlet update")
            } }
            PocketTheme { SettingsScreen(repo, onBack = {}) }
        }
        val category = runBlocking { getString(Res.string.settings_cat_support) }
        onAllNodes(hasText(category)).onFirst().performClick()
        onNodeWithText(runBlocking { getString(Res.string.updates_app) }).performScrollTo().performClick()
        onNodeWithTag("app-update-dialog").assertExists()
    }

    @Test fun daemon_version_update_notice_opens_confirmation() = runComposeUiTest {
        setContent {
            val scope = rememberCoroutineScope()
            val repo = remember { PocketRepository(scope).also {
                it.versionStatus.value = VersionStatus("2.0.0", "1.0.0", "2.0.0", "pairlet update")
            } }
            PocketTheme { SettingsScreen(repo, onBack = {}) }
        }
        val category = runBlocking { getString(Res.string.settings_cat_support) }
        onAllNodes(hasText(category)).onFirst().performClick()
        onNodeWithText(runBlocking { getString(Res.string.updates_daemon) }).performScrollTo().performClick()
        onNodeWithTag("daemon-update-dialog").assertExists()
    }
}
