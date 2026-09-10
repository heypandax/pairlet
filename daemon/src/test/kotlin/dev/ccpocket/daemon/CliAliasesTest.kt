package dev.ccpocket.daemon

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

class CliAliasesTest {
    @TempDir lateinit var dir: Path
    private val windows get() = System.getProperty("os.name").lowercase().contains("win")

    @Test fun unix_alias_follows_upgrade_and_forwards_arguments_and_exit_code() {
        assumeFalse(windows)
        val bin = Files.createDirectories(dir.resolve("with spaces/bin"))
        val launcher = bin.resolve("cc-pocket-daemon")
        for (version in listOf("old", "new")) {
            val exe = dir.resolve(version)
            Files.writeString(exe, "#!/bin/sh\nprintf '$version\\n'\nprintf '<%s>\\n' \"\$@\"\nexit 23\n")
            assertTrue(exe.toFile().setExecutable(true))
            val pending = bin.resolve(".switch")
            Files.createSymbolicLink(pending, exe)
            Files.move(pending, launcher, ATOMIC_MOVE, REPLACE_EXISTING)
            repeat(2) { CliAliases.ensureAlias(launcher, windows = false) }
            val alias = bin.resolve("pairlet")
            assertTrue(Files.isRegularFile(alias))
            assertFalse(Files.isSymbolicLink(alias))
            for (command in listOf(launcher, alias)) {
                val process = ProcessBuilder(command.toString(), "pair", "two words", "", "a&b").start()
                assertEquals("$version\n<pair>\n<two words>\n<>\n<a&b>\n", process.inputStream.bufferedReader().readText())
                assertEquals(23, process.waitFor())
            }
        }
    }

    @Test fun existing_commands_and_dangling_links_are_never_overwritten() {
        assumeFalse(windows)
        val launcher = Files.writeString(dir.resolve("cc-pocket-daemon"), "legacy")
        val alias = Files.writeString(dir.resolve("pairlet"), "another tool")
        assertFailsWith<IllegalStateException> { CliAliases.ensureAlias(launcher, windows = false) }
        assertEquals("another tool", Files.readString(alias))
        Files.delete(alias)
        Files.createSymbolicLink(alias, Path.of("missing-other-tool"))
        assertFailsWith<IllegalStateException> { CliAliases.ensureAlias(launcher, windows = false) }
        assertEquals(Path.of("missing-other-tool"), Files.readSymbolicLink(alias))
    }

    @Test fun old_managed_install_gets_alias_on_first_start_but_package_managers_do_not() {
        assumeFalse(windows)
        val home = Files.createDirectories(dir.resolve("home"))
        val exe = home.resolve(".local/share/cc-pocket/versions/1.9.8/cc-pocket-daemon/bin/cc-pocket-daemon")
        Files.createDirectories(exe.parent)
        Files.writeString(exe, "legacy")
        val bin = Files.createDirectories(home.resolve(".local/bin"))
        Files.createSymbolicLink(bin.resolve("cc-pocket-daemon"), exe)
        CliAliases.ensureForManagedInstall(dir.resolve("Caskroom/cc-pocket/bin/cc-pocket-daemon"), home)
        assertFalse(Files.exists(bin.resolve("pairlet")))
        repeat(2) { CliAliases.ensureForManagedInstall(exe, home) }
        assertEquals(CliAliases.UNIX_SHIM, Files.readString(bin.resolve("pairlet")))
        assertTrue(Files.isExecutable(bin.resolve("pairlet")))
    }

    @Test fun windows_shim_is_stable_on_retry_and_preserves_other_commands() {
        val launcher = Files.writeString(dir.resolve("cc-pocket-daemon.cmd"), "legacy shim")
        repeat(2) { CliAliases.ensureAlias(launcher, windows = true) }
        val alias = dir.resolve("pairlet.cmd")
        val original = Files.readString(alias)
        Files.writeString(launcher, "upgraded shim")
        CliAliases.ensureAlias(launcher, windows = true)
        assertEquals(original, Files.readString(alias))
        Files.writeString(alias, "another command")
        assertFailsWith<IllegalStateException> { CliAliases.ensureAlias(launcher, windows = true) }
        assertEquals("another command", Files.readString(alias))
    }

    @Test fun windows_cmd_forwards_arguments_and_exit_code_after_upgrade() {
        assumeTrue(windows)
        val bin = Files.createDirectories(dir.resolve("space 中文"))
        val launcher = bin.resolve("cc-pocket-daemon.cmd")
        for (version in listOf("old", "new")) {
            Files.writeString(launcher, "@echo off\r\n@echo $version \"%~1\" \"%~2\"\r\n@exit /b 23\r\n")
            CliAliases.ensureAlias(launcher, windows = true)
            Files.writeString(bin.resolve("invoke.cmd"), "@echo off\r\n@call pairlet.cmd \"two words\" \"a&b\"\r\n@exit /b %errorlevel%\r\n")
            val process = ProcessBuilder("cmd.exe", "/d", "/u", "/c", "invoke.cmd")
                .directory(bin.toFile()).start()
            assertEquals("$version \"two words\" \"a&b\"", process.inputStream.readBytes().toString(Charsets.UTF_16LE).trim())
            assertEquals(23, process.waitFor())
        }
    }
}
