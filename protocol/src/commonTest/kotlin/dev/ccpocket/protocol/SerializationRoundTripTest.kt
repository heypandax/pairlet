package dev.ccpocket.protocol

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SerializationRoundTripTest {

    @Test
    fun openSession_discriminator_defaults_and_null_omission() {
        val env = Envelope(id = "1", ts = 7, body = OpenSession(workdir = "/x"))
        val json = PocketJson.encodeToString(env)
        assertTrue("\"t\":\"pocket/session.open\"" in json, json)
        assertTrue("\"to\":\"PEER\"" in json, json)        // encodeDefaults
        assertTrue("\"mode\":\"default\"" in json, json)   // encodeDefaults
        assertFalse("resumeId" in json, json)              // explicitNulls=false
        assertEquals(env, PocketJson.decodeFromString<Envelope>(json))
    }

    @Test
    fun all_permission_modes_roundtrip() {
        val expected = mapOf(
            PermissionMode.DEFAULT to "default",
            PermissionMode.ACCEPT_EDITS to "acceptEdits",
            PermissionMode.PLAN to "plan",
            PermissionMode.BYPASS_PERMISSIONS to "bypassPermissions",
        )
        for ((mode, name) in expected) {
            assertEquals("\"$name\"", PocketJson.encodeToString(mode))
            assertEquals(mode, PocketJson.decodeFromString<PermissionMode>("\"$name\""))
        }
    }

    @Test
    fun decision_serializes_lowercase() {
        assertEquals("\"allow\"", PocketJson.encodeToString(Decision.ALLOW))
        assertEquals("\"deny\"", PocketJson.encodeToString(Decision.DENY))
    }

    @Test
    fun assistantChunk_with_text_piece_roundtrips() {
        val env = Envelope(id = "2", ts = 0, body = AssistantChunk("c1", 3, StreamPiece.Text("hi")))
        val json = PocketJson.encodeToString(env)
        assertTrue("\"t\":\"pocket/chunk\"" in json, json)
        assertTrue("\"t\":\"text\"" in json, json) // nested StreamPiece discriminator
        val back = PocketJson.decodeFromString<Envelope>(json)
        assertEquals(env, back)
        assertTrue((back.body as AssistantChunk).piece is StreamPiece.Text)
    }

    @Test
    fun unknown_keys_are_tolerated() {
        val json =
            """{"id":"9","ts":0,"to":"PEER","body":{"t":"pocket/prompt","convoId":"c","text":"hey","future":123}}"""
        val back = PocketJson.decodeFromString<Envelope>(json)
        assertEquals(SendPrompt("c", "hey"), back.body)
    }

    @Test
    fun sessionSummary_omits_null_gitBranch() {
        val s = SessionSummary(
            sessionId = "s", title = "t", firstPrompt = "p",
            messageCount = 1, cwd = "/x", lastModified = 0,
        )
        val json = PocketJson.encodeToString(s)
        assertFalse("gitBranch" in json, json)
        assertEquals(s, PocketJson.decodeFromString<SessionSummary>(json))
    }

    @Test
    fun audioChunk_roundtrips() {
        val env = Envelope(id = "4", ts = 0, body = AudioChunk("c1", "cap-1", 0, last = true, mediaType = "audio/mp4", base64 = "QUFB"))
        val json = PocketJson.encodeToString(env)
        assertTrue("\"t\":\"pocket/audio.chunk\"" in json, json)
        assertEquals(env, PocketJson.decodeFromString<Envelope>(json))
    }

    @Test
    fun audioCancel_roundtrips() {
        val env = Envelope(id = "5", ts = 0, body = AudioCancel("c1", "cap-1"))
        val json = PocketJson.encodeToString(env)
        assertTrue("\"t\":\"pocket/audio.cancel\"" in json, json)
        assertEquals(env, PocketJson.decodeFromString<Envelope>(json))
    }

    @Test
    fun transcript_ok_defaults_and_error_variant() {
        val ok = Envelope(id = "6", ts = 0, body = Transcript("c1", "cap-1", text = "hello world"))
        val okJson = PocketJson.encodeToString(ok)
        assertTrue("\"t\":\"pocket/transcript\"" in okJson, okJson)
        assertFalse("error" in okJson, okJson) // explicitNulls=false
        assertEquals(ok, PocketJson.decodeFromString<Envelope>(okJson))

        val err = Transcript("c1", "cap-2", ok = false, error = "whisper-cli not found")
        val errJson = PocketJson.encodeToString<Transcript>(err)
        assertTrue("\"ok\":false" in errJson, errJson)
        assertEquals(err, PocketJson.decodeFromString<Transcript>(errJson))
    }

    @Test
    fun sessionLive_carries_mode_and_omits_it_when_unknown() {
        val controlled = Envelope(id = "7", ts = 0, body = SessionLive("c1", "/x", "sid", mode = PermissionMode.ACCEPT_EDITS))
        val json = PocketJson.encodeToString(controlled)
        assertTrue("\"mode\":\"acceptEdits\"" in json, json)
        assertEquals(controlled, PocketJson.decodeFromString<Envelope>(json))

        val observed = SessionLive("c2", "/y", "sid2", observing = true) // terminal session: mode unknown
        val obsJson = PocketJson.encodeToString<SessionLive>(observed)
        assertFalse("mode" in obsJson, obsJson) // explicitNulls=false
        assertEquals(observed, PocketJson.decodeFromString<SessionLive>(obsJson))
    }

    @Test
    fun pairBegin_roundtrips() {
        val env = Envelope(id = "3", ts = 0, to = Route.RELAY, body = PairBegin("daemon-e2e-pub"))
        val json = PocketJson.encodeToString(env)
        assertTrue("\"t\":\"pocket/pair.begin\"" in json, json)
        assertEquals(env, PocketJson.decodeFromString<Envelope>(json))
    }
}
