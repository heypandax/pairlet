package dev.ccpocket.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wire contract for the Claude subscription-allowance frames.
 *
 * What these pin down, in order of how badly they would hurt if they drifted:
 *  1. [ClaudeQuota.status] and the row vocabulary stay TOLERANT Strings. Turned into enums, an unknown
 *     future value would be rewritten by `coerceInputValues` to the field's DEFAULT — and the default
 *     status is "ok", so a brand-new failure mode would arrive on an already-shipped app as a SUCCESS
 *     with zero limits, i.e. "your allowance is fine" while it is not. The test decodes made-up values
 *     and asserts they survive verbatim.
 *  2. Every field is a trailing optional: a peer that predates a field still decodes the frame.
 *  3. [ClaudeQuotaLimit.resetsAt] keeps its null default, so an ABSENT reset moment reads as "unknown"
 *     (no countdown) rather than as the epoch or as "now".
 */
class ClaudeQuotaWireCompatTest {

    /** The shape a client shipped BEFORE a later field was added would emit/expect. */
    @Serializable
    private data class OldClaudeQuotaLimit(
        val kind: String = "",
        val group: String = "",
        val percent: Int = 0,
    )

    @Test
    fun request_and_reply_roundtrip_with_wire_safe_defaults() {
        val req = Envelope(id = "q1", ts = 0, body = ClaudeQuotaGet())
        val reqJson = PocketJson.encodeToString(req)
        assertTrue("\"t\":\"pocket/claude.quota.get\"" in reqJson, reqJson)
        assertTrue("\"forceRefresh\":false" in reqJson, reqJson) // encodeDefaults
        assertEquals(req, PocketJson.decodeFromString<Envelope>(reqJson))

        val resp = Envelope(
            id = "q2", ts = 0,
            body = ClaudeQuota(
                limits = listOf(
                    ClaudeQuotaLimit(CLAUDE_QUOTA_KIND_SESSION, "session", 18, resetsAt = 1_787_554_200_177L),
                    ClaudeQuotaLimit(CLAUDE_QUOTA_KIND_WEEKLY_SCOPED, "weekly", 81, CLAUDE_QUOTA_SEVERITY_WARNING, 1L, true, "Fable"),
                ),
                fetchedAt = 1_700_000_000_000L,
            ),
        )
        val respJson = PocketJson.encodeToString(resp)
        assertTrue("\"t\":\"pocket/claude.quota\"" in respJson, respJson)
        assertEquals(resp, PocketJson.decodeFromString<Envelope>(respJson))
    }

    /** The request/reply shapes a client shipped BEFORE #348 (no agent field) emit/expect. */
    @Serializable
    private data class OldClaudeQuotaGet(val forceRefresh: Boolean = false)

    @Serializable
    private data class OldClaudeQuota(
        val limits: List<OldClaudeQuotaLimit> = emptyList(),
        val fetchedAt: Long = 0,
        val status: String = CLAUDE_QUOTA_OK,
        val error: String? = null,
    )

    @Test
    fun agent_rides_as_a_trailing_optional_defaulting_to_claude_both_ways() {
        // issue #348: a Codex reading keeps the same frame names; only the agent tag differs
        val req = ClaudeQuotaGet(agent = AgentKind.CODEX)
        val reqJson = PocketJson.encodeToString(req)
        assertTrue("\"agent\":\"codex\"" in reqJson, reqJson)
        assertEquals(req, PocketJson.decodeFromString<ClaudeQuotaGet>(reqJson))
        // an old client's request (no key) is a Claude request; an old daemon reading a new request
        // sees only forceRefresh — the client-side quotaAgents gate is what keeps that from mis-attributing
        assertEquals(AgentKind.CLAUDE, PocketJson.decodeFromString<ClaudeQuotaGet>("""{"forceRefresh":true}""").agent)
        assertEquals(OldClaudeQuotaGet(false), PocketJson.decodeFromString<OldClaudeQuotaGet>(reqJson))

        val reply = ClaudeQuota(
            limits = listOf(ClaudeQuotaLimit(CLAUDE_QUOTA_KIND_WEEKLY_ALL, "weekly", 48, resetsAt = 1_789_179_749_000L)),
            fetchedAt = 1_700_000_000_000L,
            agent = AgentKind.CODEX,
            planType = "pro",
        )
        val replyJson = PocketJson.encodeToString(reply)
        assertEquals(reply, PocketJson.decodeFromString<ClaudeQuota>(replyJson))
        // an old daemon's reply (no key) is Claude's; an old client skips the new keys entirely
        assertEquals(AgentKind.CLAUDE, PocketJson.decodeFromString<ClaudeQuota>("""{"status":"ok"}""").agent)
        assertNull(PocketJson.decodeFromString<ClaudeQuota>("""{"status":"ok"}""").planType)
        assertEquals(
            OldClaudeQuota(limits = listOf(OldClaudeQuotaLimit(CLAUDE_QUOTA_KIND_WEEKLY_ALL, "weekly", 48)), fetchedAt = 1_700_000_000_000L),
            PocketJson.decodeFromString<OldClaudeQuota>(replyJson),
        )
        // a future agent name coerces to the declared default (coerceInputValues) instead of failing the frame
        assertEquals(AgentKind.CLAUDE, PocketJson.decodeFromString<ClaudeQuota>("""{"agent":"some-future-agent"}""").agent)
    }

