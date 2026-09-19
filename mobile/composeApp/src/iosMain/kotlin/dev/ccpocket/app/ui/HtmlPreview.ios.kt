package dev.ccpocket.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSError
import platform.Foundation.NSURLErrorCancelled
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject

private class PreviewNavigationDelegate(private val onError: () -> Unit) : NSObject(), WKNavigationDelegateProtocol {
    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailNavigation: WKNavigation?, withError: NSError) {
        if (withError.code != NSURLErrorCancelled) onError()
    }
    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailProvisionalNavigation: WKNavigation?, withError: NSError) {
        if (withError.code != NSURLErrorCancelled) onError()
    }
    override fun webViewWebContentProcessDidTerminate(webView: WKWebView) = onError()
}

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun HtmlPreview(document: String, modifier: Modifier, onError: () -> Unit) {
    val loaded = remember { arrayOfNulls<String>(1) }
    val reportError by rememberUpdatedState(onError)
    val delegate = remember { PreviewNavigationDelegate { reportError() } } // WK holds its delegate weakly
    UIKitView(
        modifier = modifier,
        factory = {
            val configuration = WKWebViewConfiguration().apply {
                websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore()
            }
            WKWebView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), configuration = configuration).apply { navigationDelegate = delegate }
        },
        update = { view ->
            if (loaded[0] != document) {
                loaded[0] = document
                view.loadHTMLString(document, baseURL = null)
            }
        },
        onRelease = { view -> view.navigationDelegate = null; view.stopLoading(); view.loadHTMLString("", baseURL = null) },
        // DRAFT for #390, never compiled or run on a device — see docs/iterations/2026-09-19-claude-code/
        // issue-390-html-scroll.md. The default interop mode is Cooperative: Compose holds a touch that
        // lands on this view for a moment to see whether a Compose ancestor wants it, and only then
        // replays it to WKWebView. Nothing above this preview competes for a drag (the file viewer is a
        // full-screen route; every ancestor is a plain Box/Column and the chat list is not composed at
        // all), so that first refusal buys nothing and can cost the flick that should have scrolled the
        // document. NonCooperative hands touches straight to the web view.
        //
        // Scope is exactly this view's bounds: the 预览/源码 buttons and the ← back button sit outside it
        // and keep working. The trade is that Compose can no longer intercept a gesture over the preview,
        // so a future swipe-to-dismiss covering this area would not fire — there is none today
        // (SystemBackHandler is a no-op on iOS; navigation is the ← button).
        properties = UIKitInteropProperties(interactionMode = UIKitInteropInteractionMode.NonCooperative),
    )
}
