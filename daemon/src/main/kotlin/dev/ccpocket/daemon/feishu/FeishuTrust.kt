package dev.ccpocket.daemon.feishu

import dev.ccpocket.protocol.PocketJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import java.io.File

/**
 * The trust MODE a chat's members run under (issues #198 + reviewed-trust design):
 *
 *  - [UNTRUSTED] — every request waits on the machine owner's per-request approval card (the default,
 *    and the meaning of an ABSENT record: fail closed).
 *  - [REVIEWED] — each request is first classified by an independent Guardian Reviewer against the
 *    owner's Trust Contract; only a clear low-risk match runs card-free, under the same closed
 *    tool ceiling as [TRUSTED]. Everything else falls back to the owner's card.
 *  - [TRUSTED] — the owner waived the per-request card for this (chat, project) pair outright.
 */
@Serializable
enum class FeishuTrustMode {
    UNTRUSTED,
    REVIEWED,
    TRUSTED,
}

/** One chat's trust state, keyed on the (chat, project) PAIR — see [FeishuTrust] on why. */
@Serializable
data class FeishuTrustRecord(
    val workdir: String,
    val mode: FeishuTrustMode,
    /** The owner's Trust Contract for a [FeishuTrustMode.REVIEWED] chat; null = the default contract. */
    val purpose: String? = null,
    /** Bumped on every mode/purpose change, so an async review can prove the policy it read still stands. */
    val contractVersion: Long = 1,
    val updatedAtEpochMs: Long = 0,
)

/** The on-disk shape, versioned so the pre-三态 `chatId -> workdir` map can be told apart and migrated. */
@Serializable
data class FeishuTrustFile(
    val version: Int = 2,
    val chats: Map<String, FeishuTrustRecord> = emptyMap(),
)

/** A write's DISTINGUISHABLE outcome: "already in that state" and "couldn't persist" must never read the
 *  same — a silent failure to revoke would quietly come back on the next daemon start. */
enum class TrustWrite { CHANGED, UNCHANGED, WRITE_FAILED }

/**
 * An immutable read of one chat's effective policy at a moment in time. The reviewed request flow takes
 * one BEFORE the (async, seconds-long) Guardian review and re-checks [FeishuTrust.stillMatches] after it:
 * a /untrust, rebind or contract edit that landed mid-review must void the result (TOCTOU), because trust
 * commands deliberately do not wait on the per-chat turn lock.
 */
data class TrustSnapshot(
    val chatId: String,
    val workdir: String,
    val mode: FeishuTrustMode,
    val purpose: String?,
    val contractVersion: Long,
    /** Guards the ABA hole contractVersion alone leaves open: /untrust deletes the record, so a same-args
     *  /review restarts at version 1 and can rebuild a record FIELD-IDENTICAL to the one the in-flight
     *  review snapshotted. The write timestamp differs across that revoke/re-grant, so the data-class
     *  equality in [FeishuTrust.stillMatches] voids the stale result without any extra comparison logic. */
    val updatedAtEpochMs: Long = 0,
)

/**
 * The chats whose members may run with reduced per-request approval (issue #198 + reviewed trust), in the
 * engine's own state dir — `feishu-trust.json`.
 *
 * Keyed on the project, not just the chat, because trust is granted for a (chat, project) PAIR: the owner
 * frees a group to work on the repo they had in mind. A chat can be re-pointed at another allow-listed
 * project by the /bind authority — which may be the Feishu GROUP OWNER, not the machine owner — so a bare
 * chat-id key would let a rebind silently carry reduced-approval execution onto a project nobody trusted
 * it with. Matching on the CURRENT binding voids the grant instead, and needs no hook on bind/unbind to
 * stay honest.
 *
 * Why a separate file from [FeishuRoutes] rather than a field on the binding: the two are set by different
 * authorities and carry different weight (a binding only selects an already allow-listed project; trust
 * waives or conditions the machine owner's own review of what runs). Keeping them apart also makes the
 * fail-safe direction obvious — an unreadable or absent trust file means "nothing is trusted", the default
 * posture anyway.
 *
 * Two independent conditions must BOTH hold before a request skips (or conditions) its card, and this file
 * is only the second one — the owner also has to enable the feature per bridge (FEISHU_NO_APPROVAL, which
 * lives in the owner-only bridge spec the chat side cannot write). So a stale entry from before the master
 * switch was turned off grants nothing.
 */
class FeishuTrust(private val path: File) {
    private val chats = LinkedHashMap<String, FeishuTrustRecord>()

    init {
        if (path.exists()) {
            // Unlike the routes table a corrupt trust file must NOT fail the engine start: the safe reading of
            // "I can't tell which chats are trusted" is "none are", and refusing to start would take the whole
            // bridge down over an optional convenience. The corrupt file is left in place (never overwritten by
            // the failed read itself); the next successful write regenerates it as v2.
            runCatching { chats.putAll(parse(path.readText())) }
        }
    }

    /** True only if this chat is fully TRUSTED for the project it is bound to RIGHT NOW. */
    @Synchronized fun isTrusted(chatId: String, workdir: String): Boolean =
        modeFor(chatId, workdir) == FeishuTrustMode.TRUSTED

    /** The chat's effective mode for THIS project — a record naming another workdir grants nothing. */
    @Synchronized fun modeFor(chatId: String, workdir: String): FeishuTrustMode =
        chats[chatId]?.takeIf { it.workdir == workdir }?.mode ?: FeishuTrustMode.UNTRUSTED

    /** Any trust record for this chat, whatever project it names — for /untrust revocation and /trust-status. */
    @Synchronized fun recordFor(chatId: String): FeishuTrustRecord? = chats[chatId]

