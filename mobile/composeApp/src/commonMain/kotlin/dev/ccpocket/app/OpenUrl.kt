package dev.ccpocket.app

// Operational help is mirrored on the relay origin so an Actions/Pages deployment incident cannot
// strand the in-app help buttons on a stale manual. The marketing site remains on GitHub Pages.
const val USER_MANUAL_URL = "https://pocket.ark-nexus.cc/manual/"
const val USER_MANUAL_TROUBLESHOOTING_URL = "${USER_MANUAL_URL}?q=offline"
// Public browser-based support; it must never require membership in a messaging team.
const val SUPPORT_URL = "https://pocket.ark-nexus.cc/support/"

/** Open an http(s) [url] for viewing: mobile presents an in-app browser (iOS SFSafariViewController,
 *  Android a Custom Tab when the default browser offers one), desktop hands it to the system browser. */
expect fun openWebUrl(url: String)
