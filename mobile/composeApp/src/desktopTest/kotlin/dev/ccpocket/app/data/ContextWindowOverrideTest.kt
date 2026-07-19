package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.DEFAULT_CONTEXT_WINDOW
import dev.ccpocket.protocol.LARGE_CONTEXT_WINDOW
import dev.ccpocket.protocol.SessionLive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Issue #159: an explicitly typed context window must OUTRANK the observed-usage upgrade.
 *
 * The upgrade rule (`provenWindow`) exists because a declared window is usually a guess — a beta-1M model
 * declares its 200k canonical id, so occupancy past 200k proves the bigger window. But a number the user
 * typed into Settings isn't a guess, and letting the inference overwrite it made the custom field useless:
 * the value wouldn't survive the first turn that crossed it. These pin both halves — the exemption AND the
 * fact that the upgrade still fires for everyone who hasn't overridden.
 */
class ContextWindowOverrideTest {

    private fun repo() = PocketRepository(CoroutineScope(Dispatchers.Unconfined)).apply {
        paired.value = PairedDaemon(
            relay = "wss://test", accountId = "acct-test", daemonPub = "pk", deviceId = "dev", credential = "cred",
        )
        convoId.value = "c1"
        receiveForTest(SessionLive("c1", "/w", "sid-1", executing = false))
    }

    @Test
    fun typedWindowSurvivesOccupancyThatWouldOtherwiseProveOneMillion() {
        val r = repo()
        // a gateway model whose real window is 256k — exactly the case the custom field was added for
        r.setContextWindowOverride(256_000L)
        r.contextUsed.value = 260_000L // past the declared window: the upgrade rule would jump to 1M
        r.receiveForTest(SessionLive("c1", "/w", "sid-1", executing = false, contextWindow = DEFAULT_CONTEXT_WINDOW))
        assertEquals(
            256_000L, r.contextWindow.value,
            "typed window was overwritten — the statusline denominator jumped and the setting looks broken",
        )
    }

    @Test
    fun withoutAnOverrideTheProvenUpgradeStillFires() {
        val r = repo()
        r.contextUsed.value = 260_000L
        r.receiveForTest(SessionLive("c1", "/w", "sid-1", executing = false, contextWindow = DEFAULT_CONTEXT_WINDOW))
        assertEquals(
            LARGE_CONTEXT_WINDOW, r.contextWindow.value,
            "the beta-1M upgrade regressed — issue #20 (statusline pinned at 100% mid-1M-session) is back",
        )
    }

    @Test
    fun clearingTheOverrideHandsControlBackToTheUpgrade() {
        val r = repo()
        r.setContextWindowOverride(256_000L)
        r.contextUsed.value = 260_000L
        r.receiveForTest(SessionLive("c1", "/w", "sid-1", executing = false, contextWindow = DEFAULT_CONTEXT_WINDOW))
        assertEquals(256_000L, r.contextWindow.value)
        r.setContextWindowOverride(null) // back to "follow model"
        assertEquals(
            LARGE_CONTEXT_WINDOW, r.contextWindow.value,
            "clearing the override left the window pinned — the exemption outlived the setting it guards",
        )
    }
}
