package dev.ccpocket.app.telemetry

import dev.ccpocket.observability.Component
import dev.ccpocket.observability.Environment
import kotlin.test.*

class TelemetryMetadataTest {
    @Test fun schemaUsesAnAlphanumericDimensionWithoutConvertingNumericMetrics() {
        for (platform in listOf(Component.ANDROID, Component.IOS, Component.DESKTOP)) {
            val result = TelemetryMetadata(platform, Environment.STAGING).prepare(
                TelEvent.SessionOpenResult,
                mapOf(TelKey.AnalyticsSchema to 1, TelKey.DurationMs to 1332L),
            )
            assertEquals("v1", assertIs<String>(result[TelKey.AnalyticsSchema]))
            assertEquals(1332L, assertIs<Long>(result[TelKey.DurationMs]))
        }
    }

    @Test fun productionDeclarationIsReportableAndExplicitInternalBuildWins() {
        assertEquals("0", TelemetryMetadata(Component.IOS, Environment.PRODUCTION)
            .prepare(TelEvent.AppLaunch, emptyMap())[TelKey.InternalTraffic])
        assertEquals("1", TelemetryMetadata(Component.DESKTOP, Environment.PRODUCTION, true)
            .prepare(TelEvent.AppLaunch, emptyMap())[TelKey.InternalTraffic])
    }
    @Test fun runtimeDimensionsCannotBeSpoofedByEventParameters() {
        val result = TelemetryMetadata(Component.IOS, Environment.STAGING).prepare(TelEvent.AppLaunch,
            mapOf(TelKey.Environment to "production", TelKey.InternalTraffic to "0", TelKey.AppPlatform to "desktop"))
        assertEquals("staging", result[TelKey.Environment])
        assertEquals("1", result[TelKey.InternalTraffic])
        assertEquals("ios", result[TelKey.AppPlatform])
        assertEquals("unknown", result[TelKey.UsageMode])
    }

    @Test fun demoAndUnknownContextAreNotSilentlyCountedAsRealUsage() {
        val metadata = TelemetryMetadata(Component.DESKTOP)
        assertEquals("demo", metadata.prepare(TelEvent.SessionOpened, mapOf(TelKey.Demo to 1))[TelKey.UsageMode])
        assertEquals("demo", metadata.prepare(TelEvent.DemoEntered, emptyMap())[TelKey.UsageMode])
        val result = metadata.prepare(TelEvent.AppLaunch, emptyMap())
        assertEquals("unknown", result[TelKey.Environment])
        assertEquals("unknown", result[TelKey.InternalTraffic])
        assertEquals("unknown", result[TelKey.UsageMode])
        assertEquals("real", metadata.prepare(TelEvent.SessionOpened, emptyMap())[TelKey.UsageMode])
    }

    @Test fun customToolNamesAreRemovedAndKnownToolsRetainTheirMeaning() {
        val metadata = TelemetryMetadata(Component.ANDROID)
        val secret = "mcp__PRIVATE_ORG__PRIVATE_PROJECT"
        assertEquals("other", metadata.prepare(TelEvent.ApprovalShown, mapOf(TelKey.Tool to secret))[TelKey.Tool])
        assertEquals("Read", metadata.prepare(TelEvent.ApprovalShown, mapOf(TelKey.Tool to "Read"))[TelKey.Tool])
    }

    // ---- issue #342: install channel + onboarding return visits ----

    @Test
    fun `install channel rides only the top-of-funnel events and never overrides a caller's source`() {
        val meta = TelemetryMetadata(Component.IOS, Environment.PRODUCTION, installChannel = "sandbox")
        for (event in listOf(TelEvent.AppLaunch, TelEvent.OnboardingShown, TelEvent.DemoEntered, TelEvent.DemoExited)) {
            assertEquals("sandbox", meta.prepare(event, emptyMap())[TelKey.Source], event.name)
        }
        assertEquals("qr", meta.prepare(TelEvent.PairStarted, mapOf(TelKey.Source to "qr"))[TelKey.Source])
        assertEquals(null, meta.prepare(TelEvent.Connected, emptyMap())[TelKey.Source])
        assertEquals("deeplink", meta.prepare(TelEvent.AppLaunch, mapOf(TelKey.Source to "deeplink"))[TelKey.Source])
        // a platform with no such signal sends nothing
        assertEquals(null, TelemetryMetadata(Component.ANDROID, Environment.PRODUCTION).prepare(TelEvent.AppLaunch, emptyMap())[TelKey.Source])
        // a review/TestFlight install is still production traffic: the split is this dimension, not internal_traffic
        assertEquals("0", meta.prepare(TelEvent.AppLaunch, emptyMap())[TelKey.InternalTraffic])
    }

    @Test
    fun `demo exit is demo usage and carries its depth`() {
        val out = TelemetryMetadata(Component.IOS, Environment.PRODUCTION).prepare(TelEvent.DemoExited, mapOf(TelKey.Value to "prompted"))
        assertEquals("demo", out[TelKey.UsageMode])
        assertEquals("prompted", out[TelKey.Value])
    }

    @Test
    fun `onboarding visits name the first showing, the return and every later one, and survive a broken store`() {
        var stored: String? = null
        val next = { OnboardingVisits.next(read = { stored }, write = { stored = it }) }
        assertEquals(listOf("first", "return", "return_many", "return_many"), listOf(next(), next(), next(), next()))
        assertEquals("3", stored)
        assertEquals("first", OnboardingVisits.next(read = { "garbage" }, write = {}))
        assertEquals("first", OnboardingVisits.next(read = { error("keychain locked") }, write = { error("keychain locked") }))
    }
}
