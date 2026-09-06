package dev.ccpocket.app.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import org.jetbrains.compose.resources.stringResource

/**
 * The macOS application menu (issue #350) — the RENDERER for [appMenuSections].
 *
 * It owns no rules. Everything it shows (labels, order, accelerators, greyed items) comes from the pure
 * model, and everything it does is handed back out through [onAction] to the very lambdas the window's key
 * handler and the in-window buttons already call. That split is the point: the state table in the design
 * doc is asserted in `AppMenuModelTest`, and this file stays small enough to read in one screen.
 *
 * macOS only. The caller guards on the OS check the shell already does, so Windows/Linux mount nothing at
 * all and their key handling is byte-for-byte unchanged.
 *
 * The CC Pocket menu ([AppMenuSectionId.APP]) is deliberately NOT emitted as a Compose `Menu`: AppKit
 * already puts an application menu (About / Services / Hide / Quit) at the head of the bar, and drawing a
 * second one named "CC Pocket" beside it is exactly what the design forbids. Instead its three items are
 * wired to the NATIVE handlers via [installMacAppMenuHandlers], which is also what makes "Settings…" show
 * up in that menu with its ⌘, accelerator.
 */
@Composable
fun FrameWindowScope.MacAppMenuBar(model: DesktopModel, fullscreen: Boolean, onAction: (AppMenuAction, Int) -> Unit) {
    // The state fold happens HERE, inside this composable's own restart scope, and deliberately not at
    // the call site. Half of what the menu greys out (`streaming`, the selected session, the terminal's
    // dock state) moves during ordinary work, and the window host that would otherwise read it also
    // holds the whole shell — attributing these snapshot reads to the menu keeps a turn starting from
    // recomposing everything under it, the same reasoning as the remembered `chrome` in Main.
    MacAppMenuBar(appMenuSections(appMenuState(model, fullscreen)), onAction)
}

@Composable
fun FrameWindowScope.MacAppMenuBar(sections: List<AppMenuSection>, onAction: (AppMenuAction, Int) -> Unit) {
    MenuBar {
        sections.forEach { section ->
            val label = section.label ?: return@forEach // APP is native — see the KDoc above
            Menu(stringResource(label)) {
                section.entries.forEach { entry ->
                    when (entry) {
                        AppMenuSeparator -> Separator()
                        is AppMenuItem -> MenuLeaf(entry, onAction)
                        is AppMenuSubmenu -> Menu(stringResource(entry.label)) {
                            entry.items.forEach { MenuLeaf(it, onAction) }
                        }
                    }
                }
            }
        }
    }
}

/** One command row. A ✓ row uses the platform's own checkbox item so the mark is the system's, not ours. */
@Composable
private fun androidx.compose.ui.window.MenuScope.MenuLeaf(item: AppMenuItem, onAction: (AppMenuAction, Int) -> Unit) {
    val text = item.rawLabel ?: stringResource(item.label!!)
    if (item.checked) {
        // The active computer. Clicking it is a no-op by construction (the model disables it), but the
        // mark has to be a real checked item so VoiceOver reads "selected" rather than a decorated label.
        CheckboxItem(text, checked = true, enabled = item.enabled, onCheckedChange = {})
    } else {
        Item(text, enabled = item.enabled, shortcut = item.shortcut) { onAction(item.action, item.index) }
    }
}

/**
 * Wire the three commands AppKit's own application menu owns — About, Settings and Quit — through
 * `java.awt.Desktop`'s macOS handlers.
 *
 * Why handlers rather than menu items: AWT can REPLACE what the native About/Preferences/Quit entries do,
 * but it cannot add rows to that menu. Setting the preferences handler is also the only way to make
 * "Settings…" appear there at all — without it macOS shows About / Services / Hide / Quit and nothing else,
 * which is precisely the gap issue #350 opens with.
 *
 * Every call is best-effort: an unsupported action (any non-mac JDK, a headless run, a stripped image) is
 * skipped rather than thrown, so this can never become a launch failure. Handlers are removed on dispose so
 * a recomposition of the window host cannot leave a stale lambda holding a dead model.
 */
@Composable
fun InstallMacAppMenuHandlers(onAbout: () -> Unit, onSettings: () -> Unit, onQuit: () -> Unit) {
    // rememberUpdatedState: the handlers are installed ONCE (they are process-global AppKit state) but must
    // always call the freshest lambda — a machine switch replaces the model behind `onSettings`.
    val about by rememberUpdatedState(onAbout)
    val settings by rememberUpdatedState(onSettings)
    val quit by rememberUpdatedState(onQuit)
    DisposableEffect(Unit) {
        val desktop = runCatching { java.awt.Desktop.getDesktop() }.getOrNull()
        fun supports(a: java.awt.Desktop.Action) = runCatching { desktop?.isSupported(a) == true }.getOrDefault(false)
        if (supports(java.awt.Desktop.Action.APP_ABOUT)) {
            runCatching { desktop?.setAboutHandler { java.awt.EventQueue.invokeLater { about() } } }
        }
        if (supports(java.awt.Desktop.Action.APP_PREFERENCES)) {
            runCatching { desktop?.setPreferencesHandler { java.awt.EventQueue.invokeLater { settings() } } }
        }
        if (supports(java.awt.Desktop.Action.APP_QUIT_HANDLER)) {
            runCatching {
                desktop?.setQuitHandler { _, response ->
                    // Leave through OUR exit path (the same one ⌘Q and the close button use) and then tell
                    // AppKit the quit was handled, so the OS never force-terminates us mid-teardown.
                    java.awt.EventQueue.invokeLater { quit() }
                    runCatching { response.performQuit() }
                }
            }
        }
        onDispose {
            runCatching { if (supports(java.awt.Desktop.Action.APP_ABOUT)) desktop?.setAboutHandler(null) }
            runCatching { if (supports(java.awt.Desktop.Action.APP_PREFERENCES)) desktop?.setPreferencesHandler(null) }
            runCatching { if (supports(java.awt.Desktop.Action.APP_QUIT_HANDLER)) desktop?.setQuitHandler(null) }
        }
    }
}

/**
 * Hand an 编辑 command back to whatever component owns the keyboard, by re-playing its own keystroke.
 *
 * This is the honest implementation of a native Edit menu over a Compose window. Compose text fields, the
 * sidebar's rename field and JediTerm each implement editing themselves and share no AWT editing API; what
 * they DO share is that all three already handle ⌘C / ⌘V / ⌘Z. Once the menu accelerator exists, AppKit
 * consumes that keystroke before the window sees it — so the item's whole job is to give it back, to the
 * focus owner only. Nothing here interprets "copy" globally: no transcript copy, no agent rollback.
 *
 * Dispatch goes straight to the focused component, which bypasses the menu-accelerator layer entirely, so
 * the forwarded event cannot re-enter this handler. Best-effort by design: with no focus owner there is
 * nothing to edit and this is a no-op.
 */
internal fun dispatchEditAction(action: AppMenuAction, focusOwner: java.awt.Component?) {
    val (keyCode, modifiers) = editKeyStroke(action) ?: return
    val target = focusOwner ?: return
    val now = System.currentTimeMillis()
    listOf(java.awt.event.KeyEvent.KEY_PRESSED, java.awt.event.KeyEvent.KEY_RELEASED).forEach { id ->
        runCatching {
            target.dispatchEvent(
                java.awt.event.KeyEvent(
                    target, id, now, modifiers, keyCode,
                    java.awt.event.KeyEvent.CHAR_UNDEFINED,
                    java.awt.event.KeyEvent.KEY_LOCATION_STANDARD,
                ),
            )
        }
    }
}
