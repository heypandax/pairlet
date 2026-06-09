package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.defaultDaemonUrl
import dev.ccpocket.app.data.ChatItem
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.Decision
import kotlinx.coroutines.CoroutineScope

@Composable
fun App(scope: CoroutineScope) {
    val repo = remember { PocketRepository(scope) }
    LaunchedEffect(Unit) { if (repo.paired.value != null) repo.startRelay() } // already paired -> straight to the list
    val pendingLink by dev.ccpocket.app.DeepLink.pending.collectAsState()
    LaunchedEffect(pendingLink) { pendingLink?.let { repo.handlePairUrl(it); dev.ccpocket.app.DeepLink.pending.value = null } }
    PocketTheme {
        Surface(Modifier.fillMaxSize(), color = Tok.base) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding()) {
                when {
                    !repo.connected.value -> if (repo.paired.value != null) ConnectScreen(repo) else PairingScreen(repo)
                    repo.convoId.value != null -> ChatScreen(repo)
                    repo.sessionsDir.value != null -> SessionsScreen(repo)
                    else -> DirectoryScreen(repo)
                }
            }
            repo.pendingAsk.value?.let { ask ->
                PermissionSheet(ask.tool, ask.inputPreview, { repo.resolve(Decision.ALLOW) }, { repo.resolve(Decision.DENY) })
            }
        }
    }
}

@Composable
private fun ConnectScreen(repo: PocketRepository) {
    val paired = repo.paired.value
    var link by remember { mutableStateOf("") }
    var url by remember { mutableStateOf(defaultDaemonUrl()) }
    var advanced by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("CC Pocket", color = Tok.tx, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        if (paired != null) {
            Text("Paired · ${paired.accountId.take(12)}…", color = Tok.tx2, fontSize = 14.sp)
            Spacer(Modifier.height(24.dp))
            Button({ repo.startRelay() }, Modifier.fillMaxWidth()) { Text("Connect") }
            Spacer(Modifier.height(8.dp))
            TextButton({ repo.unpair() }) { Text("Unpair", color = Tok.muted, fontSize = 12.sp) }
        } else {
            Text("Pair with your daemon", color = Tok.tx2, fontSize = 14.sp)
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(link, { link = it }, label = { Text("paste ccpocket://pair link") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            Button({ repo.pair(link) }, Modifier.fillMaxWidth(), enabled = link.isNotBlank()) { Text("Pair") }
        }
        Spacer(Modifier.height(8.dp))
        Text(repo.status.value, color = Tok.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(16.dp))
        TextButton({ advanced = !advanced }) {
            Text(if (advanced) "Hide advanced" else "Advanced · direct LAN", color = Tok.muted, fontSize = 12.sp)
        }
        if (advanced) {
            OutlinedTextField(url, { url = it }, label = { Text("daemon ws url") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedButton({ repo.startDirect(url) }, Modifier.fillMaxWidth()) { Text("Connect direct") }
        }
    }
}

@Composable
private fun DirectoryScreen(repo: PocketRepository) {
    var query by remember { mutableStateOf("") }
    val expanded = remember { mutableStateListOf<String>() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Tok.surface).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Choose a directory", color = Tok.tx, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton({ repo.disconnect() }) { Text("Exit", color = Tok.muted, fontSize = 13.sp) }
        }
        OutlinedTextField(
            query, { query = it }, placeholder = { Text("filter…") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        val rows = remember(repo.directories.toList(), query, expanded.toList()) { buildDirRows(repo.directories, query, expanded.toSet()) }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(rows) { row ->
                when (row) {
                    is DirRow.Header -> Label(row.label)
                    is DirRow.Dir -> DirCell(
                        row.entry.name.ifBlank { row.entry.path }, if (row.showPath) tilde(row.entry.path) else null,
                        row.entry.hasSessions, indent = false,
                    ) { repo.listSessions(row.entry.path) }
                    is DirRow.WtHeader -> WtHeaderCell(row.name, row.count, row.expanded) {
                        if (row.key in expanded) expanded.remove(row.key) else expanded.add(row.key)
                    }
                    is DirRow.WtChild -> DirCell(
                        row.entry.name.ifBlank { row.entry.path }, null, row.entry.hasSessions, indent = true,
                    ) { repo.listSessions(row.entry.path) }
                }
            }
        }
    }
}

@Composable
private fun DirCell(name: String, path: String?, hasSessions: Boolean, indent: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (indent) 16.dp else 0.dp)
            .clip(RoundedCornerShape(10.dp)).background(Tok.surface).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, color = Tok.tx, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1)
            if (path != null) Text(path, color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1)
        }
        if (hasSessions) Text("history", color = Tok.accent, fontSize = 11.sp)
    }
}

