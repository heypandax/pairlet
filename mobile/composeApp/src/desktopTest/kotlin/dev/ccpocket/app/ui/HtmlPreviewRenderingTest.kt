package dev.ccpocket.app.ui

import javafx.application.Platform
import javafx.scene.layout.StackPane
import javafx.scene.web.WebView
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeFalse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises the shipped WebKit/JFXPanel, not a mock renderer. A display is needed (local desktop
 * tests already use Skia); explicitly skipped on truly headless hosts. No daemon or network traffic. */
class HtmlPreviewRenderingTest {
    private fun <T> fx(block: () -> T): T {
        val result = CompletableFuture<T>()
        Platform.runLater { runCatching(block).fold(result::complete, result::completeExceptionally) }
        return result.get(15, TimeUnit.SECONDS)
    }

    @Test
    fun rendersCssAndJavascriptKeepsSourceIsolatedAndCanReopen() {
        assumeFalse(GraphicsEnvironment.isHeadless())
        var error: String? = null
        repeat(2) { generation ->
            lateinit var panel: HtmlPreviewPanel
            EventQueue.invokeAndWait { panel = HtmlPreviewPanel { error = "native preview failed" } }
            try {
                // Attribute-breakout payload must stay inside srcdoc; & entities must decode once.
                val html = """<!doctype html><html><head><style>#card{color:rgb(12, 34, 56);display:flex}</style></head>
<body><div id="card">你好 &amp; HTML $generation</div><div data-value='"><script>window.top.escaped=true</script>'></div>
<script>
let isolated=false; try { parent.document.body; } catch(e) { isolated=true; }
setInterval(() => parent.postMessage(document.getElementById('card').textContent+'|'+getComputedStyle(document.getElementById('card')).color+'|'+getComputedStyle(document.getElementById('card')).display+'|'+isolated, '*'), 20);
</script></body></html>"""
                EventQueue.invokeAndWait { panel.load(htmlPreviewDocument(html)) }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                var result: String? = null
                while (System.nanoTime() < deadline && result == null) {
                    result = fx {
                        val view = (panel.scene?.root as? StackPane)?.children?.firstOrNull() as? WebView
                        view?.engine?.executeScript("if(!window.listening){window.listening=true;window.addEventListener('message',e=>window.previewResult=e.data);} window.previewResult || null") as? String
                    }
                    if (result == null) Thread.sleep(25)
                }
                assertEquals("你好 & HTML $generation|rgb(12, 34, 56)|flex|true", result)
                assertTrue(fx {
                    val view = (panel.scene.root as StackPane).children.single() as WebView
                    view.engine.executeScript("window.escaped !== true && document.querySelectorAll('iframe').length === 1") as Boolean
                })
                assertNull(error)
            } finally {
                EventQueue.invokeAndWait { panel.dispose() }
                fx { assertNull(panel.scene) }
            }
        }
    }
}
