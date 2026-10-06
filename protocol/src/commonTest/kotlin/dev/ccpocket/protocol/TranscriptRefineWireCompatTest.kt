package dev.ccpocket.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The `pocket/client.caps` reader an already-shipped daemon decodes with (before the refine capability). */
@Serializable
private data class PreRefineClientCaps(
    val supportsAgents: List<String> = emptyList(),
    val supportsVoiceMemo: Boolean = false,
    val supportsSessionObservationV1: Boolean = false,
)

/** The `pocket/daemon.info` reader an already-shipped App decodes with (before the refine capability). */
@Serializable
private data class PreRefineDaemonInfo(
    val hostname: String? = null,
    val supportedAgents: List<String> = emptyList(),
    val voiceMemoAgents: List<String> = emptyList(),
    val supportsSessionObservationV1: Boolean = false,
)

/**
 * Wire compatibility of voice input v2's refine exchange (docs/design/VOICE-INPUT-REFINE-SEND.md §5): both frames
 * round-trip under their discriminators, both capability fields are trailing optionals that an older peer neither
 * sends nor needs, and unknown keys — on the new frames as on the capability frames — never fail a decode.
 */
class TranscriptRefineWireCompatTest {

    private fun roundTrip(frame: Frame): Frame =
        PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(Envelope("1", 0, body = frame))).body

    private fun bodyJson(frame: Frame): String =
        PocketJson.parseToJsonElement(PocketJson.encodeToString(Envelope("1", 0, body = frame))).jsonObject
            .getValue("body").toString()

    private fun body(json: String): Frame = PocketJson.decodeFromString<Envelope>("""{"id":"1","ts":0,"body":$json}""").body

    @Test
    fun refine_request_roundtrips_under_its_discriminator() {
        val full = TranscriptRefine("c1", "cap-1", "帮我看一下 cloud code 的日志", locale = "zh-Hans", agentHint = "claude")
        assertEquals(full, roundTrip(full))
        assertTrue(bodyJson(full).contains("\"t\":\"pocket/transcript.refine\""), bodyJson(full))
        // the optional hints are omitted when null (explicitNulls = false) and come back null
        val bare = TranscriptRefine("c1", "cap-2", "hello")
        assertFalse(bodyJson(bare).contains("locale"))
        assertFalse(bodyJson(bare).contains("agentHint"))
        assertEquals(bare, roundTrip(bare))
    }

    @Test
    fun refined_reply_roundtrips_both_shapes() {
        val ok = TranscriptRefined(
            "c1", "cap-1", ok = true,
            text = "帮我看一下 Claude Code 的日志",
            edits = listOf(TextEdit("cloud code", "Claude Code")),
            agent = "claude",
        )
        assertEquals(ok, roundTrip(ok))
        assertTrue(bodyJson(ok).contains("\"t\":\"pocket/transcript.refined\""), bodyJson(ok))

        val failed = TranscriptRefined("c1", "cap-1", ok = false, agent = "claude", error = TranscriptRefineError.TIMEOUT)
        val back = assertIs<TranscriptRefined>(roundTrip(failed))
        assertEquals(failed, back)
        assertEquals("", back.text)
        assertTrue(back.edits.isEmpty())
    }

    @Test
    fun minimal_hand_written_frames_decode_with_defaults() {
        val req = assertIs<TranscriptRefine>(
            body("""{"t":"pocket/transcript.refine","convoId":"c","captureId":"k","text":"hi"}"""),
        )
        assertNull(req.locale)
        assertNull(req.agentHint)

        val reply = assertIs<TranscriptRefined>(
            body("""{"t":"pocket/transcript.refined","convoId":"c","captureId":"k","ok":false}"""),
        )
        assertEquals("", reply.text)
        assertTrue(reply.edits.isEmpty())
        assertNull(reply.agent)
        assertNull(reply.error)
    }

    @Test
    fun legacy_capability_json_decodes_to_no_refine() {
        // what an older App / daemon actually puts on the wire: other capabilities present, the new keys absent
        val caps = assertIs<ClientCaps>(
            body("""{"t":"pocket/client.caps","supportsAgents":["opencode"],"supportsVoiceMemo":true,"maxFrameBytes":4194304}"""),
        )
        assertFalse(caps.supportsTranscriptRefine)
        assertTrue(caps.supportsVoiceMemo)

        val info = assertIs<DaemonInfo>(
            body("""{"t":"pocket/daemon.info","hostname":"mac","supportedAgents":["claude","codex"],"voiceMemoAgents":["claude"]}"""),
        )
        assertTrue(info.transcriptRefineAgents.isEmpty())
        assertEquals(listOf("claude"), info.voiceMemoAgents)

        // the bare discriminators decode too
        assertFalse((body("""{"t":"pocket/client.caps"}""") as ClientCaps).supportsTranscriptRefine)
        assertTrue((body("""{"t":"pocket/daemon.info"}""") as DaemonInfo).transcriptRefineAgents.isEmpty())
    }

    @Test
    fun new_capability_fields_roundtrip_and_older_readers_skip_them() {
        val caps = ClientCaps(supportsAgents = listOf("opencode"), supportsVoiceMemo = true, supportsTranscriptRefine = true)
        assertTrue((roundTrip(caps) as ClientCaps).supportsTranscriptRefine)
        val oldDaemonView = PocketJson.decodeFromString<PreRefineClientCaps>(bodyJson(caps))
        assertEquals(PreRefineClientCaps(supportsAgents = listOf("opencode"), supportsVoiceMemo = true), oldDaemonView)

        val info = DaemonInfo(hostname = "mac", supportedAgents = listOf("claude"), transcriptRefineAgents = listOf("claude"))
        assertEquals(listOf("claude"), (roundTrip(info) as DaemonInfo).transcriptRefineAgents)
        val oldAppView = PocketJson.decodeFromString<PreRefineDaemonInfo>(bodyJson(info))
        assertEquals(PreRefineDaemonInfo(hostname = "mac", supportedAgents = listOf("claude")), oldAppView)
    }

    @Test
    fun unknown_keys_never_fail_a_decode() {
        val req = assertIs<TranscriptRefine>(
            body("""{"t":"pocket/transcript.refine","convoId":"c","captureId":"k","text":"hi","mode":"strict","future":{"x":[1,2]}}"""),
        )
        assertEquals("hi", req.text)

        val reply = assertIs<TranscriptRefined>(
            body(
                """{"t":"pocket/transcript.refined","convoId":"c","captureId":"k","ok":true,"text":"Hi",""" +
                    """"edits":[{"from":"hi","to":"Hi","span":[0,2]}],"agent":"claude","latencyMs":2100}""",
            ),
        )
        assertEquals(listOf(TextEdit("hi", "Hi")), reply.edits)

        val caps = assertIs<ClientCaps>(body("""{"t":"pocket/client.caps","supportsTranscriptRefine":true,"supportsSomethingLater":true}"""))
        assertTrue(caps.supportsTranscriptRefine)
        val info = assertIs<DaemonInfo>(body("""{"t":"pocket/daemon.info","transcriptRefineAgents":["claude","later"],"refineVersion":3}"""))
        // a name this build has no adapter for stays a plain string — the list is never an enum
        assertEquals(listOf("claude", "later"), info.transcriptRefineAgents)
        // an error code this build does not know still decodes (the phone reads it as "failed")
        val unknownCode = assertIs<TranscriptRefined>(body("""{"t":"pocket/transcript.refined","convoId":"c","captureId":"k","ok":false,"error":"quota"}"""))
        assertEquals("quota", unknownCode.error)
    }

    @Test
    fun wire_vocabulary_is_pinned() {
        assertEquals(
            listOf("unavailable", "invalid", "timeout", "failed", "superseded"),
            listOf(
                TranscriptRefineError.UNAVAILABLE, TranscriptRefineError.INVALID, TranscriptRefineError.TIMEOUT,
                TranscriptRefineError.FAILED, TranscriptRefineError.SUPERSEDED,
            ),
        )
        assertEquals(4_000, TranscriptRefineLimits.MAX_TEXT_CHARS)
    }
}
