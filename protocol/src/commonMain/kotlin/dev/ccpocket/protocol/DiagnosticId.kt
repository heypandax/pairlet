package dev.ccpocket.protocol

import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.json.*

/** Public, ephemeral socket label. Never an account, credential, routing key or business trace. */
@Serializable(with = DiagnosticIdSerializer::class)
data class DiagnosticId(val value: String) {
    fun validated(): String? = value.takeIf { it.length == 32 && it.any { c -> c != '0' } && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
}

object DiagnosticIdSerializer : KSerializer<DiagnosticId> {
    override val descriptor = PrimitiveSerialDescriptor("DiagnosticId", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: DiagnosticId) = encoder.encodeString(value.validated().orEmpty())
    override fun deserialize(decoder: Decoder): DiagnosticId {
        val json = decoder as? JsonDecoder ?: return DiagnosticId(decoder.decodeString())
        val element = json.decodeJsonElement() as? JsonPrimitive
        return DiagnosticId(element?.takeIf { it.isString }?.content.orEmpty())
    }
}