    /** Any trust mark for this chat, whatever project it names — for the chat-side "already on?" answer. */
    @Synchronized fun trustedProject(chatId: String): String? = chats[chatId]?.workdir

    /** Immutable policy read for the reviewed flow's before/after comparison (see [TrustSnapshot]). An
     *  absent or other-project record reads as UNTRUSTED with contractVersion 0. */
    @Synchronized fun snapshot(chatId: String, workdir: String): TrustSnapshot {
        val r = chats[chatId]?.takeIf { it.workdir == workdir }
        return TrustSnapshot(
            chatId = chatId,
            workdir = workdir,
            mode = r?.mode ?: FeishuTrustMode.UNTRUSTED,
            purpose = r?.purpose,
            contractVersion = r?.contractVersion ?: 0,
            updatedAtEpochMs = r?.updatedAtEpochMs ?: 0,
        )
    }

    /** Did nothing change since [snap] was taken? False voids an in-flight review result (revoke race). */
    @Synchronized fun stillMatches(chatId: String, workdir: String, snap: TrustSnapshot): Boolean =
        snapshot(chatId, workdir) == snap

    @Synchronized fun trust(chatId: String, workdir: String): TrustWrite =
        put(chatId, workdir, FeishuTrustMode.TRUSTED, purpose = null)

    @Synchronized fun setReviewed(chatId: String, workdir: String, purpose: String?): TrustWrite =
        put(chatId, workdir, FeishuTrustMode.REVIEWED, purpose = purpose?.trim()?.take(MAX_PURPOSE_CHARS)?.takeIf { it.isNotEmpty() })

    @Synchronized fun untrust(chatId: String): TrustWrite {
        if (chatId !in chats) return TrustWrite.UNCHANGED
        // Deleting (not writing an UNTRUSTED row) keeps the default state fail-closed. Write BEFORE mutating
        // memory, unlike put(): a revocation that updated memory and then failed to flush would look revoked
        // until the next daemon start and then quietly come back. Failing to revoke must therefore fail
        // LOUDLY (WRITE_FAILED → the chat is told it did not take), never silently.
        if (!write(chats - chatId)) return TrustWrite.WRITE_FAILED
        chats.remove(chatId)
        return TrustWrite.CHANGED
    }

    @Synchronized fun size(): Int = chats.size

    private fun put(chatId: String, workdir: String, mode: FeishuTrustMode, purpose: String?): TrustWrite {
        val existing = chats[chatId]
        if (existing != null && existing.workdir == workdir && existing.mode == mode && existing.purpose == purpose) {
            return TrustWrite.UNCHANGED
        }
        val next = FeishuTrustRecord(
            workdir = workdir,
            mode = mode,
            purpose = purpose,
            // any change to the policy (mode, purpose, or the project it names) invalidates in-flight reviews
            contractVersion = (existing?.contractVersion ?: 0) + 1,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        if (!write(chats + (chatId to next))) return TrustWrite.WRITE_FAILED
        chats[chatId] = next
        return TrustWrite.CHANGED
    }

    private fun write(next: Map<String, FeishuTrustRecord>): Boolean =
        runCatching { writeOwnerOnly(path, PocketJson.encodeToString(FeishuTrustFile(chats = next))) }.isSuccess

    companion object {
        const val MAX_PURPOSE_CHARS = 500

        /** The default Trust Contract a bare /review establishes — what the Guardian classifies against. */
        val DEFAULT_CONTRACT =
            "仅允许围绕当前绑定项目进行日常开发、阅读、解释、代码评审、问题定位、修改和测试；" +
                "不得读取或收集凭证，不得访问项目外目录、提升系统权限、建立持久化、规避审批或向外部发送项目数据。"

        /**
         * Read either shape off disk. The v2 object is detected STRUCTURALLY (a JSON object carrying
         * "version") rather than by decode-and-hope: PocketJson ignores unknown keys, so decoding the legacy
         * `chatId -> workdir` map as [FeishuTrustFile] would "succeed" as an empty table and silently drop
         * every grant. A legacy map migrates in-memory to TRUSTED records — those rows were the owner's
         * explicit /trust, so the upgrade must not quietly change their meaning.
         *
         * Only the integer version 2 is decoded (reviewed-trust design §21.7). Any OTHER version — newer,
         * older, or the wrong type — reads as empty trust rather than best-effort: a future v3 may hang new
         * safety conditions on fields this build does not know, so applying its TRUSTED/REVIEWED rows while
         * ignoring those conditions would grant more than the owner agreed to. Fail closed is the same
         * posture as a corrupt file, and like it the unsupported file is never overwritten by the read —
         * only a later explicit trust write regenerates it as v2.
         */
        internal fun parse(text: String): Map<String, FeishuTrustRecord> {
            val root = PocketJson.parseToJsonElement(text)
            if (root !is JsonObject || "version" !in root) {
                return PocketJson.decodeFromJsonElement<Map<String, String>>(root).mapValues { (_, wd) ->
                    FeishuTrustRecord(workdir = wd, mode = FeishuTrustMode.TRUSTED, purpose = null, contractVersion = 1)
                }
            }
            // an unquoted integer 2, exactly — "2" (string), null, 3, -2 etc. all fall through to fail closed
            val v = root["version"] as? JsonPrimitive
            if (v == null || v.isString || v.intOrNull != 2) return emptyMap()
            return PocketJson.decodeFromJsonElement<FeishuTrustFile>(root).chats
        }
    }
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
 * Privacy boundary (reviewed-trust design §10): lines must NOT carry the prompt text (nor a "head" of it) —
 * message content stays out of durable storage; the in-memory ring is where a redacted excerpt may live.
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
            // parts of this line can be attacker-adjacent: \n would forge a record, and \r would let it
            // visually overwrite the previous one in a terminal `cat`. Both collapse to a space.
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
