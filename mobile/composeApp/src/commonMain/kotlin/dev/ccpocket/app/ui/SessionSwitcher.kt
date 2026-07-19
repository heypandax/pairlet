package dev.ccpocket.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.SessionSwitcherItem
import dev.ccpocket.app.data.SessionWorkingSet
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import org.jetbrains.compose.resources.stringResource

/**
 * Cross-project session switcher (issue #165). Moving between the two or three sessions you are actually
 * juggling used to mean walking the whole navigation back out to the project list and in again; this lands
 * you in any of them in one tap, without leaving the chat.
 */

/**
 * Composer-accessory entry — a stack glyph + the count of OTHER sessions you could jump to, with a dot when
 * one of them wants you (design: switcher-entry-placement.jsx, option A).
 *
 * It shipped first as a bare 28dp bordered square in the CHAT HEADER and failed twice in the field: the
 * header had no width left to give (back · title · machine · folder · model · running · ⋯), and a box
 * holding only a number reads as a status badge, not a control. Both are the disease the two-layer composer
 * already cured for the model chip (issue #157 follow-up), so the entry takes the same medicine: down to
 * the accessory row, in the model chip's exact pill grammar, with a glyph that shows what it means instead
 * of leaving it to be inferred.
 *
 * Honest tradeoff (design flagged it): this row otherwise answers "what will this message do", and a
 * control that yanks you into a DIFFERENT chat sits a thumb-width from Send. The pill stays quiet and
 * never accent-filled so Send keeps being the loudest thing here.
 *
 * Renders nothing at zero — with no other session there is nothing to switch to, and the row collapses
 * back to exactly the shipped attach · chip · action layout with no gap.
 */
@Composable
fun SessionStackChip(count: Int, attention: Boolean, onClick: () -> Unit) {
    if (count <= 0) return
    val cd = stringResource(Res.string.switcher_open)
    Box {
        Row(
            Modifier.height(30.dp).clip(RoundedCornerShape(999.dp)).background(Tok.raised)
                .border(1.dp, Tok.hair, RoundedCornerShape(999.dp))
                .clickable { onClick() }
                .padding(start = 9.dp, end = 10.dp)
                .semantics { contentDescription = cd },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SessionStackGlyph(Tok.tx2, Modifier.size(13.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                // TightCenter for the same reason the model chip needs it: mono ascent/descent are
                // asymmetric, so a raw Text rides high inside the pill even under CenterVertically
                if (count > 9) "9+" else count.toString(),
                color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 11.sp, style = TightCenter,
                maxLines = 1,
            )
        }
        // attention rides the corner rather than recoloring the pill: the count stays readable, and the
        // composer keeps exactly one accent-colored thing at a time (Send)
        if (attention) Box(
            Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp)
                .size(7.dp).clip(CircleShape).background(Tok.accent),
        )
    }
}

/** 13dp "stacked cards" glyph — two offset rounded rects, 1.5pt stroke like the rest of the line icons. */
@Composable
private fun SessionStackGlyph(tint: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val r = 1.5.dp.toPx()
        val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        // back card peeks out top-right; front card sits full — reads as "more than one of these"
        drawRoundRect(
            tint, topLeft = Offset(w * 0.30f, 0f), size = Size(w * 0.70f, h * 0.70f),
            cornerRadius = CornerRadius(r, r), style = stroke,
        )
        drawRoundRect(
            tint, topLeft = Offset(0f, h * 0.30f), size = Size(w * 0.70f, h * 0.70f),
            cornerRadius = CornerRadius(r, r), style = stroke,
        )
    }
}

/**
 * The switcher sheet. The order is the whole design: the session on screen sits pinned at the top so you
 * can always see where you are, then everything alive anywhere (the same rule the home screen's Active
 * section uses), then the most-recently-opened — whose FIRST row is the session you just came from, so
 * bouncing between two projects is tap-tap and never a search.
 */
@Composable
fun SessionSwitcherSheet(
    set: SessionWorkingSet,
    onSelect: (SessionSwitcherItem) -> Unit,
    onAllProjects: () -> Unit,
    onDismiss: () -> Unit,
) {
    PocketSheet(onDismiss = onDismiss) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(Res.string.sessions_title), color = Tok.tx, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(Res.string.switcher_total, set.otherCount + if (set.current != null) 1 else 0),
                color = Tok.muted, fontSize = 12.sp,
            )
        }
        // scrolls rather than clips: a busy fleet can fill more than the cap, and a row you cannot reach
        // is the same as a row that isn't there
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            set.current?.let { SwitcherRow(it, onClick = null) }
            if (set.running.isNotEmpty()) {
                SwitcherLabel(stringResource(Res.string.dir_active))
                set.running.forEach { s -> SwitcherRow(s) { onSelect(s); onDismiss() } }
            }
            if (set.recent.isNotEmpty()) {
                SwitcherLabel(stringResource(Res.string.switcher_recent))
                set.recent.forEach { s -> SwitcherRow(s) { onSelect(s); onDismiss() } }
            }
            if (set.otherCount == 0) Text(
                stringResource(Res.string.switcher_empty), color = Tok.muted, fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 22.dp),
            )
        }
        Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(1.dp).background(Tok.hair))
        // escape hatch back into the full navigation — the switcher covers the working set, not everything
        Row(
            Modifier.fillMaxWidth().clickable { onDismiss(); onAllProjects() }
                .padding(horizontal = 18.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(Res.string.switcher_all_projects), color = Tok.tx2, fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Rounded.ChevronRight, null, tint = Tok.muted, modifier = Modifier.size(17.dp))
        }
    }
}

/** Section caption — same weight and size as the home screen's PINNED / ACTIVE / PROJECTS labels. */
@Composable
private fun SwitcherLabel(text: String) = Text(
    text, color = Tok.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
    modifier = Modifier.padding(start = 20.dp, end = 18.dp, top = 12.dp, bottom = 2.dp),
)

/**
 * One row. A null [onClick] is the current session: it still renders so you can see where you are, but
 * taking a tap that goes nowhere would just feel broken.
 */
@Composable
private fun SwitcherRow(s: SessionSwitcherItem, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = 56.dp)
            .clip(RoundedCornerShape(11.dp))
            .let { if (onClick != null) it.clickable { onClick() } else it }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // the unread marker lives in a fixed gutter so every title starts on the same left edge,
        // whether or not its row carries one
        Box(Modifier.size(6.dp)) {
            if (s.unseen) Box(Modifier.size(6.dp).clip(CircleShape).background(Tok.accent))
        }
        Column(Modifier.weight(1f)) {
            Text(
                s.title, color = Tok.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                Text(
                    s.project, color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                // a live row's dot already says "this one is working"; the timestamp only means something
                // for a session you left behind
                if (!s.running && s.lastOpenedAt > 0) {
                    Text(" · ", color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text(
                        relativeTime(s.lastOpenedAt), color = Tok.muted, fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp, maxLines = 1,
                    )
                }
            }
        }
        when {
            s.current -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(stringResource(Res.string.switcher_current), color = Tok.muted, fontSize = 12.sp)
                Icon(Icons.Rounded.Check, null, tint = Tok.accent, modifier = Modifier.size(17.dp))
            }
            // accent while mid-turn, calm green when merely alive — the same two-tone rule the chat header's
            // connection dot follows, so "working" reads the same everywhere
            s.running -> PulseDot(if (s.executing) Tok.accent else Tok.ok, size = 7.dp)
        }
    }
}
