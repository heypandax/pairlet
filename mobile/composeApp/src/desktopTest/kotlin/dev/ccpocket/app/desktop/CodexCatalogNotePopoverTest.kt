package dev.ccpocket.app.desktop

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.label_model
import dev.ccpocket.app.resources.new_path_start
import dev.ccpocket.app.resources.settings_default_model
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.ui.agentName
import dev.ccpocket.app.ui.modelChoicesFor
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.CODEX_MODEL_IDS
import dev.ccpocket.protocol.ModelsList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The desktop new-session popover's half of [dev.ccpocket.app.ui.CodexCatalogNoteTest]: Codex's built-in trio
 * arrives WITH the daemon's "cache not found" sentence, and the popover must print it under the model row —
 * otherwise those three rows read as the user's whole catalog.
 *
 * Fed through a real [RepoDesktopModel] and wired the way DesktopApp wires the popover, so what is pinned is
 * the path from the daemon answer to the screen, not a hand-fed string.
 */
@OptIn(ExperimentalTestApi::class)
class CodexCatalogNotePopoverTest {

    private val note = "Codex model cache not found at /Users/alex/.codex/models_cache.json — showing built-in " +
        "models only. Run the Codex CLI once on this computer to refresh it."

    private fun popover(error: String?, assertions: SkikoComposeUiTest.() -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repo = PocketRepository(scope)
            repo.receiveForTest(ModelsList(agent = AgentKind.CODEX, models = CODEX_MODEL_IDS, error = error))
            // FakeDesktopStore: never touch the developer's real store file from tests (issue #102)
            val model = RepoDesktopModel(repo, scope, store = FakeDesktopStore())
            runDesktopComposeUiTest(420, 1400) {
                setContent {
                    PocketTheme {
                        NewSessionPopover(
                            initialPath = "/w/proj",
                            defaultAgent = AgentKind.CODEX,
                            availableAgents = listOf(AgentKind.CLAUDE, AgentKind.CODEX),
                            modelsFor = { a -> modelChoicesFor(a, model.modelsForAgent(a)) },
                            modelsNoteFor = { a -> model.modelsNoteForAgent(a) },
                            onStart = { _, _, _, _, _, _ -> },
                        )
                    }
                }
                waitForIdle()
                assertions()
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun theBuiltInFallbackSaysSoUnderTheModelRow() = popover(error = note) {
        assertTrue(present(note))
        // the note follows the agent picked INSIDE the popover — Claude's rows have nothing to explain
        onAllNodes(hasText(agentName(AgentKind.CLAUDE))).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        assertFalse(present(note))
    }

    @Test
    fun aRealCodexCatalogPrintsNothing() = popover(error = null) {
        assertTrue(present(str(Res.string.new_path_start)), "the popover rendered")
        assertFalse(present(note))
    }

    // ── the two desktop surfaces that only READ the model: in-session popover and the settings modal ──

    /** The seed model, with Codex's list being the built-in fallback. */
    private fun fallbackModel() = object : SeedDesktopModel() {
        override fun modelsNoteForAgent(agent: AgentKind): String? = if (agent == AgentKind.CODEX) note else null
    }

    @Test
    fun theInSessionModelPopoverSaysSoInACodexChat() = runDesktopComposeUiTest(420, 900) {
        val m = fallbackModel().apply { selectSession(sessions[2]) } // the seed's Codex session
        setContent { PocketTheme { ModelPopover(m) {} } }
        waitForIdle()
        assertTrue(m.chatAgent == AgentKind.CODEX, "precondition: a Codex chat")
        assertTrue(present(note))
    }

    @Test
    fun theInSessionModelPopoverPrintsNothingInAClaudeChat() = runDesktopComposeUiTest(420, 900) {
        val m = fallbackModel()
        setContent { PocketTheme { ModelPopover(m) {} } }
        waitForIdle()
        assertTrue(m.chatAgent == AgentKind.CLAUDE, "precondition: a Claude chat")
        assertTrue(present(str(Res.string.label_model).uppercase()), "the popover rendered") // its label is set in caps
        assertFalse(present(note))
    }

    @Test
    fun desktopSettingsSaysSoWhileCodexIsTheDefaultAgent() = runDesktopComposeUiTest(900, 2400) {
        val m = fallbackModel().apply { defaultAgent = AgentKind.CODEX }
        setContent { PocketTheme { SettingsModal(m) {} } }
        waitForIdle()
        assertTrue(present(str(Res.string.settings_default_model)), "the default-model group rendered")
        assertTrue(present(note))
    }

    @Test
    fun desktopSettingsPrintsNothingWhileClaudeIsTheDefaultAgent() = runDesktopComposeUiTest(900, 2400) {
        val m = fallbackModel().apply { defaultAgent = AgentKind.CLAUDE }
        setContent { PocketTheme { SettingsModal(m) {} } }
        waitForIdle()
        assertTrue(present(str(Res.string.settings_default_model)), "the default-model group rendered")
        assertFalse(present(note))
    }
}
