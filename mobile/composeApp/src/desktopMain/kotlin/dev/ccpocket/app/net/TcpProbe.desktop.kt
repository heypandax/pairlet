package dev.ccpocket.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

actual suspend fun tcpReachable(host: String, port: Int, timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
    try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()) }
        true
    } catch (_: Throwable) {
        false
    }
}
