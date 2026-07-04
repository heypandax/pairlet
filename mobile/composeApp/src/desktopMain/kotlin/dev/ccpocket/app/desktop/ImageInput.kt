package dev.ccpocket.app.desktop

import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/** Formats the daemon-side pipeline accepts today (heic is out: ImageIO can't decode it for compression). */
private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

private fun File.isImageFile() = extension.lowercase() in IMAGE_EXTS

/**
 * Images currently on the system clipboard, as encoded bytes ready for the composer's attach pipeline:
 * a raw bitmap (screenshot tools put imageFlavor) or copied image FILES (Finder puts a file list).
 * Empty when the clipboard holds neither — the caller then lets the normal text paste proceed.
 */
fun clipboardImages(): List<ByteArray> = runCatching {
    val t = Toolkit.getDefaultToolkit().systemClipboard.getContents(null) ?: return emptyList()
    when {
        t.isDataFlavorSupported(DataFlavor.imageFlavor) ->
            listOfNotNull((t.getTransferData(DataFlavor.imageFlavor) as java.awt.Image).toPngBytes())
        t.isDataFlavorSupported(DataFlavor.javaFileListFlavor) -> {
            @Suppress("UNCHECKED_CAST")
            (t.getTransferData(DataFlavor.javaFileListFlavor) as List<File>)
                .filter { it.isImageFile() }.map { it.readBytes() }
        }
        else -> emptyList()
    }
}.getOrDefault(emptyList())

private fun java.awt.Image.toPngBytes(): ByteArray? = runCatching {
    val w = getWidth(null); val h = getHeight(null)
    if (w <= 0 || h <= 0) return null
    val buf = this as? BufferedImage ?: BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also {
        val g = it.createGraphics(); g.drawImage(this, 0, 0, null); g.dispose()
    }
    ByteArrayOutputStream().also { ImageIO.write(buf, "png", it) }.toByteArray()
}.getOrNull()

/** Native multi-select image picker. BLOCKS until dismissed — call off the UI thread (Dispatchers.IO). */
fun pickImages(): List<ByteArray> {
    val dlg = FileDialog(null as Frame?, "Attach images", FileDialog.LOAD)
    dlg.isMultipleMode = true
    dlg.setFilenameFilter { _, name -> File(name).isImageFile() } // no-op on macOS; the filter below still applies
    dlg.isVisible = true
    return dlg.files.filter { it.isImageFile() }.map { it.readBytes() }
}
