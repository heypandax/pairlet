package dev.ccpocket.app.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the cwd-relative resolution added for issue #74: a relative transcript path (e.g.
 * "notes/材料.md") must be statted under the session's working directory, not the JVM's process cwd —
 * otherwise the link never forms (exists() false) or opens the wrong file.
 */
class DesktopPathOpenerTest {

    @Test
    fun relativePathResolvesAgainstBaseDir() {
        val base = File.createTempFile("ccpocket-cwd", "").let { it.delete(); File(it.path).apply { mkdirs() } }
        try {
            File(base, "会议").mkdirs()
            File(base, "会议/材料.md").writeText("x")

            val opener = DesktopPathOpener(base.absolutePath)
            assertTrue(opener.exists("会议/材料.md"), "relative path should resolve under baseDir")
            assertFalse(opener.exists("会议/缺失.md"), "a missing relative path stays inert")
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun absolutePathIgnoresBaseDir() {
        val real = File.createTempFile("ccpocket-abs", ".md").apply { writeText("x") }
        try {
            // a bogus baseDir must not affect an absolute path — it stands on its own
            val opener = DesktopPathOpener("/no/such/base")
            assertTrue(opener.exists(real.absolutePath))
        } finally {
            real.delete()
        }
    }

    @Test
    fun nullBaseDirDoesNotResolveTranscriptRelativePaths() {
        // the app-root opener (no session cwd) must not open some unrelated file under the process cwd:
        // a relative path that isn't real relative to the JVM's own dir simply fails exists()
        val opener = DesktopPathOpener(null)
        assertFalse(opener.exists("10_Notes/会议/definitely-not-here-${System.nanoTime()}.md"))
    }
}
