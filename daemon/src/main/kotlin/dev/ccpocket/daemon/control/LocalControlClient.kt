package dev.ccpocket.daemon.control

import com.github.ajalt.clikt.core.CliktError
import dev.ccpocket.protocol.PocketJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.KSerializer
import java.io.File

/**
 * The CLI's side of the local control API — ONE place that knows about the token header, the JSON
 * content type, the "is the daemon even up?" answer and how a [LocalError] becomes an exit code.
 *
 * It only ever talks to an ALREADY-RUNNING daemon on loopback. It must never start one: a second daemon
 * would fight the first over the relay account and port 8799, which is the single most common way this
 * product breaks (see AGENTS.md). An unreachable daemon is therefore a clean non-zero failure with the
 * platform's restart hint, never an implicit spawn.
 */
class LocalControlClient(
    private val port: Int,
    private val startHint: String,
    private val jsonErrors: Boolean = false,
    private val tokenPath: File = LocalControlToken.defaultPath(),
) {

    /** GET [path] with optional query params; returns the decoded [serializer] body. */
    suspend fun <T> get(path: String, serializer: KSerializer<T>, query: Map<String, String> = emptyMap()): T {
        val q = if (query.isEmpty()) "" else "?" + query.entries.joinToString("&") { (k, v) -> "$k=${urlEncode(v)}" }
        return call(serializer) { client -> client.get("$base$path$q") { header(LocalControlToken.HEADER, token()) } }
    }

    /** POST [path] with a JSON [body]; returns the decoded [serializer] response. */
    suspend fun <T, B> post(path: String, bodySerializer: KSerializer<B>, body: B, serializer: KSerializer<T>): T =
        call(serializer) { client ->
            client.post("$base$path") {
                header(LocalControlToken.HEADER, token())
                contentType(ContentType.Application.Json)
                setBody(PocketJson.encodeToString(bodySerializer, body))
            }
        }

    private suspend fun <T> call(serializer: KSerializer<T>, send: suspend (HttpClient) -> HttpResponse): T {
        val client = HttpClient(CIO)
        try {
            val res = try {
                send(client)
            } catch (e: CliktError) {
                throw e
            } catch (_: Throwable) {
                fail("daemon_unreachable", "no cc-pocket daemon on 127.0.0.1:$port — $startHint")
            }
            val text = res.bodyAsText()
            if (res.status != HttpStatusCode.OK) {
                val err = runCatching { PocketJson.decodeFromString(LocalError.serializer(), text) }.getOrNull()
                // an unauthorized answer almost always means a version skew, not a wrong password —
                // there is no password to get wrong, only a file both sides read
                if (res.status == HttpStatusCode.Unauthorized) {
                    fail("token_rejected", "the daemon rejected this CLI's local control token — update the daemon and the CLI to the same version")
                }
                if (err != null) fail(err.code, err.message)
                fail("daemon_refused", "the daemon refused the request (${res.status})")
            }
            return runCatching { PocketJson.decodeFromString(serializer, text) }
                .getOrElse { fail("reply_invalid", "could not read the daemon's reply — is it a newer version than this CLI?") }
        } finally {
            client.close()
        }
    }

    /** The token the running daemon minted. Absent = no daemon has ever run as this user here. */
    private fun token(): String = LocalControlToken.read(tokenPath)
        ?: fail("token_missing", "no local control token in $tokenPath — start the daemon once first ($startHint)")

    private fun fail(code: String, message: String): Nothing {
        val rendered = if (jsonErrors) {
            PocketJson.encodeToString(LocalError.serializer(), LocalError(code = code, message = message))
        } else {
            "$message ($code)"
        }
        throw CliktError(rendered)
    }

    private val base = "http://127.0.0.1:$port$LOCAL_CONTROL_PREFIX"

    private fun urlEncode(v: String): String =
        java.net.URLEncoder.encode(v, Charsets.UTF_8)
}
