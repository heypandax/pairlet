package dev.ccpocket.protocol

import kotlinx.serialization.Serializable

/**
 * Root of all messages. Polymorphic via @SerialName under discriminator "t".
 *
 * The three intermediate sealed interfaces express *direction* and give compile-time
 * safety on send paths without affecting the JSON encoding.
 */
@Serializable
sealed interface Frame

/** phone -> daemon */
@Serializable
sealed interface ToDaemon : Frame

/** daemon -> phone */
@Serializable
sealed interface ToPhone : Frame

/** either end -> relay control plane (to = RELAY) */
@Serializable
sealed interface ToRelay : Frame
