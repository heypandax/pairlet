package dev.ccpocket.app.lock

import android.content.pm.PackageManager
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

// The prompt must be hosted by a FragmentActivity — wired once from MainActivity (mirrors initSecureStore).
private var activityRef: WeakReference<FragmentActivity>? = null

/** Wire the biometric prompt host. Call from MainActivity.onCreate. */
fun initAppLock(activity: FragmentActivity) { activityRef = WeakReference(activity) }

actual fun createBiometrics(): Biometrics = AndroidBiometrics()

private class AndroidBiometrics : Biometrics {
    private val activity: FragmentActivity? get() = activityRef?.get()

    override fun kind(): BiometryKind {
        val pm = activity?.packageManager ?: return BiometryKind.NONE
        return when {
            pm.hasSystemFeature(PackageManager.FEATURE_FACE) -> BiometryKind.FACE
            pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT) -> BiometryKind.FINGERPRINT
            pm.hasSystemFeature(PackageManager.FEATURE_IRIS) -> BiometryKind.GENERIC
            else -> BiometryKind.GENERIC
        }
    }

    override fun canAuthenticate(): Boolean {
        val a = activity ?: return false
        val allowed = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        return BiometricManager.from(a).canAuthenticate(allowed) == BiometricManager.BIOMETRIC_SUCCESS
    }

    override suspend fun authenticate(reason: String, allowCredential: Boolean): AuthResult {
        val a = activity ?: return AuthResult.Unavailable
        return suspendCancellableCoroutine { cont ->
            val builder = BiometricPrompt.PromptInfo.Builder()
                .setTitle("cc-pocket")
                .setSubtitle(reason)
            if (allowCredential) {
                // biometric OR device passcode — the explicit fallback path
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    builder.setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    builder.setDeviceCredentialAllowed(true) // pre-30: combined authenticators unsupported
                }
            } else {
                builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                builder.setNegativeButtonText(a.getString(android.R.string.cancel)) // our gate owns the passcode path
            }
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (cont.isActive) cont.resume(AuthResult.Success)
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val r = when (errorCode) {
                        BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> AuthResult.LockedOut
                        BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_CANCELED -> AuthResult.Canceled
                        BiometricPrompt.ERROR_NO_BIOMETRICS, BiometricPrompt.ERROR_HW_NOT_PRESENT,
                        BiometricPrompt.ERROR_HW_UNAVAILABLE, BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL -> AuthResult.Unavailable
                        else -> AuthResult.Failed
                    }
                    if (cont.isActive) cont.resume(r)
                }
                // onAuthenticationFailed = one non-match; the prompt stays up for a retry, so we do NOT resume.
            }
            try {
                BiometricPrompt(a, ContextCompat.getMainExecutor(a), callback).authenticate(builder.build())
            } catch (t: Throwable) {
                if (cont.isActive) cont.resume(AuthResult.Unavailable)
            }
        }
    }
}
