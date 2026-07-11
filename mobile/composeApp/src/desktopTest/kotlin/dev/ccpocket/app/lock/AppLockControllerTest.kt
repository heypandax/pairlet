package dev.ccpocket.app.lock

import dev.ccpocket.app.secure.SecureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A scriptable [Biometrics] — [result] is what the next authenticate() call resolves to. */
private class FakeBiometrics(
    var kindValue: BiometryKind = BiometryKind.FACE,
    var canAuth: Boolean = true,
    var result: AuthResult = AuthResult.Success,
) : Biometrics {
    var calls = 0
    override fun kind() = kindValue
    override fun canAuthenticate() = canAuth
    override suspend fun authenticate(reason: String, allowCredential: Boolean): AuthResult {
        calls++
        return result
    }
}

private class FakePrefs(
    override var enabled: Boolean = false,
    override var autoLock: AutoLockDelay = AutoLockDelay.IMMEDIATELY,
) : LockPrefs

/** Unconfined scope so the (non-suspending) fake auth completes inline — assertions read state right after. */
private fun controller(
    bio: FakeBiometrics = FakeBiometrics(),
    prefs: FakePrefs = FakePrefs(),
    clock: () -> Long = { 0L },
) = AppLockController(CoroutineScope(Dispatchers.Unconfined), bio, prefs, clock)

class AppLockControllerTest {
    @Test fun coldStartLockedWhenEnabled() {
        val c = controller(prefs = FakePrefs(enabled = true))
        assertTrue(c.enabled.value)
        assertTrue(c.locked.value)
    }

    @Test fun coldStartUnlockedWhenDisabled() {
        assertFalse(controller(prefs = FakePrefs(enabled = false)).locked.value)
    }

    @Test fun successUnlocks() {
        val c = controller(FakeBiometrics(result = AuthResult.Success), FakePrefs(enabled = true))
        c.authenticate("r")
        assertFalse(c.locked.value)
        assertEquals(GateVisual.IDLE, c.visual.value)
    }

    @Test fun failedOnceThenPasscodeFallback() {
        val c = controller(FakeBiometrics(result = AuthResult.Failed), FakePrefs(enabled = true))
        c.authenticate("r")
        assertEquals(GateVisual.FAILED, c.visual.value)
        assertTrue(c.locked.value)
        c.authenticate("r") // second soft failure promotes the passcode button (MAX_BIOMETRIC_ATTEMPTS)
        assertEquals(GateVisual.FALLBACK, c.visual.value)
        assertTrue(c.locked.value)
    }

    @Test fun biometryLockout() {
        val c = controller(FakeBiometrics(result = AuthResult.LockedOut), FakePrefs(enabled = true))
        c.authenticate("r")
        assertEquals(GateVisual.LOCKED_OUT, c.visual.value)
    }

    @Test fun unavailableSurfacesPasscode() {
        val c = controller(FakeBiometrics(result = AuthResult.Unavailable), FakePrefs(enabled = true))
        c.authenticate("r")
        assertEquals(GateVisual.FALLBACK, c.visual.value)
    }

    @Test fun cancelReturnsToIdleStaysLocked() {
        val c = controller(FakeBiometrics(result = AuthResult.Canceled), FakePrefs(enabled = true))
        c.authenticate("r")
        assertEquals(GateVisual.IDLE, c.visual.value)
        assertTrue(c.locked.value)
    }

    @Test fun devicePasscodeUnlocks() {
        val c = controller(FakeBiometrics(result = AuthResult.Success), FakePrefs(enabled = true))
        c.useDevicePasscode("r")
        assertFalse(c.locked.value)
    }

    @Test fun autoLockImmediatelyRelocksOnForeground() {
        val c = controller(FakeBiometrics(result = AuthResult.Success), FakePrefs(enabled = true, autoLock = AutoLockDelay.IMMEDIATELY))
        c.authenticate("r"); assertFalse(c.locked.value)
        c.onBackground()
        c.onForeground()
        assertTrue(c.locked.value)
    }

