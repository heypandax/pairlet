package dev.ccpocket.app.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The macOS application menu (issue #350) as a pure state fold. The native bar itself cannot be asserted
 * from a JVM test — AppKit draws it, and the design says so ("普通 Compose UI 测试不能单独证明原生菜单正确").
 * What CAN be pinned down, and is pinned down here, is everything that decides what that bar SAYS: the
 * structure, the enable rules from the design's 状态与交互约定 table, and the accelerator table.
 */
class AppMenuModelTest {

    private fun sections(s: AppMenuState) = appMenuSections(s)

    private val disconnected = AppMenuState()
    private val live = AppMenuState(
        connected = true,
        hasSession = true,
        sessionTitle = "relay heartbeat",
        hasWorkdir = true,
        canArchiveSessions = true,
    )

    @Test
    fun theSessionMenuNamesItsTarget() {
        // "菜单顶端的不可操作文字显示作用对象" — the commands below it all act on THIS session, and a menu
        // that silently targets a different one is the split-pane failure the design calls out.
        val header = sections(live).item(AppMenuAction.SESSION_CONTEXT)!!
        assertEquals("relay heartbeat", header.rawLabel)
        assertFalse(header.enabled) // a label, not a command
        assertNull(header.shortcut)
        // with nothing open it says so rather than showing a stale title
        val empty = sections(live.copy(hasSession = false, sessionTitle = null)).item(AppMenuAction.SESSION_CONTEXT)!!
        assertNull(empty.rawLabel)
        assertNotNull(empty.label)
        assertFalse(empty.enabled)
    }

    // ── structure ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun menusFollowHigOrder() {
        // App / File / Edit / View / <app-specific> / Window / Help — Apple HIG, and the order the
        // design fixed. A reordering here is a user-visible regression, not a refactor.
        assertEquals(
            listOf(
                AppMenuSectionId.APP, AppMenuSectionId.FILE, AppMenuSectionId.EDIT, AppMenuSectionId.VIEW,
                AppMenuSectionId.SESSION, AppMenuSectionId.COMPUTER, AppMenuSectionId.WINDOW, AppMenuSectionId.HELP,
            ),
            sections(live).map { it.id },
        )
    }

    @Test
    fun structureIsIdenticalDisconnected() {
        // "菜单结构稳定 … 不可用项保留位置并灰显" — nothing may disappear when the link drops, or a user
        // who learned where a command lives would find it gone precisely when they went looking.
        val shape = { s: AppMenuState -> sections(s).map { sec -> sec.id to sec.entries.map { it::class } } }
        assertEquals(shape(live), shape(disconnected))
    }

    @Test
    fun theAppMenuIsNativeAndCarriesNoTitle() {
        // AppKit already draws an application menu; drawing a second "CC Pocket" beside it is the
        // duplicate the design forbids. A null label is how the renderer knows to skip it.
        val app = sections(live).first { it.id == AppMenuSectionId.APP }
        assertNull(app.label)
        assertTrue(sections(live).drop(1).all { it.label != null })
    }

    @Test
    fun everyItemLabelsItselfExactlyOneWay() {
        // Static commands are translatable resources (no Chinese literal in Kotlin); rows naming USER
        // data (a computer, a pinned session) carry raw text, which is not translatable by definition.
        val items = sections(
            live.copy(pins = listOf("relay"), computers = listOf(AppMenuComputer("a", "panda", true, true))),
        ).items()
        assertTrue(items.isNotEmpty())
        items.forEach { assertTrue((it.label != null) != (it.rawLabel != null), "ambiguous label: $it") }
    }

    // ── disconnected: local surfaces stay reachable, remote ones go grey ───────────────────────

    @Test
    fun disconnectedKeepsSettingsAboutAndShortcutsEnabled() {
        val m = sections(disconnected)
        // The whole point of the ungated rows: the state where a user most needs About (version),
        // Shortcuts (what can I even press) and Settings is the state where nothing is connected.
        assertTrue(m.item(AppMenuAction.ABOUT)!!.enabled)
        assertTrue(m.item(AppMenuAction.SETTINGS)!!.enabled)
        assertTrue(m.item(AppMenuAction.HELP_SHORTCUTS)!!.enabled)
        assertTrue(m.item(AppMenuAction.HELP_MANUAL)!!.enabled)
        assertTrue(m.item(AppMenuAction.REPORT_ISSUE)!!.enabled)
        assertTrue(m.item(AppMenuAction.CHECK_UPDATES)!!.enabled)
        assertTrue(m.item(AppMenuAction.QUIT)!!.enabled)
        // pairing is exactly what a disconnected user is here to do
        assertTrue(m.item(AppMenuAction.ADD_COMPUTER)!!.enabled)
        // window + sidebar are local facts about a window that exists either way
        assertTrue(m.item(AppMenuAction.WINDOW_MINIMIZE)!!.enabled)
        assertTrue(m.item(AppMenuAction.TOGGLE_SIDEBAR)!!.enabled)
        assertTrue(m.item(AppMenuAction.TOGGLE_FULLSCREEN)!!.enabled)
    }

    @Test
    fun disconnectedDisablesSessionAndComputerCommands() {
        val m = sections(disconnected)
        listOf(
            AppMenuAction.SESSION_TOGGLE_PIN, AppMenuAction.SESSION_COPY_WORKDIR,
            AppMenuAction.SESSION_STOP_TURN, AppMenuAction.SESSION_ARCHIVE,
            AppMenuAction.SWITCH_COMPUTER, AppMenuAction.REFRESH,
            AppMenuAction.NEW_SESSION, AppMenuAction.OPEN_FOLDER, AppMenuAction.ALL_PROJECTS,
            AppMenuAction.ARCHIVED_SESSIONS, AppMenuAction.QUICK_OPEN,
            AppMenuAction.SHOW_FILES, AppMenuAction.SHOW_GIT, AppMenuAction.TOGGLE_TERMINAL,
        ).forEach { assertFalse(sections(disconnected).item(it)!!.enabled, "$it must be disabled offline") }
        // a pin ladder with nothing to jump to is present but inert
        assertFalse(m.items().first { it.action == AppMenuAction.JUMP_PIN }.enabled)
    }

    @Test
    fun editCommandsStayEnabledEverywhere() {
        // They act on the focused text component, which exists (search box, a rename field, the
        // connect screen's own inputs) whether or not any computer is reachable.
        listOf(
            AppMenuAction.EDIT_UNDO, AppMenuAction.EDIT_REDO, AppMenuAction.EDIT_CUT,
            AppMenuAction.EDIT_COPY, AppMenuAction.EDIT_PASTE, AppMenuAction.EDIT_SELECT_ALL,
        ).forEach { assertTrue(sections(disconnected).item(it)!!.enabled, "$it") }
    }

    // ── connected: session commands come alive, but only the ones that can act ─────────────────

    @Test
    fun connectedWithASessionEnablesSessionCommands() {
        val m = sections(live)
        assertTrue(m.item(AppMenuAction.SESSION_TOGGLE_PIN)!!.enabled)
        assertTrue(m.item(AppMenuAction.SESSION_COPY_WORKDIR)!!.enabled)
        assertTrue(m.item(AppMenuAction.SESSION_ARCHIVE)!!.enabled)
        assertTrue(m.item(AppMenuAction.SHOW_FILES)!!.enabled)
        assertTrue(m.item(AppMenuAction.SHOW_GIT)!!.enabled)
        assertTrue(m.item(AppMenuAction.TOGGLE_TERMINAL)!!.enabled)
        assertTrue(m.item(AppMenuAction.SWITCH_COMPUTER)!!.enabled)
        assertTrue(m.item(AppMenuAction.REFRESH)!!.enabled)
    }

    @Test
    fun connectedWithoutASessionLeavesSessionCommandsDisabled() {
        // "没有当前会话 → 重命名、归档、停止、文件、Git、终端禁用；可切换电脑/项目"
        val m = sections(live.copy(hasSession = false))
        assertFalse(m.item(AppMenuAction.SESSION_ARCHIVE)!!.enabled)
        assertFalse(m.item(AppMenuAction.SESSION_TOGGLE_PIN)!!.enabled)
        assertFalse(m.item(AppMenuAction.SHOW_FILES)!!.enabled)
        assertFalse(m.item(AppMenuAction.SHOW_GIT)!!.enabled)
        assertFalse(m.item(AppMenuAction.TOGGLE_TERMINAL)!!.enabled)
        // still able to move around
        assertTrue(m.item(AppMenuAction.SWITCH_COMPUTER)!!.enabled)
        assertTrue(m.item(AppMenuAction.NEW_SESSION)!!.enabled)
    }

    @Test
    fun stopNeedsARunningTurnNotJustAConnection() {
        // "会话闲置 → 停止禁用；不能因为『连接成功』就启用全部命令"
        assertFalse(sections(live).item(AppMenuAction.SESSION_STOP_TURN)!!.enabled)
        assertTrue(sections(live.copy(streaming = true)).item(AppMenuAction.SESSION_STOP_TURN)!!.enabled)
    }

    @Test
    fun observingForbidsEveryMutation() {
        // read-only OBSERVE view: a terminal/VS Code owns the session, so the menu must not become a
        // back door around the UI's own read-only state.
        val m = sections(live.copy(streaming = true, observing = true))
        assertFalse(m.item(AppMenuAction.SESSION_STOP_TURN)!!.enabled)
        assertFalse(m.item(AppMenuAction.SESSION_ARCHIVE)!!.enabled)
        // reads are still fine
        assertTrue(m.item(AppMenuAction.SESSION_COPY_WORKDIR)!!.enabled)
        assertTrue(m.item(AppMenuAction.SHOW_FILES)!!.enabled)
    }

    @Test
    fun archiveTracksTheDaemonsCapabilityBit() {
        // An older daemon would silently drop the frame — the entry stays visible and greyed instead.
        assertFalse(sections(live.copy(canArchiveSessions = false)).item(AppMenuAction.SESSION_ARCHIVE)!!.enabled)
        assertFalse(sections(live.copy(canArchiveSessions = false)).item(AppMenuAction.ARCHIVED_SESSIONS)!!.enabled)
    }

    @Test
    fun terminalNeedsAWorkdir() {
        // a remote session's cwd cannot host a local shell — the same locality contract as ⌘J
        assertFalse(sections(live.copy(hasWorkdir = false)).item(AppMenuAction.TOGGLE_TERMINAL)!!.enabled)
        assertFalse(sections(live.copy(hasWorkdir = false)).item(AppMenuAction.SESSION_COPY_WORKDIR)!!.enabled)
    }

    // ── history mirrors, toggles name their next state ────────────────────────────────────────

    @Test
    fun backAndForwardMirrorNavigationHistory() {
        fun back(s: AppMenuState) = sections(s).item(AppMenuAction.GO_BACK)!!.enabled
        fun fwd(s: AppMenuState) = sections(s).item(AppMenuAction.GO_FORWARD)!!.enabled
        assertFalse(back(live)); assertFalse(fwd(live))
        assertTrue(back(live.copy(canGoBack = true)))
        assertFalse(fwd(live.copy(canGoBack = true)))
        assertTrue(fwd(live.copy(canGoForward = true)))
        assertFalse(back(live.copy(canGoForward = true)))
        // history flags alone are not a connection
        assertFalse(back(disconnected.copy(canGoBack = true)))
    }

    @Test
    fun toggleLabelsFollowState() {
        fun label(s: AppMenuState, a: AppMenuAction) = sections(s).item(a)!!.label
        // each toggle NAMES the state it moves to, so the row never reads as a status line
        assertTrue(label(live, AppMenuAction.TOGGLE_SIDEBAR) != label(live.copy(sidebarCollapsed = true), AppMenuAction.TOGGLE_SIDEBAR))
        assertTrue(label(live, AppMenuAction.TOGGLE_FULLSCREEN) != label(live.copy(fullscreen = true), AppMenuAction.TOGGLE_FULLSCREEN))
        assertTrue(label(live, AppMenuAction.TOGGLE_TERMINAL) != label(live.copy(terminalOpen = true), AppMenuAction.TOGGLE_TERMINAL))
        assertTrue(label(live, AppMenuAction.SESSION_TOGGLE_PIN) != label(live.copy(sessionPinned = true), AppMenuAction.SESSION_TOGGLE_PIN))
    }

    // ── indexed rows: pins and computers ──────────────────────────────────────────────────────

    @Test
    fun pinLadderIsIndexedAndCappedAtNine() {
        val many = live.copy(pins = (1..12).map { "s$it" })
        val jumps = sections(many).items().filter { it.action == AppMenuAction.JUMP_PIN }
        assertEquals(9, jumps.size) // ⌘1–⌘9 — there is no ⌘10
        assertEquals((0..8).toList(), jumps.map { it.index })
        assertEquals(KeyShortcut(Key.One, meta = true), jumps.first().shortcut)
        assertEquals(KeyShortcut(Key.Nine, meta = true), jumps.last().shortcut)
        assertEquals("s1", jumps.first().rawLabel)
    }

    @Test
    fun computerRowsCheckTheActiveOneAndKeepOfflineOnesSelectable() {
        val m = sections(
            live.copy(
                computers = listOf(
                    AppMenuComputer("a", "panda", online = true, active = true),
                    AppMenuComputer("b", "studio", online = false, active = false),
                ),
            ),
        )
        val rows = m.items().filter { it.action == AppMenuAction.SELECT_COMPUTER }
        assertEquals(listOf("panda", "studio"), rows.map { it.rawLabel })
        assertTrue(rows[0].checked)
        assertFalse(rows[0].enabled) // already here — selecting it would be a no-op
        // offline still selectable: that is how a user reaches its connection/recovery surface
        assertFalse(rows[1].checked)
        assertTrue(rows[1].enabled)
    }

    @Test
    fun emptyLaddersStayVisibleAsInertRows() {
        val m = sections(disconnected)
        val pins = m.items().filter { it.action == AppMenuAction.JUMP_PIN }
        val computers = m.items().filter { it.action == AppMenuAction.SELECT_COMPUTER }
        assertEquals(1, pins.size)
        assertEquals(1, computers.size)
        assertFalse(pins[0].enabled)
        assertFalse(computers[0].enabled)
    }

    // ── accelerators ──────────────────────────────────────────────────────────────────────────

    @Test
    fun menuAdvertisesTheShortcutsTheWindowAlreadyBinds() {
        val m = sections(live)
        fun sc(a: AppMenuAction) = m.item(a)!!.shortcut
        assertEquals(KeyShortcut(Key.N, meta = true), sc(AppMenuAction.NEW_SESSION))
        assertEquals(KeyShortcut(Key.O, meta = true), sc(AppMenuAction.OPEN_FOLDER))
        assertEquals(KeyShortcut(Key.K, meta = true), sc(AppMenuAction.QUICK_OPEN))
        assertEquals(KeyShortcut(Key.J, meta = true), sc(AppMenuAction.TOGGLE_TERMINAL))
        assertEquals(KeyShortcut(Key.LeftBracket, meta = true), sc(AppMenuAction.GO_BACK))
        assertEquals(KeyShortcut(Key.RightBracket, meta = true), sc(AppMenuAction.GO_FORWARD))
        assertEquals(KeyShortcut(Key.Backslash, meta = true), sc(AppMenuAction.TOGGLE_SIDEBAR))
        assertEquals(KeyShortcut(Key.R, meta = true), sc(AppMenuAction.REFRESH))
        assertEquals(KeyShortcut(Key.Zero, meta = true), sc(AppMenuAction.SWITCH_COMPUTER))
        assertEquals(KeyShortcut(Key.Comma, meta = true), sc(AppMenuAction.SETTINGS))
        assertEquals(KeyShortcut(Key.Q, meta = true), sc(AppMenuAction.QUIT))
        assertEquals(KeyShortcut(Key.F, ctrl = true, meta = true), sc(AppMenuAction.TOGGLE_FULLSCREEN))
    }

    @Test
    fun noAcceleratorIsClaimedTwice() {
        // Two rows on one accelerator means one of them can never fire — and on macOS the loser is
        // silently whichever AppKit reaches first.
        val all = sections(live.copy(pins = listOf("a", "b"))).items().mapNotNull { it.shortcut }
        assertEquals(all.size, all.toSet().size, "duplicate accelerator in $all")
    }

    @Test
    fun refreshDoesNotStealTheReviewCentreChord() {
        // ⌘R is plain-meta; ⌘⇧R must still reach the window's own handler (Review Centre). This is the
        // "现有多处分支只检查 mod + key" hazard the design flags — an exact-modifier accelerator fixes it.
        val r = sections(live).item(AppMenuAction.REFRESH)!!.shortcut!!
        assertEquals(KeyShortcut(Key.R, meta = true), r)
        assertTrue(r != KeyShortcut(Key.R, meta = true, shift = true))
    }

    // ── the Edit menu's forwarding table ──────────────────────────────────────────────────────

    @Test
    fun editKeyStrokesMatchTheirOwnAccelerators() {
        // The forwarded keystroke IS the accelerator: the item takes the key away from the focused
        // component and hands the identical event straight back. A mismatch here would silently turn
        // "Paste" into some other command inside the composer or JediTerm.
        val meta = java.awt.event.InputEvent.META_DOWN_MASK
        val shift = java.awt.event.InputEvent.SHIFT_DOWN_MASK
        assertEquals(java.awt.event.KeyEvent.VK_Z to meta, editKeyStroke(AppMenuAction.EDIT_UNDO))
        assertEquals(java.awt.event.KeyEvent.VK_Z to (meta or shift), editKeyStroke(AppMenuAction.EDIT_REDO))
        assertEquals(java.awt.event.KeyEvent.VK_X to meta, editKeyStroke(AppMenuAction.EDIT_CUT))
        assertEquals(java.awt.event.KeyEvent.VK_C to meta, editKeyStroke(AppMenuAction.EDIT_COPY))
        assertEquals(java.awt.event.KeyEvent.VK_V to meta, editKeyStroke(AppMenuAction.EDIT_PASTE))
        assertEquals(java.awt.event.KeyEvent.VK_A to meta, editKeyStroke(AppMenuAction.EDIT_SELECT_ALL))
    }

    @Test
    fun onlyEditCommandsForward() {
        // Everything else runs a real business command; forwarding a keystroke for it would double-fire.
        AppMenuAction.entries.filter { !it.name.startsWith("EDIT_") }
            .forEach { assertNull(editKeyStroke(it), "$it must not forward a keystroke") }
        AppMenuAction.entries.filter { it.name.startsWith("EDIT_") }
            .forEach { assertNotNull(editKeyStroke(it), "$it must forward a keystroke") }
    }

    @Test
    fun dispatchWithoutAFocusOwnerIsANoOp() {
        // best-effort by design: nothing focused means nothing to edit, never a crash
        dispatchEditAction(AppMenuAction.EDIT_COPY, null)
        dispatchEditAction(AppMenuAction.REFRESH, null)
    }
}
