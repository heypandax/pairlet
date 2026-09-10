package dev.ccpocket.app.brand

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrandTransitionTest {
    @Test fun freshInstallNeverBecomesAnUpgradeAfterCreatingIdentity() {
        val store = mutableMapOf<String, String>()
        assertFalse(BrandTransition(store::get, store::put).pending)
        store["device_priv"] = "new-key"
        assertFalse(BrandTransition(store::get, store::put).pending)
    }

    @Test fun oldInstallRemainsPendingUntilDismissedAndPreservesAllData() {
        val legacy = mapOf("device_priv" to "old-key", "paired_daemon" to "old-record", "relay" to "wss://self-hosted")
        val store = legacy.toMutableMap()
        val notice = BrandTransition(store::get, store::put)
        assertTrue(notice.pending)
        assertTrue(BrandTransition(store::get, store::put).pending) // process exited before dismissal
        notice.dismiss()
        assertFalse(notice.pending) // remounting the same controller must not resurrect the banner
        assertFalse(BrandTransition(store::get, store::put).pending)
        legacy.forEach { (key, value) -> assertEquals(value, store[key]) }
    }

    @Test fun unpairedSettingsOnlyInstallStillGetsNotice() {
        val store = mutableMapOf("appearance_theme_mode" to "LIGHT")
        assertTrue(BrandTransition(store::get, store::put).pending)
    }
}
