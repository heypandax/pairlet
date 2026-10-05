package dev.ccpocket.daemon.control

import dev.ccpocket.daemon.relay.OwnerPairingWatch
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.e2e.E2ECrypto
import dev.ccpocket.protocol.e2e.PairingFingerprint
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.ktor.server.cio.CIO as ServerCIO

/**
 * `/v1/local/devices`, `/v1/local/devices/revoke`, `/v1/local/pairing/{id}` (pairing security phase 0): the
 * owner's management surface for FULL-POWER access to this computer. The shared gate guards every route —
 * token, no browser Origin, JSON Content-Type and the body cap on POST — and the answers carry fingerprints
 * computed with the one shared [PairingFingerprint].
 */
class OwnerDevicesControlTest {

    private val token = "owner-devices-test-token"

    private class FakePlane : OwnerDevicesPlane {
        val computer = E2ECrypto.generateKeyPair().publicRaw
        val rows = CopyOnWriteArrayList<OwnerDeviceRow>()
        val revoked = CopyOnWriteArrayList<String>()
        var outcome: OwnerPairingWatch.Outcome = OwnerPairingWatch.Outcome.Pending
        override val computerPub: ByteArray get() = computer
        override suspend fun devices() = rows.toList()
        override suspend fun revoke(deviceId: String): Boolean {
            val hit = rows.removeIf { it.deviceId == deviceId }
            if (hit) revoked += deviceId
            return hit
        }
        override suspend fun awaitPairing(pairingId: String, waitMs: Long) =
            if (pairingId == "p-1") outcome else OwnerPairingWatch.Outcome.Unknown
        override fun pairingRemainingMs(pairingId: String) = 42_000L
    }

    private fun <T> serving(plane: FakePlane, block: suspend (HttpClient, String) -> T): T = runBlocking {
        val server = embeddedServer(ServerCIO, host = "127.0.0.1", port = 0) {
            routing { installOwnerDevicesControl(plane, token) }
        }
        server.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(CIO)
        try {
            block(client, "http://127.0.0.1:$port$LOCAL_CONTROL_PREFIX")
        } finally {
            client.close()
            server.stop(0, 0)
        }
    }

    private suspend fun HttpClient.authPost(url: String, body: String, contentType: String = "application/json"): HttpResponse =
        post(url) {
            header(LocalControlToken.HEADER, token)
            header("Content-Type", contentType)
            setBody(body)
        }

    private fun codeOf(text: String) =
        runCatching { PocketJson.decodeFromString(LocalError.serializer(), text).code }.getOrNull()

    private fun plane(vararg ids: String) = FakePlane().apply {
        ids.forEach { rows += OwnerDeviceRow(it, E2ECrypto.generateKeyPair().publicRaw, pairedAt = null, firstContactPending = false) }
    }

    @Test
    fun every_route_is_behind_the_shared_gate() {
        val p = plane("devA")
        serving(p) { client, base ->
            for (url in listOf("$base/devices", "$base/pairing/p-1")) {
                assertEquals(HttpStatusCode.Unauthorized, client.get(url).status, url)
                assertEquals(HttpStatusCode.Unauthorized, client.get(url) { header(LocalControlToken.HEADER, "nope") }.status, url)
                val browser = client.get(url) { header(LocalControlToken.HEADER, token); header("Origin", "https://evil.example") }
                assertEquals(HttpStatusCode.Forbidden, browser.status, url)
            }
            val revoke = "$base/devices/revoke"
            assertEquals(HttpStatusCode.Unauthorized, client.post(revoke) { header("Content-Type", "application/json"); setBody("""{"deviceId":"devA"}""") }.status)
            assertEquals(HttpStatusCode.UnsupportedMediaType, client.authPost(revoke, """{"deviceId":"devA"}""", "text/plain").status)
            val big = client.authPost(revoke, """{"deviceId":"${"x".repeat(MAX_LOCAL_BODY_BYTES)}"}""")
            assertEquals(HttpStatusCode.PayloadTooLarge, big.status)
            val browser = client.post(revoke) {
                header(LocalControlToken.HEADER, token); header("Origin", "null")
                header("Content-Type", "application/json"); setBody("""{"deviceId":"devA"}""")
            }
            assertEquals(HttpStatusCode.Forbidden, browser.status)
            assertEquals(HttpStatusCode.BadRequest, client.authPost(revoke, "{not json").status)
        }
        assertEquals(emptyList(), p.revoked, "nothing refused by the gate may revoke anything")
    }

    @Test
    fun the_list_carries_the_shared_fingerprints() {
        val p = plane("devB", "devA")
        serving(p) { client, base ->
            val res = client.get("$base/devices") { header(LocalControlToken.HEADER, token) }
            assertEquals(HttpStatusCode.OK, res.status)
            val body = PocketJson.decodeFromString(LocalOwnerDevicesRes.serializer(), res.bodyAsText())
            assertEquals(PairingFingerprint.of(p.computer), body.computerFingerprint)
            assertEquals(listOf("devA", "devB"), body.items.map { it.deviceId })
            body.items.forEach { item ->
                assertEquals(PairingFingerprint.of(p.rows.single { it.deviceId == item.deviceId }.pub), item.fingerprint)
            }
        }
    }

    @Test
    fun revoke_takes_an_exact_id_and_reports_what_it_removed() {
        val p = plane("devA")
        val fp = PairingFingerprint.of(p.rows.single().pub)
        serving(p) { client, base ->
            val miss = client.authPost("$base/devices/revoke", """{"deviceId":"dev"}""")
            assertEquals(HttpStatusCode.NotFound, miss.status, "a prefix is resolved by the CLI, never here")
            assertEquals("not_found", codeOf(miss.bodyAsText()))
            val ok = client.authPost("$base/devices/revoke", """{"deviceId":"devA"}""")
            assertEquals(HttpStatusCode.OK, ok.status)
            val res = PocketJson.decodeFromString(LocalOwnerDeviceRevokeRes.serializer(), ok.bodyAsText())
            assertEquals("devA", res.deviceId)
            assertEquals(fp, res.fingerprint)
        }
        assertEquals(listOf("devA"), p.revoked)
    }

    @Test
    fun the_pairing_route_reports_each_outcome() {
        val p = plane()
        val pub = E2ECrypto.generateKeyPair().publicRaw
        serving(p) { client, base ->
            suspend fun state(id: String = "p-1"): LocalPairingRes {
                val res = client.get("$base/pairing/$id?waitMs=10") { header(LocalControlToken.HEADER, token) }
                assertEquals(HttpStatusCode.OK, res.status)
                return PocketJson.decodeFromString(LocalPairingRes.serializer(), res.bodyAsText())
            }
            assertEquals("pending", state().state)
            assertEquals(42_000L, state().remainingMs)
            p.outcome = OwnerPairingWatch.Outcome.Paired("devNew", pub)
            val paired = state()
            assertEquals("paired", paired.state)
            assertEquals("devNew", paired.deviceId)
            assertEquals(PairingFingerprint.of(pub), paired.fingerprint)
            p.outcome = OwnerPairingWatch.Outcome.Expired
            assertEquals("expired", state().state)
            p.outcome = OwnerPairingWatch.Outcome.Refused
            assertEquals("refused", state().state)
            assertEquals("unknown", state("p-other").state)
            assertTrue(state("p-other").deviceId == null)
        }
    }
}
