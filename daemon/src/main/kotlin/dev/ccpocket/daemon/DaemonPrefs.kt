package dev.ccpocket.daemon

import dev.ccpocket.daemon.identity.Identity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Daemon-side user preferences, persisted beside identity.json (~/.cc-pocket/prefs.json).
 * Currently just the phone-push switch (pocket/push.prefs.set): pushEnabled=false silences the
 * relay's "turn complete" phone alerts while someone is working at the computer — set from a
 * client's Settings, honored by RelayClient's push hook.
 */
class DaemonPrefs private constructor(private val path: File) {
    @Serializable
    private data class Stored(val pushEnabled: Boolean = true)

    @Volatile
    var pushEnabled: Boolean = true
        private set

    fun setPushEnabled(v: Boolean) {
        pushEnabled = v
        runCatching {
            path.parentFile?.mkdirs()
            path.writeText(JSON.encodeToString(Stored(pushEnabled)))
        }
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        fun defaultPath(): File = File(Identity.defaultPath().parentFile, "prefs.json")

        fun load(path: File = defaultPath()): DaemonPrefs = DaemonPrefs(path).apply {
            if (path.exists()) runCatching { pushEnabled = JSON.decodeFromString<Stored>(path.readText()).pushEnabled }
        }
    }
}
