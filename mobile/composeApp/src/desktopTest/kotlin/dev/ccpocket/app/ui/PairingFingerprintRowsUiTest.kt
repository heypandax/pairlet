package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.about_computer_fingerprint
import dev.ccpocket.app.resources.about_device_fingerprint
import dev.ccpocket.app.resources.about_fingerprint_hint
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.util.B64Url
import dev.ccpocket.protocol.e2e.PairingFingerprint
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pairing security phase 0, phone/desktop "Settings → Support & about → About": the two fingerprint rows show
 * exactly what the shared [PairingFingerprint] computes, so they can be compared with `pairlet devices`.
 */
@OptIn(ExperimentalTestApi::class)
class PairingFingerprintRowsUiTest {

    /** The P-256 generator point — a fixed public key whose fingerprint the protocol test pins. */
    private val generator = hex(
        "04" +
            "6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296" +
            "4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5",
    )

    @Test
    fun a_stored_base64_key_fingerprints_like_the_daemon_does() {
        assertEquals("7132-8382-2950-2dfe-119b", pairingFingerprintOf(B64Url.encode(generator)))
        assertEquals(PairingFingerprint.of(generator), pairingFingerprintOf(B64Url.encode(generator)))
        assertNull(pairingFingerprintOf(""), "an unreadable key shows no row rather than a wrong value")
    }

    @Test
    fun both_rows_show_the_shared_fingerprint_and_a_tap_copies_it() = runComposeUiTest {
        val device = PairingFingerprint.of(generator)
        val computer = pairingFingerprintOf(B64Url.encode(ByteArray(65)))!!
        val copied = mutableListOf<String>()
        val clipboard = object : ClipboardManager {
            override fun getText(): AnnotatedString? = copied.lastOrNull()?.let(::AnnotatedString)
            override fun setText(annotatedString: AnnotatedString) { copied += annotatedString.text }
        }
        setContent {
            PocketTheme {
                CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                    Column {
                        PairingFingerprintRows(device, computer)
                        PairingFingerprintHint()
                    }
                }
            }
        }
        val deviceLabel = runBlocking { getString(Res.string.about_device_fingerprint) }
        val computerLabel = runBlocking { getString(Res.string.about_computer_fingerprint) }
        onNode(hasText(deviceLabel)).assertExists()
        onNode(hasText(computerLabel)).assertExists()
        onNodeWithText("7132-8382-2950-2dfe-119b").assertExists()
        onNodeWithText("aa00-c619-a889-e9d9-e89f").assertExists()
        onNodeWithText(runBlocking { getString(Res.string.about_fingerprint_hint) }).assertExists()

        onNodeWithText(computer).performClick()
        assertEquals(listOf(computer), copied)
    }

    @Test
    fun an_unknown_value_has_no_row() = runComposeUiTest {
        setContent { PocketTheme { Column { PairingFingerprintRows(deviceFingerprint = "1111-2222-3333-4444-5555", computerFingerprint = null) } } }
        onNode(hasText(runBlocking { getString(Res.string.about_device_fingerprint) })).assertExists()
        onNode(hasText(runBlocking { getString(Res.string.about_computer_fingerprint) })).assertDoesNotExist()
    }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
