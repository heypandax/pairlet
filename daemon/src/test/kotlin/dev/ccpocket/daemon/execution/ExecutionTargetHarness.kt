package dev.ccpocket.daemon.execution

import dev.ccpocket.daemon.DaemonCore
import dev.ccpocket.daemon.bridge.BridgeRegistry
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.pins.MemoryProjectPinStore
import dev.ccpocket.daemon.pins.PinStoreState
import dev.ccpocket.daemon.relay.DeviceSessions
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.e2e.E2ESession
import dev.ccpocket.protocol.e2e.Wire
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * ONE target daemon, wired the way production wires it (#367 G1): a real [DaemonCore] + [BridgeRegistry] +
 * [DeviceSessions], with the execution plane injected onto the router and the bind hook onto the core.
 *
 * The point of this harness is what it does NOT contain: no execution-specific transport. Every frame a
 * source sends enters through [DeviceSessions.onFrame] and is classified by the same credential chain a
 * phone, a bridge, a guest and a collaborator go through. If a future change reintroduced a second
 * handshake path, this harness could not reach it.
 *
 * [restart] models a daemon process restart: the FILES survive (identity, devices.json,
 * execution-credentials.json, execution-grants.json + its tombstone log), every in-memory fact does not —
 * armed PSKs, provisional keys, live E2E sessions, the loaded grant rows.
 */
internal class ExecutionTargetHarness(
    private val dir: File,
    private val relay: FakeRelay,
    val identity: Identity,
    private val relayUrl: String = "wss://relay.test",
    private val now: () -> Long,
    /** How many upcoming relay-side revokes should fail (relay unreachable). */
    private val relayRevokeFails: () -> Boolean = { false },
) : TargetPump {

    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val outbound = ConcurrentHashMap<String, Channel<ByteArray>>()

    val grantsFile = File(dir, "execution-grants.json")
    val core = DaemonCore(
        emptyMap(),
        projectPinStore = MemoryProjectPinStore(PinStoreState(incarnation = "inc-0123456789abcdef")),
        managedSessionRoot = File(dir, "managed"),
        executionRunRoot = File(dir, "execution-runs"),
    )

    lateinit var bridges: BridgeRegistry
        private set
    lateinit var sessions: DeviceSessions
        private set
    lateinit var store: ExecutionGrantStore
        private set
    lateinit var target: ExecutionTarget
        private set
    lateinit var refusals: ExecutionRefusals
        private set

    init { restart() }

    /** A target daemon process restart: same files, no in-memory state. */
    fun restart() {
        bridges = BridgeRegistry(File(dir, "bridges.json"))
        sessions = DeviceSessions(core, identity, store = File(dir, "devices.json"), bridges = bridges) { deviceId, payload ->
            channel(deviceId).trySend(payload)
        }
        store = ExecutionGrantStore.load(grantsFile, identity.e2ePubB64)
        refusals = ExecutionRefusals()
        val linkPubOf: (String) -> String? = { id -> bridges.pubOf(id)?.let { b64.encodeToString(it) } }
        target = ExecutionTarget(
            identity = identity,
            relayUrl = relayUrl,
            store = store,
            bridges = bridges,
            mintTicket = { relay.mint() },
            armPsk = { psk -> sessions.onMintedTicket(psk, headless = true) },
            revokeRelayDevice = { if (relayRevokeFails()) error("relay unreachable") else relay.revoked += it },
            cutLink = { sessions.onDeviceRevoked(it) },
            isKnownDevice = { sessions.isKnownDevice(it) },
            // NOT [now]: the exclusion clock is stamped by DeviceSessions off the WALL clock, so both sides
            // of that comparison must be the wall clock. Only the grant store's own decisions use [now].
            interactivePairingRemainingMs = { sessions.interactivePairingRemainingMs() },
            now = now,
            refusals = refusals,
        )
        core.executionControl = target
        core.router.executionGuard = ExecutionGuard(store, bridges::executionGrantIdOf, linkPubOf, now, refusals)
        // the REAL run plane, exactly as production installs it — there is no test-only plane any more
        core.router.executionPlane = RunService(store, core.executionRuns, core.registry, core.scope, now)
        relay.target = this
    }

    /** Drop the execution plane + guard + bind hook, leaving the credential chain intact (fail-closed test). */
    fun unwirePlane() {
        core.router.executionGuard = null
        core.router.executionPlane = null
    }

    fun channel(deviceId: String): Channel<ByteArray> = outbound.computeIfAbsent(deviceId) { Channel(Channel.UNLIMITED) }

    override suspend fun devicePaired(deviceId: String, devicePubB64: String) = sessions.onDevicePaired(deviceId, devicePubB64)

    override suspend fun handshake(deviceId: String, initiatorEph: ByteArray): ByteArray? {
        sessions.onFrame(deviceId, Wire.payload(Wire.HANDSHAKE, initiatorEph))
        val p = withTimeoutOrNull(HANDSHAKE_WAIT_MS) { channel(deviceId).receive() } ?: return null
        return if (Wire.payloadType(p) == Wire.HANDSHAKE) Wire.payloadBody(p) else null
    }

    override suspend fun transport(deviceId: String, sealed: ByteArray): List<ByteArray> {
        sessions.onFrame(deviceId, Wire.payload(Wire.TRANSPORT, sealed))
        val out = ArrayList<ByteArray>()
        while (true) {
            val p = withTimeoutOrNull(DRAIN_MS) { channel(deviceId).receive() } ?: break
            if (Wire.payloadType(p) == Wire.TRANSPORT) out += Wire.payloadBody(p)
        }
        return out
    }

    /** Everything currently queued for [deviceId], decoded with [session] (an owner device's DaemonInfo etc.). */
    suspend fun drain(deviceId: String, session: E2ESession): List<Frame> {
        val out = ArrayList<Frame>()
        while (true) {
            val p = withTimeoutOrNull(DRAIN_MS) { channel(deviceId).receive() } ?: return out
            if (Wire.payloadType(p) != Wire.TRANSPORT) continue
            val plain = session.open(Wire.payloadBody(p)) ?: continue
            out += PocketJson.decodeFromString<Envelope>(plain.decodeToString()).body
        }
    }

    suspend fun send(deviceId: String, session: E2ESession, body: Frame) {
        val env = Envelope("0", 0L, body = body)
        sessions.onFrame(deviceId, Wire.payload(Wire.TRANSPORT, session.seal(PocketJson.encodeToString(env).encodeToByteArray())))
    }

    private companion object {
        const val HANDSHAKE_WAIT_MS = 3_000L
        // the execution branch replies INLINE on the receive path, so a short drain is enough; it only has
        // to outlast the coroutine hand-off, not any real work
        const val DRAIN_MS = 250L
    }
}
