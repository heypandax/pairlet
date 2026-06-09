package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ccpocket.app.media.rememberImageAttacher
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
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
import dev.ccpocket.protocol.DirectoryEntry
import kotlinx.coroutines.CoroutineScope

@Composable
fun App(scope: CoroutineScope) {
    val repo = remember { PocketRepository(scope) }
    LaunchedEffect(Unit) {
        dev.ccpocket.app.telemetry.Telemetry.track(dev.ccpocket.app.telemetry.TelEvent.AppLaunch)
        if (repo.paired.value != null) repo.startRelay() // already paired -> straight to the list
    }
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
                PermissionSheet(
                    ask, repo.workdir.value,
                    onDeny = { repo.resolve(Decision.DENY) },
                    onOnce = { repo.resolve(Decision.ALLOW) },
                    onAlways = { repo.resolve(Decision.ALLOW, remember = true) },
                    onDismiss = { repo.dismissAsk() },
                )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DirectoryScreen(repo: PocketRepository) {
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Tok.surface).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Choose a directory", color = Tok.tx, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton({ repo.disconnect() }) { Text("Exit", color = Tok.muted, fontSize = 13.sp) }
        }
        OutlinedTextField(
            query, { query = it }, placeholder = { Text("filter…") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        val rows = remember(repo.directories.toList(), query) { buildDirRows(repo.directories, query) }
        PullToRefreshBox(
            isRefreshing = repo.refreshing.value,
            onRefresh = { repo.refreshDirectories() },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(rows) { row ->
                    when (row) {
                        is DirRow.Header -> Label(row.label)
                        is DirRow.Dir -> {
                            val e = row.entry
                            val sid = e.activeSessionId
                            if (e.open && sid != null) {
                                LiveProjectCell(e) { repo.openSession(e.path, sid) }
                            } else {
                                DirCell(
                                    e.name.ifBlank { e.path }, if (row.showPath) tilde(e.path) else null,
                                    e.hasSessions, indent = false,
                                ) { repo.listSessions(e.path) }
                            }
                        }
                    }
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

/** A live project row: the folder + branch, with its active session inline — tap goes straight into that session. */
@Composable
private fun LiveProjectCell(e: DirectoryEntry, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Tok.surface)
            .clickable(onClick = onClick).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("📁 ${e.name}", color = Tok.tx, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1)
            e.gitBranch?.let { Text(" · $it", color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1) }
        }
        Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("▸", color = if (e.executing) Tok.accent else Tok.tx2, fontSize = 13.sp, modifier = Modifier.padding(end = 6.dp))
            Text(e.activeSessionTitle ?: "session", color = Tok.tx2, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(
                if (e.executing) "running" else "idle",
                color = if (e.executing) Tok.accent else Tok.muted, fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun SessionsScreen(repo: PocketRepository) {
    val dir = repo.sessionsDir.value ?: return
    var pickMode by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
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
                            .clickable { pickMode = true }.padding(14.dp),
                    ) { Text("＋ New session", color = Tok.accent, fontWeight = FontWeight.SemiBold) }
                }
                items(repo.sessions) { s ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface)
                            .clickable { repo.openSession(dir, s.sessionId) }.padding(14.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.title, color = Tok.tx, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
                            if (s.live) Text("● running", color = Tok.ok, fontSize = 11.sp)
                        }
                        Text("💬 ${s.messageCount} · ⑂ ${s.gitBranch ?: "-"}", color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
            }
        }
        if (pickMode) {
            StartSessionModeSheet(
                onPick = { m -> pickMode = false; repo.openSession(dir, startMode = m) },
                onDismiss = { pickMode = false },
            )
        }
    }
}

@Composable
private fun ChatScreen(repo: PocketRepository) {
    var input by remember { mutableStateOf("") }
    var viewer by remember { mutableStateOf<Pair<List<ByteArray>, Int>?>(null) } // tapped sent images → full-screen
    var showModeSheet by remember { mutableStateOf(false) }
    // platform picker resizes/compresses on-device; the repo budgets the picked photos against the 256 KiB frame
    val launchPicker = rememberImageAttacher { added -> repo.attachImages(added) }
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
    val focus = LocalFocusManager.current
    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) focus.clearFocus() } // scrolling dismisses the keyboard
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().background(Tok.surface).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ repo.backToBrowse() }) { Text("←", color = Tok.tx2, fontSize = 18.sp) }
                Column(Modifier.weight(1f)) {
                    Text("Chat", color = Tok.tx, fontWeight = FontWeight.SemiBold)
                    Text(repo.workdir.value ?: "", color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1)
                }
                if (!repo.observing.value) {
                    ModeBadge(repo.mode.value, repo.allowRules.size) { showModeSheet = true }
                    Spacer(Modifier.width(6.dp))
                }
                if (repo.streaming.value) {
                    Text("●", color = Tok.accent, fontSize = 12.sp)
                    TextButton({ repo.stopSession() }) { Text("Stop", color = Tok.danger, fontSize = 13.sp) }
                }
            }
            LazyColumn(
                Modifier.weight(1f).padding(16.dp).pointerInput(Unit) { detectTapGestures { focus.clearFocus() } },
                state = listState, verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(repo.messages) { m -> MessageItem(m) { imgs, i -> viewer = imgs to i } }
            }
            if (repo.observing.value) {
                Row(Modifier.fillMaxWidth().background(Tok.surface).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("👁 Observing · running in a terminal", color = Tok.tx2, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Button({ repo.takeOver() }) { Text("Continue here") }
                }
            } else {
                val hasReady = repo.hasReadyImages()
                Column(Modifier.fillMaxWidth().background(Tok.surface)) {
                    AttachTray(repo.pendingImages, repo::removePendingImage)
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        val attachInteraction = remember { MutableInteractionSource() }
                        val attachPressed by attachInteraction.collectIsPressedAsState()
                        IconButton(onClick = { launchPicker() }, interactionSource = attachInteraction) {
                            Icon(
                                AttachImageIcon,
                                contentDescription = "Attach image",
                                tint = if (repo.pendingImages.isNotEmpty() || attachPressed) Tok.accent else Tok.tx2,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        OutlinedTextField(
                            input, { input = it },
                            placeholder = { Text(if (repo.pendingImages.isNotEmpty()) "Add a message…" else "Message Claude…") },
                            modifier = Modifier.weight(1f), maxLines = 4,
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            enabled = input.isNotBlank() || hasReady,
                            onClick = {
                                val t = input.trim()
                                if (t.isNotBlank() || hasReady) { repo.sendPrompt(t); input = "" }
                            },
                        ) { Text("Send") }
                    }
                }
            }
        }
        viewer?.let { (imgs, idx) -> ImageViewer(imgs, idx) { viewer = null } }
        if (showModeSheet) {
            ModeSheet(
                current = repo.mode.value, rules = repo.allowRules, switching = repo.switching.value,
                onSelect = { repo.switchMode(it) }, // keep the sheet open so the "switching" state shows
                onClearRule = { repo.clearRule(it) }, onClearAll = { repo.clearAllRules() },
                onDismiss = { showModeSheet = false },
            )
        }
    }
}

@Composable
private fun MessageItem(m: ChatItem, onOpenImages: (List<ByteArray>, Int) -> Unit = { _, _ -> }) {
    when (m) {
        is ChatItem.User -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("YOU", color = Tok.muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            if (m.images.isNotEmpty()) SentImages(m.images) { i -> onOpenImages(m.images, i) }
            if (m.text.isNotBlank()) Text(m.text, color = Tok.tx)
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
        is ChatItem.RuleChip -> AllowChip(m.rule)
    }
}

@Composable
private fun Label(text: String) =
    Text(text, color = Tok.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
