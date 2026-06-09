package dev.ccpocket.app.secure

import java.io.File
import java.util.Properties

// Desktop: a plain properties file under the home dir (dev/testing convenience).
actual object SecureStore {
    private val file = File(System.getProperty("user.home"), ".cc-pocket-app/store.properties")
    private val props = Properties().apply { if (file.exists()) file.inputStream().use(::load) }

    actual fun getString(key: String): String? = props.getProperty(key)
    actual fun putString(key: String, value: String) { props.setProperty(key, value); flush() }
    actual fun remove(key: String) { props.remove(key); flush() }

    private fun flush() {
        file.parentFile?.mkdirs()
        file.outputStream().use { props.store(it, "cc-pocket") }
    }
}
