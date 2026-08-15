package dev.ccpocket.app.desktop

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
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
    fun ordinaryPayloadStillProducesABitmap() {
        val out = qrBitmapOrFailure { "ccpocket://collab#" + "a".repeat(120) }
        val bmp = out.getOrElse { fail("a normal invite must still render: $it") }
        assertTrue(bmp.width > 0 && bmp.height > 0, "generated QR must have pixels")
    }

    @Test
    fun payloadThatThrowsIsContained() {
        // stands in for CollaboratorInvite.encode() blowing up on daemon-supplied fields
        val out = qrBitmapOrFailure { throw IllegalStateException("bad invite") }
        assertTrue(out.isFailure, "an exploding payload must not reach the composition")
        assertEquals("CCP-QR-01 · IllegalStateException", qrFailureLabel(out.exceptionOrNull()!!))
    }

    @Test
    fun errorsNotJustExceptionsAreContained() {
        // the qr-kit guard catches Exception only; a packaged image missing ZXing throws an Error, and
        // THAT is the class of failure that used to escape. runCatching must cover it.
        val out = qrBitmapOrFailure { throw NoClassDefFoundError("com/google/zxing/qrcode/QRCodeWriter") }
        assertTrue(out.isFailure, "an Error on the QR path must be contained too")
        assertEquals("CCP-QR-01 · NoClassDefFoundError", qrFailureLabel(out.exceptionOrNull()!!))
    }

    @Test
    fun blankPayloadFailsClosedRatherThanDrawingNothing() {
        assertTrue(qrBitmapOrFailure { "" }.isFailure)
        assertTrue(qrBitmapOrFailure { "   " }.isFailure)
    }

    @Test
    fun overlongPayloadIsAFailureNotACrash() {
        // ZXing refuses payloads past the QR capacity ceiling — the real-world shape of "the ticket got
        // big". It must land in the Result, not on the window.
        val out = qrBitmapOrFailure { "x".repeat(20_000) }
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
