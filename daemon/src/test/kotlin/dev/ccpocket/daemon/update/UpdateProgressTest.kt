package dev.ccpocket.daemon.update

import com.sun.net.httpserver.HttpServer
import dev.ccpocket.protocol.update.ReleaseClient
import dev.ccpocket.protocol.update.ReleaseClient.DownloadProgress
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Issue #381: what `cc-pocket-daemon update` actually prints while downloading, captured as bytes — for a
 * terminal (redrawn line) and for a redirected stream (plain lines) — plus apply()'s phase reporting and
 * failure path against a local HTTP fixture, without switching any real install.
 */
class UpdateProgressTest {
    @TempDir lateinit var temp: Path

    private class Capture(interactive: Boolean) {
        var now = 0L
        val bytes = ByteArrayOutputStream()
        val renderer = TerminalUpdateProgress(PrintStream(bytes, true, Charsets.UTF_8), interactive, clock = { now })
        val text get() = bytes.toString(Charsets.UTF_8)
        fun at(ms: Long, received: Long, total: Long?) { now = ms; renderer.onDownload(DownloadProgress.of(received, total)) }
    }

    @Test
    fun terminal_known_total_redraws_one_line_with_percent_and_is_throttled() {
        val c = Capture(interactive = true)
        c.renderer.onPhase(UpdatePhase.DOWNLOAD, "cc-pocket-daemon-2.1.0-macos-arm64.tar.gz")
        c.at(0, 0, 4096)
        c.at(100, 1024, 4096)   // inside the 250 ms window: not drawn
        c.at(200, 1536, 4096)   // still inside
        c.at(300, 2048, 4096)
        c.renderer.onPhase(UpdatePhase.VERIFY, "")
        val out = c.text
        assertTrue(out.startsWith("downloading cc-pocket-daemon-2.1.0-macos-arm64.tar.gz\n"), out)
        assertTrue(out.contains("\r    0%  0 B / 4.0 KB"), out)
        assertFalse(out.contains("25%") || out.contains("1.5 KB"), "throttled frames must not be drawn: $out")
        assertTrue(out.contains("\r   50%  2.0 KB / 4.0 KB"), out)
        // completion: the line is finished at the real final count, newline, THEN verifying — not "updated"
        assertTrue(out.contains("\r   50%  2.0 KB / 4.0 KB\r   50%  2.0 KB / 4.0 KB\nverifying checksum…\n"), out)
        assertFalse(out.contains(""), "no ANSI escapes: $out")
    }

    @Test
    fun terminal_completion_reads_100_percent_when_everything_arrived() {
        val c = Capture(interactive = true)
        c.renderer.onPhase(UpdatePhase.DOWNLOAD, "a.tar.gz")
        c.at(0, 1000, 4096)
        c.at(50, 4096, 4096) // throttled, but it is the final state
        c.renderer.onPhase(UpdatePhase.VERIFY, "")
        assertTrue(c.text.endsWith("\r  100%  4.0 KB / 4.0 KB\nverifying checksum…\n"), c.text)
    }

    @Test
    fun terminal_unknown_total_shows_bytes_and_activity_but_no_percent() {
        val c = Capture(interactive = true)
        c.renderer.onPhase(UpdatePhase.DOWNLOAD, "a.tar.gz")
        c.at(0, 2 * 1024 * 1024, null)
        c.at(300, 3 * 1024 * 1024, null)
        val out = c.text
        assertTrue(out.contains("\r  2.0 MB downloaded |"), out)
        assertTrue(out.contains("\r  3.0 MB downloaded /"), out)
        assertFalse(out.contains("%"), "no invented percentage: $out")
    }

    @Test
    fun overshooting_the_declared_length_falls_back_to_unknown_instead_of_over_100_percent() {
        val c = Capture(interactive = true)
        c.renderer.onPhase(UpdatePhase.DOWNLOAD, "a.tar.gz")
        c.renderer.onDownload(DownloadProgress(receivedBytes = 5000, totalBytes = 4096)) // even if handed a raw one
        assertFalse(c.text.contains("%"), c.text)
        assertTrue(c.text.contains("4.9 KB downloaded"), c.text)
    }

