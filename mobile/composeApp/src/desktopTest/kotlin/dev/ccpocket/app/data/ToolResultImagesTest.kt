package dev.ccpocket.app.data

import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.ToolEvent
import dev.ccpocket.protocol.ToolPhase
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Issue #332, client half: the pictures a tool RESULT returned must land on the tool card that is
 * already on screen.
 *
 * The failure mode this pins is the one that would be invisible in a screenshot and obvious in use:
 * an ordinary tool has never had a RESULT phase, so the RESULT that now arrives for a screenshot must
 * be recognized as an UPDATE to the existing START card. If it fell through to the append branch the
 * transcript would show the same tool call twice — once running, once with a picture.
 */
@OptIn(ExperimentalEncodingApi::class)
class ToolResultImagesTest {

    private fun img(tag: String) = ImageData("image/jpeg", Base64.Default.encode(tag.encodeToByteArray()))

    private fun start(id: String, tool: String = "browser_take_screenshot") =
        ToolEvent("c1", 1, ToolPhase.START, tool, "page.png", toolUseId = id)

    private fun result(id: String, tool: String = "browser_take_screenshot", images: List<ImageData> = emptyList()) =
        ToolEvent("c1", 2, ToolPhase.RESULT, tool, ok = true, toolUseId = id, output = "Took it", images = images)

    @Test
    fun a_RESULT_with_images_updates_the_START_card_instead_of_adding_a_row() {
        val t = ChatTranscript()
        t.onToolEvent(start("s1"))
        assertEquals(1, t.messages.size)

        t.onToolEvent(result("s1", images = listOf(img("shot"))))

        assertEquals(1, t.messages.size, "the screenshot must not produce a SECOND row for the same call")
        val card = assertIs<ChatItem.Tool>(t.messages.single())
        assertEquals("browser_take_screenshot", card.tool)
        assertEquals("page.png", card.preview, "the START card's preview survives the update")
        assertEquals(true, card.ok)
        assertEquals(1, card.images.size)
        assertEquals("shot", card.images[0].decodeToString())
    }

    @Test
    fun several_images_arrive_in_order() {
        val t = ChatTranscript()
        t.onToolEvent(start("s1"))
        t.onToolEvent(result("s1", images = listOf(img("one"), img("two"), img("three"))))
        val card = assertIs<ChatItem.Tool>(t.messages.single())
        assertEquals(listOf("one", "two", "three"), card.images.map { it.decodeToString() })
    }

    @Test
    fun a_RESULT_without_images_never_erases_pictures_the_card_already_had() {
        // a sub-agent settling and an image-bearing result are different frames; only one speaks here
        val t = ChatTranscript()
        t.onToolEvent(start("s1"))
        t.onToolEvent(result("s1", images = listOf(img("shot"))))
        t.onToolEvent(result("s1", images = emptyList()))
        val card = assertIs<ChatItem.Tool>(t.messages.single())
        assertEquals(1, card.images.size)
    }

    @Test
    fun an_ordinary_START_only_tool_card_carries_no_images() {
        val t = ChatTranscript()
        t.onToolEvent(ToolEvent("c1", 1, ToolPhase.START, "Bash", "ls", toolUseId = "b1"))
        val card = assertIs<ChatItem.Tool>(t.messages.single())
        assertEquals(emptyList(), card.images)
        assertTrue(!card.imagesTruncated)
        assertEquals(null, card.ok, "a START-only card still claims no outcome")
    }

    @Test
    fun a_RESULT_for_a_card_that_is_not_on_screen_adds_nothing() {
        // opened mid-run: the reattach history replay carries the picture instead, so a stray RESULT
        // must not invent a row with no preview
        val t = ChatTranscript()
        t.onToolEvent(result("ghost", images = listOf(img("shot"))))
        assertEquals(0, t.messages.size)
    }

    @Test
    fun a_replayed_tool_row_renders_its_images() {
        val row = HistoryMessage(
            ChatRole.TOOL, "{\"file_path\":\"/tmp/a.png\"}", tool = "Read",
            images = listOf(img("replayed")),
        )
        val card = assertIs<ChatItem.Tool>(historyItem(row))
        assertEquals("Read", card.tool)
        assertEquals(1, card.images.size)
        assertEquals("replayed", card.images[0].decodeToString())
        assertTrue(!card.imagesTruncated)
    }

    @Test
    fun a_replayed_tool_row_reports_a_shed_screenshot_even_with_no_tiles() {
        // the budget dropped the picture; the row must SAY so rather than look like a text-only call
        val row = HistoryMessage(ChatRole.TOOL, "shot", tool = "Read", images = emptyList(), imagesTruncated = true)
        val card = assertIs<ChatItem.Tool>(historyItem(row))
        assertEquals(emptyList(), card.images)
        assertTrue(card.imagesTruncated)
    }

    @Test
    fun an_undecodable_base64_blob_is_dropped_rather_than_passed_on_as_broken_bytes() {
        val row = HistoryMessage(
            ChatRole.TOOL, "shot", tool = "Read",
            images = listOf(ImageData("image/png", "!!! not base64 !!!")),
        )
        val card = assertIs<ChatItem.Tool>(historyItem(row))
        assertEquals(emptyList(), card.images)
    }

    @Test
    fun an_ordinary_replayed_tool_row_is_unchanged() {
        val row = HistoryMessage(ChatRole.TOOL, "ls", tool = "Bash")
        val card = assertIs<ChatItem.Tool>(historyItem(row))
        assertEquals("Bash", card.tool)
        assertEquals("ls", card.preview)
        assertEquals(emptyList(), card.images)
        assertEquals(null, card.ok)
    }
}
