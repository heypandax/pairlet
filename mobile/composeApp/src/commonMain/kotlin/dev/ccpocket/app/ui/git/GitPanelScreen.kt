package dev.ccpocket.app.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.GitChip
import dev.ccpocket.app.data.GitCommitBlock
import dev.ccpocket.app.data.GitRowAction
import dev.ccpocket.app.data.GitSection
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.data.commitBlockedBy
import dev.ccpocket.app.data.conflictShape
import dev.ccpocket.app.data.divergenceText
import dev.ccpocket.app.data.gitInSync
import dev.ccpocket.app.data.gitSections
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.DiffEmptyState
import dev.ccpocket.protocol.GIT_OP_COMMIT
import dev.ccpocket.protocol.GIT_OP_FETCH
import dev.ccpocket.protocol.GIT_OP_PULL
import dev.ccpocket.protocol.GIT_OP_PUSH
import dev.ccpocket.protocol.GIT_OP_STAGE
import dev.ccpocket.protocol.GIT_OP_UNSTAGE
import dev.ccpocket.protocol.GitFileEntry
import dev.ccpocket.protocol.GitStatus
import org.jetbrains.compose.resources.stringResource

// ════════════════════════════════════════════════════════════════════
//  Screen A — the Git status panel (issue #280, design A1–A7).
//  Three fixed bands: repo header, the grouped list (the only thing
//  that scrolls), and the pinned composer + three remote verbs. Every
//  state in the handoff is the SAME layout with parts removed — the
//  surface never changes shape between them.
// ════════════════════════════════════════════════════════════════════

@Composable
fun GitPanelScreen(
    repo: PocketRepository,
    onBack: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenWorktrees: () -> Unit,
    /** The path the Diff tab returns to — the last one opened from this list, remembered by the host
     *  because the diff screen replaces this one and takes its composition with it. Null before any
     *  file has been opened, and the tab is inert rather than absent so the row keeps its shape. */
    lastDiffPath: String? = null,
) {
    dev.ccpocket.app.SystemBackHandler(enabled = true) { onBack() }
    val status = repo.gitStatus.value
    var showBranches by remember { mutableStateOf(false) }
    // the message survives a status refresh (which recomposes the whole list) but not a session
    // switch — the repo clears its git state there and this screen leaves composition with it
    var message by remember(repo.convoId.value) { mutableStateOf("") }

    // one pull on entry; everything after rides GitActionResult.statusAfter — no polling anywhere
    LaunchedEffect(repo.convoId.value) { repo.fetchGitStatus(withBranches = true) }

    Column(Modifier.fillMaxSize().background(Tok.base)) {
        GitNavRow(title = repo.chatTitle.value ?: stringResource(Res.string.chat_title), onBack = onBack)
        GitTabRow(
            selected = GitTab.GIT,
            diffEnabled = lastDiffPath != null,
            onPick = { tab ->
                when (tab) {
                    GitTab.FILES -> onOpenFiles()
                    GitTab.DIFF -> lastDiffPath?.let { repo.openGitDiff(it, repo.gitDiffStaged.value) }
                    GitTab.GIT -> Unit
                }
            },
        )

        RepoHeader(
            status = status,
            busy = repo.gitStatusLoading.value,
            onOpenBranches = { repo.fetchGitStatus(withBranches = true); showBranches = true },
            onRefresh = { repo.fetchGitStatus(withBranches = true) },
            onOpenWorktrees = onOpenWorktrees,
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                // A7: the skeleton holds the real geometry so the list does not jump on arrival
                status == null && repo.gitStatusLoading.value -> StatusSkeleton()
                repo.gitStatusUnavailable.value && status == null -> DiffEmptyState(
                    glyph = ">_",
                    title = stringResource(Res.string.git_unavailable),
                    caption = null,
                )
                status == null -> Box(Modifier.fillMaxSize())
                status.notARepo -> DiffEmptyState(glyph = "⎇", title = stringResource(Res.string.git_not_a_repo), caption = null)
                !status.ok -> DiffEmptyState(glyph = "!", title = stringResource(Res.string.git_action_failed), caption = status.error)
                else -> {
                    val sections = remember(status) { gitSections(status) }
                    if (sections.isEmpty()) CleanTreeState(status) else StatusList(
                        sections = sections,
                        truncated = status.truncated,
                        onAct = { entry, action ->
                            when (action) {
                                GitRowAction.STAGE -> repo.gitAct(GIT_OP_STAGE, paths = listOf(entry.path))
                                GitRowAction.UNSTAGE -> repo.gitAct(GIT_OP_UNSTAGE, paths = listOf(entry.path))
                                GitRowAction.NONE -> Unit
                            }
                        },
                        // a staged row opens on the staged side — that is the truth the row is about
                        onOpen = { entry, chip -> repo.openGitDiff(entry.path, staged = chip == GitChip.STAGED) },
                    )
                }
            }
        }

        CommitBar(repo, status, message, onMessage = { message = it }, onCommitted = { message = "" })
    }

    if (showBranches) BranchSheet(repo) { showBranches = false }
    repo.gitPendingConfirm.value?.let { GitConfirmSheet(repo, it) }
}

