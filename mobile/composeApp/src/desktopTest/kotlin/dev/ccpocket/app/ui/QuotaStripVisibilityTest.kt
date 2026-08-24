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
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.theme.PocketTheme
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
}
