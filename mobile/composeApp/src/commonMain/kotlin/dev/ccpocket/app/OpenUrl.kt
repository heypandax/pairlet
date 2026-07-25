package dev.ccpocket.app

const val USER_MANUAL_URL = "https://heypandax.github.io/cc-pocket/manual/"
const val USER_MANUAL_TROUBLESHOOTING_URL = "${USER_MANUAL_URL}?q=offline"
// Keep the in-app help entry on the always-public manual until public AI chat is available.
// Never route it to an internal-only messaging app or an unpublished support page.
const val SUPPORT_URL = USER_MANUAL_URL

/** Open an http(s) [url] for viewing: mobile presents an in-app browser (iOS SFSafariViewController,
 *  Android a Custom Tab when the default browser offers one), desktop hands it to the system browser. */
expect fun openWebUrl(url: String)
