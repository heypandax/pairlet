package dev.ccpocket.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wire compatibility for the push-registration receipt ([PROTO_V_PUSH_ACK]).
 *
 * Two directions have to stay safe at once: an OLD client's `RegisterPush` (no `requestId`) must decode
 * on a NEW relay unchanged, and a NEW relay's `PushRegistrationResult` must be a frame an OLD client
 * merely drops. The latter is why the last test pins the hard-failure on an unknown discriminator —
 * every control-plane reader wraps the decode in `runCatching {}.getOrNull()`, so "throws" is precisely
 * what "an old build ignores this frame" is made of.
 */
class PushRegistrationWireCompatTest {

    @Test fun an_old_registration_without_a_requestId_still_decodes() {
        val old = """{"t":"pocket/push.register","platform":"apns","token":"tok-1"}"""

        val body = assertIs<RegisterPush>(PocketJson.decodeFromString<Frame>(old))
        assertEquals("apns", body.platform)
        assertEquals("tok-1", body.token)
        assertNull(body.requestId) // absent means "do not answer me", not "answer me with null"
    }

    @Test fun a_registration_without_a_requestId_encodes_to_the_old_bytes() {
        // explicitNulls=false keeps the absent id off the wire, so an old RELAY sees byte-identical
        // frames from a new client — the field can only ever be additive
        val encoded = PocketJson.encodeToString<Frame>(RegisterPush("fcm", "tok-2"))
        assertTrue("requestId" !in encoded, "an opt-out registration must not carry the key at all: $encoded")
    }

    @Test fun a_registration_with_a_requestId_roundtrips() {
        val frame = RegisterPush("fcm", "tok-3", requestId = "rid-abc")
        val back = PocketJson.decodeFromString<Frame>(PocketJson.encodeToString<Frame>(frame))
        assertEquals(frame, back)
    }

    @Test fun every_receipt_shape_roundtrips_through_an_envelope() {
        val cases = listOf(
            PushRegistrationResult("rid-1", PushRegistrationOutcome.STORED),
            PushRegistrationResult("rid-2", PushRegistrationOutcome.CLEARED),
            PushRegistrationResult("rid-3", PushRegistrationOutcome.REJECTED, code = "forbidden"),
            PushRegistrationResult("rid-4", PushRegistrationOutcome.FAILED, code = "store_failed"),
        )
        for (frame in cases) {
            val envelope = Envelope(id = "r", ts = 0, to = Route.RELAY, body = frame)
            assertEquals(envelope, PocketJson.decodeFromString(PocketJson.encodeToString(envelope)))
        }
    }

    @Test fun the_outcome_names_are_the_stable_wire_words() {
        val encoded = PocketJson.encodeToString<Frame>(PushRegistrationResult("r", PushRegistrationOutcome.STORED))
        assertTrue("\"stored\"" in encoded, encoded)
        assertTrue("pocket/push.register.result" in encoded, encoded)
    }

    @Test fun an_old_client_drops_the_receipt_instead_of_reading_it_as_something_else() {
        // a build that predates this frame has no such discriminator registered; the decode throws and its
        // runCatching{}.getOrNull() call site turns that into "ignored", never into a misread frame
        val receipt = """{"id":"r","ts":0,"to":"relay","body":{"t":"pocket/push.register.result","requestId":"rid","result":"stored"}}"""
        assertTrue(PocketJson.decodeFromString<Envelope>(receipt).body is PushRegistrationResult) // new build
        assertFailsWith<SerializationException> {
            PocketJson.decodeFromString<Envelope>(receipt.replace("pocket/push.register.result", "pocket/push.register.verdict.v9"))
        }
    }
}
