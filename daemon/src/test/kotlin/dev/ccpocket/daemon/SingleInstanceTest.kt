package dev.ccpocket.daemon

import kotlin.test.Test
import kotlin.test.assertEquals

class SingleInstanceTest {
    @Test
    fun windows_netstat_parser_returns_only_exact_local_listeners() {
        val netstat = """
              TCP    127.0.0.1:8799         0.0.0.0:0              LISTENING       1234
              TCP    127.0.0.1:18799        0.0.0.0:0              LISTENING       2345
              TCP    127.0.0.1:50000        127.0.0.1:8799         ESTABLISHED     3456
              TCP    [::1]:8799             [::]:0                 LISTENING       4567
              UDP    127.0.0.1:8799         *:*                                    5678
        """.trimIndent()

        assertEquals(setOf("1234", "4567"), SingleInstance.windowsListeningPids(netstat, 8799))
    }
}
