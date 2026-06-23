package dev.ccpocket.relay.push

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration

/**
 * APNs HTTP/2 sender with token-based (JWT ES256) auth. One instance per environment — the same .p8
 * key signs both `api.push.apple.com` (release builds) and `api.sandbox.push.apple.com` (debug
 * builds), chosen by the device's reported platform ("apns" vs "apns_sandbox"). The JWT is cached and
 * refreshed under an hour (Apple rejects tokens older than 60 min).
 */
class ApnsSender(
    p8Pkcs8Der: ByteArray,
    private val keyId: String,
    private val teamId: String,
    private val topic: String, // = iOS bundle id
    sandbox: Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) : PushSender {
    private val host = if (sandbox) "api.sandbox.push.apple.com" else "api.push.apple.com"
    private val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(p8Pkcs8Der))
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    @Volatile private var cachedJwt = ""
    @Volatile private var cachedAt = 0L

    @Synchronized
    private fun jwt(): String {
        val t = now()
        if (cachedJwt.isNotEmpty() && t - cachedAt < 50 * 60 * 1000L) return cachedJwt
        val header = PushCrypto.b64u(buildJsonObject { put("alg", "ES256"); put("kid", keyId) }.toString())
        val claims = PushCrypto.b64u(buildJsonObject { put("iss", teamId); put("iat", t / 1000) }.toString())
        val input = "$header.$claims"
        val der = Signature.getInstance("SHA256withECDSA").run { initSign(key); update(input.toByteArray()); sign() }
        cachedJwt = "$input.${PushCrypto.b64u(PushCrypto.derToJose(der, 32))}"
        cachedAt = t
        return cachedJwt
    }

    override suspend fun send(token: String, title: String, body: String, route: NotifyRoute?): Boolean = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonObject("aps") {
                putJsonObject("alert") { put("title", title); put("body", body) }
                put("sound", "default")
            }
            // custom keys (siblings of `aps`) carry deep-link routing; the OS hands them to the app on tap
            route?.let { put("wd", it.workdir); put("sid", it.sessionId) }
        }.toString()
        val req = HttpRequest.newBuilder()
            .uri(URI.create("https://$host/3/device/$token"))
            .timeout(Duration.ofSeconds(10))
            .header("authorization", "bearer ${jwt()}")
            .header("apns-topic", topic)
            .header("apns-push-type", "alert")
            .header("apns-priority", "10")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) System.err.println("[push] apns ${resp.statusCode()}: ${resp.body()}")
        resp.statusCode() == 200
    }
}
