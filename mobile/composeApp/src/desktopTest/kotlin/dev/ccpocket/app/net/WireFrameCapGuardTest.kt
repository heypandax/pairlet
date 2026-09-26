package dev.ccpocket.app.net

import dev.ccpocket.protocol.WIRE_MAX_FRAME_BYTES
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The app declares `ClientCaps.maxFrameBytes = WIRE_MAX_FRAME_BYTES` (4 MiB) on every platform. On iOS that
 * claim is only true from Ktor 3.3.2 on: earlier Darwin engines never applied `maxFrameSize` (KTOR-6963), so
 * the phone silently kept Apple's 1 MiB default and a frame the daemon was told it could send dropped the
 * link. A Ktor downgrade must fail here before it ships that claim again.
 */
class WireFrameCapGuardTest {

    private fun ktorVersion(): List<Int> {
        val catalog = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { it.resolve("gradle/libs.versions.toml") }.firstOrNull { it.isFile }
        assertNotNull(catalog, "gradle/libs.versions.toml not found above ${File("").absoluteFile}")
        val line = catalog.readLines().firstOrNull { it.trim().startsWith("ktor = ") }
        assertNotNull(line, "no `ktor = ` entry in ${catalog.path}")
        return Regex("\"(\\d+)\\.(\\d+)\\.(\\d+)").find(line)!!.groupValues.drop(1).map { it.toInt() }
    }

    @Test
    fun the_ios_frame_claim_requires_the_darwin_maxFrameSize_fix() {
        val v = ktorVersion()
        val fixed = listOf(3, 3, 2)
        assertTrue(
            v.zip(fixed).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> a > b } ?: true,
            "Ktor ${v.joinToString(".")} predates the KTOR-6963 fix (3.3.2): the app must not claim ${WIRE_MAX_FRAME_BYTES} B frames on iOS",
        )
    }

    @Test
    fun every_transport_configures_the_same_ceiling_it_declares() {
        assertEquals(4L * 1024 * 1024, WIRE_MAX_FRAME_BYTES)
        assertEquals(WIRE_MAX_FRAME_BYTES, RelayE2EConnection.MAX_FRAME_BYTES)
        assertEquals(WIRE_MAX_FRAME_BYTES, DirectE2EConnection.MAX_FRAME_BYTES)
    }
}
