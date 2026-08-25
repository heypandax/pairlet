package dev.ccpocket.app.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import io.nayuki.qrcodegen.QrCode

/**
 * Issue #251 — a QR that cannot be drawn must not take the window with it.
 *
 * The qr-kit `QRCodeImage` composable was the prime suspect in the desktop "Connect a colleague"
 * crash, for two reasons that compound:
 *
 *  1. Its own guard catches `Exception` only. The old JVM generator was ZXing `QRCodeWriter.encode` →
 *     `BufferedImage` → `toComposeImageBitmap`. The final conversion initializes Skiko's native Bitmap
 *     class on first use; the v1.9.0 Windows package reported that exact path as
 *     `ExceptionInInitializerError`, even though the rest of the Compose window stayed alive.
 *  2. The desktop call sites passed no `onFailure`, so even the failures it DOES catch were swallowed
 *     into a blank square with nothing logged — the user got "it just broke" either way.
 *
 * And the payload itself can throw before the generator is even reached: `CollaboratorInvite.encode()`
 * serializes + base64s daemon-supplied fields, and any QR encoder rejects a blob beyond its capacity.
 *
 * So this builds a pure-Java QR MODULE MATRIX synchronously inside [runCatching] — which catches
 * `Throwable`, unlike the library — and paints the modules directly on the already-running Compose
 * canvas. There is no AWT image and no second Skiko bitmap initialization on this path. When it fails
 * the surrounding dialog is
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
    val outcome = remember(payloadKey) { qrMatrixOrFailure(payload) }
    outcome.fold(
        onSuccess = { QrMatrixCanvas(it, contentDescription, modifier) },
        onFailure = { QrFailurePlaceholder(qrFailureLabel(it), modifier) },
    )
}

/**
 * Encode to a small module matrix, containing everything. `runCatching` catches [Throwable] on purpose
 * (see the file header); a blank payload is rejected up front because generator errors are otherwise
 * opaque. Four quiet modules are stored around the encoded matrix so every renderer preserves the QR
 * spec's required white border.
 * Every failure is logged with [ERR_QR] so the code on screen has a matching line on disk.
 */
internal fun qrMatrixOrFailure(payload: () -> String): Result<QrMatrix> =
    runCatching {
        val text = payload()
        require(text.isNotBlank()) { "empty QR payload" }
        val qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
        val size = qr.size + QUIET_ZONE * 2
        QrMatrix(size, BooleanArray(size * size).also { dark ->
            for (y in 0 until qr.size) for (x in 0 until qr.size) {
                if (qr.getModule(x, y)) dark[(y + QUIET_ZONE) * size + x + QUIET_ZONE] = true
            }
        })
    }.onFailure { DesktopCrashGuard.note(ERR_QR, it) }

/** Immutable pixels in MODULE coordinates rather than device pixels. */
internal data class QrMatrix(val size: Int, private val dark: BooleanArray) {
    init { require(size > 0 && dark.size == size * size) }
    operator fun get(x: Int, y: Int): Boolean = dark[y * size + x]
}

/** Draw contiguous dark runs rather than one rectangle per output pixel. A typical invite is a few
 * hundred runs, so resize/repaint stays cheap and the QR remains vector-sharp at any dialog scale. */
@Composable
private fun QrMatrixCanvas(matrix: QrMatrix, description: String, modifier: Modifier) {
    Canvas(modifier.semantics { contentDescription = description }) {
        drawRect(Color.White)
        val cell = minOf(size.width, size.height) / matrix.size
        val left = (size.width - cell * matrix.size) / 2f
        val top = (size.height - cell * matrix.size) / 2f
        for (y in 0 until matrix.size) {
            var x = 0
            while (x < matrix.size) {
                while (x < matrix.size && !matrix[x, y]) x++
                val start = x
                while (x < matrix.size && matrix[x, y]) x++
                if (start < x) {
                    drawRect(
                        Color.Black,
                        topLeft = Offset(left + start * cell, top + y * cell),
                        size = Size((x - start) * cell, cell),
                    )
                }
            }
        }
    }
}

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

private const val QUIET_ZONE = 4
