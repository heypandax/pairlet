package dev.ccpocket.daemon.bridge

import dev.ccpocket.daemon.execution.ExecutionGrantDraft
import dev.ccpocket.daemon.execution.ExecutionGrantStore
import dev.ccpocket.daemon.execution.ExecutionRelayPolicy
import dev.ccpocket.daemon.execution.ExecutionSource
import dev.ccpocket.daemon.execution.ExecutionTarget
import dev.ccpocket.daemon.execution.ExecutionTargetHarness
import dev.ccpocket.daemon.execution.FakeRelay
import dev.ccpocket.daemon.execution.FixtureTransport
import dev.ccpocket.daemon.execution.encodeUri
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.identity.PairedDevices
import dev.ccpocket.daemon.peer.PeerChannel
import dev.ccpocket.daemon.peer.PeerLinkStore
import dev.ccpocket.daemon.peer.PeerSession
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ClientCaps
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.ExecutionRunAccepted
import dev.ccpocket.protocol.ExecutionRunSubmit
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SessionGone
import dev.ccpocket.protocol.ToDaemon
import dev.ccpocket.protocol.e2e.E2ECrypto
import dev.ccpocket.protocol.e2e.E2ESession
import dev.ccpocket.protocol.executionAgentWire
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An owner revoke of a full-power device (`pairlet devices revoke`) shares the retired-credential tombstone with the
 * Collaborator Link and folder-share guest retirements. Revoking one phone — and restarting before the relay
 * confirms — must change exactly two files (devices.json and the tombstone file) and leave everything else as it
 * was: the remote-execution grant, credential and link, a bridge, the other owner phone, and the collaborator
 * tombstones an earlier upgrade left behind.
 */
class OwnerDeviceRevocationIsolationTest {

    private val root = createTempDirectory("ccp-owner-revoke-iso").toFile()
    private var clock = 1_800_000_000_000L
    private val targetDir = File(root, "target").apply { mkdirs() }
    private val sourceDir = File(root, "source").apply { mkdirs() }
    private val ws = File(root, "ws/app").apply { mkdirs() }
    private val targetIdentity = Identity.loadOrCreate(File(targetDir, "identity.json"))
    private val relay = FakeRelay(targetIdentity.accountId) { clock }
    private val harness = ExecutionTargetHarness(targetDir, relay, targetIdentity, now = { clock })
    private val transport = FixtureTransport(relay) { harness }
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    private val tombstoneFile = File(targetDir, RetiredCredentialStore.FILE_NAME)
    private val devicesFile = File(targetDir, "devices.json")

    @AfterTest
    fun cleanup() { root.deleteRecursively() }

    private fun sourceLinks() =
        PeerLinkStore.load(File(sourceDir, "execution-links.json"), File(sourceDir, "execution-link-secrets.json"))

    private fun source() = ExecutionSource(transport, sourceLinks(), ExecutionRelayPolicy(setOf("relay.test"))) { clock }

    private fun draft() = ExecutionGrantDraft(
        sourceLabel = "Studio Mac",
        workspaces = mapOf("app" to ws.path),
        allowedAgents = listOf(AgentKind.CLAUDE),
        approvalCeiling = PermissionMode.DEFAULT,
        ttlMs = 7L * 24 * 3600_000,
    )

    private suspend fun handshakeAs(deviceId: String, keys: E2ECrypto.KeyPair, psk: String): E2ESession? {
        val init = E2ESession.initiator(keys.privateRaw, keys.publicRaw, targetIdentity.e2ePubRaw, psk.encodeToByteArray())
        return harness.handshake(deviceId, init.ephPublic)?.let { init.finish(it) }
    }

    private suspend fun exchange(deviceId: String, session: E2ESession, frame: Frame): List<Frame> {
        harness.send(deviceId, session, frame)
        return harness.drain(deviceId, session)
    }

    private suspend fun dial(linkId: String, frames: List<ToDaemon>): List<Frame> {
        val links = sourceLinks()
        val got = mutableListOf<Frame>()
        transport.dial(links.byId(linkId)!!, links.secretOf(linkId)!!, object : PeerSession {
            override suspend fun onOpen(channel: PeerChannel) { frames.forEach { channel.send(it) } }
            override suspend fun onFrame(channel: PeerChannel, frame: Frame) { got += frame }
        })
        return got
    }

    private suspend fun owner(id: String): E2ECrypto.KeyPair {
        val keys = E2ECrypto.generateKeyPair()
        harness.sessions.onMintedTicket("t-$id", headless = true) // headless: no #91 exclusion stamp
        harness.sessions.onDevicePaired(id, b64.encodeToString(keys.publicRaw))
        exchange(id, assertNotNull(handshakeAs(id, keys, "t-$id")), ClientCaps())
        return keys
    }

    /** sha256 of every file under both daemon directories, except the two an owner revoke is meant to touch. */
    private fun snapshot(): Map<String, String> =
        listOf(targetDir, sourceDir).flatMap { dir ->
            dir.walkTopDown().filter { it.isFile && it.name != devicesFile.name && it.name != tombstoneFile.name }.map { f ->
                f.relativeTo(root).path to MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
            }
        }.toMap()

