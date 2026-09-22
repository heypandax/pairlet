package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextLayoutResult
import dev.ccpocket.app.advanceFrameAndWait
import dev.ccpocket.app.data.ChatItem
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.desktop.ChatPane
import dev.ccpocket.app.desktop.ChatStreamAlignment
import dev.ccpocket.app.desktop.SeedDesktopModel
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.chat_you
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.AccentTheme
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.chat.USER_TURN_CONTAINER_TAG
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.SessionLive
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/** Real chat components with synthetic content. Set CHAT_ROLE_DESIGN_OUT to retain review PNGs. */
@OptIn(ExperimentalTestApi::class)
class ChatRoleDesignTest {
    private val prompt = "请检查这次修改，并解释测试结果。\nKeep the user prompt distinct from the Agent response, including long mixed-language text."
    private val reply = "已检查修改。\n\n- 标签和正文保持独立行。\n- 窄屏仍可阅读完整内容。\n\nThe review is ready."
    private val workdir = "/workspace/demo"
    private val thumbnail: ByteArray by lazy {
        val image = BufferedImage(240, 120, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = Color(0x1E2125); fillRect(0, 0, 240, 120)
            color = Color(0xD97757); fillRoundRect(18, 20, 60, 80, 12, 12)
            color = Color(0x9BA1A6); fillRect(96, 26, 122, 8); fillRect(96, 46, 104, 8); fillRect(96, 66, 116, 8)
            dispose()
        }
        ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun SkikoComposeUiTest.save(name: String) {
        val dir = System.getenv("CHAT_ROLE_DESIGN_OUT")?.let(::File) ?: return
        dir.mkdirs()
        val image = Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap())
        File(dir, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }

    private fun SemanticsNodeInteraction.assertReadableRoleLabel() {
        val layout = mutableListOf<TextLayoutResult>().also {
            fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(it)
        }.first()
        val background = Tok.userTurnBg.compositeOver(Tok.base)
        val foreground = layout.layoutInput.style.color.compositeOver(background)
        val a = foreground.luminance()
        val b = background.luminance()
        val contrast = (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
        assertTrue(contrast >= 4.5f, "the required role label needs readable contrast: $contrast")
    }

    private fun phone(name: String, dark: Boolean = true, accent: AccentTheme = AccentTheme.POCKET, width: Int = 402, scale: Float = 1f) =
        runDesktopComposeUiTest(width, 874) {
            mainClock.autoAdvance = false
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                    val scope = rememberCoroutineScope()
                    val repo = remember {
                        PocketRepository(scope, PairedDaemon("wss://test.invalid", "role-proof", "pub", "device", "credential", hostName = "Demo Mac")).apply {
                            receiveForTest(SessionLive("role-proof", this@ChatRoleDesignTest.workdir, "session", executing = false, agent = AgentKind.CLAUDE))
                            receiveForTest(ConvoHistory("role-proof", listOf(
                                HistoryMessage(ChatRole.USER, prompt, images = if (scale == 1f) listOf(ImageData("image/png", Base64.getEncoder().encodeToString(thumbnail))) else emptyList()),
                                HistoryMessage(ChatRole.ASSISTANT, reply),
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
            onAllNodes(hasText(str(Res.string.chat_you).uppercase())).onFirst().performScrollTo()
            mainClock.autoAdvance = false
            advanceFrameAndWait()
            val label = onAllNodes(hasText(str(Res.string.chat_you).uppercase())).onFirst().getUnclippedBoundsInRoot()
            val body = onAllNodes(hasText(prompt)).onFirst().getUnclippedBoundsInRoot()
            onAllNodes(hasText(str(Res.string.chat_you).uppercase())).onFirst().assertReadableRoleLabel()
            assertTrue(body.top >= label.bottom, "role label must remain above content")
            assertTrue(body.left.value >= 0 && body.right.value <= width, "prompt must fit the phone column")
            save(name)
        }

    @Test fun phoneDark() = phone("phone-dark")
    @Test fun phoneLight() = phone("phone-light", dark = false)
    @Test fun phoneTealLight() = phone("phone-teal-light", dark = false, accent = AccentTheme.CODEX)
    @Test fun phoneNarrowLargeType() = phone("phone-320-large", width = 320, scale = 1.6f)

    private fun desktop(name: String, bubbles: Boolean, short: Boolean = false) = runDesktopComposeUiTest(760, 800) {
        mainClock.autoAdvance = false
        val message = if (short) "OK" else prompt
        val model = object : SeedDesktopModel() {
            override val streaming = false
            override val ask: PermissionAsk? = null
            override val chatTitle = "Review chat roles"
            override val chatWorkdir = workdir
            override val messages = listOf(ChatItem.User(message, images = if (short) emptyList() else listOf(thumbnail)), ChatItem.Assistant(reply))
        }.apply { chatAlignment = if (bubbles) ChatStreamAlignment.BUBBLES else ChatStreamAlignment.LEFT }
        setContent { PocketTheme { ChatPane(model) } }
        advanceFrameAndWait()
        mainClock.autoAdvance = true
        onAllNodes(hasText(message)).onFirst().performScrollTo()
        mainClock.autoAdvance = false
        advanceFrameAndWait()
        val body = onAllNodes(hasText(message)).onFirst().getUnclippedBoundsInRoot()
        val labelNode = onAllNodes(hasText(str(Res.string.chat_you).uppercase())).onFirst()
        labelNode.assertReadableRoleLabel()
        val label = labelNode.getUnclippedBoundsInRoot()
        val container = onNodeWithTag(USER_TURN_CONTAINER_TAG).getUnclippedBoundsInRoot()
        val inset = if (bubbles) 14f else 12f
        assertTrue(kotlin.math.abs(container.right.value - inset - label.right.value) < 1f, "label stays at the trailing content edge: label=$label, container=$container")
        assertTrue(body.top >= label.bottom, "desktop label stays above message content")
        if (short) assertTrue((container.right - container.left).value < 200f, "short bubbles must hug content rather than fill the 520dp limit: $container")
        assertTrue(body.left.value >= 0 && body.right.value <= 760, "prompt must fit the desktop column")
        save(name)
    }

    @Test fun desktopLeft() = desktop("desktop-left", bubbles = false)
    @Test fun desktopBubbles() = desktop("desktop-bubbles", bubbles = true)
    @Test fun desktopShortBubble() = desktop("desktop-short-bubble", bubbles = true, short = true)
}
