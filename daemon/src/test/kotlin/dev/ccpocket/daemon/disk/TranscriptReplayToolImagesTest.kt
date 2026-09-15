package dev.ccpocket.daemon.disk

import dev.ccpocket.daemon.media.ImageThumbnail
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #332, replay half: the CLI persists a tool_result's images inline as base64 (verified against
 * this machine's own transcripts — 218 such blocks across 29 of 268 files, every one a base64 source),
 * so a reattach can show the same screenshot the live stream showed instead of a bare tool name.
 *
 * Since #380 live folding (2026-09-15) an ordinary replayed TOOL row also takes its OUTCOME from the
 * tool_result (`ok` from `is_error`), so a reopened transcript folds finished steps exactly like the live
 * stream, which now settles every card. A row whose result never arrived stays outcome-free.
 */
class TranscriptReplayToolImagesTest {

    private fun tmpFile(name: String) = Files.createTempDirectory("ccp-replay-img").resolve(name)

    /** A small, real, decodable PNG — [ImageThumbnail] must actually be able to read the fixture. */
    private fun pngBase64(w: Int = 240, h: Int = 160): String {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, (x * 255 / w shl 16) or (y * 255 / h shl 8) or 0x40)
        val bos = ByteArrayOutputStream()
        ImageIO.write(img, "png", bos)
        return Base64.getEncoder().encodeToString(bos.toByteArray())
    }

    private fun imageBlock(b64: String, media: String = "image/png") =
        """{"type":"image","source":{"type":"base64","media_type":"$media","data":"$b64"}}"""

    @Test
    fun a_tool_row_gets_the_images_its_result_returned() {
        val b64 = pngBase64()
        val f = tmpFile("shot.jsonl")
        f.writeText(
            listOf(
                """{"type":"user","message":{"role":"user","content":"take a screenshot"}}""",
                """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"s1","name":"browser_take_screenshot","input":{"filename":"page.png"}}]}}""",
                """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"s1","content":[{"type":"text","text":"Took the screenshot"},${imageBlock(b64)}]}]}}""",
                """{"type":"assistant","message":{"content":[{"type":"text","text":"here it is"}]}}""",
            ).joinToString("\n"),
        )

        val msgs = TranscriptReplay.read(f)
        val tool = msgs.single { it.role == ChatRole.TOOL }
        assertEquals("browser_take_screenshot", tool.tool)
        assertEquals(1, tool.images.size, "the result's picture must land on the tool row")
        assertFalse(tool.imagesTruncated)
        // thumbnailed, not passed through raw: the wire budget is the entire point
        assertTrue(tool.images[0].base64.length <= ImageThumbnail.MAX_BASE64_BYTES)
    }

    @Test
    fun an_image_bearing_result_stamps_the_outcome_too() {
        val f = tmpFile("nook.jsonl")
        f.writeText(
            listOf(
                """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"r1","name":"Read","input":{"file_path":"/tmp/a.png"}}]}}""",
                """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"r1","content":[${imageBlock(pngBase64())}]}]}}""",
            ).joinToString("\n"),
        )
        val tool = TranscriptReplay.read(f).single { it.role == ChatRole.TOOL }
        assertEquals(true, tool.ok, "the ordinary tool row takes its outcome from the result (#380)")
        assertEquals(null, tool.output)
        assertEquals(1, tool.images.size)
    }

    @Test
    fun an_ordinary_text_only_tool_row_gains_only_its_outcome() {
        val f = tmpFile("plain.jsonl")
        f.writeText(
            listOf(
                """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"b1","name":"Bash","input":{"command":"ls"}}]}}""",
                """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"b1","content":"a\nb"}]}}""",
            ).joinToString("\n"),
        )
        val tool = TranscriptReplay.read(f).single { it.role == ChatRole.TOOL }
        assertEquals("Bash", tool.tool)
        assertEquals(emptyList(), tool.images)
        assertFalse(tool.imagesTruncated)
        assertEquals(true, tool.ok, "a text-only result still settles the row (#380)")
    }

    @Test
    fun a_failed_result_reads_false_and_a_missing_result_stays_unknown() {
        val f = tmpFile("outcome.jsonl")
        f.writeText(
            listOf(
                """{"type":"user","message":{"role":"user","content":"run it"}}""",
                """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"b1","name":"Bash","input":{"command":"false"}}]}}""",
                """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"b1","is_error":true,"content":"exit 1"}]}}""",
                """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"b2","name":"Bash","input":{"command":"sleep 99"}}]}}""",
            ).joinToString("\n"),
        )
        val tools = TranscriptReplay.read(f).filter { it.role == ChatRole.TOOL }
        assertEquals(listOf(false, null), tools.map { it.ok }, "is_error → false; no tool_result yet → unknown, never folded")
        assertTrue(tools.all { it.images.isEmpty() && it.output == null })
    }

    @Test
    fun more_images_than_the_cap_are_shed_and_the_row_says_so() {
        val b64 = pngBase64()
        val blocks = (1..7).joinToString(",") { imageBlock(b64) }
        val f = tmpFile("many.jsonl")
        f.writeText(
            listOf(
                """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"m1","name":"Read","input":{"file_path":"/tmp/a.png"}}]}}""",
                """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"m1","content":[$blocks]}]}}""",
            ).joinToString("\n"),
        )
        val tool = TranscriptReplay.read(f).single { it.role == ChatRole.TOOL }
        assertEquals(ImageThumbnail.MAX_IMAGES, tool.images.size)
        assertTrue(tool.imagesTruncated, "shedding pictures must be ANNOUNCED, never silent")
    }

    @Test
    fun an_undecodable_payload_leaves_an_ordinary_row_rather_than_an_empty_claim() {
        val f = tmpFile("bad.jsonl")
        f.writeText(
            listOf(
                """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"x1","name":"Read","input":{"file_path":"/tmp/a.webp"}}]}}""",
                """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"x1","content":[${imageBlock("bm90IGFuIGltYWdl", "image/webp")}]}]}}""",
            ).joinToString("\n"),
        )
        val tool = TranscriptReplay.read(f).single { it.role == ChatRole.TOOL }
        assertEquals(emptyList(), tool.images)
        assertFalse(tool.imagesTruncated, "nothing was SHED — the picture was never renderable to begin with")
    }

    @Test
    fun a_subagent_card_is_untouched_by_the_image_patcher() {
        // the sub-agent row is keyed into a DIFFERENT map (it takes an outcome + a report); a stray
        // image patch here would be the bug where a Task card starts showing its inner tool's picture
        val f = tmpFile("sub.jsonl")
        f.writeText(
            listOf(
                """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"a1","name":"Task","input":{"subagent_type":"general-purpose","description":"look"}}]}}""",
                """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"a1","content":[{"type":"text","text":"done looking"}]}]}}""",
            ).joinToString("\n"),
        )
        val tool = TranscriptReplay.read(f).single { it.role == ChatRole.TOOL }
        assertEquals(emptyList(), tool.images)
        assertEquals(true, tool.ok, "the sub-agent card still takes its outcome")
        assertEquals("done looking", tool.output)
    }

    @Test
    fun the_replay_budget_sheds_tool_row_images_the_same_way_it_sheds_a_prompt_attachment() {
        // ReplayBudget is role-agnostic by construction; this pins that a TOOL row is really covered,
        // because #332 is the first thing that puts pictures on one
        val heavy = HistoryMessage(
            ChatRole.TOOL, "browser_take_screenshot", tool = "browser_take_screenshot",
            images = listOf(ImageData("image/jpeg", "x".repeat(300_000))),
        )
        val fitted = ReplayBudget.fit(listOf(heavy), maxBytes = 50_000)
        assertEquals(1, fitted.size)
        assertEquals(emptyList(), fitted[0].images)
        assertTrue(fitted[0].imagesTruncated, "the phone must be told the screenshot was shed")
    }

    @Test
    fun the_budget_counts_a_tool_rows_images() {
        val row = HistoryMessage(
            ChatRole.TOOL, "ab", tool = "Read",
            images = listOf(ImageData("image/png", "y".repeat(1000))),
        )
        assertEquals(1002L, ReplayBudget.payloadSize(row), "2 bytes of text + 1000 of base64")
    }

    @Test
    fun a_per_image_oversize_tool_attachment_is_capped_like_a_user_one() {
        val row = HistoryMessage(
            ChatRole.TOOL, "shot", tool = "Read",
            images = listOf(ImageData("image/png", "z".repeat((ReplayBudget.MAX_IMAGE_BASE64_BYTES + 1).toInt()))),
        )
        val capped = ReplayBudget.capImages(row)
        assertEquals(emptyList(), capped.images)
        assertTrue(capped.imagesTruncated)
    }
}
