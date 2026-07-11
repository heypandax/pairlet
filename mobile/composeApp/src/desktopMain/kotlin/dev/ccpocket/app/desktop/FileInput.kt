package dev.ccpocket.app.desktop

import dev.ccpocket.app.media.PickedFile
import dev.ccpocket.app.media.mediaTypeFor
import dev.ccpocket.protocol.MAX_UPLOAD_BYTES
import dev.ccpocket.protocol.isImageFile
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Attachments picked/dropped on desktop, split by pipeline: images ride the existing inline
 * SendPrompt path (compressed thumbnails), everything else chunk-streams into the session's
 * workspace inbox (issue #90).
 */
class PickedAttachments(val images: List<ByteArray>, val files: List<PickedFile>)

/** Route a set of on-disk files (attach dialog or chat-pane drop) into the two pipelines. */
fun pickedFromDisk(files: List<File>): PickedAttachments {
    val images = ArrayList<ByteArray>()
    val docs = ArrayList<PickedFile>()
    for (f in files) {
        if (!f.isFile) continue // directories aren't uploadable (v1)
        if (isImageFile(f.name)) {
            runCatching { images.add(f.readBytes()) }
        } else {
            docs.add(toPicked(f))
        }
    }
    return PickedAttachments(images, docs)
}

/** Load one file for upload. Over-cap or unreadable files come back with empty bytes — the
 *  repository turns those into an immediate failed chip instead of streaming garbage. */
fun toPicked(f: File): PickedFile {
    val size = f.length()
    val mediaType = mediaTypeFor(f.name)
    if (size > MAX_UPLOAD_BYTES) return PickedFile(f.name, size, ByteArray(0), mediaType)
    val bytes = runCatching { f.readBytes() }.getOrElse { ByteArray(0) }
    return PickedFile(f.name, size, bytes, mediaType)
}

/** One AWT dialog for ANY attachment (images and files alike). BLOCKS — call on Dispatchers.IO,
 *  same contract as [pickImages]. */
fun pickAttachments(): PickedAttachments {
    val dialog = FileDialog(null as Frame?, "Attach files", FileDialog.LOAD)
    dialog.isMultipleMode = true
    dialog.isVisible = true
    return pickedFromDisk(dialog.files?.toList() ?: emptyList())
}
