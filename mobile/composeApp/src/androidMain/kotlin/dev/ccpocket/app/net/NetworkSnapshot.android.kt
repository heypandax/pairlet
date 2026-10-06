package dev.ccpocket.app.net

import java.net.Inet4Address
import java.net.NetworkInterface

// #403: kept line-for-line identical between desktopMain and androidMain except the interface-name rules
// (the mobile flag) — the Android twin can't be compiled on the dev Mac, so drift would go unnoticed.
actual fun localNetworkSnapshot(): NetworkSnapshot? = runCatching {
    var hasLoopback = false
    val out = ArrayList<LocalIpv4>()
    for (nif in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
        if (!nif.isUp) continue
        if (nif.isLoopback) { hasLoopback = true; continue }
        val kind = interfaceKind(nif.name, mobile = true)
        for (ia in nif.interfaceAddresses) {
            val a = ia.address as? Inet4Address ?: continue
            val b = a.address
            val v = ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
                ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
            out += LocalIpv4(kind, v, ia.networkPrefixLength.toInt())
        }
    }
    NetworkSnapshot(out, hasLoopback)
}.getOrNull()
