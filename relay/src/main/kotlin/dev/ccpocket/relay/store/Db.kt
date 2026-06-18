package dev.ccpocket.relay.store

import java.sql.Connection
import java.sql.DriverManager

/** Opens the SQLite database, applies durability/concurrency pragmas, and bootstraps the schema. */
object Db {
    /**
     * @param path filesystem path, or ":memory:" for an ephemeral in-process db.
     * WAL + a single shared write connection (serialized by [SqliteRelayStore]) is plenty for the
     * personal/small-team scale; revisit if this ever needs real write concurrency.
     */
    fun open(path: String): Connection {
        Class.forName("org.sqlite.JDBC")
        val url = if (path == ":memory:") "jdbc:sqlite::memory:" else "jdbc:sqlite:$path"
        val conn = DriverManager.getConnection(url)
        conn.autoCommit = true
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA synchronous=NORMAL")
            st.execute("PRAGMA foreign_keys=ON")
            st.execute("PRAGMA busy_timeout=5000")
        }
        conn.createStatement().use { st -> SCHEMA.split(";").forEach { sql -> sql.trim().takeIf { it.isNotEmpty() }?.let(st::execute) } }
        migrate(conn)
        return conn
    }

    /** Additive, idempotent migrations for databases created before a column existed. SQLite has no
     *  `ADD COLUMN IF NOT EXISTS`, so we ALTER and swallow the "duplicate column" error on re-run. */
    private fun migrate(conn: Connection) {
        listOf(
            "ALTER TABLE devices ADD COLUMN push_platform TEXT",
            "ALTER TABLE devices ADD COLUMN push_token TEXT",
            "ALTER TABLE devices ADD COLUMN push_updated_at INTEGER",
        ).forEach { sql -> runCatching { conn.createStatement().use { it.execute(sql) } } }
    }

    /** Only fingerprints, public keys, and hashes — never content, never private keys. */
    private val SCHEMA = """
        CREATE TABLE IF NOT EXISTS accounts (
          account_id    TEXT PRIMARY KEY,
          static_pubkey BLOB NOT NULL,
          created_at    INTEGER NOT NULL,
          last_seen     INTEGER
        );
        CREATE TABLE IF NOT EXISTS devices (
          device_id       TEXT PRIMARY KEY,
          account_id      TEXT NOT NULL REFERENCES accounts(account_id) ON DELETE CASCADE,
          device_pubkey   BLOB NOT NULL,
          credential_hash BLOB NOT NULL,
          created_at      INTEGER NOT NULL,
          last_seen       INTEGER,
          revoked         INTEGER NOT NULL DEFAULT 0,
          push_platform   TEXT,
          push_token      TEXT,
          push_updated_at INTEGER
        );
        CREATE INDEX IF NOT EXISTS idx_devices_account ON devices(account_id);
        CREATE TABLE IF NOT EXISTS pairing_tickets (
          ticket_hash BLOB PRIMARY KEY,
          account_id  TEXT NOT NULL REFERENCES accounts(account_id) ON DELETE CASCADE,
          created_at  INTEGER NOT NULL,
          expires_at  INTEGER NOT NULL,
          used        INTEGER NOT NULL DEFAULT 0,
          used_at     INTEGER
        );
        CREATE INDEX IF NOT EXISTS idx_tickets_expiry ON pairing_tickets(expires_at);
    """.trimIndent()
}
