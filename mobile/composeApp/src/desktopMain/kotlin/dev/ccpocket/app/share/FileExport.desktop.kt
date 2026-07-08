package dev.ccpocket.app.share

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Files

actual val exportIsSaveDialog: Boolean = true

/** Save As via the native AWT dialog (blocks on the EDT like every Compose Desktop file dialog). */
actual fun shareFile(name: String, bytes: ByteArray, mediaType: String?) {
    runCatching {
        val dialog = FileDialog(null as Frame?, "Save", FileDialog.SAVE)
        dialog.file = File(name).name // defensive: a plain name is expected, never a path
        dialog.isVisible = true
        val dir = dialog.directory ?: return
        val chosen = dialog.file ?: return // null = the user cancelled
        File(dir, chosen).writeBytes(bytes)
    }
}

/** "Open with the system app" IS the desktop preview (#79): a temp copy handed to Desktop.open. */
actual fun previewFile(name: String, bytes: ByteArray, mediaType: String?): Boolean = runCatching {
    val file = File(Files.createTempDirectory("cc-pocket-export").toFile(), File(name).name)
    file.writeBytes(bytes)
    file.deleteOnExit()
    java.awt.Desktop.getDesktop().open(file)
    true
}.getOrDefault(false)
