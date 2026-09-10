package dev.ccpocket.app.telemetry

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.secure.SecureStore
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.ui.SettingsScreen
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class DiagnosticSharingSettingsTest {
    @Test fun nativeDesktopSettingsAlsoExposeTheCollectionSwitch() {
        assertNotNull(System.getProperty("ccpocket.secureStore.file"))
        val previous = Telemetry.isEnabled()
        try {
            Telemetry.setEnabled(false)
            runComposeUiTest {
                setContent {
                    PocketTheme {
                        dev.ccpocket.app.desktop.SettingsModal(dev.ccpocket.app.desktop.SeedDesktopModel(),
                            initialTab = dev.ccpocket.app.desktop.SettingsTab.ABOUT, onDismiss = {})
                    }
                }
                val sharing = runBlocking { getString(Res.string.diagnostic_sharing_title) }
                onNodeWithContentDescription(sharing).performScrollTo().assertIsOff().performClick().assertIsOn()
                assertEquals("true", SecureStore.getString("telemetry_enabled"))
            }
        } finally { Telemetry.setEnabled(previous) }
    }

    @Test fun supportPageExposesThePersistedCollectionSwitch() {
        assertNotNull(System.getProperty("ccpocket.secureStore.file"), "never modify real app preferences")
        val previous = Telemetry.isEnabled()
        try {
            Telemetry.setEnabled(false)
            runComposeUiTest {
                setContent {
                    val scope = rememberCoroutineScope()
                    val repo = remember { PocketRepository(scope) }
                    PocketTheme { SettingsScreen(repo, onBack = {}) }
                }
                val category = runBlocking { getString(Res.string.settings_cat_support) }
                val sharing = runBlocking { getString(Res.string.diagnostic_sharing_title) }
                onAllNodes(hasText(category)).onFirst().performClick()
                onNodeWithContentDescription(sharing).performScrollTo().assertIsOff().performClick()
                onNodeWithContentDescription(sharing).assertIsOn()
                assertTrue(Telemetry.isEnabled())
                assertEquals("true", SecureStore.getString("telemetry_enabled"))
                onNodeWithContentDescription(sharing).performClick().assertIsOff()
                assertFalse(Telemetry.isEnabled())
                assertEquals("false", SecureStore.getString("telemetry_enabled"))
            }
        } finally { Telemetry.setEnabled(previous) }
    }
}
