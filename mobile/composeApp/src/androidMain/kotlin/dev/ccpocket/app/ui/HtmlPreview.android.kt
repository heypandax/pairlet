package dev.ccpocket.app.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.RenderProcessGoneDetail
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal actual fun HtmlPreview(document: String, modifier: Modifier, onError: () -> Unit) {
    val reportError by rememberUpdatedState(onError)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            runCatching { WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.setSupportMultipleWindows(false)
                webViewClient = object : WebViewClient() {
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) reportError()
                    }
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        reportError()
                        return true // composition removes and destroys this view via onRelease
                    }
                }
            } }.getOrElse { reportError(); FrameLayout(context) }
        },
        update = { view ->
            if (view is WebView && view.tag != document) {
                view.tag = document
                // Null base URL gives the preview an opaque origin, never a daemon/local file URL.
                view.loadDataWithBaseURL(null, document, "text/html", "UTF-8", null)
            }
        },
        onRelease = { view -> if (view is WebView) { view.stopLoading(); view.destroy() } },
    )
}
