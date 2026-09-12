package dev.ccpocket.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.ui.chat.ToolTurnBand
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shared thumbnail strip a tool band shows for the pictures its RESULT returned (issue #332) —
 * this is the phone's primary surface for the feature, and the desktop row embeds the same composable.
 */
@OptIn(ExperimentalTestApi::class)
class ToolResultImagesUiTest {

    private fun png(w: Int = 60, h: Int = 40): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, (x shl 16) or (y shl 8) or 0x10)
        val bos = ByteArrayOutputStream()
        ImageIO.write(img, "png", bos)
        return bos.toByteArray()
    }

    @Test
    fun tapping_a_tile_opens_that_tiles_index_not_the_first() {
        // the index is what the viewer's pager starts on — an off-by-one here means tapping the third
        // screenshot opens the first, which looks like the wrong picture was attached
        var opened = -1
        runComposeUiTest {
            setContent {
                PocketTheme { ToolResultImages(listOf(png(), png(90, 40), png(40, 90))) { opened = it } }
            }
            onNodeWithTag("${TOOL_IMAGE_TILE_TAG}2").performClick()
        }
        assertEquals(2, opened)
    }

    @Test
    fun nothing_renders_for_a_tool_that_returned_no_pictures() = runComposeUiTest {
        // the overwhelmingly common case: the band must look exactly as it always has
        setContent { PocketTheme { ToolResultImages(emptyList()) {} } }
        onAllNodes(hasTestTag(TOOL_IMAGES_TAG)).assertCountEquals(0)
    }

    @Test
    fun a_shed_screenshot_still_renders_the_strip_so_the_loss_is_stated() = runComposeUiTest {
        setContent { PocketTheme { ToolResultImages(emptyList(), truncated = true) {} } }
        onNodeWithTag(TOOL_IMAGES_TAG).assertIsDisplayed()
        onAllNodes(hasTestTag("${TOOL_IMAGE_TILE_TAG}0")).assertCountEquals(0)
    }

    @Test
    fun the_band_renders_its_footer_slot_under_the_payload() = runComposeUiTest {
        // ToolTurnBand's footer is how the phone's tool card carries the strip; a band with no footer
        // must be byte-for-byte the band it always was
        setContent {
            PocketTheme {
                ToolTurnBand(
                    tool = "Read", preview = "~/a.png", status = true,
                    footerSlot = { ToolResultImages(listOf(png())) {} },
                )
            }
        }
        onNodeWithTag(TOOL_IMAGES_TAG).assertIsDisplayed()
        onNodeWithTag("${TOOL_IMAGE_TILE_TAG}0").assertIsDisplayed()
    }

    @Test
    fun a_band_without_a_footer_slot_shows_no_strip() = runComposeUiTest {
        setContent { PocketTheme { ToolTurnBand(tool = "Bash", preview = "ls", status = true) } }
        onAllNodes(hasTestTag(TOOL_IMAGES_TAG)).assertCountEquals(0)
    }
}
