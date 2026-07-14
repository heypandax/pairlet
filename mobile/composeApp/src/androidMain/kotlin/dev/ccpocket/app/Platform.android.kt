package dev.ccpocket.app

// 10.0.2.2 is the Android emulator's alias for the host machine's loopback.
actual fun defaultDaemonUrl(): String = "ws://10.0.2.2:8765/v1/ws"

actual fun epochMillis(): Long = System.currentTimeMillis()

actual fun localClock(epochMs: Long): LocalClock {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = epochMs }
    return LocalClock(
        year = c.get(java.util.Calendar.YEAR),
        monthOfYear = c.get(java.util.Calendar.MONTH) + 1,
        dayOfMonth = c.get(java.util.Calendar.DAY_OF_MONTH),
        hour = c.get(java.util.Calendar.HOUR_OF_DAY),
        minute = c.get(java.util.Calendar.MINUTE),
        second = c.get(java.util.Calendar.SECOND),
        isoDayOfWeek = ((c.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7) + 1, // Calendar SUNDAY=1 → ISO 7
    )
}

actual fun isPreviewMode(): Boolean = false
