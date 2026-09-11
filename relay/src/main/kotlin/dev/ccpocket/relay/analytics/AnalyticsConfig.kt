package dev.ccpocket.relay.analytics

import java.security.SecureRandom

/** One GA4 Measurement-Protocol data stream. The secret never leaves this process. */
class Ga4Stream(val measurementId: String, val apiSecret: String) {
    override fun toString() = "Ga4Stream($measurementId)" // never echo the secret
    companion object {
        /** `G-XXXXXXXX:<api_secret>` — split at the FIRST colon so a secret may contain colons. */
        fun parse(raw: String?): Ga4Stream? {
            val value = raw?.trim().orEmpty()
            val i = value.indexOf(':')
            if (i <= 0 || i == value.lastIndex) return null
            val id = value.substring(0, i)
            val secret = value.substring(i + 1)
            if (!Regex("G-[A-Z0-9]{4,20}").matches(id) || secret.length !in 8..128 || secret.startsWith("REPLACE")) return null
            return Ga4Stream(id, secret)
        }
    }
}

/**
 * Server-side analytics ingress configuration (DESKTOP-GA4-INGRESS.md §5). Read from the environment
 * (`/etc/cc-pocket-relay/analytics.env` via systemd); absent or disabled means every endpoint answers
 * 204 and nothing is forwarded. Streams are keyed by the CLIENT-reported `app_environment`; an
 * environment without a stream is dropped, so development traffic can never reach a real stream.
 */
data class AnalyticsConfig(
    val enabled: Boolean,
    val streams: Map<String, Ga4Stream>,
    val tokenKey: ByteArray,
    /** Forward `debug_mode=1` for staging events (GA4 DebugView). Never applied to production. */
    val debugStaging: Boolean,
) {
    val active: Boolean get() = enabled && streams.isNotEmpty()

    companion object {
        fun fromEnv(env: (String) -> String? = System::getenv): AnalyticsConfig {
            val streams = buildMap {
                Ga4Stream.parse(env("CCPOCKET_ANALYTICS_STREAM_PRODUCTION"))?.let { put("production", it) }
                Ga4Stream.parse(env("CCPOCKET_ANALYTICS_STREAM_STAGING"))?.let { put("staging", it) }
            }
            val key = env("CCPOCKET_ANALYTICS_TOKEN_KEY")?.trim()?.takeIf { it.length >= 64 && Regex("[0-9a-fA-F]+").matches(it) }
                ?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()
                ?: ByteArray(32).also { SecureRandom().nextBytes(it) } // per-boot key: old tokens simply re-register
            return AnalyticsConfig(
                enabled = env("CCPOCKET_ANALYTICS_ENABLED") == "true",
                streams = streams,
                tokenKey = key,
                debugStaging = env("CCPOCKET_ANALYTICS_DEBUG") == "1",
            )
        }

        fun disabled() = AnalyticsConfig(false, emptyMap(), ByteArray(32), false)
    }
}
