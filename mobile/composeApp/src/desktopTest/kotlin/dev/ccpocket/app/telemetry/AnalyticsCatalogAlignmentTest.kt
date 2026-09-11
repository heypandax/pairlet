package dev.ccpocket.app.telemetry

import dev.ccpocket.observability.AnalyticsCatalog
import kotlin.test.*

/**
 * The ingress validates against [AnalyticsCatalog] (DESKTOP-GA4-INGRESS.md §4). An event or key added on only
 * one side is silently dropped at the server, which is exactly the kind of loss nobody notices — so drift on
 * EITHER side fails here rather than in a report weeks later.
 */
class AnalyticsCatalogAlignmentTest {
    @Test fun everyEventIdIsWhitelistedAndNothingExtraIs() {
        assertEquals(AnalyticsCatalog.events, TelEvent.entries.map { it.id }.toSet())
    }

    @Test fun everyParameterKeyIsWhitelistedAndNothingExtraIs() {
        assertEquals(AnalyticsCatalog.params, TelKey.entries.map { it.id }.toSet())
    }
}
