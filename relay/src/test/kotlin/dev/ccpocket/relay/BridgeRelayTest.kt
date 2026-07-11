package dev.ccpocket.relay

import dev.ccpocket.protocol.Role
import dev.ccpocket.relay.auth.Codec
import dev.ccpocket.relay.pairing.PairingService
import dev.ccpocket.relay.store.Db
import dev.ccpocket.relay.store.InMemoryRelayStore
import dev.ccpocket.relay.store.SqliteRelayStore
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Relay-side headless-bridge behavior (issue #91): authoritative marking + presence/push split. */
class BridgeRelayTest {

    @Test fun headless_is_authoritative_from_the_minting_ticket_not_the_redeeming_client() = runBlocking {
        val store = SqliteRelayStore(Db.open(":memory:"))
        store.insertAccount("acct", ByteArray(32), 0)
        val pairing = PairingService(store)
        val devPub = Codec.b64uEnc(ByteArray(32) { 3 })

        // MINT a headless (bridge) ticket; the redeeming client LIES headless=false → the ticket wins
        val mint = assertIs<PairingService.MintResult.Ok>(pairing.mint("acct", headless = true))
        val red = assertIs<PairingService.RedeemResult.Ok>(pairing.redeem(mint.ticket, devPub, clientHeadless = false))
        assertTrue(store.getDevice(red.deviceId)!!.headless, "a bridge ticket redeemed with headless=false must still land as a bridge")

        // a PHONE ticket redeemed with headless=true (a lying redeemer) is IGNORED → stays interactive:
        // the client cannot mark its own device presence-invisible either
        val mint2 = assertIs<PairingService.MintResult.Ok>(pairing.mint("acct", headless = false))
        val red2 = assertIs<PairingService.RedeemResult.Ok>(pairing.redeem(mint2.ticket, Codec.b64uEnc(ByteArray(32) { 9 }), clientHeadless = true))
        assertTrue(!store.getDevice(red2.deviceId)!!.headless, "the client's self-declared headless is ignored — the ticket is authoritative")
    }

    @Test fun headless_bridge_is_excluded_from_pushTargets() = runBlocking {
        val store = InMemoryRelayStore()
        store.insertAccount("acct", ByteArray(32), 0)
        val pairing = PairingService(store)
        val phone = assertIs<PairingService.RedeemResult.Ok>(pairing.redeem(assertIs<PairingService.MintResult.Ok>(pairing.mint("acct")).ticket, Codec.b64uEnc(ByteArray(32) { 1 })))
        val bot = assertIs<PairingService.RedeemResult.Ok>(pairing.redeem(assertIs<PairingService.MintResult.Ok>(pairing.mint("acct", headless = true)).ticket, Codec.b64uEnc(ByteArray(32) { 2 })))
        store.setPushToken(phone.deviceId, "apns", "phone-tok", 1)
        store.setPushToken(bot.deviceId, "apns", "bot-tok", 1) // even if a leaked bridge registers a token…

        // …it is NEVER a push target — the owner's session alerts can't reach a bot (issue #91 HIGH)
        assertEquals(listOf(phone.deviceId), store.pushTargets("acct").map { it.deviceId })
    }

    @Test fun sqlite_pushTargets_also_excludes_headless_and_marker_survives_touch() = runBlocking {
        val store = SqliteRelayStore(Db.open(":memory:"))
        store.insertAccount("acct", ByteArray(32), 0)
        val pairing = PairingService(store)
        val bot = assertIs<PairingService.RedeemResult.Ok>(pairing.redeem(assertIs<PairingService.MintResult.Ok>(pairing.mint("acct", headless = true)).ticket, Codec.b64uEnc(ByteArray(32) { 5 })))
        store.setPushToken(bot.deviceId, "apns", "bot-tok", 1)
        assertTrue(store.pushTargets("acct").isEmpty())
        store.touchDevice(bot.deviceId, 9) // a login touch must not silently drop the flag
        assertTrue(store.getDevice(bot.deviceId)!!.headless)
    }

    @Test fun interactive_and_headless_sockets_are_counted_separately() = runBlocking {
        val broker = Broker()
        val phone = Conn("acct", Role.DEVICE, "phone", sendText = {}, sendBinary = {}, close = {}, headless = false)
        val bot = Conn("acct", Role.DEVICE, "bot", sendText = {}, sendBinary = {}, close = {}, headless = true)
        broker.attachDevice(phone); broker.attachDevice(bot)

        assertEquals(2, broker.deviceCount("acct"))
        assertEquals(1, broker.interactiveDeviceCount("acct")) // only the phone gates presence/push
        assertEquals(1, broker.headlessDeviceCount("acct"))

        broker.detachDevice(bot)
        assertEquals(1, broker.interactiveDeviceCount("acct")) // the bot leaving doesn't move presence
        broker.detachDevice(phone)
        assertEquals(0, broker.interactiveDeviceCount("acct"))
    }
}
