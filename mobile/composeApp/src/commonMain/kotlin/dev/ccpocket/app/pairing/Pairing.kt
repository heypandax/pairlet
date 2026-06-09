package dev.ccpocket.app.pairing

import dev.ccpocket.app.secure.SecureStore
import dev.ccpocket.app.util.B64Url
import dev.ccpocket.protocol.PairCodePayload
import dev.ccpocket.protocol.PairCredential
import dev.ccpocket.protocol.e2e.E2ECrypto
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** What the daemon's QR encodes: ccpocket://pair?relay=<wss>&acct=<id>&dpk=<daemon e2e pub>&ticket=<one-time>. */
data class PairingInfo(val relay: String, val accountId: String, val daemonPub: String, val ticket: String)

/** The durable result of pairing — everything needed to reconnect end-to-end without re-pairing. */
@Serializable
data class PairedDaemon(
    val relay: String,
    val accountId: String,
    val daemonPub: String,   // base64url P-256, learned out-of-band from the QR (authenticates the daemon)
    val deviceId: String,
    val credential: String,  // relay bearer credential
)

object Pairing {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(url: String): PairingInfo? {
        if (!url.contains("?")) return null
        val m = url.substringAfter("?").split("&").mapNotNull {
            val i = it.indexOf('='); if (i < 0) null else it.substring(0, i) to it.substring(i + 1)
        }.toMap()
        val relay = m["relay"]; val acct = m["acct"]; val dpk = m["dpk"]; val ticket = m["ticket"]
        return if (relay != null && acct != null && dpk != null && ticket != null) PairingInfo(relay, acct, dpk, ticket) else null
    }

    /** This device's long-term P-256 keypair, generated once and persisted. */
    fun deviceKeys(): E2ECrypto.KeyPair {
        val priv = SecureStore.getString(K_PRIV)
        val pub = SecureStore.getString(K_PUB)
        if (priv != null && pub != null) return E2ECrypto.KeyPair(B64Url.decode(priv), B64Url.decode(pub))
        val kp = E2ECrypto.generateKeyPair()
        SecureStore.putString(K_PRIV, B64Url.encode(kp.privateRaw))
        SecureStore.putString(K_PUB, B64Url.encode(kp.publicRaw))
        return kp
    }

    /** Redeem a scanned ticket: register our pubkey, receive a credential, persist the paired record. */
    suspend fun redeem(info: PairingInfo, keys: E2ECrypto.KeyPair, client: HttpClient): PairedDaemon {
        val httpBase = info.relay.replace("wss://", "https://").replace("ws://", "http://")
        val resp = client.post("$httpBase/v1/pair/redeem") {
            setBody("""{"ticket":"${info.ticket}","devicePubKey":"${B64Url.encode(keys.publicRaw)}"}""")
        }.bodyAsText()
        val cred = runCatching { json.decodeFromString<PairCredential>(resp) }.getOrElse { error("pairing failed: $resp") }
        return PairedDaemon(info.relay, info.accountId, info.daemonPub, cred.deviceId, cred.credential).also(::save)
    }

    /** The relay this app pairs against (the daemon dials the same one). Override in Advanced if self-hosting. */
    const val DEFAULT_RELAY = "wss://pocket.ark-nexus.cc"

    /** Resolve a 6-digit code typed by the user into the full pairing info (relay-assisted path). */
    suspend fun resolveCode(code: String, client: HttpClient): PairingInfo {
        val httpBase = DEFAULT_RELAY.replace("wss://", "https://").replace("ws://", "http://")
        val resp = client.post("$httpBase/v1/pair/code") { setBody("""{"code":"$code"}""") }.bodyAsText()
        val payload = runCatching { json.decodeFromString<PairCodePayload>(resp) }.getOrElse { error("invalid or expired code") }
        return PairingInfo(DEFAULT_RELAY, payload.accountId, payload.daemonPub, payload.ticket)
    }

    fun save(p: PairedDaemon) = SecureStore.putString(K_PAIRED, json.encodeToString(p))
    fun load(): PairedDaemon? = SecureStore.getString(K_PAIRED)?.let { runCatching { json.decodeFromString<PairedDaemon>(it) }.getOrNull() }
    fun forget() = SecureStore.remove(K_PAIRED)

    private const val K_PRIV = "device_priv"
    private const val K_PUB = "device_pub"
    private const val K_PAIRED = "paired_daemon"
}
