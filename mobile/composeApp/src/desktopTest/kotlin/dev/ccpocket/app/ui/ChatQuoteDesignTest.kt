package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import dev.ccpocket.app.advanceFrameAndWait
import dev.ccpocket.app.data.ChatItem
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.desktop.ChatPane
import dev.ccpocket.app.desktop.SeedDesktopModel
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.code_copied
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.AccentTheme
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.SessionLive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * Chat Quote v1 — a drafted message quoted inside an Agent reply, between two user turns, on the real
 * phone ChatScreen and desktop ChatPane. Set CHAT_QUOTE_DESIGN_OUT to retain review PNGs.
 */
@OptIn(ExperimentalTestApi::class)
class ChatQuoteDesignTest {
    private val ask = "帮我回一下设计同事 Mia，说这周先不改首页"
    private val draft = listOf(
        "嗨 Mia，这周我们先集中修登录流程的问题，首页改版挪到下周。",
        "你上次给的两版稿子我都看过了，第二版的导航更清楚。",
        "周一上午有空一起过一下细节吗？",
    )
    private val reply = "可以，**直接说明优先级就好**，不用解释太多。\n\n可以这样发：\n\n" +
        draft.joinToString("\n>\n") { "> $it" } + "\n\n等对方回复后，再约具体时间。"
    private val next = "好的，再帮我想想周一要准备什么"

    private fun SkikoComposeUiTest.save(name: String) {
        val dir = System.getenv("CHAT_QUOTE_DESIGN_OUT")?.let(::File) ?: return
        dir.mkdirs()
        val image = Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap())
        File(dir, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }

    /** The quote renders as prose between the two user turns: no literal markers, reading order kept.
     *  A turn scrolled out of the lazy list isn't composed (320pt at 1.6×), so only present turns are ordered. */
    private fun SkikoComposeUiTest.assertQuoteBetweenTurns(width: Int) {
        assertTrue(onAllNodes(hasText("> ", substring = true)).fetchSemanticsNodes().isEmpty(), "quote markers must not render")
        fun boundsOf(text: String) = onAllNodes(hasText(text)).takeIf { it.fetchSemanticsNodes().isNotEmpty() }?.onFirst()?.getUnclippedBoundsInRoot()
        val quoteTop = boundsOf(draft.first())!!
        val quoteEnd = boundsOf(draft.last())!!
        boundsOf(ask)?.let { assertTrue(it.bottom <= quoteTop.top, "quote follows the user turn that asked for it") }
        boundsOf(next)?.let { assertTrue(quoteEnd.bottom <= it.top, "quote ends before the next user turn") }
        assertTrue(quoteTop.left.value >= 0 && quoteEnd.right.value <= width, "quote fits the column")

        // Chat Quote v1 (1a): no panel behind the quote. The indent between the rule and the text shows
        // the page itself — the shipped panel painted surface there, the user turn's grammar.
        val px = density.density
        val pixels = onRoot().captureToImage().toPixelMap()
        val probe = pixels[((quoteTop.left.value - 6f) * px).toInt(), ((quoteTop.top.value + 8f) * px).toInt()]
        assertEquals(Tok.base, probe, "the quote sits on the page, not on a filled panel")

        // one glyph: a 44dp target centered on the first line, clear of the first line's text
        val glyph = onNodeWithTag(QUOTE_COPY_TAG).getUnclippedBoundsInRoot()
        assertEquals(44f, glyph.right.value - glyph.left.value, 0.5f, "copy target is 44dp wide")
        val glyphCenterY = (glyph.top.value + glyph.bottom.value) / 2
        assertTrue(glyphCenterY in quoteTop.top.value..quoteTop.bottom.value, "glyph rides the first line")
        val slotLeft = (glyph.left.value + glyph.right.value) / 2 - 12f
        assertTrue(quoteTop.right.value <= slotLeft + 0.5f, "first line stops before the glyph: text=$quoteTop glyph=$glyph")
    }

