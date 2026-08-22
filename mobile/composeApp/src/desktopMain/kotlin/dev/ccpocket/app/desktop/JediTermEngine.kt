package dev.ccpocket.app.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.ProcessTtyConnector
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.emulator.ColorPalette
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalTextBuffer
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import com.jediterm.terminal.ui.settings.SettingsProvider
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
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JScrollBar
import javax.swing.SwingUtilities
import javax.swing.plaf.basic.BasicScrollBarUI
// this package already has a @Composable TerminalPanel — alias JediTerm's away from it
import com.jediterm.terminal.ui.TerminalPanel as JediTermPanel

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

                /** The only seam JediTerm leaves for glyph fallback — see [CjkFallback] for why we need one.
                 *  Note the argument order flip: the hook hands us (settings, style, buffer), the panel
                 *  constructor wants (settings, buffer, style). */
                override fun createTerminalPanel(
                    settings: SettingsProvider,
                    styleState: StyleState,
                    textBuffer: TerminalTextBuffer,
                ): JediTermPanel = object : JediTermPanel(settings, textBuffer, styleState) {
                    override fun getFontToDisplay(buf: CharArray, start: Int, end: Int, style: TextStyle): Font {
                        // super picks plain/bold/italic of the ONE configured font; we only step in when
                        // that font has no glyph for this cluster, so ASCII keeps rendering in JetBrains Mono
                        val primary = super.getFontToDisplay(buf, start, end, style)
                        if (primaryCanDraw(primary, buf, start, end)) return primary
                        return CjkFallback.pick(buf, start, end, primary) ?: primary
                    }
                }
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

    private fun shellCommand(): Array<String> =
        shellCommandFor(System.getProperty("os.name").lowercase(), System.getenv("COMSPEC"), System.getenv("SHELL"))

    private fun shellEnvironment(): Map<String, String> {
        val env = HashMap(System.getenv())
        env["TERM"] = "xterm-256color"
        // Windows has no locale env; cmd.exe's code page is set on the command line instead (see shellCommandFor)
        if (!System.getProperty("os.name").lowercase().contains("win")) {
            utf8LocaleFor(env, installedLocales, Locale.getDefault())?.let { env["LANG"] = it }
        }
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

// ── issue #283: the three links that have to be UTF-8 for CJK to survive ──

/**
 * The shell argv. On Windows this also fixes the console code page.
 *
 * Why: conhost/ConPTY decodes whatever bytes a child writes using the console's *code page*, and on a
 * Chinese Windows that defaults to 936 (GBK). Everything people actually run in this dock — claude, git,
 * node — emits UTF-8, so each Chinese character got torn into two GBK blobs before it ever reached us.
 * `chcp 65001` moves the console to UTF-8 for both directions (ReadConsole picks it up too, so IME input
 * is covered by the same switch).
 *
 * The quoting is verified, not hoped for: pty4j's `WinPtyProcess.joinCmdArgs` renders this argv as
 * `cmd.exe /K "chcp 65001>nul"`, and because the string holds a `>` (a "special character") cmd falls to
 * its documented rule 2 — strip the leading quote and the last quote — leaving `chcp 65001>nul` to run
 * before the prompt. `>nul` swallows the "Active code page: 65001" banner.
 */
internal fun shellCommandFor(os: String, comspec: String?, shell: String?): Array<String> = when {
    os.contains("win") -> arrayOf(comspec?.takeIf { it.isNotBlank() } ?: "cmd.exe", "/K", "chcp 65001>nul")
    else -> {
        val sh = shell?.takeIf { it.isNotBlank() } ?: if (os.contains("mac")) "/bin/zsh" else "/bin/bash"
        arrayOf(sh, "-l") // login shell: the user's PATH/aliases, like their own terminal
    }
}

/**
 * A UTF-8 `LANG` to inject, or null to leave the environment alone.
 *
 * Why: a GUI app gets launchd's/the desktop session's environment, not a terminal's — `launchctl getenv
 * LANG` is empty here, so nothing downstream sets one. A shell that lands in the C/POSIX locale mangles
 * non-ASCII both ways: BSD `ls` turns Chinese filenames into `?`, and zsh's line editor can't compose
 * multi-byte IME input. (Darwin 25 happens to default to C.UTF-8 already, which is why macOS doesn't show
 * this today — older macOS and Linux still do.)
 *
 * Only fills a gap: if the user set any of LC_ALL / LC_CTYPE / LANG we respect it, even a non-UTF-8 one.
 * Candidates are checked against `locale -a` because naming a locale the system hasn't generated makes
 * every libc program print a warning — worse than leaving it unset.
 */
internal fun utf8LocaleFor(env: Map<String, String>, available: Set<String>, locale: Locale): String? {
    if (listOf("LC_ALL", "LC_CTYPE", "LANG").any { !env[it].isNullOrBlank() }) return null
    val own = "${locale.language}_${locale.country}.UTF-8".takeIf {
        locale.language.isNotBlank() && locale.country.isNotBlank()
    }
    // the user's own locale first (keeps message language), then the portable UTF-8 fallbacks
    return listOfNotNull(own, "C.UTF-8", "en_US.UTF-8").firstOrNull { it in available }
}

/** `locale -a`, once per JVM and only consulted when the environment had no locale at all. */
private val installedLocales: Set<String> by lazy {
    runCatching {
        val p = ProcessBuilder("locale", "-a").redirectErrorStream(true).start()
        val names = p.inputStream.bufferedReader().use { it.readLines() }.map { it.trim() }.filter { it.isNotEmpty() }
        if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
        names.toSet()
    }.getOrDefault(emptySet())
}

/**
 * Whether the terminal's own font can draw this cluster, i.e. whether we can skip the fallback lookup.
 *
 * The ASCII short-circuit matters: JediTerm calls [JediTermPanel.getFontToDisplay] once per grapheme
 * cluster per repaint, and terminal output is overwhelmingly ASCII — which JetBrains Mono covers whole.
 */
internal fun primaryCanDraw(primary: Font, buf: CharArray, start: Int, end: Int): Boolean {
    for (i in start until end) if (buf[i].code >= 0x80) return primary.canDisplayUpTo(buf, start, end) < 0
    return true
}

/**
 * The families to try, in order, when the terminal font has no glyph — installed ones only.
 *
 * The filter is load-bearing: `Font("a name nobody installed")` silently degrades to the Dialog composite
 * rather than failing, and that composite answers `canDisplay('中') == true`, so an unfiltered list would
 * happily "succeed" into a proportional font. Logical `Monospaced` is the deliberate last resort — it is
 * an AWT composite with the platform's own fallback slots, so it covers machines that have a CJK font
 * under a name nobody thought to list.
 */
internal fun cjkFontChain(installed: Set<String>, candidates: List<String>): List<String> =
    candidates.filter { it in installed } + Font.MONOSPACED

/**
 * Per-glyph font fallback for the embedded terminal (issue #283).
 *
 * Two facts force this to exist. JetBrains Mono, the dock's font, has *no* CJK coverage at all — not even
 * fullwidth `，。` — and `Font.createFont` yields a plain physical font, so `createGlyphVector('中')` comes
 * back as glyph 0, the missing-glyph box. (Registering it with the GraphicsEnvironment does not help; only
 * fonts the OS itself installs get wrapped in a fallback composite.) And JediTerm does no substitution of
 * its own: `TerminalPanel` holds exactly four Fonts derived from `getTerminalFont()` and never consults
 * `canDisplay`. So Chinese rendered as rows of tofu — the mac half of "乱码".
 *
 * We only ever *add* a font for characters the primary cannot draw, so this is a correctness fix, not a
 * restyling: every character JetBrains Mono can render still renders in JetBrains Mono.
 */
internal object CjkFallback {
    private val candidates = listOf(
        "PingFang SC", "Hiragino Sans GB", "Heiti SC", "Songti SC",                     // macOS
        "Microsoft YaHei", "Microsoft JhengHei", "SimSun", "NSimSun", "Malgun Gothic",  // Windows
        "MS Gothic", "Yu Gothic",                                                       // Windows (JP)
        "Noto Sans Mono CJK SC", "Noto Sans CJK SC", "Source Han Sans SC",              // Linux
        "Sarasa Mono SC", "WenQuanYi Micro Hei", "Droid Sans Fallback",                 // Linux
    )

    private val chain: List<String> by lazy {
        val installed = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        }.getOrDefault(emptySet())
        cjkFontChain(installed, candidates)
    }

    // getFontToDisplay runs on the EDT during paint, so no synchronization — but it runs per cluster, so
    // the derived Fonts are memoized instead of re-derived on every cell.
    private val made: Array<Array<Font?>> by lazy { Array(chain.size) { arrayOfNulls<Font>(4) } }
    private var madeAt: Float = -1f

    /** First candidate that can draw [start, end), matched to the primary's size and style; null if none can. */
    fun pick(buf: CharArray, start: Int, end: Int, primary: Font): Font? {
        if (madeAt != primary.size2D) {
            made.forEach { it.fill(null) }
            madeAt = primary.size2D
        }
        val style = primary.style and 3 // PLAIN/BOLD/ITALIC/BOLD|ITALIC
        for (i in chain.indices) {
            val font = made[i][style]
                ?: Font(chain[i], style, 1).deriveFont(primary.size2D).also { made[i][style] = it }
            if (font.canDisplayUpTo(buf, start, end) < 0) return font
        }
        return null
    }
}

/**
 * pty4j process ⟷ JediTerm: streams via [ProcessTtyConnector], resize forwarded to the PTY.
 *
 * UTF-8 is correct on every platform, Windows included, and is deliberately *not* the JVM default charset:
 * the byte stream we get here is produced by the PTY layer, not by the child. winpty reads the console
 * screen buffer as UTF-16 and re-encodes it to UTF-8, and ConPTY emits UTF-8 too — so following
 * `Charset.defaultCharset()` (GBK on a Chinese Windows) would be the bug, not the fix. Decoding is
 * boundary-safe: [ProcessTtyConnector] reads through an InputStreamReader, whose stateful decoder holds a
 * half-read multi-byte sequence over to the next read instead of dropping it. The same charset encodes
 * `write(String)`, so IME input goes back out as UTF-8.
 */
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
