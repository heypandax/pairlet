package dev.ccpocket.daemon.util

import java.util.Properties

/** The daemon's own version, baked in at build time (gradle expands -PappVersion into the
 *  cc-pocket-version.properties resource). Single runtime source — the Codex clientInfo string
 *  and the self-update check both read it, so it can't drift from the release version. */
object DaemonVersion {
    val CURRENT: String by lazy {
        runCatching {
            DaemonVersion::class.java.getResourceAsStream("/cc-pocket-version.properties")?.use { s ->
                Properties().apply { load(s) }.getProperty("version")
            }
        }.getOrNull()
            // an unexpanded ${appVersion} means resource filtering didn't run (odd IDE build) — treat as dev
            ?.takeIf { it.isNotBlank() && !it.contains("\${") } ?: "0.0.0-dev"
    }
}
