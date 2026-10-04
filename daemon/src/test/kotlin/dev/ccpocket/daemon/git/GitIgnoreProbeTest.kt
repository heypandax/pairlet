package dev.ccpocket.daemon.git

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitIgnoreProbeTest {

    @TempDir
    lateinit var tmp: Path

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    @Test
    fun a_real_repository_reports_its_ignored_children() {
        assumeTrue(!isWindows && runCatching { ProcessBuilder("git", "--version").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false))
        val dir = tmp.resolve("repo").also { it.createDirectories() }
        ProcessBuilder("git", "init", "-q").directory(dir.toFile()).start().waitFor(30, TimeUnit.SECONDS)
        dir.resolve(".gitignore").writeText("node_modules/\n*.log\n")
        dir.resolve("node_modules").createDirectories()
        dir.resolve("a.log").writeText("x\n")
        dir.resolve("src.kt").writeText("x\n")
        val got = runBlocking { GitIgnoreProbe.ignored(dir, listOf("node_modules", "a.log", "src.kt", ".gitignore")) }
        assertEquals(setOf("node_modules", "a.log"), got)
    }

    @Test
    fun a_git_that_never_reads_stdin_cannot_stretch_the_two_second_bound() {
        assumeTrue(!isWindows)
        val pidFile = tmp.resolve("fake-git.pid")
        // wedged: never reads its stdin, so a large batch fills the pipe and the WRITE blocks
        val fake = tmp.resolve("fake-git")
        fake.writeText("#!/bin/sh\necho \$\$ > '$pidFile'\nexec sleep 60\n")
        assertTrue(fake.toFile().setExecutable(true))
        val names = (0 until 9_000).map { "a-reasonably-long-file-name-number-$it.txt" } // ~380 KB > any pipe buffer
        try {
            val started = System.nanoTime()
            val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "probe").apply { isDaemon = true } }
            val answer = try {
                pool.submit(Callable { runBlocking { GitIgnoreProbe.ignored(tmp, names) { fake } } to true }).get(10, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                null
            } finally {
                pool.shutdown()
            }
            assertNotNull(answer, "the probe's timeout did not run while the stdin write was blocked")
            assertNull(answer.first) // "could not ask" — the browser shows everything
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(10))
        } finally {
            runCatching { pidFile.readText().trim().toLong() }.getOrNull()
                ?.let { pid -> ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
        }
    }
}
