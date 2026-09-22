package dev.ccpocket.app.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.copy_path
import dev.ccpocket.app.resources.file_show_full_path
import dev.ccpocket.app.resources.files_copy_full_path
import dev.ccpocket.app.resources.file_open
import dev.ccpocket.app.resources.file_save_as
import dev.ccpocket.app.resources.files_open_default
import dev.ccpocket.app.resources.diff_soft_wrap
import dev.ccpocket.app.resources.diff_tab
import dev.ccpocket.app.resources.diff_none
import dev.ccpocket.app.resources.file_tip_wrap_on
import dev.ccpocket.app.resources.file_tip_wrap_off
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.ChangedFile
import dev.ccpocket.protocol.FileContent
import dev.ccpocket.protocol.FileDiff
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #393: the file viewer's toolbar is icon-only, so hovering has to say what each button does.
 * This pins the two properties that make such a tooltip worth having — it APPEARS on hover, and it does
 * not swallow the click of the control it describes (a tooltip layer over a toolbar is exactly how that
 * gets broken). The copy button stands in for the group: they all go through the same wrapper.
 */
@OptIn(ExperimentalTestApi::class)
class FileViewerTooltipTest {

    private val path = "/srv/app/services/ingest/pipeline/collector/stage.py"

    private fun model() = object : SeedDesktopModel() {
        override val changedFiles = listOf(ChangedFile(path = path, op = "write", adds = 12, dels = 3))
        override val selectedChangedPath = path
        override val selectedContent = FileContent(
            workdir = "/srv/app", sessionId = "s", path = path, text = "print('hi')\n",
        )
    }

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult =
        mutableListOf<TextLayoutResult>().also {
            fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(it)
        }.first()

    @Test
    fun hoveringRevealsTheWholeLongFilenameAndPath() = runDesktopComposeUiTest(width = 1000, height = 640) {
        val name = "project_configuration_export_".repeat(6) + ".kt"
        val fullPath = "/workspace/" + "feature_implementation_directory/".repeat(10) + name
        val label = runBlocking { getString(Res.string.file_show_full_path) }
        setContent { PocketTheme(dark = false) { FilePathTitle(fullPath) } }
        onNodeWithContentDescription(label).performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(TOOLTIP_DELAY_MS + 200L)
        waitForIdle()

        assertFalse(onNodeWithText(name).textLayout().hasVisualOverflow, "the tooltip must wrap the whole filename")
        assertFalse(onNodeWithText(fullPath).textLayout().hasVisualOverflow, "the tooltip must wrap the whole path")
    }

    @Test
    fun clickingKeepsALongPathOpenAndScrollableInASmallWindow() = runDesktopComposeUiTest(width = 280, height = 180) {
        val name = "最终导出的完整配置文件.kt"
        val fullPath = "/workspace/" + "feature_implementation_directory/".repeat(70) + name
        val label = runBlocking { getString(Res.string.file_show_full_path) }
        setContent { PocketTheme { FilePathTitle(fullPath) } }
        onNodeWithContentDescription(label).performClick()
        waitForIdle()

        val viewport = onNode(hasScrollAction())
        val bounds = viewport.getBoundsInRoot()
        assertTrue(bounds.left.value >= 0 && bounds.right.value <= 280, "path details must fit the window width: $bounds")
        assertTrue(bounds.top.value >= 0 && bounds.bottom.value <= 180, "path details must fit the window height: $bounds")
        assertFalse(onNodeWithText(fullPath).textLayout().hasVisualOverflow, "all characters remain in the scrollable content")
        fun range() = viewport.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(range().maxValue() > 0, "the long path needs scrolling")
        viewport.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        assertTrue(range().value() >= range().maxValue() - 1, "scrolling must reach the end of the full path")

        onNode(isFocused() and !hasContentDescription(label)).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        onNodeWithText(fullPath).assertDoesNotExist()
        onNodeWithContentDescription(label).performClick()
        onNodeWithText(fullPath).assertExists()
    }

    @Test
    fun hoveringTheCopyButtonExplainsItAndStillLetsItBeClicked() {
        // resolve through the resource system, so the assertions follow whatever locale the JVM runs in
        val label = runBlocking { getString(Res.string.copy_path) }
        val tip = runBlocking { getString(Res.string.files_copy_full_path) }
        runDesktopComposeUiTest(width = 1000, height = 640) {
            setContent { PocketTheme { ChangesPanel(model(), onDismiss = {}) } }
            waitForIdle()

            onNodeWithText(tip).assertDoesNotExist() // nothing on screen before the pointer arrives
            onNodeWithContentDescription(label).performMouseInput { moveTo(center) }
            mainClock.advanceTimeBy(TOOLTIP_DELAY_MS + 200L)
            waitForIdle()
            onNodeWithText(tip).assertExists()

            // the control underneath still receives the click (the copy icon flips to a check mark)
            onNodeWithContentDescription(label).performClick()
            waitForIdle()
            onNodeWithContentDescription(label).assertDoesNotExist()
        }
    }

    @Test
    fun exportActionsExplainTheirPurposeOnHover() = runDesktopComposeUiTest(width = 1000, height = 640) {
        setContent { PocketTheme(dark = false) { ChangesPanel(model(), onDismiss = {}) } }
        waitForIdle()
        for ((control, tooltip) in listOf(
            Res.string.file_open to Res.string.files_open_default,
            Res.string.file_save_as to Res.string.file_save_as,
        )) {
            val label = runBlocking { getString(control) }
            val tip = runBlocking { getString(tooltip) }
            onNodeWithContentDescription(label).performMouseInput { moveTo(center) }
            mainClock.advanceTimeBy(TOOLTIP_DELAY_MS + 200L)
            waitForIdle()
            onNodeWithText(tip).assertExists()
        }
    }

    @Test
    fun wrapTooltipFollowsTheClickAndUnavailableDiffExplainsWhy() = runDesktopComposeUiTest(width = 1000, height = 640) {
        val fileOnly = object : DesktopModel by model() {
            override val selectedDiff = FileDiff("/srv/app", "s", path, ok = false)
        }
        setContent { PocketTheme { ChangesPanel(fileOnly, onDismiss = {}) } }
        waitForIdle()
        val wrap = runBlocking { getString(Res.string.diff_soft_wrap) }
        val on = runBlocking { getString(Res.string.file_tip_wrap_on) }
        val off = runBlocking { getString(Res.string.file_tip_wrap_off) }
        onNodeWithContentDescription(wrap).performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(TOOLTIP_DELAY_MS + 200L)
        waitForIdle()
        onNodeWithText(on).assertExists()
        onNodeWithContentDescription(wrap).performClick()
        waitForIdle()
        // Clicking dismisses the hover popup; a fresh hover must describe the new state.
        onRoot().performMouseInput { moveTo(Offset.Zero) }
        onNodeWithContentDescription(wrap).performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(TOOLTIP_DELAY_MS + 200L)
        waitForIdle()
        onNodeWithText(off).assertExists()
        onNodeWithText(on).assertDoesNotExist()
        val diff = runBlocking { getString(Res.string.diff_tab) }
        val unavailable = runBlocking { getString(Res.string.diff_none) }
        onNodeWithText(diff).performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(TOOLTIP_DELAY_MS + 200L)
        waitForIdle()
        onNodeWithText(unavailable).assertExists()
        onNodeWithText(diff).assertIsNotEnabled()
    }
}
