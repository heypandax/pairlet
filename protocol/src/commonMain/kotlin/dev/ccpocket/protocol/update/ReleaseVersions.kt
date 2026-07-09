package dev.ccpocket.protocol.update

/**
 * Pure release-metadata logic shared by every self-updater — the daemon's [dev.ccpocket] update service and
 * the desktop app both compare versions and read SHA256SUMS the same way, so the decision code lives here
 * once (no target-specific deps, so it also compiles for iOS/Android even though only the JVM peers use it).
 */
object ReleaseVersions {

    /** Dotted-numeric compare, `v` prefix and non-digit suffixes ignored; true = [candidate] strictly newer. */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").split('.').map { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** SHA256SUMS format: `<hex>  <filename>` per line. Returns filename → sha256 (lowercased). */
    fun parseSums(text: String): Map<String, String> =
        text.lineSequence().mapNotNull { line ->
            val t = line.trim().split(Regex("\\s+"), limit = 2)
            if (t.size == 2 && t[0].matches(Regex("[0-9a-fA-F]{64}"))) t[1].removePrefix("*") to t[0].lowercase() else null
        }.toMap()
}
