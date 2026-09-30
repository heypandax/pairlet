package dev.ccpocket.daemon.memo

import dev.ccpocket.daemon.transcribe.WhisperTranscriber
import dev.ccpocket.protocol.VoiceMemoError
import dev.ccpocket.protocol.VoiceMemoLimits
import dev.ccpocket.protocol.VoiceMemoStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MemoCliTranscriberTest {

    private val root: Path = Files.createTempDirectory("ccp-memo-tr")
    private val whisperBin: Path = root.resolve("whisper-cli")
    private val model: Path = root.resolve("ggml-large-v3-turbo.bin")

    /** Deterministic monotonic clock the fake processes can move forward. */
    private var nowNanos = 1_000_000_000L

    private class Call(val spec: MemoProcessSpec)

    /** Plays converter then whisper; each step decides what files it leaves behind. */
    private inner class FakeRunner(
        val convert: suspend (MemoProcessSpec) -> MemoProcessResult = { spec -> writeWav(Path.of(spec.argv.last()), 16_000L * 5); exited(0) },
        val whisper: suspend (MemoProcessSpec) -> MemoProcessResult = { spec -> writeOut(spec, "检查 mobile build\n"); exited(0) },
    ) : MemoProcessExecutor {
        val calls = ArrayList<Call>()
        override suspend fun run(spec: MemoProcessSpec): MemoProcessResult {
            calls += Call(spec)
            return if (spec.argv.first() == "conv") convert(spec) else whisper(spec)
        }
    }

    private fun exited(code: Int) = MemoProcessResult.Exited(code, ByteArray(0), "stderr noise".encodeToByteArray(), false, false)

    private fun writeOut(spec: MemoProcessSpec, text: String) {
        val base = spec.argv[spec.argv.indexOf("-of") + 1]
        Path.of("$base.txt").writeText(text)
    }

    private fun transcriber(
        runner: MemoProcessExecutor,
        whisper: Path? = whisperBin,
        mdl: Path? = model,
        converter: Boolean = true,
    ) = MemoCliTranscriber(
        runner = runner,
        resolveWhisper = { whisper },
        resolveModel = { mdl },
        convertArgs = { src, wav -> if (converter) listOf("conv", src.toString(), wav.toString()) else null },
        language = { "auto" },
        tempRoot = root,
    )

    private fun input() = MemoAudio(listOf(byteArrayOf(1, 2, 3), byteArrayOf(4)), 4, VoiceMemoLimits.AUDIO_MEDIA_TYPE)

    private fun deadline(ms: Long = VoiceMemoLimits.TRANSCRIBE_DEADLINE_MS) = MemoDeadline(nowNanos + ms * 1_000_000) { nowNanos }

    private fun jobDirs() = Files.list(root).use { s -> s.filter { it.fileName.toString().startsWith("ccp-memo") }.toList() }

    @Test
    fun happy_path_uses_the_fixed_memo_prompt_and_no_project_context() = runBlocking {
        val runner = FakeRunner()
        val r = assertIs<MemoTranscribeResult.Ok>(transcriber(runner).transcribe(input(), deadline()))
        assertEquals("检查 mobile build", r.transcript)
        assertEquals(5_000, r.audioDurationMs)
        assertEquals(2, runner.calls.size)
        val argv = runner.calls[1].spec.argv
        assertEquals(whisperBin.toString(), argv[0])
        assertEquals(MemoCliTranscriber.INITIAL_PROMPT, argv[argv.indexOf("--prompt") + 1])
        assertEquals("以下是中英混合备忘，英文单词保留英文原文，不音译，例如：检查 mobile build，并补充 README。", MemoCliTranscriber.INITIAL_PROMPT)
        // whisper argv is exactly WhisperTranscriber.buildArgs — reused, not re-invented
        val wav = Path.of(runner.calls[0].spec.argv.last())
        val outBase = Path.of(argv[argv.indexOf("-of") + 1])
        assertEquals(WhisperTranscriber.buildArgs(model, wav, outBase, MemoCliTranscriber.INITIAL_PROMPT, "auto"), argv.drop(1))
        // both processes ran in the job's own temp dir, never a project dir, with no stdin text
        runner.calls.forEach {
            assertTrue(it.spec.cwd!!.fileName.toString().startsWith("ccp-memo"))
            assertEquals(null, it.spec.stdin)
        }
        assertTrue(jobDirs().isEmpty(), "temp dir must be deleted")
    }

    @Test
    fun audio_is_written_to_an_owner_only_file_before_conversion() = runBlocking {
        var seen: ByteArray? = null
        var perms: String? = null
        val runner = FakeRunner(convert = { spec ->
            val src = Path.of(spec.argv[1])
            seen = Files.readAllBytes(src)
            perms = runCatching { java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(spec.cwd!!)) }.getOrNull()
            writeWav(Path.of(spec.argv.last()), 16_000); exited(0)
        })
        transcriber(runner).transcribe(input(), deadline())
        assertEquals(listOf<Byte>(1, 2, 3, 4), seen!!.toList())
        if (perms != null) assertEquals("rwx------", perms)
    }

    @Test
    fun each_process_gets_the_remaining_budget_not_its_own() = runBlocking {
        val runner = FakeRunner(convert = { spec ->
            nowNanos += 50_000L * 1_000_000 // conversion took 50 s
            writeWav(Path.of(spec.argv.last()), 16_000); exited(0)
        })
        transcriber(runner).transcribe(input(), deadline(180_000))
        assertEquals(180_000, runner.calls[0].spec.deadlineMs)
        assertEquals(130_000, runner.calls[1].spec.deadlineMs)
    }

    @Test
    fun real_wav_duration_over_the_limit_is_audio_too_long_and_skips_whisper() = runBlocking {
        val frames = (VoiceMemoLimits.MAX_AUDIO_DURATION_MS + 1_000) * 16
        val runner = FakeRunner(convert = { spec -> writeWav(Path.of(spec.argv.last()), frames); exited(0) })
        val r = assertIs<MemoTranscribeResult.Failed>(transcriber(runner).transcribe(input(), deadline()))
        assertEquals(VoiceMemoError.AUDIO_TOO_LONG, r.errorCode)
        assertEquals(1, runner.calls.size)
    }

    @Test
    fun wrong_wav_shape_is_audio_invalid() = runBlocking {
        for (bad in listOf<(Path) -> Unit>(
            { writeWav(it, 16_000, rate = 44_100) },
            { writeWav(it, 16_000, channels = 2) },
            { writeWav(it, 16_000, bits = 8) },
            { it.writeBytes("not a wav at all".encodeToByteArray()) },
            { writeWav(it, 0) },
            { },
        )) {
            val runner = FakeRunner(convert = { spec -> bad(Path.of(spec.argv.last())); exited(0) })
            val r = assertIs<MemoTranscribeResult.Failed>(transcriber(runner).transcribe(input(), deadline()))
            assertEquals(VoiceMemoError.AUDIO_INVALID, r.errorCode)
            assertEquals(1, runner.calls.size)
        }
    }

    @Test
    fun converter_failures_map_to_fixed_codes() = runBlocking {
        suspend fun code(conv: MemoProcessResult) =
            (transcriber(FakeRunner(convert = { conv })).transcribe(input(), deadline()) as MemoTranscribeResult.Failed).errorCode
        assertEquals(VoiceMemoError.AUDIO_INVALID, code(exited(1)))
        assertEquals(VoiceMemoError.TRANSCRIBE_TIMEOUT, code(MemoProcessResult.TimedOut))
        assertEquals(VoiceMemoError.AUDIO_INVALID, code(MemoProcessResult.Aborted))
        assertEquals(VoiceMemoError.TRANSCRIBE_FAILED, code(MemoProcessResult.StartFailed))
    }

    @Test
    fun converter_output_is_capped_while_it_runs() = runBlocking {
        var predicate: (() -> Boolean)? = null
        val runner = FakeRunner(convert = { spec ->
            predicate = spec.abortWhen
            writeWav(Path.of(spec.argv.last()), 16_000); exited(0)
        })
        transcriber(runner).transcribe(input(), deadline())
        // the cap is a legal recording's PCM plus bounded overhead
        assertEquals(VoiceMemoLimits.MAX_AUDIO_DURATION_MS * 32 + 64 * 1024, MemoCliTranscriber.MAX_WAV_BYTES)
        assertFalse(predicate!!.invoke()) // the file is gone by now → not over
    }

    @Test
    fun whisper_failures_map_to_fixed_codes() = runBlocking {
        suspend fun code(w: suspend (MemoProcessSpec) -> MemoProcessResult) =
            (transcriber(FakeRunner(whisper = w)).transcribe(input(), deadline()) as MemoTranscribeResult.Failed)
        assertEquals(VoiceMemoError.TRANSCRIBE_FAILED, code { exited(2) }.errorCode)
        assertEquals(VoiceMemoError.TRANSCRIBE_TIMEOUT, code { MemoProcessResult.TimedOut }.errorCode)
        assertEquals(VoiceMemoError.TRANSCRIBE_FAILED, code { exited(0) }.errorCode) // no out.txt
        assertEquals(5_000, code { exited(2) }.audioDurationMs)
    }

    @Test
    fun blank_or_marker_only_transcript_is_empty_transcript() = runBlocking {
        for (text in listOf("", "  \n", "[BLANK_AUDIO]\n(wind)\n")) {
            val r = transcriber(FakeRunner(whisper = { spec -> writeOut(spec, text); exited(0) }))
                .transcribe(input(), deadline())
            assertEquals(VoiceMemoError.EMPTY_TRANSCRIPT, assertIs<MemoTranscribeResult.Failed>(r).errorCode)
        }
    }

    @Test
    fun oversized_clean_transcript_is_transcript_too_long() = runBlocking {
        val long = "字".repeat(VoiceMemoLimits.MAX_TRANSCRIPT_CODE_POINTS + 1)
        val r = transcriber(FakeRunner(whisper = { spec -> writeOut(spec, long); exited(0) })).transcribe(input(), deadline())
        assertEquals(VoiceMemoError.TRANSCRIPT_TOO_LONG, assertIs<MemoTranscribeResult.Failed>(r).errorCode)
    }

    @Test
    fun missing_prerequisites_report_status_and_not_ready() = runBlocking {
        val runner = FakeRunner()
        assertEquals(VoiceMemoStatus.READY, transcriber(runner).localStatus())
        assertEquals(VoiceMemoStatus.WHISPER_MISSING, transcriber(runner, whisper = null).localStatus())
        assertEquals(VoiceMemoStatus.MODEL_MISSING, transcriber(runner, mdl = null).localStatus())
        assertEquals(VoiceMemoStatus.CONVERTER_MISSING, transcriber(runner, converter = false).localStatus())
        for (t in listOf(transcriber(runner, whisper = null), transcriber(runner, mdl = null), transcriber(runner, converter = false))) {
            val r = assertIs<MemoTranscribeResult.Failed>(t.transcribe(input(), deadline()))
            assertEquals(VoiceMemoError.NOT_READY, r.errorCode)
        }
        assertTrue(runner.calls.isEmpty())
        assertTrue(jobDirs().isEmpty())
    }

    @Test
    fun cancellation_propagates_and_cleans_up() = runBlocking {
        val inWhisper = CompletableDeferred<Unit>()
        val runner = FakeRunner(whisper = { inWhisper.complete(Unit); awaitCancellation() })
        val job = async(Dispatchers.Default) { transcriber(runner).transcribe(input(), deadline()) }
        withTimeout(5_000) { inWhisper.await() }
        job.cancel()
        assertFailsWith<CancellationException> { job.await() }
        assertTrue(jobDirs().isEmpty(), "temp dir must be deleted on cancel")
    }

    @Test
    fun never_touches_the_chat_whisper_server() {
        // the resident server is the chat's; memos must not route through it nor flip its global switch
        val src = Path.of("src/main/kotlin/dev/ccpocket/daemon/memo/MemoCliTranscriber.kt")
        if (!src.exists()) return
        val code = src.readText().lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }.joinToString("\n")
        assertFalse(code.contains("WhisperServer"))
        assertFalse(code.contains("CC_POCKET_WHISPER_SERVER"))
        assertFalse(code.contains("WhisperTranscriber.transcribe("))
        assertFalse(code.contains("buildPrompt("))
    }

    companion object {
        fun writeWav(path: Path, frames: Long, rate: Int = 16_000, channels: Int = 1, bits: Int = 16) {
            val blockAlign = channels * bits / 8
            val dataLen = frames * blockAlign
            val header = ByteBuffer.allocate(44 + 12).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".encodeToByteArray()).putInt((36 + 12 + dataLen).toInt()).put("WAVE".encodeToByteArray())
            header.put("fmt ".encodeToByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
                .putInt(rate).putInt(rate * blockAlign).putShort(blockAlign.toShort()).putShort(bits.toShort())
            // an extra chunk before data, like afconvert's FLLR / ffmpeg's LIST
            header.put("LIST".encodeToByteArray()).putInt(4).put("INFO".encodeToByteArray())
            header.put("data".encodeToByteArray()).putInt(dataLen.toInt())
            Files.newOutputStream(path).use { out ->
                out.write(header.array(), 0, header.position())
                val zeros = ByteArray(64 * 1024)
                var left = dataLen
                while (left > 0) {
                    val n = minOf(left, zeros.size.toLong()).toInt()
                    out.write(zeros, 0, n); left -= n
                }
            }
        }
    }
}
