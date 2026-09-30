package dev.ccpocket.daemon.memo

import dev.ccpocket.daemon.transcribe.WhisperTranscriber
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.VoiceMemoError
import dev.ccpocket.protocol.VoiceMemoLimits
import dev.ccpocket.protocol.VoiceMemoStatus
import dev.ccpocket.protocol.VoiceMemoValidation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile

interface MemoTranscriber {
    /** LOCAL prerequisites only (binary / model / converter present) as a [VoiceMemoStatus] word. */
    fun localStatus(): String

    suspend fun transcribe(input: MemoAudio, deadline: MemoDeadline): MemoTranscribeResult
}

sealed interface MemoTranscribeResult {
    data class Ok(val transcript: String, val audioDurationMs: Long) : MemoTranscribeResult
    data class Failed(val errorCode: String, val audioDurationMs: Long? = null) : MemoTranscribeResult
}

/**
 * Memo transcription over a ONE-SHOT whisper-cli (code design §6.3) — never the chat's resident
 * [dev.ccpocket.daemon.transcribe.WhisperServer], and no global env switch, so a long memo can't change how
 * chat dictation behaves. Discovery, conversion argv, whisper argv and transcript cleaning are reused from
 * [WhisperTranscriber]; everything that launches a process goes through the injected [runner].
 *
 * Pipeline: owner-only temp dir → audio file → 16 kHz mono 16-bit WAV → header/frame check (the REAL
 * duration, not the phone's claim) → whisper-cli → clean → validate → temp dir deleted in `finally`.
 * One deadline covers all of it; each process gets what is left. Privacy: no audio, transcript or tool
 * output is logged — only stages, timings, sizes and exit codes.
 */
