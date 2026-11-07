package dev.ccpocket.app

import java.awt.Rectangle
import kotlin.test.assertEquals
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Issue #189: Windows closes to a reachable tray; every other configuration still exits normally. */
class MainWindowLifecycleTest {

    @Test
    fun restore_moves_partly_hidden_controls_below_the_menu_bar() {
        assertEquals(Rectangle(100, 25, 1180, 760),
            fitWindowToArea(Rectangle(100, -120, 1180, 760), Rectangle(0, 25, 1440, 850)))
    }

    @Test
    fun restore_shrinks_a_large_monitor_frame_to_the_laptop_screen() {
        assertEquals(Rectangle(0, 25, 1440, 850),
            fitWindowToArea(Rectangle(10, -100, 2180, 1100), Rectangle(0, 25, 1440, 850)))
    }

    @Test
    fun restore_handles_negative_monitor_origins_and_removed_displays() {
        assertEquals(Rectangle(-1200, -775, 1000, 700),
            fitWindowToArea(Rectangle(100, -900, 1000, 700), Rectangle(-1440, -775, 1240, 850)))
        assertEquals(Rectangle(440, 150, 1000, 700),
            fitWindowToArea(Rectangle(3000, 150, 1000, 700), Rectangle(0, 25, 1440, 850)))
    }

    @Test
    fun restore_preserves_a_fully_visible_frame() {
        val frame = Rectangle(120, 80, 1180, 760)
        assertEquals(frame, fitWindowToArea(frame, Rectangle(0, 25, 1440, 850)))
    }


    @Test
    fun windows_with_an_enabled_supported_tray_hides_instead_of_exiting() {
        assertTrue(shouldCloseMainWindowToTray(isWindows = true, menuBarEnabled = true, trayReady = true))
    }

    @Test
    fun no_tray_or_non_windows_never_strands_a_hidden_window() {
        assertFalse(shouldCloseMainWindowToTray(isWindows = true, menuBarEnabled = false, trayReady = true))
        assertFalse(shouldCloseMainWindowToTray(isWindows = true, menuBarEnabled = true, trayReady = false))
        assertFalse(shouldCloseMainWindowToTray(isWindows = false, menuBarEnabled = true, trayReady = true))
    }

    /** The sidebar's live-dot poll follows what the user can SEE — a #189 hidden window stays composed, so
     *  "still composed" is not "still visible" and the predicate has to say so. */
    @Test
    fun the_directory_poll_runs_only_for_a_window_on_screen() {
        assertTrue(shouldPollDirectories(windowVisible = true, minimized = false))
        assertFalse(shouldPollDirectories(windowVisible = false, minimized = false)) // closed to the tray
        assertFalse(shouldPollDirectories(windowVisible = true, minimized = true))
    }
}
