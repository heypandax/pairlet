package dev.ccpocket.daemon.util

import io.nayuki.qrcodegen.QrCode

/** Renders a QR code as ANSI terminal blocks — explicit white/black so it scans on any theme. */
object QrTerminal {
    private val ESC = 27.toChar() // ANSI escape, built from its code point to avoid literal control chars in source
    private val LIGHT = "$ESC[107m  $ESC[0m" // bright-white bg, 2 columns (square-ish module)
    private val DARK = "$ESC[40m  $ESC[0m"    // black bg

    fun render(text: String): String {
        val qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
        val n = qr.size
        val border = 2
        val sb = StringBuilder()
        for (y in -border until n + border) {
            for (x in -border until n + border) {
                val on = x in 0 until n && y in 0 until n && qr.getModule(x, y)
                sb.append(if (on) DARK else LIGHT)
            }
            sb.append('\n')
        }
        return sb.toString()
    }
}
