package dev.ccpocket.daemon.execution

import dev.ccpocket.daemon.bridge.BridgeRegistry
import dev.ccpocket.daemon.bridge.BridgeSpec
import dev.ccpocket.daemon.bridge.CredentialKind
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.identity.PairedDevices
import dev.ccpocket.daemon.server.LanE2E
import dev.ccpocket.daemon.server.WsConnection
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.protocol.AccessTier
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ClientCaps
import dev.ccpocket.protocol.CollaboratorPurpose
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.EXECUTION_FRAME_BUDGET_BYTES
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.ExecutionGrantInfo
import dev.ccpocket.protocol.ExecutionGrantQuery
import dev.ccpocket.protocol.ExecutionRunCancel
import dev.ccpocket.protocol.ExecutionRunResult
import dev.ccpocket.protocol.ExecutionRunStatus
import dev.ccpocket.protocol.ExecutionRunSubmit
import dev.ccpocket.protocol.executionAgentWire
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.LanHello
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.ToDaemon
import dev.ccpocket.protocol.e2e.E2ECrypto
import dev.ccpocket.protocol.e2e.E2ESession
import dev.ccpocket.protocol.e2e.Wire
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.Base64
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.ktor.websocket.Frame as WsFrame

/**
 * #367 G1-A — the CREDENTIAL CHAIN matrix, through the production transport only.
 *
 * The G0 fixture proves the ceremony end to end; this file proves ISOLATION in both directions on the
 * shared chain, which is the part that could not exist while the execution responder lived beside it:
 *
 *  - an EXECUTION credential is refused every owner / session / review / handoff frame and never receives
 *    a [DaemonInfo], never gets a push slot, never joins the LAN gate;
 *  - a bridge / guest / collaborator (of every purpose) / provisional key is refused every RUN frame;
 *  - a confirmed credential of an UNKNOWN kind falls into the explicit `else` refusal, not the owner branch;
 *  - the #207 mint slot is mutually exclusive between an execution approval and every other pairing;
 *  - the frame byte budget is enforced before anything becomes work;
 *  - an execution credential reloads from `execution-credentials.json` after a restart and is STILL
 *    restricted (the downgrade-safety file split, one level further than guests/collaborators).
 */
class ExecutionCredentialChainTest {

    private val root = createTempDirectory("ccp-exec-chain").toFile()
    private var clock = 1_800_000_000_000L
    private val targetDir = File(root, "target").apply { mkdirs() }
    private val ws = File(root, "ws/app").apply { mkdirs() }
    private val identity = Identity.loadOrCreate(File(targetDir, "identity.json"))
    private val relay = FakeRelay(identity.accountId) { clock }
    private val harness = ExecutionTargetHarness(targetDir, relay, identity, now = { clock })
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    @AfterTest
    fun cleanup() { root.deleteRecursively() }

    private fun draft() = ExecutionGrantDraft(
        sourceLabel = "Studio Mac",
        workspaces = mapOf("app" to ws.path),
        allowedAgents = listOf(AgentKind.CLAUDE),
        approvalCeiling = PermissionMode.DEFAULT,
        ttlMs = 7L * 24 * 3600_000,
    )

    /** Every frame the execution plane owns, as a source would send it for [grantId]. */
    private fun runFrames(grantId: String, revision: Long = 1) = listOf<ToDaemon>(
        ExecutionRunSubmit("r1", grantId, revision, "app", executionAgentWire(AgentKind.CLAUDE), "do the thing"),
        ExecutionRunStatus("r2", grantId, "run1"),
        ExecutionRunResult("r3", grantId, "run1"),
        ExecutionRunCancel("r4", grantId, "run1"),
    )

    // ---------------------------------------------------------------- direct DeviceSessions drive

    private suspend fun handshakeAs(deviceId: String, keys: E2ECrypto.KeyPair, psk: String): E2ESession {
        val init = E2ESession.initiator(keys.privateRaw, keys.publicRaw, identity.e2ePubRaw, psk.encodeToByteArray())
        val msg2 = assertNotNull(harness.handshake(deviceId, init.ephPublic), "no handshake for $deviceId")
        return init.finish(msg2)
    }

