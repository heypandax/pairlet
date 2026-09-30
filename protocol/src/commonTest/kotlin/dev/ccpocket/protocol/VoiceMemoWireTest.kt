package dev.ccpocket.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A pre-memo `pocket/client.caps` reader — proves an already-shipped daemon skips the new flag. */
@Serializable
private data class PreMemoClientCaps(
    val supportsAgents: List<String> = emptyList(),
    val supportsProjectPins: Boolean = false,
    val maxFrameBytes: Long = 0,
)

/** A pre-memo `pocket/daemon.info` reader — proves an already-shipped client skips the new fields. */
@Serializable
private data class PreMemoDaemonInfo(
    val hostname: String? = null,
    val supportsManagedSessions: Boolean = false,
    val managedAgents: List<String> = emptyList(),
)

class VoiceMemoWireTest {
    private val memoId = "0b6f3c1e-8a52-4d0e-9f11-2c7a5e9d4b10"
    private val attemptId = "5d2a9f70-1c34-4e8b-a6d2-7f0e3b1c9a44"
    private val hash = "a".repeat(64)
    private val agents = listOf(VOICE_MEMO_AGENT_CLAUDE)

    private fun roundTrip(frame: Frame): Frame =
        PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(Envelope("1", 0, body = frame))).body

    private fun bodyJson(frame: Frame): String =
        PocketJson.parseToJsonElement(PocketJson.encodeToString(Envelope("1", 0, body = frame))).jsonObject
            .getValue("body").toString()

    private fun audioStart(bytes: Long = 300_000, duration: Long = 75_000) = VoiceMemoStart(
        memoId = memoId, attemptId = attemptId, inputKind = VoiceMemoInputKind.AUDIO,
        mediaType = VoiceMemoLimits.AUDIO_MEDIA_TYPE, durationMs = duration, byteLength = bytes,
        sha256 = hash, chunkCount = VoiceMemoLimits.chunkCountFor(bytes),
    )

    private fun transcriptStart(text: String = "检查 mobile build") = VoiceMemoStart(
        memoId = memoId, attemptId = attemptId, inputKind = VoiceMemoInputKind.TRANSCRIPT,
        sha256 = hash, transcript = text, durationMs = 75_000,
    )

    private fun result(todos: Int = 3) = VoiceMemoResult(
        title = "出门想到的三件事", summary = "整理了三件事。",
        todos = List(todos) { VoiceMemoTodoSuggestion("待办 $it") }, language = "zh",
    )

    @Test
    fun the_five_frames_roundtrip_under_their_discriminators() {
        val frames = listOf(
            audioStart() to "pocket/memo.start",
            VoiceMemoAudio(memoId, attemptId, 0, "AAAA") to "pocket/memo.audio",
            VoiceMemoGet(memoId, attemptId) to "pocket/memo.get",
            VoiceMemoCancel(memoId, attemptId) to "pocket/memo.cancel",
            VoiceMemoState(
                memoId, attemptId, revision = 4, stage = VoiceMemoStage.READY, transcript = "原文", result = result(),
                metrics = VoiceMemoMetrics(audioDurationMs = 75_000, transcribeMs = 8_400, summarizeMs = 4_700),
            ) to "pocket/memo.state",
        )
        for ((frame, tag) in frames) {
            assertEquals(frame, roundTrip(frame))
            assertTrue("\"t\":\"$tag\"" in bodyJson(frame), tag)
        }
    }

    @Test
    fun a_state_with_only_the_required_fields_decodes_with_unmeasured_metrics() {
        val json = """{"id":"1","ts":0,"body":{"t":"pocket/memo.state","memoId":"$memoId","attemptId":"$attemptId","revision":0,"stage":"unknown"}}"""
        val state = PocketJson.decodeFromString<Envelope>(json).body as VoiceMemoState
        assertNull(state.result)
        assertNull(state.metrics.transcribeMs, "unmeasured must read as null, never 0")
        assertFalse(state.retryable)
    }

    @Test
    fun an_unknown_stage_decodes_and_is_reported_as_unsupported() {
        val json = """{"id":"1","ts":0,"body":{"t":"pocket/memo.state","memoId":"$memoId","attemptId":"$attemptId","revision":2,"stage":"reviewing","later":1}}"""
        val state = PocketJson.decodeFromString<Envelope>(json).body as VoiceMemoState
        assertEquals("reviewing", state.stage)
        assertEquals(VoiceMemoError.UNSUPPORTED, VoiceMemoValidation.validateState(state))
    }

    @Test
    fun a_frame_missing_a_required_id_does_not_decode() {
        val json = """{"id":"1","ts":0,"body":{"t":"pocket/memo.get","memoId":"$memoId"}}"""
        assertFailsWith<Exception> { PocketJson.decodeFromString<Envelope>(json) }
    }

    @Test
    fun capability_fields_are_trailing_optionals_in_both_directions() {
        val caps = PocketJson.encodeToString(ClientCaps(supportsVoiceMemo = true))
        assertEquals(PreMemoClientCaps(), PocketJson.decodeFromString<PreMemoClientCaps>(caps))
        assertFalse(PocketJson.decodeFromString<ClientCaps>("{}").supportsVoiceMemo)

        val info = PocketJson.encodeToString(
            DaemonInfo(hostname = "mac", voiceMemoVersion = 1, voiceMemoAgents = agents, voiceMemoStatus = VoiceMemoStatus.READY),
        )
        assertEquals("mac", PocketJson.decodeFromString<PreMemoDaemonInfo>(info).hostname)
        val legacy = PocketJson.decodeFromString<DaemonInfo>("""{"hostname":"mac"}""")
        assertEquals(0, legacy.voiceMemoVersion)
        assertTrue(legacy.voiceMemoAgents.isEmpty())
        assertEquals(VoiceMemoStatus.UNKNOWN, legacy.voiceMemoStatus)
    }

    @Test
    fun a_valid_audio_start_and_a_valid_transcript_start_pass() {
        assertNull(VoiceMemoValidation.validateStart(audioStart(), agents))
        assertNull(VoiceMemoValidation.validateStart(transcriptStart(), agents))
    }

    @Test
    fun the_two_input_shapes_are_mutually_exclusive() {
        val invalid = VoiceMemoError.INVALID_INPUT
        assertEquals(invalid, VoiceMemoValidation.validateStart(audioStart().copy(transcript = "x"), agents))
        assertEquals(invalid, VoiceMemoValidation.validateStart(transcriptStart().copy(byteLength = 10), agents))
        assertEquals(invalid, VoiceMemoValidation.validateStart(transcriptStart().copy(chunkCount = 1), agents))
        assertEquals(invalid, VoiceMemoValidation.validateStart(transcriptStart().copy(mediaType = "audio/mp4"), agents))
        assertEquals(invalid, VoiceMemoValidation.validateStart(audioStart().copy(inputKind = "video"), agents))
    }

    @Test
    fun ids_hashes_models_and_agents_are_checked() {
        val invalid = VoiceMemoError.INVALID_INPUT
        assertEquals(invalid, VoiceMemoValidation.validateStart(audioStart().copy(memoId = "../etc"), agents))
        assertEquals(invalid, VoiceMemoValidation.validateStart(audioStart().copy(attemptId = memoId.uppercase()), agents))
        assertEquals(invalid, VoiceMemoValidation.validateStart(audioStart().copy(sha256 = "A".repeat(64)), agents))
        assertEquals(invalid, VoiceMemoValidation.validateStart(audioStart().copy(model = "sonnet"), agents))
        assertEquals(invalid, VoiceMemoValidation.validateStart(audioStart().copy(locale = "zh_CN!"), agents))
        assertEquals(VoiceMemoError.AGENT_UNAVAILABLE, VoiceMemoValidation.validateStart(audioStart().copy(agent = "codex"), agents))
        assertEquals(VoiceMemoError.AGENT_UNAVAILABLE, VoiceMemoValidation.validateStart(audioStart(), emptyList()))
    }

    @Test
    fun any_advertised_organiser_may_be_asked_for_and_none_means_transcribe_only() {
        val both = listOf(VOICE_MEMO_AGENT_CLAUDE, VOICE_MEMO_AGENT_CODEX)
        assertNull(VoiceMemoValidation.validateStart(audioStart().copy(agent = VOICE_MEMO_AGENT_CODEX), both))
        assertNull(VoiceMemoValidation.validateStart(audioStart().copy(agent = VOICE_MEMO_AGENT_NONE), emptyList()), "no organiser at all: still transcribed")
        assertEquals(
            VoiceMemoError.INVALID_INPUT,
            VoiceMemoValidation.validateStart(transcriptStart().copy(agent = VOICE_MEMO_AGENT_NONE), both),
            "re-submitting a transcript without an organiser does nothing",
        )
    }

    @Test
    fun a_transcribed_snapshot_needs_the_transcript_and_nothing_else() {
        val done = VoiceMemoState(memoId, attemptId, 2, VoiceMemoStage.TRANSCRIBED, transcript = "原文")
        assertNull(VoiceMemoValidation.validateState(done))
        assertNull(VoiceMemoValidation.validateState(done.copy(errorCode = VoiceMemoError.AGENT_UNAVAILABLE)), "the organiser went away: still a usable transcript")
        assertEquals(VoiceMemoError.INVALID_RESULT, VoiceMemoValidation.validateState(done.copy(transcript = " ")))
        assertTrue(VoiceMemoStage.TRANSCRIBED in VoiceMemoStage.terminal)
    }

    @Test
    fun audio_bounds_cover_duration_size_and_chunk_count() {
        val max = VoiceMemoLimits.MAX_AUDIO_BYTES
        assertNull(VoiceMemoValidation.validateStart(audioStart(bytes = max, duration = VoiceMemoLimits.MAX_AUDIO_DURATION_MS), agents))
        assertEquals(VoiceMemoLimits.MAX_CHUNKS, VoiceMemoLimits.chunkCountFor(max))
        assertEquals(VoiceMemoError.INVALID_INPUT, VoiceMemoValidation.validateStart(audioStart(bytes = max + 1), agents))
        assertEquals(VoiceMemoError.INVALID_INPUT, VoiceMemoValidation.validateStart(audioStart(bytes = 0), agents))
        assertEquals(VoiceMemoError.AUDIO_TOO_LONG, VoiceMemoValidation.validateStart(audioStart(duration = VoiceMemoLimits.MAX_AUDIO_DURATION_MS + 1), agents))
        assertEquals(VoiceMemoError.INVALID_INPUT, VoiceMemoValidation.validateStart(audioStart(duration = 0), agents))
        assertEquals(VoiceMemoError.INVALID_INPUT, VoiceMemoValidation.validateStart(audioStart().copy(chunkCount = 99), agents))
        assertEquals(VoiceMemoError.AUDIO_INVALID, VoiceMemoValidation.validateStart(audioStart().copy(mediaType = "audio/wav"), agents))
    }

    @Test
    fun chunk_sizes_are_exact() {
        val bytes = VoiceMemoLimits.CHUNK_BYTES * 2L + 5
        assertEquals(3, VoiceMemoLimits.chunkCountFor(bytes))
        assertEquals(VoiceMemoLimits.CHUNK_BYTES, VoiceMemoValidation.expectedChunkBytes(bytes, 0))
        assertEquals(5, VoiceMemoValidation.expectedChunkBytes(bytes, 2))
        assertNull(VoiceMemoValidation.expectedChunkBytes(bytes, 3))
        assertNull(VoiceMemoValidation.expectedChunkBytes(bytes, -1))
        assertEquals(VoiceMemoLimits.CHUNK_BYTES, VoiceMemoValidation.expectedChunkBytes(VoiceMemoLimits.CHUNK_BYTES.toLong(), 0))
    }

    @Test
    fun text_limits_count_code_points_not_utf16_units() {
        val emoji = "😀"
        assertEquals(2, emoji.length)
        assertEquals(1, VoiceMemoValidation.codePoints(emoji))
        val atLimit = emoji.repeat(VoiceMemoLimits.MAX_TODO_CODE_POINTS)
        assertNull(VoiceMemoValidation.validateTodoText(atLimit))
        assertEquals(VoiceMemoError.INVALID_RESULT, VoiceMemoValidation.validateTodoText(atLimit + "a"))
        assertEquals(VoiceMemoError.INVALID_RESULT, VoiceMemoValidation.validateTodoText("  "))
    }

    @Test
    fun a_transcript_is_bounded_by_code_points() {
        assertNull(VoiceMemoValidation.validateTranscript("a".repeat(VoiceMemoLimits.MAX_TRANSCRIPT_CODE_POINTS)))
        assertEquals(VoiceMemoError.TRANSCRIPT_TOO_LONG, VoiceMemoValidation.validateTranscript("a".repeat(VoiceMemoLimits.MAX_TRANSCRIPT_CODE_POINTS + 1)))
        // The widest legal transcript — 20,000 four-byte code points — is 80,000 bytes, inside the 80 KiB cap.
        assertNull(VoiceMemoValidation.validateTranscript("😀".repeat(VoiceMemoLimits.MAX_TRANSCRIPT_CODE_POINTS)))
        assertEquals(VoiceMemoError.TRANSCRIPT_TOO_LONG, VoiceMemoValidation.validateTranscript("😀".repeat(VoiceMemoLimits.MAX_TRANSCRIPT_CODE_POINTS + 1)))
        assertEquals(VoiceMemoError.EMPTY_TRANSCRIPT, VoiceMemoValidation.validateTranscript(" \n"))
    }

    @Test
    fun a_result_may_hold_no_todos_but_not_too_many_or_empty_ones() {
        assertNull(VoiceMemoValidation.validateResult(result(todos = 0)))
        assertNull(VoiceMemoValidation.validateResult(result(todos = VoiceMemoLimits.MAX_TODOS)))
        val invalid = VoiceMemoError.INVALID_RESULT
        assertEquals(invalid, VoiceMemoValidation.validateResult(result(todos = VoiceMemoLimits.MAX_TODOS + 1)))
        assertEquals(invalid, VoiceMemoValidation.validateResult(result().copy(title = "")))
        assertEquals(invalid, VoiceMemoValidation.validateResult(result().copy(title = "题".repeat(VoiceMemoLimits.MAX_TITLE_CODE_POINTS + 1))))
        assertEquals(invalid, VoiceMemoValidation.validateResult(result().copy(summary = " ")))
        assertEquals(invalid, VoiceMemoValidation.validateResult(result().copy(schemaVersion = 2)))
        assertEquals(invalid, VoiceMemoValidation.validateResult(result().copy(language = "zh CN")))
        assertEquals(invalid, VoiceMemoValidation.validateResult(result().copy(todos = listOf(VoiceMemoTodoSuggestion("")))))
        assertEquals(
            invalid,
            VoiceMemoValidation.validateResult(result().copy(todos = listOf(VoiceMemoTodoSuggestion("x", "线".repeat(VoiceMemoLimits.MAX_TARGET_HINT_CODE_POINTS + 1))))),
        )
    }

    @Test
    fun ready_and_degraded_snapshots_must_carry_what_the_phone_stores() {
        val ready = VoiceMemoState(memoId, attemptId, 3, VoiceMemoStage.READY, transcript = "原文", result = result())
        assertNull(VoiceMemoValidation.validateState(ready))
        assertEquals(VoiceMemoError.INVALID_RESULT, VoiceMemoValidation.validateState(ready.copy(result = null)))
        assertEquals(VoiceMemoError.INVALID_RESULT, VoiceMemoValidation.validateState(ready.copy(transcript = null)))
        val degraded = VoiceMemoState(memoId, attemptId, 3, VoiceMemoStage.DEGRADED, transcript = "原文", errorCode = VoiceMemoError.INVALID_RESULT)
        assertNull(VoiceMemoValidation.validateState(degraded))
        assertEquals(VoiceMemoError.INVALID_RESULT, VoiceMemoValidation.validateState(degraded.copy(errorCode = null)))
        assertNull(VoiceMemoValidation.validateState(VoiceMemoState(memoId, attemptId, 0, VoiceMemoStage.UNKNOWN)))
    }

    @Test
    fun a_result_missing_fields_still_decodes_and_is_reported_not_dropped() {
        val json = """{"id":"1","ts":0,"body":{"t":"pocket/memo.state","memoId":"$memoId","attemptId":"$attemptId","revision":3,"stage":"ready",""" +
            """"transcript":"原文","result":{"todos":[{"suggestedTarget":"README"}],"extra":{"nested":[1,2]}},"metrics":null}}"""
        val state = PocketJson.decodeFromString<Envelope>(json).body as VoiceMemoState
        assertEquals("原文", state.transcript, "the transcript survives a result this build cannot use")
        assertEquals(VoiceMemoError.INVALID_RESULT, VoiceMemoValidation.validateState(state))
        assertNull(state.metrics.transcribeMs)
    }

    @Test
    fun a_start_without_a_hash_decodes_and_is_refused() {
        val json = """{"id":"1","ts":0,"body":{"t":"pocket/memo.start","memoId":"$memoId","attemptId":"$attemptId","inputKind":"transcript","transcript":"x"}}"""
        val start = PocketJson.decodeFromString<Envelope>(json).body as VoiceMemoStart
        assertEquals(VoiceMemoError.INVALID_INPUT, VoiceMemoValidation.validateStart(start, agents))
    }

    @Test
    fun legacy_shaped_capability_frames_decode_through_the_envelope() {
        val caps = PocketJson.decodeFromString<Envelope>("""{"id":"1","ts":0,"body":{"t":"pocket/client.caps","supportsAgents":["opencode"]}}""").body as ClientCaps
        assertFalse(caps.supportsVoiceMemo)
        val info = PocketJson.decodeFromString<Envelope>("""{"id":"1","ts":0,"body":{"t":"pocket/daemon.info","hostname":"mac"}}""").body as DaemonInfo
        assertEquals(0, info.voiceMemoVersion)
    }

    @Test
    fun the_largest_legal_frames_stay_below_the_legacy_frame_cap() {
        val chunk = VoiceMemoAudio(memoId, attemptId, 63, "A".repeat(VoiceMemoLimits.MAX_CHUNK_BASE64_CHARS))
        assertTrue(bodyJson(chunk).encodeToByteArray().size < LEGACY_CLIENT_MAX_FRAME_BYTES / 2)
        val biggest = VoiceMemoState(
            memoId, attemptId, 9, VoiceMemoStage.READY,
            // four-byte code points: the widest a legal field can be on the wire
            transcript = "😀".repeat(VoiceMemoLimits.MAX_TRANSCRIPT_CODE_POINTS),
            result = VoiceMemoResult(
                title = "😀".repeat(VoiceMemoLimits.MAX_TITLE_CODE_POINTS),
                summary = "😀".repeat(VoiceMemoLimits.MAX_SUMMARY_CODE_POINTS),
                todos = List(VoiceMemoLimits.MAX_TODOS) {
                    VoiceMemoTodoSuggestion("😀".repeat(VoiceMemoLimits.MAX_TODO_CODE_POINTS), "😀".repeat(VoiceMemoLimits.MAX_TARGET_HINT_CODE_POINTS))
                },
                language = "zh",
            ),
        )
        assertNull(VoiceMemoValidation.validateState(biggest))
        assertTrue(bodyJson(biggest).encodeToByteArray().size < LEGACY_CLIENT_MAX_FRAME_BYTES / 2)
    }

    @Test
    fun generated_ids_are_canonical_and_the_hash_matches_a_known_vector() {
        repeat(50) { assertTrue(VoiceMemoValidation.isId(VoiceMemoIds.newId(Random(it)))) }
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            VoiceMemoHash.sha256Hex("abc".encodeToByteArray()),
        )
    }
}
