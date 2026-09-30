package dev.ccpocket.daemon.memo

import dev.ccpocket.protocol.VOICE_MEMO_AGENT_CLAUDE
import dev.ccpocket.protocol.VoiceMemoAudio
import dev.ccpocket.protocol.VoiceMemoHash
import dev.ccpocket.protocol.VoiceMemoIds
import dev.ccpocket.protocol.VoiceMemoInputKind
import dev.ccpocket.protocol.VoiceMemoLimits
import dev.ccpocket.protocol.VoiceMemoResult
import dev.ccpocket.protocol.VoiceMemoStart
import dev.ccpocket.protocol.VoiceMemoState
import dev.ccpocket.protocol.VoiceMemoStatus
import dev.ccpocket.protocol.VoiceMemoTodoSuggestion
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class FakeMemoClock(var wall: Long = 1_700_000_000_000L, var nanos: Long = 5_000_000_000L) : MemoClock {
    override fun wallMs(): Long = synchronized(this) { wall }
    override fun monoNanos(): Long = synchronized(this) { nanos }

    fun advance(ms: Long) = synchronized(this) {
        wall += ms
        nanos += ms * 1_000_000
    }
}

class FakeTranscriber(
    @Volatile var status: String = VoiceMemoStatus.READY,
    @Volatile var behavior: suspend (MemoAudio, MemoDeadline) -> MemoTranscribeResult = { _, _ ->
        MemoTranscribeResult.Ok(TRANSCRIPT, 5_000)
    },
) : MemoTranscriber {
    val calls = AtomicInteger()

    override fun localStatus(): String = status

    override suspend fun transcribe(input: MemoAudio, deadline: MemoDeadline): MemoTranscribeResult {
        calls.incrementAndGet()
        return behavior(input, deadline)
    }

    companion object {
        const val TRANSCRIPT = "检查 mobile build，并补充 README"
    }
}

class FakeSummarizer(
    @Volatile var available: Boolean = true,
    @Volatile var behavior: suspend (String, String?) -> MemoSummaryResult = { _, _ -> MemoSummaryResult.Ok(RESULT) },
    override val agent: String = VOICE_MEMO_AGENT_CLAUDE,
) : MemoSummarizer {
    val calls = AtomicInteger()
    val availabilityChecks = AtomicInteger()

    /** Overrides [available] when set — e.g. "launchable at the start frame, gone by the time the worker asks". */
    @Volatile var availability: ((check: Int) -> Boolean)? = null

    override fun isAvailable(): Boolean {
        val n = availabilityChecks.incrementAndGet()
        return availability?.invoke(n) ?: available
    }

    override suspend fun summarize(transcript: String, locale: String?, deadline: MemoDeadline): MemoSummaryResult {
        calls.incrementAndGet()
        return behavior(transcript, locale)
    }

    companion object {
        val RESULT = VoiceMemoResult(
            title = "构建与文档",
            summary = "两件事。",
            todos = listOf(VoiceMemoTodoSuggestion("检查 mobile build"), VoiceMemoTodoSuggestion("补充 README")),
            language = "zh",
        )
    }
}

/** Records every frame; [accept] = what the fake transport reports back. */
class Inbox(@Volatile var accept: Boolean = true, @Volatile var throwOnSend: Boolean = false) : MemoReplyTarget {
    val states = CopyOnWriteArrayList<VoiceMemoState>()

    override suspend fun send(state: VoiceMemoState): Boolean {
        states += state
        if (throwOnSend) throw IllegalStateException("socket closed")
        return accept
    }

    fun last(): VoiceMemoState = states.last()

    suspend fun await(timeoutMs: Long = 5_000, match: (VoiceMemoState) -> Boolean): VoiceMemoState =
        withTimeout(timeoutMs) {
            var found: VoiceMemoState? = states.firstOrNull(match)
            while (found == null) {
                delay(10)
                found = states.firstOrNull(match)
            }
            found
        }

    suspend fun awaitStage(stage: String, timeoutMs: Long = 5_000) = await(timeoutMs) { it.stage == stage }
}

/** A recording split into wire frames. */
class MemoUpload(val bytes: ByteArray, val memoId: String = VoiceMemoIds.newId(), val attemptId: String = VoiceMemoIds.newId()) {
    val sha = VoiceMemoHash.sha256Hex(bytes)
    val chunkCount = VoiceMemoLimits.chunkCountFor(bytes.size.toLong())

    fun start(
        durationMs: Long = 5_000,
        sha256: String = sha,
        locale: String? = null,
        agent: String = VOICE_MEMO_AGENT_CLAUDE,
    ) = VoiceMemoStart(
        memoId = memoId,
        attemptId = attemptId,
        inputKind = VoiceMemoInputKind.AUDIO,
        agent = agent,
        mediaType = VoiceMemoLimits.AUDIO_MEDIA_TYPE,
        durationMs = durationMs,
        byteLength = bytes.size.toLong(),
        sha256 = sha256,
        chunkCount = chunkCount,
        locale = locale,
    )

    fun chunk(i: Int): VoiceMemoAudio {
        val from = i * VoiceMemoLimits.CHUNK_BYTES
        val part = bytes.copyOfRange(from, minOf(from + VoiceMemoLimits.CHUNK_BYTES, bytes.size))
        return VoiceMemoAudio(memoId, attemptId, i, Base64.getEncoder().encodeToString(part))
    }

    fun chunks() = (0 until chunkCount).map { chunk(it) }

    companion object {
        fun random(size: Int = VoiceMemoLimits.CHUNK_BYTES * 2 + 17, seed: Int = 7) = MemoUpload(Random(seed).nextBytes(size))
    }
}

fun transcriptStart(
    text: String = FakeTranscriber.TRANSCRIPT,
    memoId: String = VoiceMemoIds.newId(),
    attemptId: String = VoiceMemoIds.newId(),
    sha256: String = VoiceMemoHash.sha256Hex(text.encodeToByteArray()),
    locale: String? = "zh",
    agent: String = VOICE_MEMO_AGENT_CLAUDE,
) = VoiceMemoStart(
    memoId = memoId,
    attemptId = attemptId,
    inputKind = VoiceMemoInputKind.TRANSCRIPT,
    agent = agent,
    sha256 = sha256,
    transcript = text,
    locale = locale,
)
