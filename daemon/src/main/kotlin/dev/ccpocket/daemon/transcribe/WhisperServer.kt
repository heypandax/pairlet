package dev.ccpocket.daemon.transcribe

import dev.ccpocket.daemon.util.logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.name

/**
 * Resident whisper-server: keeps the ggml model loaded between captures so a dictation burst only
 * pays model load once (~0.5–1 s per capture with the large models). Lazily started on first use,
 * reaped after [IDLE_TIMEOUT_MS] of silence to give the RAM back; any failure at any stage returns
 * null and the caller falls back to the one-shot whisper-cli path. Disable outright with
 * `CC_POCKET_WHISPER_SERVER=0`.
 */
object WhisperServer {

    private val log = logger("WhisperSrv")

    private var proc: Process? = null
    private var port = 0
    private var loadedModel: Path? = null
    @Volatile private var lastUsed = 0L

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private val reaper = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "whisper-server-reaper").apply { isDaemon = true }
    }

    init {
        reaper.scheduleWithFixedDelay({ reapIfIdle() }, 1, 1, TimeUnit.MINUTES)
        Runtime.getRuntime().addShutdownHook(Thread { synchronized(this) { stopLocked("daemon exit") } })
    }

    val enabled: Boolean
        get() = System.getenv("CC_POCKET_WHISPER_SERVER") != "0"

    /**
     * Transcribe [wav] via the resident server, (re)starting it for [model] if needed.
     * Returns the raw transcript text, or null on any failure — caller falls back to whisper-cli.
     */
    fun transcribe(wav: ByteArray, model: Path, prompt: String, lang: String): String? {
        if (!enabled) return null
        val p = synchronized(this) { ensureRunningLocked(model) } ?: return null
        return try {
            val t0 = System.currentTimeMillis()
            val body = multipart(
                boundary = "ccp-${System.nanoTime()}",
                fields = listOf("language" to lang, "prompt" to prompt, "response_format" to "json"),
                fileField = "file", fileName = "capture.wav", fileBytes = wav,
            )
            val req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/inference"))
                .header("Content-Type", "multipart/form-data; boundary=${body.first}")
                .timeout(Duration.ofSeconds(WhisperTranscriber.WHISPER_TIMEOUT_S))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.second))
                .build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            lastUsed = System.currentTimeMillis()
            if (res.statusCode() != 200) {
                log.warn("inference HTTP ${res.statusCode()} — falling back to cli")
                return null
            }
            val text = extractText(res.body()) ?: return null
            log.info("inference ok in ${System.currentTimeMillis() - t0}ms (resident)")
            text
        } catch (t: Throwable) {
            log.warn("inference failed: ${t::class.simpleName}: ${t.message?.take(120)} — falling back to cli")
            synchronized(this) { if (proc === p) stopLocked("request failure") }
            null
        }
    }

    /** `{"text":"..."}` → text. Null on parse mismatch (server error payloads land here too). */
    fun extractText(json: String): String? = runCatching {
        Json.parseToJsonElement(json).jsonObject["text"]?.jsonPrimitive?.content
    }.getOrNull()

    /** Multipart body for /inference. Returns boundary → bytes. Text fields are UTF-8. */
    fun multipart(
        boundary: String,
        fields: List<Pair<String, String>>,
        fileField: String,
        fileName: String,
        fileBytes: ByteArray,
    ): Pair<String, ByteArray> {
        val sb = StringBuilder()
        for ((k, v) in fields) {
            sb.append("--").append(boundary).append("\r\n")
            sb.append("Content-Disposition: form-data; name=\"").append(k).append("\"\r\n\r\n")
            sb.append(v).append("\r\n")
        }
        sb.append("--").append(boundary).append("\r\n")
        sb.append("Content-Disposition: form-data; name=\"").append(fileField)
            .append("\"; filename=\"").append(fileName).append("\"\r\n")
        sb.append("Content-Type: application/octet-stream\r\n\r\n")
        val head = sb.toString().toByteArray(StandardCharsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8)
        return boundary to (head + fileBytes + tail)
    }

    /** True when the server has sat unused past the idle timeout (pure — see reaper). */
    fun idleExpired(last: Long, now: Long): Boolean = last > 0 && now - last > IDLE_TIMEOUT_MS

    // ── lifecycle (all under the object lock) ─────────────────────────────

    private fun ensureRunningLocked(model: Path): Process? {
        val cur = proc
        if (cur != null && cur.isAlive && loadedModel == model) {
            lastUsed = System.currentTimeMillis()
            return cur
        }
        stopLocked(if (cur == null) null else "model changed / died")
        val bin = WhisperTranscriber.resolveServerBin() ?: return null
        return runCatching {
            val freePort = ServerSocket(0).use { it.localPort }
            val p = ProcessBuilder(
                bin.toString(), "-m", model.toString(),
                "--host", "127.0.0.1", "--port", freePort.toString(), "--no-timestamps",
            ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            if (!waitReady(p, freePort)) {
                p.destroyForcibly()
                log.warn("server did not become ready — falling back to cli")
                return null
            }
            proc = p; port = freePort; loadedModel = model; lastUsed = System.currentTimeMillis()
            log.info("started on :$freePort (model ${model.name})")
            p
        }.onFailure { log.warn("start failed: ${it::class.simpleName}: ${it.message?.take(120)}") }.getOrNull()
    }

    /** Poll `/` until any HTTP answer. Generous budget: first-ever run compiles Metal shaders. */
    private fun waitReady(p: Process, port: Int): Boolean {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive) return false
            runCatching {
                val req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/"))
                    .timeout(Duration.ofSeconds(1)).GET().build()
                http.send(req, HttpResponse.BodyHandlers.discarding())
                return true
            }
            Thread.sleep(250)
        }
        return false
    }

    private fun reapIfIdle() {
        synchronized(this) {
            if (proc?.isAlive == true && idleExpired(lastUsed, System.currentTimeMillis())) {
                stopLocked("idle ${IDLE_TIMEOUT_MS / 60_000} min — releasing model RAM")
            }
        }
    }

    private fun stopLocked(reason: String?) {
        proc?.let { p ->
            reason?.let { log.info("stopping: $it") }
            runCatching { p.destroy(); if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly() }
        }
        proc = null; port = 0; loadedModel = null
    }

    private const val IDLE_TIMEOUT_MS = 10 * 60_000L
    private const val READY_TIMEOUT_MS = 150_000L
}
