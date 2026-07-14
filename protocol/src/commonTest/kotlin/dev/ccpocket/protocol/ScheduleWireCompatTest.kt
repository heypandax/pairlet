package dev.ccpocket.protocol

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pre-#137 `pocket/turn.done` shape — proves an OLD peer skips the new trailing optional. */
@kotlinx.serialization.Serializable
private data class OldTurnDone(
    val convoId: String,
    val finalText: String? = null,
    val error: String? = null,
)

/**
 * Wire compatibility for the scheduled-tasks family (issue #137): round-trips, defaults-omission,
 * and the four mixed-version directions the Messages.kt KDoc promises (old peers tolerate the new
 * frames/fields; new peers tolerate their absence).
 */
class ScheduleWireCompatTest {

    @Test
    fun scheduleCreate_roundtrips_with_repeat_and_omits_null_optionals() {
        val env = Envelope(
            id = "1", ts = 7,
            body = ScheduleCreate(
                workdir = "/w", prompt = "continue", runAtMs = 1_720_000_000_000,
                repeat = ScheduleRepeat(dailyAtMinute = 540), resumeId = "sess-1", label = "morning",
            ),
        )
        val json = PocketJson.encodeToString(env)
        assertTrue("\"t\":\"pocket/schedule.create\"" in json, json)
        assertFalse("intervalMs" in json, json) // explicitNulls=false — absent, not null
        assertFalse("model" in json, json)
        assertEquals(env, PocketJson.decodeFromString<Envelope>(json))

        // a bare one-shot omits repeat entirely
        val oneShot = Envelope(id = "2", ts = 0, body = ScheduleCreate("/w", "go", 5))
        val js2 = PocketJson.encodeToString(oneShot)
        assertFalse("repeat" in js2, js2)
        assertEquals(oneShot, PocketJson.decodeFromString<Envelope>(js2))
    }

    @Test
    fun scheduleCreate_clientId_roundtrips_and_is_a_trailing_optional() {
        // NEW app → NEW daemon: the client-chosen id rides the create and survives the round-trip
        val withId = Envelope(
            id = "c1", ts = 0,
            body = ScheduleCreate("/w", "Continue", 5, label = "Auto-continue", clientId = "autocont-c-1720"),
        )
        val js = PocketJson.encodeToString(withId)
        assertTrue("\"clientId\":\"autocont-c-1720\"" in js, js)
        assertEquals(withId, PocketJson.decodeFromString<Envelope>(js))

        // absent by default (explicitNulls=false) — zero cost when a caller doesn't opt in
        assertFalse("clientId" in PocketJson.encodeToString(Envelope("c2", 0, body = ScheduleCreate("/w", "go", 5))))

        // OLD app → NEW daemon: a pre-A1 payload without the key decodes to null (daemon then mints a UUID)
        val old = """{"id":"c3","ts":0,"to":"PEER","body":{"t":"pocket/schedule.create","workdir":"/w","prompt":"go","runAtMs":5}}"""
        assertNull((PocketJson.decodeFromString<Envelope>(old).body as ScheduleCreate).clientId)
    }

    @Test
    fun scheduleList_and_cancel_roundtrip() {
        val list = PocketJson.encodeToString(Envelope(id = "3", ts = 0, body = ScheduleList))
        assertTrue("\"t\":\"pocket/schedule.list\"" in list, list)
        assertEquals(ScheduleList, PocketJson.decodeFromString<Envelope>(list).body)

        val cancel = Envelope(id = "4", ts = 0, body = ScheduleCancel("id-9"))
        assertEquals(cancel, PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(cancel)))
    }

    @Test
    fun scheduleState_roundtrips_and_a_minimal_item_decodes_via_defaults() {
        val env = Envelope(
            id = "5", ts = 0,
            body = ScheduleState(
                items = listOf(
                    ScheduleInfo(
                        id = "a", workdir = "/w", prompt = "p", repeat = ScheduleRepeat(intervalMs = 60_000),
                        nextRunAtMs = 10, lastRunAtMs = 5, lastOutcome = "ok",
                    ),
                ),
            ),
        )
        assertEquals(env, PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(env)))
        // tail-first growth: an item carrying only `id` (a future slimmer daemon) still decodes
        val minimal = """{"id":"6","ts":0,"to":"PEER","body":{"t":"pocket/schedule.state","items":[{"id":"x"}]}}"""
        val back = PocketJson.decodeFromString<Envelope>(minimal).body as ScheduleState
        assertEquals("x", back.items.single().id)
        assertNull(back.items.single().nextRunAtMs)
    }

    @Test
    fun turnDone_usageLimitResetAt_is_a_trailing_optional_both_ways() {
        // OLD daemon → NEW app: the field is simply absent → null (no button)
        val oldJson = """{"id":"7","ts":0,"to":"PEER","body":{"t":"pocket/turn.done","convoId":"c","error":"usage limit reached|1720000000"}}"""
        val newSide = PocketJson.decodeFromString<Envelope>(oldJson).body as TurnDone
        assertNull(newSide.usageLimitResetAt)

        // NEW daemon → OLD app: the old TurnDone shape skips the unknown key (ignoreUnknownKeys)
        val newJson = PocketJson.encodeToString(
            Envelope(id = "8", ts = 0, body = TurnDone("c", error = "usage limit reached|1720000000", usageLimitResetAt = 1_720_000_000_000)),
        )
        assertTrue("usageLimitResetAt" in newJson, newJson)
        val oldSide = PocketJson.decodeFromString<OldTurnDone>(
            newJson.substringAfter("\"body\":").removeSuffix("}"),
        )
        assertEquals("c", oldSide.convoId)

        // and a clean turn omits it entirely (explicitNulls=false) — zero size cost on the hot path
        assertFalse("usageLimitResetAt" in PocketJson.encodeToString(Envelope(id = "9", ts = 0, body = TurnDone("c"))))
    }

    @Test
    fun an_unknown_schedule_frame_fails_the_envelope_decode_the_drop_path() {
        // what an OLD daemon does with pocket/schedule.create: the polymorphic discriminator is unknown,
        // decode throws, the runCatching-wrapped transport drops the frame (no reply — clients arm a deadline).
        // Simulated here with a discriminator NO peer knows.
        val json = """{"id":"10","ts":0,"to":"PEER","body":{"t":"pocket/schedule.frobnicate","x":1}}"""
        val r = runCatching { PocketJson.decodeFromString<Envelope>(json) }
        assertTrue(r.isFailure, "unknown discriminator must throw (the transport's drop path), not decode to garbage")
    }
}
