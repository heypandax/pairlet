package dev.ccpocket.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.GitChip
import dev.ccpocket.app.data.GitRowAction
import dev.ccpocket.app.data.commitBlockedBy
import dev.ccpocket.app.data.conflictShape
import dev.ccpocket.app.data.divergenceText
import dev.ccpocket.app.data.gitInSync
import dev.ccpocket.app.data.gitSections
import dev.ccpocket.app.data.midTruncatePath
import dev.ccpocket.app.data.parseUnifiedDiff
import dev.ccpocket.app.data.repoBasename
import dev.ccpocket.app.data.worktreeRemovable
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.DiffEmptyState
import dev.ccpocket.app.ui.git.GitDiffPaneBody
import dev.ccpocket.app.ui.git.GitErrorStrip
import dev.ccpocket.app.ui.git.GitPathText
import dev.ccpocket.app.ui.git.GitStatusChip
import dev.ccpocket.app.ui.git.conflictShapeLabel
import dev.ccpocket.protocol.GIT_OP_BRANCH
import dev.ccpocket.protocol.GIT_OP_CHECKOUT
import dev.ccpocket.protocol.GIT_OP_COMMIT
import dev.ccpocket.protocol.GIT_OP_FETCH
import dev.ccpocket.protocol.GIT_OP_PULL
import dev.ccpocket.protocol.GIT_OP_PUSH
import dev.ccpocket.protocol.GIT_OP_REVERT
import dev.ccpocket.protocol.GIT_OP_STAGE
import dev.ccpocket.protocol.GIT_OP_UNSTAGE
import dev.ccpocket.protocol.GIT_OP_WORKTREE_REMOVE
import dev.ccpocket.protocol.GitActionPreview
import dev.ccpocket.protocol.GitBranchInfo
import dev.ccpocket.protocol.GitFileEntry
import dev.ccpocket.protocol.WorktreeEntry
import org.jetbrains.compose.resources.stringResource

// ════════════════════════════════════════════════════════════════════
//  Git — the desktop face of issues #280/#281. Same overlay language as
//  Changes (⌘K scrim, two panes, footer band) and the SAME composables
//  as mobile for everything inside a row, so the two surfaces cannot
//  drift; only the density and the chrome differ.
// ════════════════════════════════════════════════════════════════════

/** The chat-header branch pill — the Git overlay's entrance, next to the "± N" changes pill. Hidden
 *  until the daemon has said this workdir is a repository at all. */
@Composable
fun GitPill(model: DesktopModel) {
    LaunchedEffect(model.selectedSessionId, model.streaming) {
        if (model.hasChat && !model.streaming) model.fetchGitStatus(withBranches = true)
    }
    val status = model.gitStatus ?: return
    if (status.notARepo || !status.ok) return
    val branch = status.branch ?: return
    val divergence = divergenceText(status)
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).border(1.dp, Tok.hair, RoundedCornerShape(999.dp))
            .clickable { model.openGit() }.padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text("⎇", color = Tok.tx2, fontFamily = Dk.mono, fontSize = 11.sp, style = tightCenter(11.sp))
        Text(
            branch, color = Tok.tx2, fontFamily = Dk.mono, fontSize = 11.sp, style = tightCenter(11.sp),
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp),
        )
        if (divergence.isNotEmpty()) Text(
            divergence, color = Tok.accent, fontFamily = Dk.mono, fontSize = 11.sp, style = tightCenter(11.sp),
        )
    }
}

