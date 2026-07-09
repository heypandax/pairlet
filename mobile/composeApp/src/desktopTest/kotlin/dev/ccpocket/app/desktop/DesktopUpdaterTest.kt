package dev.ccpocket.app.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure decision logic of the desktop self-updater (issue #87) — the install-source classification and the
 * per-platform asset naming that decide the "Check for updates" action. The effectful download/swap can't be
 * exercised without a real packaged app, so this covers the parts that gate it.
 */
class DesktopUpdaterTest {

    @Test
    fun install_source_from_the_app_path() {
        // a standalone dmg/msi lands the app in /Applications or Program Files — self-updatable
        assertEquals(DkInstallSource.STANDALONE, DesktopUpdater.classifyInstall("/Applications/CC Pocket.app"))
        assertEquals(DkInstallSource.STANDALONE, DesktopUpdater.classifyInstall("C:\\Program Files\\CC Pocket"))
        // package-manager copies are recognised by their tree and must NOT self-overwrite
        assertEquals(DkInstallSource.BREW, DesktopUpdater.classifyInstall("/opt/homebrew/Caskroom/cc-pocket/1.3.3/CC Pocket.app"))
        assertEquals(DkInstallSource.BREW, DesktopUpdater.classifyInstall("/usr/local/Cellar/cc-pocket/1.3.3/CC Pocket.app"))
        assertEquals(DkInstallSource.SCOOP, DesktopUpdater.classifyInstall("C:\\Users\\x\\scoop\\apps\\cc-pocket\\current\\CC Pocket"))
        // not a packaged app (dev / gradle run) — no self-update, fall back to the releases page
        assertEquals(DkInstallSource.UNKNOWN, DesktopUpdater.classifyInstall(null))
    }

    @Test
    fun desktop_asset_names_map_platforms() {
        // version-less names — the shape published on every release (see release.yml desktop upload steps)
        assertEquals("cc-pocket-desktop-macos-arm64.dmg", DesktopUpdater.desktopAssetFor("Mac OS X", "aarch64"))
        assertEquals("cc-pocket-desktop-macos-x86_64.dmg", DesktopUpdater.desktopAssetFor("Mac OS X", "x86_64"))
        assertEquals("cc-pocket-desktop-windows-x86_64.msi", DesktopUpdater.desktopAssetFor("Windows 11", "amd64"))
        // no artifacts published for these: Windows-on-arm, Linux, or an arch we don't build
        assertNull(DesktopUpdater.desktopAssetFor("Windows 11", "aarch64"))
        assertNull(DesktopUpdater.desktopAssetFor("Linux", "amd64"))
        assertNull(DesktopUpdater.desktopAssetFor("Mac OS X", "riscv64"))
    }

    @Test
    fun upgrade_commands_only_for_package_managers() {
        assertEquals("brew upgrade --cask heypandax/tap/cc-pocket", DesktopUpdater.upgradeCommandFor(DkInstallSource.BREW))
        assertEquals("scoop update cc-pocket", DesktopUpdater.upgradeCommandFor(DkInstallSource.SCOOP))
        assertNull(DesktopUpdater.upgradeCommandFor(DkInstallSource.STANDALONE)) // it self-updates
        assertNull(DesktopUpdater.upgradeCommandFor(DkInstallSource.UNKNOWN))
    }
}
