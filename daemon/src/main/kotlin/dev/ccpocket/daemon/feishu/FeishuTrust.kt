package dev.ccpocket.daemon.feishu

import dev.ccpocket.protocol.PocketJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.util.UUID

/**
 * The trust MODE a chat's members run under (issues #198 + #233 + reviewed-trust design):
 *
 *  - [UNTRUSTED] — every request waits on the machine owner's per-request approval card (the default,
 *    and the meaning of an ABSENT record: fail closed).
 *  - [REVIEWED] — each request is first classified by an independent Guardian Reviewer against the
 *    owner's Trust Contract; only a clear low-risk match runs card-free under a restricted project-scoped
 *    tool ceiling. Everything else falls back to the owner's card.
 *  - [TRUSTED] — the owner durably authorized this exact (chat, project) pair. Each request receives broad
 *    one-turn authority without Guardian or per-tool cards; the grant is still revoked at turn end.
 *  - [FULL_AUTO] — legacy v3 serialized spelling, retained only so already-written files remain readable.
 *    It is normalized to [TRUSTED] on read and is never written by current product flows.
 */
@Serializable
enum class FeishuTrustMode {
    UNTRUSTED,
    REVIEWED,
    TRUSTED,
    FULL_AUTO,
}

/** One chat's trust state, keyed on the (chat, project) PAIR — see [FeishuTrust] on why. */
@Serializable
data class FeishuTrustRecord(
    val workdir: String,
    val mode: FeishuTrustMode,
    /** The owner's Trust Contract for a REVIEWED chat; null = the default contract. */
    val purpose: String? = null,
    /** True only after the owner invoked the current `/trust confirm`, after reading `/trust`'s disclosure.
     *  Missing/false on legacy rows prevents a narrower historical consent from being silently widened. */
    val fullAuthorityConfirmed: Boolean = false,
    /** Bumped on every mode/purpose change, so an async review can prove the policy it read still stands. */
    val contractVersion: Long = 1,
    /**
     * A persisted identity for this exact policy grant. Every real change gets a fresh UUID, so deleting a
     * row and recreating field-identical state cannot fool an in-flight review via ABA. Null is accepted only
     * for backward-compatible reads of rows written before this field existed; their first change gets a UUID.
     */
    val policyRevision: String? = null,
    /** Human/audit metadata only; wall-clock resolution is not strong enough to identify a policy grant. */
    val updatedAtEpochMs: Long = 0,
)

