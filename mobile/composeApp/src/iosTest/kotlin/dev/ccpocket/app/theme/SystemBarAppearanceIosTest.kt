package dev.ccpocket.app.theme

import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIWindow
import kotlin.test.Test
import kotlin.test.assertEquals

class SystemBarAppearanceIosTest {
    // SYSTEM must leave the window alone: `isSystemInDarkTheme()` reads the Compose view controller's trait
    // collection, which inherits the window's override — any pin would feed back into the SYSTEM resolution.
    @Test fun systemInheritsTheOsAndOnlyForcedPicksPinTheWindow() {
        assertEquals(UIUserInterfaceStyle.UIUserInterfaceStyleUnspecified, ThemeMode.SYSTEM.uiUserInterfaceStyle())
        assertEquals(UIUserInterfaceStyle.UIUserInterfaceStyleLight, ThemeMode.LIGHT.uiUserInterfaceStyle())
        assertEquals(UIUserInterfaceStyle.UIUserInterfaceStyleDark, ThemeMode.DARK.uiUserInterfaceStyle())
    }

    // The reported bug in miniature: a window pinned dark (the DARK default, or the old Swift-side
    // `.preferredColorScheme(.dark)`) must be released when the user picks SYSTEM, or the OS flip never
    // reaches Compose and "follow system" stays dark through flips and relaunches.
    @Test fun pickingSystemReleasesAWindowPinnedDark() {
        val window = UIWindow()
        applyInterfaceStyle(ThemeMode.DARK.uiUserInterfaceStyle(), listOf(window))
        assertEquals(UIUserInterfaceStyle.UIUserInterfaceStyleDark, window.overrideUserInterfaceStyle)

        applyInterfaceStyle(ThemeMode.SYSTEM.uiUserInterfaceStyle(), listOf(window))
        assertEquals(UIUserInterfaceStyle.UIUserInterfaceStyleUnspecified, window.overrideUserInterfaceStyle)

        applyInterfaceStyle(ThemeMode.LIGHT.uiUserInterfaceStyle(), listOf(window))
        assertEquals(UIUserInterfaceStyle.UIUserInterfaceStyleLight, window.overrideUserInterfaceStyle)
    }
}
