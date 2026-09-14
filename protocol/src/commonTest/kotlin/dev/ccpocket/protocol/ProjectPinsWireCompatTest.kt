package dev.ccpocket.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pre-#362 `pocket/client.caps` body — proves an already-shipped daemon skips the new flag. */
@Serializable
private data class PrePinsClientCaps(
    val supportsAgents: List<String> = emptyList(),
    val supportsApprovalV2: Boolean = false,
    val supportsDiagnostics: Boolean = false,
)

/** A pre-#362 `pocket/daemon.info` reader — proves an already-shipped client skips the new flag. */
@Serializable
private data class PrePinsDaemonInfo(
    val lanUrl: String? = null,
    val hostname: String? = null,
    val supportedAgents: List<String> = emptyList(),
    val supportsDiagnostics: Boolean = false,
)

/**
 * Wire compatibility for project-pin sync (issue #362): both new frames round-trip, absent fields read as
 * the legacy meaning, the two capability flags are trailing optionals in BOTH directions, an unknown pin
 * discriminator takes the transport's drop path, and the shared bounds keep a full frame far below the
 * 4 MiB relay/LAN cap.
 */
class ProjectPinsWireCompatTest {

    private fun roundTrip(frame: Frame): Frame =
        PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(Envelope("1", 0, body = frame))).body

    private fun bodyJson(frame: Frame): String =
        PocketJson.parseToJsonElement(PocketJson.encodeToString(Envelope("1", 0, body = frame))).jsonObject
            .getValue("body").toString()

    private fun snapshot() = ProjectPinsSnapshot(
        incarnation = "inc-0123456789abcdef",
        revision = 7,
        pins = listOf(ProjectPin("/Users/me/app", "k1"), ProjectPin("~/notes", "k2")),
    )

    @Test
    fun sync_request_roundtrips_under_its_discriminator() {
        val request = SyncProjectPins(
            requestId = "r1",
            subscriptionId = "sub-0123456789abcdef",
            streamId = "stream-0123456789ab",
            ops = listOf(ProjectPinOp(1, "/Users/me/app", pinned = true), ProjectPinOp(2, "~/notes", pinned = false)),
            expectedIncarnation = "inc-0123456789abcdef",
        )
        assertEquals(request, roundTrip(request))
        assertTrue("\"t\":\"pocket/pins.sync\"" in bodyJson(request))
    }

    @Test
    fun an_absent_ops_array_is_a_fetch() {
        val json = """{"id":"1","ts":0,"body":{"t":"pocket/pins.sync","requestId":"r","subscriptionId":"s","streamId":"x"}}"""
        val decoded = PocketJson.decodeFromString<Envelope>(json).body as SyncProjectPins
        assertTrue(decoded.ops.isEmpty(), "no ops must read as a fetch, never as a failed decode")
        assertNull(decoded.expectedIncarnation)
    }

    @Test
    fun a_batch_that_names_no_incarnation_still_decodes_as_null() {
        val json = """{"id":"1","ts":0,"body":{"t":"pocket/pins.sync","requestId":"r","subscriptionId":"s","streamId":"x",""" +
            """"ops":[{"seq":1,"path":"/p","pinned":true}]}}"""
        val decoded = PocketJson.decodeFromString<Envelope>(json).body as SyncProjectPins
        assertEquals(1, decoded.ops.size)
        assertNull(decoded.expectedIncarnation, "absent reads as null — the daemon refuses such a batch, it never guesses")
    }

    @Test
    fun a_reply_without_resolutions_decodes_and_a_resolution_row_roundtrips() {
        val json = """{"id":"1","ts":0,"body":{"t":"pocket/pins.state","subscriptionId":"s","requestId":"r","ackSeq":1,""" +
            """"snapshot":{"incarnation":"i","revision":1,"pins":[]}}}"""
        assertNull((PocketJson.decodeFromString<Envelope>(json).body as ProjectPinsState).resolutions)

        val row = ProjectPinResolution(seq = 3, path = "~/中文 项目", key = "k".repeat(43))
        assertEquals(row, PocketJson.decodeFromString<ProjectPinResolution>(PocketJson.encodeToString(row)))
        val later = PocketJson.decodeFromString<ProjectPinResolution>("""{"seq":3,"path":"/p","key":"k","later":[1]}""")
        assertEquals(ProjectPinResolution(3, "/p", "k"), later, "an unknown key inside a row is skipped")
    }

    @Test
    fun reply_push_and_refusal_roundtrip_and_a_push_carries_no_reply_fields() {
        val reply = ProjectPinsState(
            "sub", requestId = "r1", streamId = "st", ackSeq = 2, snapshot = snapshot(),
            resolutions = listOf(ProjectPinResolution(1, "/Users/me/app", "k1"), ProjectPinResolution(2, "~/notes", "k2")),
        )
        val push = ProjectPinsState("sub", snapshot = snapshot())
        val refusal = ProjectPinsState(
            "sub", requestId = "r2", streamId = "st", ackSeq = 2, snapshot = snapshot(),
            error = ProjectPinErrors.CAPACITY, message = "too many pins",
        )
        val mismatch = ProjectPinsState(
            "sub", requestId = "r3", streamId = "st", snapshot = snapshot(), error = ProjectPinErrors.INCARNATION_MISMATCH,
        )
        listOf(reply, push, refusal, mismatch).forEach { assertEquals(it, roundTrip(it)) }
        val pushJson = bodyJson(push)
        assertTrue("\"t\":\"pocket/pins.state\"" in pushJson)
        listOf("requestId", "streamId", "ackSeq", "error", "resolutions").forEach {
            assertFalse("\"$it\"" in pushJson, "a push must not carry $it: $pushJson")
        }
        val mismatchJson = bodyJson(mismatch)
        listOf("ackSeq", "resolutions").forEach {
            assertFalse("\"$it\"" in mismatchJson, "an incarnation refusal must not carry $it: $mismatchJson")
        }
        // the refusal vocabulary is coordinated with the client: these spellings are wire contract
        assertEquals("pins_incarnation_mismatch", ProjectPinErrors.INCARNATION_MISMATCH)
        assertEquals("pins_subscription_stale", ProjectPinErrors.SUBSCRIPTION_STALE)
    }

    @Test
    fun a_storage_refusal_decodes_without_any_snapshot() {
        val json = """{"id":"1","ts":0,"body":{"t":"pocket/pins.state","subscriptionId":"s","requestId":"r","error":"pins_store_corrupt"}}"""
        val state = PocketJson.decodeFromString<Envelope>(json).body as ProjectPinsState
        assertNull(state.snapshot, "a refusal with no readable store must not synthesize an empty list")
        assertNull(state.ackSeq)
        assertEquals(ProjectPinErrors.STORE_CORRUPT, state.error)
    }

    @Test
    fun snapshot_fields_default_for_a_minimal_payload() {
        val snap = PocketJson.decodeFromString<ProjectPinsSnapshot>("""{"incarnation":"i","revision":0}""")
        assertTrue(snap.pins.isEmpty())
    }

    @Test
    fun clientCaps_supportsProjectPins_is_a_trailing_optional_both_ways() {
        // old client -> new daemon: absent reads as "does not decode pin frames"
        val legacy = PocketJson.decodeFromString<Envelope>(
            """{"id":"1","ts":0,"body":{"t":"pocket/client.caps","supportsAgents":["opencode"],"supportsApprovalV2":true}}""",
        ).body as ClientCaps
        assertFalse(legacy.supportsProjectPins)
        // new client -> old daemon: the unknown key is skipped and every older field survives
        val modern = ClientCaps(supportsAgents = listOf("kimi"), supportsDiagnostics = true, supportsProjectPins = true)
        assertEquals(modern, roundTrip(modern))
        val old = PocketJson.decodeFromString<PrePinsClientCaps>(bodyJson(modern))
        assertEquals(listOf("kimi"), old.supportsAgents)
        assertTrue(old.supportsDiagnostics)
    }

    @Test
    fun daemonInfo_supportsProjectPins_is_a_trailing_optional_both_ways() {
        assertFalse(PocketJson.decodeFromString<DaemonInfo>("""{"daemonVersion":"2.0.0"}""").supportsProjectPins)
        val modern = DaemonInfo(hostname = "mac", supportedAgents = listOf("claude"), supportsDiagnostics = true, supportsProjectPins = true)
        assertEquals(modern, roundTrip(modern))
        val old = PocketJson.decodeFromString<PrePinsDaemonInfo>(bodyJson(modern))
        assertEquals("mac", old.hostname)
        assertTrue(old.supportsDiagnostics)
    }

    @Test
    fun an_unknown_pin_frame_fails_the_envelope_decode_the_drop_path() {
        val json = """{"id":"1","ts":0,"body":{"t":"pocket/pins.frobnicate","x":1}}"""
        assertTrue(runCatching { PocketJson.decodeFromString<Envelope>(json) }.isFailure)
    }

    @Test
    fun unknown_keys_inside_pin_frames_are_skipped_losslessly() {
        val json = """{"id":"1","ts":0,"body":{"t":"pocket/pins.state","subscriptionId":"s","future":{"a":[1,2]},""" +
            """"snapshot":{"incarnation":"i","revision":3,"pins":[{"path":"/p","key":"k","later":true}]},"ackSeq":4}}"""
        val state = PocketJson.decodeFromString<Envelope>(json).body as ProjectPinsState
        assertEquals(4, state.ackSeq)
        assertEquals(listOf(ProjectPin("/p", "k")), state.snapshot?.pins)
    }

    @Test
    fun validators_bound_what_either_end_accepts() {
        assertTrue(isValidProjectPinPath("/Users/me/app"))
        assertTrue(isValidProjectPinPath("C:\\Users\\me\\app"))
        assertTrue(isValidProjectPinPath("~/中文 项目"))
        assertFalse(isValidProjectPinPath(""))
        assertFalse(isValidProjectPinPath("   "))
        assertFalse(isValidProjectPinPath("/a\nb"))
        assertFalse(isValidProjectPinPath("/a\u0000b"))
        assertFalse(isValidProjectPinPath("/a\u007fb"))
        assertTrue(isValidProjectPinPath("/" + "x".repeat(PROJECT_PIN_PATH_MAX_CHARS - 1)))
        assertFalse(isValidProjectPinPath("/" + "x".repeat(PROJECT_PIN_PATH_MAX_CHARS)))

        assertTrue(isValidProjectPinToken("0123456789abcdef"))
        assertFalse(isValidProjectPinToken("short"))
        assertTrue(isValidProjectPinToken("r1", minChars = 1))
        assertFalse(isValidProjectPinToken("has space in it here"))
        assertFalse(isValidProjectPinToken("x".repeat(PROJECT_PIN_ID_MAX_CHARS + 1)))
    }

    @Test
    fun a_full_snapshot_and_a_full_request_stay_far_below_the_frame_cap() {
        // 3 UTF-8 bytes per UTF-16 unit is the worst a valid path can cost (control characters are refused)
        val widest = "中".repeat(PROJECT_PIN_PATH_MAX_CHARS)
        val key = "k".repeat(43)
        val state = ProjectPinsState(
            subscriptionId = "s".repeat(PROJECT_PIN_ID_MAX_CHARS),
            requestId = "r".repeat(PROJECT_PIN_ID_MAX_CHARS),
            streamId = "t".repeat(PROJECT_PIN_ID_MAX_CHARS),
            ackSeq = Long.MAX_VALUE,
            snapshot = ProjectPinsSnapshot("i".repeat(PROJECT_PIN_ID_MAX_CHARS), Long.MAX_VALUE, List(PROJECT_PINS_MAX) { ProjectPin(widest, key) }),
            // a successful reply to a full batch also resolves every submitted operation
            resolutions = List(PROJECT_PINS_MAX_OPS) { ProjectPinResolution(Long.MAX_VALUE - it, widest, key) },
        )
        val request = SyncProjectPins(
            "r".repeat(PROJECT_PIN_ID_MAX_CHARS), "s".repeat(PROJECT_PIN_ID_MAX_CHARS), "t".repeat(PROJECT_PIN_ID_MAX_CHARS),
            List(PROJECT_PINS_MAX_OPS) { ProjectPinOp(Long.MAX_VALUE - it, widest, pinned = true) },
            expectedIncarnation = "i".repeat(PROJECT_PIN_ID_MAX_CHARS),
        )
        val oneMiB = 1024 * 1024
        val stateBytes = PocketJson.encodeToString(Envelope("1", 0, body = state)).encodeToByteArray().size
        val requestBytes = PocketJson.encodeToString(Envelope("1", 0, body = request)).encodeToByteArray().size
        assertTrue(stateBytes < oneMiB, "full snapshot is $stateBytes bytes")
        assertTrue(requestBytes < oneMiB, "full request is $requestBytes bytes")
    }
}
