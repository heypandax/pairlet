package dev.ccpocket.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

/** Optional, untrusted diagnostic metadata carried ONLY inside encrypted business frames.
 * Never an identity, authorization decision, transcript key, or prompt deduplication key. */
@Serializable(with = DiagnosticContextSerializer::class)
data class DiagnosticContext(val traceId: String, val attempt: Int = 0, val spanId: String? = null) {
    fun validated(): DiagnosticContext? = takeIf {
        traceId.length == 32 && traceId.all { it in '0'..'9' || it in 'a'..'f' } &&
            traceId.any { it != '0' } && attempt in 0..1000 &&
            (spanId == null || spanId.length == 16 && spanId.any { it != '0' } && spanId.all { it in '0'..'9' || it in 'a'..'f' })
    }
}

/** A malformed optional object cannot discard a legitimate open/prompt. Reject metadata alone. */
object DiagnosticContextSerializer : KSerializer<DiagnosticContext> {
    override val descriptor = buildClassSerialDescriptor("DiagnosticContext")
    override fun deserialize(decoder: Decoder): DiagnosticContext {
        val obj = (decoder as JsonDecoder).decodeJsonElement() as? JsonObject
        val id = (obj?.get("traceId") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val attempt = if (obj?.containsKey("attempt") == true)
            (obj["attempt"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull else 0
        val spanValue = obj?.get("spanId")
        val span = if (spanValue == null || spanValue == JsonNull) null else
            (spanValue as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: ""
        return DiagnosticContext(id.orEmpty(), attempt ?: -1, span).validated() ?: DiagnosticContext("")
    }
    override fun serialize(encoder: Encoder, value: DiagnosticContext) {
        val valid = value.validated()
        (encoder as JsonEncoder).encodeJsonElement(if (valid == null) JsonNull else buildJsonObject {
            put("traceId", valid.traceId); put("attempt", valid.attempt); valid.spanId?.let { put("spanId", it) }
        })
    }
}

/** Sent only to a negotiated owner connection after its initial replay/no-replay decision.
 * ready proves processing ended, not that every on-disk row was readable. Quality is independent. */
@Serializable
@SerialName("pocket/history.complete")
data class HistoryComplete(
    val convoId: String,
    val diagnostic: DiagnosticContext,
    val rows: Int = 0,
    val state: String = "ready",
    val quality: String = "unknown",
    val replaySent: Boolean = false,
) : ToPhone

/** Confirms only a matching model merge. Layout is observed separately by the App. */
@Serializable
@SerialName("pocket/history.applied")
data class HistoryApplied(val convoId: String, val diagnostic: DiagnosticContext) : ToDaemon

/** Bounded milestones, never token events. Optional capability; contains no prompt or Agent output. */
@Serializable
@SerialName("pocket/prompt.progress")
data class PromptProgress(
    val convoId: String,
    val diagnostic: DiagnosticContext,
    val stage: String,
    val result: String = "unknown",
    val output: Boolean = false,
) : ToPhone

/** A diagnostic receipt, never an approval grant or business acknowledgement. */
@Serializable
@SerialName("pocket/approval.progress")
data class ApprovalProgress(
    val convoId: String,
    val diagnostic: DiagnosticContext,
    val stage: String,
    val result: String = "unknown",
) : ToPhone
