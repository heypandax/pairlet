@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Reorder
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Computer
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.ccpocket.app.media.rememberImageAttacher
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.ccpocket.app.defaultDaemonUrl
import dev.ccpocket.app.data.ChatItem
import dev.ccpocket.app.data.ConnPhase
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.data.StatusMsg
import dev.ccpocket.app.data.VoiceState
import dev.ccpocket.app.pairing.displayName
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.LocalFontScale
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.voice.openAppSettings
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.CommandSource
import dev.ccpocket.protocol.DEFAULT_CONTEXT_WINDOW
import dev.ccpocket.protocol.Decision
import dev.ccpocket.protocol.DirectoryEntry
import dev.ccpocket.protocol.SlashCommand
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.compose.resources.stringResource

@Composable
fun App(scope: CoroutineScope) {
    val repo = remember { PocketRepository(scope) }
    LaunchedEffect(Unit) {
        dev.ccpocket.app.telemetry.Telemetry.track(dev.ccpocket.app.telemetry.TelEvent.AppLaunch)
        if (repo.paired.value != null) repo.startRelay() // already paired -> straight to the list
    }
    val pendingLink by dev.ccpocket.app.DeepLink.pending.collectAsState()
    LaunchedEffect(pendingLink) { pendingLink?.let { repo.handlePairUrl(it); dev.ccpocket.app.DeepLink.pending.value = null } }
    // a tapped task-complete push deep-links straight into its session (connecting first if needed)
    val pushOpen by dev.ccpocket.app.PushRoute.pending.collectAsState()
    LaunchedEffect(pushOpen) { pushOpen?.let { repo.requestOpenSession(it.workdir, it.sessionId); dev.ccpocket.app.PushRoute.pending.value = null } }
    dev.ccpocket.app.OnAppForeground { repo.onAppForeground() } // iOS kills sockets in background — reconnect on return
    // Android system back walks the in-app stack (chat → sessions → directories) instead of leaving
    // the app; at the root it stays disabled so the system default (exit) applies. An open sheet
    // registers its own handler later in composition, which wins while it is showing (LIFO).
    dev.ccpocket.app.SystemBackHandler(
        enabled = repo.sessionActive.value && (repo.convoId.value != null || repo.sessionsDir.value != null),
    ) {
        if (repo.convoId.value != null) repo.backToBrowse() else repo.backToDirectories()
    }
    PocketTheme(repo.fontScale.value) {
        Surface(Modifier.fillMaxSize(), color = Tok.base) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding()) {
                // pushes content down instead of overlaying the header; steady while retrying (no flicker)
                // preview/recording mode hides the demo banner for a clean marketing capture
                if (repo.demoMode.value && !dev.ccpocket.app.isPreviewMode()) StatusBanner(Tok.accent, stringResource(Res.string.demo_banner))
                if (repo.sessionActive.value && repo.phase.value == ConnPhase.Reconnecting) StatusBanner(Tok.danger, stringResource(Res.string.reconnect_banner))
                Box(Modifier.weight(1f)) {
                    when {
                        // a dead transport does NOT leave the content screens — ConnectionGate + auto-retry handle it
                        !repo.sessionActive.value ->
                            if (repo.addingDevice.value || repo.pairedList.isEmpty()) PairingScreen(repo) else ConnectScreen(repo)
                        repo.demoConnecting.value -> DemoConnectScreen { repo.finishDemoConnect() } // PREVIEW opener
                        else -> ConnectionGate(repo) {
                            when {
                                repo.convoId.value != null -> ChatScreen(repo)
                                repo.sessionsDir.value != null -> SessionsScreen(repo)
                                else -> DirectoryScreen(repo)
                            }
                        }
                    }
                }
            }
            // a permission decision never needs typing — drop the keyboard so the sheet isn't cramped
            val rootFocus = LocalFocusManager.current
            LaunchedEffect(repo.pendingAsk.value?.askId) {
                if (repo.pendingAsk.value != null) rootFocus.clearFocus()
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

/** Slim status strip above the content — reconnecting (danger-red) or computer-offline (amber). */
@Composable
private fun StatusBanner(color: Color, text: String) {
    Row(
        Modifier.fillMaxWidth().background(color.copy(alpha = 0.14f)).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** PREVIEW: a brief "connecting → end-to-end encrypted" opener shown when entering the demo (scene 1). */
@Composable
private fun DemoConnectScreen(onDone: () -> Unit) {
    var secured by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(1500); secured = true
        delay(2200); onDone()
    }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("CC Pocket", color = Tok.tx, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(44.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Icon(Icons.Rounded.Smartphone, null, tint = Tok.tx2, modifier = Modifier.size(40.dp))
            Icon(
                if (secured) Icons.Rounded.Lock else Icons.Rounded.MoreHoriz, null,
                tint = if (secured) Tok.ok else Tok.muted, modifier = Modifier.size(if (secured) 24.dp else 28.dp),
            )
            Icon(Icons.Rounded.Computer, null, tint = Tok.tx2, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(44.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PulseDot(if (secured) Tok.ok else Tok.warn, size = 8.dp)
            Text(
                stringResource(if (secured) Res.string.preview_encrypted else Res.string.preview_connecting),
                color = if (secured) Tok.ok else Tok.tx2,
                fontSize = 15.sp, fontWeight = if (secured) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

/**
 * Gates the content screens on the honest connection [ConnPhase]. Replaces "blank screen on any failure"
 * with explicit, actionable states; self-heals (auto-retry) and only escalates to a full screen once a
 * failure persists. Reconnecting/Ready just show the content (the slim banner rides above it).
 */
@Composable
private fun ConnectionGate(repo: PocketRepository, content: @Composable () -> Unit) {
    when (repo.phase.value) {
        ConnPhase.PairingInvalid -> CenteredState(
            Tok.danger,
            stringResource(Res.string.conn_pairing_invalid_title),
            stringResource(Res.string.conn_pairing_invalid_body),
            stringResource(Res.string.conn_repair), { repo.unpairActive() },
        )
        ConnPhase.RelayUnreachable -> CenteredState(
            Tok.warn,
            stringResource(Res.string.conn_relay_unreachable_title),
            stringResource(Res.string.conn_relay_unreachable_body),
            stringResource(Res.string.conn_retry), { repo.retryConnection() }, onExit = { repo.disconnect() },
        )
        ConnPhase.ComputerOffline ->
            if (repo.convoId.value != null) { StatusBanner(Tok.warn, stringResource(Res.string.conn_computer_offline_banner)); content() } // mid-chat: keep history
            else CenteredState(
                Tok.warn,
                stringResource(Res.string.conn_computer_offline_title),
                stringResource(Res.string.conn_computer_offline_body),
                stringResource(Res.string.conn_retry), { repo.retryConnection() }, onExit = { repo.disconnect() },
                hint = stringResource(Res.string.conn_computer_offline_hint),
            )
        ConnPhase.Connecting ->
            if (repo.directoriesLoaded.value || repo.convoId.value != null) content() else DirectorySkeleton()
        ConnPhase.Reconnecting, ConnPhase.Ready -> content()
    }
}

/** A centered dot + title + body + primary action (+ optional hint / exit). Shared by the gate states. */
@Composable
private fun CenteredState(
    dot: Color, title: String, body: String, primary: String, onPrimary: () -> Unit,
    onExit: (() -> Unit)? = null, hint: String? = null,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PulseDot(dot, size = 10.dp)
        Spacer(Modifier.height(16.dp))
        Text(title, color = Tok.tx, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, color = Tok.tx2, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp)
        if (hint != null) {
            Spacer(Modifier.height(6.dp))
            Text(hint, color = Tok.muted, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(24.dp))
        Button(onPrimary, Modifier.fillMaxWidth()) { Text(primary) }
        if (onExit != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onExit) { Text(stringResource(Res.string.exit), color = Tok.muted, fontSize = 12.sp) }
        }
    }
}

/** First-connect placeholder: the directory header over a few shimmering rows (never a blank screen). */
@Composable
private fun DirectorySkeleton() {
    val shimmer by rememberInfiniteTransition().animateFloat(
        initialValue = 0.25f, targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
    )
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Tok.surface).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            PulseDot(Tok.warn)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.choose_directory), color = Tok.tx, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(5) {
                Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(10.dp)).graphicsLayer { alpha = shimmer }.background(Tok.surface))
            }
        }
    }
}

/** Real empty-list state — connected, but the computer has no projects open yet (not a blank screen). */
@Composable
private fun EmptyDirectories(onRefresh: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(Res.string.dir_empty_title), color = Tok.tx, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(Res.string.dir_empty_body), color = Tok.tx2, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 19.sp)
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onRefresh) { Text(stringResource(Res.string.dir_refresh)) }
    }
}

