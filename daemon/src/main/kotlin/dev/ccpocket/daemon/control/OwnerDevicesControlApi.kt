package dev.ccpocket.daemon.control

import dev.ccpocket.daemon.identity.DeviceActivity
import dev.ccpocket.daemon.relay.OwnerPairingWatch
import dev.ccpocket.daemon.relay.RelayClient
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.e2e.PairingFingerprint
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

// ===========================================================================
//  The owner's view of the FULL-POWER devices paired to this computer, and the outcome of an interactive
//  `pairlet pair` (pairing security phase 0). Local DTOs, never wire frames; nothing here carries a ticket,
//  a credential or a private key — only public keys' fingerprints and ids.
// ===========================================================================

@Serializable
data class LocalOwnerDevice(
    val deviceId: String,
    /** [PairingFingerprint] of the device's static key — what the App shows as "This device's fingerprint". */
    val fingerprint: String,
    /** When it was paired — known only for pairings since this daemon process started. */
    val pairedAt: Long? = null,
    /** Its last E2E handshake with this daemon process (null = none since the daemon started). */
    val lastHandshakeAt: Long? = null,
    /** `relay` | `direct` — the path of that handshake. */
    val lastHandshakeVia: String? = null,
    /** Paired, but its first contact over the relay has not completed yet. */
    val firstContactPending: Boolean = false,
)

@Serializable
data class LocalOwnerDevicesRes(
    val ok: Boolean = true,
    /** [PairingFingerprint] of THIS computer's key — what the App shows as "Computer fingerprint". */
    val computerFingerprint: String,
    val items: List<LocalOwnerDevice> = emptyList(),
)

/** Revoke by EXACT device id; prefix / fingerprint matching and the confirmation happen in the CLI, against
 *  the list it just showed, so what is confirmed is what is revoked. */
@Serializable
data class LocalOwnerDeviceRevokeReq(val deviceId: String)

@Serializable
data class LocalOwnerDeviceRevokeRes(val ok: Boolean = true, val deviceId: String, val fingerprint: String)

@Serializable
data class LocalPairingRes(
    val ok: Boolean = true,
    val pairingId: String,
    /** `pending` | `paired` | `expired` | `refused` | `unknown`. */
    val state: String,
    val deviceId: String? = null,
    val fingerprint: String? = null,
    /** How much longer the pairing can still complete. */
    val remainingMs: Long = 0,
)

/** What the routes below need — the relay client in production, a fake in tests. */
interface OwnerDevicesPlane {
    val computerPub: ByteArray
    suspend fun devices(): List<OwnerDeviceRow>
    suspend fun revoke(deviceId: String): Boolean
    suspend fun awaitPairing(pairingId: String, waitMs: Long): OwnerPairingWatch.Outcome
    fun pairingRemainingMs(pairingId: String): Long
}

class OwnerDeviceRow(val deviceId: String, val pub: ByteArray, val pairedAt: Long?, val firstContactPending: Boolean)

fun ownerDevicesPlaneOf(relay: RelayClient): OwnerDevicesPlane = object : OwnerDevicesPlane {
    override val computerPub: ByteArray get() = relay.e2ePubRaw
    override suspend fun devices() = relay.ownerDevices().map { OwnerDeviceRow(it.deviceId, it.pub, it.pairedAt, it.firstContactPending) }
    override suspend fun revoke(deviceId: String) = relay.revokeOwnerDevice(deviceId)
    override suspend fun awaitPairing(pairingId: String, waitMs: Long) = relay.awaitOwnerPairing(pairingId, waitMs)
    override fun pairingRemainingMs(pairingId: String) = relay.ownerPairingRemainingMs(pairingId)
}

/** Longest single wait `GET pairing/{id}` holds the connection; the CLI simply asks again. */
internal const val MAX_PAIRING_WAIT_MS = 25_000L

/**
 * `/v1/local/devices` (list), `/v1/local/devices/revoke`, `/v1/local/pairing/{id}` (long poll). Every route
 * passes the shared gate first ([authorize]: token, no browser Origin, JSON Content-Type and the body cap on
 * POST) — this is the management surface for full-power access to this computer.
 */
fun Route.installOwnerDevicesControl(plane: OwnerDevicesPlane, token: String) {
    val log = logger("OwnerDevicesControl")

    get("$LOCAL_CONTROL_PREFIX/devices") {
        if (!call.authorize(token, post = false)) return@get
        val items = plane.devices().sortedBy { it.deviceId }.map { d ->
            val seen = DeviceActivity.lastHandshake(d.deviceId)
            LocalOwnerDevice(
                deviceId = d.deviceId, fingerprint = PairingFingerprint.of(d.pub), pairedAt = d.pairedAt,
                lastHandshakeAt = seen?.at, lastHandshakeVia = seen?.via, firstContactPending = d.firstContactPending,
            )
        }
        call.ok(LocalOwnerDevicesRes.serializer(), LocalOwnerDevicesRes(computerFingerprint = PairingFingerprint.of(plane.computerPub), items = items))
    }

    post("$LOCAL_CONTROL_PREFIX/devices/revoke") {
        if (!call.authorize(token)) return@post
        val req = call.body(LocalOwnerDeviceRevokeReq.serializer()) ?: return@post
        // the fingerprint is read BEFORE the revoke, which forgets the key
        val row = plane.devices().firstOrNull { it.deviceId == req.deviceId }
        if (row == null || !plane.revoke(req.deviceId)) {
            call.fail(HttpStatusCode.NotFound, "not_found", "no full-power device with that id is paired to this computer")
            return@post
        }
        log.info("owner device ${req.deviceId.take(8)}… revoked via the local control API")
        call.ok(LocalOwnerDeviceRevokeRes.serializer(), LocalOwnerDeviceRevokeRes(deviceId = row.deviceId, fingerprint = PairingFingerprint.of(row.pub)))
    }

    get("$LOCAL_CONTROL_PREFIX/pairing/{id}") {
        if (!call.authorize(token, post = false)) return@get
        val id = call.parameters["id"].orEmpty()
        val waitMs = call.request.queryParameters["waitMs"]?.toLongOrNull()?.coerceIn(0, MAX_PAIRING_WAIT_MS) ?: 0
        val res = when (val o = plane.awaitPairing(id, waitMs)) {
            is OwnerPairingWatch.Outcome.Paired ->
                LocalPairingRes(pairingId = id, state = "paired", deviceId = o.deviceId, fingerprint = PairingFingerprint.of(o.pub))
            OwnerPairingWatch.Outcome.Pending -> LocalPairingRes(pairingId = id, state = "pending", remainingMs = plane.pairingRemainingMs(id))
            OwnerPairingWatch.Outcome.Expired -> LocalPairingRes(pairingId = id, state = "expired")
            OwnerPairingWatch.Outcome.Refused -> LocalPairingRes(pairingId = id, state = "refused")
            OwnerPairingWatch.Outcome.Unknown -> LocalPairingRes(pairingId = id, state = "unknown")
        }
        call.ok(LocalPairingRes.serializer(), res)
    }
}
