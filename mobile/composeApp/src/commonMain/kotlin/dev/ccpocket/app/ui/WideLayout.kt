package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.wide_pick_session
import dev.ccpocket.app.theme.LocalFontScale
import dev.ccpocket.app.theme.Metric
import dev.ccpocket.app.theme.Tok
import org.jetbrains.compose.resources.stringResource

/**
 * Wide layout (issue #334) — the minimum viable answer to "an iPad runs the phone column stretched".
 *
 * There is no navigation model to rewrite here: the phone's routing is DERIVED state (convoId →
 * chat, sessionsDir → sessions, else directories), not a stack. So "two panes" is exactly that
 * derivation split across two columns — the list branch on the left, the chat branch on the right —
 * and every repository call the phone already makes keeps its meaning:
 *
 *  - opening a session sets convoId → the RIGHT pane fills; the left pane never moves;
 *  - the chat's own Back ([PocketRepository.backToBrowse]) nulls convoId but keeps sessionsDir →
 *    the right pane falls back to the placeholder, the left pane is untouched;
 *  - the sessions list's Back ([PocketRepository.backToDirectories]) nulls both — by design, since
 *    the #226 navigation fence requires an open chat to be torn down when its list is left — so the
 *    left pane returns to Projects and the right pane returns to the placeholder.
 *
 * The decision is made once at the root ([WideLayoutScope]) and read as [LocalWideLayout] everywhere
 * else, so a narrow window is byte-for-byte the app that shipped: every wide-only modifier below
 * short-circuits to the identity when the local is false. It is a device question before it is a
 * width question (#378): a phone turned sideways is as wide as a small tablet, and splitting it
 * squeezed the very chat the user had rotated for beside a list nobody asked to keep open. So a phone
 * never splits, and only a large screen measures its width against [WIDE_LAYOUT_MIN_WIDTH].
 */
val WIDE_LAYOUT_MIN_WIDTH = 700.dp

/** Left pane (the list column). Fixed like the desktop sidebar's default rather than proportional. */
// 380dp: the sessions dock ("+ New session" + the mode chip) and the quota strip both clipped at 340dp
// in English on the iPad store frames; 380 fits them and still leaves ≥ 640dp of chat at 1024pt.
private val LEFT_PANE_WIDTH = 380.dp

/** …clamped so the chat column never drops below a usable measure on a 700–740dp window. */
private val RIGHT_PANE_MIN_WIDTH = 360.dp

/** Readable measure for the transcript/composer column — prose past this reads as a banner, not a turn. */
val READABLE_MEASURE_MAX = 720.dp

/** Modals stay a modal's width instead of stretching a 6-button row across a 1366pt iPad. */
val SHEET_MEASURE_MAX = 560.dp

/**
 * What the app is running on — the half of the two-pane decision a width cannot answer (#378). It comes
 * from the platform ([platformLayoutDeviceClass]), never from the window's height, which a keyboard shrinks.
 */
internal enum class LayoutDeviceClass {
    /** A handset, a folded foldable included: one column in every orientation — rotating only widens it. */
    PHONE,

    /** A tablet, an unfolded foldable or a resizable window: two panes wherever the width holds them. */
    LARGE_SCREEN,
}

/**
 * The two-pane rule as a value, so a test can hand the root a phone or a tablet — and a threshold — without
 * the device. [LayoutDeviceClass.LARGE_SCREEN] at [WIDE_LAYOUT_MIN_WIDTH] is exactly the pre-#378 rule, and
 * it is what a desktop window keeps.
 */
internal data class WideLayoutPolicy(
    val deviceClass: LayoutDeviceClass,
    val minWidth: Dp = WIDE_LAYOUT_MIN_WIDTH,
) {
    fun isWide(availableWidth: Dp): Boolean =
        deviceClass == LayoutDeviceClass.LARGE_SCREEN && availableWidth >= minWidth
}

/**
 * True when the app gets two panes: a large screen whose window is wide enough ([WideLayoutPolicy]).
 * Default false: a screen mounted on its own (UI tests, [dev.ccpocket.app.showcase] marketing frames)
 * is the phone screen it has always been.
 */
val LocalWideLayout = staticCompositionLocalOf { false }

/**
 * The ONE place the device and the window width are turned into a layout decision. Wraps the whole app
 * content so the sheets that render outside the content stack (PocketSheet, SecureApprovalSheet) see the
 * same answer the panes do.
 *
 * [policy] defaults to this platform's device; tests hand it a phone or a tablet. On a phone the answer is
 * false at every width, so a rotation only re-measures the one column. A large screen's window can cross the
 * line under an open chat, and [ContentRouter] then only adds or removes the list beside it: the chat stays
 * where it is, with its scroll position, unsent draft and focus.
 */
@Composable
internal fun WideLayoutScope(
    modifier: Modifier = Modifier,
    policy: WideLayoutPolicy = WideLayoutPolicy(platformLayoutDeviceClass()),
    content: @Composable BoxScope.() -> Unit,
) {
    BoxWithConstraints(modifier) {
        CompositionLocalProvider(LocalWideLayout provides policy.isWide(maxWidth)) { content() }
    }
}

