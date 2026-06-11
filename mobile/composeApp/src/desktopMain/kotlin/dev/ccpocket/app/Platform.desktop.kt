package dev.ccpocket.app

actual fun defaultDaemonUrl(): String = "ws://127.0.0.1:8765/v1/ws"

actual fun epochMillis(): Long = System.currentTimeMillis()
