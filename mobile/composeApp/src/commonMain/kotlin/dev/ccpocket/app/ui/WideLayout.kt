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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * The threshold is a pure width question, measured once at the root ([WideLayoutScope]) and read as
 * [LocalWideLayout] everywhere else, so a narrow window is byte-for-byte the app that shipped: every
 * wide-only modifier below short-circuits to the identity when the local is false.
 */
val WIDE_LAYOUT_MIN_WIDTH = 700.dp

/** Left pane (the list column). Fixed like the desktop sidebar's default rather than proportional. */
private val LEFT_PANE_WIDTH = 340.dp

/** …clamped so the chat column never drops below a usable measure on a 700–740dp window. */
private val RIGHT_PANE_MIN_WIDTH = 360.dp

/** Readable measure for the transcript/composer column — prose past this reads as a banner, not a turn. */
val READABLE_MEASURE_MAX = 720.dp

/** Modals stay a modal's width instead of stretching a 6-button row across a 1366pt iPad. */
val SHEET_MEASURE_MAX = 560.dp

/**
 * True when the app window is wide enough for two panes. Default false: a screen mounted on its own
 * (UI tests, [dev.ccpocket.app.showcase] marketing frames) is the phone screen it has always been.
 */
val LocalWideLayout = staticCompositionLocalOf { false }

/**
 * The ONE place the window width is turned into a layout decision. Wraps the whole app content so the
 * sheets that render outside the content stack (PocketSheet, SecureApprovalSheet) see the same answer
 * the panes do.
 */
@Composable
internal fun WideLayoutScope(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(modifier) {
        CompositionLocalProvider(LocalWideLayout provides (maxWidth >= WIDE_LAYOUT_MIN_WIDTH)) { content() }
    }
}

/**
 * The app's content routing. Narrow = the exact single-branch `when` the phone has always rendered;
 * wide = the same three screens, two at a time.
 */
@Composable
internal fun ContentRouter(
    repo: PocketRepository,
    onOpenFleet: () -> Unit = {},
    onOpenInbox: () -> Unit = {},
) {
    if (LocalWideLayout.current) WidePanes(repo, onOpenFleet, onOpenInbox)
    else NarrowContent(repo, onOpenFleet, onOpenInbox)
}

@Composable
private fun NarrowContent(
    repo: PocketRepository,
    onOpenFleet: () -> Unit,
    onOpenInbox: () -> Unit,
) {
    when {
        // switchingSession keeps the chat mounted across a chat→chat switch:
        // openSession nulls convoId while it waits for the daemon, and without
        // this the switcher bounced you out to a session list for a beat (#165)
        repo.convoId.value != null || repo.switchingSession.value ->
            ChatScreen(repo, onOpenFleet = onOpenFleet, onOpenInbox = onOpenInbox)
        repo.sessionsDir.value != null -> SessionsScreen(repo, onOpenInbox = onOpenInbox)
        else -> DirectoryScreen(repo, onOpenFleet = onOpenFleet, onOpenInbox = onOpenInbox)
    }
}

@Composable
private fun WidePanes(
    repo: PocketRepository,
    onOpenFleet: () -> Unit,
    onOpenInbox: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val left = minOf(LEFT_PANE_WIDTH, maxWidth - RIGHT_PANE_MIN_WIDTH)
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(left).fillMaxHeight()) {
                // the list column follows the SAME derivation as the phone's list branches, so a
                // sessions list opened here is the sessions list, with its own Back to Projects
                if (repo.sessionsDir.value != null) SessionsScreen(repo, onOpenInbox = onOpenInbox)
                else DirectoryScreen(repo, onOpenFleet = onOpenFleet, onOpenInbox = onOpenInbox)
            }
            Box(Modifier.width(Metric.hairline).fillMaxHeight().background(Tok.hair))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (repo.convoId.value != null || repo.switchingSession.value) {
                    ChatScreen(repo, onOpenFleet = onOpenFleet, onOpenInbox = onOpenInbox)
                } else {
                    EmptyChatPane()
                }
            }
        }
    }
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
