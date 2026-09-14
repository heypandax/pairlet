package dev.ccpocket.daemon.execution

import dev.ccpocket.daemon.bridge.BridgeRegistry
import dev.ccpocket.daemon.handoff.CollaboratorCaps
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.review.PeerChannel
import dev.ccpocket.daemon.review.PeerLink
import dev.ccpocket.daemon.review.PeerLinkSecret
import dev.ccpocket.daemon.review.PeerLinkStore
import dev.ccpocket.daemon.review.PeerSession
import dev.ccpocket.daemon.review.b64
import dev.ccpocket.daemon.review.decodeCollaboratorInvite
import dev.ccpocket.daemon.review.decodeReviewContactInvite
import dev.ccpocket.daemon.review.encodeUri
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.CollaboratorInvite
import dev.ccpocket.protocol.CollaboratorPurpose
import dev.ccpocket.protocol.EXECUTION_GRANT_INVITE_URI_PREFIX
import dev.ccpocket.protocol.ExecutionGrantInfo
import dev.ccpocket.protocol.ExecutionGrantQuery
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.ToDaemon
import dev.ccpocket.protocol.collaboratorFingerprint
import dev.ccpocket.protocol.e2e.E2ECrypto
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #367 G0 — the fail-closed matrix for a new "remote task execution" link purpose, between TWO
 * independent daemon identities (separate identity files, separate account ids), over real Noise
 * handshakes and an in-process relay. No backend, no session, no CLI, no relay change.
 *
 * Every refusal asserts its SPECIFIC target-side reason ([ExecutionTarget.lastRefusal]) or transport-side
 * reason ([FixtureTransport.lastFailure]), not merely "no reply".
 */
class ExecutionGrantG0Test {

    private val root = createTempDirectory("ccp-exec-g0").toFile()
    private var clock = 1_800_000_000_000L
    private val targetDir = File(root, "target").apply { mkdirs() }
    private val sourceDir = File(root, "source").apply { mkdirs() }
    private val ws = File(root, "ws/app").apply { mkdirs() }
    private val ws2 = File(root, "ws/docs").apply { mkdirs() }
    private val targetIdentity = Identity.loadOrCreate(File(targetDir, "identity.json"))
    private val sourceIdentity = Identity.loadOrCreate(File(sourceDir, "identity.json"))
    private val grantsFile = File(targetDir, "execution-grants.json")
    private val tombFile = ExecutionGrantStore.tombstonePathFor(grantsFile)
    private val relay = FakeRelay(targetIdentity.accountId) { clock }
    /** How many upcoming relay-side revokes should fail (relay unreachable). */
    private var relayRevokeFailures = 0
    private val harness = ExecutionTargetHarness(
        targetDir, relay, targetIdentity, now = { clock },
        relayRevokeFails = { if (relayRevokeFailures > 0) { relayRevokeFailures--; true } else false },
    )
    private val store: ExecutionGrantStore get() = harness.store
    private val target: ExecutionTarget get() = harness.target
    private val transport = FixtureTransport(relay) { harness }
    private val sourceLinks = PeerLinkStore.load(File(sourceDir, "execution-links.json"), File(sourceDir, "execution-link-secrets.json"))
    private val source = ExecutionSource(transport, sourceLinks, ExecutionRelayPolicy(setOf("relay.test"))) { clock }

    @AfterTest
    fun cleanup() {
        targetDir.setWritable(true)
        grantsFile.setReadable(true)
        tombFile.setWritable(true)
        root.deleteRecursively()
    }

    /** Process restart of the TARGET daemon: same identity + store files, all in-memory state gone. */
    private fun restartTarget() = harness.restart()

    private fun draft(ceiling: PermissionMode = PermissionMode.DEFAULT, extra: Map<String, String> = emptyMap()) = ExecutionGrantDraft(
        sourceLabel = "Studio Mac",
        workspaces = mapOf("app" to ws.path) + extra,
        allowedAgents = listOf(AgentKind.CLAUDE),
        approvalCeiling = ceiling,
        ttlMs = 7L * 24 * 3600_000,
    )

    private suspend fun approve(): ExecutionTarget.Approval.Ok = assertIs(target.approve(draft()))

    private class Established(val grantId: String, val deviceId: String, val sourceFp: String, val invite: ExecutionInvite)

    private suspend fun establish(): Established {
        val appr = approve()
        val join = assertIs<ExecutionSource.Join.Ok>(source.join(appr.invite.encodeUri(), appr.grant.targetDaemonFingerprint))
        assertEquals("awaiting_owner_confirm", assertIs<ExecutionSource.Query.Ok>(source.query(appr.grant.grantId)).info.state)
        assertIs<ExecutionGrantStore.Write.Ok>(target.confirmSource(appr.grant.grantId, join.sourceFingerprint))
        return Established(appr.grant.grantId, join.link.deviceId, join.sourceFingerprint, appr.invite)
    }

    private fun linkOf(id: String): Pair<PeerLink, PeerLinkSecret> = sourceLinks.byId(id)!! to sourceLinks.secretOf(id)!!

    private fun deviceOf(id: String): String = sourceLinks.byId(id)!!.deviceId

    /** Send raw frames over a fresh connection; the replies (or the connection failure). */
    private suspend fun exchange(link: PeerLink, secret: PeerLinkSecret, frames: List<ToDaemon>): Result<List<Frame>> {
        val got = mutableListOf<Frame>()
        return runCatching {
            transport.dial(link, secret, object : PeerSession {
                override suspend fun onOpen(channel: PeerChannel) { frames.forEach { channel.send(it) } }
                override suspend fun onFrame(channel: PeerChannel, frame: Frame) { got += frame }
            })
            got
        }
    }

    /**
     * The source query failed with [sourceCode] AND the target refused for exactly [code].
     *
     * The default [sourceCode] is [code], not "no_reply": once a link is BOUND, the transport gate seals a
     * refusal back instead of going silent (G0's private responder simply dropped the frame). A call site
     * that is still at FIRST CONTACT — nothing bound, nothing to seal with — passes "no_reply" explicitly.
     */
    private suspend fun assertQueryRefused(grantId: String, code: String, sourceCode: String = code) {
        assertEquals(ExecutionSource.Query.Failed(sourceCode), source.query(grantId))
        assertEquals(code, target.lastRefusal(deviceOf(grantId)), "target refusal for $grantId")
    }

    /**
     * The link is DEAD, not merely refused: revoking a grant now also drops the CREDENTIAL and cuts its
     * live session, so there is nothing left to seal a refusal with. Strictly stronger than G0's sealed
     * `grant_revoked`, which left an authenticated transport alive on a dead grant.
     */
    private suspend fun assertLinkDead(grantId: String) {
        assertEquals(ExecutionSource.Query.Failed("no_reply"), source.query(grantId))
        assertFalse(harness.bridges.isExecution(deviceOf(grantId)), "credential removed with the grant")
    }

