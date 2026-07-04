package dev.ccpocket.daemon.update

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateServiceTest {

    @Test
    fun is_newer_compares_dotted_numerics() {
        assertTrue(UpdateService.isNewer("1.2.1", "1.2.0"))
        assertTrue(UpdateService.isNewer("1.10.0", "1.9.9")) // numeric, not lexicographic
        assertTrue(UpdateService.isNewer("v2.0.0", "1.9.9")) // v prefix ignored
        assertFalse(UpdateService.isNewer("1.2.0", "1.2.0"))
        assertFalse(UpdateService.isNewer("1.1.9", "1.2.0")) // downgrade is not "newer"
        assertTrue(UpdateService.isNewer("1.2.0.1", "1.2.0")) // extra segment wins
    }

    @Test
    fun asset_names_map_platforms() {
        assertEquals("cc-pocket-daemon-1.2.0-macos-arm64.tar.gz", UpdateService.assetNameFor("1.2.0", "Mac OS X", "aarch64"))
        assertEquals("cc-pocket-daemon-1.2.0-macos-x86_64.tar.gz", UpdateService.assetNameFor("v1.2.0", "Mac OS X", "x86_64"))
        assertEquals("cc-pocket-daemon-1.2.0-windows-x86_64.zip", UpdateService.assetNameFor("1.2.0", "Windows 11", "amd64"))
        assertEquals("cc-pocket-daemon-1.2.0-linux-x86_64.tar.gz", UpdateService.assetNameFor("1.2.0", "Linux", "amd64"))
        assertEquals("cc-pocket-daemon-1.2.0-linux-arm64.tar.gz", UpdateService.assetNameFor("1.2.0", "Linux", "aarch64"))
        assertNull(UpdateService.assetNameFor("1.2.0", "Linux", "riscv64"))
    }

    @Test
    fun managed_install_detects_the_versions_layout_only() {
        val home = Path.of("/Users/x")
        val managed = UpdateService.managedInstallOf(
            Path.of("/Users/x/.local/share/cc-pocket/versions/1.2.0/bin/cc-pocket-daemon"), home,
        )
        assertEquals(Path.of("/Users/x/.local/share/cc-pocket/versions"), managed?.versionsDir)

        // package-manager and dev builds are NOT ours to update
        assertNull(UpdateService.managedInstallOf(Path.of("/opt/homebrew/Caskroom/cc-pocket/1.2.0/cc-pocket-daemon/bin/cc-pocket-daemon"), home))
        assertNull(UpdateService.managedInstallOf(Path.of("/Users/x/Desktop/proj/daemon/build/install/cc-pocket-daemon/bin/cc-pocket-daemon"), home))
        assertNull(UpdateService.managedInstallOf(null, home))
    }

    @Test
    fun owner_hints_name_the_right_package_manager() {
        assertTrue("brew upgrade" in UpdateService.ownerHint(Path.of("/opt/homebrew/Caskroom/cc-pocket/1.2.0/bin/cc-pocket-daemon")))
        assertTrue("scoop update" in UpdateService.ownerHint(Path.of("C:\\Users\\x\\scoop\\apps\\cc-pocket-daemon\\1.2.0\\cc-pocket-daemon.exe")))
        assertTrue("one-liner" in UpdateService.ownerHint(Path.of("/somewhere/else/cc-pocket-daemon")))
    }

    @Test
    fun sha256sums_parsing_is_strict_about_the_hash_and_tolerant_about_markers() {
        val sums = UpdateService.parseSums(
            """
            56e51fe0f9674df9d8b246fabf8188c1dd669d7c106930ed4077e3c94e3b60ef  cc-pocket-daemon-1.2.0-windows-x86_64.zip
            8ee8fea9b9b8326acd5bfc04e3340b923eaf0d3b22e91d4eea1b67b429842c60 *cc-pocket-daemon-1.2.0-macos-x86_64.tar.gz
            not-a-hash  garbage.bin
            """.trimIndent(),
        )
        assertEquals("56e51fe0f9674df9d8b246fabf8188c1dd669d7c106930ed4077e3c94e3b60ef", sums["cc-pocket-daemon-1.2.0-windows-x86_64.zip"])
        assertEquals("8ee8fea9b9b8326acd5bfc04e3340b923eaf0d3b22e91d4eea1b67b429842c60", sums["cc-pocket-daemon-1.2.0-macos-x86_64.tar.gz"])
        assertNull(sums["garbage.bin"])
    }
}
