package dev.ccpocket.app.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.sqrt

/** Call once from MainActivity.onCreate (before setContent) — registers the RECORD_AUDIO launcher. */
fun initVoice(activity: ComponentActivity) {
    VoiceHost.appContext = activity.applicationContext
    val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        VoiceHost.pending?.complete(granted)
        VoiceHost.pending = null
    }
    VoiceHost.launch = { launcher.launch(Manifest.permission.RECORD_AUDIO) }
}

internal object VoiceHost {
    lateinit var appContext: Context
    var launch: (() -> Unit)? = null
    var pending: CompletableDeferred<Boolean>? = null
}

actual class VoiceRecorder actual constructor() {
    private val _levels = MutableSharedFlow<Float>(extraBufferCapacity = 16)
    actual val levels: Flow<Float> = _levels

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var levelJob: Job? = null
    private var startedMs = 0L

    actual suspend fun start() {
        ensureMicPermission()
        val ctx = VoiceHost.appContext
        val f = File(ctx.cacheDir, "voice-${System.currentTimeMillis()}.m4a")
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(ctx) else MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioSamplingRate(VOICE_SAMPLE_RATE)
        r.setAudioChannels(1)
        r.setAudioEncodingBitRate(VOICE_BIT_RATE)
        r.setOutputFile(f.path)
        r.prepare()
        r.start()
        recorder = r
        file = f
        startedMs = System.currentTimeMillis()
        levelJob = scope.launch {
            while (isActive) {
                delay(80)
                val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                _levels.emit(sqrt(amp / 32767f).coerceIn(0f, 1f)) // sqrt: keeps quiet speech visible
            }
        }
    }

    actual suspend fun stop(): RecordedAudio {
        levelJob?.cancel()
        recorder?.let { r ->
            runCatching { r.stop() } // throws if stopped immediately after start — treat as empty audio
            r.release()
        }
        recorder = null
        val f = file
        file = null
        val bytes = f?.takeIf { it.exists() }?.readBytes() ?: ByteArray(0)
        f?.delete()
        return RecordedAudio(bytes, "audio/mp4", System.currentTimeMillis() - startedMs)
    }

    actual fun cancel() {
        levelJob?.cancel()
        recorder?.let { r ->
            runCatching { r.stop() }
            r.release()
        }
        recorder = null
        file?.delete()
        file = null
    }
}

private suspend fun ensureMicPermission() {
    val ctx = VoiceHost.appContext
    if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return
    val launch = VoiceHost.launch ?: throw VoicePermissionDenied()
    val pending = CompletableDeferred<Boolean>()
    VoiceHost.pending = pending
    launch()
    if (!pending.await()) throw VoicePermissionDenied()
}

actual fun openAppSettings() {
    val ctx = VoiceHost.appContext
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ctx.startActivity(intent)
}
