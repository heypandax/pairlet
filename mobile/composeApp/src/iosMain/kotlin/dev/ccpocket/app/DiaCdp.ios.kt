package dev.ccpocket.app

// Dia is a macOS desktop browser; there is nothing to launch on iOS.
actual fun diaCdpSupported(): Boolean = false

actual suspend fun launchDiaCdp(port: Int): DiaCdpResult = DiaCdpResult(false, "desktop only")
