package dev.ccpocket.app.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.*
import org.jetbrains.compose.resources.StringResource

/**
 * The macOS application menu (issue #350; `docs/design/macos-app-menu-bar.md`) as DATA.
 *
 * The whole menu is one pure function — [appMenuSections] : [AppMenuState] → sections — so every rule the
 * design pins down ("菜单结构稳定；不可用项保留位置并灰显") is unit-testable without AppKit, without a
 * packaged `.app`, and without composition. [MacAppMenuBar] is then a dumb renderer: it walks this list,
 * emits one Compose `Item` per entry, and routes the [AppMenuAction] back through the SAME lambdas the
 * window's key handler and the in-window buttons already call. No command is invented here — the menu is
 * a second, browsable spelling of commands that already exist.
 *
 * Two consequences worth stating, because they are load-bearing:
 *
 * 1. **Nothing is hidden.** Every state produces the same shape; only [AppMenuItem.enabled] moves. A user
 *    who learns where "归档会话" lives finds it there while disconnected too — greyed, not gone.
 * 2. **Enabled is derived, never asserted.** Each flag below reads from state the shell already keeps
 *    (`connected`, the selected session, nav history, `streaming`, capability bits the daemon stamped),
 *    so a menu item cannot claim to be usable when the button it mirrors is not.
 */
enum class AppMenuAction {
    // ── CC Pocket (rendered by AppKit itself; see [AppMenuSectionId.APP]) ──
    ABOUT,
    SETTINGS,
    QUIT,

    // ── 文件 ──
    NEW_SESSION,
    OPEN_FOLDER,
    ALL_PROJECTS,
    ARCHIVED_SESSIONS,

    // ── 编辑 (focus-scoped; see [editKeyStroke]) ──
    EDIT_UNDO,
    EDIT_REDO,
    EDIT_CUT,
    EDIT_COPY,
    EDIT_PASTE,
    EDIT_SELECT_ALL,

    // ── 显示 ──
    QUICK_OPEN,
    GO_BACK,
    GO_FORWARD,
    TOGGLE_SIDEBAR,
    TOGGLE_TERMINAL,
    SHOW_FILES,
    SHOW_GIT,
    TOGGLE_FULLSCREEN,

    // ── 会话 ──
    /** The inert header naming what this menu acts on. Always disabled — it is a label, not a command. */
    SESSION_CONTEXT,
    SESSION_TOGGLE_PIN,
    SESSION_COPY_WORKDIR,
    SESSION_STOP_TURN,
    SESSION_ARCHIVE,
    /** Indexed by [AppMenuItem.index] — the ⌘1–9 pin ladder, same ordering as the sidebar's. */
    JUMP_PIN,

    // ── 电脑 ──
    SWITCH_COMPUTER,
    /** Indexed by [AppMenuItem.index] into [AppMenuState.computers]. */
    SELECT_COMPUTER,
    ADD_COMPUTER,
    REFRESH,

    // ── 窗口 ──
    WINDOW_MINIMIZE,
    WINDOW_ZOOM,
    WINDOW_SHOW_MAIN,

    // ── 帮助 ──
    HELP_MANUAL,
    HELP_SHORTCUTS,
    CHECK_UPDATES,
    REPORT_ISSUE,
}

/** Which top-level menu an [AppMenuSection] is. [APP] is the one AppKit already owns. */
enum class AppMenuSectionId { APP, FILE, EDIT, VIEW, SESSION, COMPUTER, WINDOW, HELP }

sealed interface AppMenuEntry

/** A rule in the design's "About 后、服务前、Quit 前分组" sense — rendered as a divider. */
data object AppMenuSeparator : AppMenuEntry

/**
 * One command. Exactly one of [label] / [rawLabel] is non-null: static commands carry a [StringResource]
 * (the design's "中文/英文" acceptance row — no Chinese literal ever reaches Kotlin), while rows that name
 * USER data (a computer, a pinned session) carry the raw text, which is not translatable by definition.
 */
