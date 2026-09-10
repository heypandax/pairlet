package dev.ccpocket.app.desktop

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.rightClick
import dev.ccpocket.app.assertPresent
import dev.ccpocket.app.theme.PocketTheme
import kotlin.test.Test

/**
 * Issue #361 — right-clicking a path inside the chat stream crashed the desktop App with
 * `IllegalArgumentException: layouts are not part of the same hierarchy` (desktop.err.log, CCP-WIN-01,
 * 2026-09-09). The chat stream sits in ONE SelectionContainer; the app-wide context menu is a Popup, i.e.
 * a separate layout hierarchy — but the Text rows inside the menu still inherited the stream's
 * LocalSelectionRegistrar and registered as selectables. The next selection-gesture sort then asked for
 * the menu row's position relative to the stream's container and threw across hierarchies.
 *
 * This pins the shape: a ContextMenuArea inside a SelectionContainer, our representation installed, a
 * right-click to open the menu, then a drag-select in the stream while the menu is up. Before the fix
 * the drag crashes the composition; after it the menu rows are simply not selectables.
 */
@OptIn(ExperimentalTestApi::class)
class ContextMenuInSelectionTest {
    @Test
    fun aDragSelectWhileTheContextMenuIsOpenDoesNotCrash() = runComposeUiTest {
        setContent {
            PocketTheme {
                CompositionLocalProvider(LocalContextMenuRepresentation provides PocketContextMenuRepresentation) {
                    SelectionContainer {
                        Column {
                            Text("first line of the turn", Modifier.testTag("first"))
                            ContextMenuArea(items = { listOf(ContextMenuItem("Copy path") {}) }) {
                                Text("/Users/panda/proj/README.md", Modifier.testTag("path"))
                            }
                            Text("last line of the turn", Modifier.testTag("last"))
                        }
                    }
                }
            }
        }
        waitForIdle()

        // 1. a selection exists first (the user was reading / had text selected)
        onNodeWithTag("first").performMouseInput { moveTo(Offset(4f, 4f)); press(); moveTo(Offset(60f, 4f)) }
        onNodeWithTag("last").performMouseInput { moveTo(center); release() }
        waitForIdle()
        // 2. the menu opens: its rows subscribe to the stream's registrar (the bug), so the registrar is
        //    dirty and re-sorts on the next selection change
        onNodeWithTag("path").performMouseInput { rightClick(center) }
        waitForIdle()
        assertPresent("Copy path")
        // 3. any further selection gesture triggers the sort across hierarchies
        onNodeWithTag("first").performMouseInput { moveTo(Offset(4f, 4f)); press(); moveTo(Offset(30f, 4f)); moveTo(Offset(90f, 4f)) }
        onNodeWithTag("path").performMouseInput { moveTo(center) }
        onNodeWithTag("last").performMouseInput { moveTo(center); release() }
        waitForIdle()
    }
}
