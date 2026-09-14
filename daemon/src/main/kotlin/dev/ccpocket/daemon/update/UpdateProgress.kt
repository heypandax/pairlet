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
 * Human progress for `cc-pocket-daemon update`, written to stderr so the command's stdout lines stay as
 * they were. Two modes:
 *  - [interactive] (a terminal): one line redrawn in place with `\r` (no ANSI, so old Windows consoles
 *    render it too), at most every [redrawMs]; a percentage only with a trusted total;
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
        if (lineOpen) { out.println(); lineOpen = false } // never leave the cursor on a half-drawn line
        out.println("${label(phase)} failed — nothing was switched")
        out.flush()
        this.phase = null
    }

    @Synchronized
    override fun onSwitched(version: String) {
        if (lineOpen) { out.println(); lineOpen = false }
        phase = null
    }

    /** Download done: draw the final count (so a finished known-length download reads 100%) and end the line. */
    private fun finishDownloadLine() {
        val last = latest
        if (last != null && (interactive || last != lastPrinted)) draw(last, waiting = false, final = true)
        if (lineOpen) { out.println(); lineOpen = false }
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

        /** Progress for this process's stderr, redrawn in place only when stderr really is a terminal. */
        fun forStderr(): TerminalUpdateProgress = TerminalUpdateProgress(System.err, interactive = stderrIsTerminal())

        private fun stderrIsTerminal(): Boolean = runCatching {
            val console = System.console()
            decideInteractive(
                consolePresent = console != null,
                consoleIsTerminal = console?.let(::consoleIsTerminal),
                term = System.getenv("TERM"),
                windows = System.getProperty("os.name").lowercase().contains("win"),
                stderrTty = {
                    // the JDK has no isatty(2); ask the shell, handing it our real stderr
                    ProcessBuilder("sh", "-c", "[ -t 2 ]")
                        .redirectInput(ProcessBuilder.Redirect.INHERIT)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.INHERIT)
                        .start().waitFor() == 0
                },
            )
        }.getOrDefault(false)

        /** JDK 22+ `Console.isTerminal()` (there System.console() may be non-null even when redirected);
         *  null on older JDKs where the method does not exist. */
        private fun consoleIsTerminal(console: java.io.Console): Boolean? = runCatching {
            java.io.Console::class.java.getMethod("isTerminal").invoke(console) as Boolean
        }.getOrNull()

        /**
         * Redraw in place only when stderr is positively a terminal. JDK 17's System.console() only reflects
         * stdin/stdout, so `update 2> err.txt` would still look interactive; on Windows there is no way to ask
         * about stderr without native code, so Windows conservatively gets plain lines.
         */
        internal fun decideInteractive(
            consolePresent: Boolean,
            consoleIsTerminal: Boolean?,
            term: String?,
            windows: Boolean,
            stderrTty: () -> Boolean,
        ): Boolean = when {
            !consolePresent -> false
            consoleIsTerminal == false -> false
            term == "dumb" -> false
            windows -> false
            else -> stderrTty()
        }
    }
}
