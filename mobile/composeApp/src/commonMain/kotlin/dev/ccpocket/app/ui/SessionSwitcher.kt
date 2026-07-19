package dev.ccpocket.app.ui

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
 *
 * The entry is deliberately ONE small element: the chat header already carries back, title, machine,
 * folder, model and the ⋯ menu, so anything wider would have to eat into the title.
 */

/**
 * Top-bar entry — a tab-counter-style square holding how many OTHER sessions you could jump to, with a dot
 * when one of them wants you. It renders nothing at zero: a "0" chip is pure noise in an already crowded
 * header, and with no other session there is nothing to switch to. The switcher appears the moment a second
 * session exists, which is also the first moment it has a job.
 */
@Composable
fun SessionStackChip(count: Int, attention: Boolean, onClick: () -> Unit) {
    if (count <= 0) return
    Box(Modifier.padding(end = 4.dp)) {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(8.dp))
                .border(1.dp, Tok.hair, RoundedCornerShape(8.dp))
                .clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (count > 9) "9+" else count.toString(),
                color = Tok.tx2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
            )
        }
        // attention rides the corner instead of recoloring the chip: the count stays readable and the
        // header keeps showing exactly one accent-colored thing at a time
        if (attention) Box(Modifier.align(Alignment.TopEnd).size(7.dp).clip(CircleShape).background(Tok.accent))
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
