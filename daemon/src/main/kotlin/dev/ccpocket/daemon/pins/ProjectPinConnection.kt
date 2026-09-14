package dev.ccpocket.daemon.pins

/**
 * What the transport a project-pin request arrived on knows about that CONNECTION (issue #362). Handed to the
 * router beside the frame by the relay and LAN transports; never serialized, never read from a frame, and it
 * names no device — the router's transport-authenticated deviceId stays the only identity. A caller without
 * one (the plaintext `--local` socket, an in-process caller) can neither subscribe nor mutate.
 */
interface ProjectPinConnection {
    /** Still an allow-listed owner connection that declared pin support and was not retired or closed. */
    suspend fun isCurrent(): Boolean

    /** The subscription this connection's last ACCEPTED fetch registered; null before one, or once not current. */
    suspend fun currentSubscription(): String?

    /** Register [subscriptionId] after a fully validated, successful fetch. False — and nothing registered —
     *  when the connection is no longer current; that fetch must then not be reported as a success. */
    suspend fun acceptFetch(subscriptionId: String): Boolean
}
