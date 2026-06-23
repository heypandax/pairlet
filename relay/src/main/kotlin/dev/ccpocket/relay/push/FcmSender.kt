package dev.ccpocket.relay.push

import dev.ccpocket.protocol.PocketJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration

/**
 * FCM HTTP v1 sender. Mints a Google OAuth2 access token from the service-account key (RS256 JWT
 * bearer grant), caches it ~55 min, then POSTs the message to the v1 endpoint. Build it via
 * [fromServiceAccount] with the JSON downloaded from the Firebase console.
 */
class FcmSender private constructor(
    private val projectId: String,
    private val clientEmail: String,
    private val tokenUri: String,
    rsaPkcs8Der: ByteArray,
    private val now: () -> Long = System::currentTimeMillis,
) : PushSender {
    private val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(rsaPkcs8Der))
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    @Volatile private var cachedAccess = ""
    @Volatile private var cachedAt = 0L

    private suspend fun bearer(): String {
        val t = now()
        if (cachedAccess.isNotEmpty() && t - cachedAt < 55 * 60 * 1000L) return cachedAccess
        val header = PushCrypto.b64u(buildJsonObject { put("alg", "RS256"); put("typ", "JWT") }.toString())
        val claims = PushCrypto.b64u(
            buildJsonObject {
                put("iss", clientEmail)
                put("scope", "https://www.googleapis.com/auth/firebase.messaging")
                put("aud", tokenUri)
                put("iat", t / 1000)
                put("exp", t / 1000 + 3600)
            }.toString(),
        )
        val input = "$header.$claims"
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(key); update(input.toByteArray()); sign() }
        val assertion = "$input.${PushCrypto.b64u(sig)}"
        val form = "grant_type=${URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", "UTF-8")}" +
            "&assertion=${URLEncoder.encode(assertion, "UTF-8")}"
        val resp = withContext(Dispatchers.IO) {
            http.send(
                HttpRequest.newBuilder().uri(URI.create(tokenUri)).timeout(Duration.ofSeconds(10))
                    .header("content-type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }
        check(resp.statusCode() == 200) { "fcm oauth ${resp.statusCode()}: ${resp.body()}" }
        cachedAccess = PocketJson.parseToJsonElement(resp.body()).jsonObject["access_token"]!!.jsonPrimitive.content
        cachedAt = t
        return cachedAccess
    }

    override suspend fun send(token: String, title: String, body: String, route: NotifyRoute?): Boolean {
        val auth = bearer()
        val payload = buildJsonObject {
            putJsonObject("message") {
                put("token", token)
                putJsonObject("notification") { put("title", title); put("body", body) }
                // data travels in the tapped launch intent's extras — routes to the right session
                route?.let { putJsonObject("data") { put("wd", it.workdir); put("sid", it.sessionId) } }
                putJsonObject("android") { put("priority", "high") }
            }
        }.toString()
        val resp = withContext(Dispatchers.IO) {
            http.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("https://fcm.googleapis.com/v1/projects/$projectId/messages:send"))
                    .timeout(Duration.ofSeconds(10))
                    .header("authorization", "Bearer $auth")
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }
        if (resp.statusCode() != 200) System.err.println("[push] fcm ${resp.statusCode()}: ${resp.body()}")
        return resp.statusCode() == 200
    }

    companion object {
        /** Build from a service-account JSON file (downloaded from Firebase console → Service accounts). */
        fun fromServiceAccount(json: String): FcmSender {
            val o = PocketJson.parseToJsonElement(json).jsonObject
            fun req(k: String) = o[k]?.jsonPrimitive?.content ?: error("service account missing \"$k\"")
            return FcmSender(
                projectId = req("project_id"),
                clientEmail = req("client_email"),
                tokenUri = o["token_uri"]?.jsonPrimitive?.content ?: "https://oauth2.googleapis.com/token",
                rsaPkcs8Der = PushCrypto.pemToDer(req("private_key")),
            )
        }
    }
}