    /** A restricted credential of [spec]'s kind, bound through the real intent → finalize chain. */
    private suspend fun restricted(deviceId: String, spec: BridgeSpec, lapse: Boolean = false): E2ESession {
        val ticket = "t-$deviceId"
        // WALL clock on purpose: [BridgeRegistry.finalize] is called by DeviceSessions with its default
        // (wall) clock, so an intent that must be DEAD by then has to lapse against that same clock
        assertTrue(harness.bridges.recordIntent(ticket, spec, ttlMs = if (lapse) 60 else 600_000))
        harness.sessions.onMintedTicket(ticket, headless = true)
        val keys = E2ECrypto.generateKeyPair()
        harness.sessions.onDevicePaired(deviceId, b64.encodeToString(keys.publicRaw))
        val session = handshakeAs(deviceId, keys, ticket)
        if (lapse) delay(200) // the intent lapses before the first transport frame: never confirmed
        return session
    }

    /** An owner phone, paired the ordinary way. */
    private suspend fun owner(deviceId: String): E2ESession {
        val ticket = "phone-$deviceId"
        harness.sessions.onMintedTicket(ticket)
        val keys = E2ECrypto.generateKeyPair()
        harness.sessions.onDevicePaired(deviceId, b64.encodeToString(keys.publicRaw))
        return handshakeAs(deviceId, keys, ticket)
    }

    /** Establish a live, ACTIVE execution link and return (grantId, deviceId, session). */
    private suspend fun execution(): Triple<String, String, E2ESession> {
        val appr = assertIs<ExecutionTarget.Approval.Ok>(harness.target.approve(draft()))
        val psk = ExecutionPsk.derive(appr.invite.connectTicket, appr.invite.inviteSecret)
        val keys = E2ECrypto.generateKeyPair()
        val deviceId = "exec-dev"
        harness.sessions.onDevicePaired(deviceId, b64.encodeToString(keys.publicRaw))
        val session = handshakeAs(deviceId, keys, psk)
        // first frame proves the derived PSK and binds the grant
        assertIs<ExecutionGrantInfo>(exchange(deviceId, session, ExecutionGrantQuery(appr.grant.grantId)).single())
        assertIs<ExecutionGrantStore.Write.Ok>(harness.target.confirmSource(appr.grant.grantId, ExecutionFingerprint.of(b64.encodeToString(keys.publicRaw))))
        return Triple(appr.grant.grantId, deviceId, session)
    }

    private suspend fun exchange(deviceId: String, session: E2ESession, vararg frames: Frame): List<Frame> {
        for (f in frames) harness.send(deviceId, session, f)
        return harness.drain(deviceId, session)
    }

    // ---------------------------------------------------------------- the matrix

    @Test
    fun an_execution_credential_is_refused_every_frame_outside_its_own_plane(): Unit = runBlocking {
        val (grantId, deviceId, session) = execution()
        // positive control first: its own plane answers
        assertIs<ExecutionGrantInfo>(exchange(deviceId, session, ExecutionGrantQuery(grantId)).single())

        val forbidden = sealedLeaves(ToDaemon::class)
            .map { instantiateFrame(it) as ToDaemon }
            .filterNot { ExecutionCaps.ingressAllowed(it) }
        assertTrue(forbidden.size > 50, "sanity: the whole request surface is enumerated")
        for (f in forbidden) {
            val replies = exchange(deviceId, session, f)
            val err = assertIs<PocketError>(replies.singleOrNull(), "reply to ${f.name()}: $replies")
            assertEquals("execution_forbidden", err.code, "reply to ${f.name()}")
        }
        // …including the one frame every other client sends first
        assertTrue(exchange(deviceId, session, ClientCaps()).all { it is PocketError })
        assertTrue(harness.drain(deviceId, session).none { it is DaemonInfo }, "an execution link never learns the LAN address")
    }

