package dev.ccpocket.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class RelayEndpointTest {
    @Test fun officialStoredEndpointsDialTheNewPrimary() {
        for (host in listOf("pocket.ark-nexus.cc", "relay.pairlet.org", "relay.pairlet.cn")) {
            for (suffix in listOf("", "/", ":443", ":443/")) {
                assertEquals(RelayEndpoint.DEFAULT, RelayEndpoint.transport("wss://$host$suffix"))
            }
        }
    }

    @Test fun customAndLookalikeEndpointsStayUntouched() {
        for (url in listOf("ws://127.0.0.1:9000", "wss://custom.example/", "wss://pocket.ark-nexus.cc:9443",
            "ws://pocket.ark-nexus.cc", "wss://pocket.ark-nexus.cc/private", "wss://pocket.ark-nexus.cc.evil.example",
            "wss://pocket.ark-nexus.cc@other.example", "wss://pocket.ark-nexus.cc?tenant=custom")) {
            assertEquals(url, RelayEndpoint.transport(url))
        }
    }
}
