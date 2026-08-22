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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.data.midTruncatePath
import dev.ccpocket.app.data.parseUnifiedDiff
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.rememberWrapState
import dev.ccpocket.protocol.GIT_OP_REVERT
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.gestures.detectTapGestures

// ════════════════════════════════════════════════════════════════════
//  Screen B — one path's diff (issue #280, design B1–B3).
//  Today's diff viewer plus exactly two new things: the Working|Staged
//  control (the question the panel creates: what will this commit
//  contain) and a Revert file… item one level down in the overflow.
// ════════════════════════════════════════════════════════════════════

@Composable
fun GitDiffScreen(
    repo: PocketRepository,
    onBack: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    dev.ccpocket.app.SystemBackHandler(enabled = true) { onBack() }
    val path = repo.gitDiffPath.value ?: return
    val diff = repo.gitDiff.value
    val staged = repo.gitDiffStaged.value
    val ext = path.substringAfterLast('.', "").lowercase()
    val wrap = rememberWrapState()
    var overflow by remember(path) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

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
                    remember(path) { midTruncatePath(path, 30) },
                    color = Tok.tx, fontSize = 16.5.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(999.dp)).clickable { overflow = !overflow },
                    contentAlignment = Alignment.Center,
                ) { Text("⋯", color = if (overflow) Tok.tx else Tok.tx2, fontSize = 20.sp, fontWeight = FontWeight.Medium) }
            }
            GitTabRow(
                selected = GitTab.DIFF,
                diffEnabled = true,
                onPick = { tab ->
                    when (tab) {
                        GitTab.FILES -> onOpenFiles()
                        GitTab.GIT -> onBack()
                        GitTab.DIFF -> Unit
                    }
                },
            )
            SideBar(
                staged = staged,
                adds = diff?.takeIf { it.ok }?.adds ?: 0,
                dels = diff?.takeIf { it.ok }?.dels ?: 0,
                hunks = remember(diff?.diff) { if (diff?.ok == true) parseUnifiedDiff(diff.diff ?: "").size else 0 },
                // the two sides are two different reads: flipping re-asks the daemon rather than
                // filtering one answer, because a partially staged file genuinely has two truths
                onPick = { repo.openGitDiff(path, staged = it) },
            )
            GitDiffPaneBody(diff, ext = ext.ifEmpty { null }, dense = false, wrap = wrap.diff.value, modifier = Modifier.weight(1f))
        }

        if (overflow) {
            // scrim that only dismisses — nothing in the menu acts immediately (B3)
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { overflow = false } })
            Column(
                Modifier.align(Alignment.TopEnd).padding(top = 88.dp, end = 14.dp)
                    .width(214.dp).clip(RoundedCornerShape(13.dp)).background(Tok.raised)
                    .border(1.dp, Tok.hair, RoundedCornerShape(13.dp)).padding(5.dp),
            ) {
                OverflowRow(stringResource(Res.string.menu_copy_path), first = true) {
                    clipboard.setText(AnnotatedString(path)); overflow = false
                }
                OverflowRow(stringResource(Res.string.git_open_terminal), first = false) {
                    overflow = false; onOpenTerminal()
                }
                // last, in danger ink, with a trailing ellipsis promising the second step (→ Screen D)
                OverflowRow(stringResource(Res.string.git_revert_file), first = false, danger = true) {
                    overflow = false
                    repo.gitAct(GIT_OP_REVERT, paths = listOf(path))
                }
            }
        }
    }

    repo.gitPendingConfirm.value?.let { GitConfirmSheet(repo, it) }
}

/** The one new control: `Working | Staged`, with the side's own totals opposite it rather than on a
 *  row of their own — changing side changes the numbers, which is how a partially staged file shows
 *  that it has two truths (B2). */
@Composable
private fun SideBar(staged: Boolean, adds: Int, dels: Int, hunks: Int, onPick: (Boolean) -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.clip(RoundedCornerShape(9.dp)).background(Tok.raised).padding(2.dp)) {
                @Composable
                fun seg(label: String, isStaged: Boolean) {
                    val on = staged == isStaged
                    Text(
                        label,
                        color = if (on) Tok.tx else Tok.tx2,
                        fontSize = 12.5.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                        modifier = Modifier.height(28.dp).clip(RoundedCornerShape(7.dp))
                            .background(if (on) Tok.tx.copy(alpha = 0.10f) else Color.Transparent)
                            .clickable(enabled = !on) { onPick(isStaged) }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
                seg(stringResource(Res.string.git_working), isStaged = false)
                seg(stringResource(Res.string.git_staged), isStaged = true)
            }
            Box(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("+$adds", color = Tok.ok, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp)
                Text("−$dels", color = Tok.danger, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp)
                Text(
                    "· " + if (hunks == 1) stringResource(Res.string.git_hunk_one) else stringResource(Res.string.git_hunks, hunks),
                    color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
    }
}

@Composable
private fun OverflowRow(label: String, first: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    Column {
        if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Box(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(9.dp))
                .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(label, color = if (danger) Tok.danger else Tok.tx, fontSize = 14.sp, lineHeight = 19.sp)
        }
    }
}
