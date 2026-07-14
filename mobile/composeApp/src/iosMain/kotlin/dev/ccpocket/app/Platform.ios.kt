package dev.ccpocket.app

import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMinute
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitSecond
import platform.Foundation.NSCalendarUnitWeekday
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.NSUserDefaults
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970

// The iOS Simulator shares the Mac's network, so 127.0.0.1 reaches the host daemon.
// For a real device, change this in-app to your Mac's LAN IP (and run the daemon with --host 0.0.0.0).
actual fun defaultDaemonUrl(): String = "ws://127.0.0.1:8765/v1/ws"

actual fun epochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

actual fun localClock(epochMs: Long): LocalClock {
    val units = NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay or
        NSCalendarUnitHour or NSCalendarUnitMinute or NSCalendarUnitSecond or NSCalendarUnitWeekday
    val c = NSCalendar.currentCalendar.components(units, fromDate = NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0))
    return LocalClock(
        year = c.year.toInt(), monthOfYear = c.month.toInt(), dayOfMonth = c.day.toInt(),
        hour = c.hour.toInt(), minute = c.minute.toInt(), second = c.second.toInt(),
        isoDayOfWeek = ((c.weekday.toInt() + 5) % 7) + 1, // NSCalendar Sunday=1 → ISO 7
    )
}

// Launch arg `-ccpPreview YES` lands in standardUserDefaults — see marketing/preview.
actual fun isPreviewMode(): Boolean = NSUserDefaults.standardUserDefaults.boolForKey("ccpPreview")
