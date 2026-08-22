package dev.ccpocket.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pre-#282 `HistoryMessage` shape — an already-shipped app must skip `seq`/`uuid` beside the keys
 *  it does know, which is the structured-unknown-key path this repo has been bitten by before. */
@Serializable
private data class PreRewindHistoryMessage(
    val role: ChatRole,
    val text: String,
    val tool: String? = null,
    val error: Boolean = false,
)

/** The pre-#282 `SessionSummary` shape, same purpose for the lineage pair. */
@Serializable
private data class PreRewindSessionSummary(
    val sessionId: String,
    val title: String,
    val firstPrompt: String,
    val messageCount: Int,
    val cwd: String,
    val lastModified: Long,
    val group: String? = null,
)

/**
 * Wire compatibility for session rewind / fork (issue #282). The daemon and the app ship on independent
 * schedules, so all four directions are pinned here — and one of them is load-bearing beyond the usual
 * "it still decodes": the ABSENCE of [HistoryMessage.seq]/[HistoryMessage.uuid] IS how a new app detects
 * an old daemon and hides the feature. If those fields ever gained non-null defaults, a new app would
 * offer a rewind against a daemon that cannot perform one, and the anchor it sent would be fiction.
 * That is why the assertions below pin key ABSENCE (`explicitNulls = false`) and not merely a null after
 * a decode: an emitted explicit null would read as a present key to anything sniffing the shape.
 *
 * The two unknown-discriminator directions (old peer receives a frame type it has never heard of) are
 * pinned once, globally, by `SerializationRoundTripTest.unknown_frame_discriminator_throws` — every
 * inbound decode is runCatching-wrapped, so a throw there IS the drop. Don't delete that pin.
 */
class RewindWireCompatTest {

