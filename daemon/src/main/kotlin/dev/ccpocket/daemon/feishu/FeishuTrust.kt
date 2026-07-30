package dev.ccpocket.daemon.feishu

import dev.ccpocket.protocol.PocketJson
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * The chats whose members may run WITHOUT a per-request approval card (issue #198), in the engine's own
 * state dir — `feishu-trust.json`, a chat id → PROJECT map.
 *
 * Keyed on the project, not just the chat, because trust is granted for a (chat, project) PAIR: the owner
 * frees a group to work on the repo they had in mind. A chat can be re-pointed at another allow-listed
 * project by the /bind authority — which may be the Feishu GROUP OWNER, not the machine owner — so a bare
 * chat-id key would let a rebind silently carry card-free execution onto a project nobody trusted it with.
 * Matching on the CURRENT binding voids the grant instead, and needs no hook on bind/unbind to stay honest.
 *
 * Why a separate file from [FeishuRoutes] rather than a field on the binding: the two are set by different
 * authorities and carry different weight (a binding only selects an already allow-listed project; trust
 * waives the machine owner's own review of what runs). Keeping them apart also makes the fail-safe direction
 * obvious — an unreadable or absent trust file means "nothing is trusted", the default posture anyway.
 *
 * Two independent conditions must BOTH hold before a request skips its card, and this file is only the
 * second one — the owner also has to enable the feature per bridge (FEISHU_NO_APPROVAL, which lives in the
 * owner-only bridge spec the chat side cannot write). So a stale entry from before the master switch was
 * turned off grants nothing.
 */
class FeishuTrust(private val path: File) {
    private val chats = LinkedHashMap<String, String>() // chat_id -> the project it was trusted FOR

    init {
        if (path.exists()) {
            // Unlike the routes table a corrupt trust file must NOT fail the engine start: the safe reading of
            // "I can't tell which chats are trusted" is "none are", and refusing to start would take the whole
            // bridge down over an optional convenience.
            runCatching { chats.putAll(PocketJson.decodeFromString<Map<String, String>>(path.readText())) }
        }
    }

    /** True only if this chat was trusted for the project it is bound to RIGHT NOW. */
    @Synchronized fun isTrusted(chatId: String, workdir: String): Boolean = chats[chatId] == workdir

    /** Any trust mark for this chat, whatever project it names — for the chat-side "already on?" answer. */
    @Synchronized fun trustedProject(chatId: String): String? = chats[chatId]

    @Synchronized fun trust(chatId: String, workdir: String): Boolean {
        if (chats[chatId] == workdir) return false
        return write(chats + (chatId to workdir)).also { if (it) { chats[chatId] = workdir } }
    }

    @Synchronized fun untrust(chatId: String): Boolean {
        if (chatId !in chats) return false
        // Write BEFORE mutating memory, unlike trust(): a revocation that updated memory and then failed to
        // flush would look revoked until the next daemon start and then quietly come back. Failing to revoke
        // must therefore fail LOUDLY (returns false → the chat is told it did not take), never silently.
        return write(chats - chatId).also { if (it) chats.remove(chatId) }
    }

    @Synchronized fun size(): Int = chats.size

    private fun write(next: Map<String, String>): Boolean =
        runCatching { writeOwnerOnly(path, PocketJson.encodeToString(next)) }.isSuccess
}

/**
 * The DURABLE record of what ran card-free (issue #198) — one line per trusted execution and per trust
 * change, appended to `feishu-trust.log` beside the trust table.
 *
 * Why this exists rather than leaning on the adapter log: with no-approval on, no [dev.ccpocket.protocol.PermissionAsk]
 * is emitted, so the owner gets no push and no card — the accountability trail is ALL that is left of their
 * oversight. The runner's log tail is a 200-line in-memory ring that a hundred later messages evict and a
 * restart wipes, which is a debug buffer, not a record. This file survives both.
 *
 * Best-effort by construction: a failure to append must never block or fail the turn (the owner already
 * authorized this chat), so every error is swallowed. It is rotated once at [MAX_BYTES] so an active group
 * cannot grow it without bound — one generation back is kept, which is the same trade the ring makes, just
 * measured in megabytes instead of lines.
 */
class FeishuTrustLog(private val path: File) {
    @Synchronized fun record(line: String) {
        runCatching {
            path.parentFile?.mkdirs()
            if (path.length() > MAX_BYTES) {
                val prev = File(path.parentFile, "${path.name}.1")
                prev.delete()
                // a rotation that FAILED must not be followed by an append: that would grow the file without
                // bound, which is the one thing the cap exists to prevent. Dropping this line is the lesser
                // evil — and the runner's log ring still carries it.
                if (!path.renameTo(prev)) return
            }
            // the prompt head in this line is attacker-controlled: \n would forge a record, and \r would
            // let it visually overwrite the previous one in a terminal `cat`. Both collapse to a space.
            path.appendText(line.replace('\n', ' ').replace('\r', ' ') + "\n")
            runCatching {
                java.nio.file.Files.setPosixFilePermissions(
                    path.toPath(),
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
                )
            }
        }
    }

    companion object {
        const val MAX_BYTES = 2L * 1024 * 1024
    }
}
