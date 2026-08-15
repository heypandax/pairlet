package dev.ccpocket.app

import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.dia_launch_failed
import dev.ccpocket.app.resources.dia_no_answer
import dev.ccpocket.app.resources.dia_not_supported
import dev.ccpocket.app.resources.dia_ready
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

private val isMac = System.getProperty("os.name").lowercase().contains("mac")
private val diaApp = File("/Applications/Dia.app")

actual fun diaCdpSupported(): Boolean = isMac && diaApp.isDirectory

actual suspend fun launchDiaCdp(port: Int): DiaCdpResult = withContext(Dispatchers.IO) {
    // messages surface in the composer hint row — resolved via suspend getString (non-composable context,
    // same pattern as RepoDesktopModel's update failures)
    // #251: every lookup here is contained — this runs detached, and a throwing resource read on a
    // coroutine with no handler is what took the desktop window down with an unnamed native error box.
    if (!diaCdpSupported()) {
        return@withContext DiaCdpResult(
            false,
            dev.ccpocket.app.data.safeString("Dia isn't installed (or this isn't macOS)") {
                getString(Res.string.dia_not_supported)
            },
        )
    }
    runCatching {
        // 1. QUIT any running Dia first. A flag-carrying launch is IGNORED while an instance is alive
        //    (Chromium forwards it to the existing process and the debug port never opens) — the key trap.
        //    Graceful quit (lets it save), then force any straggler, then confirm it's actually gone.
        runCatching { ProcessBuilder("osascript", "-e", "quit app \"Dia\"").start().waitFor() }
        var waited = 0
        while (diaRunning() && waited < 3000) { delay(250); waited += 250 }
        if (diaRunning()) {
            runCatching { ProcessBuilder("pkill", "-x", "Dia").start().waitFor() }
            delay(600)
        }
        // 2. Relaunch with the CDP port. -n forces a new instance even if a straggler lingered.
        ProcessBuilder("open", "-na", "Dia", "--args", "--remote-debugging-port=$port").start()
        // 3. Poll the endpoint until it answers — 127.0.0.1, NOT localhost (localhost can resolve to IPv6
        //    ::1 which the port isn't bound to, so it would never connect). Dia's cold start takes a moment.
        //    HttpURLConnection lives in java.base — java.net.http.HttpClient does NOT survive jlink's module
        //    stripping in the packaged desktop runtime (NoClassDefFound at launch), so use the base one.
        val endpoint = URI("http://127.0.0.1:$port/json/version").toURL()
        repeat(24) { // ~12s ceiling
            delay(500)
            val up = runCatching {
                val conn = (endpoint.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 1500; readTimeout = 1500; requestMethod = "GET"
                }
                val code = try { conn.responseCode } finally { conn.disconnect() }
                code == 200
            }.getOrDefault(false)
            if (up) {
                return@withContext DiaCdpResult(
                    true,
                    dev.ccpocket.app.data.safeString("Dia debug port :$port is ready") {
                        getString(Res.string.dia_ready, port)
                    },
                )
            }
        }
        DiaCdpResult(
            false,
            dev.ccpocket.app.data.safeString("Dia relaunched, but :$port never answered (timeout)") {
                getString(Res.string.dia_no_answer, port)
            },
        )
    }.getOrElse { e ->
        DiaCdpResult(
            false,
            dev.ccpocket.app.data.safeString("Launch failed: ${e.message ?: "?"}") {
                getString(Res.string.dia_launch_failed, e.message ?: "?")
            },
        )
    }
}

/** True if a process named exactly "Dia" (the app's main process) is alive. */
private fun diaRunning(): Boolean =
    runCatching { ProcessBuilder("pgrep", "-x", "Dia").start().waitFor() == 0 }.getOrDefault(false)
