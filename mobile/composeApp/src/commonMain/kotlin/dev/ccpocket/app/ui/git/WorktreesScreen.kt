package dev.ccpocket.app.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.data.midTruncatePath
import dev.ccpocket.app.data.repoBasename
import dev.ccpocket.app.data.worktreeLocationPreview
import dev.ccpocket.app.data.worktreeRemovable
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.DiffEmptyState
import dev.ccpocket.app.ui.PocketSheet
import dev.ccpocket.app.ui.relativeTime
import dev.ccpocket.protocol.GIT_OP_WORKTREE_ADD
import dev.ccpocket.protocol.GIT_OP_WORKTREE_REMOVE
import dev.ccpocket.protocol.GitBranchInfo
import dev.ccpocket.protocol.WorktreeEntry
import org.jetbrains.compose.resources.stringResource

// ════════════════════════════════════════════════════════════════════
//  Worktree management (issue #281, design A1–A6 / B1–B2 / C1–C2).
//  One repository, every checkout of it. The removal flow rides #280's
//  two-step token machinery verbatim — there is no second confirm
//  mechanism anywhere in these two features.
// ════════════════════════════════════════════════════════════════════

@Composable
fun WorktreesScreen(repo: PocketRepository, onOpenSessionHere: (String) -> Unit, onBack: () -> Unit) {
    dev.ccpocket.app.SystemBackHandler(enabled = true) { onBack() }
    val list = repo.worktrees.value
    val trees = list?.worktrees.orEmpty()
    var menuFor by remember { mutableStateOf<String?>(null) }
    var showNew by remember { mutableStateOf(false) }
    // #295: filter, not navigation — clearing it must cost one tap and lose nothing
    var query by remember(repo.convoId.value) { mutableStateOf("") }
    val shown = remember(trees, query) {
        val q = query.trim()
        if (q.isEmpty()) trees
        else trees.filter { it.branch?.contains(q, ignoreCase = true) == true || it.path.contains(q, ignoreCase = true) }
    }
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(repo.convoId.value) { repo.fetchWorktrees() }

    Box(Modifier.fillMaxSize().background(Tok.base)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 6.dp, end = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(999.dp)).clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) { Text("←", color = Tok.tx2, fontSize = 18.sp) }
                Text(
                    stringResource(Res.string.wt_title), color = Tok.tx, fontSize = 16.5.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(999.dp)).clickable { repo.fetchWorktrees() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (repo.worktreesLoading.value) CircularProgressIndicator(Modifier.size(15.dp), color = Tok.tx2, strokeWidth = 1.6.dp)
                    else Icon(Icons.Rounded.Refresh, stringResource(Res.string.wt_rescan), tint = Tok.tx2, modifier = Modifier.size(17.dp))
                }
            }

            // repo header: the anchor everything on this surface belongs to
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 13.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        repoBasename(list?.repoRoot ?: repo.workdir.value ?: ""),
                        color = Tok.tx, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    list?.repoRoot?.let { GitPathText(it, max = 30, color = Tok.muted, fontSize = 11.5.sp) }
                }
                if (trees.isNotEmpty()) Text(
                    if (trees.size == 1) stringResource(Res.string.wt_count_one) else stringResource(Res.string.wt_count, trees.size),
                    color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))

            // #294: the ONLY render point worktree failures have — GitPanelScreen's strip is a
            // different screen, so an ok=false reply used to die silently under this surface
            repo.gitError.value?.let { err ->
                Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                    GitErrorStrip(
                        title = stringResource(Res.string.git_action_failed),
                        detail = err.stderr.ifBlank { err.error },
                        amber = false,
                        onDismiss = { repo.dismissGitError() },
                    )
                }
            }

            // #295: the search box — branch or path, either substring. Two checkouts is when a list
            // first becomes two things to tell apart, so that is when the box appears.
            if (trees.size > 1) Box(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp)
                    .height(42.dp).clip(RoundedCornerShape(11.dp)).background(Tok.raised)
                    .border(1.dp, Tok.hair, RoundedCornerShape(11.dp)).padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) Text(
                    stringResource(Res.string.wt_search), color = Tok.muted,
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                )
                BasicTextField(
                    query, { query = it }, singleLine = true,
                    textStyle = TextStyle(color = Tok.tx, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                    cursorBrush = SolidColor(Tok.accent), modifier = Modifier.fillMaxWidth(),
                )
            }

            when {
                repo.worktreesUnavailable.value && list == null ->
                    DiffEmptyState(glyph = ">_", title = stringResource(Res.string.wt_unavailable), caption = null)
                list?.notARepo == true ->
                    DiffEmptyState(glyph = "⎇", title = stringResource(Res.string.git_not_a_repo), caption = null)
                list?.ok == false ->
                    DiffEmptyState(glyph = "!", title = stringResource(Res.string.git_action_failed), caption = list.error)
                else -> LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    item(key = "__new") { NewWorktreeRow { showNew = true } }
                    // #294: the 1–2s between Create and the list reply used to be a void the user read
                    // as failure (and retried) — the pending row holds the spot the new card will take.
                    repo.gitBusyOp.value?.takeIf { it == GIT_OP_WORKTREE_ADD || it == GIT_OP_WORKTREE_REMOVE }?.let { op ->
                        item(key = "__pending") { WorktreePendingRow(creating = op == GIT_OP_WORKTREE_ADD) }
                    }
                    // A2: one checkout is not an empty state — it is a repository that has not needed a
                    // second one yet, so it gets a sentence rather than an illustration.
                    if (trees.size == 1) item(key = "__hint") {
                        Text(
                            stringResource(Res.string.wt_hint_single), color = Tok.tx2, fontSize = 12.5.sp, lineHeight = 19.5.sp,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
                        )
                    }
                    if (list == null && repo.worktreesLoading.value) item(key = "__skeleton") { WorktreeSkeleton() }
                    items(shown, key = { it.path }) { w ->
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) {
                            WorktreeCard(w) { menuFor = w.path }
                        }
                    }
                    // A3: grey means we have not looked yet — and it blocks nothing
                    if (trees.any { it.dirty == null }) item(key = "__foot") {
                        Text(
                            stringResource(Res.string.wt_foot_unknown), color = Tok.muted, fontSize = 11.5.sp, lineHeight = 17.5.sp,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp),
                        )
                    }
                    item(key = "__tail") { Box(Modifier.height(22.dp)) }
                }
            }
        }

        // A5/A6: the card overflow. Remove is ABSENT for the main worktree — the absence is the answer,
        // a disabled row would only invite the tap.
        menuFor?.let { path ->
            val entry = trees.firstOrNull { it.path == path }
            Box(Modifier.fillMaxSize().pointerInput(path) { detectTapGestures { menuFor = null } })
            Column(
                Modifier.align(Alignment.TopEnd).padding(top = 150.dp, end = 14.dp)
                    .width(226.dp).clip(RoundedCornerShape(13.dp)).background(Tok.raised)
                    .border(1.dp, Tok.hair, RoundedCornerShape(13.dp)).padding(5.dp),
            ) {
                // zero new protocol: a worktree is just another cwd, so this is the ordinary
                // OpenSession the project list already sends (#281 §2)
                MenuRow(stringResource(Res.string.wt_menu_open), first = true) {
                    menuFor = null
                    onOpenSessionHere(path)
                }
                MenuRow(stringResource(Res.string.menu_copy_path), first = false) {
                    menuFor = null; clipboard.setText(AnnotatedString(path))
                }
                if (entry != null && worktreeRemovable(entry)) MenuRow(stringResource(Res.string.wt_menu_remove), first = false, danger = true) {
                    menuFor = null
                    repo.removeWorktree(path) // step one — the daemon answers with the preview (C1/C2)
                }
            }
        }
    }

    if (showNew) NewWorktreeSheet(repo) { showNew = false }
    repo.gitPendingConfirm.value?.let { GitConfirmSheet(repo, it) }
}

