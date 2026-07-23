package dev.ccpocket.app.secure

import java.io.File
import java.util.Properties

// Desktop: a plain properties file under the home dir (dev/testing convenience).
actual object SecureStore {
    /**
     * Tests run the desktop actual too. Pointing them at the production file under `user.home` let repository/UI
     * tests overwrite the developer's real settings (most visibly, a final `context_window_override=200000`
     * made a 628k / 1M Opus session render as 316%). Gradle gives every Test task its own freshly-cleared file;
     * ordinary app launches do not set the property and keep the existing production path.
     */
    private val file = System.getProperty(TEST_FILE_PROPERTY)
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)
        ?: File(System.getProperty("user.home"), ".cc-pocket-app/store.properties")
    private val props = Properties().apply { if (file.exists()) file.inputStream().use(::load) }

    actual fun getString(key: String): String? = props.getProperty(key)
    actual fun putString(key: String, value: String) { props.setProperty(key, value); flush() }
    actual fun remove(key: String) { props.remove(key); flush() }

    private fun flush() {
        file.parentFile?.mkdirs()
        file.outputStream().use { props.store(it, "cc-pocket") }
    }

    private const val TEST_FILE_PROPERTY = "ccpocket.secureStore.file"
}
