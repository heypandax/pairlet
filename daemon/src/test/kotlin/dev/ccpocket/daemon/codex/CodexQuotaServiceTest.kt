package dev.ccpocket.daemon.codex

import dev.ccpocket.daemon.codex.CodexQuotaService.AppServerOutcome
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.CLAUDE_QUOTA_HTTP
import dev.ccpocket.protocol.CLAUDE_QUOTA_KIND_SESSION
import dev.ccpocket.protocol.CLAUDE_QUOTA_KIND_WEEKLY_ALL
import dev.ccpocket.protocol.CLAUDE_QUOTA_KIND_WEEKLY_SCOPED
import dev.ccpocket.protocol.CLAUDE_QUOTA_NETWORK
import dev.ccpocket.protocol.CLAUDE_QUOTA_NO_TOKEN
import dev.ccpocket.protocol.CLAUDE_QUOTA_OK
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract for the CODEX allowance reader (issue #348). NOTHING here spawns a process or touches the
 * developer's Codex login: the binary resolution and the transport are both constructor seams.
 *
 * The fixture is a REAL capture's shape (codex-cli 0.153.4, 2026-09-07, values kept because they are not
 * identifying): a `secondary: null` weekly-only account, a `credits` object we do not model, and a
 * `rateLimitsByLimitId` map whose non-`codex` entries carry the per-model caps. The weekly-only case is
 * the one that matters — assuming Claude's always-both-windows shape would draw a phantom 0% session row.
 */
class CodexQuotaServiceTest {

    private val fixture = """
        {
          "rateLimits": {
            "limitId": "codex", "limitName": null,
            "primary": { "usedPercent": 50, "windowDurationMins": 10080, "resetsAt": 1789179749 },
            "secondary": null,
            "credits": { "hasCredits": false, "unlimited": false, "balance": "0" },
            "individualLimit": null, "spendControlReached": false,
            "planType": "pro", "rateLimitReachedType": null
          },
          "rateLimitsByLimitId": {
            "codex": {
              "limitId": "codex", "limitName": null,
              "primary": { "usedPercent": 50, "windowDurationMins": 10080, "resetsAt": 1789179749 },
              "secondary": null, "planType": "pro"
            },
            "base_model_inference": {
              "limitId": "base_model_inference", "limitName": "gpt-reserve",
              "primary": { "usedPercent": 0, "windowDurationMins": 10080, "resetsAt": 1789320999 },
              "secondary": null, "credits": null, "planType": "pro"
            },
            "codex_bengalfox": {
              "limitId": "codex_bengalfox", "limitName": "GPT-5.3-Codex-Spark",
              "primary": { "usedPercent": 12, "windowDurationMins": 300, "resetsAt": 1788734199 },
              "secondary": { "usedPercent": 4, "windowDurationMins": 10080, "resetsAt": 1789320999 },
              "credits": null, "planType": "pro"
            }
          },
          "rateLimitResetCredits": { "availableCount": 2, "credits": [] },
          "future_field": [1, 2, 3]
        }
    """.trimIndent()

    private val exe: Path = Path.of("/nonexistent/codex")

    private fun service(
        outcome: AppServerOutcome,
        now: () -> Long = { 1_700_000_000_000 },
        binary: () -> Path? = { exe },
    ) = CodexQuotaService(now = now, binary = binary, transport = { outcome })

    // -- parsing ------------------------------------------------------------------------------------

    @Test
    fun a_weekly_only_account_yields_exactly_one_account_window_and_no_phantom_session_row() = runBlocking {
        val q = service(AppServerOutcome.Result(fixture)).get()
        assertEquals(CLAUDE_QUOTA_OK, q.status)
        val account = q.limits.filter { it.modelDisplayName == null }
        assertEquals(1, account.size, "secondary is null upstream - a second account row would be invented")
        assertEquals(CLAUDE_QUOTA_KIND_WEEKLY_ALL, account[0].kind)
        assertEquals("weekly", account[0].group)
        assertEquals(50, account[0].percent)
    }

    @Test
    fun the_reply_is_tagged_codex_and_carries_the_plan_type() = runBlocking {
        val q = service(AppServerOutcome.Result(fixture)).get()
        assertEquals(AgentKind.CODEX, q.agent)
        assertEquals("pro", q.planType)
    }

    @Test
    fun resets_at_is_converted_from_epoch_SECONDS_to_epoch_millis() = runBlocking {
        val q = service(AppServerOutcome.Result(fixture)).get()
        // a missed x1000 would put this in January 1970 and the countdown would read "resets now" forever
        assertEquals(1789179749_000L, q.limits.first { it.modelDisplayName == null }.resetsAt)
    }

    @Test
    fun a_window_of_300_minutes_or_less_is_a_session_window_and_a_longer_one_is_weekly() = runBlocking {
        val q = service(AppServerOutcome.Result(fixture)).get()
        val spark = q.limits.filter { it.modelDisplayName == "GPT-5.3-Codex-Spark" }
        assertEquals(2, spark.size, "the scoped entry has both a 300-min and a 10080-min window")
        val short = spark.first { it.percent == 12 }
        val long = spark.first { it.percent == 4 }
        assertEquals(CLAUDE_QUOTA_KIND_SESSION, short.kind)
        assertEquals("session", short.group)
        assertEquals(CLAUDE_QUOTA_KIND_WEEKLY_SCOPED, long.kind)
        assertEquals("weekly", long.group)
    }

