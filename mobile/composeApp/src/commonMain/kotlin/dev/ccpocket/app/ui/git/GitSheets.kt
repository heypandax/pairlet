package dev.ccpocket.app.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.PocketSheet
import dev.ccpocket.app.ui.relativeTime
import dev.ccpocket.protocol.GIT_OP_BRANCH
import dev.ccpocket.protocol.GIT_OP_CHECKOUT
import dev.ccpocket.protocol.GIT_OP_WORKTREE_REMOVE
import dev.ccpocket.protocol.GitActionPreview
import dev.ccpocket.protocol.GitBranchInfo
import dev.ccpocket.protocol.GitFileEntry
import org.jetbrains.compose.resources.stringResource

// ════════════════════════════════════════════════════════════════════
//  Screen C — the branch sheet, and Screen D — the destructive confirm
//  (issue #280). D is deliberately ONE sheet for both losses (revert a
//  file, check out over a dirty tree) and it also serves #281's worktree
//  removal, which rides the same single token semantics.
// ════════════════════════════════════════════════════════════════════

/**
 * Screen C. Local branches only — a remote-only branch would need a detached HEAD or a new tracking
 * branch, neither of which is on the allow-list. Current first with a check and the word "current";
 * everything else by last commit, which is the order people actually think in.
 */
@Composable
fun BranchSheet(repo: PocketRepository, onDismiss: () -> Unit) {
    val branches = repo.gitStatus.value?.branches.orEmpty()
    val current = branches.firstOrNull { it.current }?.name ?: repo.gitStatus.value?.branch
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val ordered = remember(branches) {
        branches.sortedWith(compareByDescending<GitBranchInfo> { it.current }.thenByDescending { it.lastCommitAt })
    }

    PocketSheet(onDismiss) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(stringResource(Res.string.git_branch_title), color = Tok.tx, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Box(Modifier.weight(1f))
            Text(
                stringResource(Res.string.git_branch_count, branches.size),
                color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))

        // creating is safe, so it sits above the list in accent; every other row is a checkout,
        // which may not be. The row expands IN PLACE rather than pushing a screen (C2).
        if (!creating) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 50.dp).clickable { creating = true }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("＋", color = Tok.accent, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                Text(stringResource(Res.string.git_new_branch), color = Tok.accent, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
            }
        } else {
            Column(
                Modifier.fillMaxWidth().background(Tok.accent.copy(alpha = 0.05f))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // the label states the base branch — the one fact a name cannot carry
                Text(
                    stringResource(Res.string.git_new_branch_from, current ?: "HEAD").uppercase(),
                    color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Box(
                        Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(11.dp)).background(Tok.raised)
                            .border(1.dp, Tok.accent, RoundedCornerShape(11.dp))
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (name.isEmpty()) Text(
                            stringResource(Res.string.git_new_branch_hint), color = Tok.muted,
                            fontFamily = FontFamily.Monospace, fontSize = 13.5.sp,
                        )
                        BasicTextField(
                            name, { name = it }, singleLine = true,
                            textStyle = TextStyle(color = Tok.tx, fontSize = 13.5.sp, fontFamily = FontFamily.Monospace),
                            cursorBrush = SolidColor(Tok.accent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    val on = name.isNotBlank()
                    Text(
                        stringResource(Res.string.git_create),
                        color = if (on) Tok.base else Tok.tx.copy(alpha = 0.34f),
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.height(44.dp).clip(RoundedCornerShape(11.dp))
                            .background(if (on) Tok.accent else Tok.accent.copy(alpha = 0.16f))
                            .then(if (on) Modifier.clickable { repo.gitAct(GIT_OP_BRANCH, branch = name.trim()); onDismiss() } else Modifier)
                            .padding(horizontal = 17.dp, vertical = 13.dp),
                    )
                }
            }
        }

        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            items(ordered, key = { it.name }) { b ->
                BranchRow(b, isCurrent = b.name == current) {
                    if (b.name != current) { repo.gitAct(GIT_OP_CHECKOUT, branch = b.name); onDismiss() }
                }
            }
        }
        Box(Modifier.height(10.dp))
    }
}

