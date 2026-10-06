package dev.ccpocket.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.settings_cat_agent
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.CODEX_MODEL_IDS
import dev.ccpocket.protocol.FetchModels
import dev.ccpocket.protocol.ModelsList
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SessionLive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Codex keeps a static model fallback, so a missing model cache never empties its list: the daemon sends the
 * built-in trio WITH a sentence saying so ([ModelsList.error]). Without that sentence on screen the trio is
 * indistinguishable from the user's real catalog — "is my model list trimmed?".
 *
 * Pinned here: the one rule for when the sentence applies ([codexCatalogNote]), and every phone surface that
 * lists Codex models for picking. Each surface is checked both ways, because a note that is always there is
 * as useless as one that never is. The desktop new-session popover's half is
 * [dev.ccpocket.app.desktop.CodexCatalogNotePopoverTest].
 */
@OptIn(ExperimentalTestApi::class)
class CodexCatalogNoteTest {

    /** The daemon's sentence for a missing cache (CodexModelService), with a concrete path filled in. */
    private val note = "Codex model cache not found at /Users/alex/.codex/models_cache.json — showing built-in " +
        "models only. Run the Codex CLI once on this computer to refresh it."

    /** What the daemon sends for Codex: the built-in ids, with [error] when that is all it has. */
    private fun codexList(error: String? = note) =
        ModelsList(agent = AgentKind.CODEX, models = CODEX_MODEL_IDS, error = error)

    private fun account() = PairedDaemon(
        relay = "wss://test.invalid", accountId = "acct-codex-note", daemonPub = "pub",
        deviceId = "dev", credential = "cred", hostName = "alex-macbook",
    )

    @Test
    fun theNoteIsTheDaemonSentenceForCodexAndNothingElse() {
        assertEquals(note, codexCatalogNote(AgentKind.CODEX, codexList()))
        assertNull(codexCatalogNote(AgentKind.CODEX, codexList(error = null)), "a real catalog has nothing to explain")
        assertNull(codexCatalogNote(AgentKind.CODEX, null), "no reply yet is not a fallback")
        // every other agent keeps its own error surface ([modelCatalogNotice], OpenCode's line) — never this one
        for (agent in AgentKind.entries - AgentKind.CODEX) {
            assertNull(codexCatalogNote(agent, ModelsList(agent = agent, error = "catalog failed")), "$agent")
        }
    }

    // ── the in-session picker (a87ea5c6): the line the other surfaces now share must still render here ──

    private fun inSessionPicker(list: ModelsList, assertions: SkikoComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(402, 874) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                    val scope = rememberCoroutineScope()
                    val repo = remember {
                        PocketRepository(scope, account()).apply {
                            onSendForTest = { frame ->
                                if (frame is FetchModels && frame.agent == AgentKind.CODEX) receiveForTest(list)
                            }
                            receiveForTest(
                                SessionLive(
                                    convoId = "c-codex", workdir = "/Users/alex/code/cc-pocket", sessionId = "s1",
                                    mode = PermissionMode.DEFAULT, executing = false, model = CODEX_MODEL_IDS.first(),
                                    agent = AgentKind.CODEX,
                                ),
                            )
                            receiveForTest(list)
                        }
                    }
                    PocketTheme { ModelPicker(repo, onBack = null, onDone = {}) }
                }
            }
            waitForIdle()
            assertions()
        }

    @Test
    fun theInSessionPickerPrintsTheNoteForTheBuiltInFallback() = inSessionPicker(codexList()) {
        assertTrue(present(note))
    }

    @Test
    fun theInSessionPickerPrintsNothingForARealCatalog() = inSessionPicker(codexList(error = null)) {
        assertTrue(present(CODEX_MODEL_IDS.last()), "the Codex rows render")
        assertFalse(present(note))
    }

    // ── the new-session sheet ──

    /** Through [StartSessionModeSheet], the entry both App.kt call sites use, wired the way they wire it:
     *  rows and note off the same per-agent daemon answer. */
    private fun newSessionSheet(list: ModelsList, assertions: SkikoComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(430, 1000) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                    val scope = rememberCoroutineScope()
                    val repo = remember { PocketRepository(scope, account()).apply { receiveForTest(list) } }
                    PocketTheme {
                        StartSessionModeSheet(
                            workdir = "~/code/cc-pocket", agent = AgentKind.CODEX, computer = "alex-macbook",
                            availableAgents = listOf(AgentKind.CLAUDE, AgentKind.CODEX),
                            modelsFor = { a -> repo.newSessionModelChoices(a) },
                            modelsNoteFor = { a -> codexCatalogNote(a, repo.modelListFor(a)) },
                            onPick = { _, _, _, _, _ -> }, onDismiss = {},
                        )
                    }
                }
            }
            waitForIdle()
            assertions()
        }

    @Test
    fun theNewSessionSheetPrintsTheNoteForTheChosenAgentOnly() = newSessionSheet(codexList()) {
        assertTrue(present(note))
        // the note describes the rows on screen: switch the chip to Claude and there is nothing to explain
        onAllNodes(hasText("Claude")).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        assertFalse(present(note))
    }

    @Test
    fun theNewSessionSheetPrintsNothingForARealCatalog() = newSessionSheet(codexList(error = null)) {
        assertTrue(present(CODEX_MODEL_IDS.last()), "the Codex rows render")
        assertFalse(present(note))
    }

    // ── Settings ▸ Agent & session defaults ──

    private fun settingsDefaults(list: ModelsList, assertions: SkikoComposeUiTest.() -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = PocketRepository(scope, account()).apply {
            onSendForTest = { frame ->
                if (frame is FetchModels && frame.agent == AgentKind.CODEX) receiveForTest(list)
            }
            setDefaultAgent(AgentKind.CODEX)
            receiveForTest(list)
        }
        try {
            runDesktopComposeUiTest(402, 874) {
                setContent { PocketTheme { SettingsScreen(repo, onBack = {}) } }
                waitForIdle()
                onAllNodes(hasText(str(Res.string.settings_cat_agent))).onFirst().performClick()
                waitForIdle()
                assertions()
            }
        } finally {
            // the default agent is persisted, so it outlives this repo — hand the next test the usual Claude
            repo.setDefaultAgent(AgentKind.CLAUDE)
            scope.cancel()
        }
    }

    @Test
    fun settingsPrintsTheNoteUnderTheDefaultModelRowsWhileCodexIsTheDefault() = settingsDefaults(codexList()) {
        assertTrue(present(note))
        // the section follows the default agent; Claude's default-model rows have nothing to explain
        onAllNodes(hasText("Claude") and hasClickAction()).onFirst().performClick()
        waitForIdle()
        assertFalse(present(note))
    }

    @Test
    fun settingsPrintsNothingForARealCatalog() = settingsDefaults(codexList(error = null)) {
        assertTrue(present(CODEX_MODEL_IDS.last()), "the Codex default-model rows render")
        assertFalse(present(note))
    }
}
