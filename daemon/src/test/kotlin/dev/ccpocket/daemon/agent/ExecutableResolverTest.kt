package dev.ccpocket.daemon.agent

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Which file the resolver actually picks. The fixture names are unique so the machine's real PATH can
 * never match them — only the [fallbackDirs] passed in do, which is what lets this run identically on
 * a dev Mac and on the Linux CI runner.
 */
class ExecutableResolverTest {

    private val root = Files.createTempDirectory("ccp-exeres")

    /** A runnable file. [body] null = a native binary (no `#!`), otherwise the script's first line. */
    private fun bin(dir: String, name: String, body: String? = null): Path {
        val d = Files.createDirectories(root.resolve(dir))
        val f = d.resolve(name)
        Files.write(f, (body ?: "\u007FELF fake native binary").toByteArray())
        f.toFile().setExecutable(true)
        return f
    }

    private fun resolve(explicit: String? = null, envBin: String? = null, names: List<String>, dirs: List<Path>) =
        ExecutableResolver.resolve(explicit, envBin, names, dirs.map { it.toString() }, "nothing found")

    @Test
    fun a_native_exe_beats_a_cmd_shim_that_sits_in_an_EARLIER_directory() {
        // The reported Windows failure started here: npm's shim dir comes first on PATH, so `claude.cmd`
        // won even though `%USERPROFILE%\.local\bin\claude.exe` was installed. The name order
        // (exe-before-cmd) only breaks ties INSIDE one directory — the ranking has to cross directories.
        val shim = bin("npm", "ccp-fixture-cli.cmd", "@echo off\r\n")
        bin("local", "ccp-fixture-cli.exe")
        val picked = resolve(
            names = listOf("ccp-fixture-cli.exe", "ccp-fixture-cli.cmd"),
            dirs = listOf(shim.parent, root.resolve("local")),
        )
        assertEquals(root.resolve("local").resolve("ccp-fixture-cli.exe").toRealPath(), picked)
    }

    @Test
    fun a_cmd_shim_is_still_used_when_it_is_the_only_thing_installed() {
        // demotion, not exclusion: an npm-only machine must still launch (the daemon wraps it in cmd.exe)
        val shim = bin("onlynpm", "ccp-fixture-solo.cmd", "@echo off\r\n")
        assertEquals(
            shim.toRealPath(),
            resolve(names = listOf("ccp-fixture-solo.exe", "ccp-fixture-solo.cmd"), dirs = listOf(shim.parent)),
        )
    }

    @Test
    fun a_native_binary_still_beats_a_shebang_script_shim() {
        // the original Unix rule, unchanged: a `#!` wrapper on PATH may print to stdout and corrupt the
        // JSON stream, so a real binary elsewhere wins
        val script = bin("shimdir", "ccp-fixture-sh", "#!/bin/sh\necho hi\n")
        bin("bindir", "ccp-fixture-sh")
        assertEquals(
            root.resolve("bindir").resolve("ccp-fixture-sh").toRealPath(),
            resolve(names = listOf("ccp-fixture-sh"), dirs = listOf(script.parent, root.resolve("bindir"))),
        )
    }

    @Test
    fun an_explicit_path_is_authoritative_even_when_it_is_a_shim() {
        // --claude-bin / --codex-bin name ONE file on purpose: it is how a machine with a broken PATH pins
        // the CLI that works there. Never second-guessed, never ranked against anything else.
        val shim = bin("explicit", "ccp-fixture-pin.cmd", "@echo off\r\n")
        bin("native", "ccp-fixture-pin.exe")
        assertEquals(
            shim.toRealPath(),
            resolve(
                explicit = shim.toString(),
                names = listOf("ccp-fixture-pin.exe", "ccp-fixture-pin.cmd"),
                dirs = listOf(root.resolve("native")),
            ),
        )
    }

    @Test
    fun the_env_binary_is_authoritative_too_and_falls_through_only_when_it_is_not_runnable() {
        // CC_POCKET_*_BIN is the env-var spelling of the same explicit choice, so the same rule applies:
        // it is NOT demoted below a native binary found on PATH (it used to be — it was just another
        // candidate in the sort, which would have silently ignored an owner who pinned the shim on purpose).
        val shim = bin("envpin", "ccp-fixture-env.cmd", "@echo off\r\n")
        bin("envnative", "ccp-fixture-env.exe")
        val names = listOf("ccp-fixture-env.exe", "ccp-fixture-env.cmd")
        assertEquals(
            shim.toRealPath(),
            resolve(envBin = shim.toString(), names = names, dirs = listOf(root.resolve("envnative"))),
        )
        // a stale value (uninstalled path) must not break the launch — the search still runs
        assertEquals(
            root.resolve("envnative").resolve("ccp-fixture-env.exe").toRealPath(),
            resolve(
                envBin = root.resolve("gone").resolve("ccp-fixture-env.exe").toString(),
                names = names,
                dirs = listOf(root.resolve("envnative")),
            ),
        )
    }

    @Test
    fun nothing_installed_fails_with_the_callers_own_message() {
        val e = assertFailsWith<IllegalStateException> {
            resolve(names = listOf("ccp-fixture-absent"), dirs = listOf(root))
        }
        assertEquals("nothing found", e.message)
    }

    @Test
    fun nvm_version_bins_are_listed_newest_first_and_survive_odd_names() {
        // issue #287: `npm i -g` under nvm lands in ~/.nvm/versions/node/vX.Y.Z/bin, which no service
        // PATH contains. Newest first so a fresh runtime's globals beat a stale copy — and v9 vs v10
        // must compare numerically, not lexically.
        val home = Files.createTempDirectory("ccp-nvmhome")
        for (v in listOf("v9.11.2", "v22.12.0", "v10.24.1", "weird")) {
            Files.createDirectories(home.resolve(".nvm/versions/node/$v/bin"))
        }
        val bins = ExecutableResolver.nvmVersionBins(home)
        assertEquals(
            listOf("v22.12.0", "v10.24.1", "v9.11.2", "weird").map {
                home.resolve(".nvm/versions/node/$it/bin").toString()
            },
            bins,
        )
        // no nvm at all → empty, never an error (the common case on CI and non-nvm machines)
        assertEquals(emptyList(), ExecutableResolver.nvmVersionBins(Files.createTempDirectory("ccp-nonvm")))
    }

    @Test
    fun batch_shims_are_recognised_by_extension_case_insensitively() {
        // the launchers use this to decide to go through cmd.exe, and the Feishu reviewer to refuse to run
        // at all — so a `.CMD` from a Windows PATH entry must not read as native
        assertTrue(ExecutableResolver.isBatchShim("""C:\Users\x\AppData\Roaming\npm\claude.CMD"""))
        assertTrue(ExecutableResolver.isBatchShim("""C:\tools\claude.bat"""))
        assertTrue(!ExecutableResolver.isBatchShim("""C:\Users\x\.local\bin\claude.exe"""))
        assertTrue(!ExecutableResolver.isBatchShim("/usr/local/bin/claude"))
        // a directory that merely LOOKS like a shim doesn't count — the check is on the file name's tail
        assertTrue(!ExecutableResolver.isBatchShim("""C:\claude.cmd\claude.exe"""))
    }
}
