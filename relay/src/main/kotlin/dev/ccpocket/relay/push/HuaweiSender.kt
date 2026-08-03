package dev.ccpocket.relay.push

import dev.ccpocket.protocol.PocketJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Huawei Push Kit (AGC) REST sender for HarmonyOS devices. Mints an OAuth access token via the
 * client_credentials grant (cached until ~5 min before expiry), then POSTs an IM-category
 * notification to /v1/{appId}/messages:send. Credentials are the AGC project's client id/secret
 * (AppGallery Connect → 项目设置 → 常规), never in the repo — see PushConfig env names.
 *
 * The IM category rides the 自分类权益 the project applied for in AGC; without it Huawei may
 * downgrade delivery to the quota-capped 资讯营销 lane. Token-invalid mapping: AGC answers
 * HTTP 200 with {"code":"80000000"} on success; 80300007 = token invalid/unknown → prune.
 */
class HuaweiSender(
    private val appId: String,
    private val clientId: String,
    private val clientSecret: String,
    private val now: () -> Long = System::currentTimeMillis,
) : PushSender {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    @Volatile private var cachedAccess = ""
    @Volatile private var cachedUntilMs = 0L

    private suspend fun bearer(): String {
        val t = now()
        if (cachedAccess.isNotEmpty() && t < cachedUntilMs) return cachedAccess
        val form = listOf(
            "grant_type" to "client_credentials",
            "client_id" to clientId,
            "client_secret" to clientSecret,
        ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val resp = withContext(Dispatchers.IO) {
            http.send(
                HttpRequest.newBuilder().uri(URI.create("https://oauth-login.cloud.huawei.com/oauth2/v3/token"))
                    .timeout(Duration.ofSeconds(10))
                    .header("content-type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }
        check(resp.statusCode() == 200) { "agc oauth ${resp.statusCode()}: ${resp.body()}" }
        val body = PocketJson.parseToJsonElement(resp.body()).jsonObject
        val expiresIn = body["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600
        cachedAccess = body["access_token"]!!.jsonPrimitive.content
        cachedUntilMs = t + (expiresIn - 300).coerceAtLeast(60) * 1000
        return cachedAccess
    }

    override suspend fun send(token: String, title: String, body: String, route: NotifyRoute?): SendResult {
        val auth = bearer()
        val payload = buildJsonObject {
            putJsonObject("payload") {
                putJsonObject("notification") {
                    put("category", "IM")
                    put("title", title)
                    put("body", body)
                    putJsonObject("clickAction") { put("actionType", 0) } // 0 = 打开应用首页
                    // 路由数据随点击的 want 带回（对齐 FcmSender 的 data 形状：wd/sid 直达会话，
                    // hid 落到 offer 收件箱；kind 是内容无关的类别提示）。通知体本身不展示这些。
                    route?.let { r ->
                        val d = buildString {
                            append('{')
                            var first = true
                            fun kv(k: String, v: String?) {
                                if (v == null) return
                                if (!first) append(',')
                                first = false
                                append("\"$k\":\"$v\"")
                            }
                            kv("wd", r.workdir); kv("sid", r.sessionId); kv("hid", r.handoffId); kv("kind", r.kind)
                            append('}')
                        }
                        if (d != "{}") put("data", d)
                    }
                }
            }
            putJsonObject("target") {
                putJsonArray("token") { add(kotlinx.serialization.json.JsonPrimitive(token)) }
            }
        }.toString()
        val resp = withContext(Dispatchers.IO) {
            http.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("https://push-api.cloud.huawei.com/v1/$appId/messages:send"))
                    .timeout(Duration.ofSeconds(10))
                    .header("authorization", "Bearer $auth")
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }
        if (resp.statusCode() == 200) {
            val code = runCatching {
                PocketJson.parseToJsonElement(resp.body()).jsonObject["code"]?.jsonPrimitive?.content
            }.getOrNull()
            when (code) {
                "80000000", null -> return SendResult.ACCEPTED // null = 无法解析时按成功（HTTP 200）
                "80300007", "80300002" -> return SendResult.INVALID_TOKEN // token 失效 / 不属于本应用
                else -> {
                    System.err.println("[push] huawei code=$code: ${resp.body()}")
                    return SendResult.FAILED
                }
            }
        }
        System.err.println("[push] huawei ${resp.statusCode()}: ${resp.body()}")
        return if (resp.statusCode() == 404) SendResult.INVALID_TOKEN else SendResult.FAILED
    }
}
