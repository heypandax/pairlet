package dev.ccpocket.app.desktop

import java.io.File
import java.time.Instant
import kotlin.system.exitProcess

/**
 * Issue #251 — the desktop shell's last line of defence.
 *
 * Until now `fun main() = application { … }` was bare: no `Thread.setDefaultUncaughtExceptionHandler`,
 * no `WindowExceptionHandlerFactory`. Anything thrown out of composition took the default AWT path,
 * which on a jpackage Windows launcher is a native error box reading only "Unknown error" with a
 * single OK button — no message, no stack, no code. Worse, the window was already destroyed by then
 * while the AWT SystemTray thread (a non-daemon thread, see [MenuBarExtra]) kept the JVM alive: the
 * app "quit" but the process stayed, un-clickable and un-quittable. That combination is what the
 * reporter saw and what makes the bug unreportable — there is nothing to report.
 *
 * This object turns any such escape into three things the old path had none of:
 *  - a LINE IN A LOG FILE (code, thread, exception class, message, top frames) at a fixed path,
 *  - a SHORT CODE the user can read back to us verbatim,
 *  - a CLEAN process exit, so no tray thread can strand a half-dead app.
 *
 * It is deliberately dumb: no network, no crash-reporting SDK, no state. Failing to write the log
 * must never itself become the crash, so every step is wrapped.
 */
object DesktopCrashGuard {

    /** Escaped to a JVM thread with no handler of its own — the outermost net. */
    const val ERR_UNCAUGHT = "CCP-UEH-01"

    /** Escaped out of the Compose window's composition/draw — the net that used to be "Unknown error". */
    const val ERR_WINDOW = "CCP-WIN-01"

    /** Tests point this at a temp file; ordinary launches use the per-OS path below. */
    private const val LOG_FILE_PROPERTY = "ccpocket.desktopLog.file"

    /** Truncate-and-restart past this, so a crash loop cannot fill a disk. */
    private const val MAX_LOG_BYTES = 512L * 1024L

    /** How much stack is enough to place the fault without pasting a novel into a bug report. */
    internal const val STACK_FRAMES = 12

    val logFile: File by lazy {
        System.getProperty(LOG_FILE_PROPERTY)?.takeIf { it.isNotBlank() }?.let(::File)
            ?: defaultLogFile(
                osName = System.getProperty("os.name").orEmpty(),
                home = System.getProperty("user.home").orEmpty(),
                localAppData = System.getenv("LOCALAPPDATA"),
                xdgState = System.getenv("XDG_STATE_HOME"),
            )
    }

    /**
     * Where the desktop app writes its own crash log, per platform. Kept pure (all inputs passed in)
     * so the path choice is testable on any host — the Windows branch is the one nobody here can run.
     */
    internal fun defaultLogFile(osName: String, home: String, localAppData: String?, xdgState: String?): File {
        val os = osName.lowercase()
        return when {
            // alongside the daemon's own logs — one place to look when a report says "cc-pocket died"
            os.contains("mac") -> File(home, "Library/Logs/cc-pocket/desktop.err.log")
            os.contains("win") -> File(
                localAppData?.takeIf { it.isNotBlank() } ?: File(home, "AppData/Local").path,
                "cc-pocket/logs/desktop.err.log",
            )
            else -> File(
                xdgState?.takeIf { it.isNotBlank() } ?: File(home, ".local/state").path,
                "cc-pocket/desktop.err.log",
            )
        }
    }

    /**
     * Install the JVM-wide net. Call FIRST in `main`, before `application { }` — an exception during
     * Compose start-up is exactly the kind this exists for.
     */
    fun install() {
        runCatching {
            Thread.setDefaultUncaughtExceptionHandler { thread, t ->
                val where = "thread=${thread.name}"
                if (isUiThread(thread.name)) fatal(ERR_UNCAUGHT, t, where) else note(ERR_UNCAUGHT, t, where)
            }
        }
    }

    /**
     * Which threads' deaths must take the process with them.
     *
     * The #251 zombie is a specific shape: a UI thread dies, the window goes with it, and the AWT
     * SystemTray's non-daemon thread keeps a windowless JVM alive forever. Only a UI-thread escape can
     * produce that, so only a UI-thread escape exits. Everything else is LOGGED but survives — the JVM's
     * own default there is "the thread dies, the app continues", and quietly upgrading a stray PTY /
     * HTTP worker's exception into a process kill would be a new way to lose someone's session, which is
     * the opposite of the fix.
     */
    internal fun isUiThread(name: String): Boolean =
        name == "main" || name.startsWith("AWT-") || name.startsWith("Java2D") || name.startsWith("AppKit")

    /**
     * Record a contained failure — something a feature already recovered from (a QR that would not
     * render, a string lookup that fell back). Never exits; the point is that the user keeps their
     * window AND we still learn the code.
     */
    fun note(code: String, t: Throwable, context: String? = null) {
        runCatching { append(formatEntry(code, t, context, Instant.now().toString())) }
    }

    /**
     * Record and then LEAVE — cleanly. `exitProcess` runs shutdown hooks and terminates regardless of
     * live non-daemon AWT threads, which is the whole point: the zombie in #251 existed because
     * nothing ever called this.
     */
    fun fatal(code: String, t: Throwable, context: String? = null): Nothing {
        note(code, t, context)
        exitCrashed(code, t)
    }

    /**
     * Leave, for a caller that has ALREADY logged — the window handler logs first so a wedged or
     * un-showable notice dialog cannot cost us the one record of what happened, then comes here.
     */
    fun exitCrashed(code: String, t: Throwable): Nothing {
        runCatching { System.err.println(oneLineSummary(code, t)) }
        exitProcess(EXIT_CRASH)
    }

    /**
     * What a human is shown / says back to us: `CCP-WIN-01 · IllegalStateException` plus the log path.
     * Short on purpose — it has to survive being retyped from a screenshot.
     */
    fun oneLineSummary(code: String, t: Throwable): String =
        "$code · ${t::class.simpleName ?: "Throwable"}"

    /** The full log entry. Pure and timestamp-injected so a test can assert its shape exactly. */
    internal fun formatEntry(code: String, t: Throwable, context: String?, at: String): String = buildString {
        append(at).append(' ').append(code)
        if (!context.isNullOrBlank()) append(' ').append(context)
        append(' ').append(t::class.qualifiedName ?: t::class.simpleName ?: "Throwable")
        t.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it.lineSequence().first().take(400)) }
        append('\n')
        t.stackTrace.take(STACK_FRAMES).forEach { append("    at ").append(it).append('\n') }
        // a wrapped cause is usually the real fault (compose-resources wraps IO, ZXing wraps encode)
        generateSequence(t.cause) { it.cause }.take(3).forEach { c ->
            append("  caused by ").append(c::class.qualifiedName ?: "Throwable")
            c.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it.lineSequence().first().take(400)) }
            append('\n')
            c.stackTrace.take(4).forEach { append("    at ").append(it).append('\n') }
        }
    }

    private fun append(entry: String) {
        val f = logFile
        f.parentFile?.mkdirs()
        if (f.length() > MAX_LOG_BYTES) f.writeText("") // crash loop: keep the newest, never grow forever
        f.appendText(entry)
    }

    /** sysexits.h EX_SOFTWARE — distinguishable from a user-initiated quit in a launchd/Event-Log trace. */
    private const val EXIT_CRASH = 70
}
