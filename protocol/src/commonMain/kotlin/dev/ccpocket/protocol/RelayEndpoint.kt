package dev.ccpocket.protocol

/** Resolve only official transport endpoints; never rewrite persisted binding identities or keys. */
object RelayEndpoint {
    const val DEFAULT = "wss://relay.pairlet.cn"

    fun transport(stored: String): String = when (stored.removeSuffix("/")) {
        "wss://pocket.ark-nexus.cc", "wss://pocket.ark-nexus.cc:443",
        "wss://relay.pairlet.org", "wss://relay.pairlet.org:443",
        "wss://relay.pairlet.cn", "wss://relay.pairlet.cn:443" -> DEFAULT
        else -> stored
    }
}