    /** No reply reaches the source (the credential never confirmed / just died), and the last thing any
     *  execution layer refused this device for was exactly [code]. */
    private suspend fun assertSilentlyRefused(grantId: String, code: String) {
        assertEquals(ExecutionSource.Query.Failed("no_reply"), source.query(grantId))
        assertEquals(code, target.lastRefusal(deviceOf(grantId)), "target refusal for $grantId")
    }

    private fun runDecision(e: Established, revision: Long, alias: String = "app", agent: AgentKind = AgentKind.CLAUDE) =
        ExecutionAuthorizer.run(store, e.deviceId, sourceLinks.secretOf(e.grantId)!!.publicKeyB64, e.grantId, revision, alias, agent, clock)

    private fun randomSecret(): String = b64(ByteArray(32).also { SecureRandom().nextBytes(it) })

    // ------------------------------------------------------------------ happy path

    @Test
    fun normal_establishment_pins_the_link_and_activates_only_after_owner_confirms_fingerprint(): Unit = runBlocking {
        assertNotEquals(sourceIdentity.accountId, targetIdentity.accountId) // two independent daemons
        val appr = approve()
        assertEquals(ExecutionGrantState.PENDING_REDEEM, appr.grant.state)
        val onDisk = grantsFile.readText()
        assertFalse(onDisk.contains(appr.invite.connectTicket), "raw ticket must never be persisted")
        assertFalse(onDisk.contains(appr.invite.inviteSecret), "invite secret must never be persisted")
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(grantsFile.toPath())))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(tombFile.toPath())), "tombstone log exists, 0600")
        assertEquals("relay.test", decodeExecutionInvite(appr.invite.encodeUri())!!.relayAuthority)

        val join = assertIs<ExecutionSource.Join.Ok>(source.join(appr.invite.encodeUri(), appr.grant.targetDaemonFingerprint))
        assertEquals(ExecutionRelayPolicy.Verdict.Known, join.relay)
        assertEquals("execution_link_not_machine_identity", join.fingerprintScope)
        val derived = ExecutionPsk.derive(appr.invite.connectTicket, appr.invite.inviteSecret)
        assertEquals(derived, sourceLinks.secretOf(appr.grant.grantId)!!.ticket, "the link holds the DERIVED PSK, not the relay ticket")
        val q1 = assertIs<ExecutionSource.Query.Ok>(source.query(appr.grant.grantId)).info
        assertEquals("awaiting_owner_confirm", q1.state)
        assertTrue(q1.workspaceAliases.isEmpty() && q1.agents.isEmpty(), "nothing disclosed before owner confirmation")
        assertNull(sourceLinks.secretOf(appr.grant.grantId)!!.ticket, "first-contact PSK cleared on first authenticated reply")

        val bound = store.byId(appr.grant.grantId)!!
        assertEquals(ExecutionGrantState.AWAITING_OWNER_CONFIRM, bound.state)
        assertEquals(join.link.deviceId, bound.sourceDeviceId)
        assertEquals(join.sourceFingerprint, bound.sourceLinkFingerprint)
        assertNull(bound.ticketHash)
        assertNull(bound.sourceLinkConfirmedAt)
        val e = Established(appr.grant.grantId, join.link.deviceId, join.sourceFingerprint, appr.invite)
        assertEquals(ExecutionAuthorizer.Decision.Deny("grant_awaiting_owner_confirm"), runDecision(e, 1))

        assertIs<ExecutionGrantStore.Write.Ok>(target.confirmSource(e.grantId, join.sourceFingerprint.uppercase().replace("-", " ")))
        val q2 = assertIs<ExecutionSource.Query.Ok>(source.query(e.grantId)).info
        assertEquals("active", q2.state)
        assertEquals(listOf("app"), q2.workspaceAliases)
        assertEquals(listOf("claude"), q2.agents)
        assertEquals("default", q2.approvalCeiling)
        assertEquals(listOf(derived, ""), transport.psksOffered, "derived PSK on first contact, empty PSK after")
        assertEquals(clock, store.byId(e.grantId)!!.sourceLinkConfirmedAt, "first empty-PSK reconnect recorded")
        val allow = assertIs<ExecutionAuthorizer.Decision.Allow>(runDecision(e, 1))
        assertEquals(ws.canonicalPath, allow.workdir)
    }

    // ------------------------------------------------------------------ HIGH-1: compromised relay

    @Test
    fun a_relay_holding_the_ticket_cannot_bind_a_key_of_its_choice(): Unit = runBlocking {
        val appr = approve()
        val rawTicket = relay.lastMinted!!
        assertEquals(appr.invite.connectTicket, rawTicket, "the relay knows the raw ticket")
        val evilKeys = transport.generateKeys()
        val evil = relay.issue(evilKeys.publicKeyB64)
        val evilLink = PeerLink("evil", "evil", "wss://relay.test", targetIdentity.accountId, targetIdentity.e2ePubB64, evil.deviceId, "fp", clock)
        val guesses = listOf(rawTicket, ExecutionPsk.derive(rawTicket, randomSecret()), null)
        for (psk in guesses) {
            val secret = PeerLinkSecret("evil", evil.credential, evilKeys.privateKeyB64, evilKeys.publicKeyB64, ticket = psk)
            val got = exchange(evilLink, secret, listOf(ExecutionGrantQuery(appr.grant.grantId))).getOrThrow()
            assertTrue(got.isEmpty(), "relay-chosen key got an answer with psk=$psk")
            assertFalse(harness.bridges.isExecution(evil.deviceId), "psk=$psk: never an execution credential")
        }
        assertNull(store.boundTo(evil.deviceId))
        assertEquals(ExecutionGrantState.PENDING_REDEEM, store.byId(appr.grant.grantId)!!.state)
        assertNull(store.pendingByTicketHash(hashHex(rawTicket.encodeToByteArray())), "the raw ticket is not what is armed")

        // The relay's unsolicited announce ALSO burned the armed slot (the shared chain arms LIFO), so the
        // real source can no longer redeem this invite either — a hostile relay can DENY a pairing, which
        // it can do to a phone/bridge/guest/collaborator invite just the same. What it cannot do is BIND:
        // the grant is untouched, and a fresh approval establishes normally.
        assertIs<ExecutionSource.Join.Ok>(source.join(appr.invite.encodeUri(), appr.grant.targetDaemonFingerprint))
        assertEquals(ExecutionSource.Query.Failed("no_reply"), source.query(appr.grant.grantId))
        assertEquals(ExecutionGrantState.PENDING_REDEEM, store.byId(appr.grant.grantId)!!.state)

        // …once the burned invite's intent has lapsed: #207 admits ONE pairing at a time, so a spoiled
        // invite blocks the next approval for its ticket TTL + grace, and nothing shortens that
        assertIs<ExecutionTarget.Approval.Refused>(target.approve(draft()))
        clock += 600_000L + BridgeRegistry.INTENT_GRACE_MS + 1
        val again = approve()
        val join = assertIs<ExecutionSource.Join.Ok>(source.join(again.invite.encodeUri(), again.grant.targetDaemonFingerprint))
        assertEquals("awaiting_owner_confirm", assertIs<ExecutionSource.Query.Ok>(source.query(again.grant.grantId)).info.state)
        assertEquals(join.link.deviceId, store.byId(again.grant.grantId)!!.sourceDeviceId)
    }

    @Test
    fun relay_reported_ticket_lifetime_is_capped_locally(): Unit = runBlocking {
        relay.mintTtlSec = 86_400
        val appr = approve()
        assertEquals(ExecutionTarget.MAX_TICKET_TTL_SEC, appr.invite.ttlSec)
        assertEquals(clock + ExecutionTarget.MAX_TICKET_TTL_SEC * 1000L, appr.grant.ticketExpiresAt)
        clock += ExecutionTarget.MAX_TICKET_TTL_SEC * 1000L + ExecutionTarget.TICKET_GRACE_MS + 1
        assertIs<ExecutionSource.Join.Ok>(source.join(appr.invite.encodeUri(), appr.grant.targetDaemonFingerprint))
        assertEquals(ExecutionSource.Query.Failed("no_reply"), source.query(appr.grant.grantId))
        assertFalse(harness.bridges.isExecution(deviceOf(appr.grant.grantId)), "the lapsed intent classifies nothing")
        assertEquals(ExecutionGrantState.PENDING_REDEEM, store.byId(appr.grant.grantId)!!.state)
    }

    // ------------------------------------------------------------------ HIGH-2: source-side verification

    @Test
    fun fingerprints_are_strong_and_relay_hosts_need_explicit_confirmation(): Unit = runBlocking {
        val appr = approve()
        val fp = appr.grant.targetDaemonFingerprint
        assertTrue(Regex("^([a-km-np-z2-9]{4}-){6}[a-km-np-z2-9]{4}$").matches(fp), fp)
        assertTrue(ExecutionFingerprint.BITS >= 128)
        assertEquals(ExecutionSource.Join.Refused("target_fingerprint_mismatch"), source.join(appr.invite.encodeUri(), collaboratorFingerprint(targetIdentity.e2ePubB64)))
        assertEquals(0, relay.redeems)

        val foreign = appr.invite.copy(relay = "wss://other.example:8443").encodeUri()
        assertEquals("other.example:8443", decodeExecutionInvite(foreign)!!.relayAuthority)
        assertEquals(ExecutionSource.Join.Refused("relay_unconfirmed"), source.join(foreign, fp))
        assertEquals(ExecutionSource.Join.Refused("relay_confirmation_mismatch"), source.join(foreign, fp, confirmedRelayAuthority = "other.example"))
        assertEquals(0, relay.redeems, "an unconfirmed relay never sees the ticket")
        val ok = assertIs<ExecutionSource.Join.Ok>(source.join(foreign, fp, confirmedRelayAuthority = "OTHER.example:8443"))
        assertEquals(ExecutionRelayPolicy.Verdict.ConfirmedByUser, ok.relay)
        assertIs<ExecutionRelayPolicy.Verdict.Refused>(ExecutionRelayPolicy.DEFAULT.requireKnownRelay(appr.invite, null))
        assertEquals(ExecutionRelayPolicy.Verdict.Known, ExecutionRelayPolicy.DEFAULT.requireKnownRelay(appr.invite.copy(relay = "wss://pocket.ark-nexus.cc"), null))
    }

    // ------------------------------------------------------------------ purpose isolation

    @Test
    fun review_or_handoff_invites_and_credentials_cannot_reach_the_execution_purpose(): Unit = runBlocking {
        val appr = approve()
        val uri = appr.invite.encodeUri()
        val blob = uri.removePrefix(EXECUTION_GRANT_INVITE_URI_PREFIX)
        assertNull(decodeReviewContactInvite(uri)); assertNull(decodeCollaboratorInvite(uri))
        assertNull(decodeReviewContactInvite(blob)); assertNull(decodeCollaboratorInvite(blob))
        for (purpose in listOf(CollaboratorPurpose.REVIEW, CollaboratorPurpose.SESSION_HANDOFF)) {
            val inv = CollaboratorInvite("wss://relay.test", targetIdentity.accountId, targetIdentity.e2ePubB64, relay.mint().ticket, purpose = purpose)
            assertNull(decodeExecutionInvite(inv.encodeUri()), "$purpose uri at execution door")
            assertNull(decodeExecutionInvite(inv.encodeUri().substringAfter('#')), "$purpose blob at execution door")
            assertEquals(ExecutionSource.Join.Refused("invite_invalid"), source.join(inv.encodeUri(), appr.grant.targetDaemonFingerprint))
        }

        val reviewTicket = relay.mint().ticket
        val keys = transport.generateKeys()
        val cred = relay.redeem(reviewTicket, keys.publicKeyB64)!!
        val reviewLink = PeerLink("pl_review", "review", "wss://relay.test", targetIdentity.accountId, targetIdentity.e2ePubB64, cred.deviceId, "fp", clock)
        for (psk in listOf(reviewTicket, null)) {
            val secret = PeerLinkSecret("pl_review", cred.credential, keys.privateKeyB64, keys.publicKeyB64, ticket = psk)
            val got = exchange(reviewLink, secret, listOf(ExecutionGrantQuery(appr.grant.grantId))).getOrThrow()
            assertTrue(got.isEmpty(), "review credential got an execution reply with psk=$psk")
            assertFalse(harness.bridges.isExecution(cred.deviceId), "psk=$psk: never an execution credential")
        }
        assertNull(store.boundTo(cred.deviceId))
        assertEquals(ExecutionGrantState.PENDING_REDEEM, store.byId(appr.grant.grantId)!!.state)
        assertEquals(ExecutionSource.Query.Failed("link_unknown"), source.query("pl_review"))

        // the review redeem above consumed the armed slot (see the relay-chosen-key test): re-approve once
        // the spoiled invite's intent has lapsed (#207 admits ONE pairing at a time), and then the
        // execution door — and only the execution door — establishes
        clock += 600_000L + BridgeRegistry.INTENT_GRACE_MS + 1
        val again = approve()
        val join = assertIs<ExecutionSource.Join.Ok>(source.join(again.invite.encodeUri(), again.grant.targetDaemonFingerprint))
        assertEquals("awaiting_owner_confirm", assertIs<ExecutionSource.Query.Ok>(source.query(again.grant.grantId)).info.state)
        assertEquals(join.link.deviceId, store.byId(again.grant.grantId)!!.sourceDeviceId)

        for (p in CollaboratorPurpose.entries) {
            assertFalse(CollaboratorCaps.ingressAllowed(ExecutionGrantQuery("x"), p))
            assertFalse(CollaboratorCaps.egressAllowed(ExecutionGrantInfo("x", 1, "active", 0), p))
        }
    }

    @Test
    fun an_owner_device_can_never_be_relabelled_as_an_execution_link(): Unit = runBlocking {
        // an ordinary phone pairs first, the normal way, through the SAME chain
        val ownerPhone = "owner-phone"
        val phoneKeys = E2ECrypto.generateKeyPair()
        // headless=true only so this pairing does not stamp the #91 interactive-exclusion clock, which would
        // (correctly) refuse the execution approval below for the next ~130 wall-clock seconds. Nothing else
        // about the flag matters here: the device still lands in the FULL-POWER allow-list, which is the point.
        harness.sessions.onMintedTicket("phone-ticket", headless = true)
        harness.sessions.onDevicePaired(ownerPhone, b64(phoneKeys.publicRaw))
        assertTrue(harness.sessions.isKnownDevice(ownerPhone), "precondition: the phone is a full-power device")

        // an execution grant is approved and its PSK armed; the relay then re-announces the OWNER's
        // deviceId under a fresh key — the confusion the `isKnownDevice` gate exists for
        val appr = approve()
        harness.sessions.onDevicePaired(ownerPhone, b64(E2ECrypto.generateKeyPair().publicRaw))
        assertFalse(harness.bridges.isExecution(ownerPhone), "an owner deviceId is never an execution credential")
        assertNull(store.boundTo(ownerPhone))
        assertEquals(ExecutionGrantState.PENDING_REDEEM, store.byId(appr.grant.grantId)!!.state)

        // …and the bind hook itself refuses it even if the chain ever handed it over
        assertFalse(target.onRedeemed(ownerPhone, b64(E2ECrypto.generateKeyPair().publicRaw), appr.grant.grantId))
        assertEquals("known_device", target.lastRefusal(ownerPhone))
        assertEquals(ExecutionGrantState.PENDING_REDEEM, store.byId(appr.grant.grantId)!!.state)
    }

    @Test
    fun an_execution_credential_is_barred_from_the_lan_gate_and_never_gets_daemon_info(): Unit = runBlocking {
        val e = establish()
        // devices.json is the ONLY allow-list the LAN gate reads, and an execution key is never in it…
        assertFalse(dev.ccpocket.daemon.identity.PairedDevices.load(File(targetDir, "devices.json")).containsKey(e.deviceId))
        // …and the gate ALSO refuses it explicitly (#367), which is what Main wires
        assertTrue(harness.bridges.isRestricted(e.deviceId))
        // no DaemonInfo ever reached it: the whole exchange is grant frames and nothing else
        val (link, secret) = linkOf(e.grantId)
        val replies = exchange(link, secret, listOf(ExecutionGrantQuery(e.grantId))).getOrThrow()
        assertTrue(replies.all { it is ExecutionGrantInfo }, "an execution link receives nothing but its own plane: $replies")
        assertTrue(replies.none { it is dev.ccpocket.protocol.DaemonInfo })
    }

    @Test
    fun an_unwired_execution_plane_refuses_every_frame(): Unit = runBlocking {
        val e = establish()
        harness.unwirePlane()
        val (link, secret) = linkOf(e.grantId)
        val err = assertIs<PocketError>(exchange(link, secret, listOf(ExecutionGrantQuery(e.grantId))).getOrThrow().single())
        assertEquals("execution_unavailable", err.code)
    }

    @Test
    fun execution_link_cannot_issue_review_session_handoff_or_owner_requests(): Unit = runBlocking {
        val e = establish()
        val (link, secret) = linkOf(e.grantId)
        val executionOwn = sealedLeaves(ToDaemon::class).filter { ExecutionCaps.ingressAllowed(instantiateFrame(it) as Frame) }
        assertEquals(5, executionOwn.size, "the execution plane owns exactly the grant query + 4 run frames")
        val leaves = sealedLeaves(ToDaemon::class) - executionOwn.toSet()
        assertTrue(leaves.size > 50, "sanity: the whole request surface is enumerated")
        val frames = leaves.map { instantiateFrame(it) as ToDaemon }
        val replies = exchange(link, secret, frames).getOrThrow()
        assertEquals(frames.size, replies.size)
        replies.zip(frames).forEach { (r, f) ->
            val err = assertIs<PocketError>(r, "reply to ${f.name()}")
            assertEquals("execution_forbidden", err.code, "reply to ${f.name()}")
        }
        assertEquals("execution_forbidden", target.lastRefusal(e.deviceId))
        val oversize = exchange(link, secret, listOf(ExecutionGrantQuery(e.grantId))).getOrThrow() // control: still fine
        assertTrue(oversize.single() is ExecutionGrantInfo)
        val mismatch = exchange(link, secret, listOf(ExecutionGrantQuery("xg_someoneelse123"))).getOrThrow().single()
        assertEquals("grant_mismatch", assertIs<PocketError>(mismatch).code)
        val g = store.byId(e.grantId)!!
        assertEquals(ExecutionGrantState.ACTIVE, g.state)
        assertEquals(1, g.revision)
        assertEquals(1, store.all().size)
    }

    // ------------------------------------------------------------------ first contact interruptions

    /**
     * A first reply LOST IN FLIGHT leaves the two ends keyed apart, and the shared credential chain has no
     * way back: the target released its first-contact PSK the moment the frame decrypted (that is what
     * proves the ticket), while the source, having seen no reply, still offers it — and a restricted
     * credential is deliberately given no empty-PSK twin (#161's twin exists only for devices already in
     * the FULL-POWER allow-list, which an execution link never joins).
     *
     * This is a DELIBERATE loss against the G0 prototype, which retained the proven PSK in memory for
     * exactly this case. G0's retention lived in its own responder; G1 deleted that responder rather than
     * run a second handshake path beside the production one, so an execution link now behaves exactly like
     * a bridge / guest / collaborator whose first reply is lost. The remedy is the one the user already
     * accepted for the restart case: revoke + re-approve. The grant is BOUND (so the owner sees a link to
     * revoke) and carries no run authority until the fingerprint is confirmed.
     */
    @Test
    fun a_lost_first_reply_wedges_the_link_and_is_recovered_by_revoke_and_reapprove(): Unit = runBlocking {
        val appr = approve()
        val id = appr.grant.grantId
        val derived = ExecutionPsk.derive(appr.invite.connectTicket, appr.invite.inviteSecret)
        val join = assertIs<ExecutionSource.Join.Ok>(source.join(appr.invite.encodeUri(), appr.grant.targetDaemonFingerprint))
        transport.dropNextReplies = 1
        assertEquals(ExecutionSource.Query.Failed("no_reply"), source.query(id))
        assertEquals("reply_lost", transport.lastFailure)
        assertEquals(derived, sourceLinks.secretOf(id)!!.ticket, "source keeps its first-contact PSK")
        assertEquals(ExecutionGrantState.AWAITING_OWNER_CONFIRM, store.byId(id)!!.state, "bound, but no run authority")

        repeat(3) { assertIs<ExecutionSource.Query.Failed>(source.query(id)) }
        assertTrue(transport.psksOffered.all { it == derived }, "source never escapes to an empty PSK: ${transport.psksOffered}")
        val e = Established(id, join.link.deviceId, join.sourceFingerprint, appr.invite)
        assertEquals(ExecutionAuthorizer.Decision.Deny("grant_awaiting_owner_confirm"), runDecision(e, 1))

        assertIs<ExecutionGrantStore.Write.Ok>(target.revoke(id))
        assertTrue(join.link.deviceId in relay.revoked)
        assertFalse(harness.bridges.isExecution(join.link.deviceId), "the credential dies with the grant")
        val again = approve()
        assertIs<ExecutionSource.Join.Ok>(source.join(again.invite.encodeUri(), again.grant.targetDaemonFingerprint))
        assertEquals("awaiting_owner_confirm", assertIs<ExecutionSource.Query.Ok>(source.query(again.grant.grantId)).info.state)
    }

    @Test
    fun interrupted_first_handshake_before_any_frame_binds_nothing(): Unit = runBlocking {
        val appr = approve()
        val id = appr.grant.grantId
        assertIs<ExecutionSource.Join.Ok>(source.join(appr.invite.encodeUri(), appr.grant.targetDaemonFingerprint))
        transport.cutAfterHandshake = 1
        assertEquals(ExecutionSource.Query.Failed("no_reply"), source.query(id))
        assertEquals("connection_cut", transport.lastFailure)
        assertEquals(ExecutionGrantState.PENDING_REDEEM, store.byId(id)!!.state)
        assertNull(store.byId(id)!!.sourceDeviceId)
        assertNotNull(sourceLinks.secretOf(id)!!.ticket)
        assertIs<ExecutionSource.Query.Ok>(source.query(id))
        assertEquals(ExecutionGrantState.AWAITING_OWNER_CONFIRM, store.byId(id)!!.state)
    }

    /** A target restart between the bind and the source's next connect: same wedge, same remedy, and the
     *  source still never escapes to an empty PSK (nothing here ever offers it one). */
    @Test
    fun lost_first_reply_then_target_restart_stays_wedged_and_never_offers_an_empty_psk(): Unit = runBlocking {
        val appr = approve()
        val id = appr.grant.grantId
        val derived = ExecutionPsk.derive(appr.invite.connectTicket, appr.invite.inviteSecret)
        val join = assertIs<ExecutionSource.Join.Ok>(source.join(appr.invite.encodeUri(), appr.grant.targetDaemonFingerprint))
        transport.dropNextReplies = 1
        assertIs<ExecutionSource.Query.Failed>(source.query(id)) // attempt 1
        restartTarget() // the armed PSK was memory-only (decision 09-14: never persisted)

        repeat(3) { assertIs<ExecutionSource.Query.Failed>(source.query(id)) }
        assertTrue(transport.psksOffered.all { it == derived }, "source never escapes to an empty PSK: ${transport.psksOffered}")
        assertEquals(ExecutionGrantState.AWAITING_OWNER_CONFIRM, store.byId(id)!!.state)
        val e = Established(id, join.link.deviceId, join.sourceFingerprint, appr.invite)
        assertEquals(ExecutionAuthorizer.Decision.Deny("grant_awaiting_owner_confirm"), runDecision(e, 1))

        assertIs<ExecutionGrantStore.Write.Ok>(target.revoke(id))
        assertTrue(join.link.deviceId in relay.revoked)
        val again = approve()
        assertIs<ExecutionSource.Join.Ok>(source.join(again.invite.encodeUri(), again.grant.targetDaemonFingerprint))
        assertEquals("awaiting_owner_confirm", assertIs<ExecutionSource.Query.Ok>(source.query(again.grant.grantId)).info.state)
    }

    // ------------------------------------------------------------------ ticket lifetime

    @Test
    fun ticket_replay_and_second_consumption_are_refused(): Unit = runBlocking {
        val appr = approve()
        val uri = appr.invite.encodeUri()
        val derived = ExecutionPsk.derive(appr.invite.connectTicket, appr.invite.inviteSecret)
        assertIs<ExecutionSource.Join.Ok>(source.join(uri, appr.grant.targetDaemonFingerprint))
        val other = ExecutionSource(transport, PeerLinkStore.inMemory(), ExecutionRelayPolicy(setOf("relay.test"))) { clock }
        assertEquals(ExecutionSource.Join.Refused("redeem_refused"), other.join(uri, appr.grant.targetDaemonFingerprint))
        assertIs<ExecutionSource.Query.Ok>(source.query(appr.grant.grantId))

        val evilKeys = transport.generateKeys()
        val evil = relay.issue(evilKeys.publicKeyB64)
        // nothing is armed any more (the legitimate source consumed it), so this announce is UNANCHORED:
        // parked provisional, never allow-listed, and its first frame is dropped whatever it is sealed with
        assertFalse(harness.bridges.isRestricted(evil.deviceId))
        val evilLink = sourceLinks.byId(appr.grant.grantId)!!.copy(id = "evil", deviceId = evil.deviceId)
        val evilSecret = PeerLinkSecret("evil", evil.credential, evilKeys.privateKeyB64, evilKeys.publicKeyB64, ticket = derived)
        for (psk in listOf(derived, null)) {
            val got = exchange(evilLink, evilSecret.copy(ticket = psk), listOf(ExecutionGrantQuery(appr.grant.grantId)))
            assertTrue(got.getOrDefault(emptyList()).isEmpty(), "replayed PSK (psk=$psk) got an answer")
            assertFalse(harness.bridges.isExecution(evil.deviceId), "psk=$psk")
        }
        assertNull(store.boundTo(evil.deviceId))

        val late = approve()
        clock += 600_000L + ExecutionTarget.TICKET_GRACE_MS + 1
        relay.ignoreExpiry = true
        assertIs<ExecutionSource.Join.Ok>(source.join(late.invite.encodeUri(), late.grant.targetDaemonFingerprint))
        assertEquals(ExecutionSource.Query.Failed("no_reply"), source.query(late.grant.grantId))
        assertFalse(harness.bridges.isExecution(deviceOf(late.grant.grantId)))
        assertEquals(ExecutionGrantState.PENDING_REDEEM, store.byId(late.grant.grantId)!!.state)
    }

    // ------------------------------------------------------------------ restart

    @Test
    fun target_restart_after_approval_before_redeem_never_binds(): Unit = runBlocking {
        val appr = approve()
        restartTarget()
        assertIs<ExecutionSource.Join.Ok>(source.join(appr.invite.encodeUri(), appr.grant.targetDaemonFingerprint))
        // nothing is armed any more, so the announce is UNANCHORED: the key is parked provisional, never
        // allow-listed, and its first frame is dropped without a word (a provisional credential has no
        // sealed refusal to send — it was never confirmed as anything)
        repeat(2) { assertEquals(ExecutionSource.Query.Failed("no_reply"), source.query(appr.grant.grantId)) }
        assertEquals(
            ExecutionSource.Query.Failed(ExecutionSource.FIRST_CONTACT_STUCK), source.query(appr.grant.grantId),
        )
        assertFalse(harness.bridges.isExecution(deviceOf(appr.grant.grantId)))
        val g = store.byId(appr.grant.grantId)!!
        assertEquals(ExecutionGrantState.PENDING_REDEEM, g.state)
        assertNull(g.sourceDeviceId)
        val derived = ExecutionPsk.derive(appr.invite.connectTicket, appr.invite.inviteSecret)
        assertTrue(transport.psksOffered.all { it == derived })
    }

    @Test
    fun target_restart_keeps_active_grant_with_same_scope_pin_and_restrictions(): Unit = runBlocking {
        val e = establish()
        restartTarget()
        val q = assertIs<ExecutionSource.Query.Ok>(source.query(e.grantId)).info
        assertEquals("active", q.state)
        val before = store.byId(e.grantId)!!
        restartTarget()
        assertEquals(before, store.byId(e.grantId), "persisted grant reloads identical")
        assertEquals(before.revision, q.revision)
        val (link, secret) = linkOf(e.grantId)
        val denied = exchange(link, secret, listOf(instantiateFrame(dev.ccpocket.protocol.ListReviewRequests::class) as ToDaemon)).getOrThrow().single()
        assertEquals("execution_forbidden", assertIs<PocketError>(denied).code)
        // a different static key presenting the bound deviceId, on a link that HAS reconnected before: a wrong key, not a stuck link
        val imposter = transport.generateKeys()
        val got = exchange(link, secret.copy(privateKeyB64 = imposter.privateKeyB64, publicKeyB64 = imposter.publicKeyB64), listOf(ExecutionGrantQuery(e.grantId))).getOrThrow()
        assertTrue(got.isEmpty(), "a wrong static key on a pinned link decrypts nothing")
        assertEquals(e.deviceId, store.byId(e.grantId)!!.sourceDeviceId, "…and changes nothing about the pin")
    }

    // ------------------------------------------------------------------ store damage / tampering

    @Test
    fun corrupt_unreadable_or_tampered_store_refuses_everything_and_preserves_bytes(): Unit = runBlocking {
        val e = establish()
        val good = grantsFile.readText()
        val mintsBefore = relay.mints

        grantsFile.writeText("{not json")
        restartTarget()
        assertFalse(store.available)
        assertQueryRefused(e.grantId, "store_unavailable")
        assertEquals(ExecutionTarget.Approval.Refused("store_unavailable"), target.approve(draft()))
        assertEquals(mintsBefore, relay.mints, "no ticket minted against an unavailable store")
        assertEquals(ExecutionGrantStore.Write.Unavailable, target.revoke(e.grantId))
        assertEquals("{not json", grantsFile.readText(), "evidence untouched")

        grantsFile.writeText(good)
        assertTrue(grantsFile.setReadable(false, false))
        restartTarget()
        assertFalse(store.available)
        assertQueryRefused(e.grantId, "store_unavailable")
        assertTrue(grantsFile.setReadable(true, false))

        val g = PocketJsonGrant(good)
        val tampered = mapOf(
            "bypass ceiling" to good.replace("\"approvalCeiling\":\"default\"", "\"approvalCeiling\":\"bypassPermissions\""),
            "unknown version" to good.replace("\"v\":1", "\"v\":2"),
            "unknown state" to good.replace("\"state\":\"active\"", "\"state\":\"superuser\""),
            "root is /" to good.replace("\"canonicalRoot\":\"${g.root}\"", "\"canonicalRoot\":\"/\""),
            "root has .." to good.replace("\"canonicalRoot\":\"${g.root}\"", "\"canonicalRoot\":\"${g.root}/../..\""),
            "expiry extended" to good.replace("\"expiresAt\":${g.expiresAt}", "\"expiresAt\":${g.createdAt + ExecutionPolicy.MAX_TTL_MS + 1}"),
            "other target key" to run {
                val otherPub = b64(E2ECrypto.generateKeyPair().publicRaw)
                good.replace(targetIdentity.e2ePubB64, otherPub).replace(g.targetFp, ExecutionFingerprint.of(otherPub))
            },
        )
        for ((what, text) in tampered) {
            assertNotEquals(good, text, "tamper '$what' must change the file")
            grantsFile.writeText(text)
            restartTarget()
            assertFalse(store.available, "tamper '$what' must make the store unavailable")
            assertQueryRefused(e.grantId, "store_unavailable")
            assertEquals(text, grantsFile.readText(), "tamper '$what' evidence untouched")
        }

        grantsFile.writeText(good)
        restartTarget()
        assertTrue(store.available)
        assertEquals("active", assertIs<ExecutionSource.Query.Ok>(source.query(e.grantId)).info.state)
    }

    /** The few stored values the tamper cases need, read back through the real store. */
    private inner class PocketJsonGrant(json: String) {
        private val gr = ExecutionGrantStore.load(grantsFile.also { it.writeText(json) }, targetIdentity.e2ePubB64).all().single()
        val root = gr.workspaces.single().canonicalRoot
        val expiresAt = gr.expiresAt
        val createdAt = gr.createdAt
        val targetFp = gr.targetDaemonFingerprint
    }

    @Test
    fun a_corrupt_tombstone_log_makes_the_store_unavailable_but_a_torn_tail_is_ignored(): Unit = runBlocking {
        val e = establish()
        tombFile.appendText("{broken\n")
        restartTarget()
        assertEquals("store_unavailable", store.unavailableReason)
        assertQueryRefused(e.grantId, "store_unavailable")

        // an append that never completed (no trailing newline) is not a durable revoke
        tombFile.writeText("{\"grantId\":\"${e.grantId}\",\"reason\":\"owner_revoked\",\"at\":1}")
        restartTarget()
        assertTrue(store.available)
        assertEquals(ExecutionGrantState.ACTIVE, store.byId(e.grantId)!!.state)
    }

    // ------------------------------------------------------------------ revoke durability (decision 09-14)

    @Test
    fun revoke_persist_failure_makes_the_whole_execution_plane_unavailable_until_retry_succeeds(): Unit = runBlocking {
        val a = establish()
        val b = establish()
        val mintsBefore = relay.mints
        assertTrue(tombFile.exists())
        assertTrue(targetDir.setWritable(false, false)) // main file cannot be rewritten; the tombstone log can still append

        assertEquals(ExecutionGrantStore.Write.PersistFailed, target.revoke(a.grantId))
        assertEquals(ExecutionGrantState.REVOKED, store.byId(a.grantId)!!.state, "in force in memory immediately")
        assertEquals("revoke_persist_pending", store.unavailableReason)
        // the OTHER, untouched grant is refused too: the whole execution plane is unavailable
        assertQueryRefused(b.grantId, "revoke_persist_pending")
        assertEquals(ExecutionTarget.Approval.Refused("revoke_persist_pending"), target.approve(draft()))
        assertEquals(mintsBefore, relay.mints)
        assertEquals(ExecutionGrantStore.Write.Unavailable, target.reviseScope(b.grantId, draft()))
        assertEquals(ExecutionGrantStore.Write.Unavailable, target.confirmSource(b.grantId, b.sourceFp))

        clock += 5_000
        target.maintain() // retry is due but the disk still refuses
        assertEquals("revoke_persist_pending", store.unavailableReason)

        assertTrue(targetDir.setWritable(true, false))
        clock += ExecutionGrantStore.RETRY_MAX_MS
        target.maintain()
        assertTrue(store.available)
        assertEquals("active", assertIs<ExecutionSource.Query.Ok>(source.query(b.grantId)).info.state)
        restartTarget()
        assertEquals(ExecutionGrantState.REVOKED, store.byId(a.grantId)!!.state, "revocation durable after the retry")
    }

    @Test
    fun a_tombstone_overrides_an_active_main_row_after_restart(): Unit = runBlocking {
        val e = establish()
        assertTrue(targetDir.setWritable(false, false))
        assertEquals(ExecutionGrantStore.Write.PersistFailed, target.revoke(e.grantId))
        assertTrue(targetDir.setWritable(true, false))
        assertTrue(grantsFile.readText().contains("\"state\":\"active\""), "the main file never received the revoke")
        assertTrue(tombFile.readText().contains(e.grantId))

        restartTarget() // crash before any retry
        assertTrue(store.available)
        val g = store.byId(e.grantId)!!
        assertEquals(ExecutionGrantState.REVOKED, g.state)
        assertEquals("owner_revoked", g.endedReason)
        assertFalse(grantsFile.readText().contains("\"state\":\"active\""), "reconciled back into the main file on load")
        relay.revoked -= e.deviceId // prove the TARGET refuses, independent of the relay
        assertLinkDead(e.grantId)
    }

    @Test
    fun an_unwritable_tombstone_log_makes_the_store_unavailable(): Unit = runBlocking {
        val e = establish()
        assertTrue(tombFile.setWritable(false, false))
        assertTrue(targetDir.setWritable(false, false))
        assertEquals(ExecutionGrantStore.Write.PersistFailed, target.revoke(e.grantId))
        assertEquals("tombstone_write_failed", store.unavailableReason)
        relay.revoked -= e.deviceId
        assertLinkDead(e.grantId) // the credential dies at the owner's word, whether or not the tombstone landed
        assertEquals(ExecutionTarget.Approval.Refused("tombstone_write_failed"), target.approve(draft()))

        assertTrue(tombFile.setWritable(true, false))
        assertTrue(targetDir.setWritable(true, false))
        clock += ExecutionGrantStore.RETRY_MAX_MS
        target.maintain()
        assertTrue(store.available)
        restartTarget()
        assertEquals(ExecutionGrantState.REVOKED, store.byId(e.grantId)!!.state)
    }

    @Test
    fun a_failed_relay_revoke_is_reported_and_retried(): Unit = runBlocking {
        val e = establish()
        relayRevokeFailures = 2
        assertIs<ExecutionGrantStore.Write.Ok>(target.revoke(e.grantId))
        assertEquals(mapOf(e.deviceId to 1), target.pendingRelayRevokes())
        assertEquals("relay_revoke_pending", target.lastRefusal(e.deviceId))
        assertFalse(e.deviceId in relay.revoked)
        // the target cuts the link on its own while the relay still honours the credential
        assertLinkDead(e.grantId)

        target.maintain() // backoff not elapsed
        assertEquals(mapOf(e.deviceId to 1), target.pendingRelayRevokes())
        clock += 1_001
        target.maintain() // due, fails again
        assertEquals(mapOf(e.deviceId to 2), target.pendingRelayRevokes())
        clock += 2_001
        target.maintain() // due, succeeds
        assertTrue(target.pendingRelayRevokes().isEmpty())
        assertTrue(e.deviceId in relay.revoked)
    }

    // ------------------------------------------------------------------ revoke / expiry / revision / clock

    @Test
    fun revision_change_revoke_and_expiry_refuse_new_requests(): Unit = runBlocking {
        val a = establish()
        assertIs<ExecutionAuthorizer.Decision.Allow>(runDecision(a, 1))
        val revised = assertIs<ExecutionGrantStore.Write.Ok>(target.reviseScope(a.grantId, draft(extra = mapOf("docs" to ws2.path))))
        assertEquals(2, revised.grant.revision)
        assertEquals(ExecutionAuthorizer.Decision.Deny("revision_changed"), runDecision(a, 1))
        assertIs<ExecutionAuthorizer.Decision.Allow>(runDecision(a, 2, alias = "docs"))
        assertEquals(ExecutionAuthorizer.Decision.Deny("agent_not_allowed"), runDecision(a, 2, agent = AgentKind.CODEX))
        val q = assertIs<ExecutionSource.Query.Ok>(source.query(a.grantId)).info
        assertEquals(2, q.revision)
        assertEquals(listOf("app", "docs"), q.workspaceAliases)

        val (link, secret) = linkOf(a.grantId)
        val got = mutableListOf<Frame>()
        transport.dial(link, secret, object : PeerSession {
            override suspend fun onOpen(channel: PeerChannel) {
                channel.send(ExecutionGrantQuery(a.grantId))
                target.revoke(a.grantId)
                channel.send(ExecutionGrantQuery(a.grantId))
            }
            override suspend fun onFrame(channel: PeerChannel, frame: Frame) { got += frame }
        })
        assertEquals(1, got.size, "only the pre-revoke frame is answered: $got")
        assertFalse(harness.bridges.isExecution(a.deviceId), "the credential AND its live session died at revoke")
        assertEquals(ExecutionAuthorizer.Decision.Deny("grant_revoked"), runDecision(a, 2))
        assertEquals(ExecutionSource.Query.Failed("no_reply"), source.query(a.grantId))
        assertEquals("relay_rejected", transport.lastFailure, "relay credential revoked")
        assertTrue(a.deviceId in relay.revoked)
        relay.revoked -= a.deviceId
        assertLinkDead(a.grantId)
        assertEquals(ExecutionGrantStore.Write.Refused("grant_revoked"), target.confirmSource(a.grantId, a.sourceFp))
        assertIs<ExecutionGrantStore.Write.Refused>(target.reviseScope(a.grantId, draft()))
        restartTarget()
        assertEquals(ExecutionGrantState.REVOKED, store.byId(a.grantId)!!.state, "revocation is durable")

        val b = establish()
        clock = store.byId(b.grantId)!!.expiresAt
        assertEquals(ExecutionAuthorizer.Decision.Deny("grant_expired"), runDecision(b, 1))
        assertQueryRefused(b.grantId, "grant_expired")
        assertIs<ExecutionGrantStore.Write.Refused>(target.reviseScope(b.grantId, draft()))
    }

    @Test
    fun rolling_the_clock_back_does_not_revive_an_expired_grant(): Unit = runBlocking {
        val e = establish()
        val expiresAt = store.byId(e.grantId)!!.expiresAt
        clock = expiresAt + 1
        assertQueryRefused(e.grantId, "grant_expired")
        clock = expiresAt - 3_600_000
        assertEquals(ExecutionAuthorizer.Decision.Deny("grant_expired"), runDecision(e, 1))
        assertQueryRefused(e.grantId, "grant_expired")
        assertIs<ExecutionTarget.Approval.Ok>(target.approve(draft()))
        restartTarget()
        assertQueryRefused(e.grantId, "grant_expired")
    }

    @Test
    fun revisions_cannot_extend_a_grant_past_the_lifetime_cap(): Unit = runBlocking {
        val e = establish()
        val createdAt = store.byId(e.grantId)!!.createdAt
        clock += 6L * 24 * 3600_000 // still inside the 7-day grant
        val w = assertIs<ExecutionGrantStore.Write.Ok>(target.reviseScope(e.grantId, draft().copy(ttlMs = ExecutionPolicy.MAX_TTL_MS)))
        assertEquals(createdAt + ExecutionPolicy.MAX_TTL_MS, w.grant.expiresAt)
    }

    @Test
    fun widening_writes_do_not_commit_when_the_store_cannot_persist(): Unit = runBlocking {
        val appr = approve()
        assertIs<ExecutionSource.Join.Ok>(source.join(appr.invite.encodeUri(), appr.grant.targetDaemonFingerprint))
        assertTrue(targetDir.setWritable(false, false))
        assertSilentlyRefused(appr.grant.grantId, "bind_persist_failed")
        assertEquals(ExecutionGrantState.PENDING_REDEEM, store.byId(appr.grant.grantId)!!.state)
        assertEquals(ExecutionTarget.Approval.Refused("persist_failed"), target.approve(draft()))
        assertTrue(store.available, "a failed WIDENING write does not take the store down")
        assertTrue(targetDir.setWritable(true, false))
        // the intent was consumed by the refused bind: the invite is spent, exactly as one-attempt-per-invite requires
        assertEquals(ExecutionSource.Query.Failed("no_reply"), source.query(appr.grant.grantId))
        assertNull(store.byId(appr.grant.grantId)!!.sourceDeviceId)
    }

    // ------------------------------------------------------------------ ceiling / fingerprints

    @Test
    fun bypass_ceiling_is_refused_before_any_ticket_is_minted(): Unit = runBlocking {
        assertEquals(ExecutionTarget.Approval.Refused("ceiling_bypass_forbidden"), target.approve(draft(PermissionMode.BYPASS_PERMISSIONS)))
        assertEquals(
            ExecutionTarget.Approval.Refused("agent_unsupported"),
            target.approve(draft().copy(allowedAgents = listOf(AgentKind.CLAUDE, AgentKind.OPENCODE))),
        )
        assertEquals(0, relay.mints)
        assertTrue(store.all().isEmpty())
        assertFalse(grantsFile.exists())

        val e = establish()
        assertEquals(ExecutionGrantStore.Write.Refused("ceiling_bypass_forbidden"), target.reviseScope(e.grantId, draft(PermissionMode.BYPASS_PERMISSIONS)))
        assertEquals(1, store.byId(e.grantId)!!.revision)
        val forged = store.byId(e.grantId)!!.copy(grantId = "xg_forgedforged", sourceDeviceId = null, sourceLinkPub = null,
            sourceLinkFingerprint = null, state = ExecutionGrantState.REVOKED, approvalCeiling = PermissionMode.BYPASS_PERMISSIONS)
        assertEquals(ExecutionGrantStore.Write.Refused("invariant_ceiling"), store.insert(forged))
    }

    @Test
    fun fingerprint_mismatch_on_either_side_is_refused(): Unit = runBlocking {
        val appr = approve()
        val uri = appr.invite.encodeUri()
        val wrong = ExecutionFingerprint.of(b64(E2ECrypto.generateKeyPair().publicRaw))
        assertEquals(ExecutionSource.Join.Refused("target_fingerprint_mismatch"), source.join(uri, wrong))
        assertEquals(0, relay.redeems)
        val swappedPub = b64(E2ECrypto.generateKeyPair().publicRaw)
        val tampered = appr.invite.copy(targetDaemonPub = swappedPub).encodeUri()
        assertEquals(ExecutionSource.Join.Refused("target_fingerprint_mismatch"), source.join(tampered, appr.grant.targetDaemonFingerprint))
        assertEquals(0, relay.redeems)

        val join = assertIs<ExecutionSource.Join.Ok>(source.join(uri, appr.grant.targetDaemonFingerprint))
        assertIs<ExecutionSource.Query.Ok>(source.query(appr.grant.grantId))
        assertEquals(ExecutionGrantStore.Write.Refused("fingerprint_mismatch"), target.confirmSource(appr.grant.grantId, wrong))
        val g = store.byId(appr.grant.grantId)!!
        assertEquals(ExecutionGrantState.REVOKED, g.state)
        assertEquals("fingerprint_mismatch", g.endedReason)
        assertTrue(join.link.deviceId in relay.revoked)
        relay.revoked -= join.link.deviceId
        assertLinkDead(appr.grant.grantId)
    }
}
