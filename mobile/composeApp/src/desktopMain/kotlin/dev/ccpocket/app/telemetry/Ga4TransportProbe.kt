package dev.ccpocket.app.telemetry

import dev.ccpocket.observability.Environment
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Explicit local-development probe only; never enable DebugView for production traffic. */
internal fun ga4ProbeEnabled(requested: String?, environment: Environment?): Boolean =
    requested == "1" && environment in setOf(Environment.DEVELOPMENT, Environment.STAGING)

/** No request, response body, event name, identity, exception text or URL reaches the local sink. */
internal class Ga4TransportProbe(private val emit: (String) -> Unit) {
    private fun record(value: String) {
        // A closed stderr or failed diagnostic sink must not interrupt analytics or App work.
        runCatching { emit("[ga4-transport] $value") }
    }

    suspend fun send(request: suspend () -> Int) {
        record("attempt")
        try {
            val status = request()
            val category = when (status) {
                in 200..299 -> "http_2xx" // Transport receipt, NOT proof of GA4 ingestion.
                429 -> "rate_limited"
                in 400..499 -> "http_4xx"
                in 500..599 -> "http_5xx"
                else -> "http_other"
            }
            record("$category status=${status.takeIf { it in 100..599 } ?: 0}")
        } catch (cancelled: CancellationException) {
            record("cancelled")
            throw cancelled
        } catch (failure: Exception) {
            // Inspect only types, with a bound even for cyclic or unusually deep cause chains.
            val causes = generateSequence<Throwable>(failure) { it.cause }.take(8).toList()
            val category = when {
                causes.any { it is UnknownHostException } -> "dns_failed"
                causes.any { it is SSLException } -> "tls_failed"
                causes.any { it is SocketTimeoutException || it is io.ktor.client.plugins.HttpRequestTimeoutException } -> "timeout"
                causes.any { it is IOException } -> "io_failed"
                else -> "request_failed"
            }
            record(category)
            throw failure
        }
    }
}
