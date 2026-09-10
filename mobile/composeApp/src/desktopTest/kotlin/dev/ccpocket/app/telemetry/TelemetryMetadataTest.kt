package dev.ccpocket.app.telemetry

import dev.ccpocket.observability.Component
import dev.ccpocket.observability.Environment
import kotlin.test.*

class TelemetryMetadataTest {
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
}