class MemoCliTranscriber(
    private val runner: MemoProcessExecutor = MemoProcessRunner(),
    private val resolveWhisper: () -> Path? = { WhisperTranscriber.resolveWhisper() },
    private val resolveModel: () -> Path? = { WhisperTranscriber.resolveModel() },
    private val convertArgs: (Path, Path) -> List<String>? = { src, wav -> WhisperTranscriber.convertArgs(src, wav) },
    private val language: () -> String = {
        System.getenv("CC_POCKET_WHISPER_LANG")?.trim()?.takeIf { it.isNotEmpty() } ?: "auto"
    },
    /** Parent of the per-job temp dirs; null = the system temp dir. */
    private val tempRoot: Path? = null,
) : MemoTranscriber {
    private val log = logger("MemoTranscribe")

    override fun localStatus(): String = runCatching {
        when {
            resolveWhisper() == null -> VoiceMemoStatus.WHISPER_MISSING
            resolveModel() == null -> VoiceMemoStatus.MODEL_MISSING
            convertArgs(PROBE_PATH, PROBE_PATH) == null -> VoiceMemoStatus.CONVERTER_MISSING
            else -> VoiceMemoStatus.READY
        }
    }.getOrDefault(VoiceMemoStatus.UNKNOWN)

    override suspend fun transcribe(input: MemoAudio, deadline: MemoDeadline): MemoTranscribeResult =
        withContext(Dispatchers.IO) {
            val whisper = resolveWhisper() ?: return@withContext fail(VoiceMemoError.NOT_READY, "whisper")
            val model = resolveModel() ?: return@withContext fail(VoiceMemoError.NOT_READY, "model")
            val tmp = createPrivateDir()
            try {
                val src = tmp.resolve("in.m4a")
                writePrivate(src, input)
                input.release()
                val wav = tmp.resolve("in.wav")
                val conv = convertArgs(src, wav) ?: return@withContext fail(VoiceMemoError.NOT_READY, "converter")

                val convRun = runner.run(
                    MemoProcessSpec(
                        argv = conv,
                        cwd = tmp,
                        stdoutLimit = TOOL_OUTPUT_LIMIT,
                        stderrLimit = TOOL_OUTPUT_LIMIT,
                        deadlineMs = deadline.remainingMs(),
                        // a tiny file that decodes into hours of PCM is stopped while it grows, not after
                        abortWhen = { runCatching { wav.exists() && wav.fileSize() > MAX_WAV_BYTES }.getOrDefault(false) },
                    ),
                )
                when (convRun) {
                    MemoProcessResult.TimedOut -> return@withContext fail(VoiceMemoError.TRANSCRIBE_TIMEOUT, "convert")
                    MemoProcessResult.StartFailed -> return@withContext fail(VoiceMemoError.TRANSCRIBE_FAILED, "convert-start")
                    MemoProcessResult.Aborted -> return@withContext fail(VoiceMemoError.AUDIO_INVALID, "convert-oversize")
                    is MemoProcessResult.Exited -> if (convRun.exitCode != 0) {
                        log.info("memo convert exit=${convRun.exitCode}")
                        return@withContext fail(VoiceMemoError.AUDIO_INVALID, "convert")
                    }
                }

                val durationMs = when (val probe = probeWav(wav)) {
                    is WavProbe.Invalid -> return@withContext fail(VoiceMemoError.AUDIO_INVALID, "wav")
                    is WavProbe.Pcm -> probe.durationMs
                }
                if (durationMs > VoiceMemoLimits.MAX_AUDIO_DURATION_MS) {
                    return@withContext fail(VoiceMemoError.AUDIO_TOO_LONG, "duration", durationMs)
                }

                val outBase = tmp.resolve("out")
                val argv = buildList {
                    add(whisper.toString())
                    addAll(WhisperTranscriber.buildArgs(model, wav, outBase, INITIAL_PROMPT, language()))
                }
                val t0 = System.nanoTime()
                val run = runner.run(
                    MemoProcessSpec(
                        argv = argv,
                        cwd = tmp,
                        stdoutLimit = TOOL_OUTPUT_LIMIT,
                        stderrLimit = TOOL_OUTPUT_LIMIT,
                        deadlineMs = deadline.remainingMs(),
                    ),
                )
                val elapsed = (System.nanoTime() - t0) / 1_000_000
                when (run) {
                    MemoProcessResult.TimedOut -> return@withContext fail(VoiceMemoError.TRANSCRIBE_TIMEOUT, "whisper", durationMs)
                    MemoProcessResult.StartFailed, MemoProcessResult.Aborted ->
                        return@withContext fail(VoiceMemoError.TRANSCRIBE_FAILED, "whisper-start", durationMs)
                    is MemoProcessResult.Exited -> {
                        log.info("memo whisper exit=${run.exitCode} in ${elapsed}ms audio=${durationMs}ms")
                        if (run.exitCode != 0) return@withContext fail(VoiceMemoError.TRANSCRIBE_FAILED, "whisper", durationMs)
                    }
                }

                val txt = tmp.resolve("out.txt")
                if (!txt.isRegularFile()) return@withContext fail(VoiceMemoError.TRANSCRIBE_FAILED, "no-output", durationMs)
                if (txt.fileSize() > MAX_RAW_TRANSCRIPT_BYTES) {
                    return@withContext fail(VoiceMemoError.TRANSCRIPT_TOO_LONG, "raw-size", durationMs)
                }
                val text = WhisperTranscriber.cleanTranscript(Files.readString(txt, Charsets.UTF_8))
                if (text.isBlank()) return@withContext fail(VoiceMemoError.EMPTY_TRANSCRIPT, "empty", durationMs)
                VoiceMemoValidation.validateTranscript(text)?.let { return@withContext fail(it, "validate", durationMs) }
                MemoTranscribeResult.Ok(text, durationMs)
            } finally {
                input.release()
                runCatching { tmp.toFile().deleteRecursively() }
            }
        }

    private fun fail(code: String, stage: String, durationMs: Long? = null): MemoTranscribeResult.Failed {
        log.info("memo transcribe failed stage=$stage code=$code")
        return MemoTranscribeResult.Failed(code, durationMs)
    }

    private fun createPrivateDir(): Path {
        val root = tempRoot
        return if (POSIX) {
            val attr = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
            if (root != null) Files.createTempDirectory(root, "ccp-memo", attr) else Files.createTempDirectory("ccp-memo", attr)
        } else {
            if (root != null) Files.createTempDirectory(root, "ccp-memo") else Files.createTempDirectory("ccp-memo")
        }
    }

    private fun writePrivate(path: Path, audio: MemoAudio) {
        if (POSIX) Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
            .buffered().use { audio.writeTo(it) }
    }

    sealed interface WavProbe {
        data class Pcm(val frames: Long, val durationMs: Long) : WavProbe
        data object Invalid : WavProbe
    }

    companion object {
        /** Whisper's initial prompt for memos: generic, no project names, no files read (overall design §10.2). */
        const val INITIAL_PROMPT = "以下是中英混合备忘，英文单词保留英文原文，不音译，例如：检查 mobile build，并补充 README。"

        const val SAMPLE_RATE = 16_000
        private const val TOOL_OUTPUT_LIMIT = 16 * 1024
        /** The PCM a legal recording can produce, plus bounded container overhead (headers, LIST/FLLR chunks). */
        val MAX_WAV_BYTES: Long = VoiceMemoLimits.MAX_AUDIO_DURATION_MS * SAMPLE_RATE / 1000 * 2 + 64 * 1024
        /** whisper's raw text before cleaning; anything this large can't clean down to a legal transcript
         *  except through hallucination loops, which are not worth summarising either. */
        private const val MAX_RAW_TRANSCRIPT_BYTES = 1024L * 1024
        private val PROBE_PATH: Path = Path.of("probe")
        private val POSIX = runCatching {
            java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
        }.getOrDefault(false)

        /**
         * Check [wav] is what the converter was told to produce — RIFF/WAVE, PCM (plain or EXTENSIBLE), 16 kHz,
         * mono, 16-bit — and read its duration from the data chunk. Anything else is [WavProbe.Invalid]: a
         * converter that silently produced another shape can't be trusted for the length check either.
         */
        fun probeWav(wav: Path): WavProbe = runCatching {
            RandomAccessFile(wav.toFile(), "r").use { f ->
                val size = f.length()
                if (size < 12) return WavProbe.Invalid
                val head = ByteArray(12).also { f.readFully(it) }
                if (String(head, 0, 4, Charsets.US_ASCII) != "RIFF" || String(head, 8, 4, Charsets.US_ASCII) != "WAVE") {
                    return WavProbe.Invalid
                }
                var pos = 12L
                var fmtOk = false
                while (pos + 8 <= size) {
                    f.seek(pos)
                    val ch = ByteArray(8).also { f.readFully(it) }
                    val id = String(ch, 0, 4, Charsets.US_ASCII)
                    val len = ByteBuffer.wrap(ch, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
                    val body = pos + 8
                    when (id) {
                        "fmt " -> {
                            if (len < 16 || body + len > size) return WavProbe.Invalid
                            val fmt = ByteArray(minOf(len, 40L).toInt()).also { f.seek(body); f.readFully(it) }
                            val b = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                            val format = b.getShort(0).toInt() and 0xffff
                            val channels = b.getShort(2).toInt() and 0xffff
                            val rate = b.getInt(4)
                            val blockAlign = b.getShort(12).toInt() and 0xffff
                            val bits = b.getShort(14).toInt() and 0xffff
                            val pcm = when (format) {
                                1 -> true
                                0xFFFE -> fmt.size >= 26 && (b.getShort(24).toInt() and 0xffff) == 1
                                else -> false
                            }
                            if (!pcm || channels != 1 || rate != SAMPLE_RATE || bits != 16 || blockAlign != 2) return WavProbe.Invalid
                            fmtOk = true
                        }
                        "data" -> {
                            if (!fmtOk || body + len > size || len == 0L || len % 2 != 0L) return WavProbe.Invalid
                            val frames = len / 2
                            return WavProbe.Pcm(frames, frames * 1000 / SAMPLE_RATE)
                        }
                    }
                    pos = body + len + (len and 1L) // chunks are word-aligned
                }
                WavProbe.Invalid
            }
        }.getOrDefault(WavProbe.Invalid)
    }
}
