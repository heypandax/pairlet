package dev.ccpocket.app.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.CLAUDE_QUOTA_OK
import dev.ccpocket.protocol.CLAUDE_QUOTA_SEVERITY_NORMAL
import dev.ccpocket.protocol.ClaudeQuota
import dev.ccpocket.protocol.ClaudeQuotaLimit
import kotlin.test.Test

/**
 * Repro harness for "opening a project crashes on the phone" (2026-08-25): SessionsScreen had never been
 * composed WITH a quota snapshot in any test — on a real phone the strip is always present. Renders the
 * session list exactly as the phone does (demo sessions + strip + dock) and fails on any thrown exception.
 */
@OptIn(ExperimentalTestApi::class)
class SessionsScreenQuotaCrashTest {

    private val dir = "/Users/alex/code/relay-server"

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
    fun sessionListComposesWithTheQuotaStripPresent() = runDesktopComposeUiTest(402, 874) {
        mainClock.autoAdvance = false
        setContent {
            val scope = rememberCoroutineScope()
            val repo = remember {
                PocketRepository(scope).also {
                    it.enterDemo()
                    it.listSessions(dir)
                    it.claudeQuota.value = snapshot()
                }
            }
            PocketTheme { SessionsScreen(repo) }
        }
        waitForIdle()
        onAllNodes(hasText("5h", substring = true)).onFirst().assertExists()
    }
}