@Composable
fun GitOverlay(model: DesktopModel, onDismiss: () -> Unit) {
    val status = model.gitStatus
    var message by remember(model.selectedSessionId) { mutableStateOf("") }
    var branchesOpen by remember { mutableStateOf(false) }
    val sections = remember(status) { status?.let { gitSections(it) }.orEmpty() }

    Box(
        Modifier.widthIn(max = 1040.dp).fillMaxWidth(0.92f).heightIn(max = 660.dp).fillMaxHeight(0.9f)
            .shadow(30.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
            .background(Tok.raised).border(1.dp, Tok.hair, RoundedCornerShape(14.dp)),
    ) {
        Column(Modifier.fillMaxSize()) {
            // ── header: branch, divergence, upstream, worktrees, refresh, close ──
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = status != null && !status.notARepo) {
                            model.fetchGitStatus(withBranches = true); branchesOpen = !branchesOpen
                        }
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text("⎇", color = Tok.tx2, fontFamily = Dk.mono, fontSize = 12.sp)
                    Text(
                        status?.branch ?: "…", color = Tok.tx, fontFamily = Dk.mono, fontSize = 13.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1,
                    )
                    Text("▾", color = Tok.muted, fontSize = 9.sp)
                    val divergence = divergenceText(status)
                    when {
                        gitInSync(status) -> Text(stringResource(Res.string.git_in_sync), color = Tok.ok, fontFamily = Dk.mono, fontSize = 11.5.sp)
                        divergence.isNotEmpty() -> Text(divergence, color = Tok.tx2, fontFamily = Dk.mono, fontSize = 11.5.sp)
                    }
                }
                status?.upstream?.let { Text(it, color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp) }
                Box(Modifier.weight(1f))
                if ((status?.worktreeCount ?: 0) > 1) Text(
                    stringResource(Res.string.wt_count, status?.worktreeCount ?: 0),
                    color = Tok.tx2, fontFamily = Dk.mono, fontSize = 11.sp,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).border(1.dp, Tok.hair, RoundedCornerShape(999.dp))
                        .clickable { model.openWorktrees() }.padding(horizontal = 9.dp, vertical = 4.dp),
                )
                if (model.gitStatusLoading) CircularProgressIndicator(Modifier.size(14.dp), color = Tok.tx2, strokeWidth = 1.6.dp)
                else Icon(
                    Icons.Rounded.Refresh, stringResource(Res.string.git_refresh), tint = Tok.tx2,
                    modifier = Modifier.size(22.dp).clip(RoundedCornerShape(999.dp))
                        .clickable { model.fetchGitStatus(withBranches = true) }.padding(3.dp),
                )
                Icon(
                    Icons.Rounded.Close, stringResource(Res.string.close), tint = Tok.tx2,
                    modifier = Modifier.size(24.dp).clip(RoundedCornerShape(999.dp)).clickable(onClick = onDismiss).padding(3.dp),
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))

            when {
                status == null && model.gitStatusStale ->
                    Box(Modifier.fillMaxWidth().heightIn(min = 320.dp)) {
                        DiffEmptyState(glyph = ">_", title = stringResource(Res.string.git_unavailable), caption = null)
                    }
                status == null -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Tok.accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                }
                status.notARepo -> Box(Modifier.fillMaxWidth().weight(1f)) {
                    DiffEmptyState(glyph = "⎇", title = stringResource(Res.string.git_not_a_repo), caption = null)
                }
                else -> Row(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.width(320.dp).fillMaxHeight().background(Tok.raised)) {
                        if (sections.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(stringResource(Res.string.git_clean_title), color = Tok.tx2, fontFamily = Dk.ui, fontSize = 13.sp)
                        } else LazyColumn(Modifier.fillMaxSize()) {
                            sections.forEach { section ->
                                item(key = "h-${section.key}") { SectionHeader(section.key, section.chip, section.rows.size) }
                                items(section.rows, key = { "${section.key}:${it.path}" }) { entry ->
                                    DesktopGitRow(
                                        entry, section.chip, section.action,
                                        selected = entry.path == model.gitDiffPath,
                                        onOpen = { model.openGitDiff(entry.path, staged = section.chip == GitChip.STAGED) },
                                        onAct = {
                                            when (section.action) {
                                                GitRowAction.STAGE -> model.gitAct(GIT_OP_STAGE, paths = listOf(entry.path))
                                                GitRowAction.UNSTAGE -> model.gitAct(GIT_OP_UNSTAGE, paths = listOf(entry.path))
                                                GitRowAction.NONE -> Unit
                                            }
                                        },
                                    )
                                }
                            }
                            if (status.truncated) item(key = "__trunc") {
                                Text(
                                    stringResource(Res.string.git_truncated), color = Tok.warn, fontFamily = Dk.ui, fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                )
                            }
                        }
                    }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Tok.hair))
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        val path = model.gitDiffPath
                        if (path == null) Box(Modifier.fillMaxSize()) {
                            DiffEmptyState(glyph = "±", title = stringResource(Res.string.diff_none), caption = null)
                        } else GitDiffPane(model, path)
                    }
                }
            }

            // ── footer band: the error strip, the composer and the three remote verbs ──
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
            GitFooter(model, message, onMessage = { message = it }, onCommitted = { message = "" })
        }

        // the branch list, anchored under the header chip (mobile raises the same content as a sheet)
        if (branchesOpen) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { branchesOpen = false } })
            BranchPopover(model, status?.branches.orEmpty(), status?.branch) { branchesOpen = false }
        }
        // the two-step confirm, centered over the overlay it belongs to
        model.gitPendingConfirm?.let { GitConfirmDialog(model, it) }
    }
}

