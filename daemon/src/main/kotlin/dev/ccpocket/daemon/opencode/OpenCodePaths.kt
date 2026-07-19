package dev.ccpocket.daemon.opencode

import java.nio.file.Path

/**
 * OpenCode stores data under `~/.local/share/opencode/` (XDG data dir). Sessions are in a SQLite
 * database, but the key path is needed for transcript scanning and directory listing.
 */
object OpenCodePaths {
    fun dataRoot(): Path =
        System.getenv("OPENCODE_DATA_DIR")?.let { Path.of(it) }
            ?: System.getenv("XDG_DATA_HOME")?.let { Path.of(it, "opencode") }
            ?: System.getenv("XDG_STATE_HOME")?.let { Path.of(it, "opencode") }
            ?: Path.of(System.getProperty("user.home"), ".local", "share", "opencode")

    fun configRoot(): Path =
        System.getenv("OPENCODE_CONFIG_DIR")?.let { Path.of(it) }
            ?: Path.of(System.getProperty("user.home"), ".config", "opencode")

    /** The SQLite database containing sessions. */
    fun database(): Path = dataRoot().resolve("opencode.db")

    /** The auth credentials file. */
    fun authFile(): Path = dataRoot().resolve("auth.json")

    /**
     * READ-ONLY, busy-tolerant connection to opencode.db, or null when it doesn't exist. opencode
     * itself may be mid-write (WAL) at any moment — a default read-write open can hit
     * SQLITE_BUSY instantly and the runCatching callers would degrade to an EMPTY list, i.e. the
     * session list intermittently vanishing whenever opencode is active. Read-only mode + a busy
     * timeout rides out write bursts instead.
     */
    fun connectReadOnly(): java.sql.Connection? {
        val db = database()
        if (!java.nio.file.Files.exists(db)) return null
        val cfg = org.sqlite.SQLiteConfig().apply {
            setReadOnly(true)
            busyTimeout = 1_500
        }
        return java.sql.DriverManager.getConnection("jdbc:sqlite:${db.toAbsolutePath()}", cfg.toProperties())
    }
}
