package dev.ccpocket.daemon.transcribe

import dev.ccpocket.daemon.util.logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.isDirectory
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Runs whisper.cpp over a finished voice capture: m4a → (afconvert) → 16 kHz wav → whisper-cli → text.
 * Discovery mirrors [dev.ccpocket.daemon.claude.ClaudeLauncher]; process lifetime mirrors ClaudeProcess
 * (hard timeout, descendants reaped). Privacy: transcripts and audio are never logged — only durations
 * and exit codes; all temp files are deleted in `finally`.
 */
object WhisperTranscriber {

    sealed interface TranscribeResult {
        data class Ok(val text: String) : TranscribeResult
        data class Err(val code: String, val userMessage: String) : TranscribeResult
    }

    const val MSG_INSTALL = "whisper-cli not found — install it on the computer: brew install whisper-cpp"
    val MSG_MODEL = "whisper model missing — run on the computer:\n" +
        "mkdir -p ~/.cache/cc-pocket/models && curl -L " +
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin " +
        "-o ~/.cache/cc-pocket/models/ggml-small.bin"

    private val log = logger("Whisper")

    // brew's whisper-cpp formula installs `whisper-cli` (older releases shipped `whisper-cpp`)
    private val binNames = listOf("whisper-cli", "whisper-cpp")
    private val fallbackDirs = listOf("/opt/homebrew/bin", "/usr/local/bin")

    /** Locate the whisper binary; null = not installed (a user-facing soft error, not a crash). */
    fun resolveWhisper(explicit: String? = System.getenv("CC_POCKET_WHISPER_BIN")): Path? {
        val candidates = LinkedHashSet<Path>()
        explicit?.let { candidates.add(Path.of(it)) }
        System.getenv("PATH")?.split(File.pathSeparator)?.forEach { dir ->
            if (dir.isNotBlank()) binNames.forEach { candidates.add(Path.of(dir, it)) }
        }
        fallbackDirs.forEach { dir -> binNames.forEach { candidates.add(Path.of(dir, it)) } }
        return candidates.firstOrNull {
            runCatching { it.isRegularFile() && it.isExecutable() }.getOrDefault(false)
        }
    }

    /** Find a ggml model: prefer small, then any; `~/.cache/cc-pocket/models` first, then whisper's own cache. */
    fun resolveModel(home: Path = Path.of(System.getProperty("user.home"))): Path? {
        val dirs = listOf(home.resolve(".cache/cc-pocket/models"), home.resolve(".cache/whisper"))
        val models = dirs.filter { it.isDirectory() }.flatMap { dir ->
            runCatching { dir.listDirectoryEntries("ggml-*.bin") }.getOrDefault(emptyList())
        }
        return models.firstOrNull { it.name == "ggml-small.bin" } ?: models.firstOrNull()
    }

    /**
     * Whisper initial prompt: project terms the daemon knows (dir name, git branch, top-level names)
     * so spoken identifiers like "ClaudeProcess" transcribe correctly. Capped — it's a bias, not a prompt.
     *
     * The terms are wrapped in a fully-punctuated Chinese sentence, not emitted as a bare list:
     * whisper mimics the prompt's style, and an unpunctuated prompt yields unpunctuated transcripts
     * (worst for Chinese with the small model). The mixed zh/EN sentence also matches how users
     * actually dictate (Chinese speech with embedded code terms).
     */
    fun buildPrompt(workdir: Path?): String {
        if (workdir == null) return ""
        val terms = LinkedHashSet<String>()
        runCatching {
            val head = workdir.resolve(".git/HEAD").readText().trim()
            head.substringAfter("ref: refs/heads/", "").takeIf { it.isNotBlank() }?.let { terms.add(it) }
        }
        runCatching {
            workdir.listDirectoryEntries()
                .map { it.name }
                .filterNot { it.startsWith(".") }
                .sorted()
                .forEach { terms.add(it) }
        }
        val intro = "以下是 ${workdir.name} 项目的开发口述"
        val overhead = intro.length + "，可能提到 ".length + " 等术语。".length
        val list = StringBuilder()
        for (t in terms) {
            if (overhead + list.length + t.length + 1 > MAX_PROMPT) break
            if (list.isNotEmpty()) list.append("、")
            list.append(t)
        }
        return if (list.isEmpty()) "$intro。" else "$intro，可能提到 $list 等术语。"
    }

