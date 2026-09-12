package dev.ccpocket.app.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.ui.TOOL_IMAGES_TAG
import dev.ccpocket.app.ui.TOOL_IMAGE_TILE_TAG
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Desktop parity for issue #332: the pictures a tool RESULT returned are reachable from the tool row.
 *
 * The row is collapsed by default, so the load-bearing part is that images make it EXPANDABLE at all —
 * a result whose entire product was a screenshot has no long command and no output text, so before
 * this the row had nothing to open and the picture was simply unreachable on desktop.
 */
@OptIn(ExperimentalTestApi::class)
class ToolRowImagesTest {

    /** Real PNG bytes: the tile decodes them for its aspect ratio, so a fake payload would render blank. */
    private fun png(w: Int = 80, h: Int = 50): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, (x * 3 shl 16) or (y * 5 shl 8) or 0x20)
        val bos = ByteArrayOutputStream()
        ImageIO.write(img, "png", bos)
        return bos.toByteArray()
    }

    @Test
    fun a_screenshot_result_makes_an_otherwise_bare_row_expandable_and_shows_its_tiles() = runComposeUiTest {
        setContent {
            PocketTheme {
                // no output text and a short command: without the images this row would not expand
                ToolRow("Read", "~/a.png", ToolStatus.OK, output = null, images = listOf(png(), png(120, 60)))
            }
        }
        onAllNodes(hasTestTag(TOOL_IMAGES_TAG)).assertCountEquals(0) // collapsed
        onNodeWithTag(TOOL_ROW_TAG).performClick()
        onNodeWithTag(TOOL_IMAGES_TAG).assertIsDisplayed()
        onNodeWithTag("${TOOL_IMAGE_TILE_TAG}0").assertIsDisplayed()
        onNodeWithTag("${TOOL_IMAGE_TILE_TAG}1").assertIsDisplayed()
    }

    @Test
    fun a_text_only_tool_row_shows_no_image_strip() = runComposeUiTest {
        setContent { PocketTheme { ToolRow("Bash", "ls", ToolStatus.OK, output = "a.txt") } }
        onNodeWithTag(TOOL_ROW_TAG).performClick()
        onAllNodes(hasTestTag(TOOL_IMAGES_TAG)).assertCountEquals(0)
    }

    @Test
    fun a_row_whose_screenshot_the_budget_shed_still_says_so() = runComposeUiTest {
        setContent {
            PocketTheme { ToolRow("Read", "~/a.png", ToolStatus.OK, images = emptyList(), imagesTruncated = true) }
        }
        onNodeWithTag(TOOL_ROW_TAG).performClick()
        // the strip renders with NO tiles — the truncation note is the whole content
        onNodeWithTag(TOOL_IMAGES_TAG).assertIsDisplayed()
        onAllNodes(hasTestTag("${TOOL_IMAGE_TILE_TAG}0")).assertCountEquals(0)
    }
}
