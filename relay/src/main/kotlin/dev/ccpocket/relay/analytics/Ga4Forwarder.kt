package dev.ccpocket.relay.analytics

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Upstream MP call. Returns the HTTP status; throws on transport failure/timeout. Injectable for tests. */
fun interface Ga4Forwarder {
    suspend fun post(stream: Ga4Stream, body: String): Int
}

/** JDK client (no new dependency); bounded connect + request timeouts per DESKTOP-GA4-INGRESS.md §6. */
class HttpGa4Forwarder(
    private val base: String = "https://www.google-analytics.com/mp/collect",
    connectTimeout: Duration = Duration.ofSeconds(3),
    private val requestTimeout: Duration = Duration.ofSeconds(5),
) : Ga4Forwarder {
    private val client = HttpClient.newBuilder().connectTimeout(connectTimeout).build()

    override suspend fun post(stream: Ga4Stream, body: String): Int {
        val secret = java.net.URLEncoder.encode(stream.apiSecret, Charsets.UTF_8)
        val req = HttpRequest.newBuilder(URI("$base?measurement_id=${stream.measurementId}&api_secret=$secret"))
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        // Run on IO; the response body is discarded unread so nothing from upstream is retained.
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
        }
    }
}
