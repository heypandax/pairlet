package dev.ccpocket.relay.store

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Volatile [RelayStore] for tests and a `--in-memory` local relay. Same semantics as SQLite, no file. */
class InMemoryRelayStore : RelayStore {
    private val lock = Mutex()
    private val accounts = HashMap<String, Account>()
    private val devices = HashMap<String, Device>()
    private data class Ticket(val accountId: String, val createdAt: Long, val expiresAt: Long, var used: Boolean)
    private val tickets = HashMap<String, Ticket>() // key = hex(ticketHash)

    @OptIn(ExperimentalStdlibApi::class)
    private fun key(b: ByteArray) = b.toHexString()

    override suspend fun getAccount(accountId: String): Account? = lock.withLock { accounts[accountId] }

    override suspend fun insertAccount(accountId: String, staticPubkey: ByteArray, now: Long): Unit = lock.withLock {
        accounts.getOrPut(accountId) { Account(accountId, staticPubkey, now, now) }
        Unit
    }

    override suspend fun touchAccount(accountId: String, now: Long): Unit = lock.withLock {
        accounts[accountId]?.let { accounts[accountId] = Account(it.accountId, it.staticPubkey, it.createdAt, now) }
        Unit
    }

    override suspend fun insertTicket(ticketHash: ByteArray, accountId: String, createdAt: Long, expiresAt: Long): Unit =
        lock.withLock { tickets[key(ticketHash)] = Ticket(accountId, createdAt, expiresAt, used = false); Unit }

    override suspend fun claimTicket(ticketHash: ByteArray, now: Long): String? = lock.withLock {
        val t = tickets[key(ticketHash)] ?: return@withLock null
        if (t.used || t.expiresAt <= now) return@withLock null
        t.used = true
        t.accountId
    }

    override suspend fun countUnredeemedTickets(accountId: String, now: Long): Int = lock.withLock {
        tickets.values.count { it.accountId == accountId && !it.used && it.expiresAt > now }
    }

    override suspend fun insertDevice(device: Device): Unit = lock.withLock { devices[device.deviceId] = device; Unit }

    override suspend fun getDevice(deviceId: String): Device? = lock.withLock { devices[deviceId] }
    override suspend fun devicesForAccount(accountId: String): List<Device> =
        lock.withLock { devices.values.filter { it.accountId == accountId && !it.revoked } }

    override suspend fun countDevices(accountId: String): Int = lock.withLock {
        devices.values.count { it.accountId == accountId && !it.revoked }
    }

    override suspend fun revokeDevice(accountId: String, deviceId: String): Boolean = lock.withLock {
        val d = devices[deviceId] ?: return@withLock false
        if (d.accountId != accountId || d.revoked) return@withLock false
        devices[deviceId] = Device(d.deviceId, d.accountId, d.devicePubkey, d.credentialHash, d.createdAt, d.lastSeen, revoked = true)
        true
    }

    override suspend fun touchDevice(deviceId: String, now: Long): Unit = lock.withLock {
        devices[deviceId]?.let { devices[deviceId] = Device(it.deviceId, it.accountId, it.devicePubkey, it.credentialHash, it.createdAt, now, it.revoked, it.pushPlatform, it.pushToken) }
        Unit
    }

    override suspend fun setPushToken(deviceId: String, platform: String, token: String, now: Long): Unit = lock.withLock {
        val clear = token.isBlank()
        devices[deviceId]?.let {
            devices[deviceId] = Device(
                it.deviceId, it.accountId, it.devicePubkey, it.credentialHash, it.createdAt, it.lastSeen, it.revoked,
                pushPlatform = if (clear) null else platform,
                pushToken = if (clear) null else token,
            )
        }
        Unit
    }

    override suspend fun pushTargets(accountId: String): List<PushTarget> = lock.withLock {
        devices.values
            .filter { it.accountId == accountId && !it.revoked && !it.pushToken.isNullOrBlank() && it.pushPlatform != null }
            .map { PushTarget(it.deviceId, it.pushPlatform!!, it.pushToken!!) }
    }

    override suspend fun sweepExpired(now: Long): Unit = lock.withLock {
        tickets.entries.removeAll { it.value.expiresAt < now || it.value.used }
    }
}