data class AppMenuItem(
    val action: AppMenuAction,
    val label: StringResource? = null,
    val rawLabel: String? = null,
    val enabled: Boolean = true,
    val shortcut: KeyShortcut? = null,
    /** Payload for [AppMenuAction.JUMP_PIN] / [AppMenuAction.SELECT_COMPUTER]; -1 = not indexed. */
    val index: Int = -1,
    /** Renders the ✓ — currently only the active computer. */
    val checked: Boolean = false,
) : AppMenuEntry

/** A nested menu (the pin ladder). One level deep only — the design forbids turning icons into trees. */
data class AppMenuSubmenu(val label: StringResource, val items: List<AppMenuItem>) : AppMenuEntry

data class AppMenuSection(val id: AppMenuSectionId, val label: StringResource?, val entries: List<AppMenuEntry>)

/** One paired computer as the menu sees it. */
data class AppMenuComputer(val accountId: String, val name: String, val online: Boolean, val active: Boolean)

/**
 * Everything [appMenuSections] is allowed to look at. Folded from the live [DesktopModel] plus the two
 * facts only the window host knows (fullscreen, whether a pin ladder is currently claimed by the machine
 * switcher). Defaults describe the honest cold-start: nothing connected, nothing selected.
 */
data class AppMenuState(
    val connected: Boolean = false,
    /** A session is actually open in the main pane — not merely "a computer answered". */
    val hasSession: Boolean = false,
    /** Its title, shown as the Session menu's inert header so the menu names what it will act on. */
    val sessionTitle: String? = null,
    val sessionPinned: Boolean = false,
    /** The open session's turn is streaming — the only thing "停止当前回合" can act on. */
    val streaming: Boolean = false,
    /** Read-only OBSERVE view (a terminal/VS Code owns the session): every mutation must stay disabled. */
    val observing: Boolean = false,
    /** The daemon stamped `Sessions.archiveSupported` AND this user owns the binding. */
    val canArchiveSessions: Boolean = false,
    /** The open session has a workdir we can copy / host a terminal in. */
    val hasWorkdir: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val sidebarCollapsed: Boolean = false,
    /** The embedded terminal is docked on the current session and open (not collapsed/closed). */
    val terminalOpen: Boolean = false,
    val fullscreen: Boolean = false,
    /** Pinned-session titles in ⌘1–9 order (sessions then projects, exactly like [DesktopModel.jumpPin]). */
    val pins: List<String> = emptyList(),
    val computers: List<AppMenuComputer> = emptyList(),
)

/** Fold the live shell into [AppMenuState]. The only place the menu touches the model. */
internal fun appMenuState(model: DesktopModel, fullscreen: Boolean): AppMenuState {
    val session = model.selectedSessionId?.let { model.liveSession(it) }
    val workdir = model.chatWorkdir
    val panel = model.terminalPanel
    return AppMenuState(
        connected = model.connected,
        hasSession = session != null,
        sessionTitle = session?.title?.takeIf { it.isNotBlank() },
        sessionPinned = session != null && model.isPinned(session.sessionId),
        streaming = model.streaming,
        observing = model.observing,
        canArchiveSessions = model.canArchiveSessions,
        hasWorkdir = workdir.isNotBlank(),
        canGoBack = model.canGoBack,
        canGoForward = model.canGoForward,
        sidebarCollapsed = model.sidebarCollapsed,
        terminalOpen = panel != null && panel.dockedAt(workdir) && panel.mode == TermPanelMode.OPEN,
        fullscreen = fullscreen,
        // one keycap ladder over the one PINNED zone: sessions first, then project pins (jumpPin's order)
        pins = model.pins.map { it.title } + model.projectPins.map { it.name },
        computers = model.machines.map {
            AppMenuComputer(it.computer.accountId, it.computer.name, it.computer.online, it.active)
        },
    )
}

private fun meta(key: Key) = KeyShortcut(key, meta = true)

/**
 * The whole menu tree. Read the `enabled` expressions as the spec: they are the design's
 * "状态与交互约定" table, and the tests assert them directly.
 */
