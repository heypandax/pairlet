package dev.ccpocket.daemon.disk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Prepares a phone-born (`-p`) transcript for the desktop `claude --resume` picker:
 *
 *  1. claude tags headless transcripts `"entrypoint":"sdk-cli"` and the desktop picker (since 2.1.90)
 *     hides those — we rewrite the tag to interactive `"cli"` so the session shows up.
 *  2. drops harness-injected NOISE the phone session accumulated, so a desktop resume replays the real
 *     conversation instead of background-task chatter: standalone `<task-notification>` user turns
 *     (background-shell lifecycle notices) and `queue-operation` bookkeeping lines. parentUuid links are
 *     re-stitched across dropped turns so the chain stays intact.
 *
 * We deliberately do NOT drop `<system-reminder>` turns: those are routinely PREPENDED to a real user
 * message, so removing by prefix would eat genuine input. A `<task-notification>` turn is only dropped
 * when nothing but notification blocks remain — any real text after them keeps the turn.
 *
 * Only safe once the writing claude process has exited (replacing the file under a live process drops its
 * appends). In place, atomic replace, 0600 like claude's own. Never throws.
 */
object TranscriptPatcher {
    private const val SDK_TAG = "\"entrypoint\":\"sdk-cli\""
    private const val CLI_TAG = "\"entrypoint\":\"cli\""
    private const val QUEUE_OP_TAG = "\"queue-operation\"" // cheap substring marker for the noise prefilter
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private class Row(val raw: String, val uuid: String?, val parentUuid: String?, val noise: Boolean)

    /** Rewrite [file] in place. True if anything changed; never throws. */
    fun unhide(file: Path): Boolean {
        if (!Files.exists(file)) return false
        val lines = runCatching { Files.readAllLines(file) }.getOrNull() ?: return false

        // cheap substring prefilter: the file can only change if it carries an sdk-cli tag or a noise
        // marker. skips JSON-parsing every line on the common no-op (already-cli, noise-free) + re-runs.
        val hasSdk = lines.any { it.contains(SDK_TAG) }
        val maybeNoise = lines.any { it.contains(QUEUE_OP_TAG) || it.contains(TranscriptNoise.TN_OPEN) }
        if (!hasSdk && !maybeNoise) return false

        val rows = lines.map(::classify) // parse only once we know something might actually change
        if (!hasSdk && rows.none { it.noise }) return false // markers were false positives (e.g. tag quoted in text)

        // uuid -> parentUuid for the whole file, and the set of dropped (uuid-bearing) turns to re-link past
        val parentOf = HashMap<String, String?>()
        rows.forEach { if (it.uuid != null) parentOf[it.uuid] = it.parentUuid }
        val dropped = rows.asSequence().filter { it.noise && it.uuid != null }.mapNotNull { it.uuid }.toHashSet()

        val tmp = file.resolveSibling("${file.fileName}.pocket-tmp")
        return try {
            Files.newBufferedWriter(tmp).use { w ->
                for (row in rows) {
                    if (row.noise) continue
                    var out = row.raw
                    if (row.parentUuid != null && row.parentUuid in dropped) {
                        out = relinkParent(out, row.parentUuid, resolveSurvivor(row.parentUuid, dropped, parentOf))
                    }
                    if (out.contains(SDK_TAG)) out = out.replace(SDK_TAG, CLI_TAG)
                    w.write(out); w.newLine()
                }
            }
            runCatching { Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------")) }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    /** Walk up parentUuid until the first surviving turn (or null at the root). Cycle-guarded. */
    private fun resolveSurvivor(start: String?, dropped: Set<String>, parentOf: Map<String, String?>): String? {
        var u = start
        val seen = HashSet<String>()
        while (u != null && u in dropped) {
            if (!seen.add(u)) return null
            u = parentOf[u]
        }
        return u
    }

    // transcript lines are compact JSON (no spaces), so the parentUuid token is an exact substring
    private fun relinkParent(line: String, old: String, new: String?): String =
        line.replace("\"parentUuid\":\"$old\"", if (new == null) "\"parentUuid\":null" else "\"parentUuid\":\"$new\"")

    private fun classify(line: String): Row {
        val obj = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull()
            ?: return Row(line, null, null, noise = false)
        val type = str(obj["type"])
        val noise = when (type) {
            "queue-operation" -> true
            "user" -> TranscriptNoise.isPureTaskNotification(userText(obj))
            else -> false
        }
        return Row(line, str(obj["uuid"]), str(obj["parentUuid"]), noise)
    }

    private fun userText(obj: JsonObject): String? {
        val msg = obj["message"] as? JsonObject ?: return null
        return when (val content = msg["content"]) {
            is JsonPrimitive -> if (content.isString) content.content else null
            is JsonArray -> content.mapNotNull { str((it as? JsonObject)?.get("text")) }.joinToString("\n").ifEmpty { null }
            else -> null
        }
    }

    private fun str(el: kotlinx.serialization.json.JsonElement?): String? =
        (el as? JsonPrimitive)?.takeIf { it.isString }?.content
}
