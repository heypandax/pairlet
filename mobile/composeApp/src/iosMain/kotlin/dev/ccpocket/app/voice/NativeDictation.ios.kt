package dev.ccpocket.app.voice

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryRecord
import platform.AVFAudio.setActive
import platform.Foundation.NSError
import platform.Foundation.NSLocale
import platform.Foundation.NSSelectorFromString
import platform.Foundation.preferredLanguages
import platform.Speech.SFSpeechAudioBufferRecognitionRequest
import platform.Speech.SFSpeechRecognitionTask
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus
import kotlin.coroutines.resume
import kotlin.math.sqrt

/**
 * iOS streaming dictation: SFSpeechRecognizer over an AVAudioEngine input tap. Partials stream
 * word-by-word into the composer (design S2); the daemon is not involved on this path.
 */
@OptIn(ExperimentalForeignApi::class)
actual object NativeDictation {

    private var engine: AVAudioEngine? = null
    private var request: SFSpeechAudioBufferRecognitionRequest? = null
    private var task: SFSpeechRecognitionTask? = null

    /**
     * Recognizer for what the user actually speaks: the FIRST preferred language (e.g. zh-Hans-CN),
     * not the region locale — `SFSpeechRecognizer()` defaults to currentLocale, which follows the
     * device REGION and silently yields an English recognizer on zh-language/EN-region setups.
     */
    private fun makeRecognizer(): SFSpeechRecognizer? {
        val preferred = NSLocale.preferredLanguages.firstOrNull() as? String
        val byLanguage = preferred?.let { SFSpeechRecognizer(locale = NSLocale(localeIdentifier = it)) }
        if (byLanguage?.available == true) return byLanguage
        return SFSpeechRecognizer().takeIf { it.available }
    }

    actual val available: Boolean
        get() = runCatching { makeRecognizer() != null }.getOrDefault(false)

    actual fun start(): Flow<DictationEvent> = channelFlow {
        if (!requestSpeechAuth()) throw VoicePermissionDenied()
        if (!requestMicPermission()) throw VoicePermissionDenied()

        val recognizer = makeRecognizer()
        if (recognizer == null) {
            send(DictationEvent.Error(DictationFail.UNAVAILABLE))
            close()
            return@channelFlow
        }

        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryRecord, error = null)
        session.setActive(true, error = null)

        val req = SFSpeechAudioBufferRecognitionRequest().apply {
            shouldReportPartialResults = true
            // automatic punctuation is iOS 16+ and the deployment target is 15.0 — probe, don't call blind
            if (respondsToSelector(NSSelectorFromString("setAddsPunctuation:"))) addsPunctuation = true
        }
        val eng = AVAudioEngine()
        val input = eng.inputNode
        val format = input.outputFormatForBus(0u)
        input.installTapOnBus(0u, bufferSize = 1024u, format = format) { buffer, _ ->
            if (buffer != null) {
                req.appendAudioPCMBuffer(buffer)
                trySend(DictationEvent.Level(level(buffer)))
            }
        }
        eng.prepare()
        val started = memScoped {
            val err = alloc<ObjCObjectVar<NSError?>>()
            eng.startAndReturnError(err.ptr)
        }
        if (!started) {
            input.removeTapOnBus(0u)
            send(DictationEvent.Error(DictationFail.AUDIO_ENGINE))
            close()
            return@channelFlow
        }
        engine = eng
        request = req

        var lastHypothesis = ""
        task = recognizer.recognitionTaskWithRequest(req) { result, error ->
            when {
                error != null -> {
                    // endAudio() also completes through here on some iOS versions once a final was delivered
                    // localizedDescription is already in the device language — passed through verbatim
                    trySend(DictationEvent.Error(DictationFail.RECOGNITION, error.localizedDescription))
                    close()
                }
                result != null -> {
                    val text = result.bestTranscription.formattedString
                    if (result.final) {
                        trySend(DictationEvent.Final(text))
                        close()
                    } else {
                        // stable prefix (vs the previous hypothesis) renders primary, the volatile tail muted
                        val stable = stablePrefix(lastHypothesis, text)
                        lastHypothesis = text
                        trySend(DictationEvent.Partial(stable, text.substring(stable.length)))
                    }
                }
            }
        }

        awaitClose { teardown() }
    }

    actual suspend fun stop() {
        engine?.stop()
        engine?.inputNode?.removeTapOnBus(0u)
        request?.endAudio() // recognizer then delivers the final result and the flow completes
    }

    actual fun cancel() {
        task?.cancel()
        teardown()
    }

    private fun teardown() {
        runCatching {
            engine?.stop()
            engine?.inputNode?.removeTapOnBus(0u)
        }
        engine = null
        request = null
        task = null
        AVAudioSession.sharedInstance().setActive(false, error = null)
    }

    /**
     * Longest common prefix with the previous hypothesis. For latin text, snap back to the last
     * space so half-typed words stay in the muted tail; CJK has no spaces — rolling back would pin
     * the whole line muted forever, so the rollback is capped and skipped for long unspaced runs.
     */
    private fun stablePrefix(prev: String, cur: String): String {
        val n = minOf(prev.length, cur.length)
        var i = 0
        while (i < n && prev[i] == cur[i]) i++
        var j = i
        while (j > 0 && i - j < WORD_SNAP_MAX && !cur[j - 1].isWhitespace()) j--
        return cur.substring(0, if (i - j < WORD_SNAP_MAX) j else i)
    }

    private const val WORD_SNAP_MAX = 12

    private fun level(buffer: AVAudioPCMBuffer): Float {
        val ch = buffer.floatChannelData?.get(0) ?: return 0f
        val n = buffer.frameLength.toInt()
        if (n == 0) return 0f
        var sum = 0.0
        for (i in 0 until n) {
            val s = ch[i].toDouble()
            sum += s * s
        }
        return sqrt(sqrt(sum / n)).toFloat().coerceIn(0f, 1f)
    }

    private suspend fun requestSpeechAuth(): Boolean =
        when (SFSpeechRecognizer.authorizationStatus()) {
            SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized -> true
            SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusNotDetermined ->
                suspendCancellableCoroutine { cont ->
                    SFSpeechRecognizer.requestAuthorization { status ->
                        cont.resume(status == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized)
                    }
                }
            else -> false
        }
}
