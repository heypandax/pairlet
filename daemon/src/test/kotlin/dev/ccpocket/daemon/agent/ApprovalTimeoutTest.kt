package dev.ccpocket.daemon.agent

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The unified ask/verdict window (issue #100) and the bridge-session 120s floor (issue #91,
 * restored by #32 after the #100/#91 merge dropped it).
 */
class ApprovalTimeoutTest {

    @Test
    fun defaults_to_600s_when_unset_or_garbage() {
        assertEquals(600_000L, ApprovalTimeout.fromEnv(null))
        assertEquals(600_000L, ApprovalTimeout.fromEnv(""))
        assertEquals(600_000L, ApprovalTimeout.fromEnv("soon"))
    }

    @Test
    fun env_seconds_parse_and_clamp_to_30s_24h() {
        assertEquals(30_000L, ApprovalTimeout.fromEnv("30"))
        assertEquals(30_000L, ApprovalTimeout.fromEnv("5")) // clamp floor
        assertEquals(86_400_000L, ApprovalTimeout.fromEnv("999999")) // clamp ceiling
        assertEquals(3_600_000L, ApprovalTimeout.fromEnv(" 3600 ")) // trimmed
    }

    @Test
    fun bridge_floor_is_120s_regardless_of_a_short_user_preference() {
        // the #32 regression: CC_POCKET_ASK_TIMEOUT_SEC=30 must not shrink a bridge owner's
        // push → tap → reattach arrival window below 120s
        assertEquals(120_000L, ApprovalTimeout.bridgeMs(30_000L))
        assertEquals(120_000L, ApprovalTimeout.bridgeMs(120_000L))
    }

    @Test
    fun bridge_floor_never_shortens_a_generous_window() {
        assertEquals(600_000L, ApprovalTimeout.bridgeMs(600_000L)) // default untouched
        assertEquals(86_400_000L, ApprovalTimeout.bridgeMs(86_400_000L))
    }
}
