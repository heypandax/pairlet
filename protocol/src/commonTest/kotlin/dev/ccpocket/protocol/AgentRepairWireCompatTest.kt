package dev.ccpocket.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pre-repair PocketError shape: the new [PocketError.repair] must ride as a trailing optional that a
 *  legacy peer ignores. */
@Serializable
private data class LegacyPocketError(
    val code: String, val message: String, val convoId: String? = null,
)

/** dsh incomplete-install one-tap auto-repair frames (PocketError.repair, AgentRepairStart,
 *  AgentRepairProgress). Both directions degrade: an old daemon never sets repair / never sends the
 *  progress frame; an old app ignores the unknown field and drops the unknown frame type. */
class AgentRepairWireCompatTest {
    @Test
    fun repair_offer_roundtrips_and_old_client_ignores_it() {
        val frame = PocketError(
            "process_exited", "dsh install is incomplete — agent process ended (exit 1)", "c",
            repair = AgentRepairOffer(AgentKind.DSH, "reinstalling fixes this", "npm i -g @deepseek-ai/dsh@latest"),
        )
        val encoded = PocketJson.encodeToString<Frame>(frame)
        assertEquals(frame, PocketJson.decodeFromString<Frame>(encoded))
        // an old client decodes the same wire and simply never sees the repair offer (no button)
        val legacy = PocketJson.decodeFromString<LegacyPocketError>(encoded)
        assertEquals("process_exited", legacy.code)
        assertEquals("c", legacy.convoId)
    }

    @Test
    fun old_daemon_error_has_no_repair_and_null_is_omitted() {
        val decoded = PocketJson.decodeFromString<Frame>(
            """{"t":"pocket/error","code":"process_exited","message":"boom","convoId":"c"}""",
        ) as PocketError
        assertNull(decoded.repair)
        // a plain error must not put a repair key on the wire
        val encoded = PocketJson.encodeToString<Frame>(PocketError("x", "y", "c"))
        assertFalse("repair" in Json.parseToJsonElement(encoded).jsonObject, "null repair must stay omitted on the wire")
    }

    @Test
    fun repair_start_and_progress_frames_roundtrip() {
        val start: Frame = AgentRepairStart("c", AgentKind.DSH)
        assertEquals(start, PocketJson.decodeFromString<Frame>(PocketJson.encodeToString(start)))

        val progress: Frame = AgentRepairProgress("c", AgentKind.DSH, line = "added 520 packages")
        assertEquals(progress, PocketJson.decodeFromString<Frame>(PocketJson.encodeToString(progress)))
        val done: Frame = AgentRepairProgress("c", AgentKind.DSH, done = true, ok = true)
        assertEquals(done, PocketJson.decodeFromString<Frame>(PocketJson.encodeToString(done)))
    }

    @Test
    fun repair_start_defaults_to_dsh_for_older_shapes() {
        val decoded = PocketJson.decodeFromString<Frame>(
            """{"t":"pocket/agent.repair","convoId":"c"}""",
        ) as AgentRepairStart
        assertEquals(AgentKind.DSH, decoded.agent)
        assertTrue(decoded.convoId == "c")
    }
}
