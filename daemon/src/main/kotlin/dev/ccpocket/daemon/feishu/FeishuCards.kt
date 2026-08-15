package dev.ccpocket.daemon.feishu

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The `/menu` control panel (issue #247): one interactive Feishu card whose buttons re-enter the SAME
 * command chain a typed line does — the PROGRAMMATIC twin of docs/FEISHU-BOT-MENU.md.
 *
 * That doc tells an owner to configure three bottom-menu items in the Feishu developer console by hand;
 * this card ships the identical three with zero backend configuration, so a fresh install has them the
 * moment the bridge starts. The two must not drift: [BUTTONS] carries the doc's labels AND its exact
 * command text, and a button click is answered by feeding that command text back through
 * [FeishuCommands.handle] — never by a second, card-only execution path.
 *
 * SECURITY: a `card.action.trigger` callback is attacker-shaped input (its `value` map is whatever
 * arrives on the wire), so [commandFor] is an ALLOW-LIST, not a parser: only a string equal to one of the
 * three commands above ever becomes a chat line, and even then it runs under the clicking user's own
 * identity through the same sender/trust checks a typed line passes. A card can therefore never be a
 * wider authority than typing — at most an equal one, over a strictly smaller vocabulary.
 */
internal object FeishuCards {
    /** The key a menu button carries inside its `value` object, read back off the callback's action. */
    const val ACTION_KEY = "cmd"

    /** One quick action: what the button says, and the command it is exactly equivalent to typing. */
    data class MenuButton(val label: String, val command: String, val type: String)

    /** The three quick actions, in display order — kept literally equal to docs/FEISHU-BOT-MENU.md's table. */
    val BUTTONS: List<MenuButton> = listOf(
        MenuButton("🆕 新会话", "/new", "primary"),
        MenuButton("📁 项目列表", "/projects", "default"),
        MenuButton("ℹ️ 状态", "/trust-status", "default"),
    )

    /** The allow-list a callback's `value` is matched against — nothing else may enter the command chain. */
    val COMMANDS: Set<String> = BUTTONS.map { it.command }.toSet()

    /**
     * The command a `card.action.trigger` payload asks for, or null when it names none of ours: an absent
     * or non-string `cmd`, a stale card from an older build, or a crafted callback. Null means IGNORE —
     * the caller must never fall back to "run it anyway".
     */
    fun commandFor(value: Map<String, Any?>?): String? =
        (value?.get(ACTION_KEY) as? String)?.trim()?.takeIf { it in COMMANDS }

    /** The `/menu` card payload (Feishu message card, sent as `msg_type: interactive`). */
    fun menuCard(): String = buildJsonObject {
        putJsonObject("config") { put("wide_screen_mode", true) }
        putJsonObject("header") {
            put("template", "blue")
            putJsonObject("title") { put("tag", "plain_text"); put("content", TITLE) }
        }
        putJsonArray("elements") {
            addJsonObject {
                put("tag", "div")
                putJsonObject("text") { put("tag", "lark_md"); put("content", INTRO) }
            }
            addJsonObject {
                put("tag", "action")
                putJsonArray("actions") {
                    for (button in BUTTONS) addJsonObject {
                        put("tag", "button")
                        putJsonObject("text") { put("tag", "plain_text"); put("content", button.label) }
                        put("type", button.type)
                        // the ONLY thing a click carries back to us — read through commandFor's allow-list
                        putJsonObject("value") { put(ACTION_KEY, button.command) }
                    }
                }
            }
        }
    }.toString()

    /** What `/menu` says when the card itself can't be delivered (no interactive-message permission, a
     *  transient Feishu error): the same three actions as copyable text, so the command still helps. */
    val MENU_FALLBACK: String = buildString {
        appendLine("快捷操作（卡片发不出来时，直接发送下面的命令即可）：")
        for (button in BUTTONS) appendLine("  • ${button.label} → ${button.command}")
    }.trimEnd()

    /** Answered to a click we can't act on, so the button never just goes dead under the user's finger. */
    const val UNKNOWN_ACTION_TOAST = "这个按钮已失效，请重新发送 /menu"

    private const val TITLE = "cc-pocket 快捷操作"
    private const val INTRO = "点按钮等于替你发出对应命令，权限与手打完全一致。"
}

/**
 * Turning a `card.action.trigger` callback into the chat line it is equivalent to — pure, so the rule that
 * decides WHOSE line it is and WHERE it lands has tests that need neither Feishu nor a running engine.
 *
 * The callback tells us almost nothing about context: an operator, a `value` map, and the CARD message's
 * id. So resolution is deliberately closed: a click only becomes a chat line when the card is one this
 * engine posted into a chat it still remembers, and only when its value names an allow-listed command.
 * Everything else resolves to null = ignore. The acting identity is always the Feishu-attested OPERATOR,
 * never anything the card carries — a card posted by the machine owner grants a colleague who presses it
 * exactly the authority that colleague already had.
 */
internal object FeishuCardCallback {
    /** Where a card we posted lives, remembered at send time (the callback can't tell us). */
    data class Origin(val chatId: String, val chatType: String?, val topicRoot: String?)

    /** The typed-message equivalent of one button press. */
    data class Line(
        val command: String,
        val sender: String,
        val chatId: String,
        val chatType: String?,
        val cardMessageId: String,
        val topicRoot: String?,
    )

    /**
     * The chat line this callback stands for, or null when it must be ignored:
     *  - `value` names no allow-listed command (stale card, older build, crafted payload);
     *  - no operator or no card message id — there is no identity or anchor to act under;
     *  - the card is unknown to us (posted by another engine instance, or evicted after a restart);
     *  - the callback's own chat disagrees with where we posted that card.
     */
    fun resolve(
        value: Map<String, Any?>?,
        operatorOpenId: String?,
        cardMessageId: String?,
        callbackChatId: String?,
        originOf: (String) -> Origin?,
    ): Line? {
        val command = FeishuCards.commandFor(value) ?: return null
        val operator = operatorOpenId?.takeIf { it.isNotBlank() } ?: return null
        val cardId = cardMessageId?.takeIf { it.isNotBlank() } ?: return null
        val origin = originOf(cardId) ?: return null
        if (!callbackChatId.isNullOrBlank() && callbackChatId != origin.chatId) return null
        return Line(command, operator, origin.chatId, origin.chatType, cardId, origin.topicRoot)
    }
}
