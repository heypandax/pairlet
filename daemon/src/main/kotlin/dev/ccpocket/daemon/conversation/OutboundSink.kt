package dev.ccpocket.daemon.conversation

import dev.ccpocket.protocol.Frame

/** Where a conversation emits frames toward the connected client. Decouples from the transport. */
fun interface OutboundSink {
    suspend fun emit(frame: Frame)
}
