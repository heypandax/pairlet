package dev.ccpocket.protocol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wire contract for the #367 execution plane (wire review P1). The plane is NOT frozen, so this file's job
 * is to make every property the two daemons rely on FAIL LOUDLY the moment a shape changes — the two ends
 * ship and update independently, and there is no App in the middle to notice a drift.
 *
 * What is pinned here, and why each one earned a test:
 *  - all nine frames round-trip under their `@SerialName` discriminator;
 *  - a MINIMAL body (only the non-defaulted fields) decodes, so an older source that omits everything
 *    optional is understood rather than dropped;
 *  - unknown keys are skipped INCLUDING inside arrays of objects — the shape a newer peer's added field
 *    actually takes, and the one `ignoreUnknownKeys` is easiest to get wrong about;
 *  - an unknown `mode` coerces to null (a newer permission mode must not kill the frame);
 *  - an unknown `agent` decodes FINE and is refused by NAME — the reason that field is a string;
 *  - `explicitNulls = false` really does omit nulls, which is what keeps a submit small;
 *  - a 256 KiB all-escapes text still encodes inside [EXECUTION_FRAME_BUDGET_BYTES], i.e. the output
 *    budget and the frame budget cannot collide even in the worst case.
 */
class ExecutionWireCompatTest {

    private fun roundTrip(frame: Frame): Frame =
        PocketJson.decodeFromString<Frame>(PocketJson.encodeToString(frame))

    private fun discriminator(frame: Frame): String =
        PocketJson.parseToJsonElement(PocketJson.encodeToString(frame)).jsonObject["t"]!!.toString().trim('"')

    private val allNine: List<Frame> = listOf(
        ExecutionGrantQuery("xg_abc"),
        ExecutionGrantInfo("xg_abc", 3, "active", 99, listOf("app"), listOf("claude"), "acceptEdits", 2, 4, 60_000, 200),
        ExecutionRunSubmit("rq_1", "xg_abc", 3, "app", "claude", "do it", "sonnet", PermissionMode.DEFAULT),
        ExecutionRunStatus("rq_2", "xg_abc", "xr_1"),
        ExecutionRunResult("rq_3", "xg_abc", "xr_1", "128"),
        ExecutionRunCancel("rq_4", "xg_abc", "xr_1"),
        ExecutionRunAccepted("rq_1", "xr_1", "accepted", duplicate = true, revision = 3),
        ExecutionRunState("rq_2", "xr_1", "running", "queued", approvalPending = true, updatedAt = 7, error = null),
        ExecutionRunOutput("rq_3", "xr_1", "running", "0", "128", "hello", truncated = true, done = false),
    )

    @Test
    fun all_nine_frames_round_trip_under_their_discriminator() {
        val names = allNine.map { discriminator(it) }
        assertEquals(names.toSet().size, names.size, "every frame needs its OWN discriminator: $names")
        assertTrue(names.all { it.startsWith("pocket/execution.") }, "one namespace for the whole plane: $names")
        for (frame in allNine) assertEquals(frame, roundTrip(frame), "round trip of ${frame::class.simpleName}")
    }

    @Test
    fun a_minimal_body_decodes_with_the_documented_defaults() {
        // exactly the non-defaulted fields — what a source that sets nothing optional puts on the wire
        val submit = PocketJson.decodeFromString<Frame>(
            """{"t":"pocket/execution.run_submit","requestId":"rq_1","grantId":"xg_abc","revision":1,""" +
                """"workspaceAlias":"app","agent":"claude","prompt":"go"}""",
        )
        assertIs<ExecutionRunSubmit>(submit)
        assertNull(submit.model); assertNull(submit.mode)

        val accepted = PocketJson.decodeFromString<Frame>(
            """{"t":"pocket/execution.run_accepted","requestId":"rq_1","runId":"xr_1","state":"accepted"}""",
        )
        assertIs<ExecutionRunAccepted>(accepted)
        assertFalse(accepted.duplicate, "a body with no `duplicate` is a FIRST accept, never a retry")
        assertEquals(0, accepted.revision, "…and an older target that sends no revision reads as 'unknown', not as 0-is-real")

        val info = PocketJson.decodeFromString<Frame>(
            """{"t":"pocket/execution.grant_info","grantId":"xg_abc","revision":1,"state":"awaiting_owner_confirm","expiresAt":9}""",
        )
        assertIs<ExecutionGrantInfo>(info)
        assertTrue(info.workspaceAliases.isEmpty() && info.agents.isEmpty(), "a non-active grant discloses no scope")
        assertNull(info.approvalCeiling)

        val output = PocketJson.decodeFromString<Frame>(
            """{"t":"pocket/execution.run_output","requestId":"rq_3","runId":"xr_1","state":"completed"}""",
        )
        assertIs<ExecutionRunOutput>(output)
        assertEquals("", output.text); assertNull(output.cursor); assertNull(output.nextCursor)
        assertFalse(output.truncated); assertFalse(output.done)
    }

