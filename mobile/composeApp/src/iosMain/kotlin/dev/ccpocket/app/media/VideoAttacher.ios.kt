package dev.ccpocket.app.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.play
import platform.AVKit.AVPlayerViewController
import platform.Foundation.NSURL
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTTypeMovie

/** UIDocumentPickerViewController opening movie UTTypes (asCopy, multi-select) — reuses the shared
 *  [PickerDelegate]/[readPickedUrl] so a picked video streams up exactly like any file. */
@Composable
actual fun rememberVideoAttacher(onPicked: (List<PickedFile>) -> Unit): () -> Unit = remember {
    {
        val top = topViewController()
        if (top != null) {
            val delegate = PickerDelegate(onPicked)
            activePickerDelegate = delegate
            val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeMovie), asCopy = true)
            picker.allowsMultipleSelection = true
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
