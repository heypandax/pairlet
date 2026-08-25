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
            threadId = null,
            rootId = null,
            ownerTurn = false,
        )
        val followUp = FeishuThreading.conversationKey(
            chatId = "oc_group",
            chatType = "group",
            messageId = "om_child",
            threadId = null,
            rootId = "om_root",
            ownerTurn = false,
        )
        val unrelated = FeishuThreading.conversationKey(
            chatId = "oc_group",
            chatType = "group",
            messageId = "om_other",
            threadId = null,
            rootId = null,
            ownerTurn = false,
        )

        assertEquals(initial, followUp)
        assertNotEquals(initial, unrelated)
    }

    /**
     * #262 的真实场景：在话题面板里**直接发言**（不点回复）。飞书只给 thread_id，不给 root_id——从前只认
     * root_id 时这几条各自成键，等于每问一句就换一个会话、上下文全丢。
     */
    @Test
    fun `messages posted straight into a topic share one conversation without any root id`() {
        val opener = FeishuThreading.conversationKey(
            chatId = "oc_group",
            chatType = "group",
            messageId = "om_1",
            threadId = "omt_topic",
            rootId = null,
            ownerTurn = false,
        )
        val secondLine = FeishuThreading.conversationKey(
            chatId = "oc_group",
            chatType = "group",
            messageId = "om_2",
            threadId = "omt_topic",
            rootId = "",
            ownerTurn = false,
        )
        val otherTopic = FeishuThreading.conversationKey(
            chatId = "oc_group",
            chatType = "group",
            messageId = "om_3",
            threadId = "omt_other",
            rootId = null,
            ownerTurn = false,
        )

        assertEquals(opener, secondLine)
        assertNotEquals(opener, otherTopic)
    }

    /** 飞书自己的话题锚点优先于回复根：同一条消息两个字段都在时，归属跟着 thread_id 走。 */
    @Test
    fun `the feishu thread anchor wins over a reply root`() {
        assertEquals("omt_topic", FeishuThreading.topicAnchor("om_1", "omt_topic", "om_root"))
        assertEquals("om_root", FeishuThreading.topicAnchor("om_1", null, "om_root"))
        assertEquals("om_root", FeishuThreading.topicAnchor("om_1", "  ", "om_root"))
        assertEquals("om_1", FeishuThreading.topicAnchor("om_1", null, ""))
        assertEquals("om_1", FeishuThreading.topicAnchor("om_1", null, null))
    }

    @Test
    fun `direct chat keeps one conversation while owner bypass stays isolated`() {
        val first = FeishuThreading.conversationKey("oc_dm", "p2p", "om_1", null, null, ownerTurn = false)
        val second = FeishuThreading.conversationKey("oc_dm", "p2p", "om_2", null, null, ownerTurn = false)
        val owner = FeishuThreading.conversationKey("oc_dm", "p2p", "om_2", null, null, ownerTurn = true)

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

    /** #265：会话按话题分开，执行锁按群合并——同群两个话题写的是同一棵工作区，不能并发跑。 */
    @Test
    fun `two topics in one group share an execution lock but not a conversation`() {
        val topicA = FeishuThreading.conversationKey("oc_group", "group", "om_a", "omt_a", null, false)
        val topicB = FeishuThreading.conversationKey("oc_group", "group", "om_b", "omt_b", null, false)
        assertNotEquals(topicA, topicB)

        assertEquals(
            FeishuThreading.executionLockKey("oc_group", "group", topicA, ownerTurn = false),
            FeishuThreading.executionLockKey("oc_group", "group", topicB, ownerTurn = false),
        )
        // 另一个群仍然完全并行
        assertNotEquals(
            FeishuThreading.executionLockKey("oc_group", "group", topicA, ownerTurn = false),
            FeishuThreading.executionLockKey("oc_other", "group", topicA, ownerTurn = false),
        )
        // owner bypass 是独立一路会话，也就独立一把锁
        assertNotEquals(
            FeishuThreading.executionLockKey("oc_group", "group", topicA, ownerTurn = false),
            FeishuThreading.executionLockKey("oc_group", "group", topicA, ownerTurn = true),
        )
    }

    /** 直聊没有话题：统一收件箱的两个项目是两个 workdir，串行只会让「问完 A 再问 B」白等。 */
    @Test
    fun `a direct chat serializes per project conversation not per chat`() {
        val projectA = FeishuThreading.directProjectKey("oc_dm", "/w/a", ownerTurn = false)
        val projectB = FeishuThreading.directProjectKey("oc_dm", "/w/b", ownerTurn = false)

        assertNotEquals(
            FeishuThreading.executionLockKey("oc_dm", "p2p", projectA, ownerTurn = false),
            FeishuThreading.executionLockKey("oc_dm", "p2p", projectB, ownerTurn = false),
        )
        assertEquals(projectA, FeishuThreading.executionLockKey("oc_dm", "p2p", projectA, ownerTurn = false))
    }

    /** 顶层 /new 靠这个前缀一次清干净本群所有话题，且不会误伤别的群或直聊。 */
    @Test
    fun `the topic key prefix matches every topic of its own chat only`() {
        val prefix = FeishuThreading.topicKeyPrefix("oc_group")
        val mine = FeishuThreading.conversationKey("oc_group", "group", "om_a", "omt_a", null, false)
        val mineOwner = FeishuThreading.conversationKey("oc_group", "group", "om_a", "omt_a", null, true)
        val theirs = FeishuThreading.conversationKey("oc_other", "group", "om_a", "omt_a", null, false)
        val dm = FeishuThreading.conversationKey("oc_group_dm", "p2p", "om_a", null, null, false)

        assertTrue(mine.startsWith(prefix))
        assertTrue(mineOwner.startsWith(prefix))
        assertFalse(theirs.startsWith(prefix))
        assertFalse(dm.startsWith(prefix))
    }
}
