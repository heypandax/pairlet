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
}
