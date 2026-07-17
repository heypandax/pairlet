package dev.ccpocket.app.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.ProcessTtyConnector
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.emulator.ColorPalette
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import java.awt.Dimension
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.event.FocusEvent
import java.awt.event.FocusListener
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JScrollBar
import javax.swing.SwingUtilities
import javax.swing.plaf.basic.BasicScrollBarUI

/**
 * The embedded terminal's real engine (issue #153): a local PTY (pty4j) driving JediTerm's Swing
 * widget, themed to the design's near-black ink + the app's semantic hues. Everything here is
 * desktop-only and failure-tolerant: [spawn] returns null on any trouble (headless test JVMs,
 * missing natives, a vanished cwd) and the panel chrome keeps working with an inert body.
 *
 * JediTerm is LGPL-3.0 and consumed as an unmodified separate jar; pty4j bundles its natives in-jar.
 */
internal object JediTermEngine {

    /** [onCmdJ]: ⌘J/Ctrl+J pressed while the SHELL owns the keyboard — AWT keeps those keystrokes,
     *  so the window-level Compose binding never sees them; the engine forwards instead. */
    fun spawn(cwd: String, onCmdJ: () -> Unit): EmbeddedTerminal? = runCatching {
        if (GraphicsEnvironment.isHeadless()) return@runCatching null
        val dir = TerminalLauncher.resolve(cwd).takeIf { it.isDirectory } ?: return@runCatching null
        val process = PtyProcessBuilder(shellCommand())
            .setEnvironment(shellEnvironment())
            .setDirectory(dir.absolutePath)
            .setInitialColumns(120)
            .setInitialRows(28)
            .start()
        // The shell is LIVE from here on: if any wiring step below throws, the outer runCatching
        // alone would swallow it and orphan an invisible login shell (plus a possibly-registered
        // global key dispatcher). The catch reclaims both before falling back to null.
        var dispatcher: KeyEventDispatcher? = null
        try {
            val widget = object : JediTermWidget(CcTermSettings) {
                override fun createScrollBar(): JScrollBar = darkScrollBar()
            }
            widget.ttyConnector = PtyTtyConnector(process)
            val session = JediTermSession(widget, process)
            // running ⟷ the shell process: `exit` (or a crash) drops the strip's live dot honestly
            process.onExit().thenRun { session.markExited() }
            // the focused state drives the panel's terracotta inner ring
            widget.terminalPanel.addFocusListener(object : FocusListener {
                override fun focusGained(e: FocusEvent?) = session.markFocus(true)
                override fun focusLost(e: FocusEvent?) = session.markFocus(false)
            })
            // ⌘J from INSIDE the terminal (see onCmdJ doc above); scoped to this widget's focus subtree
            val mask = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
            val cmdJDispatcher = KeyEventDispatcher { e ->
                if (e.id == KeyEvent.KEY_PRESSED && e.keyCode == KeyEvent.VK_J && (e.modifiersEx and mask) == mask) {
                    val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                    if (owner != null && SwingUtilities.isDescendingFrom(owner, widget)) {
                        onCmdJ()
                        return@KeyEventDispatcher true
                    }
                }
                false
            }
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(cmdJDispatcher)
            dispatcher = cmdJDispatcher
            session.dispatcher = cmdJDispatcher
            widget.start()
            session
        } catch (t: Throwable) {
            dispatcher?.let { KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(it) }
            runCatching { process.destroy() }
            null
        }
    }.getOrNull()

    private fun shellCommand(): Array<String> {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") -> arrayOf(System.getenv("COMSPEC") ?: "cmd.exe")
            else -> {
                val sh = System.getenv("SHELL")?.takeIf { it.isNotBlank() }
                    ?: if (os.contains("mac")) "/bin/zsh" else "/bin/bash"
                arrayOf(sh, "-l") // login shell: the user's PATH/aliases, like their own terminal
            }
        }
    }

    private fun shellEnvironment(): Map<String, String> {
        val env = HashMap(System.getenv())
        env["TERM"] = "xterm-256color"
        return env
    }

    /** JediTerm's stock Swing scrollbar is bright LAF chrome on our near-black — restyle it slim/dark. */
    private fun darkScrollBar(): JScrollBar {
        val bar = JScrollBar()
        bar.preferredSize = Dimension(9, 0)
        bar.setUI(object : BasicScrollBarUI() {
            override fun configureScrollBarColors() {
                thumbColor = java.awt.Color(0x2A, 0x2E, 0x33)
                trackColor = java.awt.Color(0x0B, 0x0C, 0x0D)
                thumbDarkShadowColor = trackColor
                thumbHighlightColor = trackColor
                thumbLightShadowColor = trackColor
            }
            override fun createDecreaseButton(orientation: Int) = invisibleButton()
            override fun createIncreaseButton(orientation: Int) = invisibleButton()
            private fun invisibleButton() = JButton().apply {
                preferredSize = Dimension(0, 0)
                minimumSize = Dimension(0, 0)
                maximumSize = Dimension(0, 0)
            }
        })
        return bar
    }
}

