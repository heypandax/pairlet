package dev.ccpocket.app.net

/**
 * Pre-dial eligibility for a stored direct address (#403, TRANSPORT-AUTO-REPATH-V1 3.2): "could this address,
 * on the network this device is on right now, possibly be that computer?" Dialing a remembered private address
 * from cellular or a foreign subnet only costs the budget AND sends the cleartext LanHello (device id) to
 * whatever host owns that address there — so it's skipped. A skip is not a failure: no cooldown, no bad-URL mark;
 * the next attempt asks again on whatever network the device is on by then.
 */

/** How a local interface takes part in the check. [OTHER] (tunnels, VMs, unknown names) never makes a match. */
enum class NetKind { WIFI, WIRED, CELLULAR, OTHER }

/** One up, non-loopback IPv4 address of a local interface, with that interface's prefix length. */
data class LocalIpv4(val kind: NetKind, val address: Int, val prefixLength: Int)

/** What the platform reported; `null` from [localNetworkSnapshot] means "couldn't tell" (the check then allows). */
data class NetworkSnapshot(val ipv4: List<LocalIpv4>, val hasLoopback: Boolean = true)

sealed interface DirectEligibility {
    data object Dial : DirectEligibility
    data class Skip(val reason: SkipReason) : DirectEligibility
}

enum class SkipReason {
    /** A loopback address only names this machine — useless from a phone. */
    LOOPBACK_NOT_SAME_MACHINE,
    /** Private/link-local address outside every Wi‑Fi/wired subnet, and some Wi‑Fi/wired link is up. */
    OTHER_SUBNET,
    /** Private/link-local address and the only usable link is cellular. */
    CELLULAR_ONLY,
    /** Private/link-local address and no usable link at all. */
    NO_LOCAL_NETWORK,
}

/**
 * The platform's interface list — up interfaces only, IPv4 addresses with their prefix length, kinds by
 * interface name per TRANSPORT-AUTO-REPATH-V1 3.2. Returns null when the platform API fails.
 */
expect fun localNetworkSnapshot(): NetworkSnapshot?

/** Pure decision; [sameMachineClient] is true only for the desktop app (it runs next to its daemon). */
fun directEligibility(url: String, snapshot: NetworkSnapshot?, sameMachineClient: Boolean): DirectEligibility {
    val host = hostOf(url) ?: return DirectEligibility.Dial // unparseable: today's behavior, the dial fails fast
    if (host.equals("localhost", ignoreCase = true) || host == "::1") return loopback(sameMachineClient)
    val ip = parseIpv4(host) ?: return DirectEligibility.Dial // hostname or non-loopback IPv6: always dial
    if ((ip ushr 24) == 127) return loopback(sameMachineClient)
    if (!isPrivateOrLinkLocal(ip)) return DirectEligibility.Dial // public address
    snapshot ?: return DirectEligibility.Dial // can't tell: the budget already caps the cost (see 3.2)
    val lan = snapshot.ipv4.filter { it.kind == NetKind.WIFI || it.kind == NetKind.WIRED }
    if (lan.any { sameSubnet(it.address, ip, it.prefixLength) }) return DirectEligibility.Dial
    return DirectEligibility.Skip(when {
        lan.isNotEmpty() -> SkipReason.OTHER_SUBNET
        snapshot.ipv4.any { it.kind == NetKind.CELLULAR } -> SkipReason.CELLULAR_ONLY
        else -> SkipReason.NO_LOCAL_NETWORK
    })
}

private fun loopback(sameMachineClient: Boolean): DirectEligibility =
    if (sameMachineClient) DirectEligibility.Dial else DirectEligibility.Skip(SkipReason.LOOPBACK_NOT_SAME_MACHINE)

/** Host part of a ws/wss/http URL; brackets stripped from an IPv6 literal. */
internal fun hostOf(url: String): String? {
    val rest = url.substringAfter("://", missingDelimiterValue = "").substringBefore('/').substringBefore('?')
        .substringAfterLast('@')
    if (rest.isEmpty()) return null
    if (rest.startsWith("[")) return rest.substringAfter('[').substringBefore(']').ifEmpty { null }
    return rest.substringBefore(':').ifEmpty { null }
}

/** Dotted-quad → big-endian Int, or null for anything that isn't exactly four 0..255 decimal parts. */
fun parseIpv4(s: String): Int? {
    val parts = s.split('.')
    if (parts.size != 4) return null
    var v = 0
    for (p in parts) {
        if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
        val n = p.toInt()
        if (n > 255) return null
        v = (v shl 8) or n
    }
    return v
}

private fun isPrivateOrLinkLocal(ip: Int): Boolean =
    sameSubnet(ip, 0x0A000000, 8) || sameSubnet(ip, 0xAC100000.toInt(), 12) ||
        sameSubnet(ip, 0xC0A80000.toInt(), 16) || sameSubnet(ip, 0xA9FE0000.toInt(), 16)

/** Prefix lengths outside 1..32 never match: a /0 "subnet" would make every address local. */
fun sameSubnet(a: Int, b: Int, prefixLength: Int): Boolean {
    if (prefixLength !in 1..32) return false
    val mask = if (prefixLength == 32) -1 else (-1 shl (32 - prefixLength))
    return (a and mask) == (b and mask)
}

/** Interface name → kind for the JVM platforms (desktop rules; Android passes its own [mobile] = true rules). */
fun interfaceKind(name: String, mobile: Boolean): NetKind {
    val n = name.lowercase()
    return if (mobile) when {
        n.startsWith("wlan") -> NetKind.WIFI
        n.startsWith("eth") -> NetKind.WIRED
        n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp") -> NetKind.CELLULAR
        else -> NetKind.OTHER
    } else when {
        DESKTOP_VIRTUAL_PREFIXES.any { n.startsWith(it) } -> NetKind.OTHER
        else -> NetKind.WIRED
    }
}

/** iOS: `en*` is Wi‑Fi (and the rare wired adapter, same treatment), `pdp_ip*` is cellular. */
fun iosInterfaceKind(name: String): NetKind = when {
    name.startsWith("en") -> NetKind.WIFI
    name.startsWith("pdp_ip") -> NetKind.CELLULAR
    else -> NetKind.OTHER
}

private val DESKTOP_VIRTUAL_PREFIXES = listOf("utun", "tun", "tap", "tailscale", "wg", "docker", "bridge", "vmnet", "lo")
