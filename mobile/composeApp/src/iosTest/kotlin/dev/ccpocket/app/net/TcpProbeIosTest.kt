package dev.ccpocket.app.net

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** #404: the POSIX non-blocking probe runs on the simulator without crashing. */
class TcpProbeIosTest {
    @Test
    fun unlistenedLoopbackPortIsUnreachableWithoutCrashing() = runBlocking {
        // Port 9 (discard) is not listened on by the simulator host in practice.
        assertFalse(tcpReachable("127.0.0.1", 9, 1_500))
        assertFalse(tcpReachable("localhost", 9, 1_500))
    }

    @Test
    fun nonLiteralHostsDeferToTheRealAttempt() = runBlocking {
        assertTrue(tcpReachable("my-mac.local", 8799, 1_500))
    }
}
