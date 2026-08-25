package dev.ccpocket.app.desktop

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import dev.ccpocket.app.pairing.encode
import dev.ccpocket.protocol.CollaboratorInvite
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Issue #251 — Settings ▸ Collaborators ▸ "Connect a colleague" showed a contentless native "Unknown
 * error" dialog and left a zombie process. The QR square was the prime suspect: its generator's own
 * guard catches `Exception` only, and the desktop call site passed no `onFailure`, so anything the
 * guard missed escaped into the composition.
 *
 * These pin the contract that replaced it: NOTHING on the QR path throws, whatever the payload does.
 * A ticket that cannot be drawn degrades to a placeholder carrying a reportable code, next to a short
 * code that still connects the colleague by hand.
 */
class SafeQrTest {

    @Test
    fun ordinaryPayloadStillProducesAMatrix() {
        val out = qrMatrixOrFailure { "ccpocket://collab#" + "a".repeat(120) }
        val matrix = out.getOrElse { fail("a normal invite must still render: $it") }
        assertTrue(matrix.size > 20, "generated QR must have modules plus a quiet zone")
        assertTrue((0 until matrix.size).any { x -> (0 until matrix.size).any { y -> matrix[x, y] } })
    }

    @Test
    fun realCollaboratorInviteCodecAndGeneratorWorkTogether() {
        val invite = CollaboratorInvite(
            relay = "wss://pocket.example", accountId = "acct", daemonPub = "pub",
            ticket = "ticket", ownerLabel = "Panda Windows",
        )
        val payload = invite.encode()
        val matrix = qrMatrixOrFailure { payload }.getOrElse { fail("real invite must encode: $it") }

        // Decode a nearest-neighbour raster of OUR stored matrix with an independent implementation.
        // This proves the quiet-zone/index arithmetic did not merely produce a plausible checkerboard.
        val scale = 4
        val width = matrix.size * scale
        val pixels = IntArray(width * width)
        for (y in 0 until width) for (x in 0 until width) {
            pixels[y * width + x] = if (matrix[x / scale, y / scale]) 0x000000 else 0xFFFFFF
        }
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, width, pixels))))
        assertEquals(payload, decoded.text)
    }

    @Test
    fun payloadThatThrowsIsContained() {
        // stands in for CollaboratorInvite.encode() blowing up on daemon-supplied fields
        val out = qrMatrixOrFailure { throw IllegalStateException("bad invite") }
        assertTrue(out.isFailure, "an exploding payload must not reach the composition")
        assertEquals("CCP-QR-01 · IllegalStateException", qrFailureLabel(out.exceptionOrNull()!!))
    }

    @Test
    fun errorsNotJustExceptionsAreContained() {
        // the qr-kit guard catches Exception only; a packaged image missing ZXing throws an Error, and
        // THAT is the class of failure that used to escape. runCatching must cover it.
        val out = qrMatrixOrFailure { throw NoClassDefFoundError("com/google/zxing/qrcode/QRCodeWriter") }
        assertTrue(out.isFailure, "an Error on the QR path must be contained too")
        assertEquals("CCP-QR-01 · NoClassDefFoundError", qrFailureLabel(out.exceptionOrNull()!!))
    }

    @Test
    fun blankPayloadFailsClosedRatherThanDrawingNothing() {
        assertTrue(qrMatrixOrFailure { "" }.isFailure)
        assertTrue(qrMatrixOrFailure { "   " }.isFailure)
    }

    @Test
    fun overlongPayloadIsAFailureNotACrash() {
        // Every QR implementation refuses payloads past the format's capacity ceiling — the real-world
        // shape of "the ticket got big". It must land in the Result, not on the window.
        val out = qrMatrixOrFailure { "x".repeat(20_000) }
        assertTrue(out.isFailure, "an over-capacity payload must fail as a value")
    }

    @Test
    fun failureLabelNamesAThrowableEvenWhenAnonymous() {
        val anonymous = object : RuntimeException("nameless") {}
        assertTrue(qrFailureLabel(anonymous).startsWith("CCP-QR-01 · "))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun failedQrRendersAPlaceholderInsteadOfPropagating() = runComposeUiTest {
        setContent {
            SafeQrImage(payloadKey = "t1", contentDescription = "collaborator QR", modifier = Modifier) {
                throw IllegalStateException("bad invite")
            }
        }
        // the point of the placeholder: it names the fallback the user should use, and carries a code
        onNodeWithText("use the code below").assertIsDisplayed()
        onNodeWithText("CCP-QR-01 · IllegalStateException").assertIsDisplayed()
    }
}
