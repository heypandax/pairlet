package dev.ccpocket.daemon.update

import dev.ccpocket.protocol.update.ReleaseClient.DownloadProgress
import java.io.PrintStream
import java.util.Locale

/** The steps [UpdateService.apply] walks through, in order. */
enum class UpdatePhase { DOWNLOAD, VERIFY, EXTRACT, INSTALL }

/**
 * Observer of [UpdateService.apply] (issue #381). Every method defaults to doing nothing, so the
 * background auto-updater stays silent ([QUIET]) while `cc-pocket-daemon update` plugs in
 * [TerminalUpdateProgress]. Purely presentational: apply() isolates exceptions thrown from here.
 */
interface UpdateProgressListener {
    /** A new phase starts; [detail] is e.g. the asset name for [UpdatePhase.DOWNLOAD]. */
    fun onPhase(phase: UpdatePhase, detail: String) {}
    /** Periodic download observation (about every 100 ms, also when nothing new arrived). */
    fun onDownload(progress: DownloadProgress) {}
    /** apply() is about to throw while in [phase]. */
    fun onFailed(phase: UpdatePhase?, error: Throwable) {}
    /** The new version is switched in (the service restart is still the caller's job). */
    fun onSwitched(version: String) {}

    companion object {
        val QUIET: UpdateProgressListener = object : UpdateProgressListener {}
    }
}

/**
 * What this process can observe about **its own stderr** — gathered by [probeStderr] so the
 * verdict ([decideInteractive]) stays a pure function that a macOS unit test can drive.
 *
 * Every field is "what we saw", never "what we decided". Unknown is `null`, not `false`.
 */
internal data class StderrCapabilities(
    /** `os.name` says Windows. */
    val windows: Boolean,
    /** `System.console() != null` — on JDK 17 this only reflects stdin/stdout. */
    val consolePresent: Boolean,
    /** JDK 22+ `Console.isTerminal()`; null when the method does not exist (JDK 17 here). */
    val consoleIsTerminal: Boolean?,
    /** `$TERM` (`dumb` = a terminal that cannot redraw). */
    val term: String?,
    /** `$CI` is set to something truthy — output is being captured. */
    val ci: Boolean,
    /** Windows only: Win32 says the **stderr** handle is a console screen buffer.
     *  null = could not ask (non-Windows, or the native call failed). */
    val windowsStderrConsole: Boolean?,
    /** POSIX only: `[ -t 2 ]` on our inherited stderr. null = not asked. */
    val posixStderrTty: Boolean?,
)

/**
 * Human progress for `cc-pocket-daemon update`, written to stderr so the command's stdout lines stay as
 * they were. Two modes:
 *  - [interactive] (stderr is a real terminal — see [decideInteractive]): one line redrawn in place with
 *    `\r` plus right-padding to erase a longer previous line. Deliberately no ANSI: classic conhost
 *    without VT processing renders `\r` fine but would print `ESC[K` literally. At most every [redrawMs];
 *    a percentage only with a trusted total;
 *  - otherwise (redirected / piped): plain independent lines, at most every [lineMs], no carriage returns.
 * No new bytes for [waitingMs] shows "waiting for network" instead of a frozen number.
 */