    @Test
    fun the_run_frames_reach_the_plane_and_nothing_else_does(): Unit = runBlocking {
        val (grantId, deviceId, session) = execution()
        // a submit is ACCEPTED by the real run plane (this fixture has no backend, so it goes no further —
        // the point here is that the frame reached RunService and got a run id, not that a run completes)
        val accepted = assertIs<dev.ccpocket.protocol.ExecutionRunAccepted>(
            exchange(deviceId, session, runFrames(grantId).first()).single(),
        )
        assertEquals(1, accepted.revision, "the accept echoes the grant revision it was accepted under")
        assertTrue(accepted.runId.isNotBlank())
        // retrying the SAME requestId returns the ORIGINAL run, never a second one
        val retry = assertIs<dev.ccpocket.protocol.ExecutionRunAccepted>(
            exchange(deviceId, session, runFrames(grantId).first()).single(),
        )
        assertEquals(accepted.runId, retry.runId); assertTrue(retry.duplicate)
        // status / result / cancel for a run of ANOTHER grant's making are not found, never leaked
        for (f in runFrames(grantId).drop(1)) {
            val err = assertIs<PocketError>(exchange(deviceId, session, f).single(), "reply to ${f.name()}")
            assertEquals("run_not_found", err.code, "reply to ${f.name()}")
        }
        // a run frame naming ANOTHER grant never even reaches the plane
        val other = assertIs<PocketError>(exchange(deviceId, session, ExecutionRunStatus("r", "xg_someoneelse1", "run1")).single())
        assertEquals("grant_mismatch", other.code)
        // …nor does one carrying a stale revision
        val stale = assertIs<PocketError>(exchange(deviceId, session, runFrames(grantId, revision = 9).first()).single())
        assertEquals("revision_changed", stale.code)
        // …nor one naming a workspace the owner never granted
        val alias = assertIs<PocketError>(
            exchange(deviceId, session, ExecutionRunSubmit("r5", grantId, 1, "not-a-workspace", executionAgentWire(AgentKind.CLAUDE), "x")).single(),
        )
        assertEquals("workspace_not_allowed", alias.code)
        // …nor one naming an agent the owner never allowed
        val agent = assertIs<PocketError>(
            exchange(deviceId, session, ExecutionRunSubmit("r6", grantId, 1, "app", executionAgentWire(AgentKind.CODEX), "x")).single(),
        )
        assertEquals("agent_not_allowed", agent.code)
    }

    @Test
    fun an_oversize_frame_is_refused_before_it_becomes_work(): Unit = runBlocking {
        val (grantId, deviceId, session) = execution()
        val huge = ExecutionRunSubmit("big", grantId, 1, "app", executionAgentWire(AgentKind.CLAUDE), "x".repeat(EXECUTION_FRAME_BUDGET_BYTES + 1))
        val err = assertIs<PocketError>(exchange(deviceId, session, huge).single())
        assertEquals("frame_too_large", err.code)
    }

    @Test
    fun no_other_restricted_credential_may_send_a_run_frame(): Unit = runBlocking {
        val (grantId, _, _) = execution()
        val roots = listOf(ws.path)
        val credentials = buildList {
            add("bridge" to BridgeSpec("feishu-bot", roots))
            add("guest" to BridgeSpec("guest", roots, kind = CredentialKind.GUEST, expiresAt = clock + 3_600_000, tier = AccessTier.REVIEW))
            for (p in CollaboratorPurpose.entries) add("collab-${p.name.lowercase()}" to BridgeSpec("peer", roots, kind = CredentialKind.COLLABORATOR, purpose = p))
        }
        for ((i, pair) in (credentials.map { it to false } + listOf(("provisional" to BridgeSpec("late", roots)) to true)).withIndex()) {
            val (cred, lapse) = pair
            val (label, spec) = cred
            val id = "dev-$label-$i"
            val session = restricted(id, spec, lapse)
            val frames = exchange(id, session, *(runFrames(grantId) + ExecutionGrantQuery(grantId)).toTypedArray())
            assertTrue(frames.none { it is ExecutionGrantInfo }, "$label got a grant answer: $frames")
            assertTrue(frames.none { it is dev.ccpocket.protocol.ExecutionRunAccepted }, "$label got a run answer: $frames")
            if (lapse) assertTrue(frames.isEmpty(), "a provisional key is dropped silently, got $frames")
            else assertTrue(frames.all { it is PocketError && it.code.contains("forbidden") }, "$label: only refusals, got $frames")
        }
        // and none of them became an execution credential along the way
        assertEquals(1, harness.bridges.ids().count { harness.bridges.isExecution(it) })
    }

