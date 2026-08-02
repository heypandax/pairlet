package dev.ccpocket.relay.store

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Volatile [RelayStore] for tests and a `--in-memory` local relay. Same semantics as SQLite, no file. */
class InMemoryRelayStore : RelayStore {
    private val lock = Mutex()
    private val accounts = HashMap<String, Account>()
    private val devices = HashMap<String, Device>()
    private data class Ticket(
        val accountId: String,
        val createdAt: Long,
        val expiresAt: Long,
        var used: Boolean,
        val headless: Boolean,
        val collaborator: Boolean,
    )
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

    override suspend fun insertTicket(
        ticketHash: ByteArray,
        accountId: String,
        createdAt: Long,
        expiresAt: Long,
        headless: Boolean,
        collaborator: Boolean,
    ): Unit = lock.withLock {
        tickets[key(ticketHash)] =
            Ticket(accountId, createdAt, expiresAt, used = false, headless = headless, collaborator = collaborator)
        Unit
    }

    override suspend fun claimTicket(ticketHash: ByteArray, now: Long): ClaimedTicket? = lock.withLock {
        val t = tickets[key(ticketHash)] ?: return@withLock null
        if (t.used || t.expiresAt <= now) return@withLock null
        t.used = true
        ClaimedTicket(t.accountId, t.headless, t.collaborator)
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

    /**
     * Rebuild a row, changing only what is named. Every mutator below goes through this: rebuilding by hand
     * is how a marker gets silently dropped — a touch that lost `headless` would turn a bridge back into a
     * presence-counting "phone" and re-mute every push (issue #91); one that lost `collaborator` would drop
     * a contact's inbox INTO the owner's push fan-out (§3.4). A new column added to [Device] now has exactly
     * one place to be threaded through.
     */
    private fun Device.with(
        lastSeen: Long? = this.lastSeen,
        revoked: Boolean = this.revoked,
        pushPlatform: String? = this.pushPlatform,
        pushToken: String? = this.pushToken,
    ) = Device(
        deviceId, accountId, devicePubkey, credentialHash, createdAt, lastSeen, revoked,
        pushPlatform, pushToken, headless, collaborator,
    )

    override suspend fun revokeDevice(accountId: String, deviceId: String): Boolean = lock.withLock {
        val d = devices[deviceId] ?: return@withLock false
        if (d.accountId != accountId || d.revoked) return@withLock false
        devices[deviceId] = d.with(revoked = true)
        true
    }

    override suspend fun touchDevice(deviceId: String, now: Long): Unit = lock.withLock {
        devices[deviceId]?.let { devices[deviceId] = it.with(lastSeen = now) }
        Unit
    }

    override suspend fun setPushToken(deviceId: String, platform: String, token: String, now: Long): Unit = lock.withLock {
        val clear = token.isBlank() // a blank token de-registers
        devices[deviceId]?.let {
            devices[deviceId] = it.with(
                pushPlatform = if (clear) null else platform,
                pushToken = if (clear) null else token,
            )
        }
        Unit
    }

    override suspend fun clearPushToken(deviceId: String, platform: String, token: String, now: Long): Boolean = lock.withLock {
        val d = devices[deviceId] ?: return@withLock false
        if (d.pushPlatform != platform || d.pushToken != token) return@withLock false // re-registered since — keep it
        devices[deviceId] = d.with(pushPlatform = null, pushToken = null)
        true
    }

    override suspend fun pushTargets(accountId: String): List<PushTarget> = lock.withLock {
        devices.values
            // headless bridges are excluded (issue #91) — never route the owner's session pushes to a bot —
            // and collaborator inboxes on their own predicate (§3.4), never as a side effect of headless
            .filter {
                it.accountId == accountId && !it.revoked && !it.headless && !it.collaborator &&
                    !it.pushToken.isNullOrBlank() && it.pushPlatform != null
            }
            .map { PushTarget(it.deviceId, it.pushPlatform!!, it.pushToken!!) }
    }

    override suspend fun pushTargetFor(accountId: String, deviceId: String): PushTarget? = lock.withLock {
        val d = devices[deviceId] ?: return@withLock null
        // the account check is the authorization: a daemon may only ever wake devices of its own account.
        // mayRegisterPush additionally keeps a pre-#91 bridge row (one that registered a token before that
        // door was closed, and which no migration cleaned up) out of reach.
        if (d.accountId != accountId || d.revoked || !d.mayRegisterPush) return@withLock null
        val platform = d.pushPlatform ?: return@withLock null
        val token = d.pushToken?.takeUnless { it.isBlank() } ?: return@withLock null
        PushTarget(d.deviceId, platform, token)
    }

    override suspend fun sweepExpired(now: Long): Unit = lock.withLock {
        tickets.entries.removeAll { it.value.expiresAt < now || it.value.used }
    }
}
