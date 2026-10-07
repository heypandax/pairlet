package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.ccpocket.app.assertPresent
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.data.VoiceAfterDictation
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.appearance_section
import dev.ccpocket.app.resources.settings_after_dictation
import dev.ccpocket.app.resources.settings_after_dictation_compose
import dev.ccpocket.app.resources.settings_after_dictation_compose_sub
import dev.ccpocket.app.resources.settings_after_dictation_send
import dev.ccpocket.app.resources.settings_after_dictation_send_sub
import dev.ccpocket.app.resources.settings_cat_general
import dev.ccpocket.app.resources.voice_refine_disclosure_data
import dev.ccpocket.app.resources.voice_refine_disclosure_later
import dev.ccpocket.app.resources.voice_refine_disclosure_off
import dev.ccpocket.app.resources.voice_refine_disclosure_on
import dev.ccpocket.app.resources.voice_refine_disclosure_swaps
import dev.ccpocket.app.resources.voice_refine_disclosure_title
import dev.ccpocket.app.resources.voice_section
import dev.ccpocket.app.secure.SecureStore
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Voice input v2's "After dictation" setting (README "后续决定", review §11 "已确认"): off the desktop, the default
 * on a phone, and "Correct and send" reachable only through the one-time disclosure's "Turn on".
 */
@OptIn(ExperimentalTestApi::class)
class VoiceAfterDictationSettingsTest {

    private fun account() = PairedDaemon(
        relay = "wss://test.invalid", accountId = "acct-after-dictation", daemonPub = "pub",
        deviceId = "dev", credential = "cred", hostName = "alex-macbook",
    )

    @BeforeTest fun setUp() = clearStore()
    @AfterTest fun tearDown() = clearStore()

    private fun clearStore() {
        SecureStore.remove(PocketRepository.K_VOICE_AFTER_DICTATION)
        SecureStore.remove(PocketRepository.K_VOICE_REFINE_ACK)
    }

    /** Settings › General; [phone] = this app's chat hosts the send bar (null: the platform's own answer — the desktop). */
    private fun general(phone: Boolean?, assertions: SkikoComposeUiTest.(PocketRepository) -> Unit) = runDesktopComposeUiTest(402, 874) {
        lateinit var repo: PocketRepository
        setContent {
            val scope = rememberCoroutineScope()
            val r = remember { PocketRepository(scope, account()) }
            repo = r
            PocketTheme {
                if (phone == null) SettingsScreen(r, onBack = {})
                else CompositionLocalProvider(LocalHostsVoiceSendBar provides phone) { SettingsScreen(r, onBack = {}) }
            }
        }
        waitForIdle()
        onAllNodes(hasText(str(Res.string.settings_cat_general))).onFirst().performClick()
        waitForIdle()
        assertPresent(str(Res.string.appearance_section))
        assertions(repo)
    }

    private val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    private fun SkikoComposeUiTest.row() =
        onAllNodes(hasText(str(Res.string.settings_after_dictation)) and hasClickAction()).onFirst()

    private fun SkikoComposeUiTest.option(value: VoiceAfterDictation) = onAllNodes(
        hasText(str(if (value == VoiceAfterDictation.SEND) Res.string.settings_after_dictation_send else Res.string.settings_after_dictation_compose)) and radio,
    ).onFirst()

    private fun SkikoComposeUiTest.button(label: String) = onAllNodes(hasText(label) and hasClickAction()).onFirst()

    private fun SkikoComposeUiTest.disclosureUp() = present(str(Res.string.voice_refine_disclosure_title))

    /** Row → choice sheet → [value]. */
    private fun SkikoComposeUiTest.pick(value: VoiceAfterDictation) {
        row().performScrollTo().performClick()
        waitForIdle()
        option(value).performClick()
        waitForIdle()
    }

    @Test
    fun theDesktopNeverOffersIt() = general(phone = null) {
        assertFalse(present(str(Res.string.settings_after_dictation)), "the desktop's chat has no send bar")
        assertFalse(present(str(Res.string.voice_section)), "and without a native engine there is no voice section at all")
    }

