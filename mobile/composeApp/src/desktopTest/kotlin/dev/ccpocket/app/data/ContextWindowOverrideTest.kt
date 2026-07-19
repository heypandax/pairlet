package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.secure.SecureStore
import dev.ccpocket.protocol.DEFAULT_CONTEXT_WINDOW
import dev.ccpocket.protocol.LARGE_CONTEXT_WINDOW
import dev.ccpocket.protocol.SessionLive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #159: an explicitly typed context window must OUTRANK the observed-usage upgrade.
 * Issue #169: and it must be scoped to the MODEL it was typed for, not to the whole phone.
 *
 * The upgrade rule (`provenWindow`) exists because a declared window is usually a guess — a beta-1M model
 * declares its 200k canonical id, so occupancy past 200k proves the bigger window. But a number the user
 * typed into Settings isn't a guess, and letting the inference overwrite it made the custom field useless:
 * the value wouldn't survive the first turn that crossed it. #159 pinned that exemption.
 *
 * #169 is the other half: a window is a property of the model, so one global number is wrong the moment a
 * second model shows up. These pin the per-model table, its precedence over the legacy catch-all, and the
 * fact that the exemption now follows the running model instead of firing globally.
 */
class ContextWindowOverrideTest {

    /**
     * The desktop SecureStore is a real properties file shared by every test in this JVM, and BOTH override
     * keys are read at repo CONSTRUCTION. Without this reset the suite is order-dependent: whichever test
     * writes an override first leaks it into the next test's "fresh" repo and flips its assertion. That
     * hazard predates #169 (the three #159 cases below already wrote through to disk) — it only passed on
     * the luck of JUnit's method ordering. Clearing up front makes each case hermetic regardless of order.
     */
    @BeforeTest
    fun clearPersistedOverrides() {
        SecureStore.remove(PocketRepository.K_CONTEXT_WINDOW_OVERRIDE)
        SecureStore.remove(PocketRepository.K_CONTEXT_WINDOW_OVERRIDES)
    }

    private fun repo() = PocketRepository(CoroutineScope(Dispatchers.Unconfined)).apply {
        paired.value = PairedDaemon(
            relay = "wss://test", accountId = "acct-test", daemonPub = "pk", deviceId = "dev", credential = "cred",
        )
        convoId.value = "c1"
        receiveForTest(SessionLive("c1", "/w", "sid-1", executing = false))
    }

    /** Announce a SessionLive for the open conversation running [model], with [declared] as the daemon's window. */
    private fun PocketRepository.live(model: String?, declared: Long? = DEFAULT_CONTEXT_WINDOW) =
        receiveForTest(SessionLive("c1", "/w", "sid-1", executing = false, model = model, contextWindow = declared))

    // ── #159: a typed number outranks the inference ──────────────────────────────────────────────

    @Test
    fun typedWindowSurvivesOccupancyThatWouldOtherwiseProveOneMillion() {
        val r = repo()
        // a gateway model whose real window is 256k — exactly the case the custom field was added for
        r.setContextWindowOverride(256_000L)
        r.contextUsed.value = 260_000L // past the declared window: the upgrade rule would jump to 1M
        r.live(model = null)
        assertEquals(
            256_000L, r.contextWindow.value,
            "typed window was overwritten — the statusline denominator jumped and the setting looks broken",
        )
    }

    @Test
    fun withoutAnOverrideTheProvenUpgradeStillFires() {
        val r = repo()
        r.contextUsed.value = 260_000L
        r.live(model = null)
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
        r.live(model = null)
        assertEquals(256_000L, r.contextWindow.value)
        r.setContextWindowOverride(null) // back to "follow model"
        assertEquals(
            LARGE_CONTEXT_WINDOW, r.contextWindow.value,
            "clearing the override left the window pinned — the exemption outlived the setting it guards",
        )
    }

    // ── #169: the number belongs to a model, not to the phone ────────────────────────────────────

    @Test
    fun aPerModelWindowDoesNotLeakToADifferentModel() {
        val r = repo()
        // session on a 256k gateway model — pin its real window
        r.live(model = "deepseek-chat")
        r.setContextWindowOverrideFor("deepseek-chat", 256_000L)
        assertEquals(256_000L, r.contextWindow.value)

        // same phone, now running official Sonnet (natively 1M) — it must be measured against ITS OWN window
        r.live(model = "claude-sonnet-5", declared = LARGE_CONTEXT_WINDOW)
        assertEquals(
            LARGE_CONTEXT_WINDOW, r.contextWindow.value,
            "the gateway model's 256k denominator followed us onto Sonnet — that is the #169 bleed, unfixed",
        )
    }