/** Disconnected, with at least one bound computer: the device picker. Tap one to connect, or add another. */
@Composable
private fun ConnectScreen(repo: PocketRepository) {
    var url by remember { mutableStateOf(defaultDaemonUrl()) }
    var advanced by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("CC Pocket", color = Tok.tx, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(Res.string.choose_computer), color = Tok.tx2, fontSize = 14.sp)
        Spacer(Modifier.height(20.dp))
        DeviceList(repo, onSwitch = { repo.switchDaemon(it) }, onAdd = { repo.beginAddDevice() })
        Spacer(Modifier.height(10.dp))
        Text(repo.status.value.resolve(), color = Tok.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(16.dp))
        TextButton({ advanced = !advanced }) {
            Text(stringResource(if (advanced) Res.string.hide_advanced else Res.string.advanced_direct_lan), color = Tok.muted, fontSize = 12.sp)
        }
        if (advanced) {
            OutlinedTextField(url, { url = it }, label = { Text(stringResource(Res.string.daemon_ws_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedButton({ repo.startDirect(url) }, Modifier.fillMaxWidth()) { Text(stringResource(Res.string.connect_direct)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DirectoryScreen(repo: PocketRepository) {
    var query by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    if (showSettings) { SettingsScreen(repo, onBack = { showSettings = false }); return } // full-screen, replaces this screen
    LaunchedEffect(Unit) { while (true) { delay(10_000); repo.refreshDirectoriesSilently() } } // pull-only list: re-pull quietly

    val tree = repo.treeView.value
    val dirsSnapshot = repo.directories.toList()
    val root = remember(dirsSnapshot) { treeRoot(dirsSnapshot) }
    val browse = repo.browsePath.value
    // a browse path the daemon no longer has (dirs changed) falls back to root
    val base = remember(dirsSnapshot, browse, root) {
        browse?.takeIf { b -> dirsSnapshot.any { it.path == b || it.path.startsWith(b + sepOf(b)) } } ?: root // sep-aware: a Windows daemon's paths use '\' (issue #19/#22 — tree drill-in)
    }
    val treeMode = tree && query.isBlank() // filtering or flat mode both render the flat grouped list

    val openSessionsLabel = stringResource(Res.string.dir_open_sessions)
    val projectsLabel = stringResource(Res.string.dir_projects)
    val activeLabel = stringResource(Res.string.dir_active)
    val pinnedLabel = stringResource(Res.string.dir_pinned)
    val currentProjectLabel = stringResource(Res.string.dir_current_project)
    // drilled into a folder → the header names where you are (folder name); root keeps the section title.
    // This stops the big title and the in-list "Projects" label from both reading "Projects" while drilled in.
    // Reuse crumbs() (the breadcrumb's helper) so the title and breadcrumb tail stay identical by construction.
    val headerTitle = if (treeMode && base != root) crumbs(base).lastOrNull() ?: projectsLabel else projectsLabel
    val pinnedSnapshot = repo.pinnedPaths.toList()
    val flatRows = remember(dirsSnapshot, query, pinnedSnapshot, openSessionsLabel, projectsLabel) {
        buildDirRows(repo.directories, query, pinnedSnapshot, pinnedLabel, openSessionsLabel, projectsLabel)
    }
    val treeRows = remember(dirsSnapshot, base) { buildTree(repo.directories, base) }
    // when drilled into a folder that is itself a project, buildTree leads with its own leaf — split it out
    // as the "current project" row (the rest are its subfolders). Computed once here, not per recomposition.
    val currentLeaf = remember(treeRows, base, root) {
        (treeRows.firstOrNull() as? TreeRow.Leaf)?.takeIf { base != root && it.entry.path == base }
    }
    val childRows = remember(treeRows, currentLeaf) { if (currentLeaf != null) treeRows.drop(1) else treeRows }
    val live = remember(dirsSnapshot) { dirsSnapshot.filter { it.open || it.busy } } // ACTIVE: live sessions
    // pinned projects shown at the tree root (in pin order, present-only) — mirrors the flat Pinned section
    val pinned = remember(dirsSnapshot, pinnedSnapshot) { pinnedEntries(dirsSnapshot, pinnedSnapshot) }
    // long-press a project → a small sheet to pin/unpin it
    var actionTarget by remember { mutableStateOf<DirectoryEntry?>(null) }
    // "+" → type an arbitrary path to start a session in a folder with no prior history (issue #7)
    var showNewPath by remember { mutableStateOf(false) }
    var newPathTarget by remember { mutableStateOf<String?>(null) }

    // typing in the filter then scrolling the list dismisses the keyboard (fires once per scroll gesture)
    val focus = LocalFocusManager.current
    val listState = rememberLazyListState()
    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) focus.clearFocus() }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        // ── top bar: "Projects" + connection sub-line · view toggle · settings ──
        Row(Modifier.fillMaxWidth().background(Tok.surface).padding(start = 16.dp, end = 6.dp, top = 14.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(headerTitle, color = Tok.tx, fontSize = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    PulseDot(if (repo.phase.value == ConnPhase.Ready) Tok.ok else Tok.warn, size = 6.dp)
                    Spacer(Modifier.width(6.dp))
                    repo.paired.value?.let {
                        Text(it.displayName(), color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            IconButton({ showNewPath = true }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.Add, stringResource(Res.string.new_path_open), tint = Tok.tx2, modifier = Modifier.size(22.dp))
            }
            ViewToggle(tree) { repo.setTreeView(!tree) }
            Spacer(Modifier.width(4.dp))
            IconButton({ showSettings = true }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Settings, stringResource(Res.string.settings_open), tint = Tok.tx2, modifier = Modifier.size(20.dp))
            }
        }
        OutlinedTextField(
            query, { query = it }, placeholder = { Text(stringResource(Res.string.filter_hint)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        // ── breadcrumb (tree, drilled below root) ──
        if (treeMode && base != root) {
            val segs = remember(base) { crumbs(base) }
            Breadcrumb(
                segs,
                onUp = { repo.browsePath.value = base.substringBeforeLast(sepOf(base)).takeIf { it.length > root.length } },
                onSegment = { i ->
                    repo.browsePath.value = if (i <= 0) null else {
                        val s = sepOf(root)
                        (root + s + segs.drop(1).take(i).joinToString("$s")).takeIf { it.length > root.length }
                    }
                },
            )
        }
        PullToRefreshBox(isRefreshing = repo.refreshing.value, onRefresh = { repo.refreshDirectories() }, modifier = Modifier.fillMaxSize()) {
            when {
                repo.directories.isEmpty() && repo.directoriesLoaded.value && query.isBlank() ->
                    EmptyDirectories { repo.refreshDirectories() }
                !treeMode && flatRows.isEmpty() && repo.directoriesLoaded.value ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(Res.string.dir_no_matches), color = Tok.muted, fontSize = 13.sp)
                    }
                treeMode -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (base == root) { // PINNED + ACTIVE pinned on top at root
                        if (pinned.isNotEmpty()) {
                            item { Label(pinnedLabel) }
                            items(pinned, key = { "p:" + it.path }) { e -> ProjectCell(repo, e, showPath = true, direct = true, onLongPress = { actionTarget = e }) }
                        }
                        if (live.isNotEmpty()) {
                            item { Label(activeLabel) }
                            items(live, key = { "a:" + it.path }) { e -> ProjectCell(repo, e, showPath = true, direct = true, onLongPress = { actionTarget = e }) }
                        }
                        if (pinned.isNotEmpty() || live.isNotEmpty()) item { Label(projectsLabel) }
                    }
                    // drilled into a folder that is itself a project → its own sessions lead as "current project"
                    if (currentLeaf != null) {
                        item { Label(currentProjectLabel) }
                        item(key = "cur:" + currentLeaf.entry.path) {
                            val e = currentLeaf.entry
                            LeafRow(e, pinned = repo.isPinned(e.path), onLongPress = { actionTarget = e }) { repo.openProject(e) }
                        }
                        item { Label(projectsLabel) }
                    }
                    items(childRows, key = { r -> when (r) { is TreeRow.Folder -> "f:" + r.path; is TreeRow.Leaf -> "l:" + r.entry.path } }) { r ->
                        when (r) {
                            is TreeRow.Folder -> {
                                val proj = r.project
                                FolderRow(
                                    name = r.name,
                                    project = proj,
                                    pinned = proj != null && repo.isPinned(proj.path),
                                    onLongPress = proj?.let { e -> { actionTarget = e } },
                                ) { repo.browsePath.value = r.path }
                            }
                            is TreeRow.Leaf -> {
                                val e = r.entry
                                LeafRow(e, pinned = repo.isPinned(e.path), onLongPress = { actionTarget = e }) { repo.openProject(e) }
                            }
                        }
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(flatRows) { row ->
                        when (row) {
                            is DirRow.Header -> Label(row.label)
                            is DirRow.Dir -> ProjectCell(repo, row.entry, showPath = row.showPath, direct = row.direct, onLongPress = { actionTarget = row.entry })
                        }
                    }
                }
            }
        }
    }
        actionTarget?.let { ProjectActionsSheet(repo, it) { actionTarget = null } }
        if (showNewPath) NewPathSheet(
            // drilled into a folder → seed it as the parent so the user types only the new project's name (issue #7)
            parent = base.takeIf { treeMode && base != root },
            onDismiss = { showNewPath = false },
        ) { p -> showNewPath = false; newPathTarget = p }
        // chosen a new path → reuse the standard mode/agent picker, then open the session there
        newPathTarget?.let { path ->
            StartSessionModeSheet(
                workdir = path,
                selected = repo.defaultMode.value,
                agent = repo.defaultAgent.value,
                onPick = { m, a -> newPathTarget = null; repo.setDefaultAgent(a); repo.openSession(path, startMode = m, agent = a) },
                onDismiss = { newPathTarget = null },
            )
        }
    }
}

/** Start a session in a folder that has no prior cc-pocket/claude history by typing its absolute path (issue #7).
 *  The daemon validates the path is a readable directory; a not-yet-created folder can be made first via the
 *  in-chat terminal. On confirm, [onStart] hands the trimmed path to the standard new-session mode picker. */
@Composable
private fun NewPathSheet(parent: String?, onDismiss: () -> Unit, onStart: (String) -> Unit) {
    // drilled into a folder → seed the field with "<folder>/" and park the cursor at the end, so the user types
    // only the new project's leaf name. sepOf() keeps a Windows daemon's "\" paths native (issue #7).
    var field by remember(parent) {
        val seed = parent?.let { it.trimEnd('/', '\\') + sepOf(it) } ?: ""
        mutableStateOf(TextFieldValue(seed, selection = TextRange(seed.length)))
    }
    val trimmed = field.text.trim()
    // drop a trailing separator so we never open a session at "/foo/bar/", but keep a bare root ("/") intact
    val target = trimmed.trimEnd('/', '\\').ifEmpty { trimmed }
    // light client check; the daemon is the authority (rejects a non-readable dir with a clear error)
    val looksAbsolute = trimmed.startsWith("/") || trimmed.startsWith("~") || Regex("^[A-Za-z]:[\\\\/].*").matches(trimmed)
    PocketSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 16.dp, top = 4.dp)) {
            Text(stringResource(Res.string.new_path_title), color = Tok.tx, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(Res.string.new_path_sub), color = Tok.muted, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp))
            OutlinedTextField(
                field, { field = it },
                placeholder = { Text("/Users/me/new-project", fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            SheetButton(
                stringResource(Res.string.new_path_start),
                Modifier.fillMaxWidth().padding(top = 14.dp).alpha(if (looksAbsolute) 1f else 0.4f),
                bg = Tok.accent, fg = Tok.base,
            ) { if (looksAbsolute) onStart(target) }
        }
    }
}

/** Tap a project: jump straight into its live session when one is running, else open its session list. */
private fun PocketRepository.openProject(e: DirectoryEntry) {
    val sid = e.activeSessionId
    if (e.open && sid != null) openSession(e.path, sid, title = e.activeSessionTitle) else listSessions(e.path)
}

/** A project row: jumps into the live session (when [direct] and running) or opens its session list. */
@Composable
private fun ProjectCell(repo: PocketRepository, e: DirectoryEntry, showPath: Boolean, direct: Boolean, onLongPress: (() -> Unit)? = null) {
    val sid = e.activeSessionId
    val pinned = repo.isPinned(e.path)
    if (direct && e.open && sid != null) LiveProjectCell(e, pinned, onLongPress) { repo.openSession(e.path, sid, title = e.activeSessionTitle) }
    else DirCell(e.name.ifBlank { e.path }, if (showPath) tilde(e.path) else null, e.hasSessions, indent = false, pinned = pinned, onLongPress = onLongPress) { repo.listSessions(e.path) }
}

/** Long-press a project → pin it to the top, or unpin it. Small sheet, mirrors the app's other actions. */
@Composable
private fun ProjectActionsSheet(repo: PocketRepository, e: DirectoryEntry, onDismiss: () -> Unit) {
    val pinned = repo.isPinned(e.path)
    PocketSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 14.dp, top = 4.dp)) {
            Text(e.name.ifBlank { e.path }, color = Tok.tx, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TailPathText(e.path, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            Row(
                Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface)
                    .clickable { repo.togglePin(e.path); onDismiss() }.padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, null, tint = Tok.accent, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(if (pinned) Res.string.unpin_project else Res.string.pin_project),
                    color = Tok.tx, fontSize = 14.5.sp, fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/** A small filled pin marking a project that's pinned to the top. */
@Composable
private fun PinGlyph() = Icon(Icons.Filled.PushPin, null, tint = Tok.accent, modifier = Modifier.size(13.dp))

/** The terracotta "history" pill shown on a dir/folder/leaf that has Claude history. */
@Composable
private fun HistoryBadge() {
    Text(
        stringResource(Res.string.history_badge), color = Tok.accent, fontSize = 10.5.sp,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Tok.accent.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Flat ⇄ tree view-mode toggle (top-bar right). Tapping flips the persisted mode. */
@Composable
private fun ViewToggle(tree: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(9.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(9.dp))
            .clickable(onClick = onToggle).padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ViewSeg(on = !tree, icon = Icons.Rounded.Reorder)
        ViewSeg(on = tree, icon = Icons.Rounded.AccountTree)
    }
}

@Composable
private fun ViewSeg(on: Boolean, icon: ImageVector) {
    Box(
        Modifier.size(width = 30.dp, height = 26.dp).clip(RoundedCornerShape(7.dp)).then(if (on) Modifier.background(Tok.accent) else Modifier),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (on) Tok.base else Tok.tx2, modifier = Modifier.size(16.dp)) }
}

/** Path breadcrumb shown when drilled into a subfolder: back ‹ + tappable mono segments (current bolded). */
@Composable
private fun Breadcrumb(segs: List<String>, onUp: () -> Unit, onSegment: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text("‹", color = Tok.tx2, fontSize = 18.sp, modifier = Modifier.clickable(onClick = onUp).padding(end = 2.dp))
        segs.forEachIndexed { i, s ->
            val last = i == segs.lastIndex
            Text(
                s, color = if (last) Tok.tx else Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
                modifier = Modifier.clickable(enabled = !last) { onSegment(i) },
            )
            if (!last) Text("›", color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
}

/**
 * A folder row in the tree — tap drills in. [project] != null means the folder is ALSO a project
 * (history/running hint shown); drilling in surfaces its own sessions as the "current project" row at
 * the top of that level, so it's reachable without the row itself hijacking the drill gesture.
 */
@Composable
private fun FolderRow(
    name: String,
    project: DirectoryEntry?,
    pinned: Boolean,
    onLongPress: (() -> Unit)?,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(horizontal = 4.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Folder, null, tint = Tok.tx2, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(name, color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (pinned) { PinGlyph(); Spacer(Modifier.width(8.dp)) }
        if (project != null) {
            if (project.open || project.busy) {
                PulseDot(Tok.accent, size = 6.dp)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(Res.string.running), color = Tok.accent, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp)
                Spacer(Modifier.width(8.dp))
            } else {
                HistoryBadge()
                Spacer(Modifier.width(8.dp))
            }
        }
        Icon(Icons.Rounded.KeyboardArrowRight, null, tint = Tok.muted, modifier = Modifier.size(18.dp))
    }
}

/** A project-leaf row in the tree — opens its session list (or jumps into the live session). */
@Composable
private fun LeafRow(e: DirectoryEntry, pinned: Boolean, onLongPress: (() -> Unit)?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).combinedClickable(onClick = onClick, onLongClick = onLongPress).padding(horizontal = 4.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⑂", color = Tok.accent, fontSize = 14.sp, modifier = Modifier.padding(end = 9.dp)) // project marker
        Text(e.name.ifBlank { e.path }, color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (pinned) { PinGlyph(); Spacer(Modifier.width(8.dp)) }
        if (e.open || e.busy) {
            PulseDot(Tok.accent, size = 6.dp)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(Res.string.running), color = Tok.accent, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp)
            Spacer(Modifier.width(8.dp))
        }
        e.gitBranch?.let {
            Text(it, color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, maxLines = 1)
            Spacer(Modifier.width(8.dp))
        }
        if (e.hasSessions) HistoryBadge()
    }
}

@Composable
private fun DirCell(name: String, path: String?, hasSessions: Boolean, indent: Boolean, pinned: Boolean = false, onLongPress: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (indent) 16.dp else 0.dp)
            .clip(RoundedCornerShape(10.dp)).background(Tok.surface).combinedClickable(onClick = onClick, onLongClick = onLongPress).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, color = Tok.tx, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1)
            if (path != null) Text(path, color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1)
        }
        if (pinned) { PinGlyph(); Spacer(Modifier.width(8.dp)) }
        if (hasSessions) HistoryBadge()
    }
}

/** A live session row: the session title leads, the folder + branch demote to metadata — tap resumes it. */
@Composable
private fun LiveProjectCell(e: DirectoryEntry, pinned: Boolean, onLongPress: (() -> Unit)?, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Tok.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                e.activeSessionTitle ?: stringResource(Res.string.session_fallback), color = Tok.tx, fontWeight = FontWeight.Medium,
                fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (pinned) { Spacer(Modifier.width(6.dp)); PinGlyph() }
            Spacer(Modifier.width(8.dp))
            val active = e.executing || e.busy // background work counts as "running" even when the turn is idle
            if (active) {
                PulseDot(Tok.accent)
                Spacer(Modifier.width(4.dp))
            }
            Text(
                stringResource(if (active) Res.string.running else Res.string.idle),
                color = if (active) Tok.accent else Tok.muted, fontSize = 11.sp,
            )
        }
        Text(
            buildString { append(e.name); e.gitBranch?.let { append(" · ⑂ ").append(it) } },
            color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun SessionsScreen(repo: PocketRepository) {
    val dir = repo.sessionsDir.value ?: return
    var pickMode by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    if (showSettings) { SettingsScreen(repo, onBack = { showSettings = false }); return } // full-screen, replaces this screen
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().background(Tok.surface).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ repo.backToDirectories() }) { Text("←", color = Tok.tx2, fontSize = 18.sp) }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.sessions_title), color = Tok.tx, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) { // connection bar: live dot + workdir
                        PulseDot(Tok.ok)
                        Spacer(Modifier.width(5.dp))
                        TailPathText(dir)
                    }
                }
                IconButton({ showSettings = true }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Settings, stringResource(Res.string.settings_open), tint = Tok.tx2, modifier = Modifier.size(20.dp))
                }
            }
            LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.accent.copy(alpha = 0.16f))
                            .clickable { pickMode = true }.padding(14.dp),
                    ) {
                        Text(stringResource(Res.string.new_session_cta), color = Tok.accent, fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(Res.string.start_claude_in, tilde(dir)),
                            color = Tok.muted, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                items(repo.sessions) { s ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface)
                            .clickable { repo.openSession(dir, s.sessionId, title = s.title, agent = s.agent ?: AgentKind.CLAUDE) }.padding(14.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.title, color = Tok.tx, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            AgentBadge(s.agent, gap = 8.dp) // shows only for Codex (so resume opens the right backend)
                            if (s.live || s.busy) { // running, or idle with background work still going
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
                }
            }
        }
        if (pickMode) {
            StartSessionModeSheet(
                workdir = dir,
                selected = repo.defaultMode.value,
                agent = repo.defaultAgent.value,
                onPick = { m, a -> pickMode = false; repo.setDefaultAgent(a); repo.openSession(dir, startMode = m, agent = a) },
                onDismiss = { pickMode = false },
            )
        }
    }
}