@Composable
private fun NewWorktreeRow(onClick: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).background(Tok.accent.copy(alpha = 0.06f))
                .clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Text("＋", color = Tok.accent, fontFamily = FontFamily.Monospace, fontSize = 15.sp)
            Text(stringResource(Res.string.wt_new), color = Tok.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Tok.accent.copy(alpha = 0.6f), modifier = Modifier.size(15.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
    }
}

/** #294: the in-flight receipt. It sits where the new card will land, so success reads as the row
 *  resolving into a card rather than an indicator vanishing. */
@Composable
private fun WorktreePendingRow(creating: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        CircularProgressIndicator(Modifier.size(13.dp), color = Tok.accent, strokeWidth = 1.6.dp)
        Text(
            stringResource(if (creating) Res.string.wt_creating else Res.string.wt_removing),
            color = Tok.tx2, fontSize = 12.5.sp,
        )
    }
}

/** One checkout. A live session earns the card an accent edge as well as its chip — the border is
 *  what makes "something is running in here" visible while scrolling past. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorktreeCard(w: WorktreeEntry, onMenu: () -> Unit) {
    val shape = RoundedCornerShape(13.dp)
    val live = w.activeSessionId != null
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Tok.surface)
            .border(1.dp, if (live) Tok.accent.copy(alpha = 0.24f) else Tok.hair, shape)
            .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            BranchGlyph(Tok.muted)
            Text(
                w.branch ?: w.head?.take(8) ?: "?",
                color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 13.5.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (w.isMain) Text(
                stringResource(Res.string.wt_main_badge).uppercase(),
                color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 9.5.sp, fontWeight = FontWeight.Medium,
                letterSpacing = 0.9.sp,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Tok.tx.copy(alpha = 0.08f))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(999.dp)).clickable(onClick = onMenu),
                contentAlignment = Alignment.Center,
            ) { Text("⋯", color = Tok.tx2, fontSize = 19.sp, fontWeight = FontWeight.Medium) }
        }
        GitPathText(w.path, max = 42, color = Tok.muted, fontSize = 11.5.sp, modifier = Modifier.padding(end = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            when (w.dirty) {
                true -> StatusPill(stringResource(Res.string.wt_chip_dirty, w.dirtyCount ?: 0), Tok.warn)
                false -> StatusPill(stringResource(Res.string.wt_chip_clean), Tok.ok)
                null -> StatusPill(stringResource(Res.string.wt_chip_unknown), Tok.muted)
            }
            if (live) StatusPill(stringResource(Res.string.wt_chip_session), Tok.accent)
        }
    }
}

/** 24dp pill: a dot in the hue, the word beside it, the hue at 11% behind both. */
@Composable
private fun StatusPill(label: String, hue: Color) {
    Row(
        Modifier.height(24.dp).clip(RoundedCornerShape(7.dp)).background(hue.copy(alpha = 0.11f))
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(hue))
        Text(label, color = hue, fontSize = 11.5.sp, maxLines = 1)
    }
}

