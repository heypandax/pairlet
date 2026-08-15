package dev.ccpocket.app.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #251 — the async half of the crash. Every "update the daemon" timeout in [PocketRepository]
 * resolves its copy with a suspend `getString` inside a detached `scope.launch { delay(8000); … }`.
 * A throwing lookup there has no handler: on desktop it becomes an AWT error dialog with no text and
 * a window that never comes back. Localization is not worth the process.
 */
class SafeStringsTest {

    @Test
    fun successPassesTheLocalizedStringThroughUntouched() = runBlocking {
        assertEquals("Localized copy", safeString("English fallback") { "Localized copy" })
    }

    @Test
    fun failureFallsBackAndTagsTheCode() = runBlocking {
        val out = safeString("English fallback") { throw IllegalStateException("no bundle") }
        assertEquals("English fallback [CCP-STR-01 IllegalStateException]", out)
    }

    @Test
    fun anErrorIsContainedToo() = runBlocking {
        // packaged builds fail this way (missing resource root / failed <clinit>), and Exception-only
        // containment would let exactly these through — which is the bug.
        val out = safeString("English fallback") { throw NoClassDefFoundError("Res\$string") }
        assertTrue(out.startsWith("English fallback "), out)
        assertTrue(out.endsWith("[CCP-STR-01 NoClassDefFoundError]"), out)
    }

    @Test
    fun blankFallbackDegradesToTheBareCodeNotALeadingSpace() {
        assertEquals("[CCP-STR-01 RuntimeException]", stringFallbackWithCode("", RuntimeException("x")))
    }

    @Test
    fun anAnonymousThrowableStillYieldsAReportableTag() {
        val tag = stringFallbackWithCode("copy", object : RuntimeException("nameless") {})
        assertTrue(tag.startsWith("copy [CCP-STR-01 "), tag)
        assertTrue(tag.endsWith("]"), tag)
    }
}
