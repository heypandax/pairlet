package dev.ccpocket.app

import platform.Foundation.NSURL
import platform.SafariServices.SFSafariViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow

/** In-app browser sheet (SFSafariViewController) — swipe-down returns straight to the chat. Falls back
 *  to Safari proper only when no view controller is available to present from. */
actual fun openWebUrl(url: String) {
    val nsUrl = NSURL.URLWithString(url) ?: return
    val root = (UIApplication.sharedApplication.keyWindow ?: UIApplication.sharedApplication.windows.firstOrNull() as? UIWindow)
        ?.rootViewController
    if (root == null) {
        UIApplication.sharedApplication.openURL(nsUrl)
        return
    }
    // present from the topmost VC so an already-presented sheet doesn't swallow the browser
    var top: UIViewController = root
    while (top.presentedViewController != null) top = top.presentedViewController!!
    top.presentViewController(SFSafariViewController(uRL = nsUrl), animated = true, completion = null)
}
