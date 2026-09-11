package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FleetLifecycleTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val previousFleet = FleetRuntime.coordinator

    @AfterTest fun cleanUp() {
        FleetRuntime.coordinator = previousFleet
        scope.cancel()
    }

    // Pinned, inactive links: no real pairing changes, sockets, or telemetry operations.
    private fun repo(id: String) = PocketRepository(
        scope, pinnedTo = PairedDaemon("wss://test.invalid", id, "pub", "device", "credential"),
    ).apply { historyLayoutToken.value = id }

    @Test fun returningToForegroundRestoresPrimaryAndSatelliteLayoutReceipts() {
        val primary = repo("primary")
        val satellite = repo("satellite")
        val fleet = FleetCoordinator(scope, primary).apply { satellites["satellite"] = satellite }

        fleet.onAppBackground()
        assertNull(primary.contentLayoutToken)
        assertNull(satellite.contentLayoutToken)

        fleet.onAppForeground()
        assertEquals("primary", primary.contentLayoutToken)
        assertEquals("satellite", satellite.contentLayoutToken)
    }

    @Test fun retiringRootCannotBackgroundTheReplacementFleet() {
        val retiring = FleetCoordinator(scope, repo("old"))
        val replacement = FleetCoordinator(scope, repo("new"))
        FleetRuntime.coordinator = replacement

        // Android may stop the old Activity after the replacement's onResume. The callback
        // must retain its own fleet, rather than resolving the mutable process-wide handle.
        retiring.onAppBackground()

        assertNull(retiring.primary.contentLayoutToken)
        assertEquals("new", replacement.primary.contentLayoutToken)
        retiring.onAppForeground()
        assertEquals("old", retiring.primary.contentLayoutToken)
        assertEquals("new", replacement.primary.contentLayoutToken)
    }
}
