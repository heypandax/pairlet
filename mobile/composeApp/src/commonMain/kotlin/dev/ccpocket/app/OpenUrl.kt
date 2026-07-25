package dev.ccpocket.app

const val USER_MANUAL_URL = "https://heypandax.github.io/cc-pocket/manual/"
const val USER_MANUAL_TROUBLESHOOTING_URL = "${USER_MANUAL_URL}?q=offline"

/** Open an http(s) [url] for viewing: mobile presents an in-app browser (iOS SFSafariViewController,
 *  Android a Custom Tab when the default browser offers one), desktop hands it to the system browser. */
expect fun openWebUrl(url: String)
