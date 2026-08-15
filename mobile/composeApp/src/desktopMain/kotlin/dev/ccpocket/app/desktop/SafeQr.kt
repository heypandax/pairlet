package dev.ccpocket.app.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import qrgenerator.generateCode

/**
 * Issue #251 — a QR that cannot be drawn must not take the window with it.
 *
 * The qr-kit `QRCodeImage` composable was the prime suspect in the desktop "Connect a colleague"
 * crash, for two reasons that compound:
 *
 *  1. Its own guard catches `Exception` only. The JVM generator is ZXing `QRCodeWriter.encode` →
 *     `BufferedImage` → `toComposeImageBitmap`, so a `NoClassDefFoundError` (ZXing absent from a
 *     packaged image), an `OutOfMemoryError`, or an AWT `Error` walks straight past it.
 *  2. The desktop call sites passed no `onFailure`, so even the failures it DOES catch were swallowed
 *     into a blank square with nothing logged — the user got "it just broke" either way.
 *
 * And the payload itself can throw before the generator is even reached: `CollaboratorInvite.encode()`
 * serializes + base64s daemon-supplied fields, and an over-long blob makes ZXing refuse outright.
 *
 * So this does the generation OURSELVES, synchronously, inside [runCatching] — which catches
 * `Throwable`, unlike the library — and renders the resulting bitmap with a plain [Image]. Nothing on
 * this path can reach the composition as an exception. When it fails the surrounding dialog is
 * unharmed: the QR square becomes a placeholder carrying [ERR_QR] plus the exception's class name, and
 * the short code / fingerprint words / buttons beside it still work. A colleague can be connected by
 * reading the short code aloud, which is precisely the fallback the QR exists to shortcut.
 */
const val ERR_QR = "CCP-QR-01"

/**
 * A QR square that renders a placeholder instead of throwing.
 *
 * @param payloadKey re-generates when this changes — pass the identity the payload is derived from
 *   (the ticket, the invite), NOT the payload itself: computing it is part of what may throw.
 * @param payload produces the string to encode; may throw, and is contained.
 */
@Composable
fun SafeQrImage(
    payloadKey: Any?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    payload: () -> String,
) {
    val outcome = remember(payloadKey) { qrBitmapOrFailure(payload) }
    outcome.fold(
        onSuccess = {
            Image(bitmap = it, contentDescription = contentDescription, modifier = modifier, contentScale = ContentScale.Fit)
        },
        onFailure = { QrFailurePlaceholder(qrFailureLabel(it), modifier) },
    )
}

/**
 * Encode + rasterize, containing everything. `runCatching` catches [Throwable] on purpose (see the
 * file header); a blank payload is rejected up front because ZXing's own error for it is opaque.
 * Every failure is logged with [ERR_QR] so the code on screen has a matching line on disk.
 */
internal fun qrBitmapOrFailure(payload: () -> String): Result<ImageBitmap> =
    runCatching {
        val text = payload()
        require(text.isNotBlank()) { "empty QR payload" }
        // platform type: the generator is Java-facing, so a null here is possible on paper — treat it
        // as a failure rather than letting an NPE surface somewhere less containable.
        checkNotNull(generateCode(text)) { "QR generator returned no bitmap" }
    }.onFailure { DesktopCrashGuard.note(ERR_QR, it) }

/** `CCP-QR-01 · WriterException` — short enough to survive being retyped out of a screenshot. */
internal fun qrFailureLabel(t: Throwable): String = "$ERR_QR · ${t::class.simpleName ?: "Throwable"}"

/**
 * Sits inside the caller's white QR plate, so its colors are fixed rather than themed — a theme token
 * would be invisible against white in one of the two modes.
 */
@Composable
private fun QrFailurePlaceholder(label: String, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "QR unavailable", color = Color(0xFF3A3A3A), fontFamily = Dk.ui, fontSize = 12.sp,
                fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
            )
            Text(
                "use the code below", color = Color(0xFF6B6B6B), fontFamily = Dk.ui, fontSize = 11.sp,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                label, color = Color(0xFF8A8A8A), fontFamily = Dk.mono, fontSize = 10.sp,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