    @Test
    fun the_account_wide_entry_of_the_per_limit_map_is_not_emitted_twice() = runBlocking {
        val q = service(AppServerOutcome.Result(fixture)).get()
        // rateLimitsByLimitId["codex"] duplicates the top-level rateLimits; emitting it again would draw
        // the headline weekly window twice, once unscoped and once labelled "codex"
        assertNull(q.limits.firstOrNull { it.modelDisplayName == "codex" })
        assertEquals(1, q.limits.count { it.modelDisplayName == null })
    }

    @Test
    fun a_nameless_per_limit_entry_falls_back_to_its_raw_id_rather_than_disappearing() = runBlocking {
        val body = """
            {"rateLimits":{"limitId":"codex","primary":{"usedPercent":3,"windowDurationMins":10080,"resetsAt":1789179749}},
             "rateLimitsByLimitId":{"some_new_cap":{"limitId":"some_new_cap","limitName":null,
               "primary":{"usedPercent":9,"windowDurationMins":10080,"resetsAt":1789179749}}}}
        """.trimIndent()
        val q = service(AppServerOutcome.Result(body)).get()
        assertEquals("some_new_cap", q.limits.first { it.percent == 9 }.modelDisplayName)
    }

    @Test
    fun the_binding_account_window_is_the_one_closest_to_running_out() = runBlocking {
        val body = """
            {"rateLimits":{"limitId":"codex","planType":"plus",
              "primary":{"usedPercent":10,"windowDurationMins":300,"resetsAt":1789179749},
              "secondary":{"usedPercent":72,"windowDurationMins":10080,"resetsAt":1789320999}}}
        """.trimIndent()
        val q = service(AppServerOutcome.Result(body)).get()
        // "primary" is a SLOT name, not a verdict about which cap stops you first
        assertTrue(q.limits.first { it.percent == 72 }.isActive)
        assertFalse(q.limits.first { it.percent == 10 }.isActive)
        // ...and only one row may claim it
        assertEquals(1, q.limits.count { it.isActive })
    }

    @Test
    fun a_percent_outside_0_100_is_clamped_and_a_fractional_one_is_rounded() = runBlocking {
        val body = """
            {"rateLimits":{"limitId":"codex",
              "primary":{"usedPercent":140,"windowDurationMins":10080,"resetsAt":1789179749},
              "secondary":{"usedPercent":-3,"windowDurationMins":300,"resetsAt":1789179749}}}
        """.trimIndent()
        val q = service(AppServerOutcome.Result(body)).get()
        assertEquals(100, q.limits.first { it.group == "weekly" }.percent)
        assertEquals(0, q.limits.first { it.group == "session" }.percent)

        val rounded = service(
            AppServerOutcome.Result(
                """{"rateLimits":{"primary":{"usedPercent":48.6,"windowDurationMins":10080,"resetsAt":1789179749}}}""",
            ),
        ).get()
        assertEquals(49, rounded.limits.single().percent)
    }

    @Test
    fun an_absent_or_non_positive_resets_at_shows_no_countdown_rather_than_1970() = runBlocking {
        val q = service(
            AppServerOutcome.Result("""{"rateLimits":{"primary":{"usedPercent":5,"windowDurationMins":10080}}}"""),
        ).get()
        assertNull(q.limits.single().resetsAt)
        assertNull(CodexQuotaService.epochMs(0))
        assertNull(CodexQuotaService.epochMs(null))
    }

    @Test
    fun an_unparseable_payload_degrades_to_http_error_instead_of_throwing() = runBlocking {
        val q = service(AppServerOutcome.Result("not json at all")).get()
        assertEquals(CLAUDE_QUOTA_HTTP, q.status)
        assertTrue(q.limits.isEmpty())
    }

    // -- failure mapping ----------------------------------------------------------------------------

    @Test
    fun no_codex_binary_is_no_token_the_state_the_client_hides() = runBlocking {
        val q = CodexQuotaService(binary = { null }, transport = { error("must not be reached") }).get()
        assertEquals(CLAUDE_QUOTA_NO_TOKEN, q.status)
        assertEquals(AgentKind.CODEX, q.agent)
        assertTrue(q.limits.isEmpty())
    }

    @Test
    fun an_auth_flavoured_rpc_error_is_no_token_not_an_alarm() = runBlocking {
        for (msg in listOf(
            "Not logged in. Run `codex login` to continue.",
            "unauthorized",
            "authentication required",
            "no credentials found in auth.json",
        )) {
            val q = service(AppServerOutcome.RpcError(msg)).get()
            assertEquals(CLAUDE_QUOTA_NO_TOKEN, q.status, "auth-flavoured error must hide, not alarm: $msg")
        }
    }

