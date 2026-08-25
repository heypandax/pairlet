@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.ccpocket.app.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.copy_path
import dev.ccpocket.app.resources.new_session_cta
import dev.ccpocket.app.resources.rewind_caption_fork
import dev.ccpocket.app.resources.rewind_caption_rewound
import dev.ccpocket.app.resources.ses_conn_connecting
import dev.ccpocket.app.resources.ses_conn_offline
import dev.ccpocket.app.resources.ses_conn_online
import dev.ccpocket.app.resources.ses_empty_sub
import dev.ccpocket.app.resources.ses_empty_title
import dev.ccpocket.app.resources.ses_messages
import dev.ccpocket.app.resources.sessions_title
import dev.ccpocket.app.theme.Metric
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.theme.TypeRole
import dev.ccpocket.app.ui.agentName
import dev.ccpocket.app.ui.relativeTime
import dev.ccpocket.app.ui.tilde
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.SessionSummary
import org.jetbrains.compose.resources.stringResource

/**
 * The Sessions surface (Mobile UI 2.0 · A Master Core v1 / Proofs frame 04).
 *
 * List-first and container-free: hierarchy comes from type, hairlines and spacing, so nothing here draws a
 * card or a shadow. The composables are deliberately dumb — they render a [SessionRowUi] the pure mapper
 * already decided on, and hand every tap back to the caller's existing routes.
 */

/** How the header names the link. Three honest outcomes; "connecting" never claims online. */
enum class ConnBadge { ONLINE, CONNECTING, OFFLINE }

/**
 * Title + the Computer → Project → path hierarchy the whole product is organised around.
 *
 * Every line is real or absent: [machine] null drops the name and leaves the link state alone, and the path
 * row renders the FULL workdir with its own 48 dp copy target rather than an ellipsis with no way out.
 * The project folder is NOT its own row — the path's tail already names it (the same "folder once" proof
 * Chat's expanded context passed), and printing it twice stacked identical words above each other.
 */
@Composable
fun SessionsContextHeader(
    machine: String?,
    conn: ConnBadge,
    workdir: String,
    modifier: Modifier = Modifier,
) {
    val connColor = when (conn) {
        ConnBadge.ONLINE -> Tok.ok
        ConnBadge.CONNECTING -> Tok.warn
        ConnBadge.OFFLINE -> Tok.danger
    }
    val connText = stringResource(
        when (conn) {
            ConnBadge.ONLINE -> Res.string.ses_conn_online
            ConnBadge.CONNECTING -> Res.string.ses_conn_connecting
            ConnBadge.OFFLINE -> Res.string.ses_conn_offline
        },
    )
    Column(modifier.fillMaxWidth().padding(horizontal = Metric.gutter)) {
        Text(stringResource(Res.string.sessions_title), color = Tok.tx, style = TypeRole.screenTitle)
        Row(
            Modifier.padding(top = Metric.gap),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metric.gapS),
        ) {
            StateMarkGlyph(StateMark.DOT, connColor)
            Text(
                machine?.takeIf { it.isNotBlank() }?.let { "$it · $connText" } ?: connText,
                color = Tok.tx2, fontSize = 14.sp, lineHeight = 19.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        PathWithCopy(workdir, Modifier.padding(top = 2.dp))
    }
}

/**
 * The full workdir beside a real 48 dp copy target.
 *
 * The path WRAPS instead of becoming a single-line half-truth. Sessions uses the default three-line ceiling
 * because copy always retains the complete string; Chat's bounded expanded context opts into unlimited lines
 * so that surface fulfils its stricter "full workdir without ellipsis" contract.
 */
@Composable
fun PathWithCopy(
    path: String,
    modifier: Modifier = Modifier,
    color: Color = Tok.muted,
    maxLines: Int = 3,
) {
    val clipboard = LocalClipboardManager.current
    val label = stringResource(Res.string.copy_path)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            tilde(path), color = color, style = TypeRole.metaMono,
            maxLines = maxLines,
            overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier.size(Metric.touch)
                .clip(RoundedCornerShape(Metric.radiusS))
                .clickable(role = Role.Button, onClickLabel = label) { clipboard.setText(AnnotatedString(path)) },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.ContentCopy, label, tint = Tok.tx2, modifier = Modifier.size(16.dp)) }
    }
}

/** An uppercase section label ("Active" / "Recent"). Quiet by construction — it orders, it does not shout. */
@Composable
fun SessionSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), color = Tok.tx2, fontSize = 11.sp, lineHeight = 15.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = 0.9.sp, modifier = modifier,
    )
}

/**
 * One session row.
 *
 * Anatomy: state mark · title (3 lines) · first prompt · metadata · optional state action. Only the
 * highest-priority state renders an action, and that action merely OPENS the session — Secure Approval and
 * QuestionCard stay the only places a request can actually be answered.
 */
