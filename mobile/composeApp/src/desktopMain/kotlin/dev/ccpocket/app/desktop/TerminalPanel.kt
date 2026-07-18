package dev.ccpocket.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.tilde
import kotlinx.coroutines.delay

// ═══════════════════════════ state (issue #153) ═══════════════════════════
// The embedded terminal dock: ONE local shell, docked at the bottom of the ChatPane of the session
// (cwd) that opened it. This block is pure snapshot state — no Swing — so the open/collapse/close and
// default-routing contracts are unit-tested headless; the JediTerm engine plugs in via [engineFactory].

enum class TermPanelMode { CLOSED, OPEN, COLLAPSED }

/** Where the open-mode menu is anchored from — the ChatPane header's >_ chip, the embedded panel's
 *  header glyph, or the collapsed strip's glyph (design: embedded-terminal.jsx, screen ②). */
enum class TermMenuAnchor { HEADER, PANEL, STRIP }

/** A live embedded shell as the panel sees it. The real one ([JediTermEngine.spawn]) wraps a JediTerm
 *  Swing widget + PTY; tests fake it, and a null [view] renders the chrome with an inert body. */
interface EmbeddedTerminal {
    val running: Boolean
    val focused: Boolean
    fun view(): javax.swing.JComponent? = null
    fun focus() {}
    fun dispose() {}
}

/**
 * The dock's state machine. One embedded shell at a time, bound to the session cwd that opened it:
 * switching to a session in another folder hides the dock (shell keeps running); opening embedded
 * there moves the dock — the old shell is disposed first. Height is a fraction of the ChatPane,
 * persisted on drag end like the sidebar width.
 */
class TerminalPanelController(
    private val loadHeight: () -> Float? = { null },
    private val saveHeight: (Float) -> Unit = {},
) {
    /** Installed once by the live shell (Main) — creates the JediTerm engine. Null (seed/preview/tests
     *  and headless environments) keeps every verb working against an engineless panel. */
    var engineFactory: ((cwd: String) -> EmbeddedTerminal?)? = null

    var mode by mutableStateOf(TermPanelMode.CLOSED)
        private set
    var cwd by mutableStateOf<String?>(null)
        private set
    var branch by mutableStateOf<String?>(null)
        private set
    var heightFraction by mutableStateOf((loadHeight() ?: DEFAULT_FRACTION).coerceIn(MIN_FRACTION, MAX_FRACTION))
        private set
    var engine by mutableStateOf<EmbeddedTerminal?>(null)
        private set

    /** The OPEN panel's real rendered height in px, reported by TerminalPanelView's onSizeChanged.
     *  The pane Column hands the dock only what's LEFT after the subheader/composer rows, so
     *  "pane height × fraction" overstates the panel — drag math and the PANEL menu anchor read
     *  this measured truth instead (the divider used to track the mouse at only ~85%). */
    var panelHeightPx by mutableStateOf(0f)

    /** What the Column actually offers the dock (measured px ÷ the fraction that produced it) —
     *  the denominator that turns a drag Δpx into a Δfraction 1:1. 0 until the panel lays out. */
    val availableHeightPx: Float get() = if (panelHeightPx > 0f) panelHeightPx / heightFraction else 0f

    fun openEmbedded(cwd: String?, branch: String?) {
        if (cwd.isNullOrBlank()) return
        if (this.cwd != cwd) { // the dock moves to this session's folder — one shell, never two
            engine?.dispose()
            engine = null
        }
        this.cwd = cwd
        this.branch = branch
        if (engine == null) engine = engineFactory?.invoke(cwd)
        mode = TermPanelMode.OPEN
        engine?.focus()
    }

    fun collapse() {
        if (mode == TermPanelMode.OPEN) mode = TermPanelMode.COLLAPSED
    }

    fun restore() {
        if (mode == TermPanelMode.COLLAPSED) {
            mode = TermPanelMode.OPEN
            engine?.focus()
        }
    }

    fun close() {
        engine?.dispose()
        engine = null
        cwd = null
        branch = null
        mode = TermPanelMode.CLOSED
    }

    fun dragHeightTo(f: Float) {
        heightFraction = f.coerceIn(MIN_FRACTION, MAX_FRACTION)
    }

    /** Persist once per drag gesture (putString rewrites a whole file — same rule as the sidebar). */
    fun persistHeight() = saveHeight(heightFraction)

    /** The dock renders only inside the session that owns the shell (cwd match). */
    fun dockedAt(chatCwd: String?): Boolean =
        mode != TermPanelMode.CLOSED && cwd != null && cwd == chatCwd

    companion object {
        const val DEFAULT_FRACTION = 0.35f // the design's "~35% of the ChatPane height"
        const val MIN_FRACTION = 0.15f
        const val MAX_FRACTION = 0.70f
    }
}