    @Test
    fun aPhoneShowsTheDefaultAndWhatEachChoiceDoes() = general(phone = true) { repo ->
        assertPresent(str(Res.string.voice_section))
        assertEquals(VoiceAfterDictation.COMPOSE, repo.voiceAfterDictation.value)
        row().performScrollTo()
        assertTrue(present(str(Res.string.settings_after_dictation_compose)), "the row reads its current value: the default")

        row().performClick()
        waitForIdle()
        option(VoiceAfterDictation.COMPOSE).assertIsSelectedNode()
        assertPresent(str(Res.string.settings_after_dictation_compose_sub))
        assertPresent(str(Res.string.settings_after_dictation_send_sub))
        assertFalse(disclosureUp(), "the choice comes first")
    }

    @Test
    fun choosingSendFirstShowsTheDisclosureAndNotNowKeepsTheComposer() = general(phone = true) { repo ->
        pick(VoiceAfterDictation.SEND)
        assertTrue(disclosureUp(), "\"Correct and send\" before the disclosure opens it instead")
        listOf(Res.string.voice_refine_disclosure_data, Res.string.voice_refine_disclosure_swaps, Res.string.voice_refine_disclosure_off)
            .forEach { assertPresent(str(it)) }
        assertEquals(VoiceAfterDictation.COMPOSE, repo.voiceAfterDictation.value, "nothing changed yet")

        button(str(Res.string.voice_refine_disclosure_later)).performClick()
        waitForIdle()
        assertFalse(disclosureUp())
        assertEquals(VoiceAfterDictation.COMPOSE, repo.voiceAfterDictation.value)
        assertFalse(repo.voiceRefineAcked.value)

        // the scrim is "not now" too: no other way through to SEND
        pick(VoiceAfterDictation.SEND)
        assertTrue(disclosureUp())
        onRoot().performTouchInput { click(Offset(centerX, 24f)) }
        waitForIdle()
        assertFalse(disclosureUp())
        assertEquals(VoiceAfterDictation.COMPOSE, repo.voiceAfterDictation.value)
        assertFalse(repo.voiceRefineAcked.value)
    }

    @Test
    fun turnOnAcknowledgesAndSetsSend() = general(phone = true) { repo ->
        pick(VoiceAfterDictation.SEND)
        button(str(Res.string.voice_refine_disclosure_on)).performClick()
        waitForIdle()
        assertFalse(disclosureUp())
        assertTrue(repo.voiceRefineAcked.value, "the acknowledgement is recorded on this device")
        assertEquals(VoiceAfterDictation.SEND, repo.voiceAfterDictation.value)
        row().performScrollTo()
        assertTrue(present(str(Res.string.settings_after_dictation_send)), "the row reads the new value")

        // once acknowledged, both directions are one tap and the disclosure never comes back
        pick(VoiceAfterDictation.COMPOSE)
        assertEquals(VoiceAfterDictation.COMPOSE, repo.voiceAfterDictation.value)
        pick(VoiceAfterDictation.SEND)
        assertFalse(disclosureUp())
        assertEquals(VoiceAfterDictation.SEND, repo.voiceAfterDictation.value)
    }

    @Test
    fun aStoredSendWithoutTheAcknowledgementReadsAsTheComposer() {
        SecureStore.putString(PocketRepository.K_VOICE_AFTER_DICTATION, "send") // never acknowledged on this device
        general(phone = true) { repo ->
            assertEquals(VoiceAfterDictation.SEND, repo.voiceAfterDictation.value)
            row().performScrollTo()
            assertTrue(present(str(Res.string.settings_after_dictation_compose)), "what ✓ actually does: today's bar")
            assertFalse(present(str(Res.string.settings_after_dictation_send)))
            pick(VoiceAfterDictation.SEND)
            assertTrue(disclosureUp(), "turning it on still goes through the disclosure")
        }
    }

    /** The smallest phone at double type: the three points outgrow the screen, so they scroll — the two answers stay. */
    @Test
    fun theDisclosureKeepsBothAnswersOnScreenAtDoubleType() = runDesktopComposeUiTest(320, 568) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                PocketTheme { Box(Modifier.fillMaxSize()) { VoiceRefineDisclosureSheet(onTurnOn = {}, onNotNow = {}) } }
            }
        }
        waitForIdle()
        listOf(Res.string.voice_refine_disclosure_on, Res.string.voice_refine_disclosure_later).forEach {
            val b = button(str(it)).getUnclippedBoundsInRoot()
            assertTrue(b.top.value >= -0.5f && b.bottom.value <= 568.5f, "\"${str(it)}\" stays on screen: $b")
        }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertIsSelectedNode() =
        assertTrue(isSelected().matches(fetchSemanticsNode()), "the current value is the selected choice")
}
