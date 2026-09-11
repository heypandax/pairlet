package dev.ccpocket.app.telemetry

import dev.ccpocket.observability.Environment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.net.UnknownHostException
import kotlin.test.*

class Ga4TransportProbeTest {
    @Test fun productionAndUnknownNeverEnableDebugCollection() {
        assertFalse(ga4ProbeEnabled("1", Environment.PRODUCTION))
        assertFalse(ga4ProbeEnabled("1", null))
        assertFalse(ga4ProbeEnabled(null, Environment.STAGING))
        assertTrue(ga4ProbeEnabled("1", Environment.STAGING))
        assertTrue(ga4ProbeEnabled("1", Environment.DEVELOPMENT))
    }

    @Test fun responseOnlyDescribesTransportAndSinkFailureDoesNotResend() = runTest {
        val lines = mutableListOf<String>()
        val probe = Ga4TransportProbe(lines::add)
        for (status in listOf(204, 429, 503)) probe.send { status }
        assertEquals(listOf("[ga4-transport] attempt", "[ga4-transport] no_content status=204",
            "[ga4-transport] attempt", "[ga4-transport] rate_limited status=429",
            "[ga4-transport] attempt", "[ga4-transport] http_5xx status=503"), lines)
        var requests = 0
        Ga4TransportProbe { error("SINK_PRIVATE_VALUE") }.send { requests++; 204 }
        assertEquals(1, requests)
    }

    @Test fun wrappedFailureKeepsOriginalFailureWithoutDisclosingCauseOrSecret() = runTest {
        val lines = mutableListOf<String>()
        val failure = IllegalStateException("api_secret=PRIVATE", UnknownHostException("PRIVATE_HOST"))
        val caught = assertFailsWith<IllegalStateException> {
            Ga4TransportProbe(lines::add).send { throw failure }
        }
        assertSame(failure, caught)
        assertEquals(listOf("[ga4-transport] attempt", "[ga4-transport] dns_failed"), lines)
    }

    @Test fun consentCancellationIsNotReportedAsNetworkFailure() = runTest {
        val lines = mutableListOf<String>()
        val cancellation = CancellationException("PRIVATE_VALUE")
        assertSame(cancellation, assertFailsWith<CancellationException> {
            Ga4TransportProbe(lines::add).send { throw cancellation }
        })
        assertEquals(listOf("[ga4-transport] attempt", "[ga4-transport] cancelled"), lines)
    }
}