/**
 * The app's content routing. Narrow = the exact single-branch derivation the phone has always rendered;
 * wide = the same three screens, two at a time. [chatListStateForTest] reaches [ChatScreen] in either
 * shape, so a routing test can see where the transcript is parked across a resize.
 *
 * Both shapes are ONE row, and crossing between them changes only what stands beside the chat (#334, second
 * stage). A large screen's window can be resized across [WIDE_LAYOUT_MIN_WIDTH] under an open chat (iPad
 * multitasking, Android split screen). While each shape called [ChatScreen] from a parent of its own, every
 * crossing rebuilt it: the unsent draft, the reading position, a half-answered question and any open sheet were
 * gone. Moving it between those parents as movable content kept that state, but took its nodes out of the tree
 * and back, and a focused field that leaves the tree loses focus, so the keyboard still dropped. So the chat has
 * one call site in one parent at every width; the list column and its hairline come and go before it, and the
 * chat only re-measures. Closing the chat still removes it, and reopening composes a fresh one.
 */
@Composable
internal fun ContentRouter(
    repo: PocketRepository,
    onOpenFleet: () -> Unit = {},
    onOpenInbox: () -> Unit = {},
    chatListStateForTest: LazyListState? = null,
) {
    val wide = LocalWideLayout.current
    // switchingSession keeps the chat mounted across a chat→chat switch:
    // openSession nulls convoId while it waits for the daemon, and without
    // this the switcher bounced you out to a session list for a beat (#165)
    val chatOpen = repo.convoId.value != null || repo.switchingSession.value
    Row(Modifier.fillMaxSize()) {
        // the list column: beside the chat when wide, the whole width with nothing open, and gone under a narrow chat
        if (wide || !chatOpen) {
            Box((if (wide) Modifier.listPaneWidth() else Modifier.weight(1f)).fillMaxHeight()) {
                // the phone's list derivation in both shapes, so a sessions list opened in the wide left pane is the
                // sessions list, with its own Back to Projects
                if (repo.sessionsDir.value != null) SessionsScreen(repo, onOpenInbox = onOpenInbox)
                else DirectoryScreen(repo, onOpenFleet = onOpenFleet, onOpenInbox = onOpenInbox)
            }
        }
        if (wide) Box(Modifier.width(Metric.hairline).fillMaxHeight().background(Tok.hair))
        // the chat's one call site: nothing here may wrap, key or branch it on the width, or a crossing takes it out
        // of the tree again
        if (chatOpen) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                ChatScreen(repo, onOpenFleet = onOpenFleet, onOpenInbox = onOpenInbox, listStateForTest = chatListStateForTest)
            }
        } else if (wide) {
            Box(Modifier.weight(1f).fillMaxHeight()) { EmptyChatPane() }
        }
    }
}

/**
 * The list column: [LEFT_PANE_WIDTH], given back down to [RIGHT_PANE_MIN_WIDTH] of chat on a 700–740dp window.
 * Measured here in layout rather than read from a BoxWithConstraints, which would subcompose what it wraps:
 * [ContentRouter] stays one plain row in both shapes, so the chat's parent never changes.
 */
private fun Modifier.listPaneWidth(): Modifier = layout { measurable, constraints ->
    val width = minOf(LEFT_PANE_WIDTH, constraints.maxWidth.toDp() - RIGHT_PANE_MIN_WIDTH)
        .roundToPx().coerceIn(constraints.minWidth, constraints.maxWidth)
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/** The right pane with nothing open. Deliberately quiet — the left pane is where the next tap is. */
@Composable
private fun EmptyChatPane() {
    Box(Modifier.fillMaxSize().background(Tok.base), contentAlignment = Alignment.Center) {
        Text(
            stringResource(Res.string.wide_pick_session),
            color = Tok.muted,
            fontSize = 14.sp * LocalFontScale.current,
        )
    }
}

/**
 * Width caps. Both are the IDENTITY in narrow mode — not merely a no-op cap but literally the same
 * modifier chain the phone shipped — and both must sit BEFORE `fillMaxWidth()` in the chain so the
 * cap clamps the constraints the fill then fills (the other order makes the fill win).
 *
 * Centring is the PARENT's job ([wideColumnAlignment]): a capped element inside a Column/LazyColumn
 * is centred by that container's horizontalAlignment, and inside a Box by its own `align`.
 */
@Composable
internal fun Modifier.readableMeasure(max: Dp = READABLE_MEASURE_MAX): Modifier =
    if (LocalWideLayout.current) this.widthIn(max = max) else this

/** …the same cap for modal shells: a sheet is a sheet, not a 1366pt-wide button row. */
@Composable
internal fun Modifier.sheetMeasure(max: Dp = SHEET_MEASURE_MAX): Modifier =
    if (LocalWideLayout.current) this.widthIn(max = max) else this

/** Column/LazyColumn alignment that centres capped children when wide and changes nothing when not. */
internal val wideColumnAlignment: Alignment.Horizontal
    @Composable get() = if (LocalWideLayout.current) Alignment.CenterHorizontally else Alignment.Start