class TerminalUpdateProgress(
    private val out: PrintStream,
    private val interactive: Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val redrawMs: Long = 250,
    private val lineMs: Long = 5_000,
    private val waitingMs: Long = 3_000,
) : UpdateProgressListener {
    private var phase: UpdatePhase? = null
    private var lineOpen = false         // interactive: a \r-redrawn line is on screen
    private var lastWidth = 0
    private var lastDrawAt = Long.MIN_VALUE
    private var lastBytes = -1L
    private var lastBytesChangeAt = 0L
    private var lastPrinted: DownloadProgress? = null
    private var latest: DownloadProgress? = null
    private var spin = 0

    @Synchronized
    override fun onPhase(phase: UpdatePhase, detail: String) {
        if (this.phase == UpdatePhase.DOWNLOAD) finishDownloadLine()
        this.phase = phase
        when (phase) {
            UpdatePhase.DOWNLOAD -> {
                lastBytesChangeAt = clock()
                out.println("downloading $detail")
            }
            UpdatePhase.VERIFY -> out.println("verifying checksum…")
            UpdatePhase.EXTRACT -> out.println("extracting…")
            UpdatePhase.INSTALL -> out.println("switching to the new version…")
        }
        out.flush()
    }

    @Synchronized
    override fun onDownload(progress: DownloadProgress) {
        if (phase != UpdatePhase.DOWNLOAD) return
        val now = clock()
        if (progress.receivedBytes != lastBytes) { lastBytes = progress.receivedBytes; lastBytesChangeAt = now }
        latest = progress
        val interval = if (interactive) redrawMs else lineMs
        if (lastDrawAt != Long.MIN_VALUE && now - lastDrawAt < interval) return
        val waiting = now - lastBytesChangeAt >= waitingMs
        if (!interactive && !waiting && progress == lastPrinted) return
        // redirected: a "0 B" line before anything arrived is noise — only speak up once it is waiting
        if (!interactive && !waiting && progress.receivedBytes == 0L) return
        lastDrawAt = now
        draw(progress, waiting)
    }

    @Synchronized
    override fun onFailed(phase: UpdatePhase?, error: Throwable) {
        endLine() // never leave the cursor on a half-drawn line
        out.println("${label(phase)} failed — nothing was switched")
        out.flush()
        this.phase = null
    }

    @Synchronized
    override fun onSwitched(version: String) {
        endLine()
        phase = null
    }

    /**
     * Close a redrawn line with a newline if one is on screen, so whatever prints next (a shell prompt
     * after Ctrl-C included) starts clean. Idempotent and safe to call from a shutdown hook.
     */
    @Synchronized
    fun endLine() {
        if (!lineOpen) return
        out.println()
        out.flush()
        lineOpen = false
        lastWidth = 0
    }

    /** Download done: draw the final count (so a finished known-length download reads 100%) and end the line. */
    private fun finishDownloadLine() {
        val last = latest
        if (last != null && (interactive || last != lastPrinted)) draw(last, waiting = false, final = true)
        endLine()
        latest = null
    }

    private fun draw(p: DownloadProgress, waiting: Boolean, final: Boolean = false) {
        val total = p.totalBytes?.takeIf { it > 0 && p.receivedBytes <= it }
        val amount = if (total != null) {
            val pct = (p.receivedBytes * 100 / total).coerceIn(0, 100)
            "${pct.toString().padStart(3)}%  ${bytes(p.receivedBytes)} / ${bytes(total)}"
        } else {
            "${bytes(p.receivedBytes)} downloaded"
        }
        val status = when {
            waiting -> " — waiting for network…"
            final || total != null -> ""
            interactive -> " " + SPINNER[spin++ % SPINNER.length]
            else -> ""
        }
        val text = "  $amount$status"
        if (interactive) {
            out.print("\r" + text + " ".repeat((lastWidth - text.length).coerceAtLeast(0)))
            lastWidth = text.length
            lineOpen = true
        } else {
            out.println(text)
        }
        lastPrinted = p
        out.flush()
    }

    private fun label(phase: UpdatePhase?) = when (phase) {
        UpdatePhase.DOWNLOAD -> "download"
        UpdatePhase.VERIFY -> "checksum verification"
        UpdatePhase.EXTRACT -> "extraction"
        UpdatePhase.INSTALL -> "install"
        null -> "update"
    }

    companion object {
        private const val SPINNER = "|/-\\"

        fun bytes(n: Long): String = when {
            n >= 1L shl 30 -> String.format(Locale.ROOT, "%.2f GB", n / (1L shl 30).toDouble())
            n >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MB", n / (1L shl 20).toDouble())
            n >= 1L shl 10 -> String.format(Locale.ROOT, "%.1f KB", n / (1L shl 10).toDouble())
            else -> "$n B"
        }

        /**
         * Progress for this process's stderr, redrawn in place only when stderr really is a terminal.
         * The dangling-line guard also runs on an abrupt exit (Ctrl-C / System.exit), so the shell prompt
         * never lands on top of a half-drawn progress line.
         */
        fun forStderr(): TerminalUpdateProgress {
            val renderer = TerminalUpdateProgress(System.err, interactive = decideInteractive(probeStderr()))
            runCatching { Runtime.getRuntime().addShutdownHook(Thread(renderer::endLine, "update-progress-endline")) }
            return renderer
        }

        private fun probeStderr(): StderrCapabilities {
            val windows = runCatching { System.getProperty("os.name").lowercase().contains("win") }.getOrDefault(false)
            val console = runCatching { System.console() }.getOrNull()
            return StderrCapabilities(
                windows = windows,
                consolePresent = console != null,
                consoleIsTerminal = console?.let(::consoleIsTerminal),
                term = runCatching { System.getenv("TERM") }.getOrNull(),
                ci = runCatching { System.getenv("CI") }.getOrNull()
                    ?.let { it.isNotBlank() && !it.equals("false", true) && it != "0" } ?: false,
                windowsStderrConsole = if (windows) windowsStderrIsConsole() else null,
                posixStderrTty = if (windows) null else runCatching { posixStderrTty() }.getOrNull(),
            )
        }

        /** JDK 22+ `Console.isTerminal()` (there System.console() may be non-null even when redirected);
         *  null on older JDKs where the method does not exist. */
        private fun consoleIsTerminal(console: java.io.Console): Boolean? = runCatching {
            java.io.Console::class.java.getMethod("isTerminal").invoke(console) as Boolean
        }.getOrNull()

        /** The JDK has no isatty(2) — ask the shell, handing it our real stderr. */
        private fun posixStderrTty(): Boolean =
            ProcessBuilder("sh", "-c", "[ -t 2 ]")
                .redirectInput(ProcessBuilder.Redirect.INHERIT)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start().waitFor() == 0

        /**
         * The decision: redraw in place, or log plain lines (issue #381).
         *
         * Windows used to be a blanket `false` — every `cc-pocket-daemon update` there scrolled a new
         * `1%  1.4 MB / 107.6 MB` line every few seconds. The missing piece was a way to ask about
         * **stderr specifically**: `System.console()` describes stdin/stdout, so trusting it would redraw
         * into `update 2> err.txt`. Win32 can answer exactly — [windowsStderrIsConsole] — so Windows now
         * follows that answer and nothing else. When the native probe cannot run at all (null) the verdict
         * stays the old conservative `false`: plain lines in a console are ugly, control codes in a log file
         * are unreadable. Same reason mintty/MSYS (stderr is a pipe) keeps plain lines.
         *
         * `TERM=dumb` and `$CI` can only ever veto; they never promote a non-terminal to interactive.
         */
        internal fun decideInteractive(caps: StderrCapabilities): Boolean = when {
            caps.term == "dumb" -> false
            caps.ci -> false
            // Windows: the Win32 stderr answer is the whole verdict. consolePresent is deliberately
            // ignored here — it goes false when only *stdout* is redirected, which must not cost the redraw.
            caps.windows -> caps.windowsStderrConsole == true
            !caps.consolePresent -> false
            caps.consoleIsTerminal == false -> false
            else -> caps.posixStderrTty == true
        }

        // ── Win32: is the stderr handle a console? ───────────────────────────────────────────────────
        // GetConsoleMode() succeeds only for a real console screen buffer, so it fails for `2> file`,
        // `2>&1 | more` and for a pty emulator's pipe. JNA is already a daemon dependency (issue #302's
        // ProcessCwd), so this adds no new one. Non-Windows never gets here.
        private const val STD_ERROR_HANDLE = -12
        private const val INVALID_HANDLE = -1L

        private interface Kernel32Console : com.sun.jna.win32.StdCallLibrary {
            fun GetStdHandle(which: Int): com.sun.jna.Pointer?
            fun GetConsoleMode(handle: com.sun.jna.Pointer, mode: com.sun.jna.ptr.IntByReference): Boolean
        }

        /** True = stderr is a Windows console; false = redirected/piped/no handle; null = could not ask. */
        private fun windowsStderrIsConsole(): Boolean? = runCatching {
            val k32 = com.sun.jna.Native.load("kernel32", Kernel32Console::class.java)
            val handle = k32.GetStdHandle(STD_ERROR_HANDLE) ?: return@runCatching false // NULL = no stderr
            if (com.sun.jna.Pointer.nativeValue(handle) == INVALID_HANDLE) return@runCatching false
            k32.GetConsoleMode(handle, com.sun.jna.ptr.IntByReference())
        }.getOrNull()
    }
}
