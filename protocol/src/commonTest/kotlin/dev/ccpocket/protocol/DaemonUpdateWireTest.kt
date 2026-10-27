package dev.ccpocket.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.test.*

@Serializable
private data class LegacyUpdateDaemonInfo(val daemonVersion: String? = null)

class DaemonUpdateWireTest {
    @Test fun capability_is_optional_and_old_readers_ignore_it() {
        assertNull(PocketJson.decodeFromString<DaemonInfo>("{}").daemonUpdateMode)
        assertFalse(PocketJson.encodeToString(DaemonInfo()).contains("daemonUpdateMode"))
        val encoded = PocketJson.encodeToString(DaemonInfo(daemonVersion = "2.6.0", daemonUpdateMode = "remote"))
        assertEquals("2.6.0", PocketJson.decodeFromString<LegacyUpdateDaemonInfo>(encoded).daemonVersion)
    }

    @Test fun request_and_progress_roundtrip_with_future_state_words() {
        for (frame in listOf<Frame>(DaemonUpdateStart("attempt"), DaemonUpdateGet("attempt"), DaemonUpdateStatus("attempt", "future-state", "2.6.0", true))) {
            val envelope = Envelope(id = "update", ts = 0, body = frame)
            assertEquals(envelope, PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(envelope)))
        }
    }

    @Test fun update_gate_blocks_new_work_but_allows_existing_work_to_finish() {
        for (frame in listOf<Frame>(
            PermissionVerdict("chat", "permission", Decision.ALLOW),
            PermissionVerdict("chat", "question", Decision.ALLOW, answers = mapOf("Continue?" to "Yes")),
            ApprovalAttentionHeartbeat("chat", "permission", true),
            ApprovalAttentionHeartbeat("chat", "permission", false),
            CancelTurn("chat"), StopBackgroundJob("chat", "job"),
        )) assertTrue(DaemonUpdateAccess.allowed(frame), "existing work must be able to settle: $frame")
        assertFalse(DaemonUpdateAccess.allowed(SendPrompt("chat", "new task")))
        assertFalse(DaemonUpdateAccess.allowed(OpenSession("/work")))
    }
}
