package dev.ccpocket.app.voice

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFAudio.AVAudioRecorder
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryRecord
import platform.AVFAudio.AVAudioSessionRecordPermissionDenied
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.AVFAudio.AVEncoderBitRateKey
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.AVFAudio.setActive
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL.Companion.fileURLWithPath
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.posix.memcpy
import kotlinx.cinterop.ObjCObjectVar
import kotlin.coroutines.resume
import kotlin.math.pow

/** iOS fallback recorder (used when NativeDictation is unavailable): AAC m4a via AVAudioRecorder. */
@OptIn(ExperimentalForeignApi::class)
actual class VoiceRecorder actual constructor() {
    private val _levels = MutableSharedFlow<Float>(extraBufferCapacity = 16)
    actual val levels: Flow<Float> = _levels

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var recorder: AVAudioRecorder? = null
    private var path: String? = null
    private var levelJob: Job? = null
    private var startedMs = 0L

    actual suspend fun start() {
        if (!requestMicPermission()) throw VoicePermissionDenied()
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryRecord, error = null)
        session.setActive(true, error = null)

        val p = NSTemporaryDirectory() + "ccp-voice.m4a"
        val settings = mapOf<Any?, Any?>(
            AVFormatIDKey to kAudioFormatMPEG4AAC,
            AVSampleRateKey to VOICE_SAMPLE_RATE.toDouble(),
            AVNumberOfChannelsKey to 1,
            AVEncoderBitRateKey to VOICE_BIT_RATE,
        )
        val rec = memScoped {
            val err = alloc<ObjCObjectVar<NSError?>>()
            AVAudioRecorder(fileURLWithPath(p), settings, err.ptr)
        }
        rec.meteringEnabled = true
        if (!rec.record()) throw VoicePermissionDenied()
        recorder = rec
        path = p
        startedMs = nowMs()
        levelJob = scope.launch {
            while (isActive) {
                delay(80)
                val r = recorder ?: break
                r.updateMeters()
                val db = r.averagePowerForChannel(0u) // -160..0
                _levels.emit(((db + 50f) / 50f).coerceIn(0f, 1f))
            }
        }
    }

    actual suspend fun stop(): RecordedAudio {
        levelJob?.cancel()
        recorder?.stop()
        recorder = null
        AVAudioSession.sharedInstance().setActive(false, error = null)
        val p = path
        path = null
        val bytes = p?.let { readAndDelete(it) } ?: ByteArray(0)
        return RecordedAudio(bytes, "audio/mp4", nowMs() - startedMs)
    }

    actual fun cancel() {
        levelJob?.cancel()
        recorder?.stop()
        recorder = null
        AVAudioSession.sharedInstance().setActive(false, error = null)
        path?.let { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
        path = null
    }

    private fun readAndDelete(p: String): ByteArray {
        val data: NSData = NSData.dataWithContentsOfFile(p) ?: return ByteArray(0)
        val len = data.length.toInt()
        val out = ByteArray(len)
        if (len > 0) out.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        NSFileManager.defaultManager.removeItemAtPath(p, error = null)
        return out
    }

    private fun nowMs(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
}

/** Mic permission: granted fast-path, else ask; denied = false. */
internal suspend fun requestMicPermission(): Boolean {
    val session = AVAudioSession.sharedInstance()
    return when (session.recordPermission) {
        AVAudioSessionRecordPermissionGranted -> true
        AVAudioSessionRecordPermissionDenied -> false
        else -> suspendCancellableCoroutine { cont ->
            session.requestRecordPermission { granted -> cont.resume(granted) }
        }
    }
}

actual fun openAppSettings() {
    val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
    UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
}
