package dev.ccpocket.daemon

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SingleInstanceTest {
    @Test
    fun windows_netstat_parser_returns_only_exact_local_listeners() {
        val netstat = """
              TCP    127.0.0.1:8799         0.0.0.0:0              LISTENING       1234
              TCP    127.0.0.1:18799        0.0.0.0:0              LISTENING       2345
              TCP    127.0.0.1:50000        127.0.0.1:8799         ESTABLISHED     3456
              TCP    [::1]:8799             [::]:0                 LISTENING       4567
              UDP    127.0.0.1:8799         *:*                                    5678
        """.trimIndent()

        assertEquals(setOf("1234", "4567"), SingleInstance.windowsListeningPids(netstat, 8799))
    }

    /** No daemon on the pair port: the check returns "nothing taken over" and never calls exit. */
    @Test
    fun free_pair_port_passes_without_exit() {
        val port = ServerSocket(0, 50, loopback).use { it.localPort }
        val tookOver = SingleInstance.ensureSolo(port, takeover = false, exit = { error("exit($it) on a free port") }) {}
        assertEquals(false, tookOver)
    }

    /**
     * `--takeover`: the stopped daemon's servers close in their own shutdown hooks, so the direct port can
     * still be held after the pair port is free. [SingleInstance.retryBind] must ride that out with the real
     * engine the direct listener uses (Ktor CIO) — which also proves a CIO bind failure surfaces
     * synchronously from `start(wait = false)`, the thing both the retry and the old relay-only fallback rely on.
     */
    @Test
    fun retryBind_waits_out_a_port_the_old_daemon_releases_late() {
        val holder = ServerSocket(0, 50, loopback)
        val port = holder.localPort
        Thread { Thread.sleep(300); holder.close() }.apply { isDaemon = true; start() }
        val tries = AtomicInteger()
        val server = SingleInstance.retryBind(attempts = 50, waitMs = 50) {
            tries.incrementAndGet()
            embeddedServer(CIO, host = "127.0.0.1", port = port) {}.start(wait = false)
        }
        try {
            assertTrue(tries.get() > 1, "the first bind should have failed while the old holder was alive")
            assertTrue(SingleInstance.portInUse(port), "the retried listener must be up")
        } finally {
            server.stop(0, 0)
        }
    }

    @Test
    fun retryBind_gives_up_and_rethrows_the_bind_failure() {
        ServerSocket(0, 50, loopback).use { holder ->
            val tries = AtomicInteger()
            assertFailsWith<Exception> {
                SingleInstance.retryBind(attempts = 3, waitMs = 10) {
                    tries.incrementAndGet()
                    embeddedServer(CIO, host = "127.0.0.1", port = holder.localPort) {}.start(wait = false)
                }
            }
            assertEquals(3, tries.get())
        }
    }

    /**
     * Design-doc concern for takeover: re-binding the direct port right after the old daemon closed it might
     * hit TIME_WAIT. It does not: the server side closes first here (Connection: close), leaving its end of
     * the connection in TIME_WAIT, and a fresh CIO listener still binds the port at once — JDK server channels
     * carry SO_REUSEADDR on POSIX and Ktor never turns it off.
     */
    @Test
    fun direct_port_rebinds_while_old_connections_sit_in_time_wait() {
        val port = ServerSocket(0, 50, loopback).use { it.localPort }
        val old = embeddedServer(CIO, host = "127.0.0.1", port = port) {}.start(wait = false)
        Socket(loopback, port).use { s ->
            s.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".toByteArray())
            s.getInputStream().readBytes() // EOF = the server closed its end first
        }
        old.stop(0, 0)
        val fresh = embeddedServer(CIO, host = "127.0.0.1", port = port) {}.start(wait = false)
        try {
            assertTrue(SingleInstance.portInUse(port))
        } finally {
            fresh.stop(0, 0)
        }
    }

    private companion object {
        val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    }
}
