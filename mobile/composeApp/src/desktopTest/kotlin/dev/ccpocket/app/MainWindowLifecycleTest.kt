package dev.ccpocket.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Issue #189: Windows closes to a reachable tray; every other configuration still exits normally. */
class MainWindowLifecycleTest {

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
