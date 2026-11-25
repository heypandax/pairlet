package dev.ccpocket.protocol

import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * pocket/file.progress is a new daemon→phone receipt sent during every FileChunk stream, without a capability
 * gate: an old phone fails to decode the unknown "t" and drops the frame (runCatching at decode), and an old
 * daemon never sends it (the new phone then falls back to its size-scaled deadline).
 */
class FileUploadProgressWireCompatTest {
    @Test
    fun roundtrips_under_its_wire_name() {
        val env = Envelope(id = "fp1", ts = 0, body = FileUploadProgress("c1", "cap-1", 3, 3L * 786_432))
        val json = PocketJson.encodeToString(env)
        assertTrue("\"t\":\"pocket/file.progress\"" in json, json)
        assertEquals(env, PocketJson.decodeFromString<Envelope>(json))
    }

    @Test
    fun the_existing_landing_receipt_is_unchanged() {
        val env = Envelope(id = "fp2", ts = 0, body = FileUploaded("c1", "cap-1", path = ".ccpocket/inbox/cap-1/a.mov", name = "a.mov", size = 1))
        val json = PocketJson.encodeToString(env)
        assertTrue("\"t\":\"pocket/file.uploaded\"" in json, json)
        assertEquals(env, PocketJson.decodeFromString<Envelope>(json))
    }
}
