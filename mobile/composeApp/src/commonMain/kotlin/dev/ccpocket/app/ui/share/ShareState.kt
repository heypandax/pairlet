package dev.ccpocket.app.ui.share

import dev.ccpocket.protocol.AccessTier
import dev.ccpocket.protocol.ShareInfo

/**
 * Pure state derivation for the folder-share UI (issue #115). No Compose here — every function is a
 * plain input→output so the invite-config, management-list, redeem-preview and terminal-state logic is
 * unit-testable without a UI harness. The screens read these; the tests assert on them.
 */

/** The lifetime options offered in the composer (label seconds). The daemon coerces to 5min–90day, so
 *  every option here is already inside that window; [DEFAULT] mirrors the protocol default (7 days). */
enum class ShareExpiryOption(val seconds: Long) {
    HOUR_1(3_600L),
    HOURS_24(24L * 3_600),
    DAYS_7(7L * 24 * 3_600),
    DAYS_30(30L * 24 * 3_600);

    companion object {
        val DEFAULT = DAYS_7
    }
}

/** The access tiers offered in the composer, least→most autonomous. UNKNOWN is a decode fallback, never shown. */
val SHARE_TIERS: List<AccessTier> = listOf(AccessTier.REVIEW, AccessTier.COLLABORATE, AccessTier.AUTONOMOUS)

/** The recommended default tier (pre-selected, "RECOMMENDED" pill) — matches the protocol default. */
val DEFAULT_TIER: AccessTier = AccessTier.COLLABORATE

/** The owner's composer selection, before it becomes a CreateShare. */
data class InviteConfig(
    val tier: AccessTier = DEFAULT_TIER,
    val expiry: ShareExpiryOption = ShareExpiryOption.DEFAULT,
)

/**
 * Owner management grouping (screens 2a "Shared folders" vs 4d "History"): live shares up top, ended
 * ones (revoked or lapsed) fall to History. Within each, newest-created first so the freshest share leads.
 */
data class ShareGroups(val active: List<ShareInfo>, val history: List<ShareInfo>)

fun groupShares(items: List<ShareInfo>, now: Long): ShareGroups {
    val (ended, live) = items.partition { it.revoked || it.expired || it.expiresAt <= now }
    return ShareGroups(
        active = live.sortedByDescending { it.createdAt },
        history = ended.sortedByDescending { it.createdAt },
    )
}

/** A share row's live status → drives the status line + accent on the management card. */
enum class ShareStatus { ACTIVE_NOW, NEAR_EXPIRY, IDLE, EXPIRED, REVOKED }

/** Within this window of the cut instant, an idle share flips to the amber "near expiry" treatment. */
const val NEAR_EXPIRY_MS: Long = 2L * 60 * 60 * 1000 // 2 hours

fun shareStatus(s: ShareInfo, now: Long): ShareStatus = when {
    s.revoked -> ShareStatus.REVOKED
    s.expired || s.expiresAt <= now -> ShareStatus.EXPIRED
    s.online -> ShareStatus.ACTIVE_NOW
    s.expiresAt - now <= NEAR_EXPIRY_MS -> ShareStatus.NEAR_EXPIRY
    else -> ShareStatus.IDLE
}

/**
 * How much validity is left, in the coarsest sensible unit — the "6d left" / "4h left" / "12m left"
 * caption on a guest's shared row and the guest terminal check. Callers map each case to its own string
 * resource so the number stays a proper plural/format arg (no baked-in English).
 */
sealed interface ExpiryLeft {
    data class Days(val n: Int) : ExpiryLeft
    data class Hours(val n: Int) : ExpiryLeft
    data class Minutes(val n: Int) : ExpiryLeft
    data object Expired : ExpiryLeft
}

fun expiryLeft(expiresAt: Long, now: Long): ExpiryLeft {
    val ms = expiresAt - now
    if (ms <= 0) return ExpiryLeft.Expired
    val minutes = ms / 60_000
    return when {
        minutes >= 24 * 60 -> ExpiryLeft.Days((minutes / (24 * 60)).toInt())
        minutes >= 60 -> ExpiryLeft.Hours((minutes / 60).toInt())
        else -> ExpiryLeft.Minutes(minutes.toInt().coerceAtLeast(1))
    }
}

/**
 * A longer two-unit countdown ("2d 4h", "1h 12m", "45m") for the owner management card's "expires in …"
 * line and the invite recap. Locale-neutral d/h/m tokens; "expired" once past the instant.
 */
fun countdown(expiresAt: Long, now: Long): String {
    val ms = expiresAt - now
    if (ms <= 0) return "expired"
    val totalMin = ms / 60_000
    val days = totalMin / (24 * 60)
    val hours = (totalMin % (24 * 60)) / 60
    val mins = totalMin % 60
    return when {
        days > 0 -> if (hours > 0) "${days}d ${hours}h" else "${days}d"
        hours > 0 -> if (mins > 0) "${hours}h ${mins}m" else "${hours}h"
        else -> "${mins}m"
    }
}

/** mm:ss remaining for the invite ticket's short TTL countdown (screen 1c "15:00"). Clamps at 0:00. */
fun ticketCountdown(remainingMs: Long): String {
    val total = (remainingMs / 1000).coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    val ss = if (s < 10) "0$s" else "$s"
    return "$m:$ss"
}

/** Guest terminal classification for an ended shared row/card (screens 4a/4b). */
enum class GuestEnding { EXPIRED, REVOKED }