/** The nav row: back, the session's own title, nothing on the right — the panel's only overflow verb
 *  (revert) belongs to a file, so it lives on the diff screen (design B3) rather than up here. */
@Composable
private fun GitNavRow(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 6.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(999.dp)).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) { Text("←", color = Tok.tx2, fontSize = 18.sp) }
        Text(
            title, color = Tok.tx, fontSize = 16.5.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
    }
}

/** The 11×13 branch glyph the handoff draws by hand — a stem with a ring on it. */
@Composable
internal fun BranchGlyph(tint: Color = Tok.tx2) {
    Box(Modifier.size(width = 11.dp, height = 13.dp)) {
        Box(Modifier.offset(x = 4.6.dp).width(1.4.dp).fillMaxHeight().background(tint))
        Box(
            Modifier.offset(x = 1.dp, y = 2.dp).size(9.dp).clip(CircleShape)
                .background(Tok.base).border(1.4.dp, tint, CircleShape),
        )
    }
}

/**
 * Branch, divergence and upstream in three facts on two lines (A1). The whole block is the doorway
 * to the branch sheet; refresh keeps its own 44dp target so the two are never confused. The worktree
 * line appears only for a repository that HAS more than one checkout (#281 composition note).
 */
@Composable
private fun RepoHeader(
    status: GitStatus?,
    busy: Boolean,
    onOpenBranches: () -> Unit,
    onRefresh: () -> Unit,
    onOpenWorktrees: () -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 13.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                    .then(if (status != null && !status.notARepo) Modifier.clickable(onClick = onOpenBranches) else Modifier),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BranchGlyph()
                    Text(
                        status?.branch ?: "…",
                        color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Tok.muted, modifier = Modifier.size(14.dp))
                    val divergence = divergenceText(status)
                    when {
                        gitInSync(status) -> Text(
                            stringResource(Res.string.git_in_sync),
                            color = Tok.ok, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        )
                        divergence.isNotEmpty() -> Text(
                            divergence,
                            // amber while the index is unmerged: the numbers are true but acting on them isn't
                            color = if (status?.conflicted?.isNotEmpty() == true) Tok.warn else Tok.tx2,
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        )
                    }
                }
                status?.upstream?.let { Text(it, color = Tok.muted, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(999.dp)).clickable(onClick = onRefresh),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(15.dp), color = Tok.tx2, strokeWidth = 1.6.dp)
                else Icon(Icons.Rounded.Refresh, stringResource(Res.string.git_refresh), tint = Tok.tx2, modifier = Modifier.size(17.dp))
            }
        }
        // #281 composition: only a repository with a second checkout earns the line — a single-worktree
        // repo never sees an entrance to a surface that would show it one row.
        if ((status?.worktreeCount ?: 0) > 1) Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpenWorktrees)
                .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("⎇", color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Text(
                stringResource(Res.string.wt_count, status?.worktreeCount ?: 0),
                color = Tok.tx2, fontSize = 11.5.sp, modifier = Modifier.weight(1f),
            )
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Tok.muted, modifier = Modifier.size(15.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
    }
}

/** A2: empty sections are removed, not shown empty, and the caption answers the one question left —
 *  in sync against what. The composer stays visible (and disabled) so the surface keeps its shape. */
@Composable
private fun CleanTreeState(status: GitStatus) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 44.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(Tok.ok.copy(alpha = 0.10f))
                .border(1.4.dp, Tok.ok.copy(alpha = 0.45f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("✓", color = Tok.ok, fontSize = 20.sp) }
        Text(
            stringResource(Res.string.git_clean_title),
            color = Tok.tx, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.5.sp,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            status.upstream?.let { stringResource(Res.string.git_clean_caption, it) }
                ?: stringResource(Res.string.git_clean_caption_plain),
            color = Tok.muted, fontSize = 13.sp, lineHeight = 19.5.sp,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp),
        )
    }
}