    @Test
    fun aPerModelEntryBeatsTheLegacyCatchAll() {
        val r = repo()
        r.setContextWindowOverride(128_000L)                       // legacy global, applies to everything
        r.setContextWindowOverrideFor("deepseek-chat", 256_000L)   // ...except this model
        r.live(model = "deepseek-chat")
        assertEquals(
            256_000L, r.contextWindow.value,
            "the catch-all outranked the model-specific entry — precedence is inverted",
        )
    }

    @Test
    fun theLegacyCatchAllStillCoversModelsWithoutTheirOwnEntry() {
        val r = repo()
        // the value an existing user already typed, before per-model entries existed: it must keep working
        r.setContextWindowOverride(128_000L)
        r.live(model = "some-unlisted-gateway-model")
        assertEquals(
            128_000L, r.contextWindow.value,
            "an upgrading user's existing override stopped applying — #169 silently ate a setting they can't see move",
        )
    }

    @Test
    fun clearingAPerModelEntryFallsBackToTheCatchAll() {
        val r = repo()
        r.setContextWindowOverride(128_000L)
        r.setContextWindowOverrideFor("deepseek-chat", 256_000L)
        r.live(model = "deepseek-chat")
        assertEquals(256_000L, r.contextWindow.value)

        r.setContextWindowOverrideFor("deepseek-chat", null)
        assertEquals(
            128_000L, r.contextWindow.value,
            "dropping the model entry did not hand control back to the catch-all",
        )
        assertTrue("deepseek-chat" !in r.contextWindowOverrides, "the cleared entry is still in the table")
    }

    /**
     * The per-model analog of [typedWindowSurvivesOccupancyThatWouldOtherwiseProveOneMillion], and the case that
     * actually pins the exemption's new scope: a global-only guard (`contextWindowOverride != null`, the #159
     * shape) does NOT see this entry, so the upgrade would overwrite the typed 256k with 1M and the setting would
     * look broken again — exactly the bug #159 fixed, reintroduced for everyone who moves to a per-model value.
     */
    @Test
    fun aPerModelWindowSurvivesOccupancyThatWouldOtherwiseProveOneMillion() {
        val r = repo()
        r.setContextWindowOverrideFor("deepseek-chat", 256_000L)
        r.contextUsed.value = 260_000L // past the typed window: the upgrade rule would jump to 1M
        r.live(model = "deepseek-chat")
        assertEquals(
            256_000L, r.contextWindow.value,
            "the typed per-model window was overwritten by the proven-window upgrade — the #159 exemption did not " +
                "follow the value when it moved from the global slot into the per-model table",
        )
    }

    @Test
    fun theProvenUpgradeExemptionFollowsTheRunningModel() {
        val r = repo()
        r.setContextWindowOverrideFor("deepseek-chat", 256_000L) // an entry for a DIFFERENT model
        r.contextUsed.value = 260_000L
        r.live(model = "claude-sonnet-4-5") // declares 200k, occupancy proves 1M
        assertEquals(
            LARGE_CONTEXT_WINDOW, r.contextWindow.value,
            "a number typed for another model suppressed the beta-1M upgrade here — the exemption went global again",
        )
    }

    @Test
    fun entryKeysFoldCaseAndSurroundingWhitespace() {
        val r = repo()
        r.setContextWindowOverrideFor("  DeepSeek-Chat  ", 256_000L)
        assertEquals(
            256_000L, r.contextWindowOverrideFor("deepseek-chat"),
            "key normalization drifted from contextWindowFor's (trim + lowercase) — the same model got two rows",
        )
    }

    @Test
    fun aWriteWithNoModelInScopeIsDroppedRatherThanRedirectedToTheCatchAll() {
        val r = repo()
        r.setContextWindowOverrideFor(null, 256_000L)
        assertNull(
            r.contextWindowOverride.value,
            "a model-less write fell through to the global — that recreates exactly the bleed #169 removes",
        )
        assertTrue(r.contextWindowOverrides.isEmpty(), "a model-less write created a phantom entry")
    }
}
