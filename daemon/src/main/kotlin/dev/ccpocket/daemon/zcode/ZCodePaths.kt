package dev.ccpocket.daemon.zcode

import java.nio.file.Path
import java.nio.file.Files

/** Probe-observed ZCode CLI state locations. Unknown store details stay intentionally absent. */
object ZCodePaths {
    fun home(): Path = Path.of(System.getProperty("user.home"), ".zcode")
    fun cliConfig(): Path = home().resolve("cli").resolve("config.json")

    /**
     * The desktop app's own store, introduced around ZCode 3.9. Verified on 3.11.2: this is where the UI
     * keeps providers and credentials, while `~/.zcode/cli` stays the *runtime's* directory — the session
     * database below is still written there on every turn, so the two live side by side rather than one
     * superseding the other.
     */
    fun v2Config(): Path = home().resolve("v2").resolve("config.json")

    /**
     * Where a provider catalog can actually be read from, newest generation first.
     *
     * On 3.9+ only [v2Config] exists (the desktop stopped writing the runtime's `cli/config.json`), on 3.7.6
     * only [cliConfig] did. Falling back rather than picking one keeps both generations working, and keeps
     * a stale 3.7.6 leftover from shadowing the store the user actually edits today.
     */
    fun providerConfig(): Path = v2Config().takeIf { Files.isRegularFile(it) } ?: cliConfig()

    fun database(): Path = home().resolve("cli").resolve("db").resolve("db.sqlite")

    fun connectReadOnly(): java.sql.Connection? {
        val db = database()
        if (!Files.exists(db)) return null
        val cfg = org.sqlite.SQLiteConfig().apply { setReadOnly(true); busyTimeout = 1_500 }
        return java.sql.DriverManager.getConnection("jdbc:sqlite:${db.toAbsolutePath()}", cfg.toProperties())
    }
}
