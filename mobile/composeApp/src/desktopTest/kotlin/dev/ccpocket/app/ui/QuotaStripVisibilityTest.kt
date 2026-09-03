package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.CLAUDE_QUOTA_HTTP
import dev.ccpocket.protocol.CLAUDE_QUOTA_NETWORK
import dev.ccpocket.protocol.CLAUDE_QUOTA_NO_TOKEN
import dev.ccpocket.protocol.CLAUDE_QUOTA_OK
import dev.ccpocket.protocol.CLAUDE_QUOTA_SEVERITY_NORMAL
import dev.ccpocket.protocol.ClaudeQuota
import dev.ccpocket.protocol.ClaudeQuotaLimit
import kotlin.test.Test

/**
 * Repro harness for "the phone shows no strip on the project list": feeds a REAL-shaped daemon
 * snapshot (the exact kinds/groups the live endpoint returns) into the repo and asserts the docked
 * strip actually renders on DirectoryScreen. Separates "UI mount broken" from "data never arrives".
 */
@OptIn(ExperimentalTestApi::class)
class QuotaStripVisibilityTest {

    private fun snapshot() = ClaudeQuota(
        limits = listOf(
            ClaudeQuotaLimit(kind = "session", group = "session", percent = 67, severity = CLAUDE_QUOTA_SEVERITY_NORMAL, resetsAt = 1787572199680, isActive = true),
            ClaudeQuotaLimit(kind = "weekly_all", group = "weekly", percent = 7, severity = CLAUDE_QUOTA_SEVERITY_NORMAL, resetsAt = 1788163200680, isActive = false),
            ClaudeQuotaLimit(kind = "weekly_scoped", group = "weekly", percent = 13, severity = CLAUDE_QUOTA_SEVERITY_NORMAL, resetsAt = 1788163199680, isActive = false, modelDisplayName = "Fable"),
        ),
        fetchedAt = 1787560000000,
        status = CLAUDE_QUOTA_OK,
    )

    @Test
    fun aRealSnapshotRendersTheDockedStripOnTheProjectList() = runDesktopComposeUiTest(402, 874) {
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                val scope = rememberCoroutineScope()
                val repo = remember {
                    PocketRepository(scope).also {
                        it.enterDemo()
                        it.claudeQuota.value = snapshot()
                    }
                }
                PocketTheme(dark = true) { Box(Modifier.fillMaxSize()) { DirectoryScreen(repo) } }
            }
        }
        waitForIdle()
        // brand marker + both window labels must be on screen
        onAllNodes(hasText("Claude", substring = true)).onFirst().assertExists()
        onAllNodes(hasText("5h", substring = true)).onFirst().assertExists()
        onAllNodes(hasText("7d", substring = true)).onFirst().assertExists()
        // …and INSIDE the viewport: the phone bug was a strip that exists in the tree but is laid out
        // below the screen once the populated list lands (skeleton showed it, landing hid it)
        val b = onAllNodes(hasText("5h", substring = true)).onFirst().getUnclippedBoundsInRoot()
        kotlin.test.assertTrue(b.bottom.value <= 874f + 0.5f, "strip laid out below the viewport: bottom=${b.bottom}")
        kotlin.test.assertTrue(b.top.value >= 0f, "strip above the viewport: top=${b.top}")
    }

    @Test
    fun aBareStripRendersAloneWithTheSameSnapshot() = runDesktopComposeUiTest(402, 200) {
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                val scope = rememberCoroutineScope()
                val repo = remember {
                    PocketRepository(scope).also { it.claudeQuota.value = snapshot() }
                }
                PocketTheme(dark = true) { Box(Modifier.fillMaxSize()) { QuotaStrip(repo) {} } }
            }
        }
        waitForIdle()
        onAllNodes(hasText("5h", substring = true)).onFirst().assertExists()
    }

    /** Every "nothing to say" state the daemon can hand us draws NOTHING — no hairline, no band, no
     *  brand marker. The one the phone actually hit in #339 was `status=network` (a transient fetch
     *  failure mid-session), which is why the flavours are pinned one by one rather than only as null. */
    @Test
    fun everyEmptyStateDrawsNothingAtAll() {
        val empties = listOf(
            "no snapshot at all (never fetched / a machine with no Claude auth)" to null,
            "a transient fetch failure" to ClaudeQuota(status = CLAUDE_QUOTA_NETWORK),
            "no token on the machine" to ClaudeQuota(status = CLAUDE_QUOTA_NO_TOKEN),
            "an upstream HTTP error" to ClaudeQuota(status = CLAUDE_QUOTA_HTTP),
            "an ok reply carrying no windows" to ClaudeQuota(status = CLAUDE_QUOTA_OK, limits = emptyList()),
        )
        for ((why, q) in empties) runDesktopComposeUiTest(402, 200) {
            mainClock.autoAdvance = false
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                    val scope = rememberCoroutineScope()
                    val repo = remember { PocketRepository(scope).also { it.claudeQuota.value = q } }
                    PocketTheme(dark = true) { Box(Modifier.fillMaxSize()) { QuotaStrip(repo) {} } }
                }
            }
            waitForIdle()
            kotlin.test.assertTrue(
                onAllNodes(hasText("Claude", substring = true)).fetchSemanticsNodes().isEmpty(),
                "the strip rendered a shell for: $why",
            )
        }
    }

    /**
     * The #339 rule, in the one form a desktop test can hold: the FAB scrim and the FAB stack take
     * DIFFERENT bottom clearances. A phone's home-indicator inset cannot be simulated here (desktop
     * reports none), so the visual band itself is a device check — this pins the arithmetic that drew it.
     */
    @Test
    fun theScrimReachesTheEdgeWhileControlsClearTheInset() {
        // no snapshot: the scrim must fall all the way to the physical edge, the FAB must not
        val absent = bottomLifts(stripHeight = 0.dp, navInset = 34.dp)
        kotlin.test.assertEquals(0.dp, absent.scrim, "the scrim hung above the edge — #339's black band")
        kotlin.test.assertEquals(34.dp, absent.controls, "the FAB stack sat under the home indicator")
        // docked strip: it already contains the inset, so BOTH lifts are its height — the form with a
        // snapshot in hand (owner-settled) must not move
        val docked = bottomLifts(stripHeight = 58.dp, navInset = 34.dp)
        kotlin.test.assertEquals(58.dp, docked.scrim)
        kotlin.test.assertEquals(58.dp, docked.controls)
    }
}
