package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.about_computer_fingerprint
import dev.ccpocket.app.resources.about_device_fingerprint
import dev.ccpocket.app.resources.about_fingerprint_copy
import dev.ccpocket.app.resources.about_fingerprint_hint
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.util.B64Url
import dev.ccpocket.protocol.e2e.PairingFingerprint
import org.jetbrains.compose.resources.stringResource

// Pairing security phase 0: the App shows THIS device's key fingerprint and the connected computer's, so the
// owner can compare them with `pairlet devices` on the computer. Pairing currently relies on the relay; a
// mismatch here is how a key substituted during pairing becomes visible. Display only.

/** [PairingFingerprint] of a stored base64url public key (a binding's `daemonPub`), or null if unreadable. */
fun pairingFingerprintOf(pubB64: String): String? =
    runCatching { B64Url.decode(pubB64) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let(PairingFingerprint::of)

/**
 * Two rows for the existing ABOUT group (each preceded by its hairline divider, like the rows already there):
 * "This device's fingerprint" and "Computer fingerprint". Tapping a row copies its value.
 */
@Composable
fun PairingFingerprintRows(deviceFingerprint: String?, computerFingerprint: String?) {
    val clipboard = LocalClipboardManager.current
    val copy = stringResource(Res.string.about_fingerprint_copy)
    listOf(
        stringResource(Res.string.about_device_fingerprint) to deviceFingerprint,
        stringResource(Res.string.about_computer_fingerprint) to computerFingerprint,
    ).forEach { (label, value) ->
        if (value == null) return@forEach
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        AboutRow(label, value, onClick = { clipboard.setText(AnnotatedString(value)) }, onClickLabel = copy)
    }
}

/** The one line under the ABOUT group that says what the fingerprints are for. */
@Composable
fun PairingFingerprintHint() {
    Text(
        stringResource(Res.string.about_fingerprint_hint),
        color = Tok.muted, fontSize = 12.sp, lineHeight = 17.sp,
        modifier = Modifier.padding(top = 10.dp, start = 2.dp),
    )
}
