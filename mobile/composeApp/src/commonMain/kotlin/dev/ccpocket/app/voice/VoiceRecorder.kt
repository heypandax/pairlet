package dev.ccpocket.app.voice

import kotlinx.coroutines.flow.Flow

/** A finished capture ready to ship to the daemon for whisper transcription. */
class RecordedAudio(val bytes: ByteArray, val mediaType: String, val durationMs: Long)

/** Mic (or speech-recognition) permission refused — the UI raises the settings sheet (S6). */
class VoicePermissionDenied : Exception("microphone permission denied")

/**
 * One platform audio recorder for the record→daemon-whisper path.
 * Output contract (matches the daemon's afconvert/whisper input): 16 kHz mono,
 * AAC m4a on phones ("audio/mp4"), PCM wav on desktop ("audio/wav").
 */
expect class VoiceRecorder() {
    /** 0..1 volume envelope, ~12 Hz — drives the recording-bar waveform. */
    val levels: Flow<Float>

    /** Begins capturing. Throws [VoicePermissionDenied] if the user refuses the mic. */
    suspend fun start()

    /** Ends capturing and returns the encoded audio. */
    suspend fun stop(): RecordedAudio

    /** Discards the capture (user tapped ✕). */
    fun cancel()
}

/** Open the OS settings page where the user can grant the mic permission (S6 "Open Settings"). */
expect fun openAppSettings()

const val VOICE_MAX_MS = 90_000L
const val VOICE_SAMPLE_RATE = 16_000
const val VOICE_BIT_RATE = 24_000

/** Max base64 chars per [dev.ccpocket.protocol.AudioChunk]. The relay frame cap is 4 MiB nowadays,
 *  but voice captures are small — modest chunks keep the socket responsive mid-recording.
 *  (File uploads use their own larger chunk size — see PocketRepository.FILE_CHUNK_RAW.) */
const val AUDIO_CHUNK_B64 = 180_000
