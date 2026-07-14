package dev.ccpocket.app.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.play
import platform.AVKit.AVPlayerViewController
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UniformTypeIdentifiers.UTTypeMovie
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

// PHPickerViewController.delegate is WEAK — this global keeps ours alive while the picker is up
// (same pattern as FileAttacher's activePickerDelegate).
internal var activeVideoPickerDelegate: VideoPickerDelegate? = null

/** Copy a provider-owned URL into our own temp dir. The URL handed to the
 *  loadFileRepresentation callback is deleted the moment the callback returns, so the copy MUST
 *  happen inside it. The copy lives in NSTemporaryDirectory and survives the session, which keeps
 *  [PickedFile.localUri] playable via AVPlayer (#98) just like the document picker's asCopy URL. */
@OptIn(ExperimentalForeignApi::class)
private fun copyToOwnTemp(url: NSURL): NSURL? {
    val name = url.lastPathComponent ?: "video.mov"
    val dir = NSTemporaryDirectory() + "cc-pocket-picked-" + NSUUID().UUIDString
    val ok = NSFileManager.defaultManager.createDirectoryAtPath(
        dir, withIntermediateDirectories = true, attributes = null, error = null,
    )
    if (!ok) return null
    val dest = NSURL.fileURLWithPath("$dir/$name")
    return if (NSFileManager.defaultManager.copyItemAtURL(url, toURL = dest, error = null)) dest else null
}

@OptIn(ExperimentalForeignApi::class)
internal class VideoPickerDelegate(val onPicked: (List<PickedFile>) -> Unit) : NSObject(), PHPickerViewControllerDelegateProtocol {
    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, completion = null)
        val results = didFinishPicking.filterIsInstance<PHPickerResult>()
        if (results.isEmpty()) { // cancelled or nothing picked
            activeVideoPickerDelegate = null
            return
        }
        // Every loadFileRepresentation callback lands on a background thread; each copies its file
        // out, then hops to the main queue where the slots fill in order and the LAST one to land
        // delivers the whole batch — matching the document picker's single onPicked(List) call.
        val slots = arrayOfNulls<PickedFile>(results.size)
        var remaining = results.size
        results.forEachIndexed { index, result ->
            result.itemProvider.loadFileRepresentationForTypeIdentifier(UTTypeMovie.identifier) { url, _ ->
                val picked = url?.let { copyToOwnTemp(it) }?.let { readPickedUrl(it) }
                dispatch_async(dispatch_get_main_queue()) {
                    slots[index] = picked
                    remaining -= 1
                    if (remaining == 0) {
                        val files = slots.filterNotNull()
                        if (files.isNotEmpty()) onPicked(files)
                        activeVideoPickerDelegate = null
                    }
                }
            }
        }
    }
}

/** PHPickerViewController filtered to videos (multi-select) — reads the photo library without any
 *  permission prompt (out-of-process picker), then copies each pick to a temp file and reuses the
 *  shared [readPickedUrl] so a picked video streams up exactly like any file (#148). */
@Composable
actual fun rememberVideoAttacher(onPicked: (List<PickedFile>) -> Unit): () -> Unit = remember {
    {
        val top = topViewController()
        if (top != null) {
            val config = PHPickerConfiguration()
            config.filter = PHPickerFilter.videosFilter
            config.selectionLimit = 0 // 0 = no limit (multi-select)
            val delegate = VideoPickerDelegate(onPicked)
            activeVideoPickerDelegate = delegate
            val picker = PHPickerViewController(configuration = config)
            picker.delegate = delegate
            top.presentViewController(picker, animated = true, completion = null)
        }
    }
}

/** Present AVPlayerViewController modally over the app for a just-picked video's local file URL (#98). */
@Composable
actual fun rememberLocalVideoOpener(): (String) -> Unit = remember {
    { uri ->
        val url = NSURL.URLWithString(uri)
        val top = topViewController()
        if (url != null && top != null) {
            val player = AVPlayer(uRL = url)
            val vc = AVPlayerViewController()
            vc.player = player
            top.presentViewController(vc, animated = true) { player.play() }
        }
    }
}