    @Test
    fun an_unknown_status_or_kind_survives_verbatim_instead_of_degrading_to_ok() {
        // the whole reason these are Strings: a future daemon's new vocabulary must not read as success
        val q = PocketJson.decodeFromString<ClaudeQuota>(
            """{"status":"rate_limited","limits":[{"kind":"monthly_opus","group":"monthly","percent":3,"severity":"critical"}]}""",
        )
        assertEquals("rate_limited", q.status)
        assertEquals("monthly_opus", q.limits[0].kind)
        assertEquals("critical", q.limits[0].severity)
    }

    @Test
    fun an_empty_body_decodes_to_the_ok_but_empty_reading_and_absent_reset_stays_null() {
        val q = PocketJson.decodeFromString<ClaudeQuota>("{}")
        assertEquals(CLAUDE_QUOTA_OK, q.status)
        assertTrue(q.limits.isEmpty())
        assertEquals(0L, q.fetchedAt)
        assertNull(q.error)

        val row = PocketJson.decodeFromString<ClaudeQuotaLimit>("""{"kind":"session","group":"session","percent":18}""")
        assertNull(row.resetsAt, "absent reset moment must stay unknown, never become 0/now")
        assertEquals(CLAUDE_QUOTA_SEVERITY_NORMAL, row.severity)
        assertEquals(false, row.isActive)
        assertNull(row.modelDisplayName)
    }

    @Test
    fun an_old_peer_skips_the_fields_it_never_knew() {
        val full = PocketJson.encodeToString(
            ClaudeQuotaLimit(CLAUDE_QUOTA_KIND_WEEKLY_SCOPED, "weekly", 81, CLAUDE_QUOTA_SEVERITY_WARNING, 42L, true, "Fable"),
        )
        val old = PocketJson.decodeFromString<OldClaudeQuotaLimit>(full)
        assertEquals(CLAUDE_QUOTA_KIND_WEEKLY_SCOPED, old.kind)
        assertEquals(81, old.percent)
    }

    @Test
    fun a_structurally_unknown_future_field_is_skipped_without_damaging_the_known_ones() {
        // `ignoreUnknownKeys` has to skip a whole SUBTREE, not just a scalar: the upstream payload this
        // frame mirrors already grows nested experiment objects, so the first field a future daemon adds
        // is as likely to be an array of objects as a string. If the skip were shallow the decoder would
        // desynchronise and take the known fields down with it — the failure would look like corruption,
        // not like a version gap.
        val json = """
            {"status":"$CLAUDE_QUOTA_OK","fetchedAt":42,
             "future":[{"a":1,"b":{"c":2}}],
             "limits":[{"kind":"session","group":"session","percent":18,"resetsAt":7,
                        "future":[{"a":1,"b":{"c":2}}]}],
             "alsoFuture":{"deep":{"deeper":[null,true,"x"]}}}
        """.trimIndent()
        val q = PocketJson.decodeFromString<ClaudeQuota>(json)
        assertEquals(CLAUDE_QUOTA_OK, q.status)
        assertEquals(42L, q.fetchedAt)
        assertEquals(1, q.limits.size)
        assertEquals(CLAUDE_QUOTA_KIND_SESSION, q.limits[0].kind)
        assertEquals("session", q.limits[0].group)
        assertEquals(18, q.limits[0].percent)
        assertEquals(7L, q.limits[0].resetsAt, "a field AFTER the unknown subtree still decodes")
    }

    @Test
    fun a_failure_reply_carries_no_limits_and_a_null_error_is_omitted_from_the_wire() {
        val ok = PocketJson.encodeToString(ClaudeQuota(status = CLAUDE_QUOTA_OK))
        assertTrue("error" !in ok, ok) // explicitNulls = false
        val no = PocketJson.decodeFromString<ClaudeQuota>("""{"status":"$CLAUDE_QUOTA_NO_TOKEN","error":"not signed in"}""")
        assertEquals(CLAUDE_QUOTA_NO_TOKEN, no.status)
        assertTrue(no.limits.isEmpty())
    }
}
