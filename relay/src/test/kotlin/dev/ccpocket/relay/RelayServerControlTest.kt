package dev.ccpocket.relay

import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.Ping
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.Pong
import dev.ccpocket.protocol.RegisterPush
import dev.ccpocket.protocol.Role
import dev.ccpocket.protocol.Route
import dev.ccpocket.relay.store.InMemoryRelayStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** device control TEXT plane (RelayServer.handleDeviceControl): the app-level liveness echo. */
class RelayServerControlTest {

    private fun server() = RelayServer("127.0.0.1", 0, InMemoryRelayStore(), clock = { 1_000 })

    private fun control(body: dev.ccpocket.protocol.Frame): String =
        PocketJson.encodeToString(Envelope(id = "d", ts = 0, to = Route.RELAY, body = body))

    @Test fun device_ping_is_echoed_as_pong_on_the_same_socket() = runBlocking {
        val sent = mutableListOf<String>()
        val conn = Conn("acct", Role.DEVICE, "dev1", sendText = { sent += it }, sendBinary = {}, close = {})

        server().handleDeviceControl(conn, control(Ping(ts = 777)))

        val echo = assertIs<Pong>(PocketJson.decodeFromString<Envelope>(sent.single()).body)
        assertEquals(777, echo.ts) // the Pong carries the Ping's ts back verbatim
    }

    @Test fun non_ping_control_produces_no_echo() = runBlocking {
        val sent = mutableListOf<String>()
        // no device row → RegisterPush is a silent no-op, and it must never trigger a Pong either
        val conn = Conn("acct", Role.DEVICE, "dev1", sendText = { sent += it }, sendBinary = {}, close = {})

        server().handleDeviceControl(conn, control(RegisterPush("fcm", "tok")))
        server().handleDeviceControl(conn, control(Pong(ts = 1))) // a stray pong isn't echoed

        assertTrue(sent.isEmpty())
    }
}
