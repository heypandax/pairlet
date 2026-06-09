package dev.ccpocket.app.secure

import platform.Foundation.NSUserDefaults

// v1: NSUserDefaults (app-private). Production: move the private key to the iOS Keychain — see SecureStore.kt.
actual object SecureStore {
    private val d = NSUserDefaults.standardUserDefaults

    actual fun getString(key: String): String? = d.stringForKey(key)
    actual fun putString(key: String, value: String) { d.setObject(value, forKey = key) }
    actual fun remove(key: String) { d.removeObjectForKey(key) }
}
