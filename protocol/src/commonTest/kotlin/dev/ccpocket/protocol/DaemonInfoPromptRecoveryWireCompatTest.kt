package dev.ccpocket.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DaemonInfo shape from before the daemon-owned unconsumed-prompt recovery capability. */
@Serializable
@SerialName("pocket/daemon.info")
private data class PrePromptRecoveryDaemonInfo(
    val lanUrl: String? = null,
    val supportsUsageAgentFilter: Boolean = false,
)

class DaemonInfoPromptRecoveryWireCompatTest {

    @Test
    fun currentDaemonCapabilityRoundTrips() {
        val json = PocketJson.encodeToString<Frame>(DaemonInfo(supportsPromptRecovery = true))

        assertTrue("\"supportsPromptRecovery\":true" in json, json)
        assertTrue((PocketJson.decodeFromString<Frame>(json) as DaemonInfo).supportsPromptRecovery)
    }

    @Test
    fun oldDaemonOmissionKeepsTheLegacyFallback() {
        val info = PocketJson.decodeFromString<Frame>("""{"t":"pocket/daemon.info"}""") as DaemonInfo

        assertFalse(info.supportsPromptRecovery)
    }

    @Test
    fun oldAppIgnoresTheNewCapability() {
        val json = PocketJson.encodeToString<Frame>(
            DaemonInfo(lanUrl = "ws://10.0.0.2:8765/v1/ws", supportsPromptRecovery = true),
        )
        val old = PocketJson.decodeFromString<PrePromptRecoveryDaemonInfo>(json)

        assertTrue(old.lanUrl == "ws://10.0.0.2:8765/v1/ws")
    }
}