/** pty4j process ⟷ JediTerm: streams via [ProcessTtyConnector], resize forwarded to the PTY. */
private class PtyTtyConnector(private val process: PtyProcess) :
    ProcessTtyConnector(process, Charsets.UTF_8) {
    override fun getName(): String = "local"
    override fun resize(termSize: TermSize) {
        runCatching { process.winSize = WinSize(termSize.columns, termSize.rows) }
    }
}

private class JediTermSession(
    private val widget: JediTermWidget,
    private val process: PtyProcess,
) : EmbeddedTerminal {
    var dispatcher: KeyEventDispatcher? = null

    private var runningState by mutableStateOf(true)
    private var focusedState by mutableStateOf(false)

    override val running: Boolean get() = runningState
    override val focused: Boolean get() = focusedState
    override fun view(): JComponent = widget

    fun markExited() { runningState = false }
    fun markFocus(v: Boolean) { focusedState = v }

    override fun focus() {
        SwingUtilities.invokeLater { widget.requestFocusInWindow() }
    }

    override fun dispose() {
        dispatcher?.let { KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(it) }
        dispatcher = null
        runCatching { widget.close() } // stops the emulator + closes the tty (destroys the process)
        runCatching { if (process.isAlive) process.destroy() }
        runningState = false
    }
}

// ── theme: the design's near-black ink + the app's semantic hues as the ANSI 16 ──

private object CcTermSettings : DefaultSettingsProvider() {
    private val font: Font by lazy {
        runCatching {
            CcTermSettings::class.java.classLoader.getResourceAsStream("font/JetBrainsMono-Regular.ttf")!!
                .use { Font.createFont(Font.TRUETYPE_FONT, it) }
        }.getOrNull()?.deriveFont(13f) ?: Font(Font.MONOSPACED, Font.PLAIN, 13)
    }

    override fun getTerminalFont(): Font = font
    override fun getTerminalFontSize(): Float = 13f
    override fun getDefaultBackground(): TerminalColor = TerminalColor(0x0B, 0x0C, 0x0D) // design ink
    override fun getDefaultForeground(): TerminalColor = TerminalColor(0xEC, 0xED, 0xEE) // dark-palette tx
    override fun getTerminalColorPalette(): ColorPalette = CcAnsiPalette
    override fun useAntialiasing(): Boolean = true
}

private object CcAnsiPalette : ColorPalette() {
    // 0-7 normal, 8-15 bright — danger/ok/warn/info/codex are the app's own tokens
    private val colors = arrayOf(
        com.jediterm.core.Color(0x16, 0x18, 0x1B), // black — surface
        com.jediterm.core.Color(0xE5, 0x60, 0x4D), // red — danger
        com.jediterm.core.Color(0x4F, 0xB4, 0x77), // green — ok
        com.jediterm.core.Color(0xE0, 0xA9, 0x3B), // yellow — warn
        com.jediterm.core.Color(0x5B, 0x9B, 0xD5), // blue — info
        com.jediterm.core.Color(0xB1, 0x80, 0xD7), // magenta
        com.jediterm.core.Color(0x3F, 0xB5, 0xAC), // cyan — codex teal
        com.jediterm.core.Color(0x9B, 0xA1, 0xA6), // white — tx2
        com.jediterm.core.Color(0x6B, 0x71, 0x77), // bright black — muted
        com.jediterm.core.Color(0xF0, 0x7A, 0x66),
        com.jediterm.core.Color(0x6B, 0xCF, 0x97),
        com.jediterm.core.Color(0xF0, 0xC3, 0x6B),
        com.jediterm.core.Color(0x7F, 0xB5, 0xE8),
        com.jediterm.core.Color(0xC7, 0x9B, 0xF2),
        com.jediterm.core.Color(0x5F, 0xD3, 0xC9),
        com.jediterm.core.Color(0xEC, 0xED, 0xEE), // bright white — tx
    )

    override fun getForegroundByColorIndex(colorIndex: Int): com.jediterm.core.Color = colors[colorIndex]
    override fun getBackgroundByColorIndex(colorIndex: Int): com.jediterm.core.Color = colors[colorIndex]
}