fun appMenuSections(s: AppMenuState): List<AppMenuSection> {
    // A session command needs BOTH a live link and an actually-open session; observing additionally
    // forbids anything that would write, because the composer is already yielding to a terminal owner.
    val onSession = s.connected && s.hasSession
    val canMutateSession = onSession && !s.observing

    val app = AppMenuSection(
        AppMenuSectionId.APP,
        label = null, // AppKit names this menu after the bundle; we only supply its handlers
        entries = listOf(
            // Deliberately NOT gated on `connected` (design §状态与交互约定 row 1): About, Settings and Help
            // are local surfaces, and the moment a user most needs them is the moment nothing is connected.
            AppMenuItem(AppMenuAction.ABOUT, Res.string.menu_about),
            AppMenuSeparator,
            AppMenuItem(AppMenuAction.SETTINGS, Res.string.menu_settings, shortcut = meta(Key.Comma)),
            AppMenuSeparator,
            AppMenuItem(AppMenuAction.QUIT, Res.string.menu_quit, shortcut = meta(Key.Q)),
        ),
    )

    val file = AppMenuSection(
        AppMenuSectionId.FILE,
        Res.string.menu_file,
        listOf(
            AppMenuItem(AppMenuAction.NEW_SESSION, Res.string.menu_new_session, enabled = s.connected, shortcut = meta(Key.N)),
            AppMenuItem(AppMenuAction.OPEN_FOLDER, Res.string.menu_open_folder, enabled = s.connected, shortcut = meta(Key.O)),
            AppMenuSeparator,
            // Both open the SAME quick-open panel at a different scope — never a second search surface.
            AppMenuItem(AppMenuAction.ALL_PROJECTS, Res.string.menu_all_projects, enabled = s.connected),
            AppMenuItem(AppMenuAction.ARCHIVED_SESSIONS, Res.string.menu_archived_sessions, enabled = s.connected && s.canArchiveSessions),
        ),
    )
    // No "关闭窗口 ⌘W": on macOS closing this window still calls exitApplication(), and the design is
    // explicit that a menu item must not claim a close/quit split the app does not implement yet.

    // Focus-scoped, and therefore always enabled: these act on whatever text component owns the keyboard
    // (composer, search box, rename field, JediTerm), exactly like every native Edit menu. See
    // [editKeyStroke] — the action is forwarded to the focus owner rather than interpreted globally, so
    // "复制" never means "copy the transcript" and "撤销" never means "roll the agent back".
    val edit = AppMenuSection(
        AppMenuSectionId.EDIT,
        Res.string.menu_edit,
        listOf(
            AppMenuItem(AppMenuAction.EDIT_UNDO, Res.string.menu_undo, shortcut = meta(Key.Z)),
            AppMenuItem(AppMenuAction.EDIT_REDO, Res.string.menu_redo, shortcut = KeyShortcut(Key.Z, meta = true, shift = true)),
            AppMenuSeparator,
            AppMenuItem(AppMenuAction.EDIT_CUT, Res.string.menu_cut, shortcut = meta(Key.X)),
            AppMenuItem(AppMenuAction.EDIT_COPY, Res.string.menu_copy, shortcut = meta(Key.C)),
            AppMenuItem(AppMenuAction.EDIT_PASTE, Res.string.menu_paste, shortcut = meta(Key.V)),
            AppMenuSeparator,
            AppMenuItem(AppMenuAction.EDIT_SELECT_ALL, Res.string.menu_select_all, shortcut = meta(Key.A)),
        ),
    )

    val view = AppMenuSection(
        AppMenuSectionId.VIEW,
        Res.string.menu_view,
        listOf(
            AppMenuItem(AppMenuAction.QUICK_OPEN, Res.string.menu_quick_open, enabled = s.connected, shortcut = meta(Key.K)),
            AppMenuSeparator,
            AppMenuItem(AppMenuAction.GO_BACK, Res.string.menu_back, enabled = s.connected && s.canGoBack, shortcut = meta(Key.LeftBracket)),
            AppMenuItem(AppMenuAction.GO_FORWARD, Res.string.menu_forward, enabled = s.connected && s.canGoForward, shortcut = meta(Key.RightBracket)),
            AppMenuSeparator,
            // ⌘\ is ungated in the window's key handler on purpose (a collapsed sidebar leaves no chrome
            // behind and the connect screen has no toggle of its own) — the menu keeps that promise.
            AppMenuItem(
                AppMenuAction.TOGGLE_SIDEBAR,
                if (s.sidebarCollapsed) Res.string.menu_show_sidebar else Res.string.menu_hide_sidebar,
                shortcut = meta(Key.Backslash),
            ),
            AppMenuItem(
                AppMenuAction.TOGGLE_TERMINAL,
                if (s.terminalOpen) Res.string.menu_hide_terminal else Res.string.menu_show_terminal,
                // a remote session's cwd cannot host a local shell — same locality contract as ⌘J
                enabled = onSession && s.hasWorkdir,
                shortcut = meta(Key.J),
            ),
            AppMenuSeparator,
            AppMenuItem(AppMenuAction.SHOW_FILES, Res.string.menu_files, enabled = onSession),
            AppMenuItem(AppMenuAction.SHOW_GIT, Res.string.menu_git, enabled = onSession),
            AppMenuSeparator,
            AppMenuItem(
                AppMenuAction.TOGGLE_FULLSCREEN,
                if (s.fullscreen) Res.string.menu_exit_fullscreen else Res.string.menu_enter_fullscreen,
                shortcut = KeyShortcut(Key.F, ctrl = true, meta = true),
            ),
        ),
    )

    val pinItems = if (s.pins.isEmpty()) {
        listOf(AppMenuItem(AppMenuAction.JUMP_PIN, Res.string.menu_no_pins, enabled = false))
    } else {
        s.pins.take(9).mapIndexed { i, title ->
            AppMenuItem(
                AppMenuAction.JUMP_PIN,
                rawLabel = title,
                enabled = s.connected,
                shortcut = meta(DIGIT_KEYS[i]),
                index = i,
            )
        }
    }
    val session = AppMenuSection(
        AppMenuSectionId.SESSION,
        Res.string.menu_session,
        listOf(
            // "菜单顶端的不可操作文字显示作用对象" — every command below acts on THIS session, and a menu
            // that quietly targets something other than what the user thinks is open is the exact failure
            // the design's split-pane row warns about. Naming it is the cheapest guard against that.
            AppMenuItem(
                AppMenuAction.SESSION_CONTEXT,
                label = if (s.sessionTitle == null) Res.string.menu_no_session else null,
                rawLabel = s.sessionTitle,
                enabled = false,
            ),
            AppMenuSeparator,
            AppMenuItem(
                AppMenuAction.SESSION_TOGGLE_PIN,
                if (s.sessionPinned) Res.string.menu_unpin_session else Res.string.menu_pin_session,
                enabled = onSession,
            ),
            AppMenuItem(AppMenuAction.SESSION_COPY_WORKDIR, Res.string.menu_copy_workdir, enabled = onSession && s.hasWorkdir),
            AppMenuSeparator,
            // "会话闲置 → 停止禁用；不能因为『连接成功』就启用全部命令"
            AppMenuItem(AppMenuAction.SESSION_STOP_TURN, Res.string.menu_stop_turn, enabled = canMutateSession && s.streaming),
            AppMenuSeparator,
            AppMenuItem(AppMenuAction.SESSION_ARCHIVE, Res.string.menu_archive_session, enabled = canMutateSession && s.canArchiveSessions),
            AppMenuSeparator,
            AppMenuSubmenu(Res.string.menu_jump_to_pin, pinItems),
        ),
    )

    val computerRows = if (s.computers.isEmpty()) {
        listOf(AppMenuItem(AppMenuAction.SELECT_COMPUTER, Res.string.menu_no_computers, enabled = false))
    } else {
        s.computers.mapIndexed { i, c ->
            AppMenuItem(
                AppMenuAction.SELECT_COMPUTER,
                rawLabel = c.name,
                // an OFFLINE computer stays selectable — that is how you reach its reconnect surface
                enabled = s.connected && !c.active,
                index = i,
                checked = c.active,
            )
        }
    }
    val computer = AppMenuSection(
        AppMenuSectionId.COMPUTER,
        Res.string.menu_computer,
        buildList {
            add(AppMenuItem(AppMenuAction.SWITCH_COMPUTER, Res.string.menu_switch_computer, enabled = s.connected, shortcut = meta(Key.Zero)))
            add(AppMenuSeparator)
            addAll(computerRows)
            add(AppMenuSeparator)
            // Pairing is exactly what a disconnected user is here to do — the modal mounts over the
            // connect screen as well as over the shell, so this one stays live.
            add(AppMenuItem(AppMenuAction.ADD_COMPUTER, Res.string.menu_add_computer))
            add(AppMenuItem(AppMenuAction.REFRESH, Res.string.menu_refresh, enabled = s.connected, shortcut = meta(Key.R)))
        },
    )

    val window = AppMenuSection(
        AppMenuSectionId.WINDOW,
        Res.string.menu_window,
        listOf(
            AppMenuItem(AppMenuAction.WINDOW_MINIMIZE, Res.string.menu_minimize, shortcut = meta(Key.M)),
            AppMenuItem(AppMenuAction.WINDOW_ZOOM, Res.string.menu_zoom),
            AppMenuSeparator,
            AppMenuItem(AppMenuAction.WINDOW_SHOW_MAIN, Res.string.menu_show_main_window),
        ),
    )

    val help = AppMenuSection(
        AppMenuSectionId.HELP,
        Res.string.menu_help,
        listOf(
            AppMenuItem(AppMenuAction.HELP_MANUAL, Res.string.menu_help_manual),
            AppMenuItem(AppMenuAction.HELP_SHORTCUTS, Res.string.menu_help_shortcuts),
            AppMenuSeparator,
            // AWT can add handlers to the native app menu but cannot add ITEMS to it, so the updater's
            // menu entry lives here rather than under CC Pocket. It only ever checks — applying an update
            // stays the explicit click in Settings ▸ About.
            AppMenuItem(AppMenuAction.CHECK_UPDATES, Res.string.menu_check_updates),
            AppMenuItem(AppMenuAction.REPORT_ISSUE, Res.string.menu_report_issue),
        ),
    )

    return listOf(app, file, edit, view, session, computer, window, help)
}

