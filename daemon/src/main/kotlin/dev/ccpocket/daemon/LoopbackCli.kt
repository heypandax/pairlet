package dev.ccpocket.daemon

import dev.ccpocket.daemon.control.LocalControlToken
import dev.ccpocket.protocol.PocketJson
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * The CLI half of the token on the LEGACY loopback routes (`pair`, `pair --headless`, `bridges`, `status`;
 * pairing security phase 0). The daemon refuses those routes without the local control token, so each of
 * these commands reads the same 0600 file the daemon wrote and presents it — exactly what `pairlet agent`
 * already does through [dev.ccpocket.daemon.control.LocalControlClient].
 *
 * Kept apart from that client on purpose: these routes keep their historic request/response shapes (no JSON
 * Content-Type, `{"error","message"}` refusals) so a NEW CLI still works against an OLDER daemon, which
 * simply ignores the header.
 */
internal object LoopbackCli {

    /** One loopback reply: status plus raw body. */
    class Reply(val status: HttpStatusCode, val body: String)

    suspend fun reply(res: HttpResponse) = Reply(res.status, res.bodyAsText())

    /** The token the running daemon minted, or null when no daemon has ever run as this user here. */
    fun token(path: File = LocalControlToken.defaultPath()): String? = LocalControlToken.read(path)

    /** What to print when there is no token file at all. */
    fun missingTokenMessage(path: File = LocalControlToken.defaultPath(), startHint: String = daemonStartHintText()): String =
        "no local control token in $path — has the daemon run as this user yet? $startHint"

    fun HttpRequestBuilder.withToken(token: String) {
        header(LocalControlToken.HEADER, token)
    }

    /**
     * A plain sentence for a gate refusal (401 / 403 / 503 from the loopback guard), or null when [reply] is
     * not one — the caller then handles it as it always did.
     */
    fun refusal(reply: Reply): String? {
        if (reply.status != HttpStatusCode.Unauthorized && reply.status != HttpStatusCode.Forbidden &&
            reply.status != HttpStatusCode.ServiceUnavailable
        ) return null
        val obj = runCatching { PocketJson.parseToJsonElement(reply.body) as? JsonObject }.getOrNull() ?: return null
        val code = obj["error"]?.jsonPrimitive?.content ?: return null
        val message = obj["message"]?.jsonPrimitive?.content.orEmpty()
        return when (code) {
            "unauthorized" -> "the running daemon rejected this CLI's local control token — $message"
            "cli_outdated", "local_control_unavailable", "forbidden_origin", "forbidden_host" -> "$message ($code)"
            else -> null // e.g. relay_offline (503): not a gate refusal
        }
    }
}
