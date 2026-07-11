package dev.ccpocket.app.lock

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.LocalAuthentication.LABiometryTypeFaceID
import platform.LocalAuthentication.LABiometryTypeTouchID
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics
import kotlin.coroutines.resume

// LAError codes (LAErrorDomain) — stable ObjC ABI values, compared against NSError.code.
private const val LA_ERROR_AUTHENTICATION_FAILED = -1L
private const val LA_ERROR_USER_CANCEL = -2L
private const val LA_ERROR_USER_FALLBACK = -3L
private const val LA_ERROR_SYSTEM_CANCEL = -4L
private const val LA_ERROR_PASSCODE_NOT_SET = -5L
private const val LA_ERROR_BIOMETRY_NOT_AVAILABLE = -6L
private const val LA_ERROR_BIOMETRY_NOT_ENROLLED = -7L
private const val LA_ERROR_BIOMETRY_LOCKOUT = -8L
private const val LA_ERROR_APP_CANCEL = -9L

actual fun createBiometrics(): Biometrics = IosBiometrics()

@OptIn(ExperimentalForeignApi::class)
private class IosBiometrics : Biometrics {
    override fun kind(): BiometryKind {
        val ctx = LAContext()
        if (!ctx.canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, null)) return BiometryKind.NONE
        return when (ctx.biometryType) {
            LABiometryTypeFaceID -> BiometryKind.FACE
            LABiometryTypeTouchID -> BiometryKind.TOUCH
            else -> BiometryKind.NONE
        }
    }

    override fun canAuthenticate(): Boolean =
        LAContext().canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, null)

    override suspend fun authenticate(reason: String, allowCredential: Boolean): AuthResult =
        suspendCancellableCoroutine { cont ->
            val ctx = LAContext()
            // biometrics-only: hide the OS "Enter Password" button so our gate owns the passcode path
            if (!allowCredential) ctx.localizedFallbackTitle = ""
            val policy = if (allowCredential) LAPolicyDeviceOwnerAuthentication else LAPolicyDeviceOwnerAuthenticationWithBiometrics
            ctx.evaluatePolicy(policy, reason) { success, error ->
                val r = when {
                    success -> AuthResult.Success
                    else -> when (error?.code) {
                        LA_ERROR_BIOMETRY_LOCKOUT -> AuthResult.LockedOut
                        LA_ERROR_USER_CANCEL, LA_ERROR_SYSTEM_CANCEL, LA_ERROR_APP_CANCEL, LA_ERROR_USER_FALLBACK -> AuthResult.Canceled
                        LA_ERROR_BIOMETRY_NOT_AVAILABLE, LA_ERROR_BIOMETRY_NOT_ENROLLED, LA_ERROR_PASSCODE_NOT_SET -> AuthResult.Unavailable
                        LA_ERROR_AUTHENTICATION_FAILED -> AuthResult.Failed
                        else -> AuthResult.Failed
                    }
                }
                if (cont.isActive) cont.resume(r)
            }
        }
}