/**
 * The one funnel for a "just open a terminal" gesture (the ⋯ quick action; the menu's rows pick
 * explicitly instead): embedded panel when that's the user's default, else the external app.
 * [external] is injectable so the split is testable without launching a real window.
 */
fun DesktopModel.openTerminalPreferred(external: (TerminalApp, String) -> Unit = TerminalLauncher::open) {
    val dir = chatWorkdir
    if (dir.isBlank()) return
    val panel = terminalPanel
    if (terminalDefaultEmbedded && panel != null) panel.openEmbedded(dir, chatBranch)
    else external(terminalApp, dir)
}

/** ⌘J: open the embedded terminal in the current session, or collapse/restore the one it already has.
 *  Same locality contract as the header button — a remote session's cwd can't host a local shell. */
fun DesktopModel.toggleEmbeddedTerminal() {
    val panel = terminalPanel ?: return
    val dir = chatWorkdir
    if (panel.dockedAt(dir)) {
        if (panel.mode == TermPanelMode.OPEN) panel.collapse() else panel.restore()
    } else if (TerminalLauncher.canOpen(dir)) {
        panel.openEmbedded(dir, chatBranch)
    }
}

/** Middle-truncate a path the design's way (`~/code/cc-pocket/…/relay`): keep the leading segments
 *  that fit plus always the leaf, collapsing the middle into one ellipsis segment. */
internal fun middleTruncatedPath(path: String, maxChars: Int = 44): String {
    if (path.length <= maxChars) return path
    val sep = if ('\\' in path && '/' !in path) '\\' else '/'
    val parts = path.split(sep)
    if (parts.size < 3) return path.take(maxChars - 1) + "…"
    val leaf = parts.last()
    var head = parts.first()
    var i = 1
    while (i < parts.size - 1 && head.length + 1 + parts[i].length + 3 + leaf.length <= maxChars) {
        head = head + sep + parts[i]
        i++
    }
    if (i >= parts.size - 1) return path
    return "$head$sep…$sep$leaf"
}

// ═══════════════════════════ dock composables ═══════════════════════════
// Pixel spec: docs/design/claude-design-handoff/embedded-terminal/embedded-terminal.jsx.

/** The terminal's own ink — deliberately the same near-black in BOTH themes (terminals stay dark). */
private val TermInk = Color(0xFF0B0C0D)
private val TermInkText = Color(0xFF6B7177) // muted-on-ink, theme-independent like the ink itself

private val rowResizeCursor = PointerIcon(java.awt.Cursor(java.awt.Cursor.N_RESIZE_CURSOR))

/**
 * The whole dock, emitted at the bottom of ChatPane's column: divider + panel while OPEN, the slim
 * status strip while COLLAPSED, nothing when CLOSED or when the open chat isn't the shell's session.
 * [interopHidden] swaps the heavyweight Swing terminal for a flat placeholder while any Compose
 * overlay is up — SwingPanel always paints ABOVE the Compose layer, so a popover/modal would
 * otherwise be clipped by the terminal region (no compose.interop.blending; it's still experimental).
 */