    @Test
    fun no_new_bytes_shows_waiting_for_network_without_growing_the_count() {
        val c = Capture(interactive = true)
        c.renderer.onPhase(UpdatePhase.DOWNLOAD, "a.tar.gz")
        c.at(0, 1024, 4096)
        c.at(1_000, 1024, 4096)
        c.at(3_100, 1024, 4096)
        val out = c.text
        assertTrue(out.contains("\r   25%  1.0 KB / 4.0 KB — waiting for network…"), out)
        assertFalse(out.contains("2.0 KB"), out)
    }

    @Test
    fun waiting_for_headers_shows_waiting_for_network_in_both_modes() {
        // before the headers ReleaseClient reports (0, null) every tick
        val tty = Capture(interactive = true)
        tty.renderer.onPhase(UpdatePhase.DOWNLOAD, "a.tar.gz")
        tty.at(100, 0, null)
        tty.at(3_100, 0, null)
        assertTrue(tty.text.contains("\r  0 B downloaded |"), tty.text)
        assertTrue(tty.text.contains("\r  0 B downloaded — waiting for network…"), tty.text)

        val piped = Capture(interactive = false)
        piped.renderer.onPhase(UpdatePhase.DOWNLOAD, "a.tar.gz")
        piped.at(100, 0, null)   // not worth a line yet
        piped.at(1_000, 0, null)
        piped.at(3_100, 0, null) // 3 s without a byte
        assertEquals("downloading a.tar.gz\n  0 B downloaded — waiting for network…\n", piped.text)
    }

    // ── which mode: it must hinge on STDERR, not on stdin/stdout ───────────────────────────────────

    /** A POSIX box: nothing is known about Windows, `[ -t 2 ]` answered. */
    private fun posix(
        consolePresent: Boolean = true,
        consoleIsTerminal: Boolean? = null,
        term: String? = "xterm",
        ci: Boolean = false,
        stderrTty: Boolean? = true,
    ) = StderrCapabilities(
        windows = false, consolePresent = consolePresent, consoleIsTerminal = consoleIsTerminal,
        term = term, ci = ci, windowsStderrConsole = null, posixStderrTty = stderrTty,
    )

    /** A Windows box: `[ -t 2 ]` is never run, GetConsoleMode(stderr) answered (null = could not ask). */
    private fun windows(
        stderrConsole: Boolean?,
        consolePresent: Boolean = true,
        consoleIsTerminal: Boolean? = null,
        term: String? = null,
        ci: Boolean = false,
    ) = StderrCapabilities(
        windows = true, consolePresent = consolePresent, consoleIsTerminal = consoleIsTerminal,
        term = term, ci = ci, windowsStderrConsole = stderrConsole, posixStderrTty = null,
    )

    @Test
    fun posix_is_interactive_only_when_stderr_is_positively_a_terminal() {
        assertTrue(TerminalUpdateProgress.decideInteractive(posix()))
        assertTrue(TerminalUpdateProgress.decideInteractive(posix(consoleIsTerminal = true)))
        assertFalse(TerminalUpdateProgress.decideInteractive(posix(stderrTty = false)))   // update 2> err.txt
        assertFalse(TerminalUpdateProgress.decideInteractive(posix(stderrTty = null)))    // probe failed
        assertFalse(TerminalUpdateProgress.decideInteractive(posix(consolePresent = false)))
        assertFalse(TerminalUpdateProgress.decideInteractive(posix(consoleIsTerminal = false))) // JDK 22+ redirected
        assertFalse(TerminalUpdateProgress.decideInteractive(posix(term = "dumb")))
        assertFalse(TerminalUpdateProgress.decideInteractive(posix(ci = true)))
    }

    /** Issue #381's actual bug: Windows was hard-coded non-interactive, so a console user watched
     *  `1%  1.4 MB / 107.6 MB` scroll past instead of one line refreshing. */
    @Test
    fun windows_console_stderr_redraws_in_place() {
        assertTrue(TerminalUpdateProgress.decideInteractive(windows(stderrConsole = true)))
        // Windows Terminal / PowerShell redirect stdout only → System.console() is null, stderr is still a console
        assertTrue(TerminalUpdateProgress.decideInteractive(windows(stderrConsole = true, consolePresent = false)))
        assertTrue(TerminalUpdateProgress.decideInteractive(windows(stderrConsole = true, consoleIsTerminal = false)))
    }

