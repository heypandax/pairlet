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
    private data class Stored(val pushEnabled: Boolean = true, val isolatedClaudeAuth: Boolean = false)

    @Volatile
    var pushEnabled: Boolean = true
        private set

    /** Give the daemon's claude its own credential store (CLAUDE_CONFIG_DIR — issue #69) so its OAuth
     *  refreshes can't log out a terminal claude. Read at daemon startup; toggled via `config`. */
    @Volatile
    var isolatedClaudeAuth: Boolean = false
        private set

    fun setPushEnabled(v: Boolean) {
        pushEnabled = v
        persist()
    }

    fun setIsolatedClaudeAuth(v: Boolean) {
        isolatedClaudeAuth = v
        persist()
    }

    private fun persist() {
        runCatching {
            path.parentFile?.mkdirs()
            path.writeText(JSON.encodeToString(Stored(pushEnabled, isolatedClaudeAuth)))
        }
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        fun defaultPath(): File = File(Identity.defaultPath().parentFile, "prefs.json")

        fun load(path: File = defaultPath()): DaemonPrefs = DaemonPrefs(path).apply {
            if (path.exists()) runCatching {
                val s = JSON.decodeFromString<Stored>(path.readText())
                pushEnabled = s.pushEnabled
                isolatedClaudeAuth = s.isolatedClaudeAuth
            }
        }
    }
}
