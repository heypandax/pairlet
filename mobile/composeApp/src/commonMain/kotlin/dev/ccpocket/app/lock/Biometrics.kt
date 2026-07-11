package dev.ccpocket.app.lock

/**
 * The platform biometric seam for the App Lock gate (issue #109). iOS = LocalAuthentication (Face ID /
 * Touch ID with device-passcode fallback), Android = androidx.biometric BiometricPrompt, desktop = a stub
 * (no biometrics — the gate is never shown there). Kept tiny and injectable so [AppLockController] can be
 * unit-tested against a fake without any platform framework.
 */
interface Biometrics {
    /** What the device offers, for kind-aware copy ("Face ID" / "Touch ID" / "Fingerprint" / generic). */
    fun kind(): BiometryKind

    /** Whether the user can authenticate at all right now (a biometric is enrolled, or a device passcode
     *  is set) — gates whether "Require Face ID" can even be switched on. */
    fun canAuthenticate(): Boolean

    /**
     * Present the OS prompt and suspend until it resolves. [allowCredential] = true asks the deviceOwner
     * (biometric OR passcode) policy — the explicit "Enter passcode" path; false is biometrics-only, which
     * yields the retry / lockout states the gate distinguishes. [reason] is shown in the OS sheet.
     */
    suspend fun authenticate(reason: String, allowCredential: Boolean): AuthResult
}

/** The biometric hardware kind, chosen at runtime so the gate copy + glyph name matches the device. */
enum class BiometryKind { FACE, TOUCH, FINGERPRINT, GENERIC, NONE }

/** The outcome of one [Biometrics.authenticate] call, normalized across the two platforms' error models. */
sealed interface AuthResult {
    /** Identity confirmed (biometric matched, or passcode entered) — unlock. */
    data object Success : AuthResult
    /** A soft biometric mismatch with retries remaining — the gentle "Try again" state. */
    data object Failed : AuthResult
    /** The OS disabled biometrics after too many failures — only the device passcode can re-enable it. */
    data object LockedOut : AuthResult
    /** The user dismissed the prompt (cancel / fallback button) — stay on the gate, no error alarm. */
    data object Canceled : AuthResult
    /** No biometric enrolled / hardware absent / passcode not set — surface the passcode path. */
    data object Unavailable : AuthResult
}

/** Platform factory. Android reads the FragmentActivity wired by initAppLock(); iOS builds an LAContext;
 *  desktop returns a no-op stub. Called lazily (see PocketRepository.appLock) so it never runs in the
 *  desktop app or repo unit tests. */
expect fun createBiometrics(): Biometrics