/** The on-disk shape. v3 is retained for compatibility with the already-shipped policyRevision schema. */
@Serializable
data class FeishuTrustFile(
    val version: Int = 3,
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
    /** Persisted identity of the exact grant reviewed; unlike a timestamp this cannot collide in one tick. */
    val policyRevision: String? = null,
    /** Human/audit metadata retained in the snapshot, but not relied upon as the ABA guard. */
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
class FeishuTrust(
    private val path: File,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
    private val newPolicyRevision: () -> String = { UUID.randomUUID().toString() },
) {
    private val chats = LinkedHashMap<String, FeishuTrustRecord>()

    init {
        if (path.exists()) {
            // Unlike the routes table a corrupt trust file must NOT fail the engine start: the safe reading of
            // "I can't tell which chats are trusted" is "none are", and refusing to start would take the whole
            // bridge down over an optional convenience. The corrupt file is left in place (never overwritten by
            // the failed read itself); the next successful write regenerates it as v3.
            runCatching { chats.putAll(parse(path.readText())) }
        }
    }

    /** True only if this chat has current broad TRUSTED consent for the project it is bound to RIGHT NOW. */
    @Synchronized fun isTrusted(chatId: String, workdir: String): Boolean =
        modeFor(chatId, workdir) == FeishuTrustMode.TRUSTED

    /** The chat's effective mode for THIS project — a record naming another workdir grants nothing. */
    @Synchronized fun modeFor(chatId: String, workdir: String): FeishuTrustMode =
        chats[chatId]?.takeIf { it.workdir == workdir }?.let(::effectiveMode) ?: FeishuTrustMode.UNTRUSTED

    /** Any trust record for this chat, whatever project it names — for /untrust revocation and /trust-status. */
    @Synchronized fun recordFor(chatId: String): FeishuTrustRecord? = chats[chatId]

    /** Any trust mark for this chat, whatever project it names — for the chat-side "already on?" answer. */
    @Synchronized fun trustedProject(chatId: String): String? = chats[chatId]?.workdir

    /** Immutable policy read for a Guardian flow's before/after comparison (see [TrustSnapshot]). An
     *  absent or other-project record reads as UNTRUSTED with contractVersion 0. */
    @Synchronized fun snapshot(chatId: String, workdir: String): TrustSnapshot {
        val r = chats[chatId]?.takeIf { it.workdir == workdir }
        val mode = r?.let(::effectiveMode) ?: FeishuTrustMode.UNTRUSTED
        return TrustSnapshot(
            chatId = chatId,
            workdir = workdir,
            mode = mode,
            purpose = r?.purpose.takeIf { mode == FeishuTrustMode.REVIEWED },
            contractVersion = r?.contractVersion ?: 0,
            policyRevision = r?.policyRevision,
            updatedAtEpochMs = r?.updatedAtEpochMs ?: 0,
        )
    }

    /**
     * Did nothing change since [snap] was taken? Data-class equality deliberately includes
     * [TrustSnapshot.policyRevision], so a revoke/re-grant ABA voids an in-flight review even when every
     * user-visible field, contractVersion, and wall-clock timestamp is identical.
     */
    @Synchronized fun stillMatches(chatId: String, workdir: String, snap: TrustSnapshot): Boolean =
        snapshot(chatId, workdir) == snap

    @Synchronized fun trust(chatId: String, workdir: String): TrustWrite =
        put(chatId, workdir, FeishuTrustMode.TRUSTED, purpose = null, fullAuthorityConfirmed = true)

    @Synchronized fun setReviewed(chatId: String, workdir: String, purpose: String?): TrustWrite =
        put(
            chatId,
            workdir,
            FeishuTrustMode.REVIEWED,
            purpose = purpose?.trim()?.take(MAX_PURPOSE_CHARS)?.takeIf { it.isNotEmpty() },
            fullAuthorityConfirmed = false,
        )

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

    private fun put(
        chatId: String,
        workdir: String,
        mode: FeishuTrustMode,
        purpose: String?,
        fullAuthorityConfirmed: Boolean,
    ): TrustWrite {
        val existing = chats[chatId]
        if (
            existing != null && existing.workdir == workdir && existing.mode == mode &&
            existing.purpose == purpose && existing.fullAuthorityConfirmed == fullAuthorityConfirmed
        ) {
            return TrustWrite.UNCHANGED
        }
        val next = FeishuTrustRecord(
            workdir = workdir,
            mode = mode,
            purpose = purpose,
            fullAuthorityConfirmed = fullAuthorityConfirmed,
            // any change to the policy (mode, purpose, or the project it names) invalidates in-flight reviews
            contractVersion = (existing?.contractVersion ?: 0) + 1,
            policyRevision = newPolicyRevision(),
            updatedAtEpochMs = nowEpochMs(),
        )
        if (!write(chats + (chatId to next))) return TrustWrite.WRITE_FAILED
        chats[chatId] = next
        return TrustWrite.CHANGED
    }

    /** Legacy TRUSTED/FULL_AUTO rows were confirmed under narrower promises. They remain visible for status
     *  and revocation, but execute as UNTRUSTED until the owner invokes the current `/trust confirm`. */
    private fun effectiveMode(record: FeishuTrustRecord): FeishuTrustMode = when (record.mode) {
        FeishuTrustMode.REVIEWED -> FeishuTrustMode.REVIEWED
        FeishuTrustMode.TRUSTED ->
            if (record.fullAuthorityConfirmed) FeishuTrustMode.TRUSTED else FeishuTrustMode.UNTRUSTED
        FeishuTrustMode.FULL_AUTO, FeishuTrustMode.UNTRUSTED -> FeishuTrustMode.UNTRUSTED
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
         * Read every supported shape off disk. A versioned object is detected STRUCTURALLY (a JSON object carrying
         * "version") rather than by decode-and-hope: PocketJson ignores unknown keys, so decoding the legacy
         * `chatId -> workdir` map as [FeishuTrustFile] would "succeed" as an empty table and silently drop
         * every grant. A legacy map migrates in-memory to not-yet-confirmed TRUSTED records: those rows were
         * created under the old restricted promise and must not silently gain broad authority.
         *
         * Integer versions 2 and 3 are decoded. v2 rows retain exactly their legacy TRUSTED/REVIEWED meaning;
         * FULL_AUTO was valid only in v3, so a forged/mislabeled v2 FULL_AUTO file fails closed. A legitimate
         * v3 FULL_AUTO row is normalized to a not-yet-confirmed TRUSTED record: it remains fail-closed until
         * the owner invokes the current `/trust confirm`, because the former mode promised a Guardian gate.
         * Every v2 row is forced to [FeishuTrustRecord.fullAuthorityConfirmed] false even if an unknown field
         * was injected: only the current v3 writer can record the new broad consent. The additive policyRevision
         * field defaults to null for pre-field v2/v3 rows; their first real policy change writes a fresh UUID,
         * which is enough to make any
         * revoke/re-grant ABA distinguishable. Any OTHER version — newer, older, or the wrong type — also
         * reads as empty trust rather than best-effort: a future schema may hang new safety conditions on
         * fields this build does not know. The read never rewrites the source. A later explicit write emits
         * v3; daemons from before this flag ignore it and retain their narrower TRUSTED ceiling.
         */
        internal fun parse(text: String): Map<String, FeishuTrustRecord> {
            val root = PocketJson.parseToJsonElement(text)
            if (root !is JsonObject || "version" !in root) {
                return PocketJson.decodeFromJsonElement<Map<String, String>>(root).mapValues { (_, wd) ->
                    FeishuTrustRecord(
                        workdir = wd,
                        mode = FeishuTrustMode.TRUSTED,
                        purpose = null,
                        fullAuthorityConfirmed = false,
                        contractVersion = 1,
                    )
                }
            }
            // an unquoted supported integer, exactly — "2" (string), null, future versions, etc. fail closed
            val v = root["version"] as? JsonPrimitive
            val version = v?.takeUnless { it.isString }?.intOrNull
            if (version !in setOf(2, 3)) return emptyMap()
            val decoded = PocketJson.decodeFromJsonElement<FeishuTrustFile>(root).chats
            if (version == 2 && decoded.values.any { it.mode == FeishuTrustMode.FULL_AUTO }) return emptyMap()
            return decoded.mapValues { (_, record) ->
                when {
                    version == 2 -> record.copy(fullAuthorityConfirmed = false)
                    record.mode == FeishuTrustMode.FULL_AUTO ->
                        record.copy(mode = FeishuTrustMode.TRUSTED, purpose = null, fullAuthorityConfirmed = false)
                    else -> record
                }
            }
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
