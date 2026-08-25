package dev.ccpocket.app

import dev.ccpocket.app.util.B64Url
import dev.ccpocket.protocol.PocketJson
import kotlinx.serialization.Serializable

// Operational help is mirrored on the relay origin so an Actions/Pages deployment incident cannot
// strand the in-app help buttons on a stale manual. The marketing site remains on GitHub Pages.
const val USER_MANUAL_URL = "https://pocket.ark-nexus.cc/manual/"
const val USER_MANUAL_TROUBLESHOOTING_URL = "${USER_MANUAL_URL}?q=offline"
// Public browser-based support; it must never require membership in a messaging team.
const val SUPPORT_URL = "https://pocket.ark-nexus.cc/support/"
// App support opens the same public surface in ready-to-type chat mode. The query is deliberately
// stable and non-sensitive; optional App context rides in the URL fragment so it never reaches the
// static-site request, proxy logs, or Referer headers.
const val SUPPORT_CHAT_URL = "${SUPPORT_URL}?mode=chat&source=app"

// The Claude Code CLI is cc-pocket's PREREQUISITE, not its dependency: the daemon drives an agent CLI that
// the user installs and authenticates themselves. The first-run guide states that in one line and links here
// rather than paraphrasing an install procedure that Anthropic owns and revises (issue #278 batch 2).
const val CLAUDE_CLI_SETUP_URL = "https://code.claude.com/docs/en/setup"

// The long-form setup guide the first-run screen links out to — the marketing/docs site the onboarding
// screen has always pointed at, named here instead of inlined at the call site.
const val SETUP_GUIDE_URL = "https://heypandax.github.io/cc-pocket/"

// Where a phone goes for a newer app build (issue #200). Both are the EXISTING distribution surfaces —
// the release page that already carries the APK, and the store listing — so the nudge never becomes a
// second update channel of its own.
const val RELEASES_URL = "https://github.com/heypandax/cc-pocket/releases/latest"
const val APP_STORE_URL = "https://apps.apple.com/app/id6778773969"

/**
 * Small, user-visible environment snapshot that lets public support tailor button/location guidance.
 * It deliberately excludes session/conversation ids, titles, project names, paths, prompts, file
 * contents, logs, machine identity, pairing material, and credentials.
 */
@Serializable
internal data class SupportContext(
    val schemaVersion: Int = 1,
    val screen: String,
    val platform: String,
    val appVersion: String,
    val agent: String? = null,
    val model: String? = null,
    val state: String? = null,
    val controls: List<String> = emptyList(),
)

internal fun supportChatUrl(context: SupportContext? = null): String {
    if (context == null) return SUPPORT_CHAT_URL
    val json = PocketJson.encodeToString(SupportContext.serializer(), context)
    return "$SUPPORT_CHAT_URL#ctx=${B64Url.encode(json.encodeToByteArray())}"
}

/** Open an http(s) [url] for viewing: mobile presents an in-app browser (iOS SFSafariViewController,
 *  Android a Custom Tab when the default browser offers one), desktop hands it to the system browser. */
expect fun openWebUrl(url: String)
