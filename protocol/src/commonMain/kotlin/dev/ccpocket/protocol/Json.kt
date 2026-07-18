package dev.ccpocket.protocol

import kotlinx.serialization.json.Json

/**
 * The single wire codec shared by daemon, relay, and mobile.
 *
 * - classDiscriminator = "t" -> sealed [Frame] / [StreamPiece] subtypes carry their @SerialName under "t".
 * - ignoreUnknownKeys       -> forward-compatible: a newer peer may add fields.
 * - encodeDefaults          -> defaults (to=PEER, mode=default) are emitted so a differently-defaulting peer still reads intent.
 * - explicitNulls = false   -> omit null optionals (resumeId, finalText, ...) -> smaller frames, "absent" == null.
 * - coerceInputValues       -> an UNKNOWN enum value (a newer peer's AgentKind) degrades to the field's
 *                              default instead of failing the WHOLE Envelope decode — every enum-carrying
 *                              field declares a default for exactly this reason. This saves FUTURE builds;
 *                              already-shipped peers still hard-fail on unknown enums, which is why the
 *                              daemon additionally capability-gates them (see ClientCaps in Messages.kt).
 */
val PocketJson: Json = Json {
    classDiscriminator = "t"
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    isLenient = false
    coerceInputValues = true
}
