package dev.ccpocket.app.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.TargetDataLine
import kotlin.math.sqrt

/** Desktop: raw 16 kHz mono PCM via javax.sound, shipped as wav ("audio/wav" — daemon skips afconvert). */
actual class VoiceRecorder actual constructor() {
    private val _levels = MutableSharedFlow<Float>(extraBufferCapacity = 16)
    actual val levels: Flow<Float> = _levels

    private val format = AudioFormat(VOICE_SAMPLE_RATE.toFloat(), 16, 1, true, false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var line: TargetDataLine? = null
    private var pumpJob: Job? = null
    private val pcm = ByteArrayOutputStream()
    private var startedMs = 0L

    actual suspend fun start(): Unit = withContext(Dispatchers.IO) {
        val l = try {
            AudioSystem.getTargetDataLine(format).apply { open(format); start() }
        } catch (t: Throwable) {
            throw VoicePermissionDenied() // mic privacy denial / no input device both surface here
        }
        line = l
        pcm.reset()
        startedMs = System.currentTimeMillis()
        pumpJob = scope.launch {
            val buf = ByteArray(VOICE_SAMPLE_RATE / 10 * 2) // 100 ms of 16-bit samples
            while (isActive) {
                val n = l.read(buf, 0, buf.size) // returns 0/-1 once the line is stopped+closed
                if (n <= 0) break
                pcm.write(buf, 0, n)
                _levels.emit(rms16(buf, n))
            }
        }
    }

    actual suspend fun stop(): RecordedAudio = withContext(Dispatchers.IO) {
        line?.let { runCatching { it.stop(); it.close() } } // unblocks the pump's read
        pumpJob?.join()
        line = null
        val raw = pcm.toByteArray()
        val out = ByteArrayOutputStream()
        AudioSystem.write(
            AudioInputStream(ByteArrayInputStream(raw), format, (raw.size / format.frameSize).toLong()),
            AudioFileFormat.Type.WAVE,
            out,
        )
        RecordedAudio(out.toByteArray(), "audio/wav", System.currentTimeMillis() - startedMs)
    }

    actual fun cancel() {
        line?.let { runCatching { it.stop(); it.close() } }
        pumpJob?.cancel()
        line = null
        pcm.reset()
    }

    private fun rms16(buf: ByteArray, n: Int): Float {
        var sum = 0.0
        var i = 0
        while (i + 1 < n) {
            val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt() / 32768.0
            sum += s * s
            i += 2
        }
        val samples = n / 2
        if (samples == 0) return 0f
        return sqrt(sqrt(sum / samples)).toFloat().coerceIn(0f, 1f) // double sqrt ≈ perceptual lift
    }
}

/** macOS: jump straight to the Microphone privacy pane; elsewhere a no-op. */
actual fun openAppSettings() {
    if (System.getProperty("os.name").lowercase().contains("mac")) {
        runCatching {
            ProcessBuilder("open", "x-apple.systempreferences:com.apple.preference.security?Privacy_Microphone").start()
        }
    }
}
