package dev.ccpocket.daemon.update

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Issue #381 on a real Windows runner: the unit tests only prove [TerminalUpdateProgress.decideInteractive]
 * given its inputs, but the Windows input comes from a JNA call (GetStdHandle + GetConsoleMode) that was
 * written without a Windows box. If that call silently failed (null), every console user would keep
 * seeing scrolling lines — exactly the reported symptom. So this runs the real probe in a child JVM that
 * owns a fresh console (`start`, the way a terminal hands one over) and one whose stderr is `NUL`.
 *
 * Not `2>CON`: cmd opens that redirect write-only, and GetConsoleMode needs GENERIC_READ, so it reports
 * "not a console" for a handle no real terminal would ever give the process.
 *
 * No-op off Windows; the Windows CI job selects it with `*WinConsole*`.
 */
class UpdateProgressWinConsoleTest {
    @TempDir lateinit var temp: Path

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    @Test
    fun native_probe_answers_for_a_piped_stderr() {
        if (!isWindows) return
        // the Gradle test worker's stderr is a pipe: the probe must ANSWER false — null means JNA failed
        assertEquals(false, TerminalUpdateProgress.probeStderr().windowsStderrConsole)
    }

    @Test
    fun console_stderr_is_redrawn_in_place() {
        if (!isWindows) return
        assertEquals("windowsStderrConsole=true interactive=true", probeChild(newConsole = true))
    }

    @Test
    fun stderr_redirected_to_a_device_keeps_plain_lines() {
        if (!isWindows) return
        assertEquals("windowsStderrConsole=false interactive=false", probeChild(newConsole = false))
    }

    /** Run [WinStderrProbeChild] through cmd.exe: in a new console of its own ([newConsole]; `start /wait`
     *  gives it that console's standard handles), or with stderr sent to `NUL`. */
    private fun probeChild(newConsole: Boolean): String {
        val out = temp.resolve(if (newConsole) "probe-console.txt" else "probe-nul.txt")
        // the test classpath is far over cmd.exe's 8191-char limit → a JVM @argfile (forward slashes:
        // backslashes are escapes inside argfile quotes)
        val argFile = temp.resolve("args.txt")
        fun q(s: String) = "\"" + s.replace('\\', '/') + "\""
        argFile.writeText(
            listOf("-cp", q(System.getProperty("java.class.path")), WinStderrProbeChild::class.java.name, q(out.toString()))
                .joinToString("\n"),
        )
        val java = Paths.get(System.getProperty("java.home"), "bin", "java.exe").toString()
        val bat = temp.resolve("probe.cmd")
        val run = if (newConsole) "start \"\" /wait \"$java\" @\"$argFile\"" else "\"$java\" @\"$argFile\" 2>NUL"
        bat.writeText("@echo off\r\n$run\r\n")
        val log = temp.resolve("cmd.log")
        val pb = ProcessBuilder("cmd.exe", "/d", "/c", bat.toString())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
        pb.environment().remove("CI")   // GitHub Actions sets CI=true, which would veto on its own
        pb.environment().remove("TERM")
        val proc = pb.start()
        assertTrue(proc.waitFor(90, TimeUnit.SECONDS), "probe child timed out")
        assertTrue(Files.exists(out), "probe child wrote nothing (exit ${proc.exitValue()}): ${log.readText()}")
        return out.readText().trim()
    }
}

/** Child-JVM entry point for [UpdateProgressWinConsoleTest]: report what this process sees on its stderr. */
object WinStderrProbeChild {
    @JvmStatic
    fun main(args: Array<String>) {
        val caps = TerminalUpdateProgress.probeStderr()
        Paths.get(args[0]).writeText(
            "windowsStderrConsole=${caps.windowsStderrConsole} interactive=${TerminalUpdateProgress.decideInteractive(caps)}",
        )
    }
}
