package dev.ccpocket.daemon.zcode

import dev.ccpocket.daemon.agent.AgentSpec
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ZCodeLauncherTest {
    @Test
    fun `official cjs launches through bundle electron instead of its path node shebang`() {
        val root = Files.createTempDirectory("zcode-app")
        val contents = root.resolve("ZCode.app/Contents")
        val electron = contents.resolve("MacOS/ZCode").also { it.parent.createDirectories(); it.createFile() }
        Files.setPosixFilePermissions(electron, PosixFilePermissions.fromString("rwx------"))
        val cjs = contents.resolve("Resources/glm/zcode.cjs").also { it.parent.createDirectories(); it.createFile() }

        val pb = ZCodeLauncher.processBuilder(cjs, AgentSpec(root))
        assertEquals(listOf(electron.toString(), cjs.toString(), "app-server", "--stdio"), pb.command())
        assertEquals("1", pb.environment()["ELECTRON_RUN_AS_NODE"])
    }

    @Test
    fun `orphan cjs fails instead of falling through to an arbitrary path node`() {
        val root = Files.createTempDirectory("zcode-orphan")
        val cjs = root.resolve("zcode.cjs").also { it.createFile() }
        val error = assertFailsWith<IllegalStateException> {
            ZCodeLauncher.processBuilder(cjs, AgentSpec(root))
        }
        assertTrue(error.message.orEmpty().contains("bundled Electron runtime"))
    }

    @Test
    fun `official bundle fallback includes glm entry directory`() {
        assertTrue(ZCodeLauncher.fallbackDirs("/Users/test", "Mac OS X").contains("/Applications/ZCode.app/Contents/Resources/glm"))
    }

    @Test
    fun `official linux server agent fallback is discovered`() {
        assertTrue(
            ZCodeLauncher.fallbackDirs("/home/panda", "Linux")
                .contains("/home/panda/.zcode/server/agents/glm"),
        )
    }

    @Test
    fun `registry output yields the recorded install directory once`() {
        val output = """
            HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Uninstall\a1b2c3d4-zcode
                DisplayName    REG_SZ    ZCode
                DisplayIcon    REG_SZ    D:\Apps\ZCode\ZCode.exe,0
                InstallLocation    REG_SZ    D:\Apps\ZCode
                Publisher    REG_SZ    Z.ai

            End of search: 3 match(es) found.
        """.trimIndent()

        assertEquals(listOf("D:\\Apps\\ZCode"), ZCodeLauncher.parseRegistryInstallLocations(output))
    }

    @Test
    fun `registry lookup is skipped off windows`() {
        assertTrue(
            ZCodeLauncher.registryInstallLocations(
                query = { "    InstallLocation    REG_SZ    D:\\Apps\\ZCode" },
                osName = "Mac OS X",
            ).isEmpty(),
        )
    }

    @Test
    fun `registry lookup queries the uninstall hives on windows`() {
        val queried = mutableListOf<List<String>>()
        val dirs = ZCodeLauncher.registryInstallLocations(
            query = { argv ->
                queried += argv
                "    InstallLocation    REG_SZ    D:\\Apps\\ZCode\n"
            },
            osName = "Windows 11",
        )

        assertEquals(listOf("D:\\Apps\\ZCode"), dirs)
        assertEquals(3, queried.size)
        assertTrue(queried.all { it.first() == "reg.exe" && it.containsAll(listOf("query", "/s", "/f", "ZCode", "/d")) })
        assertTrue(queried.any { it.any { arg -> arg.startsWith("HKCU\\Software\\Microsoft") } })
        assertTrue(queried.any { it.any { arg -> arg.startsWith("HKLM\\Software\\WOW6432Node") } })
    }

    @Test
    fun `custom install directory from the registry becomes a probe candidate`() {
        val dirs = ZCodeLauncher.fallbackDirs(
            home = "C:\\Users\\t",
            osName = "Windows 11",
            localAppData = "C:\\Users\\t\\AppData\\Local",
            programFiles = "C:\\Program Files",
            programFilesX86 = "C:\\Program Files (x86)",
            appData = "C:\\Users\\t\\AppData\\Roaming",
            registryDirs = listOf("D:\\Apps\\ZCode"),
        )

        assertTrue(dirs.contains("D:\\Apps\\ZCode\\resources\\glm"), "missing registry glm dir in $dirs")
        assertTrue(dirs.contains("C:\\Users\\t\\AppData\\Roaming\\npm"), "missing npm global bin in $dirs")
        assertTrue(dirs.contains("C:\\Program Files (x86)\\ZCode\\resources\\glm"), "missing x86 dir in $dirs")
    }

    @Test
    fun `programs directory scan finds a version flavoured bundle name`() {
        val localAppData = Files.createTempDirectory("zcode-localappdata")
        val bundle = localAppData.resolve("Programs").resolve("zcode-desktop")
        bundle.resolve("resources").resolve("glm").createDirectories()

        val dirs = ZCodeLauncher.fallbackDirs(
            home = "C:\\Users\\t",
            osName = "Windows 11",
            localAppData = localAppData.toString(),
            programFiles = null,
            programFilesX86 = null,
            appData = null,
            registryDirs = emptyList(),
        )

        assertTrue(
            dirs.contains(bundle.resolve("resources").resolve("glm").toString()),
            "scanned bundle missing from $dirs",
        )
    }
}
