package dev.ccpocket.app.pairing

import dev.ccpocket.app.secure.SecureStore
import dev.ccpocket.protocol.RelayEndpoint
import dev.ccpocket.protocol.e2e.E2ECrypto
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RelayMigrationTest {
    private val storeKeys = listOf("paired_daemons", "paired_daemon", "active_account")
    private var saved: Map<String, String?> = emptyMap()

    @BeforeTest fun before() {
        saved = storeKeys.associateWith(SecureStore::getString)
        storeKeys.forEach(SecureStore::remove)
    }

    @AfterTest fun after() {
        storeKeys.forEach(SecureStore::remove)
        saved.forEach { (k, v) -> if (v != null) SecureStore.putString(k, v) }
    }

    @Test fun oldQrRedeemsAtNewIngressAndKeepsItsLogicalRelay() = runBlocking {
        for (old in listOf("wss://pocket.ark-nexus.cc", "wss://relay.pairlet.org")) {
            val requests = mutableListOf<String>()
            HttpClient(MockEngine { request ->
                requests += request.url.toString()
                respond("""{"deviceId":"device","credential":"credential","accountId":"account"}""")
            }).use { client ->
                val info = Pairing.parse("ccpocket://pair?relay=$old&acct=account&dpk=pinned&ticket=ticket")!!
                val paired = Pairing.redeem(info, E2ECrypto.generateKeyPair(), client)
                assertEquals(listOf("https://relay.pairlet.cn/v1/pair/redeem"), requests)
                assertEquals(old, paired.relay)
                assertEquals("pinned", paired.daemonPub)
                assertEquals(paired, Pairing.active())
            }
        }
    }

    @Test fun readingAnExistingBindingPreservesItsSerializedIdentityAndCredentials() {
        val original = PairedDaemon("wss://pocket.ark-nexus.cc", "account", "pinned", "device", "credential", label = "computer")
        Pairing.upsert(original)
        Pairing.setActive(original.accountId)
        val encoded = SecureStore.getString("paired_daemons")
        val binding = Pairing.active()!!
        assertEquals("wss://relay.pairlet.cn", RelayEndpoint.webSocketBase(binding.relay))
        assertEquals(original, binding)
        assertEquals(encoded, SecureStore.getString("paired_daemons"))
        assertEquals(original.accountId, Pairing.activeAccount())
    }

    @Test fun aRejectedRedeemCannotRewriteExistingPairingOrRetryAnOldHost() = runBlocking {
        val original = PairedDaemon("wss://pocket.ark-nexus.cc", "account", "pinned", "device", "credential")
        Pairing.upsert(original)
        Pairing.setActive(original.accountId)
        val encoded = SecureStore.getString("paired_daemons")
        val requests = mutableListOf<String>()
        HttpClient(MockEngine { request ->
            requests += request.url.toString()
            respond("""{"error":"ticket_expired"}""", HttpStatusCode.Unauthorized)
        }).use { client ->
            assertFailsWith<IllegalStateException> {
                Pairing.redeem(PairingInfo(original.relay, "account", "pinned", "expired"), E2ECrypto.generateKeyPair(), client)
            }
        }
        assertEquals(listOf("https://relay.pairlet.cn/v1/pair/redeem"), requests)
        assertEquals(encoded, SecureStore.getString("paired_daemons"))
        assertEquals(original, Pairing.active())
    }

    @Test fun sixDigitPairingUsesNewIngress() = runBlocking {
        HttpClient(MockEngine { request ->
            assertEquals("https://relay.pairlet.cn/v1/pair/code", request.url.toString())
            respond("""{"accountId":"account","daemonPub":"pinned","ticket":"ticket"}""")
        }).use { client ->
            assertEquals("wss://relay.pairlet.cn", Pairing.resolveCode("123456", client).relay)
        }
    }

    @Test fun aSelfHostedPairingKeepsItsOwnHttpEndpoint() = runBlocking {
        val custom = "wss://my-relay.example:8443/tenant"
        HttpClient(MockEngine { request ->
            assertEquals("https://my-relay.example:8443/tenant/v1/pair/redeem", request.url.toString())
            respond("""{"deviceId":"device","credential":"credential","accountId":"account"}""")
        }).use { client ->
            assertEquals(custom, Pairing.redeem(PairingInfo(custom, "account", "pinned", "ticket"), E2ECrypto.generateKeyPair(), client).relay)
        }
    }
}
