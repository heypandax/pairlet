package dev.ccpocket.daemon.claude

import dev.ccpocket.daemon.claude.ClaudeQuotaService.HttpOutcome
import dev.ccpocket.daemon.claude.ClaudeQuotaService.QuotaCredential
import dev.ccpocket.protocol.CLAUDE_QUOTA_HTTP
import dev.ccpocket.protocol.CLAUDE_QUOTA_KIND_SESSION
import dev.ccpocket.protocol.CLAUDE_QUOTA_KIND_WEEKLY_ALL
import dev.ccpocket.protocol.CLAUDE_QUOTA_KIND_WEEKLY_SCOPED
import dev.ccpocket.protocol.CLAUDE_QUOTA_NETWORK
import dev.ccpocket.protocol.CLAUDE_QUOTA_NO_TOKEN
import dev.ccpocket.protocol.CLAUDE_QUOTA_OK
import dev.ccpocket.protocol.CLAUDE_QUOTA_SEVERITY_NORMAL
import dev.ccpocket.protocol.CLAUDE_QUOTA_SEVERITY_WARNING
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract for the subscription-allowance reader. NOTHING here touches the network or the keychain: the
 * credential and the transport are both constructor seams, so every branch — expired login, API-key
 * account, 401, offline, cache — is exercised deterministically.
 *
 * The payload fixture is a real capture's SHAPE (with the identifying values replaced): three `limits`
 * rows including a per-model `weekly_scoped` one, a `scope: null` on the unscoped rows, and unknown
 * experiment keys that must be ignored rather than fail the decode.
 */
class ClaudeQuotaServiceTest {

    /** A live-shaped 200 body: note `experiment_bucket` / `notice` — fields we do not model — plus the
     *  `scope: null` and `id: null` the real endpoint sends today. */
    private val fixture = """
        {
          "five_hour": { "utilization": 18.0, "resets_at": "2026-08-24T06:50:00.177220+00:00" },
          "seven_day": { "utilization": 55.0, "resets_at": "2026-08-24T08:00:00.177239+00:00" },
          "experiment_bucket": null,
          "notice": { "kind": "none", "body": null },
          "limits": [
            { "kind": "session", "group": "session", "percent": 18, "severity": "normal",
              "resets_at": "2026-08-24T06:50:00.177220+00:00", "scope": null, "is_active": false },
            { "kind": "weekly_all", "group": "weekly", "percent": 55, "severity": "normal",
              "resets_at": "2026-08-24T08:00:00.177239+00:00", "scope": null, "is_active": false },
            { "kind": "weekly_scoped", "group": "weekly", "percent": 81, "severity": "warning",
              "resets_at": "2026-08-24T08:00:00.177239+00:00",
              "scope": { "model": { "id": null, "display_name": "Fable" }, "surface": null },
              "is_active": true, "future_field": [1, 2, 3] }
          ]
        }
    """.trimIndent()

    // ── parsing ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun limits_are_the_source_of_truth_including_the_per_model_scoped_row() {
        val q = ClaudeQuotaService.parse(fixture, at = 1_000L)
        assertEquals(CLAUDE_QUOTA_OK, q.status)
        assertNull(q.error)
        assertEquals(1_000L, q.fetchedAt)
        assertEquals(3, q.limits.size, "all three limits rows survive — the scoped one is not collapsed")

        val session = q.limits[0]
        assertEquals(CLAUDE_QUOTA_KIND_SESSION, session.kind)
        assertEquals("session", session.group)
        assertEquals(18, session.percent)
        assertEquals(CLAUDE_QUOTA_SEVERITY_NORMAL, session.severity)
        assertEquals(false, session.isActive)
        assertNull(session.modelDisplayName, "an unscoped row carries no model name (scope was null)")

        assertEquals(CLAUDE_QUOTA_KIND_WEEKLY_ALL, q.limits[1].kind)
        assertEquals(55, q.limits[1].percent)

