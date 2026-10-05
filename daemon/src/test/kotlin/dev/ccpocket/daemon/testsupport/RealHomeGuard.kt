package dev.ccpocket.daemon.testsupport

import dev.ccpocket.daemon.bridge.ExecutionCredentialStore
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.identity.PairedDevices
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import java.io.File
import java.nio.file.Path

/**
 * Fails a test before it runs when the daemon's state directory would resolve into the developer's REAL home.
 *
 * Every daemon store derives its default file from `user.home` — through [Identity.defaultPath] (prefs,
 * schedules, presets, approval history, handoffs, pins, …) or directly (`devices.json`, the credential
 * stores). The daemon's Gradle test task points `user.home` at `build/test-home`; this guard is what keeps
 * that true: a test JVM started any other way (an IDE run that bypasses the Gradle config, a shell that
 * exports `CC_POCKET_IDENTITY`, a test that repoints `user.home` and forgets to restore it) fails here
 * instead of reading or rewriting the owner's `~/.cc-pocket`.
 *
 * The real home is read from the OS environment (`HOME`, or `USERPROFILE` on Windows), which the Gradle
 * config deliberately leaves alone: it is the one witness the redirected `user.home` cannot hide.
 *
 * Registered for every test class through JUnit's extension auto-detection
 * (`META-INF/services/org.junit.jupiter.api.extension.Extension` + `junit-platform.properties`).
 */
class RealHomeGuard : BeforeAllCallback, BeforeEachCallback {
    override fun beforeAll(context: ExtensionContext) = check()

    override fun beforeEach(context: ExtensionContext) = check()

    private fun check() {
        val problems = violations()
        if (problems.isNotEmpty()) {
            throw IllegalStateException(
                "test JVM would touch the real daemon state — run daemon tests through Gradle " +
                    "(:daemon:test sets user.home to build/test-home) and unset CC_POCKET_IDENTITY:\n  " +
                    problems.joinToString("\n  "),
            )
        }
    }

    companion object {
        /** The OS-level home(s) of the user running the tests — never the redirected `user.home`. */
        fun realHomes(env: (String) -> String? = System::getenv): Set<Path> =
            listOfNotNull(env("HOME"), env("USERPROFILE"))
                .filter { it.isNotBlank() }
                .map { normalize(File(it)) }
                .toSet()

        /** Every way the daemon locates its state directory, resolved right now. */
        fun stateDirs(): List<Pair<String, File>> = listOf(
            "user.home" to File(System.getProperty("user.home")),
            "Identity.defaultPath()" to Identity.defaultPath().absoluteFile.parentFile,
            "PairedDevices.file()" to PairedDevices.file().absoluteFile.parentFile,
            "credential stores" to ExecutionCredentialStore.file().absoluteFile.parentFile,
        )

        fun violations(
            realHomes: Set<Path> = realHomes(),
            resolved: List<Pair<String, File>> = stateDirs(),
        ): List<String> = buildList {
            for ((what, dir) in resolved) {
                val p = normalize(dir)
                for (home in realHomes) {
                    val inRealState = DAEMON_DIRS.any { p.startsWith(home.resolve(it)) }
                    if (p == home || inRealState) add("$what resolves to $p (real home $home)")
                }
            }
        }

        private val DAEMON_DIRS = listOf(".cc-pocket", ".pairlet")

        private fun normalize(f: File): Path = f.toPath().toAbsolutePath().normalize()
    }
}
