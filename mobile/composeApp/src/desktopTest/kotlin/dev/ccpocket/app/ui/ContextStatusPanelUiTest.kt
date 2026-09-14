package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import dev.ccpocket.app.resources.label_model
import kotlin.test.assertEquals
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.assertPresent
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.context_status_no_data
import dev.ccpocket.app.resources.context_status_no_data_detail
import dev.ccpocket.app.resources.context_status_used_pending
import dev.ccpocket.app.resources.context_status_window_override
import dev.ccpocket.app.resources.context_status_window_unknown
import dev.ccpocket.app.resources.value_unknown
import dev.ccpocket.app.secure.SecureStore
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.SessionLive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * #320-A, user-visible: the context block tells the user which facts exist. Text is resolved through the
 * resources (the JVM locale decides the language), so these pin behaviour rather than wording.
 */
@OptIn(ExperimentalTestApi::class)
class ContextStatusPanelUiTest {
    private fun s(res: StringResource) = runBlocking { getString(res) }

    private val allNotes = listOf(
        Res.string.context_status_no_data, Res.string.context_status_no_data_detail,
        Res.string.context_status_window_unknown, Res.string.context_status_used_pending,
        Res.string.context_status_window_override,
    )

    @BeforeTest
    fun clearPersistedOverrides() {
        SecureStore.remove(PocketRepository.K_CONTEXT_WINDOW_OVERRIDE)
        SecureStore.remove(PocketRepository.K_CONTEXT_WINDOW_OVERRIDES)
    }

    @Test
    fun completeEvidenceShowsNumbersAndNoCaveat() = runComposeUiTest {
        setContent { PocketTheme { Box(Modifier.width(360.dp)) { ContextStatusPanel(contextStatusUi(84_000, 200_000)) } } }
        waitForIdle()
        assertPresent("~84k / 200k · 42%")
        allNotes.forEach { assertFalse(present(s(it)), "complete data must not carry '${s(it)}'") }
    }

    @Test
    fun windowOnlySaysUsageIsPendingAndShowsNoPercent() = runComposeUiTest {
        setContent { PocketTheme { Box(Modifier.width(360.dp)) { ContextStatusPanel(contextStatusUi(null, 200_000)) } } }
        waitForIdle()
        assertPresent(s(Res.string.context_status_used_pending))
        assertFalse(present("%", substring = true), "a missing numerator must not surface as a percentage")
    }

    @Test
    fun usedOnlySaysTheWindowIsUnknown() = runComposeUiTest {
        setContent { PocketTheme { Box(Modifier.width(360.dp)) { ContextStatusPanel(contextStatusUi(84_000, null)) } } }
        waitForIdle()
        assertPresent("~84k")
        assertPresent(s(Res.string.context_status_window_unknown))
        assertFalse(present("%", substring = true))
    }

    @Test
    fun noEvidenceExplainsInsteadOfGoingBlank() = runComposeUiTest {
        setContent { PocketTheme { Box(Modifier.width(360.dp)) { ContextStatusPanel(contextStatusUi(null, null)) } } }
        waitForIdle()
        assertPresent(s(Res.string.context_status_no_data))
        assertPresent(s(Res.string.context_status_no_data_detail))
    }

    @Test
    fun aHandTypedWindowIsMarkedAsTheUsers() = runComposeUiTest {
        setContent { PocketTheme { Box(Modifier.width(360.dp)) { ContextStatusPanel(contextStatusUi(84_000, 256_000, 256_000)) } } }
        waitForIdle()
        assertPresent(s(Res.string.context_status_window_override))
    }

    // ── the session info sheet actually renders it ────────────────────────────────────────────

    private fun repo(live: SessionLive) = PocketRepository(CoroutineScope(Dispatchers.Unconfined)).apply {
        paired.value = PairedDaemon(relay = "wss://test", accountId = "acct-test", daemonPub = "pk", deviceId = "dev", credential = "cred")
        convoId.value = live.convoId
        receiveForTest(live)
    }

    @Test
    fun sessionInfoForADshSessionWithoutDataSaysSoAndKeepsModelUnknown() = runComposeUiTest {
        val r = repo(SessionLive("c1", "/w", "sid-1", executing = false, agent = AgentKind.DSH))
        setContent { PocketTheme { SessionInfoSheet(r, onDismiss = {}) } }
        waitForIdle()
        assertPresent(s(Res.string.context_status_no_data))
        // scoped to the MODEL row. AboutRow's Row adds no semantics, so every row's label/value texts are
        // siblings under one parent — sibling matching can't isolate a row. The value is the node right after
        // its label, in order. ("default" may legitimately appear on the Effort row.)
        val labelNode = onNode(hasText(s(Res.string.label_model))).fetchSemanticsNode()
        val siblings = labelNode.parent!!.children
        val valueNode = siblings[siblings.indexOfFirst { it.id == labelNode.id } + 1]
        assertEquals(
            s(Res.string.value_unknown),
            valueNode.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text },
            "the model row must read unknown — never Claude's default",
        )
    }

    @Test
    fun sessionInfoMarksAnOverriddenWindow() = runComposeUiTest {
        val r = repo(SessionLive("c1", "/w", "sid-1", executing = false, model = "deepseek-chat", contextWindow = 128_000, contextUsed = 42_000, agent = AgentKind.DSH))
        r.setContextWindowOverrideFor("deepseek-chat", 256_000)
        setContent { PocketTheme { SessionInfoSheet(r, onDismiss = {}) } }
        waitForIdle()
        assertPresent(s(Res.string.context_status_window_override))
    }
}
