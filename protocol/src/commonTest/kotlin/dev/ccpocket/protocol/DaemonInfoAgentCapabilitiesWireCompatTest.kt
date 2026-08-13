package dev.ccpocket.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The DaemonInfo shape understood by an app released before reverse agent advertisement. */
@Serializable
@SerialName("pocket/daemon.info")
private data class PreAgentCapabilitiesDaemonInfo(
    val lanUrl: String? = null,
    val hostname: String? = null,
    val gatewayBaseUrl: String? = null,
    val bridgeControl: Boolean = false,
    val daemonVersion: String? = null,
    val latestVersion: String? = null,
    val updateCommand: String? = null,
)

class DaemonInfoAgentCapabilitiesWireCompatTest {

    @Test
    fun currentDaemonAdvertisesZcodeAndRoundTripsTheCompleteAgentList() {
        assertTrue(AGENT_WIRE_ZCODE in DAEMON_SUPPORTED_AGENT_WIRES)
        val info = DaemonInfo(supportedAgents = DAEMON_SUPPORTED_AGENT_WIRES)
        val json = PocketJson.encodeToString<Frame>(info)

        assertTrue("\"supportedAgents\"" in json, json)
        assertTrue("\"zcode\"" in json, json)
        assertEquals(info, PocketJson.decodeFromString<Frame>(json))
    }

    @Test
    fun oldDaemonOmissionDecodesToNoAdvertisedAgents() {
        val json = """{"t":"pocket/daemon.info","bridgeControl":true}"""
        val info = PocketJson.decodeFromString<Frame>(json) as DaemonInfo

        assertEquals(emptyList(), info.supportedAgents)
    }

    @Test
    fun oldAppIgnoresTheNewCapabilityField() {
        val json = PocketJson.encodeToString<Frame>(
            DaemonInfo(lanUrl = "ws://10.0.0.2:8765/v1/ws", supportedAgents = DAEMON_SUPPORTED_AGENT_WIRES),
        )
        val old = PocketJson.decodeFromString<PreAgentCapabilitiesDaemonInfo>(json)

        assertEquals("ws://10.0.0.2:8765/v1/ws", old.lanUrl)
    }
}
