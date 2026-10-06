package dev.ccpocket.app.net

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.IFF_LOOPBACK
import platform.posix.IFF_UP
import platform.darwin.freeifaddrs
import platform.darwin.getifaddrs
import platform.darwin.ifaddrs
import platform.posix.sockaddr
import platform.posix.sockaddr_in

/** #403: getifaddrs walk — up interfaces, IPv4 only; prefix length from the netmask's set bits. */
@OptIn(ExperimentalForeignApi::class)
actual fun localNetworkSnapshot(): NetworkSnapshot? = memScoped {
    val head = alloc<CPointerVar<ifaddrs>>()
    if (getifaddrs(head.ptr) != 0) return@memScoped null
    try {
        var hasLoopback = false
        val out = ArrayList<LocalIpv4>()
        var p: CPointer<ifaddrs>? = head.value
        while (p != null) {
            val ifa = p.pointed
            val flags = ifa.ifa_flags.toInt()
            val addr = ifa.ifa_addr
            if ((flags and IFF_UP) != 0 && addr != null && addr.pointed.sa_family.toInt() == AF_INET) {
                if ((flags and IFF_LOOPBACK) != 0) {
                    hasLoopback = true
                } else {
                    val ip = inAddrBytes(addr).fold(0) { acc, b -> (acc shl 8) or b }
                    val prefix = ifa.ifa_netmask?.let { m -> inAddrBytes(m).sumOf { it.countOneBits() } } ?: 32
                    out += LocalIpv4(iosInterfaceKind(ifa.ifa_name?.toKString().orEmpty()), ip, prefix)
                }
            }
            p = ifa.ifa_next
        }
        NetworkSnapshot(out, hasLoopback)
    } finally {
        freeifaddrs(head.value)
    }
}

// sin_addr is stored in network byte order: read its four bytes in memory order rather than the UInt
// field, so the result is big-endian on any host.
@OptIn(ExperimentalForeignApi::class)
private fun inAddrBytes(sa: CPointer<sockaddr>): IntArray {
    val bytes = sa.reinterpret<sockaddr_in>().pointed.sin_addr.ptr.reinterpret<ByteVar>()
    return IntArray(4) { bytes[it].toInt() and 0xFF }
}