    private fun phone(name: String, dark: Boolean = true, accent: AccentTheme = AccentTheme.POCKET, width: Int = 402, scale: Float = 1f, copied: Boolean = false) =
        runDesktopComposeUiTest(width, 874) {
            mainClock.autoAdvance = false
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                    val scope = rememberCoroutineScope()
                    val repo = remember {
                        PocketRepository(scope, PairedDaemon("wss://test.invalid", "quote-proof", "pub", "device", "credential", hostName = "Demo Mac")).apply {
                            receiveForTest(SessionLive("quote-proof", "/workspace/demo", "session", executing = false, agent = AgentKind.CODEX))
                            receiveForTest(ConvoHistory("quote-proof", listOf(
                                HistoryMessage(ChatRole.USER, ask),
                                HistoryMessage(ChatRole.ASSISTANT, reply),
                                HistoryMessage(ChatRole.USER, next),
                            )))
                        }
                    }
                    PocketTheme(dark = dark, accent = accent) {
                        Box(Modifier.fillMaxSize().background(Tok.base)) { ChatScreen(repo) }
                    }
                }
            }
            advanceFrameAndWait()
            mainClock.autoAdvance = true
            onAllNodes(hasText(draft.first())).onFirst().performScrollTo()
            mainClock.autoAdvance = false
            advanceFrameAndWait()
            if (copied) {
                onNodeWithTag(QUOTE_COPY_TAG).performClick()
                mainClock.advanceTimeBy(300) // past the rule's 150ms step, well inside the 1.5s hold
                advanceFrameAndWait()
                assertCopiedFloat()
            }
            assertQuoteBetweenTurns(width)
            save(name)
        }

    /** After a copy the float grows by its "copied" label: it sits left of the glyph, and the first line
     *  makes room for both instead of running under them. The rule steps up from muted to tx2. */
    private fun SkikoComposeUiTest.assertCopiedFloat() {
        val label = onNodeWithText(str(Res.string.code_copied)).getUnclippedBoundsInRoot()
        val glyph = onNodeWithTag(QUOTE_COPY_TAG).getUnclippedBoundsInRoot()
        val firstLine = onAllNodes(hasText(draft.first())).onFirst().getUnclippedBoundsInRoot()
        val slotLeft = (glyph.left.value + glyph.right.value) / 2 - 12f
        assertTrue(label.right.value <= slotLeft + 0.5f, "label sits before the glyph: label=$label glyph=$glyph")
        assertTrue(firstLine.right.value <= label.left.value + 0.5f, "first line stops before the label: text=$firstLine label=$label")
        val px = density.density
        val rule = onRoot().captureToImage().toPixelMap()[((firstLine.left.value - 13f) * px).toInt(), ((firstLine.top.value + 8f) * px).toInt()]
        assertEquals(Tok.tx2, rule, "the rule marks what was copied")
    }

    @Test fun phoneDark() = phone("phone-dark")
    @Test fun phoneLight() = phone("phone-light", dark = false)
    @Test fun phoneTealLight() = phone("phone-teal-light", dark = false, accent = AccentTheme.CODEX)
    @Test fun phoneNarrowLargeType() = phone("phone-320-large", width = 320, scale = 1.6f)
    @Test fun phoneCopied() = phone("phone-dark-copied", copied = true)

    @Test
    fun desktop() = runDesktopComposeUiTest(760, 800) {
        mainClock.autoAdvance = false
        val model = object : SeedDesktopModel() {
            override val streaming = false
            override val ask: PermissionAsk? = null
            override val chatTitle = "Reply to Mia"
            override val chatWorkdir = "/workspace/demo"
            override val messages = listOf(ChatItem.User(this@ChatQuoteDesignTest.ask), ChatItem.Assistant(reply), ChatItem.User(next))
        }
        setContent { PocketTheme { ChatPane(model) } }
        advanceFrameAndWait()
        mainClock.autoAdvance = true
        onAllNodes(hasText(draft.first())).onFirst().performScrollTo()
        mainClock.autoAdvance = false
        advanceFrameAndWait()
        assertQuoteBetweenTurns(760)
        save("desktop")
    }
}