@Composable
private fun ChatScreen(repo: PocketRepository) {
    // restore the composer draft (keyed per conversation, workdir for a brand-new session); re-inits on switch (#29)
    val draftKey = repo.convoId.value ?: repo.workdir.value
    var input by remember(draftKey) { mutableStateOf(repo.draftFor(draftKey)) }
    var viewer by remember { mutableStateOf<Pair<List<ByteArray>, Int>?>(null) } // tapped sent images → full-screen
    var showModeSheet by remember { mutableStateOf(false) }
    var showSessionInfo by remember { mutableStateOf(false) }
    var showQuickActions by remember { mutableStateOf(false) }
    var showBgJobs by remember { mutableStateOf(false) }
    var showTerminal by remember { mutableStateOf(false) }
    if (showTerminal) { TerminalScreen(repo) { showTerminal = false }; return } // full-screen, replaces chat (issue #3)
    // platform picker resizes/compresses on-device; the repo budgets the picked photos against the 256 KiB frame
    val launchPicker = rememberImageAttacher { added -> repo.attachImages(added) }
    val listState = rememberLazyListState()
    // stick to the bottom only while the user is there ("pinned"); scrolling up unpins and shows
    // the Jump-to-latest pill instead of yanking the viewport down on every streamed chunk.
    var pinned by remember { mutableStateOf(true) }
    // keep the message list hidden until it's first parked at the bottom, so opening a session with
    // history doesn't flash the top then visibly scroll down. Resets per session (convoId); a short
    // grace reveals an empty/new session that has no history to position on.
    var landed by remember(repo.convoId.value) { mutableStateOf(false) }
    LaunchedEffect(repo.convoId.value) { delay(180); landed = true }
    // persist the composer draft per project (debounced) so leaving mid-message doesn't lose it
    LaunchedEffect(input, draftKey) { delay(400); repo.saveDraft(draftKey, input) }
    LaunchedEffect(Unit) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canFwd) -> if (scrolling) pinned = !canFwd }
    }
    // a huge scrollOffset lands at the bottom even when the last message is taller than the viewport
    LaunchedEffect(repo.messages.size, repo.messages.lastOrNull(), repo.streaming.value) {
        if (pinned && repo.messages.isNotEmpty()) { listState.scrollToItem(repo.messages.lastIndex, Int.MAX_VALUE); landed = true }
    }
    // when the keyboard opens/animates, keep the latest message pinned above the input
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottom) {
        if (pinned && imeBottom > 0 && repo.messages.isNotEmpty()) listState.scrollToItem(repo.messages.lastIndex, Int.MAX_VALUE)
    }
    val focus = LocalFocusManager.current
    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) focus.clearFocus() } // scrolling dismisses the keyboard
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().background(Tok.surface).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ repo.saveDraft(repo.workdir.value, input); repo.backToBrowse() }) { Text("←", color = Tok.tx2, fontSize = 18.sp) }
                Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { showSessionInfo = true }.padding(vertical = 2.dp)) {
                    // session title leads (design); the generic "Chat" only before the first prompt names it
                    Text(
                        repo.chatTitle.value ?: stringResource(Res.string.chat_title),
                        color = Tok.tx, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) { // connection bar: live dot + workdir + model
                        PulseDot(Tok.ok)
                        Spacer(Modifier.width(5.dp))
                        TailPathText(repo.workdir.value ?: "", modifier = Modifier.weight(1f)) // tail-truncated: the folder stays visible
                        modelAlias(repo.model.value).takeIf { it.isNotBlank() }?.let {
                            Text(" · $it", color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1)
                        }
                        AgentBadge(repo.sessionAgent.value) // shows only for Codex; Claude stays quiet
                    }
                }
                if (!repo.observing.value) {
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).clickable { showQuickActions = true },
                        contentAlignment = Alignment.Center,
                    ) { Text("⋯", color = Tok.tx2, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(4.dp))
                    ModeBadge(repo.mode.value, repo.allowRules.size) { showModeSheet = true }
                }
            }
            Box(Modifier.weight(1f)) {
                LazyColumn(
                    Modifier.fillMaxSize().padding(16.dp).graphicsLayer { alpha = if (landed) 1f else 0f }
                        .pointerInput(Unit) { detectTapGestures { focus.clearFocus() } },
                    state = listState, verticalArrangement = Arrangement.spacedBy(10.dp),
                    // reserve a gutter below the last message so the floating context pill sits in
                    // empty space (with a gap) instead of covering the last copy button (issue #15)
                    contentPadding = PaddingValues(bottom = 30.dp),
                ) {
                    items(repo.messages) { m -> MessageItem(m) { imgs, i -> viewer = imgs to i } }
                    // a turn is running but nothing live is on screen yet — e.g. just after sending, or
                    // after re-entering a mid-turn session (the streamed Thinking row isn't in the replayed
                    // transcript). A live Thinking/Assistant row already shows progress, so don't double up.
                    val last = repo.messages.lastOrNull()
                    val liveContent = (last is ChatItem.Thinking && last.seconds == null) || last is ChatItem.Assistant
                    if (repo.streaming.value && !liveContent) item { WorkingRow() }
                }
                if (!pinned) {
                    val pillScope = rememberCoroutineScope()
                    JumpToLatestPill(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)) {
                        pinned = true
                        pillScope.launch {
                            if (repo.messages.isNotEmpty()) listState.animateScrollToItem(repo.messages.lastIndex, Int.MAX_VALUE)
                        }
                    }
                }
                // context usage floats over the message tail's bottom-right — no layout footprint (issue #15)
                ContextStatusline(
                    repo.contextUsed.value, repo.contextWindow.value,
                    Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 12.dp),
                )
            }
            if (repo.observing.value) {
                Row(Modifier.fillMaxWidth().background(Tok.surface).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.observing_notice), color = Tok.tx2, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Button({ repo.takeOver() }) { Text(stringResource(Res.string.continue_here)) }
                }
            } else {
                val hasReady = repo.hasReadyImages()
                val voiceState = repo.voice.value
                // the timer stays visible (frozen) through S3, after Recording stopped carrying it
                var recElapsed by remember { mutableStateOf(0L) }
                if (voiceState is VoiceState.Recording) recElapsed = voiceState.elapsedMs
                // "/" autocomplete: live while the user is still typing the command word (no space yet)
                val slashQuery = input.takeIf { it.startsWith("/") && ' ' !in it && '\n' !in it }?.drop(1)
                val suggestions = remember(slashQuery, repo.slashCommands.toList()) {
                    if (slashQuery == null) emptyList()
                    else repo.slashCommands
                        .filter { it.name.contains(slashQuery, ignoreCase = true) }
                        .sortedBy { !it.name.startsWith(slashQuery, ignoreCase = true) } // prefix matches first
                }
                Column(Modifier.fillMaxWidth().background(Tok.surface)) {
                    BackgroundJobsStrip(repo.backgroundJobs) { showBgJobs = true } // ≥1 running bg task → tap to expand
                    val capturing = voiceState is VoiceState.Recording || voiceState is VoiceState.Transcribing
                    if (suggestions.isNotEmpty() && !capturing) {
                        SlashCommandMenu(suggestions) { cmd ->
                            input = "/${cmd.name}" + if (cmd.argumentHint != null) " " else ""
                        }
                    }
                    AttachTray(repo.pendingImages, repo::removePendingImage)
                    repo.voiceNotice.value?.let { n ->
                        Text(stringResource(n), color = Tok.tx2, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                    }
                    if (capturing) {
                        if (repo.liveDictation.value && voiceState is VoiceState.Recording) {
                            LiveTranscriptField(repo.liveFinal.value, repo.livePartial.value)
                        }
                        RecordingBar(
                            elapsedMs = recElapsed,
                            transcribing = voiceState is VoiceState.Transcribing,
                            levels = repo.voiceLevels,
                            onCancel = repo::cancelVoice,
                            onDone = repo::stopVoice,
                        )
                    } else {
                        val failed = voiceState as? VoiceState.Failed
                        if (failed != null) VoiceErrorChip(failed.detail ?: stringResource(failed.res))
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom) {
                            val attachInteraction = remember { MutableInteractionSource() }
                            val attachPressed by attachInteraction.collectIsPressedAsState()
                            IconButton(onClick = { launchPicker() }, interactionSource = attachInteraction, modifier = Modifier.size(44.dp)) {
                                Icon(
                                    AttachImageIcon,
                                    contentDescription = stringResource(Res.string.attach_image),
                                    tint = if (repo.pendingImages.isNotEmpty() || attachPressed) Tok.accent else Tok.tx2,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            ComposerField(
                                input, { input = it },
                                placeholder = stringResource(if (repo.pendingImages.isNotEmpty()) Res.string.add_message_hint else Res.string.message_claude_hint),
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            when {
                                // generating -> the action slot morphs into Stop (interrupts the turn, session stays)
                                repo.streaming.value -> {
                                    val stopLabel = stringResource(Res.string.stop)
                                    RoundActionButton(
                                        onClick = { repo.cancelTurn() },
                                        filled = false, contentDescription = stopLabel,
                                    ) { Box(Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(Tok.accent)) }
                                }
                                input.isNotBlank() || hasReady -> {
                                    val sendLabel = stringResource(Res.string.send)
                                    RoundActionButton(
                                        onClick = {
                                            val t = input.trim()
                                            if (t.isNotBlank() || hasReady) { repo.sendPrompt(t); input = ""; repo.clearDraft(draftKey) }
                                        },
                                        filled = true, contentDescription = sendLabel,
                                    ) { Icon(SendArrowIcon, sendLabel, tint = Tok.base, modifier = Modifier.size(18.dp)) }
                                }
                                else -> {
                                    val dictateLabel = stringResource(Res.string.dictate)
                                    RoundActionButton(
                                        onClick = { if (failed != null) repo.retryVoice() else repo.startVoice() },
                                        filled = false, contentDescription = dictateLabel,
                                    ) { Icon(MicIcon, dictateLabel, tint = if (failed != null) Tok.accent else Tok.tx2, modifier = Modifier.size(22.dp)) }
                                }
                            }
                        }
                    }
                }
            }
        }
        viewer?.let { (imgs, idx) -> ImageViewer(imgs, idx) { viewer = null } }
        if (repo.micPermissionSheet.value) {
            MicPermissionSheet(
                onOpenSettings = { openAppSettings(); repo.dismissMicSheet() },
                onDismiss = repo::dismissMicSheet,
            )
        }
        if (showModeSheet) {
            ModeSheet(
                current = repo.mode.value, rules = repo.allowRules, switching = repo.switching.value, workdir = repo.workdir.value,
                onSelect = { repo.switchMode(it) }, // keep the sheet open so the "switching" state shows
                onClearRule = { repo.clearRule(it) }, onClearAll = { repo.clearAllRules() },
                onDismiss = { showModeSheet = false },
            )
        }
        if (showSessionInfo) SessionInfoSheet(repo) { showSessionInfo = false }
        if (showQuickActions) QuickActionsSheet(repo, onTerminal = { showTerminal = true }) { showQuickActions = false }
        if (showBgJobs) BackgroundJobsSheet(repo.backgroundJobs) { showBgJobs = false }
    }
}

