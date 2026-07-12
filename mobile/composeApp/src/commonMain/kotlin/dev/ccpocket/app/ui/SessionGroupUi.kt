@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.SessionGroup
import dev.ccpocket.protocol.SessionSummary
import org.jetbrains.compose.resources.stringResource

/** Collapse-map key for the trailing "ungrouped" bucket (real group ids are daemon-minted, never blank). */
internal const val UNGROUPED_KEY = "__ungrouped__"

/** One rendered stripe of the grouped session list: a defined [group] (header + its rows) or the trailing
 *  ungrouped bucket ([group] == null). */
internal data class SessionSection(val group: SessionGroup?, val sessions: List<SessionSummary>)

/**
 * Fold [sessions] into ordered group sections plus a trailing "ungrouped" bucket (issue #119).
 *
 * - [groups] empty (older daemon / guest / no groups yet) → a single ungrouped section holding everything;
 *   the caller renders it flat, with no header.
 * - Otherwise every defined group yields a section in `order` (kept even when empty, so a freshly created
 *   group stays visible and manageable), followed by the ungrouped bucket — emitted only when it has rows.
 * - A row whose [SessionSummary.group] names no live group (a stale id after a delete race) falls into the
 *   ungrouped bucket rather than vanishing.
 *
 * Membership order within a section is preserved from [sessions] (the daemon already sorts by recency).
 */
internal fun sessionSections(sessions: List<SessionSummary>, groups: List<SessionGroup>): List<SessionSection> {
    if (groups.isEmpty()) return listOf(SessionSection(null, sessions))
    val ids = groups.mapTo(HashSet()) { it.id }
    val byGroup = sessions.groupBy { it.group?.takeIf { g -> g in ids } }
    val sections = groups.sortedBy { it.order }.map { g -> SessionSection(g, byGroup[g.id].orEmpty()) }
    val ungrouped = byGroup[null].orEmpty()
    return if (ungrouped.isEmpty()) sections else sections + SessionSection(null, ungrouped)
}

/** A single session row — shared by the flat list and every group section. [onLongPress] (set only in the
 *  grouped view) opens the move-to-group menu; the tap always resumes/opens the session. */
@Composable
internal fun SessionRow(repo: PocketRepository, dir: String, s: SessionSummary, onLongPress: (() -> Unit)?) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface)
            .combinedClickable(
                onClick = { repo.openSession(dir, s.sessionId, title = s.title, agent = s.agent ?: AgentKind.CLAUDE) },
                onLongClick = onLongPress,
            ).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.title, color = Tok.tx, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            AgentBadge(s.agent, gap = 8.dp) // shows only for Codex (so resume opens the right backend)
            if (s.live || s.busy) { // running, or idle with background work still going
                Spacer(Modifier.width(8.dp))
                PulseDot(Tok.ok)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(Res.string.running), color = Tok.ok, fontSize = 11.sp)
            }
        }
        if (s.firstPrompt.isNotBlank()) Text(
            s.firstPrompt, color = Tok.tx2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            "💬 ${s.messageCount} · ⑂ ${s.gitBranch ?: "-"} · ${relativeTime(s.lastModified)}",
            color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/** A collapsible group header: chevron + name + count. Tap toggles [collapsed]; the ⋯ (and a long-press
 *  when [onManage] is set) opens rename/delete. The ungrouped bucket passes [onManage] == null (nothing to
 *  rename or delete). */
@Composable
internal fun GroupHeader(name: String, count: Int, collapsed: Boolean, onToggle: () -> Unit, onManage: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onToggle, onLongClick = onManage)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (collapsed) Icons.Rounded.KeyboardArrowRight else Icons.Rounded.KeyboardArrowDown,
            null, tint = Tok.tx2, modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(name, color = Tok.tx2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(6.dp))
        Text(count.toString(), color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        if (onManage != null) {
            Icon(
                Icons.Rounded.MoreHoriz, null, tint = Tok.muted,
                modifier = Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onManage).padding(2.dp),
            )
        }
    }
}

/** The "+ New group" affordance at the top of the list — the bootstrap path for the very first group. */
@Composable
internal fun NewGroupRow(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Add, null, tint = Tok.tx2, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(Res.string.group_new), color = Tok.tx2, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** Text-input dialog reused for creating and renaming a group. [initial] seeds the field (rename). */
@Composable
private fun GroupNameDialog(title: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tok.raised,
        titleContentColor = Tok.tx,
        textContentColor = Tok.tx2,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                name, { name = it },
                singleLine = true,
                placeholder = { Text(stringResource(Res.string.group_name_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) { onConfirm(name.trim()); onDismiss() } }) {
                Text(stringResource(Res.string.done), color = Tok.accent)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(Res.string.cancel), color = Tok.muted) } },
    )
}

/** New-group name prompt. */
@Composable
internal fun NewGroupDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) =
    GroupNameDialog(stringResource(Res.string.group_new), "", onConfirm, onDismiss)

/** Rename an existing [group], field seeded with its current name. */
@Composable
internal fun RenameGroupDialog(group: SessionGroup, onConfirm: (String) -> Unit, onDismiss: () -> Unit) =
    GroupNameDialog(stringResource(Res.string.group_rename), group.name, onConfirm, onDismiss)

/** Delete confirmation — deleting a group frees its sessions back to "ungrouped" (never deletes sessions). */
@Composable
internal fun DeleteGroupConfirm(group: SessionGroup, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tok.raised,
        titleContentColor = Tok.tx,
        textContentColor = Tok.tx2,
        title = { Text(group.name) },
        text = { Text(stringResource(Res.string.group_delete_confirm), color = Tok.tx2, fontSize = 14.sp, lineHeight = 21.sp) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(stringResource(Res.string.group_delete), color = Tok.danger) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(Res.string.cancel), color = Tok.muted) } },
    )
}

/** Group-header ⋯ / long-press menu: rename or delete. */
@Composable
internal fun GroupActionsSheet(group: SessionGroup, onRename: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    PocketSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 14.dp, top = 4.dp)) {
            Text(group.name, color = Tok.tx, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            SheetActionRow(stringResource(Res.string.group_rename), Tok.tx) { onRename(); onDismiss() }
            SheetActionRow(stringResource(Res.string.group_delete), Tok.danger) { onDelete(); onDismiss() }
        }
    }
}

/** Session-row long-press menu: file this session under a group, or lift it back to ungrouped. */
@Composable
internal fun MoveSessionSheet(
    session: SessionSummary,
    groups: List<SessionGroup>,
    onAssign: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    PocketSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 14.dp, top = 4.dp)) {
            Text(stringResource(Res.string.group_move_to), color = Tok.tx, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(session.title, color = Tok.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            groups.sortedBy { it.order }.forEach { g ->
                val current = session.group == g.id
                Row(
                    Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface)
                        .clickable(enabled = !current) { onAssign(g.id); onDismiss() }
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(g.name, color = Tok.tx, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (current) Icon(Icons.Rounded.Check, null, tint = Tok.accent, modifier = Modifier.size(18.dp))
                }
            }
            // "Remove from group" only when this session is actually filed under one.
            if (session.group != null) {
                SheetActionRow(stringResource(Res.string.group_move_out), Tok.tx2) { onAssign(null); onDismiss() }
            }
        }
    }
}

/** A tappable, full-width label row for the group action sheets (matches the ProjectActionsSheet look). */
@Composable
private fun SheetActionRow(label: String, color: Color, onClick: () -> Unit) {
    Row(
        Modifier.padding(top = 9.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = color, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
    }
}
