package dev.ccpocket.app.media

import androidx.compose.runtime.Composable

/** Unused on desktop — the desktop composer picks via its own AWT dialog + chat-pane drag-and-drop
 *  (desktop/FileInput.kt), the same split the image attacher already has. */
@Composable
actual fun rememberFileAttacher(onPicked: (List<PickedFile>) -> Unit): () -> Unit = {}