/** A7: section label bar, chip, path, trailing action — the real geometry, so nothing jumps. */
@Composable
private fun StatusSkeleton() {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        listOf(54.dp to 0.62f, 62.dp to 0.48f, 72.dp to 0.55f).forEach { (head, frac) ->
            Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Box(Modifier.width(head).height(9.dp).clip(RoundedCornerShape(4.dp)).background(Tok.tx.copy(alpha = 0.07f)))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(width = 22.dp, height = 20.dp).clip(RoundedCornerShape(5.dp)).background(Tok.tx.copy(alpha = 0.06f)))
                    Box(Modifier.weight(1f).height(11.dp).clip(RoundedCornerShape(5.dp)).background(Tok.tx.copy(alpha = 0.05f)))
                    Box(Modifier.width(44.dp).height(11.dp).clip(RoundedCornerShape(5.dp)).background(Tok.tx.copy(alpha = 0.04f)))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(width = 22.dp, height = 20.dp).clip(RoundedCornerShape(5.dp)).background(Tok.tx.copy(alpha = 0.06f)))
                    Box(Modifier.fillMaxWidth(frac).height(11.dp).clip(RoundedCornerShape(5.dp)).background(Tok.tx.copy(alpha = 0.05f)))
                }
            }
        }
    }
}

@Composable
private fun sectionLabel(section: GitSection): String = stringResource(
    when (section.key) {
        "conflicts" -> Res.string.git_sec_conflicts
        "staged" -> Res.string.git_sec_staged
        "untracked" -> Res.string.git_sec_untracked
        else -> Res.string.git_sec_changes
    },
)

@Composable
private fun StatusList(
    sections: List<GitSection>,
    truncated: Boolean,
    onAct: (GitFileEntry, GitRowAction) -> Unit,
    onOpen: (GitFileEntry, GitChip) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        sections.forEach { section ->
            item(key = "h-${section.key}") {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 17.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Text(
                        sectionLabel(section).uppercase(),
                        color = if (section.chip == GitChip.CONFLICT) Tok.danger else Tok.muted,
                        fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.5.sp,
                    )
                    Text("${section.rows.size}", color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp)
                }
            }
            items(section.rows, key = { "${section.key}:${it.path}" }) { entry ->
                GitFileRow(entry, section.chip, section.action, onAct = { onAct(entry, section.action) }) { onOpen(entry, section.chip) }
            }
        }
        if (truncated) item(key = "__trunc") {
            Text(
                stringResource(Res.string.git_truncated), color = Tok.warn, fontSize = 11.5.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            )
        }
        item(key = "__tail") { Box(Modifier.height(14.dp)) }
    }
}

