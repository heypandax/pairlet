package dev.ccpocket.app.data

import dev.ccpocket.app.media.PickedFile
import dev.ccpocket.app.secure.SecureStore
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.DaemonInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Voice input v2, the part the UI reads before anything is recorded (docs/design/VOICE-INPUT-V2-REVIEW.md §11
 * "已确认"): the "after dictation" setting and its disclosure, and whether a capture could send — every condition
 * alone must say no.
 */
class VoiceRefineSettingTest {

    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))

    @BeforeTest fun setUp() = clearStore()

    @AfterTest fun tearDown() {
        scope.cancel()
        clearStore()
    }

    private fun clearStore() {
        SecureStore.remove(PocketRepository.K_VOICE_AFTER_DICTATION)
        SecureStore.remove(PocketRepository.K_VOICE_REFINE_ACK)
    }

    // ── the setting and its disclosure ─────────────────────────────────────────────────────────────

    @Test fun everyoneStartsOnComposeAndSendNeedsTheDisclosure() {
        val r = PocketRepository(scope)
        assertEquals(VoiceAfterDictation.COMPOSE, r.voiceAfterDictation.value, "the default for new and existing users")
        assertFalse(r.voiceRefineAcked.value)

        assertFalse(r.setVoiceAfterDictation(VoiceAfterDictation.SEND), "refused before the disclosure was accepted")
        assertEquals(VoiceAfterDictation.COMPOSE, r.voiceAfterDictation.value)
        assertNull(SecureStore.getString(PocketRepository.K_VOICE_AFTER_DICTATION), "and nothing was stored")

        r.acknowledgeVoiceRefineDisclosure()
        assertTrue(r.voiceRefineAcked.value)
        assertTrue(r.setVoiceAfterDictation(VoiceAfterDictation.SEND))
        assertEquals(VoiceAfterDictation.SEND, r.voiceAfterDictation.value)

        val again = PocketRepository(scope)
        assertEquals(VoiceAfterDictation.SEND, again.voiceAfterDictation.value, "persisted on this device")
        assertTrue(again.voiceRefineAcked.value)

        assertTrue(again.setVoiceAfterDictation(VoiceAfterDictation.COMPOSE))
        assertEquals(VoiceAfterDictation.COMPOSE, PocketRepository(scope).voiceAfterDictation.value)
        assertTrue(PocketRepository(scope).voiceRefineAcked.value, "the acknowledgement is one-way")
    }

    @Test fun anythingButSendReadsCompose() {
        for (stored in listOf("compose", "SEND", "Send", "1", "", "true")) {
            SecureStore.putString(PocketRepository.K_VOICE_AFTER_DICTATION, stored)
            assertEquals(VoiceAfterDictation.COMPOSE, PocketRepository(scope).voiceAfterDictation.value, "'$stored'")
        }
        SecureStore.putString(PocketRepository.K_VOICE_AFTER_DICTATION, "send")
        assertEquals(VoiceAfterDictation.SEND, PocketRepository(scope).voiceAfterDictation.value)
    }

    // ── eligibility ────────────────────────────────────────────────────────────────────────────────

    private var probe: ComposerProbe? = ComposerProbe("", composing = false)

    /** Every condition holds: a writable chat on a Ready link, a refiner for its agent, an empty composer, SEND. */
    private fun eligible() = PocketRepository(scope).apply {
        paired.value = null
        acknowledgeVoiceRefineDisclosure()
        assertTrue(setVoiceAfterDictation(VoiceAfterDictation.SEND))
        connected.value = true
        phase.value = ConnPhase.Ready
        convoId.value = "c1"
        sessionKey.value = "s1"
        sessionAgent.value = AgentKind.CLAUDE
        receiveForTest(DaemonInfo(transcriptRefineAgents = listOf("claude")))
        composerProbe = { probe ?: error("unregistered") }
        assertTrue(voiceSendEligible(), "baseline")
    }

    private fun alone(what: String, breakIt: PocketRepository.() -> Unit) {
        probe = ComposerProbe("", composing = false)
        val r = eligible()
        r.breakIt()
        assertFalse(r.voiceSendEligible(), "$what alone must make a capture ineligible")
    }

    @Test fun eachConditionAloneMakesACaptureIneligible() {
        alone("the COMPOSE setting") { setVoiceAfterDictation(VoiceAfterDictation.COMPOSE) }
        alone("no connection") { connected.value = false }
        alone("a link that is not Ready") { phase.value = ConnPhase.Reconnecting }
        alone("a computer that is offline") { phase.value = ConnPhase.ComputerOffline }
        alone("no conversation") { convoId.value = null }
        alone("an observed session") { observing.value = true }
        alone("a degraded session") { sessionDegraded.value = true }
        alone("an unknown agent") { sessionAgent.value = null }
        alone("an agent without a refiner") { sessionAgent.value = AgentKind.CODEX }
        alone("a computer without refiners") { receiveForTest(DaemonInfo()) }
        alone("text on its way into the composer") { pendingVoiceText.value = "earlier words" }
        alone("no composer probe") { composerProbe = null }
        alone("a draft in the composer") { probe = ComposerProbe("half a thought", composing = false) }
        alone("an open IME composition") { probe = ComposerProbe("", composing = true) }
        for (state in ImgState.entries) alone("a staged photo ($state)") { pendingImages += PendingImage(1, byteArrayOf(1), state) }
        for (state in FileUpState.entries) alone("a staged file ($state)") {
            pendingFiles += PendingFile(2, "a.log", 1, byteArrayOf(1), "text/plain", state)
        }
        alone("an attachment being picked") { attachFiles(listOf(PickedFile("b.log", 1, byteArrayOf(1), "text/plain"))) }
    }

    @Test fun theDisclosureIsAConditionToo() {
        SecureStore.putString(PocketRepository.K_VOICE_AFTER_DICTATION, "send") // never acknowledged here
        val r = PocketRepository(scope).apply {
            paired.value = null
            connected.value = true
            phase.value = ConnPhase.Ready
            convoId.value = "c1"
            sessionAgent.value = AgentKind.CLAUDE
            receiveForTest(DaemonInfo(transcriptRefineAgents = listOf("claude")))
            composerProbe = { ComposerProbe("", composing = false) }
        }
        assertFalse(r.voiceSendEligible())
        r.acknowledgeVoiceRefineDisclosure()
        assertTrue(r.voiceSendEligible())
    }

    @Test fun whitespaceInTheComposerIsEmpty() {
        probe = ComposerProbe("  \n", composing = false)
        assertTrue(eligible().voiceSendEligible())
    }

    @Test fun theComputersRefinersFollowItsAnnouncementAndLeaveWithIt() {
        val r = PocketRepository(scope)
        assertTrue(r.daemonTranscriptRefineAgents.value.isEmpty())
        r.receiveForTest(DaemonInfo(transcriptRefineAgents = listOf("claude")))
        assertEquals(listOf("claude"), r.daemonTranscriptRefineAgents.value)
        r.receiveForTest(DaemonInfo()) // an older daemon: the field is absent
        assertTrue(r.daemonTranscriptRefineAgents.value.isEmpty())
        r.receiveForTest(DaemonInfo(transcriptRefineAgents = listOf("claude")))
        r.disconnect()
        assertTrue(r.daemonTranscriptRefineAgents.value.isEmpty(), "the next computer re-advertises its own")
    }
}
