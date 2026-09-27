package dev.ccpocket.app

/** Test-only observation seam (desktop compilation only), the same shape as `telemetryTap`: `internal`,
 *  null in production. A desktopTest that clicks a link-bearing control reads the URL it would have
 *  opened from here. */
internal var webUrlTap: ((String) -> Unit)? = null

/** Desktop: the system browser is the right viewer — no in-app chrome.
 *
 *  Never under the unit-test runner, though (`ccpocket.test`, set for every Test task by the root build,
 *  the same gate Telemetry and Sentry honour): a UI test that clicks a link used to hand the URL to the
 *  developer's real browser, so every `desktopTest` run popped a GitHub tab on the machine running it. */
actual fun openWebUrl(url: String) {
    webUrlTap?.invoke(url)
    if (System.getProperty("ccpocket.test") == "true") return
    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
}
