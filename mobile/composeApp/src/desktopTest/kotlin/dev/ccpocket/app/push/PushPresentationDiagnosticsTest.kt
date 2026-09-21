package dev.ccpocket.app.push

import dev.ccpocket.observability.*
import kotlin.test.*

class PushPresentationDiagnosticsTest {
    @Test fun distinguishesSystemPresentationSettingsAndAppSuppression() {
        val records = mutableListOf<DiagnosticRecord>()
        Diagnostics.install(DiagnosticReporter(Component.IOS, Environment.STAGING, "ios@test",
            DiagnosticSink { records.add(it) }, successSamplePercent = 0))
        try {
            PushPresentationDiagnostics.settings(alert = 1, lockScreen = 2, center = 0, sound = 999)
            PushPresentationDiagnostics.foreground(false)
            PushPresentationDiagnostics.foreground(true)
            PushPresentationDiagnostics.opened()
            val settings = records.first().metrics
            assertEquals(NotificationSetting.DISABLED, settings.notificationAlert)
            assertEquals(NotificationSetting.ENABLED, settings.notificationLockScreen)
            assertEquals(NotificationSetting.UNSUPPORTED, settings.notificationCenter)
            assertEquals(NotificationSetting.UNKNOWN, settings.notificationSound)
            assertEquals(listOf(ErrorCode.SETTINGS_OBSERVED, ErrorCode.FOREGROUND_SUPPRESSED,
                ErrorCode.FOREGROUND_PRESENTED, ErrorCode.OPENED), records.map { it.code })
            assertTrue(records.all { it.stage == Stage.PUSH_PRESENTATION })
            assertEquals(1, records.map { it.traceId }.distinct().size)
        } finally { Diagnostics.install(null) }
    }
}
