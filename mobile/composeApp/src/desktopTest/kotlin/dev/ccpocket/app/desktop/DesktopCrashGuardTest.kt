package dev.ccpocket.app.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #251 — the report was "no information, then it hangs". The guard's whole job is to make the
 * next report possible: a fixed log path per platform, an entry that names code + place, and a short
 * summary a user can retype out of a screenshot.
 *
 * The exit half ([DesktopCrashGuard.fatal]) is deliberately NOT exercised — it calls `exitProcess`,
 * which would take the test JVM with it. What is testable is everything it writes before leaving.
 */
class DesktopCrashGuardTest {

    @Test
    fun macLogsAlongsideTheDaemonLogs() {
        assertEquals(
            File("/Users/x/Library/Logs/cc-pocket/desktop.err.log"),
            DesktopCrashGuard.defaultLogFile("Mac OS X", "/Users/x", localAppData = null, xdgState = null),
        )
    }

    @Test
    fun windowsPrefersLocalAppData() {
        // the platform the crash was reported on, and the one nobody here can run — pin it by construction
        assertEquals(
            File("C:\\Users\\x\\AppData\\Local", "cc-pocket/logs/desktop.err.log"),
            DesktopCrashGuard.defaultLogFile("Windows 11", "C:\\Users\\x", "C:\\Users\\x\\AppData\\Local", null),
        )
    }

    @Test
    fun windowsFallsBackWhenLocalAppDataIsMissingOrBlank() {
        val expected = File(File("C:\\Users\\x", "AppData/Local").path, "cc-pocket/logs/desktop.err.log")
        assertEquals(expected, DesktopCrashGuard.defaultLogFile("Windows 11", "C:\\Users\\x", null, null))
        assertEquals(expected, DesktopCrashGuard.defaultLogFile("Windows 11", "C:\\Users\\x", "  ", null))
    }

    @Test
    fun linuxHonorsXdgStateHome() {
        assertEquals(
            File("/home/x/.state", "cc-pocket/desktop.err.log"),
            DesktopCrashGuard.defaultLogFile("Linux", "/home/x", null, "/home/x/.state"),
        )
        assertEquals(
            File(File("/home/x", ".local/state").path, "cc-pocket/desktop.err.log"),
            DesktopCrashGuard.defaultLogFile("Linux", "/home/x", null, null),
        )
    }

    @Test
    fun entryCarriesCodeContextClassMessageAndStack() {
        val t = runCatching { error("kaboom") }.exceptionOrNull()!!
        val entry = DesktopCrashGuard.formatEntry("CCP-WIN-01", t, "thread=AWT-EventQueue-0", "2026-08-15T00:00:00Z")
        assertTrue(entry.startsWith("2026-08-15T00:00:00Z CCP-WIN-01 thread=AWT-EventQueue-0 "), entry)
        assertTrue(entry.contains("java.lang.IllegalStateException: kaboom"), entry)
        assertTrue(entry.contains("\n    at "), "an entry with no stack places nothing")
        assertTrue(entry.endsWith("\n"), "entries must not run together in the log")
    }

    @Test
    fun entryIncludesTheWrappedCauseWhichIsUsuallyTheRealFault() {
        val root = IllegalArgumentException("missing resource root")
        val entry = DesktopCrashGuard.formatEntry("CCP-STR-01", RuntimeException("lookup failed", root), null, "T")
        assertTrue(entry.contains("caused by java.lang.IllegalArgumentException: missing resource root"), entry)
    }

    @Test
    fun aMultiLineOrRunawayMessageStaysOneLine() {
        val t = RuntimeException("first line\nsecond line")
        val entry = DesktopCrashGuard.formatEntry("CCP-QR-01", t, null, "T")
        assertEquals("T CCP-QR-01 java.lang.RuntimeException: first line", entry.lineSequence().first())
    }

    @Test
    fun summaryIsShortEnoughToRetypeFromAScreenshot() {
        assertEquals(
            "CCP-WIN-01 · IllegalStateException",
            DesktopCrashGuard.oneLineSummary("CCP-WIN-01", IllegalStateException("anything at all")),
        )
    }

    @Test
    fun onlyUiThreadDeathsAreFatal() {
        // these are the ones that can strand a windowless JVM behind a live tray icon (#251)
        assertTrue(DesktopCrashGuard.isUiThread("AWT-EventQueue-0"))
        assertTrue(DesktopCrashGuard.isUiThread("AWT-Shutdown"))
        assertTrue(DesktopCrashGuard.isUiThread("main"))
        assertTrue(DesktopCrashGuard.isUiThread("AppKit Thread"))
        // these must NOT newly kill the app — the JVM's own default lets the thread die and go on
        assertFalse(DesktopCrashGuard.isUiThread("DefaultDispatcher-worker-3"))
        assertFalse(DesktopCrashGuard.isUiThread("OkHttp ConnectionPool"))
        assertFalse(DesktopCrashGuard.isUiThread("pty-reader"))
        assertFalse(DesktopCrashGuard.isUiThread("mainRelayLoop")) // prefix-matching must not overreach
    }

    @Test
    fun jdkTrayNpeIsBenignButNothingBroaderIs() {
        // the JDK's own TrayIcon-click NPE (LightweightDispatcher dereferences a Component a TrayIcon
        // event never carries) — matched by its exact shape, so it stops quitting the app…
        val jdkShape = NullPointerException("Cannot invoke \"java.awt.Component.isShowing()\"").apply {
            stackTrace = arrayOf(
                StackTraceElement("java.awt.LightweightDispatcher", "eventDispatched", null, -1),
                StackTraceElement("java.awt.Toolkit\$SelectiveAWTEventListener", "eventDispatched", null, -1),
                StackTraceElement("java.awt.TrayIcon", "dispatchEvent", null, -1),
            )
        }
        assertTrue(DesktopCrashGuard.isBenignJdkTrayNpe(jdkShape))
        // …while an NPE of OURS raised during tray handling keeps its fatal path: same dispatcher
        // frame but no TrayIcon on the stack, or app frames on top, must not slip through the pardon
        val sameTopNoTray = NullPointerException("x").apply {
            stackTrace = arrayOf(StackTraceElement("java.awt.LightweightDispatcher", "eventDispatched", null, -1))
        }
        assertFalse(DesktopCrashGuard.isBenignJdkTrayNpe(sameTopNoTray))
        val appOnTop = NullPointerException("x").apply {
            stackTrace = arrayOf(
                StackTraceElement("dev.ccpocket.app.desktop.MenuBarExtraKt", "onClick", null, -1),
                StackTraceElement("java.awt.TrayIcon", "dispatchEvent", null, -1),
            )
        }
        assertFalse(DesktopCrashGuard.isBenignJdkTrayNpe(appOnTop))
        assertFalse(DesktopCrashGuard.isBenignJdkTrayNpe(IllegalStateException("not an NPE at all")))
    }

    @Test
    fun noteAppendsWithoutThrowingAndKeepsEarlierEntries() {
        val before = DesktopCrashGuard.logFile.takeIf { it.exists() }?.readText().orEmpty()
        DesktopCrashGuard.note("CCP-QR-01", RuntimeException("first note"))
        DesktopCrashGuard.note("CCP-QR-01", RuntimeException("second note"), "where=test")
        val after = DesktopCrashGuard.logFile.readText()
        assertTrue(after.startsWith(before), "note() must append, never rewrite")
        assertTrue(after.contains("first note") && after.contains("second note"), after)
        assertTrue(after.contains("where=test"), after)
    }
}
