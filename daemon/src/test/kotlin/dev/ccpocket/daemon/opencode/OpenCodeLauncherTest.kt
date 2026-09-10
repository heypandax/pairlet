package dev.ccpocket.daemon.opencode

import dev.ccpocket.daemon.agent.ExecutableResolver
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenCodeLauncherTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun standalone_install_is_discovered_after_a_failed_probe_without_changing_path() {
        val home = Files.createDirectories(root.resolve("user with spaces"))
        // A unique name prevents the developer's PATH or compatibility symlinks from masking the bug.
        // This tests discovery only: the fixture is never executed or sent a model request.
        val name = "cc-pocket-opencode-install-fixture.exe"
        val dirs = OpenCodeLauncher.fallbackDirs(home.toString())
        fun resolve() = ExecutableResolver.resolve(null, null, listOf(name), dirs, "not installed")

        assertFailsWith<IllegalStateException> { resolve() }

        // The official standalone installer writes here, after the service may already be running.
        val bin = home.resolve(".opencode/bin").resolve(name)
        Files.createDirectories(bin.parent)
        Files.writeString(bin, "native binary fixture")
        assertTrue(bin.toFile().setExecutable(true))

        assertEquals(bin.toRealPath(), resolve())
    }
}
