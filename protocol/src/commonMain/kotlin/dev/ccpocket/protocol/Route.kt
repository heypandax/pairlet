package dev.ccpocket.protocol

import kotlinx.serialization.Serializable

/** PEER = relay forwards to the paired other end. RELAY = relay itself consumes (control plane). */
@Serializable
enum class Route { PEER, RELAY }
