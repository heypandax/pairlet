package dev.ccpocket.daemon.feishu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 完成回报的 @ 前缀装配（#284）：群聊定向到发起人，单聊/退化场景一个字符都不改。 */
class FeishuMentionTest {

    @Test
    fun group_completion_is_prefixed_with_the_requester_mention() {
        val out = FeishuMention.completionText("ou_1a2b3c", "已完成：修好了登录崩溃。")
        assertEquals("<at user_id=\"ou_1a2b3c\"></at> 已完成：修好了登录崩溃。", out)
    }

    @Test
    fun direct_chat_text_is_untouched() {
        // 单聊天然定向，多一个 @ 只会在气泡里多出一坨蓝字
        val text = "已完成：修好了登录崩溃。"
        assertEquals(text, FeishuMention.completionText(null, text))
    }

    @Test
    fun blank_open_id_is_equivalent_to_no_mention() {
        // 拼出 <at user_id=""> 在群里会渲染成指向不存在用户的死链，比不 @ 更糟
        val text = "⚠️ 会话已退出。"
        assertEquals(text, FeishuMention.completionText("", text))
        assertEquals(text, FeishuMention.completionText("   ", text))
    }

    @Test
    fun the_mention_prefix_survives_the_outbound_secret_scrub() {
        // reply() 会先过 SecretRedactor 再截断；前缀里的 user_id= 不能被当成密钥赋值洗掉，
        // 否则群里看到的是 <at user_id="‹已隐去›"> —— @ 直接失效且无人察觉
        val out = FeishuMention.completionText("ou_1a2b3c", "跑完了")
        val (scrubbed, hit) = SecretRedactor.redact(out)
        assertFalse(hit, "mention prefix must not trip the redactor: $scrubbed")
        assertTrue(scrubbed.startsWith("<at user_id=\"ou_1a2b3c\"></at> "), scrubbed)
    }
}
