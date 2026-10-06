package dev.ccpocket.app.net

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.posix.AF_INET
import platform.posix.EINPROGRESS
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.IPPROTO_TCP
import platform.posix.O_NONBLOCK
import platform.posix.POLLOUT
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_ERROR
import platform.posix.close
import platform.posix.connect
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.getsockopt
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import kotlinx.cinterop.IntVar

/**
 * POSIX non-blocking connect + poll(POLLOUT) + SO_ERROR (#404). Only IPv4 literals and `localhost`
 * are probed; anything else (hostnames, IPv6) returns true so the real direct attempt — which has
 * its own 3 s budget (#403) — decides. A false "reachable" costs one bounded attempt; a false
 * "unreachable" would silently pin the client to relay, so we err on the side of trying.
 */
@OptIn(ExperimentalForeignApi::class)
actual suspend fun tcpReachable(host: String, port: Int, timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
    val literal = if (host.equals("localhost", ignoreCase = true)) "127.0.0.1" else host
    if (parseIpv4(literal) == null) return@withContext true
    if (port !in 1..65535) return@withContext false
    val fd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
    if (fd < 0) return@withContext false
    try {
        memScoped {
            val flags = fcntl(fd, F_GETFL, 0)
            if (flags < 0 || fcntl(fd, F_SETFL, flags or O_NONBLOCK) < 0) return@withContext false
            val addr = alloc<sockaddr_in>()
            addr.sin_len = sizeOf<sockaddr_in>().convert()
            addr.sin_family = AF_INET.convert()
            // Network byte order by hand: Darwin's htons is a macro that cinterop does not export.
            addr.sin_port = (((port and 0xff) shl 8) or ((port shr 8) and 0xff)).convert()
            // s_addr is network order in memory; on little-endian Apple silicon that is the
            // byte-reversed host-order value parseIpv4 returns (a.b.c.d -> a<<24|b<<16|c<<8|d).
            val ip = parseIpv4(literal) ?: return@withContext false
            addr.sin_addr.s_addr = (((ip and 0xff) shl 24) or ((ip ushr 8 and 0xff) shl 16) or
                ((ip ushr 16 and 0xff) shl 8) or (ip ushr 24 and 0xff)).toUInt()
            val rc = connect(fd, addr.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert())
            if (rc == 0) return@withContext true
            if (errno != EINPROGRESS) return@withContext false
            val pfd = alloc<pollfd>()
            pfd.fd = fd
            pfd.events = POLLOUT.convert()
            val ready = poll(pfd.ptr, 1u, timeoutMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt())
            if (ready <= 0) return@withContext false
            val err = alloc<IntVar>()
            val len = alloc<socklen_tVar>()
            len.value = sizeOf<IntVar>().convert()
            if (getsockopt(fd, SOL_SOCKET, SO_ERROR, err.ptr, len.ptr) != 0) return@withContext false
            err.value == 0
        }
    } catch (_: Throwable) {
        false
    } finally {
        close(fd)
    }
}