@Composable
private fun SectionHeader(key: String, chip: GitChip, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(
                when (key) {
                    "conflicts" -> Res.string.git_sec_conflicts
                    "staged" -> Res.string.git_sec_staged
                    "untracked" -> Res.string.git_sec_untracked
                    else -> Res.string.git_sec_changes
                },
            ).uppercase(),
            color = if (chip == GitChip.CONFLICT) Tok.danger else Tok.muted,
            fontFamily = Dk.mono, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp,
        )
        Text("$count", color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.sp)
    }
}

/** One left-pane row, at ChangesOverlay's density: chip · path · counts, with the Stage/Unstage pill
 *  appearing on hover or selection so the column of pills does not shout down the list. */
@Composable
private fun DesktopGitRow(
    entry: GitFileEntry,
    chip: GitChip,
    action: GitRowAction,
    selected: Boolean,
    onOpen: () -> Unit,
    onAct: () -> Unit,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box(
        Modifier.fillMaxWidth()
            .background(
                when {
                    selected -> Tok.surface
                    hovered -> Color.White.copy(alpha = 0.025f)
                    else -> Color.Transparent
                },
            )
            .hoverable(hover).clickable(onClick = onOpen),
    ) {
        if (selected) Box(
            Modifier.align(Alignment.CenterStart).fillMaxHeight().padding(vertical = 5.dp)
                .width(2.dp).clip(RoundedCornerShape(2.dp)).background(Tok.accent),
        )
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            GitStatusChip(entry.code, chip, dense = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                GitPathText(entry.path, max = 34, color = Tok.tx, fontSize = 12.sp)
                val shape = conflictShape(entry)
                if (shape != null) Text(
                    conflictShapeLabel(shape), color = Tok.muted, fontFamily = Dk.ui, fontSize = 10.5.sp,
                ) else if ((entry.adds ?: 0) > 0 || (entry.dels ?: 0) > 0) Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if ((entry.adds ?: 0) > 0) Text("+${entry.adds}", color = Tok.ok, fontFamily = Dk.mono, fontSize = 10.5.sp)
                    if ((entry.dels ?: 0) > 0) Text("−${entry.dels}", color = Tok.danger, fontFamily = Dk.mono, fontSize = 10.5.sp)
                }
            }
            if (action != GitRowAction.NONE && (hovered || selected)) Text(
                stringResource(if (action == GitRowAction.STAGE) Res.string.git_stage else Res.string.git_unstage),
                color = Tok.tx2, fontFamily = Dk.ui, fontSize = 11.5.sp,
                modifier = Modifier.clip(RoundedCornerShape(7.dp)).border(1.dp, Tok.hair, RoundedCornerShape(7.dp))
                    .clickable(onClick = onAct).padding(horizontal = 9.dp, vertical = 4.dp),
            )
        }
    }
}