/** The "/" autocomplete panel above the composer: tap a row to fill the input with the command. */
@Composable
private fun SlashCommandMenu(commands: List<SlashCommand>, onPick: (SlashCommand) -> Unit) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp).background(Tok.raised).padding(vertical = 4.dp)) {
        items(commands) { cmd ->
            Column(Modifier.fillMaxWidth().clickable { onPick(cmd) }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "/${cmd.name}", color = Tok.accent, fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    )
                    cmd.argumentHint?.let {
                        Text(" $it", color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        stringResource(
                            when (cmd.source) {
                                CommandSource.BUILTIN -> Res.string.cmd_source_builtin
                                CommandSource.USER -> Res.string.cmd_source_user
                                CommandSource.PROJECT -> Res.string.cmd_source_project
                                CommandSource.SKILL -> Res.string.cmd_source_skill
                            },
                        ),
                        color = Tok.muted, fontSize = 10.sp,
                    )
                }
                if (cmd.description.isNotBlank()) {
                    Text(cmd.description, color = Tok.tx2, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun MessageItem(m: ChatItem, onOpenImages: (List<ByteArray>, Int) -> Unit = { _, _ -> }) {
    when (m) {
        // accent-rail user turn (design: User Turn Styles.html, direction B) — the terracotta
        // rail + warm tint mark "what I said" as a quote; no label, assistant flow untouched
        is ChatItem.User -> Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 10.dp, bottomEnd = 10.dp, bottomStart = 4.dp))
                .background(Tok.accent.copy(alpha = 0.05f)),
        ) {
            Box(Modifier.fillMaxHeight().width(2.dp).clip(RoundedCornerShape(2.dp)).background(Tok.accent.copy(alpha = 0.6f)))
            Column(
                Modifier.weight(1f).padding(start = 12.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (m.images.isNotEmpty()) SentImages(m.images) { i -> onOpenImages(m.images, i) }
                if (m.text.isNotBlank()) {
                    SelectionContainer { Text(m.text, color = Tok.tx, fontSize = 14.sp * LocalFontScale.current) } // drag-select to copy (no native toolbar on iOS)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        CopyChip(m.text) // one-tap copy — the reliable path on iOS where select-to-copy has no menu (issue #5)
                    }
                }
            }
        }
        is ChatItem.Assistant -> Column {
            SelectionContainer { MarkdownText(m.text, Tok.tx) } // drag-select any span to copy
            if (m.text.isNotBlank()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                CopyChip(m.text) // one-tap copy of the whole turn
            }
        }
        is ChatItem.Thinking -> ThinkingRow(m)
        is ChatItem.Tool -> {
            val isPlan = m.tool == "ExitPlanMode" || m.tool == "exit_plan_mode"
            var expanded by remember(m) { mutableStateOf(isPlan) } // plans read open by default (issue #10)
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Tok.raised)
                    .clickable { expanded = !expanded }.padding(8.dp),
            ) {
                Text(if (isPlan) "⚙ Plan" else "⚙ ${m.tool}", color = Tok.accent, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                if (m.preview.isNotBlank()) {
                    if (isPlan && expanded) Box(Modifier.padding(top = 4.dp)) { MarkdownText(m.preview, Tok.tx2) } // plan rendered as markdown
                    else Text(
                        m.preview, color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        maxLines = if (expanded) Int.MAX_VALUE else 1,
                        overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        is ChatItem.Sys -> Text(stringResource(Res.string.error_prefix, m.text), color = Tok.danger, fontSize = 12.sp)
        is ChatItem.RuleChip -> AllowChip(m.rule)
    }
}

@Composable
private fun Label(text: String) =
    Text(text, color = Tok.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))

/**
 * "Thinking…" activity row: a pulsing dot + label shown while a turn is running but nothing live is on
 * screen yet (just-sent, or re-entered mid-turn where the streamed reasoning wasn't in the transcript).
 */
@Composable
private fun WorkingRow() {
    Row(
        Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PulseDot(Tok.muted)
        Text(stringResource(Res.string.thinking_streaming), color = Tok.muted, fontSize = 12.5.sp, fontStyle = FontStyle.Italic)
    }
}

/**
 * The usage indicator (issue #15): a light "Context NN%" that FLOATS over the bottom-right of the
 * message list, so it costs no layout height and never pushes the composer. How full the model's
 * window is after the last turn — seeded on resume from the daemon's transcript snapshot, refreshed
 * each turn from TurnDone; hidden until there's a number. A faint raised pill keeps it legible over
 * content; rests at muted, escalating at the ContextBar thresholds (warn ≥ 80%, danger ≥ 95%).
 */
@Composable
private fun ContextStatusline(used: Long?, window: Long?, modifier: Modifier = Modifier) {
    used ?: return // no turn yet / older daemon — nothing to show
    val cap = window ?: DEFAULT_CONTEXT_WINDOW
    val frac = (used.toFloat() / cap).coerceIn(0f, 1f)
    Text(
        "${stringResource(Res.string.label_context)} ${(frac * 100).toInt()}%",
        color = contextColor(frac, Tok.muted),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Tok.raised.copy(alpha = 0.85f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Extended reasoning, collapsed to one italic line; expands to the full text behind a hairline rule. */
@Composable
private fun ThinkingRow(m: ChatItem.Thinking) {
    var expanded by remember(m.seconds == null) { mutableStateOf(false) }
    Column {
        Row(
            Modifier.clip(RoundedCornerShape(6.dp)).clickable { expanded = !expanded }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (expanded) "▾ " else "▸ ", color = Tok.muted, fontSize = 11.sp)
            Text(
                m.seconds?.let { stringResource(Res.string.thought_for, it) } ?: stringResource(Res.string.thinking_streaming),
                color = Tok.muted, fontSize = 12.5.sp, fontStyle = FontStyle.Italic,
            )
        }
        if (expanded && m.text.isNotBlank()) {
            Row(Modifier.height(IntrinsicSize.Min).padding(start = 5.dp, top = 2.dp)) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(Tok.hair))
                Text(
                    m.text, color = Tok.muted, fontSize = 12.5.sp, fontStyle = FontStyle.Italic, lineHeight = 18.sp,
                    modifier = Modifier.padding(start = 13.dp),
                )
            }
        }
    }
}

/** The design's connection/live indicator: a softly pulsing dot (scale 1→0.82, alpha 1→0.45, ~1.25s). */
@Composable
internal fun PulseDot(color: Color, size: Dp = 6.dp) {
    val pulse by rememberInfiniteTransition().animateFloat(
        initialValue = 1f, targetValue = 0.45f,
        animationSpec = infiniteRepeatable(tween(625), RepeatMode.Reverse),
    )
    Box(
        Modifier.size(size)
            .graphicsLayer { alpha = pulse; scaleX = 0.82f + 0.18f * pulse; scaleY = 0.82f + 0.18f * pulse }
            .clip(CircleShape).background(color),
    )
}

/** Floating pill over the message list when the user has scrolled away from the bottom. */
@Composable
private fun JumpToLatestPill(modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier.shadow(6.dp, shape).clip(shape).background(Tok.raised).border(1.dp, Tok.hair, shape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Tok.tx2, modifier = Modifier.size(14.dp))
        Text(stringResource(Res.string.jump_to_latest), color = Tok.tx2, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
    }
}

/** "2h ago"-style relative time; tolerates daemon timestamps in seconds or millis. */
@Composable
internal fun relativeTime(epoch: Long): String {
    val ms = if (epoch < 1_000_000_000_000L) epoch * 1000 else epoch
    val min = ((dev.ccpocket.app.epochMillis() - ms).coerceAtLeast(0)) / 60_000
    return when {
        min < 1 -> stringResource(Res.string.time_just_now)
        min < 60 -> stringResource(Res.string.time_minutes_ago, min)
        min < 24 * 60 -> stringResource(Res.string.time_hours_ago, min / 60)
        min < 48 * 60 -> stringResource(Res.string.time_yesterday)
        else -> stringResource(Res.string.time_days_ago, min / (24 * 60))
    }
}

/** Resolve a repo [StatusMsg] into display text — substitutes the optional %1$s detail argument. */
@Composable
internal fun StatusMsg.resolve(): String = arg?.let { stringResource(res, it) } ?: stringResource(res)
