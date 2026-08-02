package dev.ccpocket.daemon.feishu

import dev.ccpocket.protocol.PocketJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.MessageDigest

/**
 * One structured audit event per REVIEWED request (design §10) — enough to trace WHAT the Guardian and the
 * owner decided and how long it took, without persisting anything a group member typed or is identified by:
 * chat/sender/message land as one-way hashes, the prompt not at all, absolute paths never (projectName is
 * the display basename). JSON encoding escapes newlines, so a crafted purpose/prompt can't forge log lines.
 */
@Serializable
data class FeishuReviewEvent(
    val timestampMs: Long,
    /** "review" (live) or "review_shadow" (shadow rollout), plus the turn-level events the engine appends:
     *  "handoff" / "escalation". */
    val eventType: String,
    val reviewId: String,
    val chatIdHash: String,
    val senderHash: String,
    val messageIdHash: String,
    val projectName: String,
    val mode: String,
    val contractVersion: Long,
    val risk: String? = null,
    val confidence: Double? = null,
    val reasonCodes: List<String> = emptyList(),
    val decision: String? = null,
    /** reviewer_auto_allowed / escalated_owner / escalated_owner_allowed / escalated_owner_denied /
     *  reviewer_timeout / reviewer_unavailable / reviewer_invalid_output / policy_changed_during_review /
     *  handoff_failed / turn_started */
    val finalOutcome: String,
    val assessor: String? = null,
    val assessorVersion: String? = null,
    val latencyMs: Long? = null,
)

/**
 * JSONL sink for [FeishuReviewEvent] — `feishu-review.log` beside the trust table. Same durability trade
 * as [FeishuTrustLog]: best-effort (an audit failure must never block a request OR flip its outcome),
 * size-capped with one rotated generation, owner-only permissions.
 */
class FeishuReviewLog(private val path: File) {
    @Synchronized fun record(event: FeishuReviewEvent) {
        runCatching {
            path.parentFile?.mkdirs()
            if (path.length() > MAX_BYTES) {
                val prev = File(path.parentFile, "${path.name}.1")
                prev.delete()
                // a failed rotation must not be followed by an append — dropping the line is the lesser evil
                if (!path.renameTo(prev)) return
            }
            path.appendText(PocketJson.encodeToString(event) + "\n")
            runCatching {
                java.nio.file.Files.setPosixFilePermissions(
                    path.toPath(),
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
                )
            }
        }
    }

    companion object {
        const val MAX_BYTES = 4L * 1024 * 1024

        /** Correlatable-but-irreversible id form for the audit trail (design §8.2): SHA-256, truncated. */
        fun hash(value: String): String {
            if (value.isEmpty()) return ""
            val d = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            return d.joinToString("") { "%02x".format(it) }.take(16)
        }
    }
}
