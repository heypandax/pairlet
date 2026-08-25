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
    private data class Stored(
        val pushEnabled: Boolean = true,
        val isolatedClaudeAuth: Boolean = false,
        val askNoAutoDeny: Boolean = false,
        val fullControlExpiryMs: Long = 0L,
        val autoUpdate: Boolean? = null,
    )

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

    /** Issue #201: the owner's OWN approval asks wait for a manual decision instead of auto-denying.
     *  Persisted here (not client-side) because the daemon is what runs the timeout — a client-local
     *  copy would drift the moment a second device flipped it. Mirrored into ApprovalTimeout.noAutoDeny,
     *  which is what the per-ask read actually consults. */
    @Volatile
    var askNoAutoDeny: Boolean = false
        private set

    /** Issue #220: how long the owner's manually-entered Full Control lasts before auto-reverting to the
     *  default ask-driven mode. 0 = never expires (the default — a manual Full Control is a deliberate
     *  authorization). Persisted here (not client-side) because the daemon is what runs the expiry clock;
     *  mirrored into ApprovalTimeout.fullControlExpiryMs, which each Conversation's arm-expiry read consults. */
    @Volatile
    var fullControlExpiryMs: Long = 0L
        private set

    /** Issue #244: whether the daemon applies new versions by itself. null = never set — the caller
     *  ([UpdateChecker.resolveAutoApply]) then picks the built-in default (on). Set explicitly via
     *  `config --auto-update on|off`; that's the opt-out for someone who wants to pick their own moment.
     *  A `true` here still only reaches an actual self-update through UpdateChecker's managed-install /
     *  service-anchored / non-Windows guards. */
    @Volatile
    var autoUpdate: Boolean? = null
        private set

    fun setAutoUpdate(v: Boolean?) {
        autoUpdate = v
        persist()
    }

    fun setIsolatedClaudeAuth(v: Boolean) {
        isolatedClaudeAuth = v
        persist()
    }

    fun setAskNoAutoDeny(v: Boolean) {
        askNoAutoDeny = v
        persist()
    }

    fun setFullControlExpiryMs(v: Long) {
        fullControlExpiryMs = v.coerceAtLeast(0L)
        persist()
    }

    private fun persist() {
        runCatching {
            path.parentFile?.mkdirs()
            path.writeText(JSON.encodeToString(Stored(pushEnabled, isolatedClaudeAuth, askNoAutoDeny, fullControlExpiryMs, autoUpdate)))
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
                askNoAutoDeny = s.askNoAutoDeny
                fullControlExpiryMs = s.fullControlExpiryMs
                autoUpdate = s.autoUpdate
            }
        }
    }
}
