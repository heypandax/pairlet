package dev.ccpocket.daemon.zcode

import dev.ccpocket.daemon.agent.AgentSpec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZCodeLauncherTest {
    private val windowsHost = System.getProperty("os.name").lowercase().contains("win")

    /**
     * An official bundle in THIS host's own layout, returning `electron to zcode.cjs`. The launcher reads
     * `os.name` at class-init to decide which runtime file name to look for, so a fixture hard-coded to one
     * platform can only ever pass on that platform — the macOS-only shape used to fail the whole suite on
     * Windows, which is the platform issue #386 is about.
     */
    private fun officialBundle(root: Path): Pair<Path, Path> = if (windowsHost) {
        val electron = root.resolve("ZCode.exe").also { it.parent.createDirectories(); it.createFile() }
        val cjs = root.resolve("resources/glm/zcode.cjs").also { it.parent.createDirectories(); it.createFile() }
        electron to cjs
    } else {
        val contents = root.resolve("ZCode.app/Contents")
        val electron = contents.resolve("MacOS/ZCode").also { it.parent.createDirectories(); it.createFile() }
        Files.setPosixFilePermissions(electron, PosixFilePermissions.fromString("rwx------"))
        val cjs = contents.resolve("Resources/glm/zcode.cjs").also { it.parent.createDirectories(); it.createFile() }
        electron to cjs
    }

    @Test
    fun `official cjs launches through bundle electron instead of its path node shebang`() {
        val root = Files.createTempDirectory("zcode-app")
        val (electron, cjs) = officialBundle(root)

        val pb = ZCodeLauncher.processBuilder(cjs, AgentSpec(root))
        assertEquals(listOf(electron.toString(), cjs.toString(), "app-server", "--stdio"), pb.command())
        assertEquals("1", pb.environment()["ELECTRON_RUN_AS_NODE"])
    }

    @Test
    fun `a complete bundle answers with its node entry so the native agent binary never wins`() {
        val root = Files.createTempDirectory("zcode-both")
        val (_, cjs) = officialBundle(root)
        // The optional native artefact ZCode also ships in this very directory (`zcode-agent(.exe)`).
        cjs.parent.resolve(if (windowsHost) "zcode-agent.exe" else "zcode-agent").createFile()

        assertEquals(cjs.toRealPath(), ZCodeLauncher.bundledNodeEntry(cjs.parent))
    }

    @Test
    fun `a server agent directory without electron keeps its native binary`() {
        val agents = Files.createTempDirectory("zcode-server").resolve(".zcode/server/agents/glm")
        agents.createDirectories()
        agents.resolve("zcode.cjs").createFile()
        agents.resolve(if (windowsHost) "zcode-agent.exe" else "zcode-agent").createFile()

        assertNull(ZCodeLauncher.bundledNodeEntry(agents))
    }

    @Test
    fun `a wrapper directory without a node entry is left alone`() {
        val bin = Files.createTempDirectory("zcode-wrapper").resolve("bin")
        bin.createDirectories()

        assertNull(ZCodeLauncher.bundledNodeEntry(bin))
        assertNull(ZCodeLauncher.bundledNodeEntry(null))
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
        // Built with Path.of in production, so the separator is the HOST's — spell the expectation the same
        // way rather than hard-coding "/", which made this Linux assertion unsatisfiable on a Windows host.
        val expected = Path.of("/home/panda", ".zcode", "server", "agents", "glm").toString()
        assertTrue(ZCodeLauncher.fallbackDirs("/home/panda", "Linux").contains(expected))
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
    fun `registry subkeys are read off the search listing`() {
        val listing = """
            |
            |HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Uninstall\5d2a-zcode
            |    DisplayName    REG_SZ    ZCode
            |
            |HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Uninstall\other\nested
            |    DisplayName    REG_SZ    ZCode Helper
            |
            |End of search: 2 match(es) found.
        """.trimMargin()
        assertEquals(
            listOf("HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\5d2a-zcode"),
            ZCodeLauncher.parseRegistrySubkeys(listing, "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall"),
        )
    }

    @Test
    fun `registry lookup queries the uninstall hives on windows in two passes`() {
        val queried = mutableListOf<List<String>>()
        val dirs = ZCodeLauncher.registryInstallLocations(
            query = { argv ->
                queried += argv
                if ("/f" in argv) {
                    // pass 1: the search listing names the matching subkey (DisplayName hit, path has no "ZCode")
                    val hive = argv[2]
                    if (hive.startsWith("HKCU")) "HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\5d2a-zcode\n    DisplayName    REG_SZ    ZCode\n" else ""
                } else {
                    // pass 2: the full subkey carries the install path
                    "    DisplayName    REG_SZ    ZCode\n    InstallLocation    REG_SZ    D:\\Apps\\Zai\n"
                }
            },
            osName = "Windows 11",
        )

        assertEquals(listOf("D:\\Apps\\Zai"), dirs)
        val searches = queried.filter { "/f" in it }
        val fulls = queried.filter { "/f" !in it }
        assertEquals(3, searches.size)
        assertTrue(searches.all { it.first() == "reg.exe" && it.containsAll(listOf("query", "/s", "/f", "ZCode")) && "/d" !in it })
        assertTrue(searches.any { it.any { arg -> arg.startsWith("HKCU\\Software\\Microsoft") } })
        assertTrue(searches.any { it.any { arg -> arg.startsWith("HKLM\\Software\\WOW6432Node") } })
        assertEquals(listOf(listOf("reg.exe", "query", "HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\5d2a-zcode")), fulls)
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
