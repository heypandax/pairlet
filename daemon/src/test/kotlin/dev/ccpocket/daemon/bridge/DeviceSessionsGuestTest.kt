package dev.ccpocket.daemon.bridge

import dev.ccpocket.daemon.DaemonCore
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.identity.PairedDevices
import dev.ccpocket.daemon.relay.DeviceSessions
import dev.ccpocket.protocol.AccessTier
import dev.ccpocket.protocol.Decision
import dev.ccpocket.protocol.Directories
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ListDirectories
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionVerdict
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.RunShellCommand
import dev.ccpocket.protocol.e2e.E2ECrypto
import dev.ccpocket.protocol.e2e.E2ESession
import dev.ccpocket.protocol.e2e.Wire
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The end-to-end enforcement path for a GUEST folder share (issue #115), the analogue of
 * DeviceSessionsBridgeTest: a real Noise handshake as a guest, then —
 *   1. the guest CONFIRMS (isGuest) and is NOT dropped — its OpenSession under the shared root routes
 *      (this is the regression guard for the "recognized" check that must cover guests, not only bridges);
 *   2. the handshake DaemonInfo (the LAN address) is WITHHELD from a guest, like a bridge;
 *   3. an OpenSession OUTSIDE the shared root is refused (share_forbidden_workdir);
 *   4. a management-plane frame (shell.run) is refused (share_forbidden) — but a verdict is NOT (the guest
 *      approves its own asks, the defining difference from a bridge);
 *   5. a guest key never lands in devices.json.
 */
class DeviceSessionsGuestTest {

    private val dir = createTempDirectory("ccp-ds-guest").toFile()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    private class Harness(dir: File) {
        val identity = Identity.loadOrCreate(File(dir, "identity.json"))
        val bridges = BridgeRegistry(File(dir, "bridges.json"))
        val outbound = Channel<Pair<String, ByteArray>>(Channel.UNLIMITED)
        val sessions = DeviceSessions(
            core = DaemonCore(emptyMap()), // no backends → OpenSession replies agent_unavailable, proving it ROUTED
            identity = identity,
            store = File(dir, "devices.json"),
            bridges = bridges,
        ) { deviceId, payload -> outbound.trySend(deviceId to payload) }
    }

    private suspend fun handshake(h: Harness, deviceId: String, keys: E2ECrypto.KeyPair, ticket: String): E2ESession {
        val init = E2ESession.initiator(keys.privateRaw, keys.publicRaw, h.identity.e2ePubRaw, psk = ticket.encodeToByteArray())
        h.sessions.onFrame(deviceId, Wire.payload(Wire.HANDSHAKE, init.ephPublic))
        val (_, resp) = h.outbound.receive()
        assertEquals(Wire.HANDSHAKE, Wire.payloadType(resp))
        return init.finish(Wire.payloadBody(resp))
    }

    private inline fun <reified T> decode(session: E2ESession, framed: ByteArray): T {
        assertEquals(Wire.TRANSPORT, Wire.payloadType(framed))
        val plain = session.open(Wire.payloadBody(framed))!!
        return PocketJson.decodeFromString<Envelope>(plain.decodeToString()).body as T
    }

    private suspend fun send(h: Harness, deviceId: String, session: E2ESession, body: Frame) {
        val env = Envelope("0", 0L, body = body)
        h.sessions.onFrame(deviceId, Wire.payload(Wire.TRANSPORT, session.seal(PocketJson.encodeToString(env).encodeToByteArray())))
    }

    private fun bindGuest(h: Harness, ticket: String, root: File): E2ECrypto.KeyPair {
        val spec = BridgeSpec.guest("alex", root.canonicalFile.path, AccessTier.COLLABORATE, Long.MAX_VALUE)
        h.bridges.recordIntent(ticket, spec, ttlMs = 240_000)
        h.sessions.onMintedTicket(ticket, headless = true) // a guest is minted as a headless-class ticket
        val keys = E2ECrypto.generateKeyPair()
        runBlocking { h.sessions.onDevicePaired("devGuest", b64.encodeToString(keys.publicRaw)) }
        return keys
    }

    @Test
    fun guest_confirms_routes_under_scope_and_daemonInfo_is_withheld() = runBlocking {
        val root = File(dir, "shared").apply { mkdirs() }
        val h = Harness(dir)
        val keys = bindGuest(h, "guest-ticket-1", root)
        // a guest key, like a bridge key, is NEVER written into the full-power allow-list
        assertTrue("devGuest" !in PairedDevices.load(File(dir, "devices.json")).keys)

        val session = handshake(h, "devGuest", keys, "guest-ticket-1")
        // FIRST transport frame confirms the guest binding. An OpenSession UNDER the root routes to the
        // (backend-less) registry → agent_unavailable. Getting that error PROVES the guest was recognized
        // and routed (the regression guard: an unrecognized device would be dropped silently instead).
        send(h, "devGuest", session, OpenSession(root.path))
        val err = decode<PocketError>(session, h.outbound.receive().second)
        assertEquals("agent_unavailable", err.code)
        assertTrue(h.bridges.isGuest("devGuest"))
        // the handshake DaemonInfo was WITHHELD (a guest is relay-only, no LAN address) — nothing preceded
        // the routed error
        assertTrue(h.outbound.tryReceive().isFailure)
    }

    @Test
    fun guest_open_outside_the_shared_root_is_refused() = runBlocking {
        val root = File(dir, "shared2").apply { mkdirs() }
        val outside = File(dir, "private").apply { mkdirs() }
        val h = Harness(dir)
        val keys = bindGuest(h, "guest-ticket-2", root)
        val session = handshake(h, "devGuest", keys, "guest-ticket-2")
        send(h, "devGuest", session, OpenSession(outside.path))
        // guest-friendly deny code (not the bridge_* wording)
        assertEquals("share_out_of_scope", decode<PocketError>(session, h.outbound.receive().second).code)
    }

    @Test
    fun guest_management_frame_is_refused_by_caps_and_a_foreign_verdict_by_ownership() = runBlocking {
        val root = File(dir, "shared3").apply { mkdirs() }
        val h = Harness(dir)
        val keys = bindGuest(h, "guest-ticket-3", root)
        val session = handshake(h, "devGuest", keys, "guest-ticket-3")
        // shell.run (the standalone terminal) is management-plane → refused at the CAPS layer for a guest
        send(h, "devGuest", session, RunShellCommand("c", "env", root.path))
        assertEquals("share_forbidden", decode<PocketError>(session, h.outbound.receive().second).code)
        // a verdict IS admitted by the caps (a guest approves its OWN asks — the bridge-vs-guest difference),
        // but the GUARD still refuses one for a convo the guest never opened: a guest can't approve another
        // session's ask. This proves the verdict reaches the guard (unlike a bridge, whose caps deny it outright).
        send(h, "devGuest", session, PermissionVerdict("not-mine", "a", Decision.ALLOW))
        assertEquals("share_not_own_session", decode<PocketError>(session, h.outbound.receive().second).code)
    }

    @Test
    fun revoking_a_confirmed_guest_seals_a_shareEnded_notice_before_the_cut() = runBlocking {
        // issue #115 follow-up: the owner's revoke must give the guest a PRECISE ending — a ShareEnded
        // notice sealed with the still-live E2E session BEFORE the credential/session prune — so the
        // guest terminal lights "Access ended · revoked" instead of a bare disconnect.
        val root = File(dir, "shared-revoke").apply { mkdirs() }
        val h = Harness(dir)
        val keys = bindGuest(h, "guest-ticket-rev", root)
        val session = handshake(h, "devGuest", keys, "guest-ticket-rev")
        send(h, "devGuest", session, OpenSession(root.path)) // FIRST transport frame → guest CONFIRMED
        decode<PocketError>(session, h.outbound.receive().second) // consume the routed agent_unavailable
        assertTrue(h.bridges.isGuest("devGuest"))

        val noticed = h.sessions.onDeviceRevoked("devGuest", dev.ccpocket.protocol.ShareEnded.REASON_REVOKED)
        assertTrue(noticed, "a live guest session must report the notice as sealed")
        // the notice is the ONLY frame emitted by the revoke, decryptable by the guest's live session…
        val ended = decode<dev.ccpocket.protocol.ShareEnded>(session, h.outbound.receive().second)
        assertEquals(dev.ccpocket.protocol.ShareEnded.REASON_REVOKED, ended.reason)
        assertTrue(h.outbound.tryReceive().isFailure) // …and nothing follows it
        // and the security anchor is untouched: the credential is gone the same call
        assertTrue(!h.bridges.isGuest("devGuest"))

        // revoking a CONFIRMED guest with NO live E2E session (offline at revoke) reports no notice —
        // the caller then skips the delivery grace and the guest falls back to the generic ended path
        val spec2 = BridgeSpec.guest("bob", root.canonicalFile.path, AccessTier.COLLABORATE, Long.MAX_VALUE)
        h.bridges.recordIntent("guest-ticket-rev2", spec2, ttlMs = 240_000)
        h.sessions.onMintedTicket("guest-ticket-rev2", headless = true)
        val keys2 = E2ECrypto.generateKeyPair()
        h.sessions.onDevicePaired("devGuest2", b64.encodeToString(keys2.publicRaw))
        val session2 = handshake(h, "devGuest2", keys2, "guest-ticket-rev2")
        send(h, "devGuest2", session2, OpenSession(root.path)) // FIRST transport frame → confirmed
        h.outbound.receive()                                   // consume its routed error
        assertTrue(h.bridges.isGuest("devGuest2"))
        // NOTE (#145/#146): onDisconnect no longer clears E2E sessions — they bind to the device
        // handshake, not the daemon's relay leg — so a session-less guest is one from BEFORE a daemon
        // RESTART: a fresh DeviceSessions over the same stores reloads the confirmed guest (guests.json)
        // but holds no live session, and its revoke can seal nothing.
        val restarted = Harness(dir)
        assertTrue(restarted.bridges.isGuest("devGuest2"))
        val offlineNoticed = restarted.sessions.onDeviceRevoked("devGuest2")
        assertTrue(!offlineNoticed)
        assertTrue(restarted.outbound.tryReceive().isFailure) // nothing was (or could be) sealed toward it
    }

    @Test
    fun guest_project_list_returns_only_the_shared_root_stamped_never_other_folders() = runBlocking {
        // VISIBILITY (issue #115 §1): a guest's ListDirectories must return ONLY its shared root — never any
        // of the owner's other project folders — stamped with the origin label + tier so the app renders the
        // "shared by …" row. scopedDirectories filters the daemon's full dir list to the scope and adds the
        // bare root, so even though the host machine has its own ~/.claude projects, a fresh temp root yields
        // exactly one stamped entry: the confidentiality boundary, proven over the wire.
        val root = File(dir, "shared-vis").apply { mkdirs() }.canonicalFile
        val h = Harness(dir)
        val keys = bindGuest(h, "guest-ticket-vis", root)
        val session = handshake(h, "devGuest", keys, "guest-ticket-vis")
        send(h, "devGuest", session, ListDirectories(root = "/"))
        val listing = decode<Directories>(session, h.outbound.receive().second)
        val entry = listing.entries.single() // ONLY the shared root — no other project folder leaks
        assertEquals(root.path, File(entry.path).canonicalFile.path)
        assertEquals("alex", entry.sharedBy)           // origin label stamped
        assertEquals(AccessTier.COLLABORATE, entry.shareTier) // tier badge stamped
    }
}
