package dev.ccpocket.daemon.feishu

import com.lark.oapi.Client
import com.lark.oapi.core.httpclient.OkHttpTransport
import com.lark.oapi.core.response.RawResponse
import com.lark.oapi.core.token.AccessTokenType
import com.lark.oapi.okhttp.OkHttpClient
import com.lark.oapi.service.im.v1.model.GetChatReq
import com.lark.oapi.service.im.v1.model.GetMessageReq
import com.lark.oapi.service.im.v1.model.CreateMessageReactionReq
import com.lark.oapi.service.im.v1.model.CreateMessageReactionReqBody
import com.lark.oapi.service.im.v1.model.Emoji
import com.lark.oapi.service.im.v1.model.ReplyMessageReq
import com.lark.oapi.service.im.v1.model.ReplyMessageReqBody
import dev.ccpocket.daemon.util.logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InterruptedIOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** A fetched Feishu message reduced to the two fields [FeishuMessageText] needs. */
internal data class FeishuFetchedMessage(val type: String?, val content: String?)

/** Bounded, secret-free transport counters surfaced in the bridge log on stop/shutdown. */
internal data class FeishuApiSnapshot(
    val calls: Long,
    val failures: Long,
    val timeouts: Long,
    val active: Int,
    val peakActive: Int,
    val connections: Int,
    val idleConnections: Int,
    val closed: Boolean,
)

/**
 * The ONE HTTP path for Feishu REST calls.
 *
 * Issue #236: [FeishuEngine] used a second, unbounded JDK `HttpClient` next to the official SDK. After a
 * long-lived TLS connection hit the local fake-IP/TUN path, two `SSLFlowDelegate` workers spun forever,
 * consuming two cores and allocating fast enough to trigger 8-10 young GCs per second. The Lark SDK itself
 * already speaks through its shaded OkHttp; use that path for bot info, chat lookup, quoted messages and
 * replies as well, with one whole-call deadline and an explicitly owned lifecycle.
 *
 * The client is deliberately retained across a managed runner stop/start (credentials did not change), then
 * closed exactly once by [FeishuEngine.shutdown]. This avoids replacing an OkHttp connection pool on every
 * restart while still letting bridge removal/reconfigure cancel in-flight calls and release all sockets.
 */