    @Test
    fun an_owner_device_may_not_send_a_run_frame_either(): Unit = runBlocking {
        val (grantId, _, _) = execution()
        val session = owner("owner-phone")
        harness.drain("owner-phone", session) // the handshake DaemonInfo an owner DOES get
        val frames = exchange("owner-phone", session, *(runFrames(grantId) + ExecutionGrantQuery(grantId)).toTypedArray())
        assertTrue(frames.isNotEmpty(), "the owner branch answers something")
        assertTrue(
            frames.all { it is PocketError },
            "the owner router has no handler for the execution plane — it must not route one: $frames",
        )
    }

    /**
     * The `else ->` branch #367 added to DeviceSessions' credential dispatch. Before it, a CONFIRMED
     * restricted credential whose kind none of the branches claimed fell through to the FULL-POWER OWNER
     * branch — i.e. an unrecognised credential got the management plane on the strength of not being
     * recognised. It now gets `credential_unsupported`.
     *
     * The branch is unreachable TODAY by construction, and that is the point: it is the landing pad for a
     * [CredentialKind] a NEWER daemon writes into a file this build then loads. So instead of faking a
     * fifth kind, this test pins the premise — every kind that exists has its own branch — with an
     * exhaustive `when`. Adding a kind fails to compile here until someone has looked at the dispatch.
     */
    @Test
    fun every_credential_kind_has_its_own_branch_so_the_else_refusal_is_only_for_future_kinds() {
        for (kind in CredentialKind.entries) {
            val claimed = when (kind) { // exhaustive: a new kind breaks this line, on purpose
                CredentialKind.BRIDGE, CredentialKind.GUEST, CredentialKind.COLLABORATOR, CredentialKind.EXECUTION -> true
            }
            assertTrue(claimed, "$kind has no dispatch branch — it would hit the else refusal")
        }
    }

    @Test
    fun an_execution_row_with_no_grant_pointer_is_refused_at_bind_and_at_load(): Unit = runBlocking {
        // the shape closest to "a credential this build cannot police": an EXECUTION spec whose grantId is
        // gone. Nothing could ever re-authorise it, so the bind hook refuses it and the credential the
        // finalize just created is dropped again — it never routes anywhere, least of all to the owner branch.
        val id = "no-grant"
        val session = restricted(id, BridgeSpec.execution("future", "xg_placeholder1").copy(grantId = null))
        val frames = exchange(id, session, ExecutionGrantQuery("xg_placeholder1"))
        assertTrue(frames.isEmpty(), "an unbindable credential is dropped, never routed: $frames")
        assertFalse(harness.bridges.isRestricted(id), "…and the credential does not survive the frame that made it")
        assertTrue(frames.none { it is DaemonInfo })
    }

    @Test
    fun the_mint_slot_is_mutually_exclusive_with_every_other_pairing(): Unit = runBlocking {
        // an execution approval holds the ONE #207 slot: no other class may record an intent behind it
        val appr = assertIs<ExecutionTarget.Approval.Ok>(harness.target.approve(draft()))
        assertTrue(harness.bridges.intentPending(clock))
        assertFalse(harness.bridges.reserveMint(clock), "another mint cannot start while an execution intent pends")
        assertFalse(
            harness.bridges.recordIntent("other-ticket", BridgeSpec.collaborator("peer"), 600_000, clock),
            "a collaborator intent cannot interleave",
        )
        // …and the reverse: a pending collaborator intent refuses an execution approval
        clock += 600_000L + BridgeRegistry.INTENT_GRACE_MS + 1 // let the execution intent lapse
        assertTrue(harness.bridges.recordIntent("collab-ticket", BridgeSpec.collaborator("peer"), 600_000, clock))
        // …and the refusal says WHEN, not just "shortly": a spent invite holds the slot for its whole TTL
        val busy = assertIs<ExecutionTarget.Approval.Refused>(harness.target.approve(draft()))
        assertEquals("mint_busy", busy.code)
        assertTrue((busy.retryAfterMs ?: 0) > 0, "a WAIT refusal must carry a retry hint, got ${busy.retryAfterMs}")
        // an interactive phone pairing blocks it too (issue #91 mint serialization)
        harness.bridges.releaseMint()
        clock += 600_000L + BridgeRegistry.INTENT_GRACE_MS + 1
        harness.sessions.onMintedTicket("phone-ticket") // interactive
        val phone = assertIs<ExecutionTarget.Approval.Refused>(harness.target.approve(draft()))
        assertEquals("interactive_pairing_pending", phone.code)
        assertTrue((phone.retryAfterMs ?: 0) > 0, "…same for the interactive-pairing wait")
        assertEquals(ExecutionGrantState.PENDING_REDEEM, harness.store.byId(appr.grant.grantId)!!.state)
    }

