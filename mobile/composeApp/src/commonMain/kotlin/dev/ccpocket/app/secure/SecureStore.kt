package dev.ccpocket.app.secure

/**
 * Small persistent key/value store for the device's long-term secrets (its E2E private key, the
 * paired-daemon record). Backed by the platform's secure store where available.
 *
 * NOTE (hardening): the iOS/Android actuals here use NSUserDefaults / SharedPreferences for v1, which
 * are app-private but NOT hardware-backed. Production should move the private key to the iOS Keychain
 * (Security.framework) and Android Keystore/EncryptedSharedPreferences — a localized change behind
 * this same interface. See docs/SECURITY.md.
 */
expect object SecureStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}