    @Test
    fun windows_redirected_or_unknowable_stderr_keeps_plain_lines() {
        // `update 2> err.txt` / `2>&1 | more`: GetConsoleMode fails even though a console exists for stdout
        assertFalse(TerminalUpdateProgress.decideInteractive(windows(stderrConsole = false, consoleIsTerminal = true)))
        // native probe unavailable (no JNA, odd host) → conservative: control codes must never reach a log file
        assertFalse(TerminalUpdateProgress.decideInteractive(windows(stderrConsole = null, consolePresent = true)))
        // env vetoes still win over a real console handle
        assertFalse(TerminalUpdateProgress.decideInteractive(windows(stderrConsole = true, term = "dumb")))
        assertFalse(TerminalUpdateProgress.decideInteractive(windows(stderrConsole = true, ci = true)))
    }

    /** The real probe (System.console(), $TERM, `[ -t 2 ]`, and on Windows the native call) must never
     *  throw on any host — a broken probe must cost at most the redraw, never the update. */
    @Test
    fun the_real_stderr_probe_is_safe_to_run_here() {
        assertNotNull(TerminalUpdateProgress.forStderr())
    }

    @Test
    fun redirected_output_is_plain_low_frequency_lines() {
        val c = Capture(interactive = false)
        c.renderer.onPhase(UpdatePhase.DOWNLOAD, "a.tar.gz")
        var t = 0L
        var got = 0L
        while (t <= 12_000) { got = (got + 300).coerceAtMost(4096); c.at(t, got, 4096); t += 100 }
        c.renderer.onPhase(UpdatePhase.VERIFY, "")
        c.renderer.onPhase(UpdatePhase.EXTRACT, "")
        val out = c.text
        assertFalse(out.contains("\r") || out.contains(""), "no carriage returns / ANSI when redirected: $out")
        val progressLines = out.lines().filter { it.startsWith("  ") }
        // 121 ticks over 12 s → only t=0, t=5000, t=10000 print (bytes stopped at t=1300, hence "waiting");
        // the completion adds no duplicate line because the last printed state is already final
        val waitingLine = "  100%  4.0 KB / 4.0 KB — waiting for network…"
        assertEquals(listOf("    7%  300 B / 4.0 KB", waitingLine, waitingLine), progressLines)
        assertTrue(out.endsWith("verifying checksum…\nextracting…\n"), out)
    }

    @Test
    fun failure_mid_download_ends_the_line_and_never_claims_success() {
        val c = Capture(interactive = true)
        c.renderer.onPhase(UpdatePhase.DOWNLOAD, "a.tar.gz")
        c.at(0, 1024, 4096)
        c.renderer.onFailed(UpdatePhase.DOWNLOAD, java.io.IOException("stalled"))
        val out = c.text
        assertTrue(out.endsWith("\r   25%  1.0 KB / 4.0 KB\ndownload failed — nothing was switched\n"), out)
        assertFalse(out.contains("updated") || out.contains("verifying"), out)
    }

    @Test
    fun a_shorter_line_fully_erases_the_longer_one_it_replaces() {
        val c = Capture(interactive = true)
        c.renderer.onPhase(UpdatePhase.DOWNLOAD, "a.tar.gz")
        c.at(0, 1024, 4096)                  // "   25%  1.0 KB / 4.0 KB"
        c.at(3_100, 1024, 4096)              // + " — waiting for network…"  (longer)
        c.at(3_400, 2048, 4096)              // back to the short form (shorter)
        val frames = c.text.split("\r").drop(1)
        val long = frames.first { it.contains("waiting") }
        val short = frames.last()
        assertTrue(short.startsWith("   50%  2.0 KB / 4.0 KB"), short)
        assertEquals(long.length, short.length, "a shorter frame must be padded over the longer one: '$short'")
        assertTrue(short.endsWith(" "), "padding is spaces, not an ANSI erase: '$short'")
    }

