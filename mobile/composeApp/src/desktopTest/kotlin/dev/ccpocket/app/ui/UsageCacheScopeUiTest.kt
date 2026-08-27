package dev.ccpocket.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.ccpocket.app.assertPresent
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.usage_cache
import dev.ccpocket.app.resources.usage_cache_ratio
import dev.ccpocket.app.resources.usage_scope_all_agents
import dev.ccpocket.app.resources.usage_scope_no_filter
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Usage
import dev.ccpocket.protocol.UsageDay
import dev.ccpocket.protocol.UsageModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * The cache-hit card must say WHAT its percentage covers (issue #323).
 *
 * The reported symptom was "cache hit sits at 50–60% while my own tooling says 90%+" — and re-running the
 * daemon's own arithmetic over real transcripts put Claude at 99% and Codex at 96%, so nothing was being
 * mis-parsed. What was actually broken is that the page aggregates every backend it scanned and never named
 * the mix, so a number dominated by a cache-poor backend was indistinguishable from a parsing bug. These
 * pin the two things that make it falsifiable: the label always carries the agent dimension (including the
 * explicit "all agents"), and the raw numerator/denominator ride underneath so the percentage can be
 * recomputed by hand. Neither may quietly disappear.
 */
@OptIn(ExperimentalTestApi::class)
class UsageCacheScopeUiTest {

    /** A span-1 (Today) reply — no hours, so no trend block, leaving the stat cards as the whole page.
     *  1.2M / 1.3M is 92%, so the printed percentage and the printed ratio agree by construction. */
    private fun snapshot(rawTokens: Boolean = true, windowFields: Boolean = true) = Usage(
        days = listOf(UsageDay("Thu", 1_300_000, date = "2026-08-27")),
        models = listOf(UsageModel("claude-opus-5", 1_300_000, AgentKind.CLAUDE)),
        tokensToday = 1_300_000,
        requestsToday = 12,
        cacheHitPct = 92,
        requestsWindow = if (windowFields) 12 else null,
        cacheHitPctWindow = if (windowFields) 92 else null,
        cacheReadTokensWindow = if (rawTokens) 1_200_000 else null,
        cacheBaseTokensWindow = if (rawTokens) 1_300_000 else null,
    )

    /** A repo already holding [usage] as the ALL-agents reply, as if one fetch had landed. */
    private fun repoWith(usage: Usage, filterable: Boolean) =
        PocketRepository(CoroutineScope(Dispatchers.Unconfined)).also {
            it.usage.value = usage
            it.daemonUsageAgentFilter.value = filterable
        }

    /** Renders the page exactly as the phone route does, with the daemon capabilities the test cares about. */
    private fun harness(repo: PocketRepository): @androidx.compose.runtime.Composable () -> Unit = {
        PocketTheme { UsageScreen(repo, onBack = {}) }
    }

    private fun harness(usage: Usage, filterable: Boolean) = harness(repoWith(usage, filterable))

    /** The unfiltered view is the DEFAULT, so it is the one that most needs naming itself — an unlabeled
     *  aggregate is exactly what made the reported number impossible to argue with. */
    @Test
    fun unfilteredCacheCardNamesTheAggregateAndItsRawTokens() = runDesktopComposeUiTest(402, 874) {
        mainClock.autoAdvance = false
        setContent(harness(snapshot(), filterable = true))
        waitForIdle()

        assertPresent(str(Res.string.usage_scope_all_agents), substring = true)
        assertPresent(str(Res.string.usage_cache_ratio, "1.2M", "1.3M"), substring = true)
        // a daemon that CAN filter must not also apologize for not filtering
        assertFalse(present(str(Res.string.usage_scope_no_filter), substring = true))
    }

    /**
     * The label follows the REPLY, not the chip. Tapping an agent re-fetches, but the page deliberately
     * keeps the previous reply on screen meanwhile — so between tap and reply the card still holds the
     * all-agents numbers, and must still say so. A label wired to the selection would spend that whole
     * window putting one agent's name over another agent's number, which is the very confusion #323 is
     * about, just faster.
     */
    @Test
    fun theLabelWaitsForTheReplyInsteadOfFollowingTheChip() = runDesktopComposeUiTest(402, 874) {
        mainClock.autoAdvance = false
        val repo = repoWith(snapshot(), filterable = true)
        setContent(harness(repo))
        waitForIdle()

        onAllNodes(hasText(agentName(AgentKind.CODEX))).onFirst().performClick()
        waitForIdle()

        // fetch in flight: the numbers on screen are still the aggregate, so the label still is too
        assertPresent(str(Res.string.usage_scope_all_agents), substring = true)
        assertFalse(present(agentName(AgentKind.CODEX) + " ·", substring = true))

        // …and once Codex's own reply lands, the card is relabelled with it
        repo.receiveForTest(snapshot())
        waitForIdle()

        assertPresent("${str(Res.string.usage_cache)} · today · ${agentName(AgentKind.CODEX)}", substring = true)
        assertFalse(present(str(Res.string.usage_scope_all_agents), substring = true))
    }

    /** The worst case: the daemon ignores the filter, so the chip row is hidden and the reader cannot even
     *  probe the scope by tapping. The card has to state the scope in prose or nothing on the page does. */
    @Test
    fun aDaemonThatCannotFilterSaysSoUnderTheCard() = runDesktopComposeUiTest(402, 874) {
        mainClock.autoAdvance = false
        setContent(harness(snapshot(), filterable = false))
        waitForIdle()

        assertPresent(str(Res.string.usage_scope_no_filter), substring = true)
        assertPresent(str(Res.string.usage_scope_all_agents), substring = true)
    }

    /** An old daemon sends no accumulators. The line is dropped rather than faked — a ratio invented on the
     *  client would be the same unverifiable number the issue is about, with extra confidence. */
    @Test
    fun anOldDaemonWithoutRawTokensDrawsNoRatioLine() = runDesktopComposeUiTest(402, 874) {
        mainClock.autoAdvance = false
        setContent(harness(snapshot(rawTokens = false, windowFields = false), filterable = true))
        waitForIdle()

        assertPresent("92%", substring = true) // the percentage itself still renders, from the today fields
        assertFalse(present(str(Res.string.usage_cache_ratio, "1.2M", "1.3M"), substring = true))
        assertFalse(present("tokens", substring = true), "no half-built traceability line")
    }
}