    @Test
    fun an_execution_row_never_lands_in_bridges_json_even_beside_a_real_bridge(): Unit = runBlocking {
        // wire review P3. The downgrade-safety chain rests entirely on the WRITE SPLIT in
        // BridgeRegistry.persist: a daemon that predates #367 loads bridges.json / guests.json /
        // collaborator-keys.json and must find NO execution key there, or it would honour one under a
        // policy it has never heard of. The earlier assertion only proved the file did not mention the
        // deviceId while bridges.json happened to be EMPTY — which proves nothing about the split.
        val (grantId, execDevice, _) = execution()
        val bridgeDevice = "dev-real-bridge"
        // a REAL bridge, bound the same way any bridge binds: the credential is only written to disk when
        // its first transport frame decrypts under the intent's ticket (BridgeRegistry.finalize)
        val bridgeSession = restricted(bridgeDevice, BridgeSpec("feishu-bot", listOf(ws.path)))
        exchange(bridgeDevice, bridgeSession, dev.ccpocket.protocol.ClientCaps())

        val bridgesJson = File(targetDir, "bridges.json")
        assertTrue(bridgesJson.exists() && bridgesJson.readText().contains(bridgeDevice), "precondition: a real bridge is on disk")
        assertFalse(bridgesJson.readText().contains(execDevice), "an EXECUTION row must never reach bridges.json")
        assertFalse(bridgesJson.readText().contains(grantId), "…nor its grant pointer")
        assertFalse(bridgesJson.readText().contains("EXECUTION"), "…nor the kind, under any key")
        for (name in listOf("guests.json", "collaborator-keys.json")) {
            val f = File(targetDir, name)
            assertFalse(f.exists() && f.readText().contains(execDevice), "an EXECUTION row must never reach $name")
        }
        val credFile = File(targetDir, "execution-credentials.json")
        assertTrue(credFile.readText().contains(execDevice), "it lives in execution-credentials.json and nowhere else")
        assertFalse(credFile.readText().contains(bridgeDevice), "…and that file holds nothing else in return")
    }

    @Test
    fun a_revoke_kills_the_link_even_when_the_store_cannot_record_it(): Unit = runBlocking {
        // #367 security review LOW-1. revoke() used to return EARLY on Unavailable / NotFound — precisely
        // the two states in which the owner most needs the link gone. "The tombstone could not be written"
        // is a reason to keep refusing everything, never a reason to leave an authenticated remote link
        // alive; and a row this daemon can no longer read is not evidence that no credential is bound.
        val (grantId, deviceId, session) = execution()
        assertTrue(harness.bridges.isExecution(deviceId), "precondition: a live execution credential")

        // wedge the store so every write fails and it reports itself unusable
        assertTrue(targetDir.setWritable(false, false))
        try {
            harness.target.revoke(grantId)
        } finally {
            assertTrue(targetDir.setWritable(true, false))
        }
        assertFalse(harness.bridges.isExecution(deviceId), "the credential dies whatever the store managed to record")
        assertTrue(deviceId in relay.revoked, "…and the relay is told, so its row goes too")
        assertTrue(exchange(deviceId, session, ExecutionGrantQuery(grantId)).isEmpty(), "nothing answers it any more")

        // …and a revoke naming a grant this daemon cannot resolve is still not a silent no-op for a
        // credential that IS bound: nothing is left behind for the next process to reload
        harness.restart()
        assertFalse(harness.bridges.isExecution(deviceId))
    }

