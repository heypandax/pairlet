package dev.ccpocket.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The pre-notice client shape: metadata additions must not require a new frame discriminator. */
@Serializable
private data class LegacySessionLive(
    val convoId: String, val workdir: String, val sessionId: String? = null,
    val observing: Boolean = false, val executing: Boolean? = null,
)

class SessionNoticeWireCompatTest {
    @Test
    fun notice_roundtrips_and_old_client_ignores_it_without_changing_lifecycle_fields() {
        val frame = SessionLive("c", "/w", "sid", executing = true, notice = "Ungrouped in DSH Web")
        val encoded = PocketJson.encodeToString<Frame>(frame)
        assertEquals(frame, PocketJson.decodeFromString<Frame>(encoded))
        assertEquals(LegacySessionLive("c", "/w", "sid", executing = true),
            PocketJson.decodeFromString<LegacySessionLive>(encoded))
    }

    @Test
    fun old_daemon_metadata_has_no_notice() {
        val decoded = PocketJson.decodeFromString<Frame>(
            """{"t":"pocket/session.live","convoId":"c","workdir":"/w","sessionId":"sid"}""",
        ) as SessionLive
        assertNull(decoded.notice)
        assertNull(SessionLive("c", "/w").notice)
        val encoded = PocketJson.encodeToString<Frame>(SessionLive("c", "/w"))
        assertFalse("notice" in Json.parseToJsonElement(encoded).jsonObject, "null metadata must stay omitted on the wire")
    }
}
