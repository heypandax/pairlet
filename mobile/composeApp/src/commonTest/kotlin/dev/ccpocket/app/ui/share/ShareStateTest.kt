package dev.ccpocket.app.ui.share

import dev.ccpocket.protocol.AccessTier
import dev.ccpocket.protocol.ShareInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pure state-derivation for the folder-share UI (issue #115): grouping, per-share status, and the
 *  expiry captions/countdowns the four screens render. No Compose — deterministic input→output. */
class ShareStateTest {
    private val now = 1_000_000_000_000L

    private fun share(
        id: String, expiresAt: Long, online: Boolean = false, revoked: Boolean = false,
        expired: Boolean = false, createdAt: Long = now,
    ) = ShareInfo(
        deviceId = id, path = "/p/$id", tier = AccessTier.COLLABORATE, createdAt = createdAt,
        expiresAt = expiresAt, online = online, revoked = revoked, expired = expired,
    )

    @Test fun groupSplitsLiveFromEnded() {
        val a = share("a", now + DAY)                    // active
        val b = share("b", now + DAY, revoked = true)     // ended: revoked
        val c = share("c", now - DAY)                     // ended: lapsed by clock
        val d = share("d", now + DAY, expired = true)     // ended: flagged
        val g = groupShares(listOf(a, b, c, d), now)
        assertEquals(listOf("a"), g.active.map { it.deviceId })
        assertTrue(g.history.map { it.deviceId }.containsAll(listOf("b", "c", "d")))
    }

    @Test fun activeSortedNewestFirst() {
        val old = share("old", now + DAY, createdAt = now - 10 * DAY)
        val fresh = share("fresh", now + DAY, createdAt = now - DAY)
        assertEquals(listOf("fresh", "old"), groupShares(listOf(old, fresh), now).active.map { it.deviceId })
    }

    @Test fun statusLadder() {
        assertEquals(ShareStatus.REVOKED, shareStatus(share("x", now + DAY, revoked = true), now))
        assertEquals(ShareStatus.EXPIRED, shareStatus(share("x", now - 1), now))
        assertEquals(ShareStatus.ACTIVE_NOW, shareStatus(share("x", now + DAY, online = true), now))
        assertEquals(ShareStatus.NEAR_EXPIRY, shareStatus(share("x", now + 30 * MIN), now))
        assertEquals(ShareStatus.IDLE, shareStatus(share("x", now + DAY), now))
    }

    @Test fun expiryLeftPicksCoarsestUnit() {
        assertEquals(ExpiryLeft.Days(6), expiryLeft(now + 6 * DAY + 3 * HOUR, now))
        assertEquals(ExpiryLeft.Hours(4), expiryLeft(now + 4 * HOUR + 5 * MIN, now))
        assertEquals(ExpiryLeft.Minutes(12), expiryLeft(now + 12 * MIN, now))
        assertEquals(ExpiryLeft.Minutes(1), expiryLeft(now + 30_000, now)) // under a minute floors to 1
        assertEquals(ExpiryLeft.Expired, expiryLeft(now - 1, now))
    }

    @Test fun countdownIsTwoUnits() {
        assertEquals("2d 4h", countdown(now + 2 * DAY + 4 * HOUR, now))
        assertEquals("1h 12m", countdown(now + HOUR + 12 * MIN, now))
        assertEquals("45m", countdown(now + 45 * MIN, now))
        assertEquals("expired", countdown(now - 1, now))
    }

    @Test fun ticketCountdownMmSs() {
        assertEquals("15:00", ticketCountdown(15 * 60 * 1000L))
        assertEquals("0:05", ticketCountdown(5 * 1000L))
        assertEquals("0:00", ticketCountdown(-100))
    }

    companion object {
        const val MIN = 60_000L
        const val HOUR = 60 * MIN
        const val DAY = 24 * HOUR
    }
}