    @Test
    fun unknown_keys_are_skipped_including_inside_arrays_of_objects() {
        // the shape a NEWER peer's addition really takes: extra scalars, extra objects, and extra
        // objects nested in an array — the last is the one a hand-rolled skip gets wrong
        val json = buildJsonObject {
            put("t", "pocket/execution.grant_info")
            put("grantId", "xg_abc"); put("revision", 2); put("state", "active"); put("expiresAt", 5)
            put("futureScalar", 1)
            put("futureObject", buildJsonObject { put("a", buildJsonObject { put("b", 1) }) })
            put(
                "futureRows",
                buildJsonArray {
                    add(buildJsonObject { put("k", "v"); put("nested", buildJsonObject { put("deep", true) }) })
                    add(buildJsonObject { put("k", "v2") })
                },
            )
        }
        val info = assertIs<ExecutionGrantInfo>(PocketJson.decodeFromString<Frame>(json.toString()))
        assertEquals("xg_abc", info.grantId)
        assertEquals(2, info.revision)
    }

    @Test
    fun an_unknown_permission_mode_coerces_to_null_instead_of_killing_the_frame() {
        val submit = assertIs<ExecutionRunSubmit>(
            PocketJson.decodeFromString<Frame>(
                """{"t":"pocket/execution.run_submit","requestId":"rq_1","grantId":"xg_abc","revision":1,""" +
                    """"workspaceAlias":"app","agent":"claude","prompt":"go","mode":"someFutureMode"}""",
            ),
        )
        // coerceInputValues turns the unknown enum into the field default (null) — the target then applies
        // the grant's own ceiling, which is the safe reading
        assertNull(submit.mode, "an unknown mode must degrade to 'unspecified', never fail the decode")
    }

    @Test
    fun an_unknown_agent_decodes_and_is_refused_by_name() {
        // THE reason `agent` is a wire String (see its KDoc). As an enum this body would fail to decode and
        // the source would see silence — indistinguishable from a dead link. As a string it decodes, and
        // the target answers `agent_not_allowed`, which is both true and actionable.
        val submit = assertIs<ExecutionRunSubmit>(
            PocketJson.decodeFromString<Frame>(
                """{"t":"pocket/execution.run_submit","requestId":"rq_1","grantId":"xg_abc","revision":1,""" +
                    """"workspaceAlias":"app","agent":"gemini-9","prompt":"go"}""",
            ),
        )
        assertEquals("gemini-9", submit.agent)
        assertNull(executionAgentOrNull(submit.agent), "a backend this build cannot name resolves to nothing…")
        // …and every backend this build DOES have round-trips through the same pair of helpers
        for (kind in AgentKind.entries) {
            assertEquals(kind, executionAgentOrNull(executionAgentWire(kind)), "wire name of $kind")
        }
        assertEquals(AgentKind.CLAUDE, executionAgentOrNull(" claude "), "surrounding space is tolerated")
    }

    @Test
    fun nulls_are_omitted_from_the_encoded_body() {
        val json = PocketJson.encodeToString<Frame>(
            ExecutionRunSubmit("rq_1", "xg_abc", 1, "app", "claude", "go", model = null, mode = null),
        )
        assertFalse("model" in json, "explicitNulls=false must keep an unset model off the wire: $json")
        assertFalse("mode" in json, "…and an unset mode: $json")
        assertContains(json, "\"agent\":\"claude\"")
    }

    @Test
    fun a_worst_case_output_page_still_fits_the_frame_budget() {
        // every byte an escape: 256 KiB of quotes is the densest legal expansion JSON has (1 char -> 2),
        // so if THIS fits, no output page can ever overflow the frame the target must seal it into
        val worst = "\"".repeat(EXECUTION_OUTPUT_MAX_BYTES)
        val encoded = PocketJson.encodeToString<Frame>(
            ExecutionRunOutput("rq_3", "xr_1", "running", "0", "262144", worst, truncated = true, done = false),
        )
        assertTrue(
            encoded.encodeToByteArray().size < EXECUTION_FRAME_BUDGET_BYTES,
            "a full, fully-escaped output page must stay under the frame budget: ${encoded.length} chars",
        )
        assertEquals(worst, assertIs<ExecutionRunOutput>(PocketJson.decodeFromString<Frame>(encoded)).text)
    }

    @Test
    fun the_invite_door_is_its_own_prefix() {
        // an older App's collaborator scanner / an older daemon's `review join` must fall through rather
        // than burn a one-time ticket under a purpose neither side agreed on
        assertTrue(EXECUTION_GRANT_INVITE_URI_PREFIX.startsWith("ccpocket://execution-grant"))
        assertFalse(EXECUTION_GRANT_INVITE_URI_PREFIX.startsWith("ccpocket://collab"))
        assertNotNull(EXECUTION_GRANT_INVITE_URI_PREFIX.substringAfter("#", "").let { "" })
    }
}
