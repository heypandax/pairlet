package dev.ccpocket.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [ClientCaps] as every build before the frame-cap field encoded and decoded it. */
@Serializable
private data class PreFrameCapClientCaps(
    val supportsAgents: List<String> = emptyList(),
    val supportsApprovalV2: Boolean = false,
    val supportsDiagnostics: Boolean = false,
    val supportsProjectPins: Boolean = false,
    val supportsManagedSessions: Boolean = false,
    val supportsToolOutcomes: Boolean = false,
)

/** [ClientCaps.maxFrameBytes] (KTOR-6963) must be additive in both directions: an old client's declaration
 *  reads as "unknown", and an old daemon drops the new key rather than the whole declaration. */
class FrameCapWireCompatTest {

    private fun bodyJson(frame: Frame): String =
        PocketJson.parseToJsonElement(PocketJson.encodeToString(Envelope("1", 0, body = frame))).jsonObject
            .getValue("body").toString()

    private fun body(json: String): Frame = PocketJson.decodeFromString<Envelope>("""{"id":"1","ts":0,"body":$json}""").body

    @Test
    fun an_old_client_declaration_reads_as_unknown_cap() {
        val old = PocketJson.encodeToString(PreFrameCapClientCaps(supportsDiagnostics = true))
        val decoded = body("""{"t":"pocket/client.caps",${old.removePrefix("{")}""") as ClientCaps
        assertEquals(0L, decoded.maxFrameBytes)
        assertTrue(decoded.supportsDiagnostics)
    }

    @Test
    fun a_declared_cap_roundtrips_and_an_old_daemon_ignores_it() {
        val declared = ClientCaps(supportsToolOutcomes = true, maxFrameBytes = WIRE_MAX_FRAME_BYTES)
        val json = bodyJson(declared)
        assertTrue("\"maxFrameBytes\":4194304" in json, json)
        assertEquals(declared, body(json))
        // the pre-cap daemon: unknown key dropped, every other bit intact
        val old = PocketJson.decodeFromString<PreFrameCapClientCaps>(json.replace("\"t\":\"pocket/client.caps\",", ""))
        assertTrue(old.supportsToolOutcomes)
    }

    @Test
    fun an_undeclared_new_build_still_puts_the_zero_on_the_wire_and_a_bare_declaration_decodes() {
        // encodeDefaults: the daemon's frameCap(0) → legacy path is what a new build that declares nothing hits
        assertTrue("\"maxFrameBytes\":0" in bodyJson(ClientCaps()))
        assertEquals(ClientCaps(), body("""{"t":"pocket/client.caps"}"""))
    }

    @Test
    fun the_legacy_assumption_is_apples_default_under_the_wire_ceiling() {
        assertEquals(1L shl 20, LEGACY_CLIENT_MAX_FRAME_BYTES) // NSURLSessionWebSocketTask.maximumMessageSize default
        assertEquals(4L shl 20, WIRE_MAX_FRAME_BYTES) // RelayServer.MAX_FRAME
        assertTrue(LEGACY_CLIENT_MAX_FRAME_BYTES < WIRE_MAX_FRAME_BYTES)
    }
}
