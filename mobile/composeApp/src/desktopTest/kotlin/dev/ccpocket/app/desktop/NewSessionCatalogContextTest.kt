package dev.ccpocket.app.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.AgentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The desktop new-session popover names the directory typed INSIDE it when it asks for a catalog (Codex catalog
 * cache): the catalog must be project B's, not the open chat's. Typing is debounced — one request per settled
 * path, not one per keystroke.
 */
@OptIn(ExperimentalTestApi::class)
class NewSessionCatalogContextTest {

    @Test
    fun the_catalog_context_follows_the_typed_directory_debounced() = runDesktopComposeUiTest(420, 1400) {
        val contexts = mutableListOf<Pair<AgentKind, String>>()
        val refreshes = mutableListOf<Pair<AgentKind, String>>()
        mainClock.autoAdvance = false
        setContent {
            PocketTheme {
                NewSessionPopover(
                    initialPath = "/w/a",
                    defaultAgent = AgentKind.CODEX,
                    availableAgents = listOf(AgentKind.CLAUDE, AgentKind.CODEX),
                    onCatalogContext = { a, d -> contexts += a to d },
                    onRefreshModels = { a, d -> refreshes += a to d },
                    onStart = { _, _, _, _, _, _ -> },
                )
            }
        }
        mainClock.advanceTimeByFrame(); waitForIdle()
        assertEquals(listOf(AgentKind.CODEX to "/w/a"), contexts, "opening asks for the initial directory at once")

        // typing a new path: nothing until it settles
        onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("/w/b")
        mainClock.advanceTimeBy(CATALOG_PATH_DEBOUNCE_MS / 2); waitForIdle()
        assertEquals(1, contexts.size, "no request per keystroke")
        mainClock.advanceTimeBy(CATALOG_PATH_DEBOUNCE_MS); waitForIdle()
        assertEquals(AgentKind.CODEX to "/w/b", contexts.last(), "the settled path is the catalog's target")
        assertEquals(2, contexts.size)

        // a relative fragment is not a directory the daemon could scope by: no request
        onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("proj")
        mainClock.advanceTimeBy(CATALOG_PATH_DEBOUNCE_MS * 2); waitForIdle()
        assertEquals(2, contexts.size)
        assertTrue(refreshes.isEmpty())
    }
}
