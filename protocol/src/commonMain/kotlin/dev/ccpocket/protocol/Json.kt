package dev.ccpocket.protocol

import kotlinx.serialization.json.Json

/**
 * The single wire codec shared by daemon, relay, and mobile.
 *
 * - classDiscriminator = "t" -> sealed [Frame] / [StreamPiece] subtypes carry their @SerialName under "t".
 * - ignoreUnknownKeys       -> forward-compatible: a newer peer may add fields.
 * - encodeDefaults          -> defaults (to=PEER, mode=default) are emitted so a differently-defaulting peer still reads intent.
 * - explicitNulls = false   -> omit null optionals (resumeId, finalText, ...) -> smaller frames, "absent" == null.
 */
val PocketJson: Json = Json {
    classDiscriminator = "t"
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    isLenient = false
}
