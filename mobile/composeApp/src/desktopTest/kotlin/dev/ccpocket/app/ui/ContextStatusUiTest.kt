package dev.ccpocket.app.ui

import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.secure.SecureStore
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.SessionLive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #320-A: the context readout must say WHICH facts it has, never fill a gap with a guess.
 *
 * The four evidence shapes (both / numerator only / denominator only / neither) each get their own
 * wording; null is never 0, a lone numerator never becomes a percentage, and a missing value never
 * turns into "unsupported". These pin the pure projection both shells render from.
 */
class ContextStatusUiTest {

    @BeforeTest
    fun clearPersistedOverrides() {
        SecureStore.remove(PocketRepository.K_CONTEXT_WINDOW_OVERRIDE)
        SecureStore.remove(PocketRepository.K_CONTEXT_WINDOW_OVERRIDES)
    }

    // ── both facts ────────────────────────────────────────────────────────────────────────────

    @Test
    fun bothFactsGiveUsedOfWindowAndTheExistingRatio() {
        val s = contextStatusUi(used = 84_000, window = 200_000)
        assertEquals(ContextStatusKind.USED_OF_WINDOW, s.kind)
        assertEquals(42, s.percent)
        assertEquals(0.42f, s.fraction)
        assertTrue(s.notes.isEmpty(), "complete evidence needs no caveat")
        assertEquals("~84k / 200k · 42%", contextStatusReadout(s))
        assertEquals("ctx 42%", contextStatusMetaSegment(s))
    }

    @Test
    fun zeroUsedIsRealDataNotMissing() {
        val s = contextStatusUi(used = 0, window = 200_000)
        assertEquals(ContextStatusKind.USED_OF_WINDOW, s.kind, "0 is a measured value; only null is unknown")
        assertEquals(0, s.percent)
        assertEquals(0f, s.fraction)
        assertEquals("0 / 200k · 0%", contextStatusReadout(s))
        assertEquals("ctx 0%", contextStatusMetaSegment(s))
    }

    @Test
    fun overflowKeepsTheHeaderRatioButClampsTheBar() {
        // the desktop header always printed u*100/w unclamped; the bar fill can't exceed its track
        val s = contextStatusUi(used = 260_000, window = 200_000)
        assertEquals(130, s.percent)
        assertEquals(1f, s.fraction)
    }

    // ── numerator only ────────────────────────────────────────────────────────────────────────

    @Test
    fun usedOnlyShowsTokensAndSaysTheWindowIsUnknown() {
        val s = contextStatusUi(used = 84_000, window = null)
        assertEquals(ContextStatusKind.USED_ONLY, s.kind)
        assertNull(s.percent, "no denominator → no percentage")
        assertNull(s.fraction)
        assertEquals(listOf(ContextStatusNote.WINDOW_UNKNOWN), s.notes)
        assertEquals("~84k", contextStatusReadout(s))
        assertEquals("ctx ~84k", contextStatusMetaSegment(s))
    }

    @Test
    fun nonPositiveWindowCountsAsNoWindow() {
        for (w in listOf(0L, -1L)) {
            val s = contextStatusUi(used = 84_000, window = w)
            assertEquals(ContextStatusKind.USED_ONLY, s.kind, "window=$w must not be a denominator")
            assertNull(s.window)
            assertNull(s.percent)
        }
        assertEquals(ContextStatusKind.NO_DATA, contextStatusUi(used = null, window = 0).kind)
    }

    @Test
    fun negativeUsedIsNotEvidence() {
        assertEquals(ContextStatusKind.WINDOW_ONLY, contextStatusUi(used = -5, window = 200_000).kind)
    }

    // ── denominator only ──────────────────────────────────────────────────────────────────────

    @Test
    fun windowOnlyShowsTheCapAndDoesNotPretendZeroUsed() {
        val s = contextStatusUi(used = null, window = 200_000)
        assertEquals(ContextStatusKind.WINDOW_ONLY, s.kind)
        assertNull(s.used)
        assertNull(s.percent, "a missing numerator is not 0%")
        assertNull(s.fraction)
        assertEquals(listOf(ContextStatusNote.USED_PENDING), s.notes)
        assertEquals("— / 200k", contextStatusReadout(s))
        assertEquals("ctx — / 200k", contextStatusMetaSegment(s))
    }