/** A4: the skeleton keeps the card's geometry — branch line, path line, chip row. */
@Composable
private fun WorktreeSkeleton() {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(0.38f to 0.64f, 0.52f to 0.72f, 0.44f to 0.58f).forEach { (name, path) ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(Tok.surface)
                    .border(1.dp, Tok.hair, RoundedCornerShape(13.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Box(Modifier.size(width = 11.dp, height = 13.dp).clip(RoundedCornerShape(3.dp)).background(Tok.tx.copy(alpha = 0.07f)))
                    Box(Modifier.fillMaxWidth(name).height(11.dp).clip(RoundedCornerShape(5.dp)).background(Tok.tx.copy(alpha = 0.07f)))
                }
                Box(Modifier.fillMaxWidth(path).height(9.dp).clip(RoundedCornerShape(4.dp)).background(Tok.tx.copy(alpha = 0.05f)))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.width(74.dp).height(24.dp).clip(RoundedCornerShape(7.dp)).background(Tok.tx.copy(alpha = 0.045f)))
                    Box(Modifier.width(96.dp).height(24.dp).clip(RoundedCornerShape(7.dp)).background(Tok.tx.copy(alpha = 0.035f)))
                }
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, first: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    Column {
        if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Box(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(9.dp))
                .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) { Text(label, color = if (danger) Tok.danger else Tok.tx, fontSize = 14.sp, lineHeight = 19.sp) }
    }
}

// ── Screen B: the new-worktree sheet ────────────────────────────────

/**
 * Two ways to make a checkout, one destination. A branch already checked out somewhere is DIMMED and
 * names its holder ([GitBranchInfo.checkedOutAt]) rather than letting the tap fail — `git worktree
 * add` would refuse it, and finding that out after the fact teaches nothing.
 */
