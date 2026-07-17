package dev.ccpocket.daemon.disk

import dev.ccpocket.daemon.util.logger
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Appends the user's rename to an IDLE Claude transcript as the CLI's OWN `custom-title` record
 * (issue #158), so claude and every reader adopt it through the existing
 * custom-title → ai-title → firstPrompt fallback ([TranscriptScanner], issue #14).
 *
 * Record shape = exactly what claude's own session-store `renameSession` appends (extracted from
 * CLI 2.1.210; key order included):
 *
 * ```
 * {"type":"custom-title","customTitle":"<trimmed>","sessionId":"<sid>","uuid":"<v4>","timestamp":"<ISO-8601 ms Z>"}
 * ```
 *
 * (The CLI's live `/rename` + `rename_session` control paths write the same record minus uuid/timestamp;
 * its reader matches only `"type":"custom-title"`, and "last record wins" — so a later CLI rename still
 * overrides ours, and ours overrides an earlier one.)
 *
 * ONLY safe when no live process is writing the file — the caller gates on that
 * ([dev.ccpocket.daemon.session.SessionRegistry.renameSession]: a daemon-held live session renames
 * through the CLI itself; an external live writer is refused). The append is a single whole-line
 * write under O_APPEND, and a transcript whose tail lost its newline (a crashed writer) gets one
 * first so the record never merges into a partial line. Never creates the file, never throws.
 */
object TranscriptRename {
    private val log = logger("TranscriptRename")

    // node's Date.toISOString always prints exactly 3 fraction digits — match it byte-for-byte
    // (Instant.toString drops a .000 fraction entirely)
    private val TS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** Append the rename record to [file]. False when the transcript is missing or the write failed. */
    fun append(file: Path, sessionId: String, title: String): Boolean {
        if (!Files.isRegularFile(file)) return false
        val record = buildJsonObject {
            put("type", "custom-title")
            put("customTitle", title)
            put("sessionId", sessionId)
            put("uuid", UUID.randomUUID().toString())
            put("timestamp", TS.format(Instant.now()))
        }.toString()
        return runCatching {
            val rescueNewline = !endsWithNewline(file)
            val bytes = (if (rescueNewline) "\n$record\n" else "$record\n").toByteArray(Charsets.UTF_8)
            FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { ch ->
                val buf = ByteBuffer.wrap(bytes)
                while (buf.hasRemaining()) ch.write(buf) // one call in practice — a short line never splits
            }
            true
        }.getOrElse { e ->
            log.warn("rename append failed for $file: ${e.message}")
            false
        }
    }

    /** True for an empty file too — nothing to rescue there. */
    private fun endsWithNewline(file: Path): Boolean {
        val size = Files.size(file)
        if (size == 0L) return true
        FileChannel.open(file, StandardOpenOption.READ).use { ch ->
            val buf = ByteBuffer.allocate(1)
            ch.read(buf, size - 1)
            return buf.array()[0] == '\n'.code.toByte()
        }
    }
}
