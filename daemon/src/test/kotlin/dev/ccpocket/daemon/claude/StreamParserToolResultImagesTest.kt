package dev.ccpocket.daemon.claude

import dev.ccpocket.daemon.agent.AgentEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Issue #332: a tool_result's IMAGE blocks used to be flattened away by `toolResultText`, so a call
 * whose entire product was a picture (Playwright `browser_take_screenshot`; a `Read` of a PNG, which
 * is the shape that actually occurs in this machine's transcripts) reached the phone as a bare tool
 * name. These assert the parser now carries them, verbatim and un-capped — the ceilings belong to the
 * emitter, which is where the decode that enforces them is paid for.
 */
class StreamParserToolResultImagesTest {

    private fun results(line: String) = StreamParser.parse(line).filterIsInstance<AgentEvent.ToolResult>()

    @Test
    fun a_tool_result_carrying_text_and_an_image_yields_both() {
        val line = """
            {"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1",
            "content":[{"type":"text","text":"Took the screenshot"},
            {"type":"image","source":{"type":"base64","media_type":"image/png","data":"iVBORw0KGgo="}}]}]}}
        """.trimIndent().replace("\n", "")

        val r = results(line).single()
        assertEquals("t1", r.toolUseId)
        assertEquals("Took the screenshot", r.content, "the text must survive alongside the picture")
        assertEquals(1, r.images.size)
        assertEquals("image/png", r.images[0].mediaType)
        assertEquals("iVBORw0KGgo=", r.images[0].base64)
    }

    @Test
    fun an_image_only_result_still_yields_the_image_with_null_text() {
        // the measured shape of a `Read` of a PNG: content is exactly one image block, no text at all
        val line = """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t2","content":[{"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":"AAAA"}}]}]}}"""
        val r = results(line).single()
        assertEquals(null, r.content, "an image-only result has no text — that must not become an empty string")
        assertEquals(1, r.images.size)
        assertEquals("image/jpeg", r.images[0].mediaType)
    }

    @Test
    fun several_images_keep_their_wire_order() {
        // order is load-bearing: the tile strip and the viewer's pager both index into this list, so a
        // reordering here would open the wrong picture from the right tile
        val img = { d: String -> """{"type":"image","source":{"type":"base64","media_type":"image/png","data":"$d"}}""" }
        val line = """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t3",""" +
            """"content":[${img("one")},${img("two")},${img("three")}]}]}}"""
        assertEquals(listOf("one", "two", "three"), results(line).single().images.map { it.base64 })
    }

    @Test
    fun a_url_source_is_skipped_because_it_carries_no_bytes_to_render() {
        val line = """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t4","content":[{"type":"image","source":{"type":"url","url":"https://example.com/x.png"}}]}]}}"""
        assertEquals(emptyList(), results(line).single().images)
    }

    @Test
    fun a_blank_data_payload_is_skipped() {
        val line = """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t5","content":[{"type":"image","source":{"type":"base64","media_type":"image/png","data":""}}]}]}}"""
        assertEquals(emptyList(), results(line).single().images)
    }

    @Test
    fun a_missing_media_type_defaults_rather_than_dropping_the_picture() {
        val line = """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t6","content":[{"type":"image","source":{"type":"base64","data":"QUJD"}}]}]}}"""
        val r = results(line).single()
        assertEquals(1, r.images.size)
        assertEquals("image/png", r.images[0].mediaType)
    }

    @Test
    fun an_ordinary_text_result_still_carries_no_images() {
        // the overwhelmingly common case — this must stay allocation-cheap and, above all, unchanged
        val line = """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t7","content":"exit 0"}]}}"""
        val r = results(line).single()
        assertEquals("exit 0", r.content)
        assertEquals(emptyList(), r.images)
    }

    @Test
    fun a_subagent_scoped_result_keeps_its_parent_id_alongside_its_images() {
        // parentId is what stops an inner result from claiming a main-chain row of its own
        val line = """{"type":"user","parent_tool_use_id":"a1","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t8","content":[{"type":"image","source":{"type":"base64","media_type":"image/png","data":"ZZ"}}]}]}}"""
        val r = results(line).single()
        assertEquals("a1", r.parentId)
        assertEquals(1, r.images.size)
    }
}
