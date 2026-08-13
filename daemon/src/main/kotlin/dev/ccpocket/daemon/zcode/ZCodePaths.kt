package dev.ccpocket.daemon.zcode

import java.nio.file.Path
import java.nio.file.Files

/** Probe-observed ZCode 3.7.6 CLI state locations. Unknown store details stay intentionally absent. */
object ZCodePaths {
    fun home(): Path = Path.of(System.getProperty("user.home"), ".zcode")
    fun cliConfig(): Path = home().resolve("cli").resolve("config.json")
    fun database(): Path = home().resolve("cli").resolve("db").resolve("db.sqlite")

    fun connectReadOnly(): java.sql.Connection? {
        val db = database()
        if (!Files.exists(db)) return null
        val cfg = org.sqlite.SQLiteConfig().apply { setReadOnly(true); busyTimeout = 1_500 }
        return java.sql.DriverManager.getConnection("jdbc:sqlite:${db.toAbsolutePath()}", cfg.toProperties())
    }
}