    @Test
    fun rewindSession_roundtrips_under_its_discriminator() {
        val env = Envelope(id = "rw1", ts = 0, body = RewindSession("c1", anchorSeq = 42, anchorUuid = "u-7", mode = RewindMode.REWIND))
        val json = PocketJson.encodeToString(env)
        assertTrue("\"t\":\"pocket/session.rewind\"" in json, json)
        assertEquals(env, PocketJson.decodeFromString<Envelope>(json))
        assertFalse((PocketJson.decodeFromString<Envelope>(json).body as RewindSession).dryRun)

        val dry = Envelope(id = "rw2", ts = 0, body = RewindSession("c1", 42, "u-7", RewindMode.FORK, dryRun = true))
        assertEquals(dry, PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(dry)))
    }

    @Test
    fun mode_is_a_tolerant_string_not_an_enum() {
        // A newer client's unknown mode must reach the daemon as DATA (answered `bad_mode`) rather than
        // failing the whole frame's decode — the difference between one refusal and a dropped connection.
        val body = PocketJson.decodeFromString<RewindSession>(
            """{"t":"pocket/session.rewind","convoId":"c1","anchorSeq":1,"anchorUuid":"u","mode":"rewind-files"}""",
        )
        assertEquals("rewind-files", body.mode)
    }

    @Test
    fun preview_and_done_roundtrip_with_their_optionals() {
        val ok = Envelope(id = "rw3", ts = 0, body = RewindPreview("c1", dropTurns = 4, dropToolCalls = 11, ok = true))
        val okJson = PocketJson.encodeToString(ok)
        assertTrue("\"t\":\"pocket/session.rewindPreview\"" in okJson, okJson)
        assertEquals(ok, PocketJson.decodeFromString<Envelope>(okJson))
        assertNull((PocketJson.decodeFromString<Envelope>(okJson).body as RewindPreview).reason)
        assertFalse("reason" in okJson, "explicitNulls=false — a clean preview carries no reason key")

        val refused = Envelope(id = "rw4", ts = 0, body = RewindPreview("c1", 0, 0, ok = false, reason = RewindRefusal.STALE))
        assertEquals(refused, PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(refused)))

        val done = Envelope(id = "rw5", ts = 0, body = RewindDone("c1", ok = true, newConvoId = "c2"))
        val doneJson = PocketJson.encodeToString(done)
        assertTrue("\"t\":\"pocket/session.rewindDone\"" in doneJson, doneJson)
        assertEquals(done, PocketJson.decodeFromString<Envelope>(doneJson))
        // the lazy branch has no session id yet, and that is a SUCCESS shape — clients must not require it
        assertNull((PocketJson.decodeFromString<Envelope>(doneJson).body as RewindDone).newSessionId)
        assertFalse("newSessionId" in doneJson, "explicitNulls=false — absent, not an explicit null")
        assertFalse("reason" in doneJson, doneJson)

        val failed = Envelope(id = "rw6", ts = 0, body = RewindDone("c1", ok = false, reason = RewindRefusal.NOT_IDLE))
        assertEquals(failed, PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(failed)))
    }

    @Test
    fun historyMessage_seq_and_uuid_are_trailing_optionals_both_ways() {
        // OLD daemon → new app: no keys ⇒ null ⇒ the app hides the rewind entry. This is the capability
        // probe itself, so a non-null default here would be a real defect, not a style question.
        val legacy = PocketJson.decodeFromString<HistoryMessage>("""{"role":"user","text":"hi"}""")
        assertNull(legacy.seq)
        assertNull(legacy.uuid)

        // …and the reverse: a row WITHOUT coordinates must go out byte-identical to the pre-#282 shape.
        // Only `explicitNulls = false` keeps the keys off it, and if that ever flipped, "has coordinates"
        // would stop being answerable from the wire at all — the probe would report every row rewindable.
        val bare = PocketJson.encodeToString(HistoryMessage(ChatRole.USER, "hi"))
        assertFalse("seq" in bare, bare)
        assertFalse("uuid" in bare, bare)

        // NEW daemon → old app: the extra keys are skipped beside the ones it knows
        val fresh = HistoryMessage(ChatRole.USER, "hi", seq = 12, uuid = "u-1")
        val old = PocketJson.decodeFromString<PreRewindHistoryMessage>(PocketJson.encodeToString(fresh))
        assertEquals(PreRewindHistoryMessage(ChatRole.USER, "hi"), old)

        assertEquals(fresh, PocketJson.decodeFromString<HistoryMessage>(PocketJson.encodeToString(fresh)))
    }

    @Test
    fun historyMessage_coordinates_survive_a_full_ConvoHistory_frame() {
        val env = Envelope(
            id = "rw7", ts = 0,
            body = ConvoHistory(
                "c1",
                listOf(
                    HistoryMessage(ChatRole.USER, "first", seq = 1, uuid = "u-1"),
                    HistoryMessage(ChatRole.ASSISTANT, "ok", seq = 2),
                ),
                lastSeq = 2, firstSeq = 1,
            ),
        )
        val back = PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(env)).body as ConvoHistory
        assertEquals(1L, back.messages[0].seq)
        assertEquals("u-1", back.messages[0].uuid)
        // a non-USER row carries the cursor but never a uuid: a rewind anchor is always a user message,
        // and shipping one on other rows would be an attractive nuisance
        assertEquals(2L, back.messages[1].seq)
        assertNull(back.messages[1].uuid)

        // the scroll-back page carries them too (same parse behind it) — rows silently losing their
        // rewind entry once you scroll up would be a hard failure to ever notice by hand
        val page = PocketJson.decodeFromString<Envelope>(
            PocketJson.encodeToString(
                Envelope(
                    id = "rw7b", ts = 0,
                    body = ConvoHistoryPage("c1", listOf(HistoryMessage(ChatRole.USER, "older", seq = 0, uuid = "u-0")), firstSeq = 0),
                ),
            ),
        ).body as ConvoHistoryPage
        assertEquals(0L, page.messages[0].seq)
        assertEquals("u-0", page.messages[0].uuid)
    }

    @Test
    fun sessionSummary_lineage_is_a_trailing_optional_both_ways() {
        val legacy = PocketJson.decodeFromString<SessionSummary>(
            """{"sessionId":"s1","title":"t","firstPrompt":"p","messageCount":1,"cwd":"/x","lastModified":9}""",
        )
        assertNull(legacy.forkedFrom)
        assertNull(legacy.rewindOf)

        val plain = PocketJson.encodeToString(SessionSummary("s1", "t", "p", 1, "/x", 9))
        assertFalse("forkedFrom" in plain, plain)
        assertFalse("rewindOf" in plain, plain)

        val forked = SessionSummary("s2", "t2", "p2", 1, "/x", 10, forkedFrom = "s1")
        val old = PocketJson.decodeFromString<PreRewindSessionSummary>(PocketJson.encodeToString(forked))
        assertEquals("s2", old.sessionId)
        assertEquals(forked, PocketJson.decodeFromString<SessionSummary>(PocketJson.encodeToString(forked)))

        val rewound = SessionSummary("s3", "t3", "p3", 1, "/x", 11, rewindOf = "s1")
        assertEquals(rewound, PocketJson.decodeFromString<SessionSummary>(PocketJson.encodeToString(rewound)))
    }

    @Test
    fun sessions_frame_carries_lineage_through_unchanged() {
        val env = Envelope(
            id = "rw8", ts = 0,
            body = Sessions(
                "/x",
                listOf(
                    SessionSummary("orig", "Original", "p", 3, "/x", 100),
                    SessionSummary("branch", "Take 2", "p", 1, "/x", 101, rewindOf = "orig"),
                ),
            ),
        )
        val back = PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(env)).body as Sessions
        assertEquals("orig", back.items[1].rewindOf)
        assertNull(back.items[0].rewindOf)
    }
}