@Composable
fun TerminalDock(
    model: DesktopModel,
    interopHidden: Boolean,
    onOpenMenu: (TermMenuAnchor) -> Unit,
    menuAnchor: TermMenuAnchor?,
) {
    val tp = model.terminalPanel ?: return
    if (!tp.dockedAt(model.chatWorkdir)) return
    when (tp.mode) {
        TermPanelMode.OPEN -> {
            TerminalDivider(tp)
            TerminalPanelView(tp, model, interopHidden, onOpenMenu, menuOpen = menuAnchor == TermMenuAnchor.PANEL)
        }
        TermPanelMode.COLLAPSED ->
            TerminalCollapsedStrip(tp, onOpenMenu, menuOpen = menuAnchor == TermMenuAnchor.STRIP)
        TermPanelMode.CLOSED -> {}
    }
}

/** The draggable hairline divider above the panel: 9dp hit strip, centered 1px hairline, a grab
 *  pill on hover, row-resize cursor. Dragging re-fractions the panel against the height the dock
 *  can actually occupy (NOT the whole pane — that denominator made the divider lag the mouse);
 *  the height persists on release. */
@Composable
private fun TerminalDivider(tp: TerminalPanelController) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Box(
        Modifier.fillMaxWidth().height(9.dp).background(Tok.base)
            .hoverable(src).pointerHoverIcon(rowResizeCursor)
            .pointerInput(tp) {
                detectDragGestures(onDragEnd = { tp.persistHeight() }) { change, drag ->
                    change.consume()
                    val h = tp.availableHeightPx
                    if (h > 0f) tp.dragHeightTo(tp.heightFraction - drag.y / h)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        if (hovered) Box(Modifier.size(width = 34.dp, height = 4.dp).clip(RoundedCornerShape(999.dp)).background(Tok.muted))
    }
}

/** The open panel: 32dp raised header (glyph · cwd · branch chip | external · collapse · close) over
 *  the near-black terminal body. While the shell owns the keyboard the panel carries a 1px terracotta
 *  inner ring (the body is inset 1dp so the ring survives the heavyweight Swing child). */
@Composable
private fun TerminalPanelView(
    tp: TerminalPanelController,
    model: DesktopModel,
    interopHidden: Boolean,
    onOpenMenu: (TermMenuAnchor) -> Unit,
    menuOpen: Boolean,
) {
    val engine = tp.engine
    val focused = engine?.focused == true
    Column(
        Modifier.fillMaxWidth().fillMaxHeight(tp.heightFraction)
            .onSizeChanged { tp.panelHeightPx = it.height.toFloat() } // the real height (see panelHeightPx)
            .border(1.dp, if (focused) Tok.accent else Color.Transparent)
            .padding(1.dp),
    ) {
        // header — hairline top/bottom borders around a 30dp raised row (32dp total, per the spec)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Row(
            Modifier.fillMaxWidth().height(30.dp).background(Tok.raised)
                .padding(start = 11.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            TermGlyphButton(menuOpen, size = 24.dp) { onOpenMenu(TermMenuAnchor.PANEL) }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(
                    middleTruncatedPath(tilde(tp.cwd ?: "")), color = Tok.tx2, fontFamily = Dk.mono, fontSize = 11.5.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                tp.branch?.let { TermBranchChip(it) }
            }
            TermHeaderIconButton(onClick = { tp.cwd?.let { TerminalLauncher.open(model.terminalApp, it) } }) {
                Icon(Icons.Rounded.OpenInNew, "Open in external terminal", tint = Tok.tx2, modifier = Modifier.size(14.dp))
            }
            TermHeaderIconButton(onClick = { tp.collapse() }) {
                Icon(Icons.Rounded.KeyboardArrowDown, "Collapse terminal", tint = Tok.tx2, modifier = Modifier.size(16.dp))
            }
            TermHeaderIconButton(onClick = { tp.close() }) {
                Icon(Icons.Rounded.Close, "Close terminal", tint = Tok.muted, modifier = Modifier.size(14.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        // body — the live Swing terminal, or a flat stand-in while overlays are up / no engine exists
        Box(Modifier.fillMaxWidth().weight(1f).background(TermInk)) {
            val view = remember(engine) { engine?.view() }
            if (view != null && !interopHidden) {
                SwingPanel(background = TermInk, factory = { view }, modifier = Modifier.fillMaxSize())
                // focus the shell once the AWT child is actually attached (a bare requestFocus at
                // open time lands before the SwingPanel exists and silently no-ops — same lesson
                // as the composer's land-ready loop, #72)
                LaunchedEffect(view) {
                    repeat(3) {
                        engine?.focus()
                        delay(80)
                    }
                }
            } else if (engine == null) {
                Text(
                    "shell engine unavailable — use the header's open-external instead",
                    color = TermInkText, fontFamily = Dk.mono, fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

/** Collapsed: a 30dp status strip at the pane bottom — glyph (menu) · cwd · branch · running dot ·
 *  chevron; clicking anywhere else restores the panel. */
@Composable
private fun TerminalCollapsedStrip(
    tp: TerminalPanelController,
    onOpenMenu: (TermMenuAnchor) -> Unit,
    menuOpen: Boolean,
) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Row(
            Modifier.fillMaxWidth().height(29.dp).background(Tok.raised)
                .clickable { tp.restore() }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            TermGlyphButton(menuOpen, size = 20.dp) { onOpenMenu(TermMenuAnchor.STRIP) }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(
                    middleTruncatedPath(tilde(tp.cwd ?: "")), color = Tok.tx2, fontFamily = Dk.mono, fontSize = 11.5.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                tp.branch?.let { TermBranchChip(it) }
            }
            if (tp.engine?.running == true) {
                PulseDot(Tok.ok, 6.dp)
                Text("1 running", color = Tok.tx2, fontFamily = Dk.mono, fontSize = 10.5.sp)
            }
            Icon(Icons.Rounded.KeyboardArrowUp, "Restore terminal", tint = Tok.muted, modifier = Modifier.size(15.dp))
        }
    }
}

/** The terminal glyph as a button — anchors the open-mode menu; menu-open lifts it like the design. */
@Composable
private fun TermGlyphButton(menuOpen: Boolean, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(6.dp))
            .background(if (menuOpen) Tok.surface else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.Terminal, "Terminal menu",
            tint = if (menuOpen) Tok.tx else Tok.tx2, modifier = Modifier.size(size - 9.dp),
        )
    }
}

@Composable
private fun TermHeaderIconButton(onClick: () -> Unit, icon: @Composable () -> Unit) {
    Box(
        Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)).hoverFill(RoundedCornerShape(7.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { icon() }
}

/** The git-branch pill: base fill, hairline ring, ⑂ + branch in 10.5 mono. */
@Composable
private fun TermBranchChip(branch: String) {
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(Tok.base)
            .border(1.dp, Tok.hair, RoundedCornerShape(999.dp))
            .padding(start = 6.dp, end = 8.dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("⑂", color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.5.sp)
        Text(branch, color = Tok.tx2, fontFamily = Dk.mono, fontSize = 10.5.sp, maxLines = 1)
    }
}

// ═══════════════════════════ open-mode menu ═══════════════════════════

/**
 * The menu's dismiss-on-outside-click layer inside ChatPane's root box, anchored per [anchor]:
 * under the header's >_ chip, just above the open panel, or above the collapsed strip (design ②).
 */
@Composable
fun TerminalMenuOverlay(
    model: DesktopModel,
    anchor: TermMenuAnchor,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    Box(
        Modifier.fillMaxSize()
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
    ) {
        val (alignment, pad) = when (anchor) {
            TermMenuAnchor.HEADER -> Alignment.TopEnd to PaddingValues(top = 44.dp, end = 96.dp)
            TermMenuAnchor.PANEL -> {
                // sit just above the panel's top edge — its MEASURED height, not pane × fraction:
                // the Column gives the dock only what's left after the subheader/composer, so the
                // estimate floated the menu tens of px too high at large fractions
                val panelH = with(density) { (model.terminalPanel?.panelHeightPx ?: 0f).toDp() }
                Alignment.BottomStart to PaddingValues(start = 8.dp, bottom = panelH + 11.dp)
            }
            TermMenuAnchor.STRIP -> Alignment.BottomStart to PaddingValues(start = 8.dp, bottom = 38.dp)
        }
        Box(
            Modifier.align(alignment).padding(pad)
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
        ) {
            TerminalOpenModeMenu(
                model,
                onEmbedded = {
                    model.terminalPanel?.openEmbedded(model.chatWorkdir, model.chatBranch)
                    onDismiss()
                },
                onExternal = {
                    TerminalLauncher.open(model.terminalApp, model.chatWorkdir)
                    onDismiss()
                },
            )
        }
    }
}

/** The anchored "Open terminal" menu (design screen ②): Open embedded (⌘J) and Open in the external
 *  app, the check marking whichever is the user's DEFAULT, plus the Settings pointer. */
@Composable
fun TerminalOpenModeMenu(model: DesktopModel, onEmbedded: () -> Unit, onExternal: () -> Unit) {
    val externalLabel = if (model.terminalApp == TerminalApp.GHOSTTY) "Open in Ghostty" else "Open in system terminal"
    Column(
        Modifier.width(300.dp).clip(RoundedCornerShape(13.dp)).background(Tok.raised)
            .border(1.dp, Tok.hair, RoundedCornerShape(13.dp)),
    ) {
        Text(
            "OPEN TERMINAL", color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
            modifier = Modifier.padding(start = 13.dp, end = 13.dp, top = 12.dp, bottom = 6.dp),
        )
        Column(Modifier.padding(start = 6.dp, end = 6.dp, bottom = 4.dp)) {
            TermMenuRow(
                title = "Open embedded", sub = "Docked in this session", hint = "⌘J",
                primary = model.terminalDefaultEmbedded, onClick = onEmbedded,
            ) { tint -> Icon(Icons.Rounded.Terminal, null, tint = tint, modifier = Modifier.size(15.dp)) }
            TermMenuRow(
                title = externalLabel, sub = "Your system terminal", hint = null,
                primary = !model.terminalDefaultEmbedded, onClick = onExternal,
            ) { tint -> Icon(Icons.Rounded.OpenInNew, null, tint = tint, modifier = Modifier.size(15.dp)) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Text(
            buildAnnotatedString {
                append("Default can be changed in ")
                withStyle(SpanStyle(color = Tok.tx2)) { append("Settings → Terminal") }
                append(".")
            },
            color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.5.sp, lineHeight = 16.sp,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun TermMenuRow(
    title: String,
    sub: String,
    hint: String?,
    primary: Boolean,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit,
) {
    Row(
        // the tag pins WHICH row carries the default check — UI tests assert its position, not just presence
        Modifier.fillMaxWidth().testTag(if (primary) "term-menu-default-row" else "term-menu-row")
            .clip(RoundedCornerShape(8.dp)).hoverFill(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(7.dp))
                .background(if (primary) Tok.accent.copy(alpha = 0.12f) else Tok.surface)
                .border(1.dp, if (primary) Tok.accent.copy(alpha = 0.4f) else Tok.hair, RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center,
        ) { icon(if (primary) Tok.accent else Tok.tx2) }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (primary) Icon(Icons.Rounded.Check, null, tint = Tok.accent, modifier = Modifier.size(14.dp))
            }
            Text(sub, color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.5.sp)
        }
        if (hint != null) Key(hint)
    }
}
