package dev.ccpocket.protocol.update

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Persistence of the ENFORCED-mode anti-rollback mark: atomic, only ever raised, and a broken file means
 *  "no mark" (logged) rather than "refuse every update". */
class ReleaseHighWaterTest {
    private val dir: Path = Files.createTempDirectory("highwater-test")
    private val file: Path = dir.resolve("state").resolve(ReleaseHighWater.FILE_NAME)
    private val logs = mutableListOf<String>()

    @AfterTest
    fun cleanup() { dir.toFile().deleteRecursively() }

    private fun manifest(version: String, at: String) = ReleaseSignature.Manifest(version, Instant.parse(at), mapOf("a" to "0".repeat(64)))
    private fun read() = ReleaseHighWater.read(file) { logs += it }
    private fun raise(version: String, at: String) = ReleaseHighWater.raise(file, read(), manifest(version, at)) { logs += it }

    @Test
    fun absent_file_is_no_mark_and_raising_persists_it() {
        assertNull(read())
        raise("2.4.0", "2026-10-05T08:00:00Z")
        assertEquals(ReleaseSignature.HighWater("2.4.0", Instant.parse("2026-10-05T08:00:00Z")), read())
        assertTrue(logs.isEmpty(), "$logs")
        assertEquals(listOf(ReleaseHighWater.FILE_NAME), Files.list(file.parent).use { s -> s.map { it.fileName.toString() }.toList() },
            "the atomic write leaves no temp file behind")
    }

    @Test
    fun the_mark_only_ever_rises() {
        raise("2.4.0", "2026-10-05T08:00:00Z")
        raise("2.3.9", "2026-12-01T00:00:00Z")                 // lower version: ignored
        raise("2.4.0", "2026-10-01T00:00:00Z")                 // same version, earlier: ignored
        assertEquals(ReleaseSignature.HighWater("2.4.0", Instant.parse("2026-10-05T08:00:00Z")), read())
        raise("2.4.0", "2026-10-06T00:00:00Z")                 // same version re-signed later (hotfix): raised
        assertEquals(Instant.parse("2026-10-06T00:00:00Z"), read()!!.publishedAt)
        raise("2.5.0", "2026-10-02T00:00:00Z")                 // newer version wins whatever its time
        assertEquals("2.5.0", read()!!.version)
    }

    @Test
    fun a_corrupt_file_counts_as_no_mark_and_is_logged_and_then_replaced() {
        Files.createDirectories(file.parent)
        for (garbage in listOf("not json", "{}", """{"version":"../x","publishedAt":"2026-10-05T08:00:00Z"}""",
                """{"version":"2.4.0","publishedAt":"yesterday"}""")) {
            Files.writeString(file, garbage)
            logs.clear()
            assertNull(read(), garbage)
            assertTrue(logs.single().contains("unreadable — treating it as absent"), "$logs")
        }
        raise("2.4.0", "2026-10-05T08:00:00Z")
        assertEquals("2.4.0", read()!!.version)
    }

    @Test
    fun a_failed_write_is_logged_not_thrown() {
        Files.writeString(dir.resolve("blocker"), "a file where the state directory should be")
        val blocked = dir.resolve("blocker").resolve(ReleaseHighWater.FILE_NAME)
        ReleaseHighWater.raise(blocked, null, manifest("2.4.0", "2026-10-05T08:00:00Z")) { logs += it }
        assertTrue(logs.single().startsWith("could not record the update signature high-water mark"), "$logs")
    }
}
