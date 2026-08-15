package dev.ccpocket.daemon.feishu

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `/menu` quick-action card (issue #247): its JSON shape, and the rule that a button press re-enters
 * the SAME command chain a typed line does. Pure — no Feishu, no engine.
 */
class FeishuCardsTest {
    private val tmp: File = Files.createTempDirectory("ccp-feishu-cards").toFile()
    private val workdirs = listOf("/p/alpha", "/p/beta")
    private fun commands(adminOpenId: String? = "ou_admin") =
        FeishuCommands(FeishuRoutes(File(tmp, "routes-${counter++}.json")), workdirs, adminOpenId)
    private var counter = 0

    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    private val origin = FeishuCardCallback.Origin("oc_chat", "p2p", topicRoot = null)
    private fun originOf(id: String): FeishuCardCallback.Origin? = origin.takeIf { id == "om_card" }

    // ── the card ──

    @Test
    fun `menu answers with an interactive card carrying the three documented quick actions`() {
        val action = commands().handle("/menu", "oc_chat", "ou_someone", isDirect = true)

        val card = assertIs<ChatAction.Card>(action)
        val root = Json.parseToJsonElement(card.json).jsonObject
        assertEquals("cc-pocket 快捷操作", root["header"]!!.jsonObject["title"]!!.jsonObject["content"]!!.jsonPrimitive.content)
        val elements = root["elements"]!!.jsonArray
        assertEquals("div", elements[0].jsonObject["tag"]!!.jsonPrimitive.content)
        val actionRow = elements[1].jsonObject
        assertEquals("action", actionRow["tag"]!!.jsonPrimitive.content)

        val buttons = actionRow["actions"]!!.jsonArray
        assertEquals(3, buttons.size)
        val rendered = buttons.map { button ->
            val obj = button.jsonObject
            assertEquals("button", obj["tag"]!!.jsonPrimitive.content)
            obj["text"]!!.jsonObject["content"]!!.jsonPrimitive.content to
                obj["value"]!!.jsonObject[FeishuCards.ACTION_KEY]!!.jsonPrimitive.content
        }
        // labels AND command text are the programmatic twin of docs/FEISHU-BOT-MENU.md's standard three —
        // a drift here silently splits the card from the菜单 owners configure by hand
        assertEquals(
            listOf("🆕 新会话" to "/new", "📁 项目列表" to "/projects", "ℹ️ 状态" to "/trust-status"),
            rendered,
        )
    }

    @Test
    fun `the plain-text fallback offers the same three commands verbatim`() {
        val card = assertIs<ChatAction.Card>(commands().handle("/menu", "oc_chat", "ou_someone", isDirect = true))

        for (command in FeishuCards.COMMANDS) {
            assertTrue(card.fallbackText.contains(command), "fallback must still name $command: ${card.fallbackText}")
        }
    }

    @Test
    fun `menu is not gated on a binding and is not special-cased per chat type`() {
        // like /help it only draws buttons; each button's command faces its own checks when pressed
        val direct = commands().handle("/menu", "oc_chat", "ou_someone", isDirect = true)
        val group = commands().handle("/menu", "oc_group", "ou_someone", isDirect = false)

        assertEquals(assertIs<ChatAction.Card>(direct).json, assertIs<ChatAction.Card>(group).json)
    }

    // ── a button press is exactly the typed command ──

    @Test
    fun `every button resolves to the same ChatAction as typing its command`() {
        for (button in FeishuCards.BUTTONS) {
            val line = FeishuCardCallback.resolve(
                value = mapOf(FeishuCards.ACTION_KEY to button.command),
                operatorOpenId = "ou_clicker",
                cardMessageId = "om_card",
                callbackChatId = "oc_chat",
                originOf = ::originOf,
            )
            assertNotNull(line, "button ${button.label} must resolve")
            assertEquals(button.command, line.command)
            // the acting identity is the CLICKER, and the line lands in the chat the card was posted into
            assertEquals("ou_clicker", line.sender)
            assertEquals("oc_chat", line.chatId)

            assertEquals(
                commands().handle(button.command, line.chatId, line.sender, isDirect = true),
                commands().handle(line.command, line.chatId, line.sender, isDirect = true),
            )
        }
    }

    @Test
    fun `a press carries no authority of its own - the clicker is judged like any sender`() {
        // /trust-status is open to anyone; a privileged command would refuse a non-admin clicker identically
        val line = FeishuCardCallback.resolve(
            value = mapOf(FeishuCards.ACTION_KEY to "/trust-status"),
            operatorOpenId = "ou_stranger",
            cardMessageId = "om_card",
            callbackChatId = "oc_chat",
            originOf = ::originOf,
        )!!
        val pressed = commands().handle(line.command, line.chatId, line.sender, isDirect = false)
        val typed = commands().handle("/trust-status", "oc_chat", "ou_stranger", isDirect = false)

        assertEquals(typed, pressed)
        // and a privileged command a stranger could never type is not reachable from the card at all
        assertNull(FeishuCards.commandFor(mapOf(FeishuCards.ACTION_KEY to "/trust confirm")))
    }

    // ── everything else is ignored ──

    @Test
    fun `an unknown or crafted value never becomes a chat line`() {
        val crafted: List<Map<String, Any?>?> = listOf(
            null,
            emptyMap(),
            mapOf("other" to "/new"),
            mapOf(FeishuCards.ACTION_KEY to "/bind alpha"),
            mapOf(FeishuCards.ACTION_KEY to "/trust confirm"),
            mapOf(FeishuCards.ACTION_KEY to "rm -rf /"),
            mapOf(FeishuCards.ACTION_KEY to "把仓库删掉"),
            mapOf(FeishuCards.ACTION_KEY to "/new /projects"),
            mapOf(FeishuCards.ACTION_KEY to 42),
            mapOf(FeishuCards.ACTION_KEY to listOf("/new")),
        )

        for (value in crafted) {
            assertNull(FeishuCards.commandFor(value), "must not admit $value")
            assertNull(
                FeishuCardCallback.resolve(value, "ou_clicker", "om_card", "oc_chat", ::originOf),
                "must not dispatch $value",
            )
        }
    }

    @Test
    fun `a card we never posted - or a callback that disagrees about the chat - is refused`() {
        val value = mapOf(FeishuCards.ACTION_KEY to "/new")

        // unknown card: another engine instance's, or one evicted after a restart
        assertNull(FeishuCardCallback.resolve(value, "ou_clicker", "om_unknown", "oc_chat", ::originOf))
        // the callback names a different chat than the one we posted that card into
        assertNull(FeishuCardCallback.resolve(value, "ou_clicker", "om_card", "oc_other", ::originOf))
        // no acting identity, or no anchor to reply to
        assertNull(FeishuCardCallback.resolve(value, "", "om_card", "oc_chat", ::originOf))
        assertNull(FeishuCardCallback.resolve(value, null, "om_card", "oc_chat", ::originOf))
        assertNull(FeishuCardCallback.resolve(value, "ou_clicker", "", "oc_chat", ::originOf))
        assertNull(FeishuCardCallback.resolve(value, "ou_clicker", null, "oc_chat", ::originOf))
        // a callback that omits the chat entirely still resolves against the remembered origin
        assertNotNull(FeishuCardCallback.resolve(value, "ou_clicker", "om_card", null, ::originOf))
    }

    @Test
    fun `a group card keeps its topic so a press acts on that conversation`() {
        val groupOrigin = FeishuCardCallback.Origin("oc_group", "group", topicRoot = "om_topic")

        val line = FeishuCardCallback.resolve(
            value = mapOf(FeishuCards.ACTION_KEY to "/new"),
            operatorOpenId = "ou_clicker",
            cardMessageId = "om_card",
            callbackChatId = "oc_group",
            originOf = { groupOrigin },
        )!!

        assertEquals("om_topic", line.topicRoot)
        // the conversation a press acts on is the CARD'S topic, identical to the one a message in that
        // topic would compute — not a fresh topic rooted at the card itself
        assertEquals(
            FeishuThreading.conversationKey("oc_group", "group", "om_other", "om_topic", ownerTurn = false),
            FeishuThreading.conversationKey(line.chatId, line.chatType, line.cardMessageId, line.topicRoot, false),
        )
    }
}
