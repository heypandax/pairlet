package dev.ccpocket.app.data

import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.voice_dictation_failed
import dev.ccpocket.app.resources.voice_transcribe_failed
import dev.ccpocket.app.resources.voice_setup_request
import dev.ccpocket.protocol.SendPrompt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSetupRequestTest {
    private val diagnostic = "whisper model missing — run on the computer:\nmkdir -p ~/.cache/cc-pocket/models"
    private fun failure(detail: String = diagnostic) = VoiceState.Failed(Res.string.voice_transcribe_failed, detail)

    @Test
    fun any_missing_prerequisite_sends_one_complete_setup_request() = runTest {
        val r = PocketRepository(backgroundScope).apply {
            convoId.value = "c1"
            connected.value = true
        }
        val sent = mutableListOf<SendPrompt>()
        r.onSendForTest = { if (it is SendPrompt) sent += it }
        for (detail in listOf("whisper-cli not found", "whisper model missing", "no audio converter found")) {
            val failed = failure(detail)
            r.voice.value = failed
            val prompt = getString(Res.string.voice_setup_request, detail)
            assertTrue(r.requestVoiceSetup(failed, "c1", prompt))
            runCurrent()
            val request = sent.last().text
            // Even a software-only diagnostic must give the agent the model's location and converter.
            for (required in listOf("brew install whisper-cpp", "ggml-large-v3-turbo-q5_0.bin", "~/.cache/cc-pocket/models", "afconvert", "ffmpeg", detail)) {
                assertTrue(required in request, "setup request omitted $required for $detail")
            }
        }
        assertEquals(3, sent.size, "one request per explicit tap, each covering the whole environment")
    }

    @Test
    fun only_missing_computer_dependencies_offer_agent_setup() {
        assertEquals(VoiceSetupIssue.MODEL, failure().setupIssue)
        assertEquals(VoiceSetupIssue.TRANSCRIBER, failure("whisper-cli not found — install it on the computer: brew install whisper-cpp").setupIssue)
        assertEquals(VoiceSetupIssue.CONVERTER, failure("no audio converter found — install ffmpeg on the computer").setupIssue)
        assertNull(failure("transcription timed out").setupIssue)
        assertNull(failure("transcription failed (whisper exit 1)").setupIssue)
        assertNull(VoiceState.Failed(Res.string.voice_transcribe_failed).setupIssue)
        assertNull(VoiceState.Failed(Res.string.voice_dictation_failed, diagnostic).setupIssue)
    }

    @Test
    fun explicit_request_uses_current_conversation_without_sending_draft_or_attachments() = runTest {
        val r = PocketRepository(backgroundScope).apply {
            convoId.value = "c1"
            connected.value = true
            workdir.value = "/repo"
            streaming.value = true // normal mid-turn steering/queue path remains available
        }
        val sent = mutableListOf<SendPrompt>()
        r.onSendForTest = { if (it is SendPrompt) sent += it }
        val failure = failure()
        r.voice.value = failure
        r.saveDraft("c1", "keep my draft")
        val image = PendingImage(1, byteArrayOf(1, 2, 3), ImgState.Ready)
        val file = PendingFile(2, "note.txt", 1, byteArrayOf(), "text/plain", FileUpState.Landed, path = "/inbox/note.txt")
        val upload = file.copy(id = 3, state = FileUpState.Uploading, path = null)
        r.pendingImages += image
        r.pendingFiles += listOf(file, upload)
        runCurrent()
        assertTrue(sent.isEmpty(), "showing an error never sends anything automatically")

        val prompt = "Please set up voice input.\n$diagnostic"
        assertTrue(r.requestVoiceSetup(failure, "c1", prompt))
        assertFalse(r.requestVoiceSetup(failure, "c1", prompt), "a second tap cannot duplicate the request")
        runCurrent()
        assertEquals(1, sent.size)
        assertEquals("c1", sent.single().convoId)
        assertEquals(prompt, sent.single().text)
        assertTrue(sent.single().images.isEmpty())
        assertEquals(prompt, r.messages.filterIsInstance<ChatItem.User>().single().text)
        assertEquals("keep my draft", r.draftFor("c1"))
        assertSame(image, r.pendingImages.single())
        assertEquals(listOf(file, upload), r.pendingFiles.toList())
        assertEquals(VoiceState.Idle, r.voice.value)
    }

    @Test
    fun rejected_request_preserves_failure_and_normal_send_gate() = runTest {
        val r = PocketRepository(backgroundScope).apply {
            convoId.value = "c1"
            connected.value = true
            sessionDegraded.value = true
        }
        val failure = failure()
        r.voice.value = failure
        val sent = mutableListOf<SendPrompt>()
        r.onSendForTest = { if (it is SendPrompt) sent += it }
        assertFalse(r.requestVoiceSetup(failure, "c1", "Set up voice"))
        assertSame(failure, r.voice.value)
        runCurrent()
        assertTrue(sent.isEmpty())
        assertTrue(r.requestVoiceSetup(failure, "c1", "Set up voice"))
        runCurrent()
        assertEquals(1, sent.size)
    }

    @Test
    fun stale_disconnected_and_observer_actions_cannot_start_setup() = runTest {
        val r = PocketRepository(backgroundScope).apply { convoId.value = "c1" }
        val failure = failure()
        r.voice.value = failure
        assertFalse(r.requestVoiceSetup(failure, "c1", "Set up voice")) // disconnected
        r.connected.value = true
        assertFalse(r.requestVoiceSetup(failure, "old-conversation", "Set up voice"))
        assertFalse(r.requestVoiceSetup(failure.copy(), "c1", "Set up voice"))
        r.observing.value = true
        assertFalse(r.requestVoiceSetup(failure, "c1", "Set up voice"))
        assertSame(failure, r.voice.value)
        assertTrue(r.messages.isEmpty())
    }

    @Test
    fun ordinary_send_still_consumes_staged_attachments() = runTest {
        val r = PocketRepository(backgroundScope).apply { convoId.value = "c1" }
        val sent = mutableListOf<SendPrompt>()
        r.onSendForTest = { if (it is SendPrompt) sent += it }
        r.pendingImages += PendingImage(1, byteArrayOf(1, 2, 3), ImgState.Ready)
        r.pendingFiles += PendingFile(2, "note.txt", 1, byteArrayOf(), "text/plain", FileUpState.Landed, path = "/inbox/note.txt")
        assertTrue(r.sendPrompt("My draft"))
        runCurrent()
        assertEquals(1, sent.single().images.size)
        assertEquals("My draft\n\n@/inbox/note.txt", sent.single().text)
        assertTrue(r.pendingImages.isEmpty())
        assertTrue(r.pendingFiles.isEmpty())
    }
}