@Composable
private fun GitFileRow(
    entry: GitFileEntry,
    chip: GitChip,
    action: GitRowAction,
    onAct: () -> Unit,
    onOpen: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            GitStatusChip(entry.code, chip)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                GitPathText(entry.path)
                val shape = conflictShape(entry)
                val hasCounts = (entry.adds ?: 0) > 0 || (entry.dels ?: 0) > 0
                if (hasCounts || shape != null) Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if ((entry.adds ?: 0) > 0) Text("+${entry.adds}", color = Tok.ok, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    if ((entry.dels ?: 0) > 0) Text("−${entry.dels}", color = Tok.danger, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    if (shape != null) Text(
                        "${conflictShapeLabel(shape)} · ${stringResource(Res.string.git_conflict_resolve_hint)}",
                        color = Tok.muted, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // a conflict row carries NO pill — only a chevron to the diff (A3)
            if (action == GitRowAction.NONE) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Tok.muted, modifier = Modifier.size(16.dp))
            } else {
                Text(
                    stringResource(if (action == GitRowAction.STAGE) Res.string.git_stage else Res.string.git_unstage),
                    color = Tok.tx2, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.height(34.dp).clip(RoundedCornerShape(9.dp))
                        .border(1.dp, Tok.hair, RoundedCornerShape(9.dp))
                        .clickable(onClick = onAct)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

// ── the pinned bottom band: strip · composer · three remote verbs ───

@Composable
private fun CommitBar(
    repo: PocketRepository,
    status: GitStatus?,
    message: String,
    onMessage: (String) -> Unit,
    onCommitted: () -> Unit,
) {
    val blocked = commitBlockedBy(status, message)
    val busy = repo.gitBusyOp.value
    Column(Modifier.fillMaxWidth().background(Tok.base)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp)) {
            repo.gitError.value?.let { err ->
                GitErrorStrip(
                    title = when {
                        err.notFastForward -> stringResource(Res.string.git_pull_refused)
                        err.op == GIT_OP_PUSH -> stringResource(Res.string.git_push_rejected)
                        else -> stringResource(Res.string.git_action_failed)
                    },
                    // ours above, git's own words below — the strip shows both, never a paraphrase
                    detail = err.stderr.ifBlank { err.error },
                    amber = err.notFastForward,
                    onDismiss = { repo.dismissGitError() },
                )
                Box(Modifier.height(11.dp))
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Tok.raised)
                        .border(1.dp, Tok.hair, RoundedCornerShape(12.dp))
                        .padding(horizontal = 13.dp, vertical = 12.dp),
                ) {
                    if (message.isEmpty()) Text(stringResource(Res.string.git_commit_hint), color = Tok.muted, fontSize = 14.5.sp)
                    BasicTextField(
                        message, onMessage,
                        textStyle = TextStyle(color = Tok.tx, fontSize = 14.5.sp, lineHeight = 20.sp),
                        cursorBrush = SolidColor(Tok.accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                val on = blocked == null
                Row(
                    Modifier.height(46.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (on) Tok.accent else Tok.accent.copy(alpha = 0.16f))
                        .then(if (on) Modifier.clickable { repo.gitAct(GIT_OP_COMMIT, message = message.trim()); onCommitted() } else Modifier)
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (busy == GIT_OP_COMMIT) CircularProgressIndicator(Modifier.size(13.dp), color = Tok.base, strokeWidth = 1.6.dp)
                    Text(
                        stringResource(Res.string.git_commit),
                        color = if (on) Tok.base else Tok.tx.copy(alpha = 0.34f),
                        fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            // the refusal is explained in place, not by a toast (A3). An empty message needs no
            // sentence — the placeholder already says what is missing.
            val note = when (blocked) {
                GitCommitBlock.CONFLICTS -> stringResource(Res.string.git_commit_blocked_conflicts, status?.conflicted?.size ?: 0)
                GitCommitBlock.DETACHED -> stringResource(Res.string.git_commit_blocked_detached)
                else -> null
            }
            if (note != null) Text(
                note, color = Tok.danger, fontSize = 12.sp, lineHeight = 17.5.sp,
                modifier = Modifier.padding(start = 2.dp, top = 9.dp),
            )
            Row(Modifier.padding(top = 11.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RemoteButton(
                    label = stringResource(if (busy == GIT_OP_FETCH) Res.string.git_fetching else Res.string.git_fetch),
                    tail = null, spinning = busy == GIT_OP_FETCH, modifier = Modifier.weight(1f),
                ) { repo.gitAct(GIT_OP_FETCH) }
                RemoteButton(
                    label = stringResource(if (busy == GIT_OP_PULL) Res.string.git_pulling else Res.string.git_pull),
                    tail = stringResource(Res.string.git_pull_tail), spinning = busy == GIT_OP_PULL,
                    modifier = Modifier.weight(1f),
                ) { repo.gitAct(GIT_OP_PULL) }
                val ahead = status?.ahead ?: 0
                RemoteButton(
                    label = stringResource(if (busy == GIT_OP_PUSH) Res.string.git_pushing else Res.string.git_push),
                    // the count drops while the push runs — it is no longer a promise (A4)
                    tail = if (ahead > 0 && busy != GIT_OP_PUSH) "↑$ahead" else null,
                    spinning = busy == GIT_OP_PUSH, modifier = Modifier.weight(1f),
                ) { repo.gitAct(GIT_OP_PUSH) }
            }
        }
    }
}

/** 42dp hairline button. The spinner lives in the button that started the work and NOTHING else
 *  locks: staging can continue while a push runs (A4). */
@Composable
private fun RemoteButton(label: String, tail: String?, spinning: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(42.dp).clip(RoundedCornerShape(10.dp))
            .background(if (spinning) Tok.tx.copy(alpha = 0.05f) else Color.Transparent)
            .border(1.dp, Tok.hair, RoundedCornerShape(10.dp))
            .clickable(enabled = !spinning, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
    ) {
        Text(label, color = if (spinning) Tok.tx2 else Tok.tx, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        if (tail != null) Text(tail, color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
        if (spinning) CircularProgressIndicator(Modifier.size(12.dp), color = Tok.accent, strokeWidth = 1.6.dp)
    }
}