    @Test
    fun the_credential_reloads_from_its_own_file_and_is_still_restricted(): Unit = runBlocking {
        val (grantId, deviceId, _) = execution()
        val credFile = File(targetDir, "execution-credentials.json")
        assertTrue(credFile.readText().contains(grantId), "the credential names its grant on disk")
        assertFalse(File(targetDir, "bridges.json").let { it.exists() && it.readText().contains(deviceId) })
        assertFalse(File(targetDir, "guests.json").let { it.exists() && it.readText().contains(deviceId) })
        assertFalse(File(targetDir, "collaborator-keys.json").let { it.exists() && it.readText().contains(deviceId) })
        assertFalse(PairedDevices.load(File(targetDir, "devices.json")).containsKey(deviceId), "never in the full-power allow-list")

        harness.restart()
        assertTrue(harness.bridges.isExecution(deviceId), "reloaded as an EXECUTION credential")
        assertEquals(grantId, harness.bridges.executionGrantIdOf(deviceId))

        // a row whose grant pointer is gone is not policeable: refuse to load it at all
        credFile.writeText(credFile.readText().replace("\"grantId\":\"$grantId\"", "\"grantId\":null"))
        harness.restart()
        assertFalse(harness.bridges.isExecution(deviceId), "an execution row with no grantId is refused at load")
        assertFalse(harness.bridges.isRestricted(deviceId))
    }

    // ---------------------------------------------------------------- LAN gate

    private class FakeWs(override val coroutineContext: CoroutineContext) : WebSocketSession {
        val inbound = Channel<WsFrame>(Channel.UNLIMITED)
        val sent = Channel<WsFrame>(Channel.UNLIMITED)
        override val incoming: ReceiveChannel<WsFrame> get() = inbound
        override val outgoing: SendChannel<WsFrame> get() = sent
        override val extensions: List<WebSocketExtension<*>> get() = emptyList()
        override var masking: Boolean = false
        override var maxFrameSize: Long = Long.MAX_VALUE
        override suspend fun send(frame: WsFrame) { sent.send(frame) }
        override suspend fun flush() {}
        @Deprecated("Use cancel() instead.", replaceWith = ReplaceWith("cancel()", "kotlinx.coroutines.cancel"))
        override fun terminate() { inbound.close() }
    }

    @Test
    fun an_execution_credential_is_refused_at_the_lan_gate(): Unit = runBlocking {
        val (_, deviceId, _) = execution()
        val keys = E2ECrypto.generateKeyPair()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // the allow-list is deliberately RIGGED to contain the execution device — the point is that the
            // explicit #367 refusal holds even when the structural one (its key is in another file) does not
            val allowed = hashMapOf(deviceId to keys.publicRaw)
            val gate = LanE2E(
                identity = identity, lanUrl = { null }, pairedDevices = { allowed },
                restrictedCredential = { harness.bridges.isRestricted(it) },
            )
            val sock = FakeWs(scope.coroutineContext)
            scope.launch { runCatching { WsConnection(sock, harness.core.router, SessionRegistry(scope, emptyMap()), e2e = gate).serve() } }
            val init = E2ESession.initiator(keys.privateRaw, keys.publicRaw, identity.e2ePubRaw, ByteArray(0))
            sock.inbound.send(WsFrame.Text(PocketJson.encodeToString(Envelope("c", 0, body = LanHello(deviceId)))))
            sock.inbound.send(WsFrame.Binary(true, Wire.payload(Wire.HANDSHAKE, init.ephPublic)))
            assertNull(withTimeoutOrNull(1_500) { sock.sent.receive() }, "the gate answered a restricted credential")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun an_ordinary_owner_device_still_passes_the_lan_gate(): Unit = runBlocking {
        // negative control for the refusal above: the new predicate must not lock out real devices
        val keys = E2ECrypto.generateKeyPair()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val allowed = hashMapOf("phone" to keys.publicRaw)
            val gate = LanE2E(
                identity = identity, lanUrl = { null }, pairedDevices = { allowed },
                restrictedCredential = { harness.bridges.isRestricted(it) },
            )
            val sock = FakeWs(scope.coroutineContext)
            scope.launch { runCatching { WsConnection(sock, harness.core.router, SessionRegistry(scope, emptyMap()), e2e = gate).serve() } }
            val init = E2ESession.initiator(keys.privateRaw, keys.publicRaw, identity.e2ePubRaw, ByteArray(0))
            sock.inbound.send(WsFrame.Text(PocketJson.encodeToString(Envelope("c", 0, body = LanHello("phone")))))
            sock.inbound.send(WsFrame.Binary(true, Wire.payload(Wire.HANDSHAKE, init.ephPublic)))
            val reply = assertIs<WsFrame.Binary>(withTimeout(5_000) { sock.sent.receive() })
            assertEquals(Wire.HANDSHAKE, Wire.payloadType(reply.data))
        } finally {
            scope.cancel()
        }
    }
}
