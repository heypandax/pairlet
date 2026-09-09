package dev.ccpocket.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.scene.web.WebView
import java.awt.EventQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JPanel

/** JFXPanel owns the Swing/JavaFX thread handoff; neither the native engine nor scripts get any
 * repository objects. Each mount owns a fresh engine and cancels it on file/tab/viewer changes. */
internal class HtmlPreviewPanel(private val onError: () -> Unit) : JFXPanel() {
    private val disposed = AtomicBoolean(false)
    private var document: String? = null // EDT only
    private var browser: WebView? = null // FX thread only

    init {
        Platform.setImplicitExit(false) // closing one preview must not shut down the shared FX toolkit
        Platform.runLater {
            if (!disposed.get()) runCatching {
                val view = WebView()
                view.engine.setCreatePopupHandler { null }
                view.engine.setOnError { reportError() }
                browser = view
                scene = Scene(StackPane(view))
            }.onFailure { reportError() }
        }
    }

    fun load(document: String) {
        if (this.document == document || disposed.get()) return
        this.document = document
        Platform.runLater {
            if (!disposed.get()) runCatching { browser?.engine?.loadContent(document, "text/html") }
                .onFailure { reportError() }
        }
    }

    fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        Platform.runLater {
            browser?.engine?.apply { loadWorker.cancel(); load(null); setOnError(null) }
            scene = null
            browser = null
        }
    }

    private fun reportError() = EventQueue.invokeLater { if (!disposed.get()) onError() }
}

@Composable
internal actual fun HtmlPreview(document: String, modifier: Modifier, onError: () -> Unit) {
    val holder = remember { arrayOfNulls<HtmlPreviewPanel>(1) }
    DisposableEffect(Unit) {
        onDispose { holder[0]?.dispose(); holder[0] = null }
    }
    SwingPanel(
        modifier = modifier,
        background = Color.White,
        factory = {
            runCatching { HtmlPreviewPanel(onError).also { holder[0] = it } }
                .getOrElse { EventQueue.invokeLater(onError); JPanel() }
        },
        update = { panel -> (panel as? HtmlPreviewPanel)?.load(document) },
    )
}
