package dev.ccpocket.app.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.about_computer_fingerprint
import dev.ccpocket.app.resources.about_device_fingerprint
import dev.ccpocket.app.resources.about_fingerprint_hint
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.e2e.PairingFingerprint
import kotlin.test.Test

/**
 * Pairing security phase 0, desktop Settings ▸ About: this App's and the connected computer's fingerprints
 * (the shared [PairingFingerprint]) appear as rows, with the line saying to compare them with `pairlet devices`;
 * an unpaired model shows neither.
 */
@OptIn(ExperimentalTestApi::class)
class AboutFingerprintUiTest {

    private val devicePub = ByteArray(65).also { it[0] = 4; it[64] = 7 }
    private val computerPub = ByteArray(65).also { it[0] = 4; it[64] = 9 }

    private class Paired(private val device: String, private val computer: String) : DesktopModel by SeedDesktopModel() {
        override val deviceFingerprint: String get() = device
        override val computerFingerprint: String get() = computer
    }

    @Test
    fun about_shows_both_fingerprints_when_paired() = runComposeUiTest {
        val device = PairingFingerprint.of(devicePub)
        val computer = PairingFingerprint.of(computerPub)
        setContent { PocketTheme { SettingsModal(Paired(device, computer), initialTab = SettingsTab.ABOUT, onDismiss = {}) } }
        onNode(hasText(str(Res.string.about_device_fingerprint))).performScrollTo().assertExists()
        onNode(hasText(str(Res.string.about_computer_fingerprint))).assertExists()
        onNode(hasText(device)).assertExists()
        onNode(hasText(computer)).assertExists()
        onNode(hasText(str(Res.string.about_fingerprint_hint))).assertExists()
    }

    @Test
    fun an_unpaired_model_shows_no_fingerprint_rows() = runComposeUiTest {
        setContent { PocketTheme { SettingsModal(SeedDesktopModel(), initialTab = SettingsTab.ABOUT, onDismiss = {}) } }
        onNode(hasText(str(Res.string.about_device_fingerprint))).assertDoesNotExist()
        onNode(hasText(str(Res.string.about_fingerprint_hint))).assertDoesNotExist()
    }
}