@Composable
private fun WtHeaderCell(name: String, count: Int, expanded: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Tok.raised).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (expanded) "▾" else "▸", color = Tok.tx2, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
        Text(name, color = Tok.tx2, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
        Text("·$count", color = Tok.muted, fontSize = 12.sp)
    }
}

@Composable
private fun SessionsScreen(repo: PocketRepository) {
    val dir = repo.sessionsDir.value ?: return
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Tok.surface).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ repo.backToDirectories() }) { Text("←", color = Tok.tx2, fontSize = 18.sp) }
            Column(Modifier.weight(1f)) {
                Text("Sessions", color = Tok.tx, fontWeight = FontWeight.SemiBold)
                Text(dir, color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1)
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.accent.copy(alpha = 0.16f))
                        .clickable { repo.openSession(dir) }.padding(14.dp),
                ) { Text("＋ New session", color = Tok.accent, fontWeight = FontWeight.SemiBold) }
            }
            items(repo.sessions) { s ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface)
                        .clickable { repo.openSession(dir, s.sessionId) }.padding(14.dp),
                ) {
                    Text(s.title, color = Tok.tx, fontWeight = FontWeight.SemiBold)
                    Text("💬 ${s.messageCount} · ⑂ ${s.gitBranch ?: "-"}", color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun ChatScreen(repo: PocketRepository) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    // stick to the very bottom: re-scroll on new messages AND while the last one streams/grows;
    // a huge scrollOffset lands at the bottom even when the last message is taller than the viewport.
    LaunchedEffect(repo.messages.size, repo.messages.lastOrNull()) {
        if (repo.messages.isNotEmpty()) listState.scrollToItem(repo.messages.lastIndex, Int.MAX_VALUE)
    }
    // when the keyboard opens/animates, keep the latest message pinned above the input
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottom) {
        if (imeBottom > 0 && repo.messages.isNotEmpty()) listState.scrollToItem(repo.messages.lastIndex, Int.MAX_VALUE)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Tok.surface).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ repo.backToBrowse() }) { Text("←", color = Tok.tx2, fontSize = 18.sp) }
            Column(Modifier.weight(1f)) {
                Text("Chat", color = Tok.tx, fontWeight = FontWeight.SemiBold)
                Text(repo.workdir.value ?: "", color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1)
            }
            if (repo.streaming.value) Text("●", color = Tok.accent)
        }
        LazyColumn(Modifier.weight(1f).padding(16.dp), state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(repo.messages) { m -> MessageItem(m) }
        }
        Row(Modifier.fillMaxWidth().background(Tok.surface).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, placeholder = { Text("Message Claude…") }, modifier = Modifier.weight(1f), maxLines = 4)
            Spacer(Modifier.width(8.dp))
            Button({ if (input.isNotBlank()) { repo.sendPrompt(input.trim()); input = "" } }) { Text("Send") }
        }
    }
}

@Composable
private fun MessageItem(m: ChatItem) {
    when (m) {
        is ChatItem.User -> Column {
            Text("YOU", color = Tok.muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Text(m.text, color = Tok.tx)
        }
        is ChatItem.Assistant -> MarkdownText(m.text, Tok.tx)
        is ChatItem.Tool -> {
            var expanded by remember(m) { mutableStateOf(false) }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Tok.raised)
                    .clickable { expanded = !expanded }.padding(8.dp),
            ) {
                Text("⚙ ${m.tool}", color = Tok.accent, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                if (m.preview.isNotBlank()) Text(
                    m.preview, color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        is ChatItem.Sys -> Text(m.text, color = Tok.danger, fontSize = 12.sp)
    }
}

@Composable
private fun PermissionSheet(tool: String, preview: String, onAllow: () -> Unit, onDeny: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = { Button(onAllow) { Text("Allow") } },
        dismissButton = { OutlinedButton(onDeny) { Text("Deny") } },
        title = { Text("Claude needs permission") },
        text = {
            Column {
                Text(tool, fontWeight = FontWeight.SemiBold, color = Tok.tx)
                Spacer(Modifier.height(8.dp))
                Text(preview, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Tok.tx2)
            }
        },
        containerColor = Tok.raised,
    )
}

@Composable
private fun Label(text: String) =
    Text(text, color = Tok.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
