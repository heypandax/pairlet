package dev.ccpocket.protocol

import kotlinx.serialization.Serializable

/**
 * Single-layer transport wrapper.
 *
 * @param id  unique per sender (UUID string at M0) — correlates request/response.
 * @param ts  epoch millis at send time.
 * @param to  routing hint for the relay (ignored on the direct-LAN M0 path).
 * @param body the actual [Frame].
 */
@Serializable
data class Envelope(
    val id: String,
    val ts: Long,
    val to: Route = Route.PEER,
    val body: Frame,
)
