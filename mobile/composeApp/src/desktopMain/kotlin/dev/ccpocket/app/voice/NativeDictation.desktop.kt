package dev.ccpocket.app.voice

import kotlinx.coroutines.flow.Flow

/** P0: desktop always uses record→daemon-whisper. */
actual object NativeDictation {
    actual val available: Boolean = false
    actual fun start(): Flow<DictationEvent> = error("native dictation unavailable on desktop")
    actual suspend fun stop() {}
    actual fun cancel() {}
}
