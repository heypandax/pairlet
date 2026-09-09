package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipe
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.FileContent
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Enter through FileTabBody so these gestures test the actual file-viewer route. A gradient image
 * lets pixel assertions prove that panning reveals another area, not just that a state flag changed. */
@OptIn(ExperimentalTestApi::class)
class FileImagePreviewTest {
    private val content: FileContent = run {
        val image = BufferedImage(800, 800, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 800) for (x in 0 until 800) image.setRGB(x, y, ((x * 255 / 799) shl 16) or ((y * 255 / 799) shl 8) or 32)
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        FileContent("/project", "session", "/project/gradient.png", base64 = Base64.getEncoder().encodeToString(bytes), mediaType = "image/png")
    }

    private fun ComposeUiTest.showFile() {
        setContent {
            PocketTheme {
                Box(Modifier.size(420.dp, 400.dp)) { FileTabBody(content, "png", wrap = false) }
            }
        }
    }

    private fun ComposeUiTest.centrePixel(): Color {
        val image = onNodeWithTag("file-image-viewport").captureToImage().toPixelMap()
        return image[image.width / 2, image.height / 2]
    }

    @Test
    fun doubleTapThenTouchDragShowsAnotherAreaAndFitRestoresIt() = runComposeUiTest {
        showFile()
        val original = centrePixel()
        onNodeWithTag("file-image-viewport").performTouchInput { doubleClick(center) }
        onNodeWithTag("file-image-zoom").assertTextEquals("2.5×")
        val before = centrePixel()
        onNodeWithTag("file-image-viewport").performTouchInput {
            swipe(center, center + Offset(65f, 45f), durationMillis = 250)
        }
        val after = centrePixel()
        assertTrue(after.red < before.red - 0.03f, "drag right must reveal pixels to the left")
        assertTrue(after.green < before.green - 0.02f, "drag down must reveal pixels above")
        onNodeWithTag("file-image-fit").performClick()
        onNodeWithTag("file-image-zoom").assertTextEquals("1.0×")
        val reset = centrePixel()
        assertTrue(kotlin.math.abs(reset.red - original.red) < 0.01f)
        assertTrue(kotlin.math.abs(reset.green - original.green) < 0.01f)
    }

    @Test
    fun mouseWheelButtonsAndDragWorkInsideTheFileViewer() = runComposeUiTest {
        showFile()
        onNodeWithTag("file-image-zoom-in").performClick()
        onNodeWithTag("file-image-zoom").assertTextEquals("1.5×")
        onNodeWithTag("file-image-zoom-out").performClick()
        onNodeWithTag("file-image-zoom").assertTextEquals("1.0×")
        onNodeWithTag("file-image-viewport").performMouseInput { moveTo(center); scroll(-6f) }
        assertNotEquals("1.0×", onNodeWithTag("file-image-viewport").fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        val before = centrePixel()
        onNodeWithTag("file-image-viewport").performMouseInput {
            moveTo(center); press()
            moveBy(Offset(30f, 0f)); moveBy(Offset(40f, 0f)); release()
        }
        assertTrue(centrePixel().red < before.red - 0.03f, "mouse drag must pan the magnified picture")
    }

    @Test
    fun pinchZoomsAndSwitchingFilesResetsTheView() = runComposeUiTest {
        var selected by mutableStateOf(content)
        setContent {
            PocketTheme {
                Box(Modifier.size(420.dp, 400.dp)) { FileTabBody(selected, "png", wrap = false) }
            }
        }
        onNodeWithTag("file-image-viewport").performTouchInput {
            pinch(center - Offset(35f, 0f), center - Offset(110f, 0f), center + Offset(35f, 0f), center + Offset(110f, 0f))
        }
        assertNotEquals("1.0×", onNodeWithTag("file-image-viewport").fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        runOnIdle { selected = content.copy(path = "/project/another.png") }
        onNodeWithTag("file-image-zoom").assertTextEquals("1.0×")
        // Same-sized replacement may not trigger another layout-size callback: it must still pan.
        onNodeWithTag("file-image-viewport").performTouchInput { doubleClick(center) }
        val before = centrePixel()
        onNodeWithTag("file-image-viewport").performTouchInput { swipe(center, center + Offset(65f, 0f)) }
        assertTrue(centrePixel().red < before.red - 0.03f)
    }
}
