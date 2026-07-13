package dev.ccpocket.daemon.disk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
 *     (background-shell lifecycle notices), `queue-operation` bookkeeping lines, and skill/command
 *     injection turns (root-level isMeta:true, or their text fingerprints — issue #126). parentUuid
 *     links are re-stitched across dropped turns so the chain stays intact.
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
    private const val META_TAG = "\"isMeta\":true" // ditto, for skill/command injection rows (issue #126)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private class Row(val raw: String, val uuid: String?, val parentUuid: String?, val leafUuid: String?, val type: String?, val noise: Boolean)

    /** Rewrite [file] in place. True if anything changed; never throws. */
    fun unhide(file: Path): Boolean {
        if (!Files.exists(file)) return false
        val lines = runCatching { Files.readAllLines(file) }.getOrNull() ?: return false

        // cheap substring prefilter: the file can only change if it carries an sdk-cli tag or a noise
        // marker. skips JSON-parsing every line on the common no-op (already-cli, noise-free) + re-runs.
        val hasSdk = lines.any { it.contains(SDK_TAG) }
        val maybeNoise = lines.any { line ->
            line.contains(QUEUE_OP_TAG) || line.contains(TranscriptNoise.TN_OPEN) || line.contains(META_TAG) ||
                line.contains(TranscriptNoise.SKILL_INJECTION_PREFIX) ||
                TranscriptNoise.COMMAND_WRAPPER_TAGS.any(line::contains)
        }
        if (!hasSdk && !maybeNoise) return false

        val rows = lines.map(::classify) // parse only once we know something might actually change

        // uuid -> parentUuid and uuid -> type for the whole file
        val parentOf = HashMap<String, String?>()
        val typeOf = HashMap<String, String?>()
        rows.forEach { if (it.uuid != null) { parentOf[it.uuid] = it.parentUuid; typeOf[it.uuid] = it.type } }

        // The set of dropped (uuid-bearing) noise turns to re-link past — EXCEPT a noise turn whose parent is
        // a `type:"system"` record (a /compact compact_boundary, or a pre-prompt local_command). Dropping that
        // turn would relink the surviving child straight onto the system record; `claude --resume` then can't
        // rebuild the API prompt and fails the whole turn with 400 "System message must be at the beginning"
        // (issue #24). Keeping it leaves claude's own well-formed chain (system → user → assistant) intact —
        // the only cost is one stray task-notification bubble on a desktop resume, in that rare shape.
        val dropped = rows.asSequence()
            .filter { it.noise && it.uuid != null }
            .filterNot { it.parentUuid != null && typeOf[it.parentUuid] == "system" }
            .mapNotNull { it.uuid }
            .toHashSet()

        if (!hasSdk && dropped.isEmpty()) return false // nothing left to rewrite (false positives, or all kept)

        // preserve the transcript's real last-activity mtime across the rewrite: unhide is daemon bookkeeping,
        // not a new turn. If the rewrite bumped mtime, a just-reaped phone session would look freshly written and
        // re-opening it would read as a live foreign session — the phone shows a bogus "Continue here"/take-over
        // (and take-over would even fork a duplicate). See SessionRegistry's externallyActive guard.
        val originalMtime = runCatching { Files.getLastModifiedTime(file) }.getOrNull()
        val tmp = file.resolveSibling("${file.fileName}.pocket-tmp")
        return try {
            Files.newBufferedWriter(tmp).use { w ->
                for (row in rows) {
                    // drop noise, but keep a noise turn rescued from `dropped` by the system-parent guard
                    // (uuid-less noise like queue-operation is always dropped — it can't be a relink target)
                    if (row.noise && (row.uuid == null || row.uuid in dropped)) continue
                    var out = row.raw
                    if (row.parentUuid != null && row.parentUuid in dropped) {
                        out = relinkParent(out, row.parentUuid, resolveSurvivor(row.parentUuid, dropped, parentOf))
                    }
                    // a summary's leafUuid can point AT a dropped noise turn — remap it to the nearest surviving
                    // ancestor, else the summary dangles and claude loses the branch linkage on resume
                    if (row.leafUuid != null && row.leafUuid in dropped) {
                        out = relinkLeaf(out, row.leafUuid, resolveSurvivor(row.leafUuid, dropped, parentOf))
                    }
                    if (out.contains(SDK_TAG)) out = out.replace(SDK_TAG, CLI_TAG)
                    w.write(out); w.newLine()
                }
            }
            runCatching { Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------")) }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            originalMtime?.let { runCatching { Files.setLastModifiedTime(file, it) } } // rewrite must not read as fresh activity
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

    private fun relinkLeaf(line: String, old: String, new: String?): String =
        line.replace("\"leafUuid\":\"$old\"", if (new == null) "\"leafUuid\":null" else "\"leafUuid\":\"$new\"")

    private fun classify(line: String): Row {
        val obj = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull()
            ?: return Row(line, null, null, null, null, noise = false)
        val type = str(obj["type"])
        val noise = when (type) {
            "queue-operation" -> true
            "user" -> isNoiseUserTurn(obj)
            else -> false
        }
        return Row(line, str(obj["uuid"]), str(obj["parentUuid"]), str(obj["leafUuid"]), type, noise)
    }

    /** True for a user record that is harness plumbing, not the user typing: a pure task-notification
     *  turn, or a skill/command injection — root-level isMeta:true, or the text-fingerprint fallback
     *  for isMeta-less older CLIs (issue #126). A row carrying a tool_result is NEVER noise: dropping
     *  it would orphan the matching tool_use and 400 the resumed API chain. */
    private fun isNoiseUserTurn(obj: JsonObject): Boolean {
        val text = userText(obj)
        if (TranscriptNoise.isPureTaskNotification(text)) return true
        if (hasToolResult(obj)) return false
        val meta = (obj["isMeta"] as? JsonPrimitive)?.booleanOrNull == true
        return meta || TranscriptNoise.isInjectedHarnessText(text)
    }

    private fun hasToolResult(obj: JsonObject): Boolean {
        val content = (obj["message"] as? JsonObject)?.get("content") as? JsonArray ?: return false
        return content.any { str((it as? JsonObject)?.get("type")) == "tool_result" }
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
