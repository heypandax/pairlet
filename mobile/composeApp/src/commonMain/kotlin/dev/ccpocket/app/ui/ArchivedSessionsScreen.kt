package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.SessionSummary
import org.jetbrains.compose.resources.stringResource

/**
 * The cross-project archive (issue #202) — the one place every archived session is reachable, grouped by
 * project path.
 *
 * Row interaction, per the design's verdict:
 *  - the trailing 44dp icon is the ONLY unarchive affordance, always visible, one tap, no confirm. Swipe and
 *    long-press were both rejected: this is a room you enter rarely, and a gesture nobody has rehearsed is a
 *    gesture nobody finds — better to omit it than ship half of it.
 *  - tapping anywhere else OPENS the session and leaves it archived (matching the daemon: opening is not
 *    an implicit restore, so you can look without re-filing).
 *  - a running archived session keeps its green dot: archived ≠ stopped.
 */
@Composable
internal fun ArchivedSessionsScreen(repo: PocketRepository, onBack: () -> Unit) {
    LaunchedEffect(Unit) { repo.listArchivedSessions() }
    val rows = repo.archivedSessions.toList()

    Column(Modifier.fillMaxSize().background(Tok.base)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "‹", color = Tok.tx, fontSize = 24.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack).padding(horizontal = 8.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(stringResource(Res.string.archive_title), color = Tok.tx, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (rows.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "${rows.size}", color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(Tok.surface)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }

        if (rows.isEmpty()) {
            // No illustration, no button: the empty state's job is to explain what archiving DOES, since
            // the only way to fill this screen is an action taken somewhere else.
            Column(Modifier.fillMaxSize().padding(horizontal = 28.dp), verticalArrangement = Arrangement.Center) {
                Text(stringResource(Res.string.archive_empty), color = Tok.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(Res.string.archive_empty_sub), color = Tok.muted, fontSize = 12.5.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            return@Column
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // grouped by the row's OWN cwd — the archive spans projects, so the path is the only grouping
            // key that exists here (there is no single "current" directory)
            rows.groupBy { it.cwd }.forEach { (cwd, inProject) ->
                item(key = "hdr-$cwd") {
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            tilde(cwd), color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        Text("${inProject.size}", color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
                items(inProject, key = { it.sessionId }) { s -> ArchivedRow(repo, s) }
            }
        }
    }
}

@Composable
private fun ArchivedRow(repo: PocketRepository, s: SessionSummary) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.weight(1f)
                .clickable {
                    // open, but STAY archived — looking is not re-filing
                    repo.openSession(s.cwd, s.sessionId, title = s.title, agent = s.agent ?: AgentKind.CLAUDE)
                }
                .padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.title, color = Tok.tx, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                AgentBadge(s.agent, gap = 8.dp)
                if (s.live || s.busy) { // archived ≠ stopped — the dot stays so nobody thinks it was killed
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
        // the one verb this screen exists for: 44dp, always visible, no confirmation
        Box(
            Modifier.size(44.dp).clip(CircleShape)
                .clickable {
                    repo.setSessionArchived(
                        s.cwd, s.sessionId, archived = false, fromArchiveView = true,
                        title = s.title, running = s.live || s.busy,
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Unarchive, stringResource(Res.string.archive_unarchive), tint = Tok.accent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(4.dp))
    }
}

/**
 * The archive confirmation (issue #202). Deliberately NOT an "Undo": archive and unarchive are exact
 * inverses, so the action offers the REVERSE VERB — one call, no undo stack, and the label stays literally
 * true about what tapping it does. The destination line answers "where did it go?", and [running] answers
 * the one thing a vanishing green dot makes people fear.
 */
@Composable
internal fun ArchiveToastBar(repo: PocketRepository, modifier: Modifier = Modifier) {
    val toast = repo.archiveToast.value ?: return
    LaunchedEffect(toast.sessionId, toast.at) {
        kotlinx.coroutines.delay(4_000)
        if (repo.archiveToast.value?.at == toast.at) repo.dismissArchiveToast()
    }
    Row(
        modifier.fillMaxWidth().padding(horizontal = 14.dp)
            .clip(RoundedCornerShape(12.dp)).background(Tok.raised).border(1.dp, Tok.hair, RoundedCornerShape(12.dp))
            .clickable { repo.dismissArchiveToast() }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (toast.archived) stringResource(Res.string.archive_toast_archived)
                else stringResource(Res.string.archive_toast_restored, tilde(toast.workdir)),
                color = Tok.tx, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                toast.title + if (toast.running) " · " + stringResource(Res.string.archive_toast_still_running) else "",
                color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        // the reverse verb, not "Undo"
        Text(
            if (toast.archived) stringResource(Res.string.archive_unarchive) else stringResource(Res.string.archive_session),
            color = Tok.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                repo.setSessionArchived(
                    toast.workdir, toast.sessionId, archived = !toast.archived,
                    fromArchiveView = !toast.archived, title = toast.title, running = toast.running,
                )
            }.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
    Spacer(Modifier.height(8.dp))
}