    @Test
    fun aborting_mid_line_leaves_the_cursor_on_a_fresh_line() {
        val c = Capture(interactive = true)
        c.renderer.onPhase(UpdatePhase.DOWNLOAD, "a.tar.gz")
        c.at(0, 1024, 4096)
        c.renderer.endLine()                 // what the shutdown hook does on Ctrl-C
        c.renderer.endLine()                 // idempotent: no second blank line
        assertTrue(c.text.endsWith("KB / 4.0 KB\n"), c.text)
        assertFalse(c.text.endsWith("\n\n"), c.text)
    }

    // ── apply() wiring against a local HTTP fixture ─────────────────────────────────────────────────

    private class Recorder : UpdateProgressListener {
        val events = mutableListOf<String>()
        var downloads = 0
        override fun onPhase(phase: UpdatePhase, detail: String) { events += "phase:$phase" }
        override fun onDownload(progress: DownloadProgress) { downloads++ }
        override fun onFailed(phase: UpdatePhase?, error: Throwable) { events += "failed:$phase" }
        override fun onSwitched(version: String) { events += "switched" }
    }

    private fun withRelease(version: String, body: ByteArray?, sumsFor: ((String) -> String)?, block: (ReleaseClient.Release, String) -> Unit) {
        val asset = UpdateService.assetNameFor(version)
        assumeTrue(asset != null, "no published asset for this host")
        val sums = sumsFor?.invoke(asset!!)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/asset") { ex ->
            if (body == null) ex.sendResponseHeaders(404, -1) else { ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.write(body) }
            ex.close()
        }
        server.createContext("/sums") { ex -> val b = sums!!.toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.write(b); ex.close() }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val urls = buildMap { put(asset!!, "$base/asset"); if (sums != null) put("SHA256SUMS", "$base/sums") }
            block(ReleaseClient.Release(version, urls), asset!!)
        } finally { server.stop(0) }
    }

    private fun install() = UpdateService.ManagedInstall(
        versionsDir = Files.createDirectories(temp.resolve("cc-pocket/versions")),
        launcher = temp.resolve("bin/cc-pocket-daemon"),
        serviceAnchored = false,
    )

    @Test
    fun checksum_mismatch_reports_verify_failure_and_switches_nothing() {
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        withRelease("99.0.0", payload, sumsFor = { asset -> "${"0".repeat(64)}  $asset\n" }) { release, _ ->
            val rec = Recorder()
            val inst = install()
            val e = assertFailsWith<IllegalStateException> { UpdateService.apply(release, inst, rec) }
            assertTrue(e.message!!.contains("checksum mismatch"), e.message)
            assertEquals(listOf("phase:DOWNLOAD", "phase:VERIFY", "failed:VERIFY"), rec.events)
            assertTrue(rec.downloads >= 1)
            assertFalse(inst.launcher.exists(), "launcher must not be switched")
            assertFalse(inst.versionsDir.resolve("99.0.0").exists())
        }
    }

    @Test
    fun http_error_is_reported_as_a_download_failure_in_the_terminal_output() {
        withRelease("99.0.1", body = null, sumsFor = null) { release, asset ->
            val bytes = ByteArrayOutputStream()
            val renderer = TerminalUpdateProgress(PrintStream(bytes, true, Charsets.UTF_8), interactive = false)
            val inst = install()
            val e = assertFailsWith<IllegalStateException> { UpdateService.apply(release, inst, renderer) }
            assertTrue(e.message!!.contains("HTTP 404"), e.message)
            assertEquals("downloading $asset\ndownload failed — nothing was switched\n", bytes.toString(Charsets.UTF_8))
            assertFalse(inst.launcher.exists())
        }
    }

    @Test
    fun a_throwing_listener_cannot_change_the_outcome() {
        withRelease("99.0.2", body = null, sumsFor = null) { release, _ ->
            val boom = object : UpdateProgressListener {
                override fun onPhase(phase: UpdatePhase, detail: String) = throw IllegalStateException("renderer bug")
                override fun onFailed(phase: UpdatePhase?, error: Throwable) = throw IllegalStateException("renderer bug")
            }
            val e = assertFailsWith<IllegalStateException> { UpdateService.apply(release, install(), boom) }
            assertTrue(e.message!!.contains("HTTP 404"), "the real failure surfaces, not the listener's: ${e.message}")
        }
    }
}
