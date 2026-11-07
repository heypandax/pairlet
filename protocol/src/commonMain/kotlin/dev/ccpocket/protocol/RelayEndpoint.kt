package dev.ccpocket.protocol

/** Resolve only official transport endpoints; never rewrite persisted binding identities or keys. */
object RelayEndpoint {
    const val DEFAULT = "wss://relay.pairlet.cn"

    const val DEFAULT_HTTP = "https://relay.pairlet.cn"
    val officialAuthorities: Set<String> = setOf("pocket.ark-nexus.cc", "relay.pairlet.org", "relay.pairlet.cn")

    fun webSocketBase(stored: String): String = transport(stored)

    fun httpBase(stored: String): String {
        val ws = if (stored.startsWith("https://")) "wss://" + stored.removePrefix("https://") else stored
        val base = transport(ws)
        return when {
            base.startsWith("wss://") -> "https://" + base.removePrefix("wss://")
            base.startsWith("ws://") -> "http://" + base.removePrefix("ws://")
            else -> base
        }
    }

    fun transport(stored: String): String = when (stored.removeSuffix("/")) {
        "wss://pocket.ark-nexus.cc", "wss://pocket.ark-nexus.cc:443",
        "wss://relay.pairlet.org", "wss://relay.pairlet.org:443",
        "wss://relay.pairlet.cn", "wss://relay.pairlet.cn:443" -> DEFAULT
        else -> stored
    }
}
