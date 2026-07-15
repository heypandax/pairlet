package dev.ccpocket.daemon.opencode

import java.nio.file.Path

/**
 * OpenCode stores data under `~/.local/share/opencode/` (XDG data dir). Sessions are in a SQLite
 * database, but the key path is needed for transcript scanning and directory listing.
 */
object OpenCodePaths {
    fun dataRoot(): Path =
        System.getenv("OPENCODE_DATA_DIR")?.let { Path.of(it) }
            ?: Path.of(System.getProperty("user.home"), ".local", "share", "opencode")

    fun configRoot(): Path =
        System.getenv("OPENCODE_CONFIG_DIR")?.let { Path.of(it) }
            ?: Path.of(System.getProperty("user.home"), ".config", "opencode")

    /** The SQLite database containing sessions. */
    fun database(): Path = dataRoot().resolve("opencode.db")

    /** The auth credentials file. */
    fun authFile(): Path = dataRoot().resolve("auth.json")
}
