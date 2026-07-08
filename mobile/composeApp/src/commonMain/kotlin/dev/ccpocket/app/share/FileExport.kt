package dev.ccpocket.app.share

import dev.ccpocket.protocol.FileContent
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// ════════════════════════════════════════════════════════════════════
//  Getting a session file OUT of the app (issue #67) and into a native
//  viewer (issue #79). The bytes are whatever FileContent already
//  carried over the E2E channel — no new read surface on the daemon.
// ════════════════════════════════════════════════════════════════════

/** true = this platform exports through a Save As dialog (desktop); false = a system share sheet
 *  (iOS/Android) — drives the action labels so the button says what actually happens. */
expect val exportIsSaveDialog: Boolean

/** Hand the file to the platform's export gesture: iOS/Android present the share sheet (save to
 *  Files, AirDrop, WeChat/Feishu, …); desktop shows a Save As dialog. [name] is a plain file name
 *  (no directories). Best-effort: failures are swallowed — there is nothing actionable to show. */
expect fun shareFile(name: String, bytes: ByteArray, mediaType: String?)

/** Open the file in the platform's native previewer: QuickLook on iOS, ACTION_VIEW on Android,
 *  the default app on desktop. Returns false when nothing here can show it, so callers can fall
 *  back to [shareFile]. */
expect fun previewFile(name: String, bytes: ByteArray, mediaType: String?): Boolean

/** The viewer payload as raw bytes, or null when there is nothing exportable (loading / error).
 *  Truncated text exports the shown prefix — the viewer's banner already says so. */
@OptIn(ExperimentalEncodingApi::class)
fun exportBytesOf(content: FileContent?): ByteArray? = when {
    content == null || !content.ok -> null
    content.base64 != null -> runCatching { Base64.Default.decode(content.base64!!) }.getOrNull()
    content.text != null -> content.text!!.encodeToByteArray()
    else -> null
}
