package dev.ccpocket.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.data.TerminalEntry
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import org.jetbrains.compose.resources.stringResource

/**
 * The quick-terminal (issue #3): run one-off shell commands in the active session's cwd to check the
 * environment from afar. Each command is approval-gated by the daemon (the standard permission sheet
 * appears over this screen); the result lands inline. Full-screen so output has room and the approval
 * overlay sits cleanly on top.
 */
@Composable
fun TerminalScreen(repo: PocketRepository, onBack: () -> Unit) {
    dev.ccpocket.app.SystemBackHandler(enabled = true) { onBack() }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val entries = repo.terminalEntries
    // keep the newest output in view as commands run / results land
    LaunchedEffect(entries.size, repo.terminalBusy.value) {
        if (entries.isNotEmpty()) runCatching { listState.animateScrollToItem(entries.size - 1) }
    }
    Column(Modifier.fillMaxSize().background(Tok.base).imePadding()) {
        Row(
            Modifier.fillMaxWidth().background(Tok.surface).padding(start = 6.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton({ onBack() }) { Text("←", color = Tok.tx2, fontSize = 18.sp) }
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.terminal_title), color = Tok.tx, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                TailPathText(repo.workdir.value ?: "", fontSize = 11.sp)
            }
            if (entries.isNotEmpty()) {
                Text(
                    stringResource(Res.string.terminal_clear), color = Tok.tx2, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { repo.clearTerminal() }.padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (entries.isEmpty()) {
                Text(
                    stringResource(Res.string.terminal_empty), color = Tok.muted, fontSize = 13.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 14.dp), state = listState,
                    verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp),
                ) {
                    items(entries) { e -> TerminalRow(e) }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(Tok.surface).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val busy = repo.terminalBusy.value
            OutlinedTextField(
                input, { input = it },
                placeholder = { Text(stringResource(Res.string.terminal_hint), fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
                singleLine = true, enabled = !busy,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            val canRun = input.isNotBlank() && !busy
            Box(
                Modifier.height(54.dp).widthIn(min = 64.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (canRun) Tok.accent else Tok.raised).alpha(if (canRun) 1f else 0.6f)
                    .clickable(enabled = canRun) { repo.runShell(input); input = "" },
                contentAlignment = Alignment.Center,
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Tok.tx2, strokeWidth = 2.dp)
                else Text(stringResource(Res.string.terminal_run), color = Tok.base, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun TerminalRow(e: TerminalEntry) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Text("$ ", color = Tok.accent, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            Text(e.command, color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.weight(1f))
        }
        val r = e.result
        if (r == null) {
            Text("…", color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            return
        }
        val body = buildString {
            if (r.stdout.isNotEmpty()) append(r.stdout.trimEnd('\n'))
            if (r.stderr.isNotEmpty()) { if (isNotEmpty()) append('\n'); append(r.stderr.trimEnd('\n')) }
            r.error?.let { if (isNotEmpty()) append('\n'); append(it) }
        }
        if (body.isNotEmpty()) {
            SelectionContainer(Modifier.padding(top = 3.dp)) {
                Text(body, color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.fillMaxWidth())
            }
        }
        val bad = r.denied || r.timedOut || r.error != null || r.exitCode != 0
        val status = when {
            r.denied -> "✗ " + stringResource(Res.string.terminal_denied)
            r.timedOut -> "✗ " + stringResource(Res.string.terminal_timed_out)
            r.error != null -> "✗ error"
            else -> "exit ${r.exitCode}"
        }
        Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(status, color = if (bad) Tok.danger else Tok.ok, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.weight(1f))
            if (body.isNotEmpty()) CopyChip(body)
        }
    }
}