    // ── neither ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun neitherFactIsNoDataYetNeverUnsupported() {
        val s = contextStatusUi(used = null, window = null)
        assertEquals(ContextStatusKind.NO_DATA, s.kind)
        assertEquals(listOf(ContextStatusNote.NO_DATA), s.notes)
        assertEquals("—", contextStatusReadout(s))
        assertEquals("ctx —", contextStatusMetaSegment(s))
    }

    // ── user override is not runtime evidence ─────────────────────────────────────────────────

    @Test
    fun aHandTypedWindowIsLabelledAsTheUsersValue() {
        val s = contextStatusUi(used = 84_000, window = 256_000, windowOverride = 256_000)
        assertEquals(ContextStatusKind.USED_OF_WINDOW, s.kind)
        assertTrue(s.windowIsUserOverride)
        assertEquals(listOf(ContextStatusNote.WINDOW_USER_OVERRIDE), s.notes)

        val pending = contextStatusUi(used = null, window = 256_000, windowOverride = 256_000)
        assertEquals(listOf(ContextStatusNote.USED_PENDING, ContextStatusNote.WINDOW_USER_OVERRIDE), pending.notes)
    }

    @Test
    fun anOverrideThatIsNotTheDisplayedWindowIsNotClaimed() {
        assertFalse(contextStatusUi(used = 1, window = 200_000, windowOverride = 256_000).windowIsUserOverride)
        assertFalse(contextStatusUi(used = 1, window = null, windowOverride = 256_000).windowIsUserOverride)
        assertFalse(contextStatusUi(used = 1, window = 200_000, windowOverride = null).windowIsUserOverride)
    }

    // ── model label: unknown stays unknown ────────────────────────────────────────────────────

    @Test
    fun aNamedModelNeedsNoFallback() {
        assertNull(sessionModelFallback(AgentKind.DSH, "deepseek-v4-flash"))
        assertNull(sessionModelFallback(AgentKind.CLAUDE, "claude-opus-5"))
    }

    @Test
    fun unknownModelIsUnknownForEveryNonClaudeBackend() {
        for (agent in AgentKind.entries.filter { it != AgentKind.CLAUDE }) {
            assertEquals(SessionModelFallback.UNKNOWN, sessionModelFallback(agent, null), "$agent must not read 'default'")
            assertEquals(SessionModelFallback.UNKNOWN, sessionModelFallback(agent, "  "), "$agent blank id is still unknown")
        }
    }

    @Test
    fun onlyClaudeHasAnAccountDefault() {
        assertEquals(SessionModelFallback.ACCOUNT_DEFAULT, sessionModelFallback(AgentKind.CLAUDE, null))
        // an older daemon omits agent → the phone assumes Claude (SessionLive.agent contract)
        assertEquals(SessionModelFallback.ACCOUNT_DEFAULT, sessionModelFallback(null, null))
    }

    // ── older daemon: fields absent on the wire ───────────────────────────────────────────────

    private fun repoAfter(live: SessionLive) = PocketRepository(CoroutineScope(Dispatchers.Unconfined)).apply {
        paired.value = PairedDaemon(
            relay = "wss://test", accountId = "acct-test", daemonPub = "pk", deviceId = "dev", credential = "cred",
        )
        convoId.value = live.convoId
        receiveForTest(live)
    }

    private fun PocketRepository.status() =
        contextStatusUi(contextUsed.value, contextWindow.value, contextWindowOverrideFor(model.value))

    @Test
    fun anOlderDaemonsNonClaudeSessionReadsNoDataNotZero() {
        val r = repoAfter(SessionLive("c1", "/w", "sid-1", executing = false, agent = AgentKind.DSH))
        val s = r.status()
        assertEquals(ContextStatusKind.NO_DATA, s.kind)
        assertNull(s.used, "absent usage must stay absent, not become 0")
        assertNull(s.percent)
        // the repo's own agent/model state (not a hand-picked AgentKind) must resolve to unknown
        assertEquals(SessionModelFallback.UNKNOWN, sessionModelFallback(r.sessionAgent.value, r.model.value))
    }

    @Test
    fun anOlderDaemonWithoutAgentOrUsageKeepsClaudesDerivedWindowOnly() {
        val r = repoAfter(SessionLive("c1", "/w", "sid-1", executing = false, model = "claude-sonnet-4-5"))
        val s = r.status()
        assertEquals(ContextStatusKind.WINDOW_ONLY, s.kind, "no usage seed yet — the derived window alone, not 0%")
        assertNull(s.percent)
        assertFalse(s.windowIsUserOverride, "a model-derived window is not the user's value")
    }

    @Test
    fun aLiveSeedWithBothFieldsIsUsedOfWindow() {
        val r = repoAfter(
            SessionLive("c1", "/w", "sid-1", executing = false, model = "deepseek-chat", contextWindow = 128_000, contextUsed = 0, agent = AgentKind.DSH),
        )
        val s = r.status()
        assertEquals(ContextStatusKind.USED_OF_WINDOW, s.kind)
        assertEquals(0, s.percent)
    }
}