/** The right pane: path · Working|Staged · totals, then the diff at desktop density. */
@Composable
private fun GitDiffPane(model: DesktopModel, path: String) {
    val diff = model.gitDiff
    val staged = model.gitDiffStaged
    val ext = path.substringAfterLast('.', "").lowercase()
    Row(
        Modifier.fillMaxWidth().background(Tok.surface).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        GitPathText(path, max = 52, color = Tok.tx2, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Row(Modifier.clip(RoundedCornerShape(8.dp)).background(Tok.raised).padding(2.dp)) {
            @Composable
            fun seg(label: String, isStaged: Boolean) {
                val on = staged == isStaged
                Text(
                    label, color = if (on) Tok.tx else Tok.tx2, fontFamily = Dk.ui, fontSize = 11.5.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp))
                        .background(if (on) Tok.surface else Color.Transparent)
                        .clickable(enabled = !on) { model.openGitDiff(path, isStaged) }
                        .padding(horizontal = 11.dp, vertical = 5.dp),
                )
            }
            seg(stringResource(Res.string.git_working), isStaged = false)
            seg(stringResource(Res.string.git_staged), isStaged = true)
        }
        if (diff?.ok == true) {
            Text("+${diff.adds}", color = Tok.ok, fontFamily = Dk.mono, fontSize = 11.5.sp)
            Text("−${diff.dels}", color = Tok.danger, fontFamily = Dk.mono, fontSize = 11.5.sp)
            val hunks = remember(diff.diff) { parseUnifiedDiff(diff.diff ?: "").size }
            Text(
                if (hunks == 1) stringResource(Res.string.git_hunk_one) else stringResource(Res.string.git_hunks, hunks),
                color = Tok.muted, fontFamily = Dk.mono, fontSize = 11.5.sp,
            )
        }
        // the destructive verb is one level down and never acts immediately (design B3)
        Text(
            stringResource(Res.string.git_revert_file), color = Tok.danger, fontFamily = Dk.ui, fontSize = 11.5.sp,
            modifier = Modifier.clip(RoundedCornerShape(7.dp))
                .clickable { model.gitAct(GIT_OP_REVERT, paths = listOf(path)) }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
    GitDiffPaneBody(diff, ext = ext.ifEmpty { null }, dense = true, wrap = false, modifier = Modifier.fillMaxSize())
}

@Composable
private fun GitFooter(model: DesktopModel, message: String, onMessage: (String) -> Unit, onCommitted: () -> Unit) {
    val status = model.gitStatus
    val blocked = commitBlockedBy(status, message)
    val busy = model.gitBusyOp
    Column(Modifier.fillMaxWidth().background(Tok.raised).padding(horizontal = 16.dp, vertical = 11.dp)) {
        model.gitError?.let { err ->
            GitErrorStrip(
                title = when {
                    err.notFastForward -> stringResource(Res.string.git_pull_refused)
                    err.op == GIT_OP_PUSH -> stringResource(Res.string.git_push_rejected)
                    else -> stringResource(Res.string.git_action_failed)
                },
                detail = err.stderr.ifBlank { err.error },
                amber = err.notFastForward,
                onDismiss = { model.dismissGitError() },
            )
            Box(Modifier.height(9.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(
                Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(9.dp)).background(Tok.surface)
                    .border(1.dp, Tok.hair, RoundedCornerShape(9.dp)).padding(horizontal = 11.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (message.isEmpty()) Text(stringResource(Res.string.git_commit_hint), color = Tok.muted, fontFamily = Dk.ui, fontSize = 12.5.sp)
                BasicTextField(
                    message, onMessage, singleLine = true,
                    textStyle = TextStyle(color = Tok.tx, fontSize = 12.5.sp, fontFamily = Dk.ui),
                    cursorBrush = SolidColor(Tok.accent), modifier = Modifier.fillMaxWidth(),
                )
            }
            val on = blocked == null
            Box(
                Modifier.height(36.dp).clip(RoundedCornerShape(9.dp))
                    .background(if (on) Tok.accent else Tok.accent.copy(alpha = 0.16f))
                    .then(if (on) Modifier.clickable { model.gitAct(GIT_OP_COMMIT, message = message.trim()); onCommitted() } else Modifier)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(Res.string.git_commit),
                    color = if (on) Tok.base else Tok.tx.copy(alpha = 0.34f),
                    fontFamily = Dk.ui, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                )
            }
            FooterVerb(stringResource(if (busy == GIT_OP_FETCH) Res.string.git_fetching else Res.string.git_fetch), null, busy == GIT_OP_FETCH) { model.gitAct(GIT_OP_FETCH) }
            FooterVerb(
                stringResource(if (busy == GIT_OP_PULL) Res.string.git_pulling else Res.string.git_pull),
                stringResource(Res.string.git_pull_tail), busy == GIT_OP_PULL,
            ) { model.gitAct(GIT_OP_PULL) }
            val ahead = status?.ahead ?: 0
            FooterVerb(
                stringResource(if (busy == GIT_OP_PUSH) Res.string.git_pushing else Res.string.git_push),
                if (ahead > 0 && busy != GIT_OP_PUSH) "↑$ahead" else null, busy == GIT_OP_PUSH,
            ) { model.gitAct(GIT_OP_PUSH) }
        }
        val note = when (blocked) {
            dev.ccpocket.app.data.GitCommitBlock.CONFLICTS ->
                stringResource(Res.string.git_commit_blocked_conflicts, status?.conflicted?.size ?: 0)
            dev.ccpocket.app.data.GitCommitBlock.DETACHED -> stringResource(Res.string.git_commit_blocked_detached)
            else -> null
        }
        if (note != null) Text(
            note, color = Tok.danger, fontFamily = Dk.ui, fontSize = 11.sp,
            modifier = Modifier.padding(top = 7.dp),
        )
    }
}

@Composable
private fun FooterVerb(label: String, tail: String?, spinning: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.height(36.dp).clip(RoundedCornerShape(9.dp))
            .background(if (spinning) Tok.tx.copy(alpha = 0.05f) else Color.Transparent)
            .border(1.dp, Tok.hair, RoundedCornerShape(9.dp))
            .clickable(enabled = !spinning, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, color = if (spinning) Tok.tx2 else Tok.tx, fontFamily = Dk.ui, fontSize = 12.sp, maxLines = 1)
        if (tail != null) Text(tail, color = Tok.muted, fontFamily = Dk.mono, fontSize = 11.sp, maxLines = 1)
        if (spinning) CircularProgressIndicator(Modifier.size(11.dp), color = Tok.accent, strokeWidth = 1.5.dp)
    }
}

/** Screen C on the desktop: the same content the phone raises as a sheet, anchored under the header
 *  chip it came from. Local branches only; creating is safe and sits on top in accent. */
@Composable
private fun BranchPopover(model: DesktopModel, branches: List<GitBranchInfo>, current: String?, onDismiss: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val ordered = remember(branches) {
        branches.sortedWith(compareByDescending<GitBranchInfo> { it.current }.thenByDescending { it.lastCommitAt })
    }
    Column(
        Modifier.padding(start = 14.dp, top = 46.dp).width(330.dp)
            .shadow(20.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp))
            .background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).padding(5.dp),
    ) {
        if (!creating) Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { creating = true }
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("＋", color = Tok.accent, fontFamily = Dk.mono, fontSize = 12.sp)
            Text(stringResource(Res.string.git_new_branch), color = Tok.accent, fontFamily = Dk.ui, fontSize = 12.5.sp)
        } else Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(
                stringResource(Res.string.git_new_branch_from, current ?: "HEAD"),
                color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(
                    Modifier.weight(1f).height(32.dp).clip(RoundedCornerShape(8.dp)).background(Tok.raised)
                        .border(1.dp, Tok.accent, RoundedCornerShape(8.dp)).padding(horizontal = 9.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (name.isEmpty()) Text(stringResource(Res.string.git_new_branch_hint), color = Tok.muted, fontFamily = Dk.mono, fontSize = 12.sp)
                    BasicTextField(
                        name, { name = it }, singleLine = true,
                        textStyle = TextStyle(color = Tok.tx, fontSize = 12.sp, fontFamily = Dk.mono),
                        cursorBrush = SolidColor(Tok.accent), modifier = Modifier.fillMaxWidth(),
                    )
                }
                val on = name.isNotBlank()
                Text(
                    stringResource(Res.string.git_create),
                    color = if (on) Tok.base else Tok.tx.copy(alpha = 0.34f),
                    fontFamily = Dk.ui, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .background(if (on) Tok.accent else Tok.accent.copy(alpha = 0.16f))
                        .then(if (on) Modifier.clickable { model.gitAct(GIT_OP_BRANCH, branch = name.trim()); onDismiss() } else Modifier)
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                )
            }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
            items(ordered, key = { it.name }) { b ->
                val isCurrent = b.name == current
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !isCurrent) { model.gitAct(GIT_OP_CHECKOUT, branch = b.name); onDismiss() }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
                        if (isCurrent) Text("✓", color = Tok.ok, fontSize = 11.sp)
                    }
                    Text(
                        b.name, color = if (isCurrent) Tok.tx else Tok.tx2, fontFamily = Dk.mono, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    if (isCurrent) Text(stringResource(Res.string.git_branch_current), color = Tok.muted, fontFamily = Dk.ui, fontSize = 10.5.sp)
                }
            }
        }
    }
}

