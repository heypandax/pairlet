package dev.ccpocket.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus

/**
 * The [TextToolbar] PocketTheme provides app-wide. Android/desktop return the platform toolbar
 * untouched; iOS wraps it in [ReshowingTextToolbar] — without it, 全选 (Select All) in a text
 * field's edit menu never yields the follow-up copy menu until some unrelated touch (a scroll)
 * happens to revive it.
 */
@Composable
expect fun rememberPlatformTextToolbar(): TextToolbar

/**
 * Hides the platform toolbar before any showMenu that changes the menu ITEM SET while the platform
 * still claims Shown — the signature of CMP 1.7.3 iOS's dismissed-but-not-forgotten edit menu.
 *
 * Mechanics (CMPEditMenuView.m, v1.7.3): performing a menu action makes UIKit auto-dismiss the
 * UIEditMenuInteraction menu, but the view's isEditMenuShown flag stays true and its hitTest-based
 * touch tracking never fires — the tap lands in the menu's own window, not the compose view. Select
 * All then mutates the selection and recomposition re-issues showMenu with the changed item set
 * (copy/cut appear, select-all drops), which the view routes to reloadVisibleMenu() /
 * updateVisibleMenuPositionAnimated() — both silent no-ops on a menu no longer on screen. Calling
 * hide() first destroys the stale interaction and forces the fresh-present path (the same
 * hide-then-show recovery CMP's own pre-iOS-16 branch does for every item change). Same-item calls
 * pass straight through, so genuine repositions of a truly visible menu keep the platform's
 * smooth no-teardown path.
 *
 * Platform-agnostic by construction (that is what makes it unit-testable from desktopTest) but only
 * installed on iOS: Android's ActionMode survives its own menu actions, so hide+show there would
 * only add flicker. Fixed upstream by the 1.8 selection rewrite — drop together with the CMP
 * upgrade (see ComposerState's version ledger).
 */
internal class ReshowingTextToolbar(private val platform: TextToolbar) : TextToolbar {

    /** Nullness bitmask of the last showMenu's callbacks — CMPEditMenuView's own items-changed test. */
    private var lastItems = -1

    override val status: TextToolbarStatus get() = platform.status

    override fun hide() = platform.hide()

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        val items = (if (onCopyRequested != null) 1 else 0) or
            (if (onPasteRequested != null) 2 else 0) or
            (if (onCutRequested != null) 4 else 0) or
            (if (onSelectAllRequested != null) 8 else 0)
        if (items != lastItems && platform.status == TextToolbarStatus.Shown) platform.hide()
        lastItems = items
        platform.showMenu(rect, onCopyRequested, onPasteRequested, onCutRequested, onSelectAllRequested)
    }
}
