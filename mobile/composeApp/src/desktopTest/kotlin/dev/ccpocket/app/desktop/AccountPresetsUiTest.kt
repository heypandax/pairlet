package dev.ccpocket.app.desktop

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.ccpocket.app.assertPresent
import dev.ccpocket.app.present
import dev.ccpocket.app.theme.PocketTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Settings ▸ Account — API presets (issue #113). Renders the real pane against [SeedDesktopModel]
 * (canned PresetsState, the exact daemon reply shape) and drives the flows: masked truth card,
 * row activation, the write-only token form, and the old-daemon gate that hides the form entirely.
 */
@OptIn(ExperimentalTestApi::class)
class AccountPresetsUiTest {

    private fun ComposeUiTest.openAccount(model: SeedDesktopModel) {
        setContent { PocketTheme { SettingsModal(model) {} } }
        onAllNodes(hasText("Account")).onFirst().performClick()
        waitForIdle()
    }

    @Test
    fun accountPaneShowsMaskedPresetTruthAndList() = runComposeUiTest {
        openAccount(SeedDesktopModel())
        // authentication card = the ACTIVE preset's truth, masked (design 1a)
        assertPresent("API key")
        assertPresent("preset · Work proxy")
        assertPresent("sk-…••••3f9a", substring = true)      // the mask is all that ever renders
        assertPresent("https://api.example-proxy.com/v1")     // base URL stays plaintext by design
        assertPresent("model → gpt-4o · fast → gpt-4o-mini")  // model routing line
        // the list: active tag + the other rows with scheme-stripped hosts
        assertPresent("active")
        assertPresent("Personal key")
        assertPresent("api.anthropic.com")
        assertPresent("localhost:11434")
        assertPresent("New preset")
        // the secrets red line, stated on the pane
        assertPresent("Tokens are stored on the computer and never sent back to this app.")
        // settled note names the computer (design 3b)
        assertPresent("New sessions on Lidapeng-MacBook use this preset", substring = true)
    }

    @Test
    fun clickingAnIdleRowActivatesItThroughTheModel() = runComposeUiTest {
        val m = SeedDesktopModel()
        openAccount(m)
        // rows live below the 500dp modal fold — scroll into view or the click lands outside the hit area
        onAllNodes(hasText("Personal key")).onFirst().performScrollTo().performClick()
        waitForIdle()
        assertEquals("pr-2", m.presetsState?.activeId)        // the tap reached activatePreset
        assertPresent("preset · Personal key")                // the auth card re-points (design 3b)
        assertPresent("sk-…••••a71c", substring = true)
    }

    @Test
    fun deactivateReturnsToTheComputersOwnLogin() = runComposeUiTest {
        val m = SeedDesktopModel()
        openAccount(m)
        onAllNodes(hasText("Deactivate")).onFirst().performClick()
        waitForIdle()
        assertEquals(null, m.presetsState?.activeId)
        // with no preset driving, the seeded OAuth account card takes the Authentication slot (design 1c)
        assertPresent("jordan@example.com")
        assertPresent("Switch account…")
        // …and the OAuth-coexistence explainer is NOT shown (presets exist, the list renders instead)
        assertPresent("Work proxy")
    }

    @Test
    fun newPresetFormIsWriteOnlyAndGatesSaveOnValidity() = runComposeUiTest {
        val m = SeedDesktopModel()
        openAccount(m)
        onAllNodes(hasText("New preset")).onFirst().performScrollTo().performClick()
        waitForIdle()
        assertPresent("Base URL")
        assertPresent("Auth token")
        assertPresent("AUTH_TOKEN")                            // the env-var segmented toggle
        assertPresent("API_KEY")
        assertPresent("Model routing")

        // invalid (all blank): Save is disabled — clicking must not create anything
        onAllNodes(hasText("Save preset")).onFirst().performScrollTo().performClick()
        waitForIdle()
        assertEquals(3, m.presetsState?.presets?.size)

        // fill the three required fields (composition order: Name, Base URL, Token)
        onAllNodes(hasSetTextAction())[0].performScrollTo().performTextInput("My proxy")
        onAllNodes(hasSetTextAction())[1].performScrollTo().performTextInput("https://my.proxy.example/v1")
        onAllNodes(hasSetTextAction())[2].performScrollTo().performTextInput("sk-new-abcdef123456")
        waitForIdle()
        onAllNodes(hasText("Save preset")).onFirst().performScrollTo().performClick()
        waitForIdle()

        // saved through the model (masked by the "daemon" side) and back on the list
        assertEquals(4, m.presetsState?.presets?.size)
        assertPresent("My proxy")
        assertPresent("my.proxy.example")
        // the raw token never appears anywhere in the tree
        assertTrue(!present("sk-new-abcdef123456", substring = true), "plaintext token must never render")
    }

    @Test
    fun editFormShowsStoredPlaceholderInsteadOfTheToken() = runComposeUiTest {
        openAccount(SeedDesktopModel())
        // hover the idle row to reveal its actions (design 1a), then enter the edit form
        onAllNodes(hasText("Personal key")).onFirst().performScrollTo().performMouseInput { moveTo(center) }
        waitForIdle()
        onAllNodes(hasText("Edit")).onFirst().performClick()
        waitForIdle()
        assertPresent("Edit preset")
        assertPresent("•••• stored — leave blank to keep")     // never prefilled, blank = keep (design 2b)
        assertPresent("Delete")                                // pinned left in the footer
    }

    @Test
    fun oldDaemonHidesTheTokenFormBehindAnUpdateLine() = runComposeUiTest {
        val m = SeedDesktopModel()
        m.seedPresets(null) // a pre-presets daemon silently drops the fetch — no reply ever comes
        openAccount(m)
        // the virtual clock auto-advances past the 4s fetch timeout during waitForIdle,
        // so the settled "update the daemon" line is what's observable here
        assertPresent("Presets need the computer's daemon updated first.")
        // the create/edit entry point is GONE: a plaintext token can never be fired at an old daemon
        assertTrue(!present("New preset"), "form entry must be hidden for a pre-presets daemon")
    }
}
