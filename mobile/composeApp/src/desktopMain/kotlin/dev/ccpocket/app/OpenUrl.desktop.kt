package dev.ccpocket.app

/** Desktop: the system browser is the right viewer — no in-app chrome. */
actual fun openWebUrl(url: String) {
    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
}
