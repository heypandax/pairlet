package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.new_session_cta
import dev.ccpocket.app.resources.new_session_subtitle
import dev.ccpocket.app.resources.help_ask_support
import dev.ccpocket.app.resources.support_title
import dev.ccpocket.app.theme.PocketTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun newSessionArmsComposerAutoFocusResumeDoesNot() = runComposeUiTest {
        val repo = composeSessionsScreen()
        val cta = runBlocking { getString(Res.string.new_session_cta) }
        onAllNodes(hasText(cta)).onFirst().performClick()
        waitForIdle()
        // ChatScreen consumes this to focus the composer + raise the keyboard on first landing
        assertTrue(repo.autoFocusComposer.value, "a brand-new session must arm the composer auto-focus")
        repo.openSession(dir, resumeId = "sess-resume")
        waitForIdle()
        assertFalse(repo.autoFocusComposer.value, "resuming an existing session must not pop the keyboard")
    }

    /**
     * #178: the three-up agent picker's longest name ("OpenCode") must never wrap to "OpenCod / e" nor
     * ellipsize — [AutoSizeSingleLineText] shrinks the font to fit its column. Three width ceilings pin
     * the contract: a too-narrow one shrinks the name below the base size yet keeps it one line and
     * overflow-free; a roomy one leaves the base 15.5sp untouched; a hopeless sliver bottoms out at the
     * floor and still stays a single line (never wraps). Floors here are chosen to make the fit
     * deterministic regardless of the headless JVM's font metrics; the real call sites floor at 9sp.
     */
    @Test
    fun openCodeNameAutoSizesToOneLine() = runComposeUiTest {
        var narrow: TextLayoutResult? = null
        var roomy: TextLayoutResult? = null
        var floored: TextLayoutResult? = null
        setContent {
            PocketTheme {
                Column {
                    AutoSizeSingleLineText("OpenCode", 15.5.sp, 6.sp, modifier = Modifier.width(44.dp), onTextLayout = { narrow = it })
                    AutoSizeSingleLineText("OpenCode", 15.5.sp, 9.sp, modifier = Modifier.width(240.dp), onTextLayout = { roomy = it })
                    AutoSizeSingleLineText("OpenCode", 15.5.sp, 11.sp, modifier = Modifier.width(12.dp), onTextLayout = { floored = it })
                }
            }
        }
        waitForIdle()
        val n = assertNotNull(narrow, "narrow card must have laid out")
        assertEquals(1, n.lineCount, "OpenCode must stay a single line, never wrap to \"OpenCod / e\"")
        assertFalse(n.hasVisualOverflow, "the font must shrink until OpenCode fully fits — no clip, no ellipsis")
        assertTrue(n.layoutInput.style.fontSize.value < 15.5f, "a too-narrow card must shrink the name below the base size")
        val r = assertNotNull(roomy, "roomy card must have laid out")
        assertEquals(15.5f, r.layoutInput.style.fontSize.value, "a card with room keeps the base 15.5sp")
        val f = assertNotNull(floored, "floored card must have laid out")
        assertEquals(11f, f.layoutInput.style.fontSize.value, "shrinking stops at the readable floor, never below")
        assertEquals(1, f.lineCount, "even past the floor it stays one line — softWrap is off")
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

    @Test
    fun helpIconOpensTheNativeTaskCatalog() = runComposeUiTest {
        composeSessionsScreen()
        onNodeWithContentDescription(runBlocking { getString(Res.string.support_title) }).performClick()
        waitForIdle()
        assertTrue(
            present(runBlocking { getString(Res.string.help_ask_support) }),
            "the one-tap help entry must open native task learning before handing off to the web",
        )
    }
}
