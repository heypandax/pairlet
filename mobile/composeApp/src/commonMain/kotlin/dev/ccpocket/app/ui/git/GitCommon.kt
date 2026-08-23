package dev.ccpocket.app.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.GitChip
import dev.ccpocket.app.data.GitConflictShape
import dev.ccpocket.app.data.midTruncatePath
import dev.ccpocket.app.data.parseUnifiedDiff
import dev.ccpocket.app.data.staleDaemon
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.DiffEmptyState
import dev.ccpocket.app.ui.DiffView
import dev.ccpocket.app.ui.TruncatedBanner
import dev.ccpocket.protocol.GitDiff
import dev.ccpocket.protocol.gitStderrHighlight
import org.jetbrains.compose.resources.stringResource

// ════════════════════════════════════════════════════════════════════
//  Git surface — the pieces the mobile panel, the git diff screen and
//  the desktop overlay all share (issue #280 / #281, design handoff in
//  claude-design-handoff/git-panel-280 + worktrees-281).
//
//  Colours come from [Tok] wherever a token means the same thing the
//  handoff's variable did: --ok/--warn/--bad/--acc map 1:1 onto
//  ok/warn/danger/accent, and the tint alphas are the handoff's own.
// ════════════════════════════════════════════════════════════════════

/** Which of the three session tabs is lit. The Files/Diff pair is the app's existing surface (the
 *  changed-files sheet and its viewer); Git is the third one this issue adds — they are three
 *  destinations, not three modes of one screen, so this is a selector rather than shared state. */
enum class GitTab { FILES, DIFF, GIT }

/**
 * The `Files | Diff | Git` tab row (design: 42dp, 22dp gaps, 20dp side padding, 2dp accent underline
 * under the selected label, a hairline under the whole row). [onPick] is null for a tab that has
 * nowhere to go right now — Diff with no file open — and that tab renders inert rather than absent,
 * so the row does not change width between screens.
 */
@Composable
fun GitTabRow(selected: GitTab, onPick: (GitTab) -> Unit, diffEnabled: Boolean) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            @Composable
            fun tab(label: String, tab: GitTab, enabled: Boolean) {
                val on = selected == tab
                Column(
                    Modifier.height(42.dp)
                        .then(if (enabled && !on) Modifier.clickable { onPick(tab) } else Modifier),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            label,
                            color = when {
                                on -> Tok.tx
                                enabled -> Tok.tx2
                                else -> Tok.muted.copy(alpha = 0.55f)
                            },
                            fontSize = 14.5.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                    Box(
                        Modifier.fillMaxWidth().height(2.dp)
                            .background(if (on) Tok.accent else Color.Transparent),
                    )
                }
            }
            tab(stringResource(Res.string.git_files_tab), GitTab.FILES, enabled = true)
            tab(stringResource(Res.string.diff_tab), GitTab.DIFF, enabled = diffEnabled)
            tab(stringResource(Res.string.git_tab), GitTab.GIT, enabled = true)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
    }
}

/** The row chip's (background, foreground) pair. Alphas are the handoff's; hues are the semantic
 *  tokens, so a light-theme switch recolours the whole panel with everything else. */
fun gitChipColors(chip: GitChip): Pair<Color, Color> = when (chip) {
    GitChip.STAGED -> Tok.ok.copy(alpha = 0.15f) to Tok.ok
    GitChip.UNSTAGED -> Tok.warn.copy(alpha = 0.15f) to Tok.warn
    GitChip.CONFLICT -> Tok.danger.copy(alpha = 0.17f) to Tok.danger
    GitChip.UNTRACKED -> Tok.muted.copy(alpha = 0.16f) to Tok.muted
}