internal class FeishuApiClient(
    appId: String,
    appSecret: String,
    private val bridgeName: String,
    private val healthLog: (String) -> Unit = {},
    baseUrl: String? = null,
    callTimeoutMs: Long = CALL_TIMEOUT_MS,
    connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
) : AutoCloseable {
    private val log = logger("FeishuApiClient")
    private val closed = AtomicBoolean(false)
    private val calls = AtomicLong(0)
    private val failures = AtomicLong(0)
    private val timeouts = AtomicLong(0)
    private val active = AtomicInteger(0)
    private val peakActive = AtomicInteger(0)

    private val http = OkHttpClient.Builder()
        // callTimeout bounds DNS + connect + request + response as ONE operation; connectTimeout gives a
        // tighter failure for an unreachable proxy/fake-IP before that outer deadline expires.
        .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    private val client: Client = Client.newBuilder(appId, appSecret)
        .httpTransport(OkHttpTransport(http))
        .let { builder -> baseUrl?.let(builder::openBaseUrl) ?: builder }
        .build()

    fun botOpenId(): String = tracked("bot.info") {
        // bot/v3 predates the generated service surface in oapi-sdk 2.4.19. The generic SDK call still owns
        // tenant-token acquisition/cache and the same bounded OkHttp transport; no hand-rolled token request.
        val response = client.get(
            "/open-apis/bot/v3/info",
            null,
            AccessTokenType.Tenant,
        )
        val root = response.jsonBody("bot.info")
        val code = root["code"]?.jsonPrimitive?.intOrNull ?: 0
        if (code != 0) throw FeishuApiException("bot.info code=$code")
        root["bot"]?.jsonObject?.get("open_id")?.jsonPrimitive?.contentOrNull
            ?: throw FeishuApiException("bot.info missing bot.open_id")
    }

    fun chatOwnerOpenId(chatId: String): String? = tracked("chat.get") {
        requireFeishuId(chatId, "chat id")
        val response = client.im().v1().chat().get(
            GetChatReq.newBuilder().chatId(chatId).userIdType("open_id").build(),
        )
        if (!response.success()) throw FeishuApiException("chat.get code=${response.code}")
        response.data?.ownerId
    }

    fun message(messageId: String): FeishuFetchedMessage? = tracked("message.get") {
        requireFeishuId(messageId, "message id")
        val response = client.im().v1().message().get(
            GetMessageReq.newBuilder().messageId(messageId).build(),
        )
        if (!response.success()) throw FeishuApiException("message.get code=${response.code}")
        response.data?.items?.firstOrNull()?.let { item ->
            FeishuFetchedMessage(item.msgType, item.body?.content)
        }
    }

    /** Lightweight, best-effort receipt used by the group bridge before it starts any real work (#234). */
    fun reactTyping(messageId: String) = tracked("message.react_typing") {
        requireFeishuId(messageId, "message id")
        val response = client.im().v1().messageReaction().create(
            CreateMessageReactionReq.newBuilder().messageId(messageId)
                .createMessageReactionReqBody(
                    CreateMessageReactionReqBody.newBuilder()
                        .reactionType(Emoji.newBuilder().emojiType(TYPING_EMOJI).build())
                        .build(),
                )
                .build(),
        )
        if (!response.success()) throw FeishuApiException("message.react_typing code=${response.code}")
    }

    fun reply(messageId: String, contentJson: String, inThread: Boolean = false) = tracked("message.reply") {
        requireFeishuId(messageId, "message id")
        val body = ReplyMessageReqBody.newBuilder().content(contentJson).msgType("text")
        // Preserve the direct-message request shape exactly; only group traffic opts into Feishu topics.
        if (inThread) body.replyInThread(true)
        val response = client.im().v1().message().reply(
            ReplyMessageReq.newBuilder().messageId(messageId)
                .replyMessageReqBody(body.build())
                .build(),
        )
        if (!response.success()) throw FeishuApiException("message.reply code=${response.code}")
    }

    fun snapshot(): FeishuApiSnapshot = FeishuApiSnapshot(
        calls = calls.get(),
        failures = failures.get(),
        timeouts = timeouts.get(),
        active = active.get(),
        peakActive = peakActive.get(),
        connections = if (closed.get()) 0 else http.connectionPool().connectionCount(),
        idleConnections = if (closed.get()) 0 else http.connectionPool().idleConnectionCount(),
        closed = closed.get(),
    )

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Synchronous SDK calls execute on their caller, but cancelAll still aborts their sockets. The owned
        // dispatcher/pool must not outlive a removed or reconfigured bridge.
        http.dispatcher().cancelAll()
        http.connectionPool().evictAll()
        runCatching { http.cache()?.close() }
        http.dispatcher().executorService().shutdownNow()
    }

    private inline fun <T> tracked(operation: String, block: () -> T): T {
        check(!closed.get()) { "Feishu API client is closed" }
        calls.incrementAndGet()
        val nowActive = active.incrementAndGet()
        updatePeak(nowActive)
        val started = System.nanoTime()
        try {
            return block().also {
                val elapsedMs = elapsedMs(started)
                if (elapsedMs >= SLOW_CALL_MS) {
                    log.warn("feishu api {} for bridge {} took {}ms", operation, bridgeName, elapsedMs)
                    healthLog("[http] slow $operation (${elapsedMs}ms)")
                }
            }
        } catch (t: Throwable) {
            failures.incrementAndGet()
            val timeout = t.isTimeout()
            if (timeout) timeouts.incrementAndGet()
            // Operation names and exception classes are enough to diagnose transport health. Avoid response
            // bodies, URLs and exception messages here: SDK failures can echo request material.
            log.warn(
                "feishu api {} for bridge {} failed after {}ms ({}{})",
                operation,
                bridgeName,
                elapsedMs(started),
                t.javaClass.simpleName,
                if (timeout) ", timeout" else "",
            )
            healthLog(
                "[http] $operation failed after ${elapsedMs(started)}ms " +
                    "(${t.javaClass.simpleName}${if (timeout) ", timeout" else ""})",
            )
            throw t
        } finally {
            active.decrementAndGet()
        }
    }

    private fun updatePeak(value: Int) {
        var seen = peakActive.get()
        while (value > seen && !peakActive.compareAndSet(seen, value)) seen = peakActive.get()
    }

    private fun RawResponse.jsonBody(operation: String) = (body ?: ByteArray(0)).let { bytes ->
        if (statusCode !in 200..299) throw FeishuApiException("$operation http=$statusCode")
        if (bytes.size > MAX_RESPONSE_BYTES) throw FeishuApiException("$operation response too large")
        Json.parseToJsonElement(String(bytes, StandardCharsets.UTF_8)).jsonObject
    }

    private fun requireFeishuId(value: String, label: String) {
        require(value.matches(FEISHU_ID)) { "invalid $label" }
    }

    private fun Throwable.isTimeout(): Boolean = generateSequence(this as Throwable?) { it.cause }
        .any { it is InterruptedIOException || it.javaClass.simpleName.contains("Timeout", ignoreCase = true) }

    private fun elapsedMs(started: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

    private class FeishuApiException(message: String) : RuntimeException(message)

    private companion object {
        const val CALL_TIMEOUT_MS = 15_000L
        const val CONNECT_TIMEOUT_MS = 8_000L
        const val SLOW_CALL_MS = 5_000L
        const val MAX_RESPONSE_BYTES = 1_048_576
        const val TYPING_EMOJI = "Typing"
        val FEISHU_ID = Regex("^[A-Za-z0-9_-]+$")
    }
}
