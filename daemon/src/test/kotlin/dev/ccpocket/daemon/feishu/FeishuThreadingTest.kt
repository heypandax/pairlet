package dev.ccpocket.daemon.feishu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FeishuThreadingTest {
    @Test
    fun `group top-level message and its follow-ups share one topic conversation`() {
        val initial = FeishuThreading.conversationKey(
            chatId = "oc_group",
            chatType = "group",
            messageId = "om_root",
            rootId = null,
            ownerTurn = false,
        )
        val followUp = FeishuThreading.conversationKey(
            chatId = "oc_group",
            chatType = "group",
            messageId = "om_child",
            rootId = "om_root",
            ownerTurn = false,
        )
        val unrelated = FeishuThreading.conversationKey(
            chatId = "oc_group",
            chatType = "group",
            messageId = "om_other",
            rootId = null,
            ownerTurn = false,
        )

        assertEquals(initial, followUp)
        assertNotEquals(initial, unrelated)
    }

    @Test
    fun `direct chat keeps one conversation while owner bypass stays isolated`() {
        val first = FeishuThreading.conversationKey("oc_dm", "p2p", "om_1", null, ownerTurn = false)
        val second = FeishuThreading.conversationKey("oc_dm", "p2p", "om_2", null, ownerTurn = false)
        val owner = FeishuThreading.conversationKey("oc_dm", "p2p", "om_2", null, ownerTurn = true)

        assertEquals(first, second)
        assertNotEquals(first, owner)
    }

    @Test
    fun `only group replies request thread mode`() {
        assertTrue(FeishuThreading.replyTarget("om_group", "group").inThread)
        assertTrue(FeishuThreading.replyTarget("om_group", "GROUP").inThread)
        assertFalse(FeishuThreading.replyTarget("om_dm", "p2p").inThread)
        assertFalse(FeishuThreading.replyTarget("om_unknown", null).inThread)
    }
}