/** 22×20 rounded-square carrying git's OWN status letter (design §2 — the panel teaches nothing new). */
@Composable
fun GitStatusChip(letter: String, chip: GitChip, dense: Boolean = false) {
    val (bg, fg) = gitChipColors(chip)
    Box(
        Modifier.size(width = if (dense) 20.dp else 22.dp, height = if (dense) 18.dp else 20.dp)
            .clip(RoundedCornerShape(5.dp)).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter.take(1), color = fg, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** One-line monospace path that middle-truncates from the LEFT so the filename never moves (A1). */
@Composable
fun GitPathText(
    path: String,
    max: Int = 34,
    color: Color = Tok.tx2,
    fontSize: androidx.compose.ui.unit.TextUnit = 12.5.sp,
    modifier: Modifier = Modifier,
) {
    Text(
        remember(path, max) { midTruncatePath(path, max) },
        color = color, fontFamily = FontFamily.Monospace, fontSize = fontSize,
        maxLines = 1, overflow = TextOverflow.Clip, softWrap = false, modifier = modifier,
    )
}

/** The conflict shape in the user's language, never git's two letters. */
@Composable
fun conflictShapeLabel(shape: GitConflictShape): String = stringResource(
    when (shape) {
        GitConflictShape.BOTH_MODIFIED -> Res.string.git_conflict_both_modified
        GitConflictShape.BOTH_ADDED -> Res.string.git_conflict_both_added
        GitConflictShape.BOTH_DELETED -> Res.string.git_conflict_both_deleted
        GitConflictShape.ADDED_BY_US -> Res.string.git_conflict_added_by_us
        GitConflictShape.ADDED_BY_THEM -> Res.string.git_conflict_added_by_them
        GitConflictShape.DELETED_BY_US -> Res.string.git_conflict_deleted_by_us
        GitConflictShape.DELETED_BY_THEM -> Res.string.git_conflict_deleted_by_them
    },
)

/**
 * The Git diff body — [dev.ccpocket.app.ui.DiffPaneBody]'s twin for a [GitDiff]. Deliberately a
 * PARALLEL five-line function rather than a generalised one: the two frames share nothing but the
 * unified-diff string, and the rendering itself ([parseUnifiedDiff] → [DiffView]) is already the
 * single shared implementation both go through.
 */
@Composable
fun GitDiffPaneBody(diff: GitDiff?, ext: String?, dense: Boolean, wrap: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        when {
            diff == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Tok.tx2, strokeWidth = 2.dp)
            }
            diff.staleDaemon -> DiffEmptyState(
                glyph = ">_",
                title = stringResource(Res.string.diff_stale_title),
                caption = stringResource(Res.string.diff_stale_hint),
            )
            !diff.ok || diff.diff.isNullOrBlank() ->
                DiffEmptyState(glyph = "±", title = stringResource(Res.string.diff_none), caption = null)
            else -> Column(Modifier.fillMaxSize()) {
                if (diff.truncated) TruncatedBanner(shownKb = (diff.diff?.length ?: 0) / 1024)
                val hunks = remember(diff.diff) { parseUnifiedDiff(diff.diff ?: "") }
                DiffView(hunks, ext = ext, dense = dense, wrap = wrap, modifier = Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/**
 * The pinned composer's error strip (A5 red / A6 amber): one sentence of ours, then git's own stderr
 * line verbatim in mono — a developer recognises `(fetch first)` instantly and we never rewrite git's
 * words into something a search engine cannot match.
 *
 * [hint] is the second sentence of OURS — what to do next — and it only appears when we actually know
 * (issue #280 真机反馈 5: a push rejected by a diverged remote). Order is ours, ours, git's.
 *
 * The mono line goes through [gitStderrHighlight] here as well as in the daemon: a phone on a new build
 * talking to a daemon that still ships the old verbatim stderr would otherwise show `To https://…`,
 * which is the line that started this complaint.
 */
@Composable
fun GitErrorStrip(title: String, detail: String?, amber: Boolean, onDismiss: () -> Unit, hint: String? = null) {
    val ink = if (amber) Tok.warn else Tok.danger
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(ink.copy(alpha = if (amber) 0.08f else 0.09f))
            .border(1.dp, ink.copy(alpha = if (amber) 0.28f else 0.30f), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.padding(top = 1.dp).size(15.dp).clip(RoundedCornerShape(999.dp))
                .border(1.3.dp, ink, RoundedCornerShape(999.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("!", color = ink, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = ink, fontSize = 13.sp, lineHeight = 17.5.sp, fontWeight = FontWeight.SemiBold)
            if (!hint.isNullOrBlank()) Text(
                hint, color = Tok.tx2, fontSize = 12.sp, lineHeight = 17.sp,
            )
            val line = remember(detail) { detail?.let { gitStderrHighlight(it) }.orEmpty() }
            if (line.isNotEmpty()) Text(
                line,
                color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier.size(24.dp).clip(RoundedCornerShape(999.dp)).clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) { Text("×", color = Tok.muted, fontSize = 14.sp) }
    }
}
