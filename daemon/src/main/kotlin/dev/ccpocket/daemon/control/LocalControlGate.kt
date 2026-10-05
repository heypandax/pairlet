package dev.ccpocket.daemon.control

import dev.ccpocket.protocol.PocketJson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

@Serializable
data class LocalError(val ok: Boolean = false, val code: String, val message: String)

const val LOCAL_CONTROL_PREFIX = "/v1/local"

/** 192 KiB: the value this cap has always had, pinned as a literal so it is a decision of its own. */
internal const val MAX_LOCAL_BODY_BYTES = 196_608

// ---- the three gates, in one place ----------------------------------------

/**
 * Enforce the token + the anti-browser rules. Answers the call itself on refusal and returns false.
 *
 * `internal`, not private: the #367 execution routes ([installExecutionControl]) are a SEPARATE file but
 * must be the SAME gate. Re-implementing these three checks next door is precisely how one surface ends
 * up without the Origin refusal or the body cap.
 */
internal suspend fun ApplicationCall.authorize(token: String, post: Boolean = true): Boolean {
    // a CLI never sets Origin; a browser always does. Refusing its PRESENCE (not just a wrong value) is
    // the honest statement that this API has no web callers at all.
    if (request.headers["Origin"] != null) {
        fail(HttpStatusCode.Forbidden, "forbidden_origin", "this API is not callable from a web page")
        return false
    }
    if (post && request.headers["Content-Type"]?.substringBefore(';')?.trim()?.lowercase() != "application/json") {
        fail(HttpStatusCode.UnsupportedMediaType, "bad_content_type", "Content-Type: application/json is required")
        return false
    }
    if (post && request.headers["Content-Length"]?.toLongOrNull()?.let { it > MAX_LOCAL_BODY_BYTES } == true) {
        fail(HttpStatusCode.PayloadTooLarge, "body_too_large", "request body exceeds $MAX_LOCAL_BODY_BYTES bytes")
        return false
    }
    if (!LocalControlToken.matches(token, request.headers[LocalControlToken.HEADER])) {
        fail(HttpStatusCode.Unauthorized, "unauthorized", "missing or wrong local control token")
        return false
    }
    return true
}

internal suspend fun <T> ApplicationCall.body(serializer: KSerializer<T>): T? {
    val bytes = runCatching {
        receiveChannel().readRemaining(MAX_LOCAL_BODY_BYTES.toLong() + 1).readByteArray()
    }.getOrNull()
    if (bytes != null && bytes.size > MAX_LOCAL_BODY_BYTES) {
        fail(HttpStatusCode.PayloadTooLarge, "body_too_large", "request body exceeds $MAX_LOCAL_BODY_BYTES bytes")
        return null
    }
    val text = bytes?.toString(Charsets.UTF_8)
    val parsed = text?.let { runCatching { PocketJson.decodeFromString(serializer, it) }.getOrNull() }
    if (parsed == null) fail(HttpStatusCode.BadRequest, "bad_request", "could not read the request body")
    return parsed
}

internal suspend fun <T> ApplicationCall.ok(serializer: KSerializer<T>, value: T) =
    respondText(PocketJson.encodeToString(serializer, value), ContentType.Application.Json)

internal suspend fun ApplicationCall.fail(status: HttpStatusCode, code: String, message: String) =
    respondText(
        PocketJson.encodeToString(LocalError.serializer(), LocalError(code = code, message = message)),
        ContentType.Application.Json,
        status,
    )
