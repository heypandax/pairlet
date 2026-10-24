package dev.ccpocket.daemon.codex

import dev.ccpocket.protocol.RedeemQuotaReset
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.QuotaResetResult
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.daemon.bridge.BridgeCaps
import kotlinx.serialization.encodeToString
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CodexQuotaResetTest {
    @Test
    fun quota_request_identifiers_roundtrip_but_remain_optional_for_old_peers() {
        val request = dev.ccpocket.protocol.ClaudeQuotaGet(agent = dev.ccpocket.protocol.AgentKind.CODEX, requestId = "read-1")
        val envelope = Envelope(id = "quota", ts = 0, body = request)
        assertEquals(envelope, PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(envelope)))
        val legacy = PocketJson.decodeFromString<dev.ccpocket.protocol.ClaudeQuotaGet>("""{"agent":"codex"}""")
        assertNull(legacy.requestId)
    }

    @Test
    fun reset_frames_roundtrip_and_are_refused_for_bridges() {
        val request = RedeemQuotaReset("retry-key", "card-1")
        val result = QuotaResetResult(request.requestId, "alreadyRedeemed")
        for (frame in listOf(request, result)) {
            val envelope = Envelope(id = "reset", ts = 0, body = frame)
            assertEquals(envelope, PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(envelope)))
        }
        assertFalse(BridgeCaps.ingressAllowed(request))
        assertFalse(BridgeCaps.egressAllowed(result))
    }

    @Test
    fun reset_cards_keep_identity_status_and_expiry_in_milliseconds() {
        val reading = CodexQuotaService.parse("""{
            "rateLimits": {}, "rateLimitsByLimitId": null,
            "rateLimitResetCredits": {"availableCount": 1, "credits": [{
                "id": "card-1", "status": "available", "resetType": "codexRateLimits",
                "grantedAt": 1700000000, "expiresAt": 1800000000, "title": "Saved reset"
            }]}
        }""", 1000)
        assertEquals(1L, reading.resetCredits?.availableCount)
        val card = reading.resetCredits!!.credits!!.single()
        assertEquals("card-1", card.id)
        assertEquals(1800000000000L, card.expiresAt)
        assertNull(CodexQuotaService.parse("""{"rateLimits":{}}""", 1000).resetCredits)
    }

    @Test
    fun redemption_invalidates_cached_usage_and_reuses_the_callers_key() = runBlocking {
        var reads = 0
        val keys = mutableListOf<String>()
        val service = CodexQuotaService(
            binary = { Path.of("/fake/codex") },
            transport = { reads++; CodexQuotaService.AppServerOutcome.Result("""{"rateLimits":{}}""") },
            resetTransport = { _, request ->
                keys += request.requestId
                CodexQuotaService.AppServerOutcome.Result("""{"outcome":"alreadyRedeemed"}""")
            },
        )
        service.get()
        service.get()
        assertEquals(1, reads)
        val request = RedeemQuotaReset("retry-the-same-attempt", "card-1")
        assertEquals("alreadyRedeemed", service.redeem(request).outcome)
        assertEquals("alreadyRedeemed", service.redeem(request).outcome)
        assertEquals(listOf(request.requestId, request.requestId), keys)
        service.get()
        assertEquals(2, reads)
    }

    @Test
    fun uncertain_exchange_is_never_reported_as_success() = runBlocking {
        val service = CodexQuotaService(binary = { Path.of("/fake/codex") }, resetTransport = { _, _ ->
            CodexQuotaService.AppServerOutcome.Failure("timeout")
        })
        assertEquals("unknown", service.redeem(RedeemQuotaReset("attempt-1")).outcome)
    }
}