/** The preview earns its lines only when it says MORE than the title: an untitled session's title IS its
 *  first prompt (scanner fallback), and printing the same words twice stacks identical lines. One owner
 *  for the rule — every list that pairs a title with a firstPrompt preview goes through here. */
fun SessionSummary.distinctPreview(): String? =
    firstPrompt.takeIf { it.isNotBlank() && it.trim() != title.trim() }

@Composable
fun SessionListRow(
    row: SessionRowUi,
    onOpen: () -> Unit,
    onLongPress: (() -> Unit)?,
    modifier: Modifier = Modifier,
    // #282 · design frame D1: an optional lineage line ("⑂ fork of ‹parent›"), rendered INSIDE the text
    // column between the preview and the metadata. A slot rather than a String so the row stays ignorant
    // of lineage vocabulary, and so every list that does not have lineage pays nothing for it.
    caption: (@Composable () -> Unit)? = null,
) {
    val s = row.session
    val tint = stateColor(row.tone)
    val action = row.action
    val markSize = sessionStateMarkSize(LocalDensity.current.fontScale)
    Column(
        modifier.fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(vertical = Metric.gap),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            // The Sessions master makes the whole state vocabulary 10dp, rising to 14dp with large type.
            // Written state remains authoritative; the larger decorative mark preserves the fill ladder.
            Box(Modifier.padding(top = 7.dp)) {
                StateMarkGlyph(row.mark, tint, size = markSize, strokeWidth = SessionStateMarkStroke)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    s.title, color = Tok.tx, style = TypeRole.rowTitle,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
                s.distinctPreview()?.let {
                    Text(
                        it, color = Tok.tx2, style = TypeRole.preview,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp),
                    )
                }
                caption?.invoke()
                val meta = sessionMetaLine(row)
                if (meta.isNotBlank()) Text(
                    meta, color = Tok.tx2, style = TypeRole.metaMono,
                    maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        // the loudest thing on the screen belongs to the row that actually owns the intervention
        if (action != null) StateActionBand(row.state, action, tint, onOpen, Modifier.padding(start = 19.dp, top = 14.dp))
    }
}

// ── Rewind / fork lineage (issue #282, design frames D1/D2) ────────────────────────────────────────────

/** The two lineage glyphs. Never interchangeable: ⑂ branches FORWARD off a peer that is still there,
 *  ↩ points BACK from a folded original at whatever replaced it. */
const val FORK_GLYPH = "⑂"
const val REWOUND_GLYPH = "↩"

/**
 * One quiet line naming the OTHER end of a lineage edge.
 *
 * Both directions share this composable on purpose: "⑂ fork of X" and "↩ rewound → Y" are the same
 * sentence read from opposite ends, so letting them drift apart in size, ink or spacing would make two
 * unrelated-looking annotations out of one idea.
 */
@Composable
fun LineageCaption(glyph: String, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(top = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(glyph, color = Tok.muted, style = TypeRole.metaMono)
        Text(
            text, color = Tok.muted, fontSize = 13.sp, lineHeight = 18.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A fork child's caption: it names the peer it branched off, which is still in the list beside it. */
@Composable
fun ForkLineageCaption(parentTitle: String) =
    LineageCaption(FORK_GLYPH, stringResource(Res.string.rewind_caption_fork, parentTitle))

/**
 * A session the list folded away because a rewind replaced it (design frame D2).
 *
 * Deliberately NOT a [SessionListRow]. A superseded original has no live state worth a coloured mark, no
 * preview worth two more lines, and no metadata worth a line at all — the row is inside a group whose
 * header already says what these are, and repeating "Complete · Claude · main · 2h ago" for each one
 * would make the fold as loud as the list it was supposed to shorten. What remains is the only thing
 * still worth reading: the title, in secondary ink behind a hollow ring, and the caption that says where
 * the conversation actually continued.
 *
 * It still opens, and still long-presses. Folded is not deleted — that is the whole promise the group
 * makes, and a row you cannot tap would break it.
 */
@Composable
fun RewoundSessionRow(
    session: SessionSummary,
    successorTitle: String?,
    onOpen: () -> Unit,
    onLongPress: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val markSize = sessionStateMarkSize(LocalDensity.current.fontScale)
    Row(
        modifier.fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(vertical = Metric.gap),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        // RING, in muted ink: the settled mark drained of its state colour. Same footprint as every other
        // row's mark, so the folded titles stay on the list's optical left edge instead of stepping in.
        Box(Modifier.padding(top = 7.dp)) {
            StateMarkGlyph(StateMark.RING, Tok.muted, size = markSize, strokeWidth = SessionStateMarkStroke)
        }
        Column(Modifier.weight(1f)) {
            Text(
                session.title, color = Tok.tx2, style = TypeRole.rowTitle,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            // null only when the successor left this view (another agent filter, another project). The row
            // then reads as a plain quiet session rather than claiming a destination it cannot name.
            successorTitle?.let {
                LineageCaption(REWOUND_GLYPH, stringResource(Res.string.rewind_caption_rewound, it))
            }
        }
    }
}

/** The attention band: the written state and the one control that takes you to its decision surface. */
@Composable
private fun StateActionBand(
    state: SurfaceState,
    action: StateAction,
    tint: Color,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(11.dp)
    val actionLabel = stateActionLabel(action)
    Row(
        modifier.fillMaxWidth().heightIn(min = Metric.touch)
            .clip(shape).background(tint.copy(alpha = 0.10f)).border(Metric.hairline, tint.copy(alpha = 0.42f), shape)
            .padding(start = 13.dp, end = Metric.gapS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Tok.tx, not the tone: the state must clear contrast in BOTH palettes. Tone lives on the mark,
        // the border and the fill, so the signal is still shape + colour + words.
        Text(
            stateLabel(state), color = Tok.tx, fontSize = 14.5.sp, lineHeight = 19.sp,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        )
        Spacer(Modifier.width(Metric.gap))
        Box(
            Modifier.sizeIn(minWidth = 72.dp, minHeight = 40.dp)
                .clip(RoundedCornerShape(9.dp)).background(Tok.accent)
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(horizontal = Metric.gapL, vertical = Metric.gapS),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                actionLabel, color = Tok.base, fontSize = 14.5.sp, lineHeight = 19.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
        }
    }
}

/**
 * "Running · Claude · feat/auth · 2h ago · 14 msg" — every fact real, every missing fact simply absent
 * (no `-` placeholder, no emoji standing in for a label).
 *
 * A row that already carries an action band omits the state word here: it is written once, loudly, there.
 */
@Composable
private fun sessionMetaLine(row: SessionRowUi): String {
    val s = row.session
    val parts = buildList {
        if (row.action == null) add(stateLabel(row.state))
        add(agentName(s.agent ?: AgentKind.CLAUDE))
        s.gitBranch?.takeIf { it.isNotBlank() }?.let { add(it) }
        if (s.lastModified > 0L) add(relativeTime(s.lastModified))
        if (s.messageCount > 0) add(stringResource(Res.string.ses_messages, s.messageCount))
    }
    return parts.joinToString(" · ")
}

/** Honest empty treatment — states the fact and points at the dock, rather than drawing a fake row. */
@Composable
fun SessionsEmptyState(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 32.dp)) {
        Text(stringResource(Res.string.ses_empty_title), color = Tok.tx, style = TypeRole.rowTitle)
        Text(
            stringResource(Res.string.ses_empty_sub), color = Tok.tx2, style = TypeRole.preview,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * The pinned bottom dock.
 *
 * A hairline button, never a filled one: the pending approval above must stay the only filled control on
 * the screen. [onStart] starts immediately under the persisted defaults; [defaultsChip] is the caller's
 * own chip, which opens the full picker WITHOUT starting anything — both tested paths survive intact.
 */
@Composable
fun NewSessionDock(
    starting: Boolean,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
    defaultsChip: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(13.dp)
    val cta = stringResource(Res.string.new_session_cta)
    // The dock owns the screen's bottom edge: its base fill runs to the physical edge and the
    // nav-bar/home-indicator inset REPLACES the bottom gap (union = max, not sum) — the button rides
    // just above the indicator instead of stacking gap + inset. Inset-less devices keep the plain gap.
    Column(
        modifier.fillMaxWidth().background(Tok.base)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets(bottom = Metric.gap))),
    ) {
        Hairline()
        Row(
            Modifier.fillMaxWidth().padding(start = Metric.gutter, end = Metric.gutter, top = Metric.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).heightIn(min = 52.dp)
                    .clip(shape).border(Metric.hairline, Tok.tx2.copy(alpha = 0.45f), shape)
                    .clickable(enabled = !starting, role = Role.Button, onClick = onStart)
                    .padding(horizontal = Metric.gap, vertical = Metric.gapS),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    cta, color = Tok.tx, fontSize = 17.sp, lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (starting) {
                    Spacer(Modifier.width(Metric.gapS))
                    CircularProgressIndicator(Modifier.size(14.dp), color = Tok.accent, strokeWidth = 1.5.dp)
                }
            }
            Spacer(Modifier.width(Metric.gap))
            Box(Modifier.heightIn(min = Metric.touch), contentAlignment = Alignment.Center) { defaultsChip() }
        }
    }
}
