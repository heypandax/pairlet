package dev.ccpocket.app.data

import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.Transcript
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #221: a completed voice transcript must NOT auto-send. It lands in [PocketRepository.pendingVoiceText]
 * for the composer (App.kt) to append and let the user confirm/edit before an explicit send. Drives the real
 * wire path (a `Transcript` frame → onTranscript → deliverTranscript); the capture-match gate is satisfied by
 * setting the internal [PocketRepository.captureId] to the frame's captureId.
 */
class VoiceTranscriptReviewTest {

    private fun repo() = PocketRepository(CoroutineScope(Dispatchers.Unconfined)).apply {
        convoId.value = "c1"
    }

    @Test
    fun aFinishedTranscriptLandsInTheComposerInsteadOfSending() {
        val r = repo()
        val sent = mutableListOf<SendPrompt>()
        r.onSendForTest = { f: Frame -> if (f is SendPrompt) sent.add(f) }
        r.captureId = "cap-1"
        r.voice.value = VoiceState.Transcribing

        r.receiveForTest(Transcript("c1", "cap-1", text = "open the settings file", ok = true))

        assertEquals(
            "open the settings file", r.pendingVoiceText.value,
            "the transcript must be staged for the composer to review (issue #221)",
        )
        assertTrue(sent.isEmpty(), "recognition results must never auto-send — the user sends explicitly")
        assertTrue(r.voice.value is VoiceState.Idle, "delivering the transcript resets the recording state")
    }

    @Test
    fun aBlankTranscriptStagesNothing() {
        val r = repo()
        val sent = mutableListOf<SendPrompt>()
        r.onSendForTest = { f: Frame -> if (f is SendPrompt) sent.add(f) }
        r.captureId = "cap-2"
        r.voice.value = VoiceState.Transcribing

        r.receiveForTest(Transcript("c1", "cap-2", text = "   ", ok = true))

        assertNull(r.pendingVoiceText.value, "an empty transcript stages nothing for the composer")
        assertTrue(sent.isEmpty(), "and certainly sends nothing")
    }
}
