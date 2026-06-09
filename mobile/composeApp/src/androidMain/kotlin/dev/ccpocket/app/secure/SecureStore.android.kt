package dev.ccpocket.app.secure

import android.content.Context
import android.content.SharedPreferences

/** Call once from Application/Activity onCreate before any SecureStore use. */
fun initSecureStore(context: Context) {
    AndroidSecureStore.prefs = context.applicationContext.getSharedPreferences("cc-pocket", Context.MODE_PRIVATE)
}

internal object AndroidSecureStore {
    lateinit var prefs: SharedPreferences
}

actual object SecureStore {
    actual fun getString(key: String): String? = AndroidSecureStore.prefs.getString(key, null)
    actual fun putString(key: String, value: String) { AndroidSecureStore.prefs.edit().putString(key, value).apply() }
    actual fun remove(key: String) { AndroidSecureStore.prefs.edit().remove(key).apply() }
}
