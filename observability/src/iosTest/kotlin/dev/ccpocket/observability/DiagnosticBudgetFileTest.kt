package dev.ccpocket.observability

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.*

@OptIn(ExperimentalForeignApi::class)
class DiagnosticBudgetFileTest {
    @Test fun nativeAtomicFileSurvivesBudgetReplacement() {
        val dir = NSTemporaryDirectory() + "budget-test-" + NSUUID().UUIDString
        try {
            fun fresh() = DiagnosticBudget(Component.IOS,
                diagnosticBudgetStore(Component.IOS, Environment.STAGING, dir))
            repeat(10) { assertTrue(fresh().admit(DiagnosticKind.ERROR, 100)) }
            assertFalse(fresh().admit(DiagnosticKind.ERROR, 100))
            assertTrue(fresh().admit(DiagnosticKind.LOG, 100))
        } finally { NSFileManager.defaultManager.removeItemAtPath(dir, null) }
    }
}
