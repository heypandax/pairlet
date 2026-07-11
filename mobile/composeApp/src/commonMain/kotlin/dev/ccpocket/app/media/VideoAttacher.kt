package dev.ccpocket.app.media

import androidx.compose.runtime.Composable

/**
 * Movie-filtered document picker (issue #98). Returns a launcher; picked files ride the SAME
 * chunk-upload as [rememberFileAttacher] — a video is just a large file that lands in the workspace
 * inbox. The only difference is the picker's type filter (Android video MIME wildcard, iOS movie UTType) so the
 * "Video" attach-sheet option surfaces videos instead of every document. Each [PickedFile] also carries
 * a [PickedFile.localUri] so a just-sent video can play back on this device.
 *  - Android: SAF OpenMultipleDocuments filtered to the video MIME wildcard
 *  - iOS: `UIDocumentPickerViewController` opening `UTTypeMovie`
 *  - Desktop: unused no-op — the desktop composer already accepts video via its AWT dialog + drop.
 */
@Composable
expect fun rememberVideoAttacher(onPicked: (List<PickedFile>) -> Unit): () -> Unit

/**
 * Hand a locally-available video ([PickedFile.localUri], carried through to
 * [dev.ccpocket.app.data.SentFile.localUri]) to the platform's video player (issue #98). Only reachable
 * for a video just picked on THIS device; a general/remote view has no local handle and the player
 * falls back to the "open it on the computer" note instead of calling this.
 *  - Android: `ACTION_VIEW` intent (the system video player)
 *  - iOS: modally-presented `AVPlayerViewController`
 *  - Desktop: the OS default player (never mounted — the desktop card opens the landed file directly)
 */
@Composable
expect fun rememberLocalVideoOpener(): (String) -> Unit