        val scoped = q.limits[2]
        assertEquals(CLAUDE_QUOTA_KIND_WEEKLY_SCOPED, scoped.kind)
        assertEquals(81, scoped.percent)
        assertEquals(CLAUDE_QUOTA_SEVERITY_WARNING, scoped.severity)
        assertTrue(scoped.isActive)
        assertEquals("Fable", scoped.modelDisplayName)
    }

    @Test
    fun resets_at_becomes_epoch_millis_and_an_unparseable_one_becomes_null() {
        val q = ClaudeQuotaService.parse(fixture, at = 0)
        // 2026-08-24T06:50:00.177220+00:00
        assertEquals(1787554200177L, q.limits[0].resetsAt)
        // the client must show NO countdown rather than a wrong one
        assertNull(ClaudeQuotaService.epochMs(null))
        assertNull(ClaudeQuotaService.epochMs(""))
        assertNull(ClaudeQuotaService.epochMs("next tuesday"))
    }

    @Test
    fun a_payload_without_limits_falls_back_to_the_flat_five_hour_seven_day_pair() {
        val flat = """{"five_hour":{"utilization":18.0,"resets_at":"2026-08-24T06:50:00.177220+00:00"},
                       "seven_day":{"utilization":55.4,"resets_at":"2026-08-24T08:00:00+00:00"}}"""
        val q = ClaudeQuotaService.parse(flat, at = 5)
        assertEquals(CLAUDE_QUOTA_OK, q.status)
        assertEquals(listOf(CLAUDE_QUOTA_KIND_SESSION, CLAUDE_QUOTA_KIND_WEEKLY_ALL), q.limits.map { it.kind })
        assertEquals(18, q.limits[0].percent)
        assertEquals(55, q.limits[1].percent, "utilization rounds to a whole percent")
    }

    @Test
    fun the_flat_fallback_never_double_counts_a_payload_that_has_both() {
        // the flat pair IS the first two limits rows flattened — synthesizing them again would show the
        // 5-hour window twice
        assertEquals(3, ClaudeQuotaService.parse(fixture, at = 0).limits.size)
    }

    @Test
    fun an_unrecognized_body_is_an_http_error_not_a_zero_allowance() {
        // the dangerous failure would be "parsed fine, everything is 0% used"
        val q = ClaudeQuotaService.parse("<html>gateway</html>", at = 0)
        assertEquals(CLAUDE_QUOTA_HTTP, q.status)
        assertTrue(q.limits.isEmpty())
        assertNotNull(q.error)
    }

    @Test
    fun percentages_are_clamped_and_missing_severity_degrades_to_normal() {
        val odd = """{"limits":[{"kind":"session","group":"session","percent":140},
                                {"kind":"weekly_all","group":"weekly","percent":-4,"severity":null,"is_active":null}]}"""
        val q = ClaudeQuotaService.parse(odd, at = 0)
        assertEquals(100, q.limits[0].percent)
        assertEquals(0, q.limits[1].percent)
        assertEquals(CLAUDE_QUOTA_SEVERITY_NORMAL, q.limits[1].severity)
        assertEquals(false, q.limits[1].isActive, "an explicit null is_active coerces to the default")
    }

    // ── credential store ───────────────────────────────────────────────────────────────────────────

    @Test
    fun a_credential_blob_yields_the_token_only_while_it_is_unexpired() {
        val blob = """{"claudeAiOauth":{"accessToken":"tok-abc","expiresAt":2000,"scopes":["user:inference"]}}"""
        assertEquals(
            QuotaCredential.Present("tok-abc"),
            ClaudeQuotaService.parseCredential(blob, now = 1000),
        )
        assertEquals(QuotaCredential.Expired, ClaudeQuotaService.parseCredential(blob, now = 2000))
        assertEquals(QuotaCredential.Expired, ClaudeQuotaService.parseCredential(blob, now = 9999))
    }

    @Test
    fun an_api_key_account_or_a_junk_store_reads_as_missing_never_as_an_error() {
        // no claudeAiOauth object at all == the API-key case: nothing to report, not a failure
        assertEquals(QuotaCredential.Missing, ClaudeQuotaService.parseCredential("""{"someOtherKey":1}""", now = 0))
        assertEquals(QuotaCredential.Missing, ClaudeQuotaService.parseCredential("""{"claudeAiOauth":{}}""", now = 0))
        assertEquals(QuotaCredential.Missing, ClaudeQuotaService.parseCredential("""{"claudeAiOauth":{"accessToken":""}}""", now = 0))
        assertEquals(QuotaCredential.Missing, ClaudeQuotaService.parseCredential("not json", now = 0))
    }

    @Test
    fun a_credential_without_an_expiry_is_treated_as_usable() {
        // absence of expiresAt is not evidence of expiry; a stale token still degrades correctly via 401
        assertEquals(
            QuotaCredential.Present("tok"),
            ClaudeQuotaService.parseCredential("""{"claudeAiOauth":{"accessToken":"tok"}}""", now = 9_999_999),
        )
    }

    @Test
    fun the_first_unexpired_candidate_wins_and_all_expired_reports_expired() {
        val expired = """{"claudeAiOauth":{"accessToken":"old","expiresAt":10}}"""
        val live = """{"claudeAiOauth":{"accessToken":"new","expiresAt":9999}}"""
        // keychain first on macOS
        assertEquals(
            QuotaCredential.Present("new"),
            ClaudeQuotaService.readCredential(isMac = true, files = emptyList(), keychain = { live }, now = 100),
        )
        // an expired keychain item does not shadow a live file
        val f = java.io.File.createTempFile("creds", ".json").apply { deleteOnExit(); writeText(live) }
        assertEquals(
            QuotaCredential.Present("new"),
            ClaudeQuotaService.readCredential(isMac = true, files = listOf(f), keychain = { expired }, now = 100),
        )
        // everything expired → Expired (a distinct state from "never signed in")
        val g = java.io.File.createTempFile("creds", ".json").apply { deleteOnExit(); writeText(expired) }
        assertEquals(
            QuotaCredential.Expired,
            ClaudeQuotaService.readCredential(isMac = true, files = listOf(g), keychain = { expired }, now = 100),
        )
        // nothing at all → Missing; and the keychain is NOT consulted off macOS
        assertEquals(
            QuotaCredential.Missing,
            ClaudeQuotaService.readCredential(isMac = false, files = emptyList(), keychain = { error("must not be called") }, now = 100),
        )
    }

    // ── service behaviour ──────────────────────────────────────────────────────────────────────────

    @Test
    fun a_missing_credential_answers_no_token_but_an_expired_one_is_only_transient() = runBlocking {
        // Missing = never signed in / API-key machine → authoritative NO_TOKEN (client hides the strip).
        // Expired = the ~8h OAuth token lapsed between claude runs → TRANSIENT (client keeps its aging
        // snapshot); mapping it to NO_TOKEN made the strip vanish every night (QuotaRoute log, 08-24→27).
        // Neither state may burn a doomed request on Anthropic.
        for ((cred, want) in listOf(
            QuotaCredential.Missing to CLAUDE_QUOTA_NO_TOKEN,
            QuotaCredential.Expired to dev.ccpocket.protocol.CLAUDE_QUOTA_NETWORK,
        )) {
            val calls = AtomicInteger()
            val svc = ClaudeQuotaService(
                credentials = { cred },
                transport = { calls.incrementAndGet(); HttpOutcome.Body(fixture) },
            )
            val q = svc.get()
            assertEquals(want, q.status, "for $cred")
            assertTrue(q.limits.isEmpty())
            assertEquals(0, calls.get(), "a token-less machine must not call Anthropic at all")
        }
    }

    @Test
    fun a_non_2xx_surfaces_the_code_and_a_transport_failure_surfaces_as_network() = runBlocking {
        val http = ClaudeQuotaService(
            credentials = { QuotaCredential.Present("tok") },
            transport = { HttpOutcome.Status(401) },
        ).get()
        assertEquals(CLAUDE_QUOTA_HTTP, http.status)
        assertTrue(http.error!!.contains("401"))

        val net = ClaudeQuotaService(
            credentials = { QuotaCredential.Present("tok") },
            transport = { HttpOutcome.Failure("could not reach Anthropic (ConnectException)") },
        ).get()
        assertEquals(CLAUDE_QUOTA_NETWORK, net.status)
        assertTrue(net.limits.isEmpty())
    }

    @Test
    fun a_throwing_transport_degrades_instead_of_propagating() = runBlocking {
        val q = ClaudeQuotaService(
            credentials = { QuotaCredential.Present("tok") },
            transport = { error("boom") },
        ).get()
        assertEquals(CLAUDE_QUOTA_NETWORK, q.status)
    }

    @Test
    fun the_token_never_appears_in_the_reply() = runBlocking {
        val secret = "sk-ant-oat01-NEVER-SHOW-ME"
        val q = ClaudeQuotaService(
            credentials = { QuotaCredential.Present(secret) },
            transport = { HttpOutcome.Status(500) },
        ).get()
        assertTrue(secret !in (q.error ?: ""), "the error line must never quote the credential")
    }

    @Test
    fun a_success_is_cached_for_a_minute_and_forceRefresh_bypasses_it() = runBlocking {
        val calls = AtomicInteger()
        var clock = 0L
        val svc = ClaudeQuotaService(
            credentials = { QuotaCredential.Present("tok") },
            now = { clock },
            transport = { calls.incrementAndGet(); HttpOutcome.Body(fixture) },
        )
        assertEquals(CLAUDE_QUOTA_OK, svc.get().status)
        assertEquals(1, calls.get())
        clock = 30_000
        svc.get(); assertEquals(1, calls.get(), "inside the OK TTL the cached snapshot answers")
        svc.get(forceRefresh = true); assertEquals(2, calls.get(), "an explicit refresh always goes out")
        clock = 30_000 + ClaudeQuotaService.OK_TTL_MS + 1
        svc.get(); assertEquals(3, calls.get(), "past the TTL it re-fetches")
    }

    @Test
    fun a_failure_is_cached_only_briefly_so_reconnecting_recovers_fast() = runBlocking {
        val calls = AtomicInteger()
        var clock = 0L
        val svc = ClaudeQuotaService(
            credentials = { QuotaCredential.Present("tok") },
            now = { clock },
            transport = { calls.incrementAndGet(); HttpOutcome.Failure("offline") },
        )
        svc.get(); assertEquals(1, calls.get())
        clock = ClaudeQuotaService.FAIL_TTL_MS - 1
        svc.get(); assertEquals(1, calls.get())
        // the failure TTL is strictly shorter than the success one — that asymmetry is the point
        assertTrue(ClaudeQuotaService.FAIL_TTL_MS < ClaudeQuotaService.OK_TTL_MS)
        clock = ClaudeQuotaService.FAIL_TTL_MS + 1
        svc.get(); assertEquals(2, calls.get())
    }

    @Test
    fun a_cached_reply_keeps_the_original_fetch_moment() = runBlocking {
        var clock = 7_000L
        val svc = ClaudeQuotaService(
            credentials = { QuotaCredential.Present("tok") },
            now = { clock },
            transport = { HttpOutcome.Body(fixture) },
        )
        val first = svc.get()
        assertEquals(7_000L, first.fetchedAt)
        clock = 40_000
        // the numbers are as old as their fetch, not as young as the request that re-read the cache
        assertEquals(7_000L, svc.get().fetchedAt)
    }
}
