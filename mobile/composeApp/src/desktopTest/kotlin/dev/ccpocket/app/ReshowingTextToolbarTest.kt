package dev.ccpocket.app

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks the zombie-menu recovery contract of [ReshowingTextToolbar] (iOS CMP 1.7.3: the edit menu
 * UIKit auto-dismissed on 全选 is still "Shown" to CMPEditMenuView, so the follow-up showMenu with
 * the new item set silently no-ops and no copy button appears until a stray touch revives it).
 *
 * The fake mimics the platform's lying flag: status stays Shown after showMenu even though UIKit
 * would have dismissed the menu on the action tap — exactly the state the wrapper must detect.
 */
class ReshowingTextToolbarTest {

    private class FakePlatformToolbar : TextToolbar {
        val calls = mutableListOf<String>()
        override var status: TextToolbarStatus = TextToolbarStatus.Hidden
        override fun hide() { calls += "hide"; status = TextToolbarStatus.Hidden }
        override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?,
        ) { calls += "show"; status = TextToolbarStatus.Shown }
    }

    private val none: (() -> Unit)? = null
    private val some: () -> Unit = {}
    private val rect = Rect(0f, 0f, 10f, 10f)

    @Test
    fun select_all_from_a_word_selection_forces_hide_then_show() {
        val platform = FakePlatformToolbar()
        val toolbar = ReshowingTextToolbar(platform)
        // word selected: copy/cut/paste/selectAll all offered
        toolbar.showMenu(rect, some, some, some, some)
        // 全选 tapped: UIKit dismissed the menu (platform still says Shown); recomposition re-shows
        // with selectAll gone — the wrapper must destroy the stale interaction first
        toolbar.showMenu(rect, some, some, some, none)
        assertEquals(listOf("show", "hide", "show"), platform.calls)
    }

    @Test
    fun select_all_from_a_collapsed_cursor_forces_hide_then_show() {
        val platform = FakePlatformToolbar()
        val toolbar = ReshowingTextToolbar(platform)
        // cursor menu: paste + selectAll only
        toolbar.showMenu(rect, none, some, none, some)
        // after 全选 the selection is non-collapsed: copy/cut appear, selectAll drops
        toolbar.showMenu(rect, some, some, some, none)
        assertEquals(listOf("show", "hide", "show"), platform.calls)
    }

    @Test
    fun same_items_reposition_passes_through_without_teardown() {
        val platform = FakePlatformToolbar()
        val toolbar = ReshowingTextToolbar(platform)
        toolbar.showMenu(rect, some, some, some, some)
        // scroll under a genuinely visible menu: same items, new rect — must NOT hide (flicker)
        toolbar.showMenu(Rect(0f, 20f, 10f, 30f), some, some, some, some)
        assertEquals(listOf("show", "show"), platform.calls)
    }

    @Test
    fun item_change_while_hidden_shows_without_an_extra_hide() {
        val platform = FakePlatformToolbar()
        val toolbar = ReshowingTextToolbar(platform)
        toolbar.showMenu(rect, some, some, some, some)
        toolbar.hide() // handle drag start etc. — CMP hid it properly
        toolbar.showMenu(rect, some, some, some, none)
        assertEquals(listOf("show", "hide", "show"), platform.calls)
    }
}
