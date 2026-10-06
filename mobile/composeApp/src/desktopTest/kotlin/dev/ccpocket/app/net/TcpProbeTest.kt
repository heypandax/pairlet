package dev.ccpocket.app.net

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlinx.coroutines.runBlocking

/** #404 / TRANSPORT-AUTO-REPATH-V1 4.2 step 3: the bounded TCP probe and the URL port helper. */
class TcpProbeTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test
    fun listeningPortIsReachable() = runBlocking {
        ServerSocket(0, 50, loopback).use { server ->
            assertTrue(tcpReachable("127.0.0.1", server.localPort, 1_500))
            assertTrue(tcpReachable("localhost", server.localPort, 1_500))
        }
    }

    @Test
    fun closedPortIsUnreachable() = runBlocking {
        val port = ServerSocket(0, 50, loopback).use { it.localPort }
        assertFalse(tcpReachable("127.0.0.1", port, 1_500))
    }

    @Test
    fun silentListenerTimesOutWithinBudget() = runBlocking {
        // A listener whose accept backlog is full drops further SYNs, i.e. it never answers — the
        // black hole a stale LAN address looks like. Fill the backlog until a connect stalls.
        ServerSocket(0, 1, loopback).use { server ->
            val fillers = mutableListOf<Socket>()
            try {
                var stalled = false
                repeat(64) {
                    if (stalled) return@repeat
                    val s = Socket()
                    try {
                        s.connect(InetSocketAddress(loopback, server.localPort), 200)
                        fillers += s
                    } catch (_: Exception) {
                        s.close(); stalled = true
                    }
                }
                assertTrue(stalled, "backlog never filled; cannot build a silent listener here")
                val mark = TimeSource.Monotonic.markNow()
                assertFalse(tcpReachable("127.0.0.1", server.localPort, 500))
                val took = mark.elapsedNow().inWholeMilliseconds
                assertTrue(took < 500 + 1_000, "probe must honour its timeout, took ${took}ms")
            } finally {
                fillers.forEach { runCatching { it.close() } }
            }
        }
    }

    @Test
    fun portOfUsesExplicitPortOrSchemeDefault() {
        assertEquals(8799, portOf("ws://192.168.1.5:8799/pair"))
        assertEquals(8799, portOf("ws://[fe80::1]:8799"))
        assertEquals(80, portOf("ws://host.local/x"))
        assertEquals(443, portOf("wss://host"))
        assertEquals(80, portOf("http://h"))
        assertEquals(443, portOf("https://u@h/"))
        assertNull(portOf("ws://h:notaport"))
        assertNull(portOf("ws://h:70000"))
        assertNull(portOf("ftp://h"))
        assertNull(portOf("garbage"))
    }
}
