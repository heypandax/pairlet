package dev.ccpocket.daemon

import com.github.ajalt.clikt.testing.test
import kotlin.test.*

class ServiceInstallPortsTest {
    @Test fun invalidAndCollidingPortsDoNotGenerateServiceDefinitions() {
        for (args in listOf("--pair-port 0", "--port 65536", "--port -1", "--pair-port 8765", "--port 8799", "--port 18000 --pair-port 18000")) {
            val result = ServiceInstallCmd().test("--exec /tmp/pairlet-test-launcher $args")
            assertNotEquals(0, result.statusCode, args)
            assertFalse("ProgramArguments" in result.output)
            assertFalse("ExecStart=" in result.output)
        }
    }

    @Test fun customPortsReachServiceDefinitionWithoutStartingDaemon() {
        val result = ServiceInstallCmd().test("--exec /tmp/pairlet-test-launcher --pair-port 18899 --port 18765")
        assertEquals(0, result.statusCode, result.output)
        assertTrue("--pair-port" in result.output)
        assertTrue("18899" in result.output)
        assertTrue("--port" in result.output)
        assertTrue("18765" in result.output)
    }
}