    @Test
    fun any_other_rpc_error_is_http_error_and_carries_the_message() = runBlocking {
        val q = service(AppServerOutcome.RpcError("upstream 503 from the rate-limit service")).get()
        assertEquals(CLAUDE_QUOTA_HTTP, q.status)
        assertTrue(q.error!!.contains("503"), "the reason must survive: ${q.error}")
    }

    @Test
    fun a_control_character_or_runaway_message_is_bounded_before_it_reaches_the_wire() {
        val long = "x".repeat(5_000) + "bell" + 7.toChar() + "tab" + 9.toChar()
        val clean = CodexQuotaService.sanitize(long)
        assertTrue(clean.length <= 200)
        assertFalse(clean.any { it.isISOControl() })
    }

    @Test
    fun a_spawn_or_timeout_failure_is_the_TRANSIENT_network_status() = runBlocking {
        val q = service(AppServerOutcome.Failure("the Codex app-server did not answer in time")).get()
        // transient, not signed-out: the client keeps its last good snapshot on this one
        assertEquals(CLAUDE_QUOTA_NETWORK, q.status)
        assertEquals("the Codex app-server did not answer in time", q.error)
    }

    @Test
    fun a_throwing_transport_never_escapes_into_the_routers_coroutine() = runBlocking {
        val q = CodexQuotaService(binary = { exe }, transport = { throw IllegalStateException("boom") }).get()
        assertEquals(CLAUDE_QUOTA_NETWORK, q.status)
    }

    // -- caching ------------------------------------------------------------------------------------

    @Test
    fun a_success_is_reused_for_a_minute_and_forceRefresh_goes_out_anyway() = runBlocking {
        val calls = AtomicInteger()
        var clock = 1_000_000L
        val svc = CodexQuotaService(
            now = { clock },
            binary = { exe },
            transport = { calls.incrementAndGet(); AppServerOutcome.Result(fixture) },
        )
        svc.get(); svc.get()
        assertEquals(1, calls.get(), "a second read inside the TTL must not spawn a second app-server")
        svc.get(forceRefresh = true)
        assertEquals(2, calls.get(), "pull-to-refresh bypasses the cache")
        clock += CodexQuotaService.OK_TTL_MS + 1
        svc.get()
        assertEquals(3, calls.get())
    }

    @Test
    fun a_failure_is_cached_for_a_shorter_window_than_a_success() = runBlocking {
        val calls = AtomicInteger()
        var clock = 1_000_000L
        val svc = CodexQuotaService(
            now = { clock },
            binary = { exe },
            transport = { calls.incrementAndGet(); AppServerOutcome.Failure("offline") },
        )
        svc.get()
        clock += CodexQuotaService.FAIL_TTL_MS - 1
        svc.get()
        assertEquals(1, calls.get())
        clock += 2
        svc.get()
        assertEquals(2, calls.get(), "a reconnecting user must not wait out a success-length TTL")
    }

    @Test
    fun a_cached_reply_keeps_reporting_the_moment_the_numbers_were_actually_true() = runBlocking {
        var clock = 1_000_000L
        val svc = CodexQuotaService(now = { clock }, binary = { exe }, transport = { AppServerOutcome.Result(fixture) })
        val first = svc.get()
        clock += 30_000
        val second = svc.get()
        assertEquals(first.fetchedAt, second.fetchedAt, "a cached reply must age honestly, not look freshly fetched")
    }

    // -- availability advertisement -----------------------------------------------------------------

    @Test
    fun availability_follows_the_binary_and_is_memoized_only_briefly() {
        var present = false
        var clock = 1_000_000L
        val probes = AtomicInteger()
        val svc = CodexQuotaService(now = { clock }, binary = { probes.incrementAndGet(); if (present) exe else null })
        assertFalse(svc.available())
        assertFalse(svc.available())
        assertEquals(1, probes.get(), "the handshake path must not re-walk the PATH on every connect")
        // installing the CLI must not require a daemon restart to be advertised
        present = true
        clock += CodexQuotaService.AVAILABILITY_TTL_MS + 1
        assertTrue(svc.available())
    }

    @Test
    fun availability_never_throws_out_of_a_broken_resolver() {
        val svc = CodexQuotaService(binary = { throw RuntimeException("PATH is on fire") })
        assertFalse(svc.available())
    }

    // -- the mapping helpers, directly --------------------------------------------------------------

    @Test
    fun the_auth_hint_matcher_does_not_swallow_an_ordinary_failure() {
        assertTrue(CodexQuotaService.looksLikeAuthFailure("Please sign in first"))
        assertFalse(CodexQuotaService.looksLikeAuthFailure("internal server error"))
        assertFalse(CodexQuotaService.looksLikeAuthFailure("rate limit service unavailable"))
    }

    @Test
    fun parse_stamps_the_supplied_fetch_moment() {
        val q = CodexQuotaService.parse(fixture, at = 4_242_000L)
        assertEquals(4_242_000L, q.fetchedAt)
        assertNotNull(q.limits.firstOrNull())
    }
}
