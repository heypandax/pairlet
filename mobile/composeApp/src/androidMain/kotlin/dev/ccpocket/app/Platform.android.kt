package dev.ccpocket.app

// 10.0.2.2 is the Android emulator's alias for the host machine's loopback.
actual fun defaultDaemonUrl(): String = "ws://10.0.2.2:8765/v1/ws"

actual fun epochMillis(): Long = System.currentTimeMillis()
