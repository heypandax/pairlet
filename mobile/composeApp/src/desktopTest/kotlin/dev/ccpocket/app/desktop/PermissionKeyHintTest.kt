package dev.ccpocket.app.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ccpocket.app.assertPresent
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.allow
import dev.ccpocket.app.resources.allow_for_task
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.PermissionAsk
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * Audit 2026-10-04 (desktop M1): the approval buttons promise no shortcut that does not exist.
 *
 * Allow and Allow-for-task carried a ⌘⏎ keycap, but nothing in the window binds ⌘⏎ to an approval: with
 * the card up the composer holds focus, and its Enter handler sends the DRAFT as a prompt instead (the
 * only ⌘⏎ handler in the app raises the main window from the menu-bar popover). Same rule as the tray's
 * `keyHint`: a keycap is shown only where the key is wired.
 */
@OptIn(ExperimentalTestApi::class)
class PermissionKeyHintTest {
    private val ask = PermissionAsk("c1", "ask-1", "Bash", "./gradlew test", title = "Run command", rule = "Bash(./gradlew:*)")

    @Test
    fun theLegacyInlineCardShowsNoUnwiredShortcut() = runComposeUiTest {
        setContent { PocketTheme { InlinePermCard(ask, AgentKind.CLAUDE, "~/w", null, onAllow = {}, onDeny = {}) } }
        assertPresent(str(Res.string.allow))
        assertFalse(present("⌘⏎"), "⌘⏎ is not bound to Allow")
    }

    @Test
    fun theTaskGrantCardShowsNoUnwiredShortcut() = runComposeUiTest {
        setContent {
            PocketTheme {
                InlinePermCard(
                    ask.copy(grantOptions = listOf("once", "task", "session")), AgentKind.CLAUDE, "~/w", null,
                    onAllow = {}, onDeny = {}, onAllowTask = {}, onRetrySafer = {},
                )
            }
        }
        assertPresent(str(Res.string.allow_for_task))
        assertFalse(present("⌘⏎"), "⌘⏎ is not bound to Allow for task")
    }

    @Test
    fun theFocusedModalShowsNoUnwiredShortcut() = runComposeUiTest {
        setContent {
            PocketTheme { FocusedModal("devbox", ask, AgentKind.CLAUDE, "~/w", null, onAllow = {}, onDeny = {}, onDismiss = {}) }
        }
        assertPresent(str(Res.string.allow))
        assertFalse(present("⌘⏎"), "⌘⏎ is not bound to the modal's Allow")
    }
}
