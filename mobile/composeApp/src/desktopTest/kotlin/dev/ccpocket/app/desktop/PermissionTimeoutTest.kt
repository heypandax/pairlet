package dev.ccpocket.app.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.ccpocket.app.assertPresent
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.present
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.theme.ThemeMode
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.PermissionAsk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The desktop approval card's TIMED_OUT terminal state (issue #100 follow-up). Covers the two halves of the
 * gap: the inline [InlinePermCard] flips to a dismiss-only "auto-denied" state (and a late click sends no
 * verdict), and [RepoDesktopModel] forwards the repo's `timedOutAskId` signal to the card while retiring the
 * now-dead ask from the attention bell — the parallel surface that would otherwise still offer Allow/Deny.
 */
@OptIn(ExperimentalTestApi::class)
class PermissionTimeoutTest {

    private val cmdAsk = PermissionAsk(
        convoId = "c1", askId = "ask-1", tool = "Bash",
        inputPreview = "rm -rf ./build && ./gradlew clean", title = "Run command",
    )

    // ── the inline card flips to its terminal state and offers no verdict ─────────────────────────
    @Test
    fun timedOutCardShowsTerminalStateAndHidesDecision() = runComposeUiTest {
        var allowed = false
        var denied = false
        var dismissed = false
        setContent {
            PocketTheme {
                InlinePermCard(
                    cmdAsk, AgentKind.CLAUDE, "~/code/cc-pocket", null,
                    onAllow = { allowed = true }, onDeny = { denied = true },
                    timedOut = true, onDismiss = { dismissed = true },
                )
            }
        }
        // the request stays visible (greyed, not vanished) so the returning user sees what happened…
        assertPresent("rm -rf ./build && ./gradlew clean", substring = true)
        // …under a terminal danger note + a dismiss-only control
        assertPresent("Auto-denied", substring = true)
        assertPresent("Dismiss")
        // Allow/Deny are gone — the flipped card can no longer fire a verdict the CLI stopped waiting for
        assertFalse(present("Allow"), "a timed-out card offers no Allow")
        assertFalse(present("Deny"), "a timed-out card offers no Deny")

        // a late tap: the only live control is Dismiss, and it sends no allow/deny
        onAllNodes(hasText("Dismiss")).onFirst().performClick()
        assertTrue(dismissed, "Dismiss retires the card")
        assertFalse(allowed, "a late click never allows")
        assertFalse(denied, "a late click never denies")
    }

    // ── a live (not-yet-timed-out) card is the ordinary actionable card ───────────────────────────
    @Test
    fun liveCardShowsDecisionButtonsAndNoTerminalNote() = runComposeUiTest {
        setContent {
            PocketTheme {
                InlinePermCard(
                    cmdAsk, AgentKind.CLAUDE, "~/code/cc-pocket", null,
                    onAllow = {}, onDeny = {},
                    timedOut = false, onDismiss = {},
                )
            }
        }
        assertPresent("Allow")
        assertPresent("Deny")
        assertFalse(present("Auto-denied", substring = true), "a live card shows no terminal note")
    }

    // ── the terminal state reads in light mode too (Tok palette is theme-aware) ───────────────────
    @Test
    fun terminalStateRendersInLightTheme() = runComposeUiTest {
        setContent {
            PocketTheme(mode = ThemeMode.LIGHT) {
                InlinePermCard(
                    cmdAsk, AgentKind.CLAUDE, "~/code/cc-pocket", null,
                    onAllow = {}, onDeny = {},
                    timedOut = true, onDismiss = {},
                )
            }
        }
        assertPresent("Auto-denied", substring = true)
        assertPresent("Dismiss")
    }

    // ── the desktop model forwards the repo's timeout signal + retires the dead ask from attention ─
    @Test
    fun repoModelForwardsTimeoutSignalAndClearsAttention() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val repo = PocketRepository(scope)
        repo.paired.value = PairedDaemon(
            relay = "wss://test", accountId = "acct-test", daemonPub = "pk", deviceId = "dev", credential = "cred",
        )
        val model = RepoDesktopModel(repo, scope)
        repo.convoId.value = "c1"
        repo.pendingAsk.value = cmdAsk

        // before the timeout: an ordinary actionable ask — surfaced inline AND on the attention bell
        assertFalse(model.askTimedOut)
        assertNotNull(model.ask)
        assertTrue(model.attention.any { it.id == "ask-1" }, "a live ask is a waiting item")

        // the daemon reports TIMED_OUT: the repo keeps the pendingAsk and stamps timedOutAskId
        repo.timedOutAskId.value = "ask-1"

        assertTrue(model.askTimedOut, "the model forwards the timeout signal to the inline card")
        assertNotNull(model.ask) // the card is KEPT (it flips terminal) — not vanished
        assertTrue(model.attention.none { it.id == "ask-1" }, "a timed-out ask is no longer a waiting item")

        // id-matching, not a sticky flag: a fresh ask is live again even while the old timeout id lingers
        repo.pendingAsk.value = cmdAsk.copy(askId = "ask-2")
        assertFalse(model.askTimedOut, "a stale timeout id can't bleed onto the next ask")
        assertTrue(model.attention.any { it.id == "ask-2" }, "the new ask is a waiting item again")
    }
}