    @Test
    fun revoking_one_phone_touches_only_devices_json_and_the_tombstones(): Unit = runBlocking {
        // ---- stage: an ACTIVE execution grant + credential and its source link, a bridge, two owner phones, and the
        // collaborator tombstones an earlier upgrade left behind
        val appr = assertIs<ExecutionTarget.Approval.Ok>(harness.target.approve(draft()))
        var source = source()
        val join = assertIs<ExecutionSource.Join.Ok>(source.join(appr.invite.encodeUri(), appr.grant.targetDaemonFingerprint))
        val grantId = appr.grant.grantId
        assertEquals("awaiting_owner_confirm", assertIs<ExecutionSource.Query.Ok>(source.query(grantId)).info.state)
        assertIs<ExecutionGrantStore.Write.Ok>(harness.target.confirmSource(grantId, join.sourceFingerprint))
        val execDevice = join.link.deviceId

        val bridgeTicket = "t-dev-bridge"
        assertTrue(harness.bridges.recordIntent(bridgeTicket, BridgeSpec("feishu-bot", listOf(ws.canonicalPath)), ttlMs = 600_000))
        harness.sessions.onMintedTicket(bridgeTicket, headless = true)
        val bridgeKeys = E2ECrypto.generateKeyPair()
        harness.sessions.onDevicePaired("dev-bridge", b64.encodeToString(bridgeKeys.publicRaw))
        exchange("dev-bridge", assertNotNull(handshakeAs("dev-bridge", bridgeKeys, bridgeTicket)), OpenSession(ws.path))
        assertTrue(harness.bridges.isBridge("dev-bridge"))

        val goneKeys = owner("phone-gone")
        val keptKeys = owner("phone-kept")
        val collabTombstones = setOf("dev-collab-1", "dev-collab-2")
        assertTrue(RetiredCredentialStore.save(collabTombstones, tombstoneFile))
        harness.restart() // the collaborator tombstones load like an upgrade left them
        assertEquals(setOf("phone-gone", "phone-kept"), PairedDevices.load(devicesFile).keys)

        // ---- the revoke, then a restart before the relay ever processes it
        val before = snapshot()
        assertTrue(harness.sessions.revokeOwnerDevice("phone-gone"))
        harness.restart()
        assertEquals(before, snapshot(), "only devices.json and the tombstone file may change")
        assertEquals(setOf("phone-kept"), PairedDevices.load(devicesFile).keys)
        assertEquals(collabTombstones + "phone-gone", RetiredCredentialStore.load(tombstoneFile), "added next to the old ids")
        assertFalse(tombstoneFile.readText().contains(b64.encodeToString(goneKeys.publicRaw)), "the tombstone holds no key")
        assertEquals(collabTombstones + "phone-gone", harness.sessions.pendingRetiredRevocations().map { it.deviceId }.toSet())
        // the guest retirement that runs after every attach has nothing to do and leaves the tombstones alone
        assertFalse(harness.sessions.retireLegacyGuests())
        assertEquals(collabTombstones + "phone-gone", RetiredCredentialStore.load(tombstoneFile))

        // the replay still announces the revoked phone; an interactive ticket is armed — it stays out
        harness.sessions.onMintedTicket("phone-window")
        harness.sessions.onDevicePaired("phone-gone", b64.encodeToString(goneKeys.publicRaw))
        assertFalse("phone-gone" in PairedDevices.load(devicesFile).keys)
        assertNull(handshakeAs("phone-gone", goneKeys, ""))
        assertNull(handshakeAs("phone-gone", goneKeys, "phone-window"))

        // ---- the execution grant and link still work: query + a real run
        assertTrue(harness.bridges.isExecution(execDevice))
        source = source()
        assertEquals("active", assertIs<ExecutionSource.Query.Ok>(source.query(grantId)).info.state)
        val accepted = dial(grantId, listOf(ExecutionRunSubmit("rq_after", grantId, 1, "app", executionAgentWire(AgentKind.CLAUDE), "hello")))
        assertIs<ExecutionRunAccepted>(accepted.single(), "the real run plane accepts a run: $accepted")

        // ---- the bridge still handshakes and routes (agent_unavailable: past the bridge gate, no backend here)
        val bridge = assertNotNull(handshakeAs("dev-bridge", bridgeKeys, ""))
        assertEquals("agent_unavailable", assertIs<PocketError>(exchange("dev-bridge", bridge, OpenSession(ws.path)).single()).code)

        // ---- and the other phone still routes as an owner
        val kept = assertNotNull(handshakeAs("phone-kept", keptKeys, ""))
        assertTrue(exchange("phone-kept", kept, ClientCaps()).any { it is DaemonInfo })
        assertEquals("ghost", assertIs<SessionGone>(exchange("phone-kept", kept, SendPrompt("ghost", "hi")).single()).convoId)

        // ---- the relay confirms each revoke: only that id's tombstone goes; the collaborator ones are untouched
        harness.sessions.onRelayDeviceRevoked("phone-gone")
        assertEquals(collabTombstones, RetiredCredentialStore.load(tombstoneFile))
        assertEquals(setOf("phone-kept"), PairedDevices.load(devicesFile).keys)
    }
}
