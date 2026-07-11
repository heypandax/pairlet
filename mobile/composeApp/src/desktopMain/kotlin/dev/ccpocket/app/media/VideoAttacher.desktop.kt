package dev.ccpocket.app.media

import androidx.compose.runtime.Composable

/** Unused on desktop — the desktop composer already accepts video through its AWT dialog + chat-pane
 *  drag-and-drop (desktop/FileInput.kt), the same split the file attacher uses. */
@Composable
actual fun rememberVideoAttacher(onPicked: (List<PickedFile>) -> Unit): () -> Unit = {}

/** The mobile player overlay is never mounted on desktop (the desktop card opens the landed file
 *  directly via the system player). This actual exists only to satisfy the expect; it opens a local
 *  file URI in the OS default app as a defensive fallback. */
@Composable
actual fun rememberLocalVideoOpener(): (String) -> Unit = { uri ->
    runCatching {
        val file = java.io.File(java.net.URI(uri))
        if (file.exists()) java.awt.Desktop.getDesktop().open(file)
    }
}