    @Test fun autoLockAfterOneMinuteHonorsDelay() {
        var t = 0L
        val c = controller(FakeBiometrics(result = AuthResult.Success), FakePrefs(enabled = true, autoLock = AutoLockDelay.AFTER_1_MIN), clock = { t })
        c.authenticate("r"); assertFalse(c.locked.value)
        t = 1_000; c.onBackground()
        t = 1_000 + 30_000; c.onForeground()
        assertFalse(c.locked.value)            // 30s < 1min → still unlocked
        c.onBackground()                        // background again (at t = 31_000)
        t = 31_000 + 61_000; c.onForeground()
        assertTrue(c.locked.value)             // >1min elapsed → re-locks
    }

    @Test fun coverShownWhenObscuredThenClearedOnForeground() {
        val c = controller(FakeBiometrics(result = AuthResult.Success), FakePrefs(enabled = true))
        c.authenticate("r"); assertFalse(c.locked.value)
        c.onWillObscure()
        assertTrue(c.covered.value)
        c.onForeground()
        assertFalse(c.covered.value)
    }

    @Test fun noCoverWhenDisabled() {
        val c = controller(prefs = FakePrefs(enabled = false))
        c.onWillObscure()
        assertFalse(c.covered.value)
    }

    @Test fun enableVerifiesThenPersists() {
        val prefs = FakePrefs(enabled = false)
        val c = controller(FakeBiometrics(result = AuthResult.Success), prefs)
        c.requestEnable("r")
        assertTrue(c.enabled.value)
        assertTrue(prefs.enabled)
        assertFalse(c.enabling.value)
    }

    @Test fun enableCancelStaysOff() {
        val prefs = FakePrefs(enabled = false)
        val c = controller(FakeBiometrics(result = AuthResult.Canceled), prefs)
        c.requestEnable("r")
        assertFalse(c.enabled.value)
        assertFalse(prefs.enabled)
    }

    @Test fun disablePersistsAndUnlocks() {
        val prefs = FakePrefs(enabled = true)
        val c = controller(prefs = prefs)
        assertTrue(c.locked.value)
        c.disable()
        assertFalse(c.enabled.value)
        assertFalse(prefs.enabled)
        assertFalse(c.locked.value)
    }

    @Test fun setAutoLockPersists() {
        val prefs = FakePrefs(autoLock = AutoLockDelay.IMMEDIATELY)
        val c = controller(prefs = prefs)
        c.setAutoLock(AutoLockDelay.AFTER_1_MIN)
        assertEquals(AutoLockDelay.AFTER_1_MIN, c.autoLock.value)
        assertEquals(AutoLockDelay.AFTER_1_MIN, prefs.autoLock)
    }

    @Test fun persistenceRoundTripAcrossControllers() {
        val prefs = FakePrefs(enabled = false)
        controller(FakeBiometrics(result = AuthResult.Success), prefs).requestEnable("r")
        val fresh = controller(prefs = prefs)   // a new controller reading the same prefs comes up enabled + locked
        assertTrue(fresh.enabled.value)
        assertTrue(fresh.locked.value)
    }

    @Test fun autoLockDelayParsingDefaultsToImmediately() {
        assertEquals(AutoLockDelay.IMMEDIATELY, AutoLockDelay.from(null))
        assertEquals(AutoLockDelay.IMMEDIATELY, AutoLockDelay.from("garbage"))
        assertEquals(AutoLockDelay.AFTER_1_MIN, AutoLockDelay.from("AFTER_1_MIN"))
        assertEquals(AutoLockDelay.IMMEDIATELY, AutoLockDelay.from("IMMEDIATELY"))
    }
}

/** The real [SecureStore]-backed prefs — proves the "1"/"0" + enum-name encoding round-trips. */
class SecureStoreLockPrefsTest {
    @Test fun roundTripsThroughSecureStore() {
        try {
            SecureStoreLockPrefs().apply { enabled = true; autoLock = AutoLockDelay.AFTER_1_MIN }
            assertTrue(SecureStoreLockPrefs().enabled)
            assertEquals(AutoLockDelay.AFTER_1_MIN, SecureStoreLockPrefs().autoLock)
            SecureStoreLockPrefs().enabled = false
            assertFalse(SecureStoreLockPrefs().enabled)
        } finally {
            SecureStore.remove(SecureStoreLockPrefs.K_ENABLED)
            SecureStore.remove(SecureStoreLockPrefs.K_AUTOLOCK)
        }
    }
}
