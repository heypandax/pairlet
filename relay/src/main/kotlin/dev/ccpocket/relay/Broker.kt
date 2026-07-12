package dev.ccpocket.relay

import dev.ccpocket.protocol.Role
import dev.ccpocket.protocol.e2e.Wire
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One authenticated socket. [sendBinary] carries the opaque E2E data plane; [sendText] carries
 * relay control frames; [close] force-disconnects (e.g. on revoke or supersede).
 */
class Conn(
    val account: String,
    val role: Role,
    val deviceId: String?,                       // non-null for devices
    val sendText: suspend (String) -> Unit,
    val sendBinary: suspend (ByteArray) -> Unit,
    val close: suspend (reason: String) -> Unit,
    // issue #91: a headless bridge socket must not count as "a person is watching" — presence,
    // offline-push gating, and the interactive connection cap all read [headless]. From the device's
    // relay-store row (self-declared at redeem; advisory, never a capability).
    val headless: Boolean = false,
    // daemons only: the protoV from DaemonHello — gates whether headless DevicePaired rows are
    // replayed to this daemon (an older daemon would file them as full-power devices).
    val daemonProtoV: Int = 1,
)

/**
 * In-memory routing keyed by AUTHENTICATED account id. The broker only ever sees opaque ciphertext
 * on the data plane (it forwards bytes, never decodes them) — durable state and identity live in the
 * store/auth/pairing services. Routing maps are bounded by the per-account device caps enforced at
 * pairing, plus the single-active-daemon rule here.
 */
class Broker {
    private val mutex = Mutex()
    private val daemons = HashMap<String, Conn>()
    private val devices = HashMap<String, MutableSet<Conn>>()

    /** Register a daemon; returns the superseded previous daemon (caller closes it — newest wins). */
    suspend fun attachDaemon(conn: Conn): Conn? = mutex.withLock { daemons.put(conn.account, conn) }

    /** Unregister a daemon socket. False when [conn] was already superseded by a newer attach — the account
     *  still has a live daemon, so the caller must NOT broadcast "offline" (a false offline right after the
     *  successor's "online" strands devices: they key their E2E re-handshake on an offline→online edge). */
    suspend fun detachDaemon(conn: Conn): Boolean = mutex.withLock {
        (daemons[conn.account] === conn).also { if (it) daemons.remove(conn.account) }
    }

    /** Register a device socket; returns the superseded previous socket with the same deviceId, if any
     *  (caller closes it — newest wins, like daemons). One live socket per device matters: the daemon keeps
     *  a single E2E session per deviceId, so two sockets racing their handshakes deafen whichever loses. */
    suspend fun attachDevice(conn: Conn): Conn? = mutex.withLock {
        val set = devices.getOrPut(conn.account) { mutableSetOf() }
        val old = conn.deviceId?.let { id -> set.firstOrNull { it.deviceId == id } }
        if (old != null) set.remove(old)
        set.add(conn)
        old
    }

    suspend fun detachDevice(conn: Conn): Unit = mutex.withLock {
        devices[conn.account]?.remove(conn)
        if (devices[conn.account]?.isEmpty() == true) devices.remove(conn.account)
        Unit
    }

    suspend fun daemonOnline(account: String): Boolean = mutex.withLock { daemons.containsKey(account) }
    suspend fun deviceCount(account: String): Int = mutex.withLock { devices[account]?.size ?: 0 }

    /** Live INTERACTIVE device sockets (phones/desktops) — the only ones that count for presence and
     *  for the "push only when nobody is attached" gate. An always-on bridge must never mute pushes
     *  or convince the daemon a person is watching (issue #91). */
    suspend fun interactiveDeviceCount(account: String): Int =
        mutex.withLock { devices[account]?.count { !it.headless } ?: 0 }

    /** Live HEADLESS bridge sockets — capped separately from interactive devices. */
    suspend fun headlessDeviceCount(account: String): Int =
        mutex.withLock { devices[account]?.count { it.headless } ?: 0 }

    /** The account's live daemon socket (to read its protoV for replay gating), if any. */
    suspend fun daemonConn(account: String): Conn? = mutex.withLock { daemons[account] }

    /** device -> the account's daemon (data plane, opaque), tagged with the source deviceId. */
    suspend fun toDaemonFrom(account: String, deviceId: String, data: ByteArray) {
        val d = mutex.withLock { daemons[account] }
        d?.let { runCatching { it.sendBinary(Wire.wrapDevice(deviceId, data)) } }
    }

    /** daemon -> a specific device (data plane, opaque); the deviceId is the relay's routing key only. */
    suspend fun toDevice(account: String, deviceId: String, payload: ByteArray) {
        val ds = mutex.withLock { devices[account]?.filter { it.deviceId == deviceId }.orEmpty() }
        ds.forEach { runCatching { it.sendBinary(payload) } }
    }

    /** relay -> daemon control frame (e.g. DevicePaired, PeerPresence). */
    suspend fun controlToDaemon(account: String, text: String) {
        val d = mutex.withLock { daemons[account] }
        d?.let { runCatching { it.sendText(text) } }
    }

    /** relay -> all the account's devices control frame (e.g. PeerPresence). */
    suspend fun controlToDevices(account: String, text: String) {
        val ds = mutex.withLock { devices[account]?.toList().orEmpty() }
        ds.forEach { runCatching { it.sendText(text) } }
    }

    /** Force-close every live socket of a just-revoked device. */
    suspend fun closeDevice(account: String, deviceId: String) {
        val ds = mutex.withLock { devices[account]?.filter { it.deviceId == deviceId }.orEmpty() }
        ds.forEach { runCatching { it.close("revoked") } }
    }
}