private val DIGIT_KEYS = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)

/** Flatten to items — what callers (and tests) use to ask "is this command enabled right now?". */
fun List<AppMenuSection>.items(): List<AppMenuItem> = flatMap { it.entries }.flatMap {
    when (it) {
        is AppMenuItem -> listOf(it)
        is AppMenuSubmenu -> it.items
        AppMenuSeparator -> emptyList()
    }
}

/** The first item carrying [action] (indexed actions have several — use [items] for those). */
fun List<AppMenuSection>.item(action: AppMenuAction): AppMenuItem? = items().firstOrNull { it.action == action }

// ── the 编辑 menu's forwarding table ─────────────────────────────────────────────────────────────
//
// Compose text fields, the Swing rename field and JediTerm each implement editing THEMSELVES; nothing in
// AWT can "cut the selection" generically. So an Edit item does the only honest thing: it re-plays its own
// keystroke into whatever component owns the keyboard, which is precisely the input each of those three
// already handles. Taking the accelerator away from the window and handing it back to the focused
// component is a no-op for the user — and is why this table must match the accelerators above exactly.
//
// Pure so the pairing is testable without AppKit (the dispatch itself needs a real Mac; see the issue).

/** `(java.awt.event.KeyEvent.VK_*, modifier mask)` for an Edit command, or null if not an Edit command. */
internal fun editKeyStroke(action: AppMenuAction): Pair<Int, Int>? {
    val meta = java.awt.event.InputEvent.META_DOWN_MASK
    val shift = java.awt.event.InputEvent.SHIFT_DOWN_MASK
    return when (action) {
        AppMenuAction.EDIT_UNDO -> java.awt.event.KeyEvent.VK_Z to meta
        AppMenuAction.EDIT_REDO -> java.awt.event.KeyEvent.VK_Z to (meta or shift)
        AppMenuAction.EDIT_CUT -> java.awt.event.KeyEvent.VK_X to meta
        AppMenuAction.EDIT_COPY -> java.awt.event.KeyEvent.VK_C to meta
        AppMenuAction.EDIT_PASTE -> java.awt.event.KeyEvent.VK_V to meta
        AppMenuAction.EDIT_SELECT_ALL -> java.awt.event.KeyEvent.VK_A to meta
        else -> null
    }
}
