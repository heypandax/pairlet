package dev.ccpocket.app.share

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.QuickLook.QLPreviewController
import platform.QuickLook.QLPreviewControllerDataSourceProtocol
import platform.QuickLook.QLPreviewItemProtocol
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.popoverPresentationController
import platform.darwin.NSInteger
import platform.darwin.NSObject

actual val exportIsSaveDialog: Boolean = false

/** The topmost presentable VC (same walk as OpenUrl.ios.kt — an open sheet must not swallow ours). */
private fun topViewController(): UIViewController? {
    var top = (UIApplication.sharedApplication.keyWindow ?: UIApplication.sharedApplication.windows.firstOrNull() as? UIWindow)
        ?.rootViewController ?: return null
    while (top.presentedViewController != null) top = top.presentedViewController!!
    return top
}

/** Materialize the bytes under NSTemporaryDirectory() so the share sheet / QuickLook get a real
 *  file URL carrying the real file NAME (receivers keep it; QuickLook picks its renderer by it). */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun tempFileUrl(name: String, bytes: ByteArray): NSURL? {
    val dir = NSTemporaryDirectory() + "cc-pocket-export"
    NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
    val path = "$dir/${name.substringAfterLast('/')}"
    val data = if (bytes.isEmpty()) NSData() else bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
    if (!data.writeToFile(path, atomically = true)) return null
    return NSURL.fileURLWithPath(path)
}

/** UIActivityViewController: save to Files, AirDrop, WeChat/Feishu … in one gesture (issue #67). */
@OptIn(ExperimentalForeignApi::class)
actual fun shareFile(name: String, bytes: ByteArray, mediaType: String?) {
    val url = tempFileUrl(name, bytes) ?: return
    val top = topViewController() ?: return
    val avc = UIActivityViewController(activityItems = listOf(url), applicationActivities = null)
    // iPad presents this as a popover and CRASHES without an anchor — center it on the presenter
    avc.popoverPresentationController?.let { pop ->
        pop.sourceView = top.view
        pop.sourceRect = top.view.bounds.useContents { CGRectMake(size.width / 2, size.height / 2, 0.0, 0.0) }
    }
    top.presentViewController(avc, animated = true, completion = null)
}

// QLPreviewController.dataSource is WEAK — this global keeps ours alive while the preview is up
// (replaced on the next preview; a stale one is a few hundred bytes, not a leak class).
private var activeQlSource: QlSource? = null

private class QlSource(val url: NSURL) : NSObject(), QLPreviewControllerDataSourceProtocol {
    override fun numberOfPreviewItemsInPreviewController(controller: QLPreviewController): NSInteger = 1
    @Suppress("CAST_NEVER_SUCCEEDS") // NSURL conforms to QLPreviewItem via a QuickLook category
    override fun previewController(controller: QLPreviewController, previewItemAtIndex: NSInteger): QLPreviewItemProtocol =
        url as QLPreviewItemProtocol
}

/** QuickLook (issue #79): native xlsx/docx/pptx/pdf rendering — no home-grown office viewer. */
actual fun previewFile(name: String, bytes: ByteArray, mediaType: String?): Boolean {
    val url = tempFileUrl(name, bytes) ?: return false
    @Suppress("CAST_NEVER_SUCCEEDS")
    if (!QLPreviewController.canPreviewItem(url as QLPreviewItemProtocol)) return false
    val top = topViewController() ?: return false
    val source = QlSource(url)
    activeQlSource = source
    val ql = QLPreviewController()
    ql.dataSource = source
    top.presentViewController(ql, animated = true, completion = null)
    return true
}
