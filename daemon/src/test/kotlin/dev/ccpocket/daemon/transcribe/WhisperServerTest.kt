package dev.ccpocket.daemon.transcribe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WhisperServerTest {

    @Test
    fun extractText_reads_inference_payload_and_rejects_noise() {
        assertEquals("你好 hello\n", WhisperServer.extractText("""{"text":"你好 hello\n"}"""))
        assertNull(WhisperServer.extractText("""{"error":"failed"}"""))
        assertNull(WhisperServer.extractText("<html>502</html>"))
    }

    @Test
    fun multipart_carries_fields_then_file_with_terminal_boundary() {
        val (boundary, bytes) = WhisperServer.multipart(
            boundary = "BND",
            fields = listOf("language" to "auto", "prompt" to "中英混合"),
            fileField = "file", fileName = "capture.wav", fileBytes = byteArrayOf(1, 2, 3),
        )
        assertEquals("BND", boundary)
        val s = bytes.toString(Charsets.ISO_8859_1) // 1:1 byte view; UTF-8 field text checked via encoded form
        assertTrue(s.startsWith("--BND\r\nContent-Disposition: form-data; name=\"language\"\r\n\r\nauto\r\n"))
        assertTrue("name=\"prompt\"" in s)
        assertTrue("中英混合".toByteArray(Charsets.UTF_8).toString(Charsets.ISO_8859_1) in s)
        assertTrue("filename=\"capture.wav\"" in s)
        assertTrue(s.endsWith("\r\n--BND--\r\n"))
        val fileStart = s.indexOf("\r\n\r\n", s.indexOf("filename=")) + 4
        assertEquals(listOf<Byte>(1, 2, 3), bytes.slice(fileStart until fileStart + 3))
    }

    @Test
    fun idleExpired_only_after_timeout_and_never_before_first_use() {
        assertFalse(WhisperServer.idleExpired(last = 0, now = 99_999_999)) // never used → nothing to reap
        assertFalse(WhisperServer.idleExpired(last = 1_000_000, now = 1_000_000 + 9 * 60_000))
        assertTrue(WhisperServer.idleExpired(last = 1_000_000, now = 1_000_000 + 11 * 60_000))
    }

    /** Real spawn → ready → multipart POST → parse, against the local toolchain. Skips (silently
     *  passes) where whisper-server or a model isn't installed, so CI stays hermetic. */
    @Test
    fun residentServer_end_to_end_when_toolchain_present() {
        val model = WhisperTranscriber.resolveModel() ?: return
        WhisperTranscriber.resolveServerBin() ?: return
        val text = WhisperServer.transcribe(silenceWav(seconds = 1), model, prompt = "", lang = "auto")
        // silence may transcribe to "" or a [BLANK_AUDIO] marker — reaching ANY parsed text proves the path
        kotlin.test.assertNotNull(text)
    }

    /** Minimal valid 16 kHz mono 16-bit PCM WAV of digital silence. */
    private fun silenceWav(seconds: Int): ByteArray {
        val dataLen = 16_000 * 2 * seconds
        val bb = java.nio.ByteBuffer.allocate(44 + dataLen).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(36 + dataLen).put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        bb.putInt(16_000).putInt(16_000 * 2).putShort(2).putShort(16)
        bb.put("data".toByteArray()).putInt(dataLen)
        return bb.array()
    }
}
