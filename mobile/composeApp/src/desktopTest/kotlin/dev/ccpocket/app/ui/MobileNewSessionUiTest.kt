package dev.ccpocket.app.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.new_session_cta
import dev.ccpocket.app.resources.new_session_subtitle
import dev.ccpocket.app.theme.PocketTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString

/**
 * The mobile new-session flow, end-to-end through demo mode (no network: demoRespond answers
 * OpenSession synchronously with SessionLive). Guards the one-tap contract: tapping "＋ New session"
 * opens the conversation directly under the persisted defaults — no mode picker in between — while
 * the defaults chip still routes through the full picker. Assertions avoid the default agent/mode
 * VALUES on purpose (they come from the machine's real store and differ per developer), and every
 * matched text resolves via getString — the JVM locale picks the resource language per machine.
 */
@OptIn(ExperimentalTestApi::class)
class MobileNewSessionUiTest {

    // a non-live demo project: tapping its rows never auto-jumps into a running session
    private val dir = "/Users/alex/code/relay-server"

    private fun ComposeUiTest.composeSessionsScreen(): PocketRepository {
        lateinit var repo: PocketRepository
        setContent {
            val scope = rememberCoroutineScope()
            repo = remember { PocketRepository(scope).also { it.enterDemo(); it.listSessions(dir) } }
            PocketTheme { SessionsScreen(repo) }
        }
        waitForIdle()
        return repo
    }

    @Test
    fun oneTapStartsSessionWithDefaults() = runComposeUiTest {
        val repo = composeSessionsScreen()
        val cta = runBlocking { getString(Res.string.new_session_cta) }
        val pickerSubtitle = runBlocking { getString(Res.string.new_session_subtitle) }
        onAllNodes(hasText(cta)).onFirst().performClick()
        waitForIdle()
        assertTrue(!present(pickerSubtitle), "one-tap start must not open the mode picker")
        assertNotNull(repo.convoId.value, "one tap must open the conversation")
        assertEquals(dir, repo.workdir.value)
        assertEquals(repo.defaultMode.value, repo.mode.value) // started under the persisted default mode
    }

    @Test
    fun defaultsChipOpensThePickerWithoutStarting() = runComposeUiTest {
        val repo = composeSessionsScreen()
        // the chip is labeled with the default mode (or Codex preset) — resolve the same resource the UI uses
        val chipLabel = runBlocking { getString(sessionDefaultsLabel(repo.defaultAgent.value, repo.defaultMode.value)) }
        val pickerSubtitle = runBlocking { getString(Res.string.new_session_subtitle) }
        onAllNodes(hasText(chipLabel)).onFirst().performClick()
        waitForIdle()
        assertTrue(present(pickerSubtitle), "the defaults chip must open the full picker")
        assertNull(repo.convoId.value, "opening the picker must not start a session yet")
    }
}
