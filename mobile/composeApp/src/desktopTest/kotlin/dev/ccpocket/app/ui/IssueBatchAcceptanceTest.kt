package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import dev.ccpocket.app.advanceFrameAndWait
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.desktop.ChatPane
import dev.ccpocket.app.desktop.ChatStreamAlignment
import dev.ccpocket.app.desktop.FakeDesktopStore
import dev.ccpocket.app.desktop.RepoDesktopModel
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.chat.USER_TURN_CONTAINER_TAG
import dev.ccpocket.protocol.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** Integration of #394/#396 with #397, through the actual repository and both chat entry points. */
@OptIn(ExperimentalTestApi::class)
class IssueBatchAcceptanceTest {
    private val summary = "This session is being continued from a previous conversation that ran out of context.\nSummary: 保留完整上下文。"
    private val answer = "Agent reply stays outside the user container."
    private fun live(used: Long? = 191_000, authoritative: Boolean = false, summary: String? = null) =
        SessionLive("acceptance", "/workspace/demo", "session", executing = false, agent = AgentKind.CLAUDE,
            contextWindow = 200_000, contextUsed = used, contextUsedAuthoritative = authoritative, compactSummary = summary)

    private fun repo(scope: CoroutineScope, includeLive: Boolean = true) = PocketRepository(scope).apply {
        paired.value = PairedDaemon("wss://test.invalid", "acceptance", "public", "device", "credential", hostName = "Demo Mac")
        convoId.value = "acceptance"
        if (includeLive) receiveForTest(live())
    }

    @Suppress("DEPRECATION")
    private class Clipboard : ClipboardManager {
        var copied: AnnotatedString? = null
        override fun getText() = copied
        override fun setText(annotatedString: AnnotatedString) { copied = annotatedString }
    }

    private fun SkikoComposeUiTest.save(name: String) {
        val dir = System.getenv("ISSUE_ACCEPTANCE_OUT")?.let(::File) ?: return
        dir.mkdirs()
        val image = Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap())
        File(dir, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }

    private fun chat(desktop: Boolean, dark: Boolean = true, bubbles: Boolean = false) =
        runDesktopComposeUiTest(if (desktop) 900 else 402, 950) {
            val scope = CoroutineScope(Dispatchers.Unconfined)
            val repo = repo(scope, includeLive = false)
            val clipboard = Clipboard()
            try {
                // Construct the live model before delivering its first session, as the application does.
                val model = if (desktop) RepoDesktopModel(repo, scope, store = FakeDesktopStore()).apply {
                    chatAlignment = if (bubbles) ChatStreamAlignment.BUBBLES else ChatStreamAlignment.LEFT
                } else null
                repo.receiveForTest(live())
                // Identical words, distinct provenance: a user's quotation must remain an ordinary turn.
                repo.receiveForTest(ConvoHistory("acceptance", listOf(
                    HistoryMessage(ChatRole.USER, summary),
                    HistoryMessage(ChatRole.ASSISTANT, answer),
                    HistoryMessage(ChatRole.USER, summary, compactSummary = true),
                )))
                mainClock.autoAdvance = false
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f), LocalClipboardManager provides clipboard) {
                        PocketTheme(dark = dark) {
                            Box(Modifier.fillMaxSize().background(Tok.base)) {
                                if (model != null) ChatPane(model) else ChatScreen(repo)
                            }
                        }
                    }
                }
                advanceFrameAndWait()
                onAllNodesWithTag(USER_TURN_CONTAINER_TAG).assertCountEquals(1)
                onNodeWithTag(USER_TURN_CONTAINER_TAG).assert(hasAnyDescendant(hasText(summary)))
                onAllNodesWithText(summary).assertCountEquals(1)
                val collapsed = "▸ " + str(Res.string.compact_summary)
                val expanded = "▾ " + str(Res.string.compact_summary)
                onNodeWithText(collapsed).assertExists()

                // A duplicate live echo must not add another card.
                repo.receiveForTest(live(used = null, authoritative = true, summary = summary))
                advanceFrameAndWait()
                onAllNodesWithText(collapsed).assertCountEquals(1)
                val name = if (desktop) "desktop-${if (bubbles) "bubbles" else "left"}" else "phone-${if (dark) "dark" else "light"}"
                save("$name-collapsed")
                onNodeWithText(collapsed).performClick()
                advanceFrameAndWait()
                onAllNodesWithText(summary).assertCountEquals(2)
                onAllNodesWithTag(USER_TURN_CONTAINER_TAG).assertCountEquals(1)
                onAllNodesWithText(str(Res.string.code_copy)).onLast().performClick()
                advanceFrameAndWait()
                assertEquals(summary, clipboard.copied?.text, "the expanded summary copies its complete content")
                save("$name-expanded")
                onNodeWithText(expanded).performClick()
                advanceFrameAndWait()
                onAllNodesWithText(summary).assertCountEquals(1)
            } finally { scope.cancel() }
        }

    @Test fun phoneDarkKeepsQuotedAndGeneratedSummariesDistinct() = chat(desktop = false)
    @Test fun phoneLightKeepsQuotedAndGeneratedSummariesDistinct() = chat(desktop = false, dark = false)
    @Test fun desktopLeftKeepsQuotedAndGeneratedSummariesDistinct() = chat(desktop = true)
    @Test fun desktopBubblesKeepsQuotedAndGeneratedSummariesDistinct() = chat(desktop = true, bubbles = true)

    @Test fun sessionInfoRefreshesWithoutAnotherUserPrompt() = runDesktopComposeUiTest(600, 950) {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val repo = repo(scope)
        try {
            mainClock.autoAdvance = false
            setContent { PocketTheme { SessionInfoSheet(repo, onDismiss = {}) } }
            advanceFrameAndWait()
            onNodeWithText("~191k / 200k · 95%").assertExists()
            repo.receiveForTest(live(used = null, authoritative = true))
            advanceFrameAndWait()
            onNodeWithText("~191k / 200k · 95%").assertDoesNotExist()
            onNodeWithText(str(Res.string.context_status_used_pending)).assertExists()
            save("context-pending")
            repo.receiveForTest(live(used = 12_000, authoritative = true))
            advanceFrameAndWait()
            onNodeWithText("~12k / 200k · 6%").assertExists()
            onNodeWithText(str(Res.string.context_status_used_pending)).assertDoesNotExist()
            save("context-refreshed")
        } finally { scope.cancel() }
    }
}