@Composable
private fun BranchRow(b: GitBranchInfo, isCurrent: Boolean, onClick: () -> Unit) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = !isCurrent, onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Box(Modifier.size(width = 16.dp, height = 16.dp), contentAlignment = Alignment.Center) {
                if (isCurrent) Text("✓", color = Tok.ok, fontSize = 13.sp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    b.name, color = if (isCurrent) Tok.tx else Tok.tx2,
                    fontFamily = FontFamily.Monospace, fontSize = 13.5.sp,
                    fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val when_ = if (b.lastCommitAt > 0) relativeTime(b.lastCommitAt) else ""
                Text(
                    if (isCurrent) listOf(stringResource(Res.string.git_branch_current), when_).filter { it.isNotEmpty() }.joinToString(" · ")
                    else when_,
                    color = Tok.muted, fontSize = 11.5.sp,
                )
            }
            if (!isCurrent) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Tok.muted, modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * Screen D — the shared destructive confirm, plus #281's worktree removal (C1 dirty / C2 blocked).
 * A [GitActionPreview.blocked] preview never becomes a red sheet: the badge turns grey, the panel is
 * informational, and the action button is inert. A confirmation the daemon will refuse anyway must
 * not look like one a tap can buy.
 */
@Composable
fun GitConfirmSheet(repo: PocketRepository, preview: GitActionPreview) {
    val worktree = preview.op == GIT_OP_WORKTREE_REMOVE
    val blocked = preview.blocked
    val danger = !blocked
    val ink = if (blocked) Tok.tx2 else Tok.danger

    PocketSheet({ repo.dismissGitConfirm() }) {
        Column(Modifier.padding(horizontal = 22.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(if (blocked) Tok.tx.copy(alpha = 0.06f) else Tok.danger.copy(alpha = 0.13f))
                    .border(1.3.dp, if (blocked) Tok.hair else Tok.danger.copy(alpha = 0.42f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (blocked) "i" else "!", color = ink, fontFamily = FontFamily.Monospace, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                when {
                    worktree -> stringResource(Res.string.wt_remove_title, preview.branch ?: preview.path.orEmpty())
                    preview.branch != null -> stringResource(Res.string.git_checkout_title, preview.branch!!)
                    else -> stringResource(Res.string.git_discard_title, preview.files.size)
                },
                color = Tok.tx, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp,
            )
            if (worktree) preview.path?.let {
                GitPathText(it, max = 40, color = Tok.tx2, fontSize = 12.sp)
            }

            if (blocked) {
                // C2: a session is running in this checkout — information, not a warning
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(Tok.surface)
                        .border(1.dp, Tok.hair, RoundedCornerShape(11.dp)).padding(13.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier.padding(top = 1.dp).size(16.dp).clip(CircleShape).border(1.3.dp, Tok.tx2, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Text("i", color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(stringResource(Res.string.wt_remove_blocked), color = Tok.tx, fontSize = 13.5.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
                        preview.blockedReason?.let { Text(it, color = Tok.tx2, fontSize = 12.sp, lineHeight = 17.5.sp) }
                    }
                }
            } else if (preview.files.isNotEmpty()) {
                LostFilesCard(preview.files, worktree)
            }

            Text(
                when {
                    worktree && blocked -> stringResource(Res.string.wt_remove_note_clean)
                    worktree -> stringResource(Res.string.wt_remove_note_dirty)
                    else -> stringResource(Res.string.git_discard_body)
                },
                color = Tok.tx2, fontSize = 13.sp, lineHeight = 20.sp,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(12.dp))
                    .border(1.dp, Tok.hair, RoundedCornerShape(12.dp))
                    .clickable { repo.dismissGitConfirm() },
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(Res.string.cancel), color = Tok.tx, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
            Box(
                Modifier.weight(if (worktree) 1f else 1.15f).height(50.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (danger) Tok.danger else Tok.danger.copy(alpha = 0.16f))
                    .then(if (danger) Modifier.clickable { repo.confirmPendingGit() } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (worktree) stringResource(Res.string.wt_remove) else stringResource(Res.string.git_discard_confirm),
                    color = if (danger) Tok.base else Tok.tx.copy(alpha = 0.34f),
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Box(Modifier.height(12.dp))
    }
}

/** The exact paths in mono with their counts — named, never summarised into a number the user has to
 *  trust. Framed in danger ink for a worktree removal (#281 C1), plain hairline for a revert (D). */
@Composable
private fun LostFilesCard(files: List<GitFileEntry>, worktree: Boolean) {
    val shape = RoundedCornerShape(11.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (worktree) Tok.danger.copy(alpha = 0.09f) else Tok.surface)
            .border(1.dp, if (worktree) Tok.danger.copy(alpha = 0.30f) else Tok.hair, shape),
    ) {
        if (worktree) Row(
            Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(
                Modifier.size(15.dp).clip(CircleShape).border(1.3.dp, Tok.danger, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("!", color = Tok.danger, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold) }
            Text(stringResource(Res.string.wt_remove_lost, files.size), color = Tok.danger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        files.forEachIndexed { i, f ->
            if (i > 0 || worktree) Box(
                Modifier.fillMaxWidth().height(1.dp)
                    .background(if (worktree) Tok.danger.copy(alpha = 0.16f) else Tok.hair),
            )
            Row(
                Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 13.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GitPathText(f.path, max = 30, color = Tok.tx2, fontSize = 12.sp, modifier = Modifier.weight(1f))
                // an untracked file has no counts to lose — it is the whole file, so say so
                if (f.adds == null && f.dels == null) Text(
                    stringResource(Res.string.git_new_file), color = Tok.ok,
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                ) else {
                    if ((f.adds ?: 0) > 0) Text("+${f.adds}", color = Tok.ok, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    if ((f.dels ?: 0) > 0) Text("−${f.dels}", color = Tok.danger, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }
    }
}
