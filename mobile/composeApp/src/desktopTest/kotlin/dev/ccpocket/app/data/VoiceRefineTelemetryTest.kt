package dev.ccpocket.app.data

import dev.ccpocket.app.telemetry.TelEvent
import dev.ccpocket.app.telemetry.TelKey
import dev.ccpocket.observability.AnalyticsCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `voice_refine` carries fixed vocabularies only (docs/observability/EVENT-CATALOG.md §9): no text, no ids, no raw
 * numbers, and every value inside what the analytics ingress accepts.
 */
class VoiceRefineTelemetryTest {

    @Test fun theEventAndItsKeysAreWhitelisted() {
        assertTrue(TelEvent.VoiceRefine.id in AnalyticsCatalog.events)
        for (key in listOf(TelKey.Outcome, TelKey.Edits, TelKey.LatencyMs)) assertTrue(key.id in AnalyticsCatalog.params, key.id)
    }

    @Test fun everyValueIsShortAndIngressSafe() {
        val values = VoiceRefineTelemetry.OUTCOMES + VoiceRefineTelemetry.EDITS + VoiceRefineTelemetry.LATENCIES
        for (v in values) {
            assertTrue(AnalyticsCatalog.stringValue.matches(v) && v.length <= AnalyticsCatalog.MAX_STRING_LENGTH, v)
        }
        assertEquals(
            setOf(
                "requested", "auto_sent", "to_composer_timeout", "to_composer_not_adopted", "to_composer_unavailable",
                "to_composer_review", "to_composer_disconnected", "to_composer_not_sent", "to_composer_edit", "discarded",
            ),
            VoiceRefineTelemetry.OUTCOMES.toSet(),
        )
        assertEquals(VoiceRefineTelemetry.OUTCOMES.size, VoiceRefineTelemetry.OUTCOMES.toSet().size, "one name per outcome")
    }

    @Test fun bucketsCoverEveryValue() {
        assertEquals(listOf("0", "0", "1", "2-3", "2-3", "4+", "4+"), listOf(-1, 0, 1, 2, 3, 4, 12).map(VoiceRefineTelemetry::editsBucket))
        assertEquals(
            listOf("0-2999", "0-2999", "3000-5999", "3000-5999", "6000-9999", "6000-9999", "10000+", "10000+"),
            listOf(0L, 2_999L, 3_000L, 5_999L, 6_000L, 9_999L, 10_000L, 40_000L).map(VoiceRefineTelemetry::latencyBucket),
        )
        val produced = (0..20).map(VoiceRefineTelemetry::editsBucket).toSet()
        assertEquals(VoiceRefineTelemetry.EDITS.toSet(), produced)
    }
}