/** Screen D on the desktop — the same one sheet for both losses, as a centered card. A blocked
 *  preview (#281 C2) turns grey and its action inert rather than red and tempting. */
@Composable
private fun GitConfirmDialog(model: DesktopModel, preview: GitActionPreview) {
    val worktree = preview.op == GIT_OP_WORKTREE_REMOVE
    val blocked = preview.blocked
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))
            .pointerInput(preview.confirmToken) { detectTapGestures { model.dismissGitConfirm() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(440.dp).clip(RoundedCornerShape(14.dp)).background(Tok.surface)
                .border(1.dp, if (blocked) Tok.hair else Tok.danger.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(34.dp).clip(CircleShape)
                    .background(if (blocked) Tok.tx.copy(alpha = 0.06f) else Tok.danger.copy(alpha = 0.13f))
                    .border(1.3.dp, if (blocked) Tok.hair else Tok.danger.copy(alpha = 0.42f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text(if (blocked) "i" else "!", color = if (blocked) Tok.tx2 else Tok.danger, fontFamily = Dk.mono, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            Text(
                when {
                    worktree -> stringResource(Res.string.wt_remove_title, preview.branch ?: preview.path.orEmpty())
                    preview.branch != null -> stringResource(Res.string.git_checkout_title, preview.branch!!)
                    else -> stringResource(Res.string.git_discard_title, preview.files.size)
                },
                color = Tok.tx, fontFamily = Dk.ui, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp,
            )
            if (blocked) {
                Text(stringResource(Res.string.wt_remove_blocked), color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.5.sp)
                preview.blockedReason?.let { Text(it, color = Tok.muted, fontFamily = Dk.ui, fontSize = 12.sp) }
            } else if (preview.files.isNotEmpty()) Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Tok.raised)
                    .border(1.dp, Tok.hair, RoundedCornerShape(10.dp)),
            ) {
                preview.files.take(12).forEach { f ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        GitPathText(f.path, max = 42, color = Tok.tx2, fontSize = 11.5.sp, modifier = Modifier.weight(1f))
                        if ((f.adds ?: 0) > 0) Text("+${f.adds}", color = Tok.ok, fontFamily = Dk.mono, fontSize = 10.5.sp)
                        if ((f.dels ?: 0) > 0) Text("−${f.dels}", color = Tok.danger, fontFamily = Dk.mono, fontSize = 10.5.sp)
                    }
                }
            }
            Text(
                when {
                    worktree && blocked -> stringResource(Res.string.wt_remove_note_clean)
                    worktree -> stringResource(Res.string.wt_remove_note_dirty)
                    else -> stringResource(Res.string.git_discard_body)
                },
                color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.5.sp, lineHeight = 19.sp,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(
                    Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(9.dp))
                        .border(1.dp, Tok.hair, RoundedCornerShape(9.dp)).clickable { model.dismissGitConfirm() },
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(Res.string.cancel), color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.sp) }
                Box(
                    Modifier.weight(1.15f).height(38.dp).clip(RoundedCornerShape(9.dp))
                        .background(if (blocked) Tok.danger.copy(alpha = 0.16f) else Tok.danger)
                        .then(if (blocked) Modifier else Modifier.clickable { model.confirmPendingGit() }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (worktree) stringResource(Res.string.wt_remove) else stringResource(Res.string.git_discard_confirm),
                        color = if (blocked) Tok.tx.copy(alpha = 0.34f) else Tok.base,
                        fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

// ── #281: the worktree surface, same overlay language ───────────────

@Composable
fun WorktreesOverlay(model: DesktopModel, onDismiss: () -> Unit) {
    val list = model.worktrees
    val trees = list?.worktrees.orEmpty()
    var showNew by remember { mutableStateOf(false) }

    Box(
        Modifier.widthIn(max = 720.dp).fillMaxWidth(0.7f).heightIn(max = 560.dp).fillMaxHeight(0.8f)
            .shadow(30.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
            .background(Tok.raised).border(1.dp, Tok.hair, RoundedCornerShape(14.dp)),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(Res.string.wt_title), color = Tok.tx, fontFamily = Dk.ui, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                list?.repoRoot?.let { Text(repoBasename(it), color = Tok.muted, fontFamily = Dk.mono, fontSize = 11.5.sp) }
                Box(Modifier.weight(1f))
                if (trees.isNotEmpty()) Text(
                    if (trees.size == 1) stringResource(Res.string.wt_count_one) else stringResource(Res.string.wt_count, trees.size),
                    color = Tok.muted, fontFamily = Dk.mono, fontSize = 11.sp,
                )
                Icon(
                    Icons.Rounded.Refresh, stringResource(Res.string.wt_rescan), tint = Tok.tx2,
                    modifier = Modifier.size(22.dp).clip(RoundedCornerShape(999.dp)).clickable { model.fetchWorktrees() }.padding(3.dp),
                )
                Icon(
                    Icons.Rounded.Close, stringResource(Res.string.close), tint = Tok.tx2,
                    modifier = Modifier.size(24.dp).clip(RoundedCornerShape(999.dp)).clickable(onClick = onDismiss).padding(3.dp),
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
            when {
                list == null && model.worktreesStale ->
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        DiffEmptyState(glyph = ">_", title = stringResource(Res.string.wt_unavailable), caption = null)
                    }
                list == null -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Tok.accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                }
                else -> LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp)) {
                    item(key = "__new") {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(10.dp))
                                .background(Tok.accent.copy(alpha = 0.06f)).clickable { showNew = true }
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            Text("＋", color = Tok.accent, fontFamily = Dk.mono, fontSize = 13.sp)
                            Text(stringResource(Res.string.wt_new), color = Tok.accent, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (trees.size == 1) item(key = "__hint") {
                        Text(
                            stringResource(Res.string.wt_hint_single), color = Tok.tx2, fontFamily = Dk.ui,
                            fontSize = 11.5.sp, lineHeight = 17.5.sp, modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    items(trees, key = { it.path }) { w -> DesktopWorktreeCard(model, w) }
                    if (trees.any { it.dirty == null }) item(key = "__foot") {
                        Text(
                            stringResource(Res.string.wt_foot_unknown), color = Tok.muted, fontFamily = Dk.ui,
                            fontSize = 11.sp, lineHeight = 16.5.sp, modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                    item(key = "__tail") { Box(Modifier.height(14.dp)) }
                }
            }
        }
        if (showNew) NewWorktreeDialog(model) { showNew = false }
        model.gitPendingConfirm?.let { GitConfirmDialog(model, it) }
    }
}

@Composable
private fun DesktopWorktreeCard(model: DesktopModel, w: WorktreeEntry) {
    val live = w.activeSessionId != null
    val shape = RoundedCornerShape(11.dp)
    Column(
        Modifier.fillMaxWidth().padding(top = 10.dp).clip(shape).background(Tok.surface)
            .border(1.dp, if (live) Tok.accent.copy(alpha = 0.24f) else Tok.hair, shape)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("⎇", color = Tok.muted, fontFamily = Dk.mono, fontSize = 11.5.sp)
            Text(
                w.branch ?: w.head?.take(8) ?: "?", color = Tok.tx, fontFamily = Dk.mono, fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (w.isMain) Text(
                stringResource(Res.string.wt_main_badge).uppercase(), color = Tok.tx2, fontFamily = Dk.mono, fontSize = 9.sp,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Tok.tx.copy(alpha = 0.08f)).padding(horizontal = 5.dp, vertical = 3.dp),
            )
            // Remove is ABSENT for the main worktree, never merely disabled (design A6)
            if (worktreeRemovable(w)) Text(
                stringResource(Res.string.wt_menu_remove), color = Tok.danger, fontFamily = Dk.ui, fontSize = 11.5.sp,
                modifier = Modifier.clip(RoundedCornerShape(7.dp)).clickable { model.removeWorktree(w.path) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Text(midTruncatePath(w.path, 60), color = Tok.muted, fontFamily = Dk.mono, fontSize = 11.sp, maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when (w.dirty) {
                true -> DesktopPill(stringResource(Res.string.wt_chip_dirty, w.dirtyCount ?: 0), Tok.warn)
                false -> DesktopPill(stringResource(Res.string.wt_chip_clean), Tok.ok)
                null -> DesktopPill(stringResource(Res.string.wt_chip_unknown), Tok.muted)
            }
            if (live) DesktopPill(stringResource(Res.string.wt_chip_session), Tok.accent)
        }
    }
}

@Composable
private fun DesktopPill(label: String, hue: Color) {
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).background(hue.copy(alpha = 0.11f)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(hue))
        Text(label, color = hue, fontFamily = Dk.ui, fontSize = 11.sp)
    }
}

/** Screen B on the desktop: the same two segments and the same read-only Location promise. */
@Composable
private fun NewWorktreeDialog(model: DesktopModel, onDismiss: () -> Unit) {
    var newBranch by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<String?>(null) }
    val repoRoot = model.worktrees?.repoRoot
    val branches = model.gitStatus?.branches.orEmpty()
    val defaultBranch = model.worktrees?.worktrees?.firstOrNull { it.isMain }?.branch ?: "main"
    val target = if (newBranch) name else picked.orEmpty()

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(460.dp).clip(RoundedCornerShape(14.dp)).background(Tok.surface)
                .border(1.dp, Tok.hair, RoundedCornerShape(14.dp))
                .pointerInput(Unit) { detectTapGestures { } }.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(Res.string.wt_new_title), color = Tok.tx, fontFamily = Dk.ui, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Tok.raised).padding(3.dp)) {
                @Composable
                fun seg(label: String, isNew: Boolean) {
                    val on = newBranch == isNew
                    Box(
                        Modifier.weight(1f).height(30.dp).clip(RoundedCornerShape(7.dp))
                            .background(if (on) Tok.surface else Color.Transparent)
                            .clickable(enabled = !on) { newBranch = isNew },
                        contentAlignment = Alignment.Center,
                    ) { Text(label, color = if (on) Tok.tx else Tok.tx2, fontFamily = Dk.ui, fontSize = 12.sp) }
                }
                seg(stringResource(Res.string.wt_seg_existing), isNew = false)
                seg(stringResource(Res.string.wt_seg_new), isNew = true)
            }
            if (!newBranch) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                items(branches.sortedByDescending { it.lastCommitAt }, key = { it.name }) { b ->
                    val inUse = b.checkedOutAt != null
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !inUse) { picked = b.name }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
                            if (picked == b.name && !inUse) Text("✓", color = Tok.accent, fontSize = 11.sp)
                        }
                        Text(
                            b.name, color = if (inUse) Tok.muted else Tok.tx, fontFamily = Dk.mono, fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        // already checked out elsewhere: dimmed and NAMED, never a tap that fails
                        if (inUse) Text(
                            stringResource(Res.string.wt_in_use, midTruncatePath(b.checkedOutAt ?: "", 26)),
                            color = Tok.muted, fontFamily = Dk.ui, fontSize = 10.5.sp, maxLines = 1,
                        )
                    }
                }
            } else Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(
                    Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(9.dp)).background(Tok.raised)
                        .border(1.dp, Tok.accent, RoundedCornerShape(9.dp)).padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (name.isEmpty()) Text(stringResource(Res.string.git_new_branch_hint), color = Tok.muted, fontFamily = Dk.mono, fontSize = 12.sp)
                    BasicTextField(
                        name, { name = it }, singleLine = true,
                        textStyle = TextStyle(color = Tok.tx, fontSize = 12.sp, fontFamily = Dk.mono),
                        cursorBrush = SolidColor(Tok.accent), modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(stringResource(Res.string.wt_branched_from, defaultBranch), color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp)
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Tok.raised)
                    .border(1.dp, Tok.hair, RoundedCornerShape(10.dp)).padding(horizontal = 11.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(Res.string.wt_location).uppercase(), color = Tok.muted, fontFamily = Dk.mono, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    dev.ccpocket.app.data.worktreeLocationPreview(repoRoot, target),
                    color = Tok.tx, fontFamily = Dk.mono, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(stringResource(Res.string.wt_location_note), color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp)
            }
            val on = target.isNotBlank()
            Box(
                Modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(9.dp))
                    .background(if (on) Tok.accent else Tok.accent.copy(alpha = 0.16f))
                    .then(if (on) Modifier.clickable { model.addWorktree(target.trim(), newBranch); onDismiss() } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(Res.string.wt_create),
                    color = if (on) Tok.base else Tok.tx.copy(alpha = 0.34f),
                    fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
