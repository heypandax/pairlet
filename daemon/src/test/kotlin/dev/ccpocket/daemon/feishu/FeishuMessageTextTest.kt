package dev.ccpocket.daemon.feishu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The quoted-message text extractor (requirement 2). Best-effort by contract: text/post yield the words;
 *  other kinds yield a bracketed placeholder; a malformed body never throws. */
class FeishuMessageTextTest {
    @Test
    fun text_message_yields_its_text() {
        assertEquals("修一下登录崩溃", FeishuMessageText.plainText("text", """{"text":"修一下登录崩溃"}"""))
    }

    @Test
    fun post_message_harvests_title_and_all_text_runs_in_order() {
        val content = """
            {"title":"标题","content":[
              [{"tag":"text","text":"第一段"},{"tag":"a","text":"链接","href":"https://x"}],
              [{"tag":"text","text":"第二段"}]
            ]}
        """.trimIndent()
        val out = FeishuMessageText.plainText("post", content)
        assertTrue("标题" in out && "第一段" in out && "链接" in out && "第二段" in out, "got: $out")
    }

    @Test
    fun post_message_handles_the_locale_wrapped_shape() {
        val content = """{"zh_cn":{"title":"T","content":[[{"tag":"text","text":"正文"}]]}}"""
        val out = FeishuMessageText.plainText("post", content)
        assertTrue("T" in out && "正文" in out, "got: $out")
    }

    @Test
    fun non_text_kinds_become_placeholders() {
        assertEquals("[图片]", FeishuMessageText.plainText("image", """{"image_key":"img_x"}"""))
        assertEquals("[文件]", FeishuMessageText.plainText("file", """{"file_key":"f"}"""))
        assertEquals("[视频]", FeishuMessageText.plainText("media", "{}"))
        assertEquals("[audit_log]", FeishuMessageText.plainText("audit_log", "{}"))
    }

    /** inboundText is the INSTRUCTION reader — the regression that made a bullet-list @mention vanish. */
    @Test
    fun inbound_reads_text_and_post_alike() {
        assertEquals("修一下登录崩溃", FeishuMessageText.inboundText("text", """{"text":"修一下登录崩溃"}"""))
        val post = """{"title":"","content":[
              [{"tag":"at","user_id":"@_user_1","user_name":"bot"},{"tag":"text","text":" 我已经："}],
              [{"tag":"text","text":"取消排队的旧 job"}]
            ]}"""
        val out = FeishuMessageText.inboundText("post", post)
        assertTrue(out != null && "我已经" in out && "取消排队的旧 job" in out, "got: $out")
    }

    /** …and unlike plainText it must NOT invent a placeholder: a turn driven by the literal "[图片]" is
     *  worse than no turn, so a kind with no instruction in it reads as null and the message is ignored. */
    @Test
    fun inbound_has_no_instruction_in_non_text_kinds_or_a_bad_body() {
        assertEquals(null, FeishuMessageText.inboundText("image", """{"image_key":"img_x"}"""))
        assertEquals(null, FeishuMessageText.inboundText("interactive", "{}"))
        assertEquals(null, FeishuMessageText.inboundText("text", "not json{"))
        assertEquals(null, FeishuMessageText.inboundText("text", """{"text":"   "}"""))
        assertEquals(null, FeishuMessageText.inboundText("post", """{"content":[[{"tag":"at","user_id":"@_user_1"}]]}"""))
        assertEquals(null, FeishuMessageText.inboundText(null, null))
    }

    @Test
    fun malformed_or_empty_body_degrades_never_throws() {
        assertEquals("[非文本消息]", FeishuMessageText.plainText("text", "not json{"))
        assertEquals("[非文本消息]", FeishuMessageText.plainText("text", null))
        assertEquals("[非文本消息]", FeishuMessageText.plainText(null, null))
    }
}
