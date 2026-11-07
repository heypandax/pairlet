package dev.ccpocket.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class RelayEndpointTest {
    @Test fun legacyHttpsSettingsUseCnWithoutChangingCustomEndpoints() {
        for (host in listOf("pocket.ark-nexus.cc", "relay.pairlet.org", "relay.pairlet.cn")) {
            assertEquals("https://relay.pairlet.cn", RelayEndpoint.httpBase("https://$host"))
            assertEquals("https://relay.pairlet.cn", RelayEndpoint.httpBase("https://$host/"))
            for (suffix in listOf(":8443", "/custom", "?tenant=custom", ".attacker.example", "@attacker.example")) {
                val custom = "https://$host$suffix"
                assertEquals(custom, RelayEndpoint.httpBase(custom))
            }
        }
    }

    @Test fun releasedOfficialRootsReachTheSameCoreOverTls() {
        for (host in listOf("pocket.ark-nexus.cc", "relay.pairlet.org", "relay.pairlet.cn")) {
            for (suffix in listOf("", "/", ":443", ":443/")) {
                val saved = "wss://$host$suffix"
                assertEquals("wss://relay.pairlet.cn", RelayEndpoint.webSocketBase(saved))
                assertEquals("https://relay.pairlet.cn", RelayEndpoint.httpBase(saved))
            }
        }
    }

    @Test fun anOfficialSubstringCannotRedirectASelfHostedOrUntrustedUrl() {
        for (url in listOf(
            "wss://pocket.ark-nexus.cc.attacker.example",
            "wss://pocket.ark-nexus.cc@attacker.example",
            "wss://attacker.example/pocket.ark-nexus.cc",
            "wss://example.com?next=wss://pocket.ark-nexus.cc",
            "wss://pocket.ark-nexus.cc:8443",
            "wss://pocket.ark-nexus.cc/custom",
            "wss://pocket.ark-nexus.cc?tenant=custom",
            "wss://pocket.ark-nexus.cc#custom",
            "wss://pocket.ark-nexus.cc//",
            "ws://pocket.ark-nexus.cc",
            "ws://127.0.0.1:9000",
            "wss://my-relay.example/tenant",
            " wss://pocket.ark-nexus.cc",
        )) assertEquals(url, RelayEndpoint.webSocketBase(url), url)
    }

    @Test fun customHttpDerivationPreservesPortAndPath() {
        assertEquals("https://example.com:9443/relay", RelayEndpoint.httpBase("wss://example.com:9443/relay"))
        assertEquals("http://127.0.0.1:9000", RelayEndpoint.httpBase("ws://127.0.0.1:9000"))
        assertEquals("https://pocket.ark-nexus.cc:8443", RelayEndpoint.httpBase("wss://pocket.ark-nexus.cc:8443"))
    }
}