@Composable
fun NewWorktreeSheet(repo: PocketRepository, onDismiss: () -> Unit) {
    val repoRoot = repo.worktrees.value?.repoRoot ?: repo.workdir.value
    val branches = repo.gitStatus.value?.branches.orEmpty()
    var newBranch by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<String?>(null) }
    val defaultBranch = remember(repo.worktrees.value) {
        repo.worktrees.value?.worktrees?.firstOrNull { it.isMain }?.branch ?: "main"
    }
    // the branch list is the status reply's, so make sure we asked for it at least once
    LaunchedEffect(Unit) { if (branches.isEmpty()) repo.fetchGitStatus(withBranches = true) }

    val target = if (newBranch) name else picked.orEmpty()

    PocketSheet(onDismiss, dropKeyboard = false) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(stringResource(Res.string.wt_new_title), color = Tok.tx, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Box(Modifier.weight(1f))
            Text(repoBasename(repoRoot ?: ""), color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).clip(RoundedCornerShape(10.dp))
                .background(Tok.raised).padding(3.dp),
        ) {
            @Composable
            fun seg(label: String, isNew: Boolean) {
                val on = newBranch == isNew
                Box(
                    Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (on) Tok.tx.copy(alpha = 0.10f) else Color.Transparent)
                        .clickable(enabled = !on) { newBranch = isNew },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label, color = if (on) Tok.tx else Tok.tx2, fontSize = 13.5.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
            seg(stringResource(Res.string.wt_seg_existing), isNew = false)
            seg(stringResource(Res.string.wt_seg_new), isNew = true)
        }

        if (!newBranch) {
            Box(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 12.dp)
                    .height(42.dp).clip(RoundedCornerShape(11.dp)).background(Tok.raised)
                    .border(1.dp, Tok.hair, RoundedCornerShape(11.dp)).padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) Text(
                    stringResource(Res.string.wt_search_branches), color = Tok.muted,
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                )
                BasicTextField(
                    query, { query = it }, singleLine = true,
                    textStyle = TextStyle(color = Tok.tx, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                    cursorBrush = SolidColor(Tok.accent), modifier = Modifier.fillMaxWidth(),
                )
            }
            val shown = remember(branches, query) {
                branches.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
                    .sortedByDescending { it.lastCommitAt }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                items(shown, key = { it.name }) { b -> BranchPickRow(b, picked == b.name) { picked = b.name } }
            }
        } else {
            Column(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text(
                    stringResource(Res.string.wt_branch_name).uppercase(), color = Tok.tx2,
                    fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp,
                )
                Box(
                    Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(11.dp)).background(Tok.raised)
                        .border(1.dp, Tok.accent, RoundedCornerShape(11.dp)).padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (name.isEmpty()) Text(
                        stringResource(Res.string.git_new_branch_hint), color = Tok.muted,
                        fontFamily = FontFamily.Monospace, fontSize = 13.5.sp,
                    )
                    BasicTextField(
                        name, { name = it }, singleLine = true,
                        textStyle = TextStyle(color = Tok.tx, fontSize = 13.5.sp, fontFamily = FontFamily.Monospace),
                        cursorBrush = SolidColor(Tok.accent), modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(stringResource(Res.string.wt_branched_from, defaultBranch), color = Tok.muted, fontSize = 11.5.sp, lineHeight = 16.5.sp)
            }
        }

        // shared by both segments: where this lands, updating as you type. Read-only — the daemon
        // computes and validates the real path; showing it is a promise, not an input.
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair).padding(bottom = 14.dp))
            Column(
                Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(11.dp)).background(Tok.surface)
                    .border(1.dp, Tok.hair, RoundedCornerShape(11.dp)).padding(horizontal = 13.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    stringResource(Res.string.wt_location).uppercase(), color = Tok.tx2,
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.4.sp,
                )
                Text(
                    worktreeLocationPreview(repoRoot, target),
                    color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(stringResource(Res.string.wt_location_note), color = Tok.muted, fontSize = 11.5.sp)
            }
            val on = target.isNotBlank()
            Box(
                Modifier.fillMaxWidth().padding(top = 12.dp).height(50.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (on) Tok.accent else Tok.accent.copy(alpha = 0.16f))
                    .then(if (on) Modifier.clickable { repo.addWorktree(target.trim(), createBranch = newBranch); onDismiss() } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(Res.string.wt_create),
                    color = if (on) Tok.base else Tok.tx.copy(alpha = 0.34f),
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Box(Modifier.height(14.dp))
    }
}

@Composable
private fun BranchPickRow(b: GitBranchInfo, selected: Boolean, onPick: () -> Unit) {
    val inUse = b.checkedOutAt != null
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 54.dp).clickable(enabled = !inUse, onClick = onPick)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Box(Modifier.size(15.dp), contentAlignment = Alignment.Center) {
                if (selected && !inUse) Text("✓", color = Tok.accent, fontSize = 13.sp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    b.name,
                    color = when {
                        inUse -> Tok.muted
                        selected -> Tok.tx
                        else -> Tok.tx2
                    },
                    fontFamily = FontFamily.Monospace, fontSize = 13.5.sp,
                    fontWeight = if (selected && !inUse) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (inUse) stringResource(Res.string.wt_in_use, midTruncatePath(b.checkedOutAt ?: "", 30))
                    else if (b.lastCommitAt > 0) relativeTime(b.lastCommitAt) else "",
                    color = Tok.muted, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
    }
}
