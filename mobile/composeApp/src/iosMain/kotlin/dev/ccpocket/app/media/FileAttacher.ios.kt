package dev.ccpocket.app.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.ccpocket.protocol.MAX_UPLOAD_BYTES
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSObject
import platform.posix.memcpy

/** The topmost presentable VC (same walk as FileExport.ios.kt — an open sheet must not swallow ours). */
private fun topViewController(): UIViewController? {
    var top = (UIApplication.sharedApplication.keyWindow ?: UIApplication.sharedApplication.windows.firstOrNull() as? UIWindow)
        ?.rootViewController ?: return null
    while (top.presentedViewController != null) top = top.presentedViewController!!
    return top
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

// UIDocumentPickerViewController.delegate is WEAK — this global keeps ours alive while the picker
// is up (replaced on the next launch; the same pattern as FileExport's activeQlSource).
private var activePickerDelegate: PickerDelegate? = null

@OptIn(ExperimentalForeignApi::class)
private class PickerDelegate(val onPicked: (List<PickedFile>) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        val files = didPickDocumentsAtURLs.filterIsInstance<NSURL>().mapNotNull { url -> read(url) }
        if (files.isNotEmpty()) onPicked(files)
        activePickerDelegate = null
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        activePickerDelegate = null
    }

    /** asCopy=true → the URL is a sandbox temp copy; read it directly (no security scope dance). */
    private fun read(url: NSURL): PickedFile? {
        val path = url.path ?: return null
        val name = url.lastPathComponent ?: "file"
        val mediaType = mediaTypeFor(name)
        val size = (NSFileManager.defaultManager.attributesOfItemAtPath(path, null)
            ?.get(NSFileSize) as? NSNumber)?.longLongValue ?: -1L
        // over-cap pick: don't load it — the repository turns the empty-bytes PickedFile + size
        // into an immediate "larger than 200 MB" failed chip
        if (size > MAX_UPLOAD_BYTES) return PickedFile(name, size, ByteArray(0), mediaType)
        val data = NSData.dataWithContentsOfURL(url) ?: return null
        val bytes = data.toByteArray()
        return PickedFile(name, if (size >= 0) size else bytes.size.toLong(), bytes, mediaType)
    }
}

/** UIDocumentPickerViewController (Files app / iCloud Drive / providers), asCopy, multi-select. */
@Composable
actual fun rememberFileAttacher(onPicked: (List<PickedFile>) -> Unit): () -> Unit = remember {
    {
        val top = topViewController()
        if (top != null) {
            val delegate = PickerDelegate(onPicked)
            activePickerDelegate = delegate
            val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeItem), asCopy = true)
            picker.allowsMultipleSelection = true
            picker.delegate = delegate
            top.presentViewController(picker, animated = true, completion = null)
        }
    }
}
