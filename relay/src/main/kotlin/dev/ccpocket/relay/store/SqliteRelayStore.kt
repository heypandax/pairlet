package dev.ccpocket.relay.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.sql.Connection

/**
 * SQLite-backed [RelayStore]. A single connection guarded by a [Mutex] serializes all access
 * (SQLite serializes writes anyway, and this scale never needs concurrent readers); JDBC's blocking
 * calls run on [Dispatchers.IO].
 */
class SqliteRelayStore(private val conn: Connection) : RelayStore {
    private val lock = Mutex()

    private suspend fun <T> tx(block: (Connection) -> T): T =
        lock.withLock { withContext(Dispatchers.IO) { block(conn) } }

    override suspend fun getAccount(accountId: String): Account? = tx { c ->
        c.prepareStatement("SELECT static_pubkey, created_at, last_seen FROM accounts WHERE account_id=?").use { ps ->
            ps.setString(1, accountId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) null
                else Account(accountId, rs.getBytes(1), rs.getLong(2), rs.getLong(3).takeUnless { rs.wasNull() })
            }
        }
    }

    override suspend fun insertAccount(accountId: String, staticPubkey: ByteArray, now: Long) = tx { c ->
        c.prepareStatement("INSERT OR IGNORE INTO accounts(account_id, static_pubkey, created_at, last_seen) VALUES(?,?,?,?)").use { ps ->
            ps.setString(1, accountId); ps.setBytes(2, staticPubkey); ps.setLong(3, now); ps.setLong(4, now)
            ps.executeUpdate()
        }
        Unit
    }

    override suspend fun touchAccount(accountId: String, now: Long) = tx { c ->
        c.prepareStatement("UPDATE accounts SET last_seen=? WHERE account_id=?").use { ps ->
            ps.setLong(1, now); ps.setString(2, accountId); ps.executeUpdate()
        }
        Unit
    }

    override suspend fun insertTicket(ticketHash: ByteArray, accountId: String, createdAt: Long, expiresAt: Long) = tx { c ->
        c.prepareStatement("INSERT INTO pairing_tickets(ticket_hash, account_id, created_at, expires_at, used) VALUES(?,?,?,?,0)").use { ps ->
            ps.setBytes(1, ticketHash); ps.setString(2, accountId); ps.setLong(3, createdAt); ps.setLong(4, expiresAt)
            ps.executeUpdate()
        }
        Unit
    }

    override suspend fun claimTicket(ticketHash: ByteArray, now: Long): String? = tx { c ->
        val claimed = c.prepareStatement(
            "UPDATE pairing_tickets SET used=1, used_at=? WHERE ticket_hash=? AND used=0 AND expires_at>?"
        ).use { ps ->
            ps.setLong(1, now); ps.setBytes(2, ticketHash); ps.setLong(3, now)
            ps.executeUpdate()
        }
        if (claimed != 1) return@tx null
        c.prepareStatement("SELECT account_id FROM pairing_tickets WHERE ticket_hash=?").use { ps ->
            ps.setBytes(1, ticketHash)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    override suspend fun countUnredeemedTickets(accountId: String, now: Long): Int = tx { c ->
        c.prepareStatement("SELECT COUNT(*) FROM pairing_tickets WHERE account_id=? AND used=0 AND expires_at>?").use { ps ->
            ps.setString(1, accountId); ps.setLong(2, now)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    override suspend fun insertDevice(device: Device) = tx { c ->
        c.prepareStatement(
            "INSERT INTO devices(device_id, account_id, device_pubkey, credential_hash, created_at, last_seen, revoked) VALUES(?,?,?,?,?,?,0)"
        ).use { ps ->
            ps.setString(1, device.deviceId); ps.setString(2, device.accountId)
            ps.setBytes(3, device.devicePubkey); ps.setBytes(4, device.credentialHash)
            ps.setLong(5, device.createdAt); ps.setLong(6, device.createdAt)
            ps.executeUpdate()
        }
        Unit
    }

    override suspend fun getDevice(deviceId: String): Device? = tx { c ->
        c.prepareStatement(
            "SELECT account_id, device_pubkey, credential_hash, created_at, last_seen, revoked FROM devices WHERE device_id=?"
        ).use { ps ->
            ps.setString(1, deviceId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) null
                else Device(
                    deviceId = deviceId,
                    accountId = rs.getString(1),
                    devicePubkey = rs.getBytes(2),
                    credentialHash = rs.getBytes(3),
                    createdAt = rs.getLong(4),
                    lastSeen = rs.getLong(5).takeUnless { rs.wasNull() },
                    revoked = rs.getInt(6) != 0,
                )
            }
        }
    }

    override suspend fun devicesForAccount(accountId: String): List<Device> = tx { c ->
        c.prepareStatement(
            "SELECT device_id, device_pubkey, credential_hash, created_at, last_seen FROM devices WHERE account_id=? AND revoked=0"
        ).use { ps ->
            ps.setString(1, accountId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        Device(
                            deviceId = rs.getString(1),
                            accountId = accountId,
                            devicePubkey = rs.getBytes(2),
                            credentialHash = rs.getBytes(3),
                            createdAt = rs.getLong(4),
                            lastSeen = rs.getLong(5).takeUnless { rs.wasNull() },
                            revoked = false,
                        )
                    )
                }
            }
        }
    }

    override suspend fun countDevices(accountId: String): Int = tx { c ->
        c.prepareStatement("SELECT COUNT(*) FROM devices WHERE account_id=? AND revoked=0").use { ps ->
            ps.setString(1, accountId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    override suspend fun revokeDevice(accountId: String, deviceId: String): Boolean = tx { c ->
        c.prepareStatement("UPDATE devices SET revoked=1 WHERE device_id=? AND account_id=? AND revoked=0").use { ps ->
            ps.setString(1, deviceId); ps.setString(2, accountId)
            ps.executeUpdate() == 1
        }
    }

    override suspend fun touchDevice(deviceId: String, now: Long) = tx { c ->
        c.prepareStatement("UPDATE devices SET last_seen=? WHERE device_id=?").use { ps ->
            ps.setLong(1, now); ps.setString(2, deviceId); ps.executeUpdate()
        }
        Unit
    }

    override suspend fun setPushToken(deviceId: String, platform: String, token: String, now: Long) = tx { c ->
        // a blank token de-registers — null out both columns so pushTargets() drops the device
        val clear = token.isBlank()
        c.prepareStatement("UPDATE devices SET push_platform=?, push_token=?, push_updated_at=? WHERE device_id=?").use { ps ->
            if (clear) { ps.setNull(1, java.sql.Types.VARCHAR); ps.setNull(2, java.sql.Types.VARCHAR) }
            else { ps.setString(1, platform); ps.setString(2, token) }
            ps.setLong(3, now); ps.setString(4, deviceId)
            ps.executeUpdate()
        }
        Unit
    }

    override suspend fun clearPushToken(deviceId: String, platform: String, token: String, now: Long): Boolean = tx { c ->
        // conditional on the token still matching: a fresh re-registration between send and prune wins
        c.prepareStatement(
            "UPDATE devices SET push_platform=NULL, push_token=NULL, push_updated_at=? " +
                "WHERE device_id=? AND push_platform=? AND push_token=?"
        ).use { ps ->
            ps.setLong(1, now); ps.setString(2, deviceId); ps.setString(3, platform); ps.setString(4, token)
            ps.executeUpdate() > 0
        }
    }

    override suspend fun pushTargets(accountId: String): List<PushTarget> = tx { c ->
        c.prepareStatement(
            "SELECT device_id, push_platform, push_token FROM devices " +
                "WHERE account_id=? AND revoked=0 AND push_token IS NOT NULL AND push_token<>''"
        ).use { ps ->
            ps.setString(1, accountId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val platform = rs.getString(2) ?: continue
                        val token = rs.getString(3) ?: continue
                        add(PushTarget(rs.getString(1), platform, token))
                    }
                }
            }
        }
    }

    override suspend fun sweepExpired(now: Long) = tx { c ->
        c.prepareStatement("DELETE FROM pairing_tickets WHERE expires_at<? OR used=1").use { ps ->
            ps.setLong(1, now); ps.executeUpdate()
        }
        Unit
    }
}
