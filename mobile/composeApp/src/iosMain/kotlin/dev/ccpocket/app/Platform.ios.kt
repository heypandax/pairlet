package dev.ccpocket.app

import platform.Foundation.NSDate
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970

// The iOS Simulator shares the Mac's network, so 127.0.0.1 reaches the host daemon.
// For a real device, change this in-app to your Mac's LAN IP (and run the daemon with --host 0.0.0.0).
actual fun defaultDaemonUrl(): String = "ws://127.0.0.1:8765/v1/ws"

actual fun epochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

// Launch arg `-ccpPreview YES` lands in standardUserDefaults — see marketing/preview.
actual fun isPreviewMode(): Boolean = NSUserDefaults.standardUserDefaults.boolForKey("ccpPreview")
