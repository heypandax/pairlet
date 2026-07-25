package dev.ccpocket.app

const val USER_MANUAL_URL = "https://heypandax.github.io/cc-pocket/manual/"
const val USER_MANUAL_TROUBLESHOOTING_URL = "${USER_MANUAL_URL}?q=offline"
// Version the public entry so in-app browser/CDN caches cannot strand users on a retired support channel.
const val SUPPORT_URL = "https://heypandax.github.io/cc-pocket/support/?v=20260725-2"

/** Open an http(s) [url] for viewing: mobile presents an in-app browser (iOS SFSafariViewController,
 *  Android a Custom Tab when the default browser offers one), desktop hands it to the system browser. */
expect fun openWebUrl(url: String)
