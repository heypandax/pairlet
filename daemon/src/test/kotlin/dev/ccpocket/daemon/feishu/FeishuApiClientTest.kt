package dev.ccpocket.daemon.feishu

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class FeishuApiClientTest {
    @Test
    fun `bot chat quote and reply share one bounded SDK transport`() {
        FakeFeishuServer().use { server ->
            val health = CopyOnWriteArrayList<String>()
            val client = FeishuApiClient(
                appId = "cli_${UUID.randomUUID()}",
                appSecret = "test-secret",
                bridgeName = "test-bridge",
                healthLog = health::add,
                baseUrl = server.baseUrl,
                callTimeoutMs = 2_000,
                connectTimeoutMs = 500,
            )
            try {
                assertEquals("ou_bot", client.botOpenId())
                assertEquals("ou_owner", client.chatOwnerOpenId("oc_test"))
                assertEquals(
                    FeishuFetchedMessage("text", "{\"text\":\"quoted\"}"),
                    client.message("om_quote"),
                )
                client.reactTyping("om_reply")
                client.reply("om_reply", "{\"text\":\"done\"}", inThread = true)

                val snapshot = client.snapshot()
                assertEquals(5, snapshot.calls)
                assertEquals(0, snapshot.failures)
                assertEquals(0, snapshot.timeouts)
                assertEquals(0, snapshot.active)
                assertEquals(1, snapshot.peakActive)
                assertTrue(health.isEmpty(), "healthy fast calls should not spam the bridge log: $health")

                // The generated APIs and the legacy bot-info endpoint both acquired auth through the same SDK
                // transport. The token cache should fetch once, not once per operation.
                assertEquals(
                    1,
                    server.requests.count { it.path == "/open-apis/auth/v3/tenant_access_token/internal" },
                )
                assertTrue(
                    server.requests.filterNot { it.path.contains("tenant_access_token") }
                        .all { it.authorization?.startsWith("Bearer ") == true },
                )
                val reaction = server.requests.single { it.path == "/open-apis/im/v1/messages/om_reply/reactions" }
                assertTrue(reaction.body.contains("\"emoji_type\":\"Typing\""), reaction.body)
                val reply = server.requests.single { it.path == "/open-apis/im/v1/messages/om_reply/reply" }
                assertTrue(reply.body.contains("\"reply_in_thread\":true"), reply.body)
            } finally {
                client.close()
            }

            val closed = client.snapshot()
            assertTrue(closed.closed)
            assertEquals(0, closed.connections)
            assertEquals(0, closed.idleConnections)
            client.close() // idempotent shutdown: bridge stop/remove can converge here more than once
            assertFails { client.botOpenId() }
        }
    }

    @Test
    fun `whole-call deadline aborts a stalled response and records timeout`() {
        FakeFeishuServer(botDelayMs = 2_000).use { server ->
            val health = CopyOnWriteArrayList<String>()
            val client = FeishuApiClient(
                appId = "cli_${UUID.randomUUID()}",
                appSecret = "test-secret",
                bridgeName = "timeout-bridge",
                healthLog = health::add,
                baseUrl = server.baseUrl,
                callTimeoutMs = 250,
                connectTimeoutMs = 200,
            )
            try {
                val elapsed = measureTimeMillis { assertFails { client.botOpenId() } }
                assertTrue(elapsed < 1_500, "stalled Feishu response was not bounded: ${elapsed}ms")

                val snapshot = client.snapshot()
                assertEquals(1, snapshot.calls)
                assertEquals(1, snapshot.failures)
                assertEquals(1, snapshot.timeouts)
                assertEquals(0, snapshot.active)
                assertEquals(1, snapshot.peakActive)
                assertTrue(health.single().contains("timeout"), "timeout must be visible without request data")
            } finally {
                client.close()
            }
        }
    }

    private data class SeenRequest(val path: String, val authorization: String?, val body: String)

    private class FakeFeishuServer(private val botDelayMs: Long = 0) : AutoCloseable {
        private val executor: ExecutorService = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@FakeFeishuServer.executor
            createContext("/", ::handle)
            start()
        }
        val requests = CopyOnWriteArrayList<SeenRequest>()
        val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

        private fun handle(exchange: HttpExchange) {
            requests += SeenRequest(
                exchange.requestURI.path,
                exchange.requestHeaders.getFirst("Authorization"),
                String(exchange.requestBody.readAllBytes(), StandardCharsets.UTF_8),
            )
            val body = when (exchange.requestURI.path) {
                "/open-apis/auth/v3/tenant_access_token/internal" ->
                    """{"code":0,"msg":"ok","tenant_access_token":"tenant-test","expire":7200}"""
                "/open-apis/bot/v3/info" -> {
                    if (!delayBot(exchange)) return
                    """{"code":0,"msg":"ok","bot":{"open_id":"ou_bot"}}"""
                }
                "/open-apis/im/v1/chats/oc_test" ->
                    """{"code":0,"msg":"ok","data":{"owner_id":"ou_owner","owner_id_type":"open_id"}}"""
                "/open-apis/im/v1/messages/om_quote" ->
                    """{"code":0,"msg":"ok","data":{"items":[{"message_id":"om_quote","msg_type":"text","body":{"content":"{\"text\":\"quoted\"}"}}]}}"""
                "/open-apis/im/v1/messages/om_reply/reactions" ->
                    """{"code":0,"msg":"ok","data":{"reaction_id":"reaction-test","reaction_type":{"emoji_type":"Typing"}}}"""
                "/open-apis/im/v1/messages/om_reply/reply" ->
                    """{"code":0,"msg":"ok","data":{"message_id":"om_done"}}"""
                else -> """{"code":404,"msg":"unexpected ${exchange.requestURI.path}"}"""
            }
            respond(exchange, if (body.contains("\"code\":404")) 404 else 200, body)
        }

        private fun delayBot(exchange: HttpExchange): Boolean {
            if (botDelayMs <= 0) return true
            return try {
                Thread.sleep(botDelayMs)
                true
            } catch (_: InterruptedException) {
                exchange.close()
                false
            }
        }

        private fun respond(exchange: HttpExchange, status: Int, body: String) {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            runCatching {
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }.onFailure { exchange.close() }
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }
}