    /** whisper-cli argv. `-otxt -of` writes a deterministic .txt next to [outBase]; stdout is noise. */
    fun buildArgs(model: Path, wav: Path, outBase: Path, prompt: String): List<String> = buildList {
        add("-m"); add(model.toString())
        add("-l"); add("auto")
        add("--no-timestamps")
        add("-np")
        add("-otxt")
        add("-of"); add(outBase.toString())
        if (prompt.isNotBlank()) { add("--prompt"); add(prompt) }
        add("-f"); add(wav.toString())
    }

    /** Strip whisper artifacts: sound-event brackets, blank-audio markers, hallucinated repeats. */
    fun cleanTranscript(raw: String): String {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val kept = ArrayList<String>(lines.size)
        for (line in lines) {
            if (BRACKET_ONLY.matches(line)) continue          // [BLANK_AUDIO], (wind blowing), [Music]…
            if (kept.lastOrNull() == line) continue            // consecutive duplicate = hallucination loop
            kept.add(line)
        }
        return kept.joinToString(" ").trim()
    }

    /** Full pipeline. [mediaType] "audio/wav" skips conversion; anything else goes through afconvert. */
    suspend fun transcribe(bytes: ByteArray, mediaType: String, workdir: Path?, whisper: Path, model: Path): TranscribeResult =
        withContext(Dispatchers.IO) {
            val tmp = Files.createTempDirectory("ccp-voice")
            try {
                val wav: Path
                if (mediaType == "audio/wav") {
                    wav = tmp.resolve("in.wav")
                    Files.write(wav, bytes)
                } else {
                    val src = tmp.resolve("in.m4a")
                    Files.write(src, bytes)
                    wav = tmp.resolve("in.wav")
                    val conv = runProcess(
                        listOf("/usr/bin/afconvert", "-f", "WAVE", "-d", "LEI16@16000", "-c", "1", src.toString(), wav.toString()),
                        CONVERT_TIMEOUT_S,
                    )
                    if (conv != 0) return@withContext TranscribeResult.Err("convert_failed", "audio conversion failed (afconvert exit $conv)")
                }

                val outBase = tmp.resolve("out")
                val t0 = System.currentTimeMillis()
                val exit = runProcess(
                    buildList { add(whisper.toString()); addAll(buildArgs(model, wav, outBase, buildPrompt(workdir))) },
                    WHISPER_TIMEOUT_S,
                )
                log.info("whisper exit=$exit in ${System.currentTimeMillis() - t0}ms")
                if (exit == EXIT_TIMEOUT) return@withContext TranscribeResult.Err("timeout", "transcription timed out")
                if (exit != 0) return@withContext TranscribeResult.Err("whisper_failed", "transcription failed (whisper exit $exit)")

                val txt = tmp.resolve("out.txt")
                if (!txt.isRegularFile()) return@withContext TranscribeResult.Err("whisper_failed", "transcription produced no output")
                TranscribeResult.Ok(cleanTranscript(txt.readText()))
            } catch (t: Throwable) {
                log.warn("transcribe error: ${t::class.simpleName}: ${t.message?.take(120)}")
                TranscribeResult.Err("internal", "transcription failed unexpectedly")
            } finally {
                runCatching { tmp.toFile().deleteRecursively() }
            }
        }

    /** Run to completion with a hard timeout; on timeout kill the whole tree. Returns exit code or [EXIT_TIMEOUT]. */
    private fun runProcess(argv: List<String>, timeoutS: Long): Int {
        val p = ProcessBuilder(argv).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        val descendants = p.toHandle().descendants().toList()
        val done = p.waitFor(timeoutS, TimeUnit.SECONDS)
        if (!done) {
            (descendants + p.toHandle().descendants().toList()).forEach { runCatching { it.destroyForcibly() } }
            p.destroyForcibly()
            runCatching { p.waitFor(2, TimeUnit.SECONDS) }
            return EXIT_TIMEOUT
        }
        return p.exitValue()
    }

    private val BRACKET_ONLY = Regex("""^[\[(][^\[\]()]*[])]$""")
    private const val MAX_PROMPT = 200
    private const val CONVERT_TIMEOUT_S = 15L
    private const val WHISPER_TIMEOUT_S = 60L
    private const val EXIT_TIMEOUT = Int.MIN_VALUE
}
