package dev.ccpocket.app.ui.entry

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.cfg_preset
import dev.ccpocket.app.resources.cfg_preset_custom
import dev.ccpocket.app.resources.cfg_preset_follow
import dev.ccpocket.app.resources.cfg_start
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.AgentPresetInfo
import dev.ccpocket.protocol.PermissionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #333 — the new-session AGENT PRESET row.
 *
 * The contract worth pinning is not the layout, it is the DEGRADATION: the row appears only when the
 * connected daemon advertised presets for the agent on screen. A daemon that never sent them also never
 * reads [dev.ccpocket.protocol.OpenSession.agentPreset] back, so a picker shown against one would let the
 * user make a choice that is silently dropped on the wire and never takes effect — the session then runs
 * a different persona than the sheet said it would, with nothing on screen admitting it.
 */
@OptIn(ExperimentalTestApi::class)
class AgentPresetRowTest {

    private val presets = listOf(
        AgentPresetInfo("standard", "Standard", "Full coding agent.", recommended = true),
        AgentPresetInfo("minimal", "Minimal", "bash + str_replace_editor only."),
        AgentPresetInfo("mine", "My preset", "Authored locally.", custom = true),
    )

    private fun sheet(
        agent: AgentKind = AgentKind.DSH,
        advertised: List<AgentPresetInfo> = presets,
        onPicked: (PermissionMode, AgentKind, String?, String?, String?) -> Unit = { _, _, _, _, _ -> },
        assertions: SkikoComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(430, 1000) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                PocketTheme {
                    ConfigureSessionSheet(
                        workdir = "~/code/cc-pocket", agent = agent, computer = "alex-macbook",
                        availableAgents = listOf(AgentKind.CLAUDE, AgentKind.DSH),
                        agentPresetsFor = { a -> if (a == AgentKind.DSH) advertised else emptyList() },
                        onPick = onPicked, onDismiss = {},
                    )
                }
            }
        }
        waitForIdle()
        assertions()
    }

    @Test
    fun an_advertised_catalogue_renders_the_preset_rows_with_the_backend_own_copy() = sheet {
        onAllNodes(hasText(str(Res.string.cfg_preset), substring = true)).onFirst().assertExists()
        // labels and details are the BACKEND's, verbatim — a preset this build never heard of still reads
        onAllNodes(hasText("Standard")).onFirst().assertExists()
        onAllNodes(hasText("bash + str_replace_editor only.")).onFirst().assertExists()
        // "mine" versus "shipped" is the one distinction the label alone does not carry
        onAllNodes(hasText(str(Res.string.cfg_preset_custom))).onFirst().assertExists()
    }

    /** THE degradation contract. An empty catalogue is "not advertised", and the row must not exist. */
    @Test
    fun an_unadvertised_agent_shows_no_preset_row_at_all() = sheet(advertised = emptyList()) {
        assertEquals(
            0,
            onAllNodes(hasText(str(Res.string.cfg_preset), substring = true)).fetchSemanticsNodes().size,
            "a daemon that never advertised presets would silently drop the choice",
        )
    }

    /** …and neither does an agent whose daemon answer carries none, even while a sibling agent's does. */
    @Test
    fun switching_to_an_agent_without_presets_removes_the_row() = sheet {
        onAllNodes(hasText(str(Res.string.cfg_preset), substring = true)).onFirst().assertExists()
        onAllNodes(hasText("Claude")).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        assertEquals(
            0,
            onAllNodes(hasText(str(Res.string.cfg_preset), substring = true)).fetchSemanticsNodes().size,
        )
    }

    /**
     * "Follow the computer's default" is the initial selection and rides out as NULL. The backend already
     * has a default; pre-selecting a named row would send an explicit choice the user never made.
     */
    @Test
    fun the_default_row_leads_and_starting_untouched_sends_no_preset() {
        var picked: String? = "sentinel"
        sheet(onPicked = { _, _, _, _, preset -> picked = preset }) {
            onAllNodes(hasText(str(Res.string.cfg_preset_follow))).onFirst().assertExists()
            onAllNodes(hasText(str(Res.string.cfg_start), substring = true)).onFirst().performClick()
            waitForIdle()
        }
        assertNull(picked, "an untouched preset row must not invent a choice")
    }

    @Test
    fun choosing_a_preset_rides_out_on_start() {
        var picked: String? = null
        var agent: AgentKind? = null
        sheet(onPicked = { _, a, _, _, preset -> agent = a; picked = preset }) {
            onAllNodes(hasText("Minimal")).onFirst().performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
            onAllNodes(hasText(str(Res.string.cfg_start), substring = true)).onFirst().performClick()
            waitForIdle()
        }
        assertEquals("minimal", picked, "the BACKEND's id travels, never the display label")
        assertEquals(AgentKind.DSH, agent)
    }

    /**
     * Switching agent must clear the pick, exactly like the model above it: a dsh preset id means nothing
     * to Claude, and carrying it across would send one backend's vocabulary to another.
     */
    @Test
    fun switching_agent_clears_a_chosen_preset() {
        var picked: String? = "sentinel"
        sheet(onPicked = { _, _, _, _, preset -> picked = preset }) {
            onAllNodes(hasText("Minimal")).onFirst().performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
            onAllNodes(hasText("Claude")).onFirst().performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
            onAllNodes(hasText(str(Res.string.cfg_start), substring = true)).onFirst().performClick()
            waitForIdle()
        }
        assertNull(picked)
    }

    /** The desktop popover's rows come off the same daemon answer, through the same "empty = no row" gate. */
    @Test
    fun the_desktop_seed_model_advertises_dsh_presets_and_nothing_else() {
        val seed = dev.ccpocket.app.desktop.SeedDesktopModel()
        assertTrue(seed.agentPresetsForAgent(AgentKind.DSH).isNotEmpty())
        assertTrue(seed.agentPresetsForAgent(AgentKind.CLAUDE).isEmpty())
    }
}
