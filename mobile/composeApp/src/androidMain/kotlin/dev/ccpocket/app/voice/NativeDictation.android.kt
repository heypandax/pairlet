package dev.ccpocket.app.voice

import kotlinx.coroutines.flow.Flow

/** P0: Android always uses record→daemon-whisper (system SpeechRecognizer is absent on many CN ROMs). */
actual object NativeDictation {
    actual val available: Boolean = false
    actual fun start(): Flow<DictationEvent> = error("native dictation unavailable on Android")
    actual suspend fun stop() {}
    actual fun cancel() {}
}
