package dev.ccpocket.app.util

/** base64url without padding — matches the daemon/relay's `Base64.getUrlEncoder().withoutPadding()`. */
object B64Url {
    private const val A = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val INV = IntArray(128) { -1 }.also { for (i in A.indices) it[A[i].code] = i }

    fun encode(data: ByteArray): String {
        val sb = StringBuilder((data.size * 4 + 2) / 3)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xff
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xff else -1
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xff else -1
            sb.append(A[b0 ushr 2])
            sb.append(A[((b0 and 0x3) shl 4) or (if (b1 >= 0) b1 ushr 4 else 0)])
            if (b1 >= 0) sb.append(A[((b1 and 0xf) shl 2) or (if (b2 >= 0) b2 ushr 6 else 0)])
            if (b2 >= 0) sb.append(A[b2 and 0x3f])
            i += 3
        }
        return sb.toString()
    }

    fun decode(s: String): ByteArray {
        val clean = s.trimEnd('=')
        val out = ArrayList<Byte>(clean.length * 3 / 4)
        var buf = 0
        var bits = 0
        for (c in clean) {
            val v = if (c.code < 128) INV[c.code] else -1
            if (v < 0) continue
            buf = (buf shl 6) or v
            bits += 6
            if (bits >= 8) { bits -= 8; out.add(((buf ushr bits) and 0xff).toByte()) }
        }
        return out.toByteArray()
    }
}
