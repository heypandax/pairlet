@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import dev.ccpocket.app.ui.fleet.crossMachineAttention
import dev.ccpocket.app.ui.fleet.fleetAttention
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
import dev.ccpocket.protocol.isQuestion
import dev.ccpocket.protocol.isSubagentTool
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SlashCommand
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.compose.resources.stringResource

@Composable
fun App(scope: CoroutineScope) {
    val repo = remember { PocketRepository(scope) }
    // one live link per paired computer: the primary repo keeps its exact semantics; the coordinator
    // maintains pinned satellites for the other bindings so the whole fleet is live at once
    remember { dev.ccpocket.app.data.FleetCoordinator(scope, repo).also { dev.ccpocket.app.data.FleetRuntime.coordinator = it; it.start() } }
    // fleet surfaces (machine-first triage) overlay the content stack from anywhere: header machine
    // name → Fleet home; attention banner / cross-machine banner → inbox. UI-local like the sheets.
    var fleetOpen by remember { mutableStateOf(false) }
    var inboxOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        dev.ccpocket.app.telemetry.Telemetry.track(dev.ccpocket.app.telemetry.TelEvent.AppLaunch)
        if (repo.paired.value != null) repo.startRelay() // already paired -> straight to the list
    }
    val pendingLink by dev.ccpocket.app.DeepLink.pending.collectAsState()
    LaunchedEffect(pendingLink) { pendingLink?.let { repo.handlePairUrl(it); dev.ccpocket.app.DeepLink.pending.value = null } }
    // a tapped task-complete push deep-links straight into its session (connecting first if needed)
    val pushOpen by dev.ccpocket.app.PushRoute.pending.collectAsState()
    LaunchedEffect(pushOpen) { pushOpen?.let { repo.requestOpenSession(it.workdir, it.sessionId); dev.ccpocket.app.PushRoute.pending.value = null } }
    dev.ccpocket.app.OnAppForeground { // iOS kills sockets in background — reconnect the whole fleet on return
        repo.onAppForeground()
        dev.ccpocket.app.data.FleetRuntime.coordinator?.onAppForeground()
    }
    // Android system back walks the in-app stack (chat → sessions → directories) instead of leaving
    // the app; at the root it stays disabled so the system default (exit) applies. An open sheet
    // registers its own handler later in composition, which wins while it is showing (LIFO).
    dev.ccpocket.app.SystemBackHandler(
        enabled = repo.sessionActive.value && (repo.convoId.value != null || repo.sessionsDir.value != null),
    ) {
        if (repo.convoId.value != null) repo.backToBrowse() else repo.backToDirectories()
    }
    // registered after the content handler so it wins (LIFO) while a fleet overlay is up
    dev.ccpocket.app.SystemBackHandler(enabled = fleetOpen || inboxOpen) {
        if (inboxOpen) inboxOpen = false else fleetOpen = false
    }
    // appearance (issue #63): PocketTheme resolves the persisted mode against the OS, so a SYSTEM pick tracks a
    // live system flip while the app is foregrounded and LIGHT/DARK force it.
    PocketTheme(mode = repo.themeMode.value, fontScale = repo.fontScale.value) {
        Surface(Modifier.fillMaxSize(), color = Tok.base) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding()) {
                // pushes content down instead of overlaying the header; steady while retrying (no flicker)
                // preview/recording mode hides the demo banner for a clean marketing capture
                if (repo.demoMode.value && !dev.ccpocket.app.isPreviewMode()) StatusBanner(Tok.accent, stringResource(Res.string.demo_banner))
                if (repo.sessionActive.value && repo.phase.value == ConnPhase.Reconnecting) StatusBanner(Tok.danger, stringResource(Res.string.reconnect_banner))
                // entering a chat before the first link-up used to look identical to "connected" — say so (issue #41)
                if (repo.sessionActive.value && repo.phase.value == ConnPhase.Connecting && repo.convoId.value != null) StatusBanner(Tok.warn, stringResource(Res.string.conn_connecting_banner))
                if (repo.openTimedOut.value) {
                    StatusBanner(Tok.warn, stringResource(Res.string.open_session_timeout))
                    LaunchedEffect(Unit) { delay(6000); repo.openTimedOut.value = false } // transient; leaves composition → effect cancels
                }
                Box(Modifier.weight(1f)) {
                    when {
                        // a dead transport does NOT leave the content screens — ConnectionGate + auto-retry handle it
                        !repo.sessionActive.value ->
                            if (repo.addingDevice.value || repo.pairedList.isEmpty()) PairingScreen(repo) else ConnectScreen(repo)
                        repo.demoConnecting.value -> DemoConnectScreen { repo.finishDemoConnect() } // PREVIEW opener
                        else -> Box(Modifier.fillMaxSize()) {
                            ConnectionGate(repo) {
                                when {
                                    repo.convoId.value != null -> ChatScreen(repo, onOpenFleet = { fleetOpen = true }, onOpenInbox = { inboxOpen = true })
                                    repo.sessionsDir.value != null -> SessionsScreen(repo)
                                    else -> DirectoryScreen(repo, onOpenFleet = { fleetOpen = true })
                                }
                            }
                            // fleet overlays ride ABOVE the gate: the fleet view is exactly where you
                            // want to be while this machine is reconnecting or another one has news
                            if (fleetOpen) dev.ccpocket.app.ui.fleet.FleetHomeScreen(repo, onBack = { fleetOpen = false }, onOpenInbox = { inboxOpen = true })
                            if (inboxOpen) dev.ccpocket.app.ui.fleet.AttentionInboxScreen(repo) { inboxOpen = false }
                        }
                    }
                }
            }
            // a permission decision never needs typing — drop the keyboard so the sheet isn't cramped
            val rootFocus = LocalFocusManager.current
            LaunchedEffect(repo.pendingAsk.value?.askId) {
                if (repo.pendingAsk.value != null) rootFocus.clearFocus()
            }
            // AskUserQuestion (ask.questions != null) renders as the docked QuestionCard inside
            // ChatScreen instead — questions are conversation, not a safety gate, and the user
            // should be able to scroll the chat for context while answering.
            repo.pendingAsk.value?.takeIf { !it.isQuestion }?.let { ask ->
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
            if (repo.directoriesLoaded.value || repo.convoId.value != null) content() else DirectorySkeleton(repo)
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

/** The Projects top bar (title + status dot + monospace machine line, then [actions]) — ONE implementation
 *  shared by [DirectoryScreen] and its connect/switch skeleton, so the skeleton→list swap can never drift. */
@Composable
private fun ProjectsTopBar(
    title: String,
    dotColor: Color,
    machine: String?,
    machineLineModifier: Modifier = Modifier,
    machineTrailing: @Composable RowScope.() -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().background(Tok.surface).padding(start = 16.dp, end = 6.dp, top = 14.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Tok.tx, fontSize = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp).then(machineLineModifier)) {
                PulseDot(dotColor, size = 6.dp)
                Spacer(Modifier.width(6.dp))
                machine?.let {
                    Text(it, color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                machineTrailing()
            }
        }
        actions()
    }
}

/** Connect/switch placeholder: the REAL Projects header (amber dot while the link comes up) over
 *  shimmering rows — so landing on a machine only swaps skeleton→list and amber→green, instead of
 *  flashing a differently-shaped "Choose a directory" screen first. */
@Composable
private fun DirectorySkeleton(repo: PocketRepository) {
    val shimmer by rememberInfiniteTransition().animateFloat(
        initialValue = 0.25f, targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
    )
    Column(Modifier.fillMaxSize()) {
        ProjectsTopBar(stringResource(Res.string.dir_projects), Tok.warn, repo.paired.value?.displayName())
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
private fun DirectoryScreen(repo: PocketRepository, onOpenFleet: () -> Unit = {}) {
    var query by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    if (showSettings) { SettingsScreen(repo, onBack = { showSettings = false }); return } // full-screen, replaces this screen
    // pull-only list: refresh NOW — entering (and RE-entering, back from a session) shows fresh state
    // instead of the pre-session snapshot — then keep re-pulling quietly
    LaunchedEffect(Unit) { while (true) { repo.refreshDirectoriesSilently(); delay(10_000) } }

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
    // at the root, also surface projects OUTSIDE it (other drives / off-home) as plain leaves
    val treeRows = remember(dirsSnapshot, base, root) { buildTree(repo.directories, base, includeOrphans = base == root) }
    // when drilled into a folder that is itself a project, buildTree leads with its own leaf — split it out
    // as the "current project" row (the rest are its subfolders). Computed once here, not per recomposition.
    val currentLeaf = remember(treeRows, base, root) {
        (treeRows.firstOrNull() as? TreeRow.Leaf)?.takeIf { base != root && it.entry.path == base }
    }
    val childRows = remember(treeRows, currentLeaf) { if (currentLeaf != null) treeRows.drop(1) else treeRows }
    val live = remember(dirsSnapshot) { dirsSnapshot.filter { it.open || it.busy }.flatMap(::expandLiveSessions) } // ACTIVE: one row per live session
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
        ProjectsTopBar(
            headerTitle,
            if (repo.phase.value == ConnPhase.Ready) Tok.ok else Tok.warn,
            repo.paired.value?.displayName(),
            // the machine line is the doorway into the fleet: tap → Your computers (live overview)
            machineLineModifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onOpenFleet).padding(vertical = 2.dp),
            machineTrailing = {
                val waiting = repo.fleetAttention().size
                if (repo.pairedList.size > 1 || waiting > 0) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Rounded.KeyboardArrowRight, null, tint = Tok.muted, modifier = Modifier.size(13.dp))
                }
                if (waiting > 0) {
                    Spacer(Modifier.width(4.dp))
                    dev.ccpocket.app.ui.fleet.AttentionBadge(waiting)
                }
            },
        ) {
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
        // discoverable entry to open ANY folder by absolute path — the browser only shows folders that already
        // have history, and the top-bar "+" reads as "new", so this spells out how to reach a fresh folder (#32)
        Row(
            Modifier.fillMaxWidth().clickable { showNewPath = true }.padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Add, null, tint = Tok.accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.new_path_open_row), color = Tok.tx2, fontSize = 13.sp)
        }
        // ── breadcrumb (tree, drilled below root) ──
        if (treeMode && base != root) {
            // labels + real drill targets anchored at root — a reconstruction from display labels broke
            // whenever root was deeper than one segment (common-prefix roots like /opt/x or C:\dev)
            val segs = remember(base, root) { crumbTargets(base, root) }
            Breadcrumb(
                segs.map { it.first },
                onUp = { repo.browsePath.value = segs.getOrNull(segs.size - 2)?.second?.takeIf { it != root } },
                onSegment = { i -> repo.browsePath.value = segs.getOrNull(i)?.second?.takeIf { it != root } },
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
                            // key carries the session too — expansion can put the same project here several times
                            items(live, key = { "a:" + it.path + ":" + (it.activeSessionId ?: "") }) { e -> ProjectCell(repo, e, showPath = true, direct = true, onLongPress = { actionTarget = e }) }
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
            parent = base.takeIf { it.length > 1 }, // seed the current location (root prefix or a drilled folder) so "type the rest of the path" is obvious (#32/#7)
            agent = repo.defaultAgent.value,
            mode = repo.defaultMode.value,
            onDismiss = { showNewPath = false },
            onOptions = { p -> showNewPath = false; newPathTarget = p },
        ) { p -> showNewPath = false; repo.openSession(p) } // one tap: start with the defaults right away
        // wants a different agent/mode for the new path → the standard picker, then open the session there
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
 *  in-chat terminal. [onStart] opens the session immediately with the default agent/mode (shown on the chip);
 *  [onOptions] routes the path through the full new-session picker instead. */
@Composable
private fun NewPathSheet(
    parent: String?, agent: AgentKind, mode: PermissionMode,
    onDismiss: () -> Unit, onOptions: (String) -> Unit, onStart: (String) -> Unit,
) {
    // drilled into a folder → seed the field with "<folder>/" and park the cursor at the end, so the user types
    // only the new project's leaf name. sepOf() keeps a Windows daemon's "\" paths native (issue #7).
    var field by remember(parent) {
        val seed = parent?.let { it.trimEnd('/', '\\') + sepOf(it) } ?: ""
        mutableStateOf(TextFieldValue(seed, selection = TextRange(seed.length)))
    }
    val trimmed = field.text.trim()
    // drop a trailing separator so we never open a session at "/foo/bar/", but keep a bare root ("/") intact
    val target = trimTrailingSep(trimmed)
    val looksAbsolute = looksAbsolutePath(trimmed)
    PocketSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 16.dp, top = 4.dp)) {
            Text(stringResource(Res.string.new_path_title), color = Tok.tx, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(Res.string.new_path_sub), color = Tok.muted, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp))
            OutlinedTextField(
                field, { field = it },
                placeholder = { Text("/Users/me/new-project", fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (looksAbsolute) onStart(target) }),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp).alpha(if (looksAbsolute) 1f else 0.4f),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // default agent+mode preview; tap → the full picker for this path (52dp to pair with the button)
                SessionDefaultsChip(agent, mode, Modifier.height(52.dp), enabled = looksAbsolute) { onOptions(target) }
                SheetButton(
                    stringResource(Res.string.new_path_start),
                    Modifier.weight(1f),
                    bg = Tok.accent, fg = Tok.base,
                ) { if (looksAbsolute) onStart(target) }
            }
        }
    }
}

/** "/" autocomplete: the query while the user is still typing the command word (no space yet), else null. */
internal fun slashQueryOf(input: String): String? =
    input.takeIf { it.startsWith("/") && ' ' !in it && '\n' !in it }?.drop(1)

/** What picking a command puts in the composer. Trailing space always: it closes the menu
 *  ([slashQueryOf] bails on a space) and leaves the cursor ready for arguments; send trims it off
 *  a bare command. Shared by the mobile composer and desktop ChatPane. */
internal fun SlashCommand.completion(): String = "/$name "

/** Matching commands for [query], prefix matches first — shared by the mobile composer and desktop ChatPane. */
internal fun slashSuggestions(query: String?, commands: List<SlashCommand>): List<SlashCommand> =
    if (query == null) emptyList()
    else commands.filter { it.name.contains(query, ignoreCase = true) }
        .sortedBy { !it.name.startsWith(query, ignoreCase = true) }

/** Glued-to-newest tracker for a transcript list: a user scroll away unpins, scrolling back to the very
 *  bottom re-pins. Shared by the mobile ChatScreen and desktop ChatPane — the unpin heuristic is subtle
 *  and has been tuned before; keep it in one place.
 *
 *  [userGesturesOnly] (touch platforms): only a real drag (and its fling tail) may change the pin.
 *  Programmatic follows — the ime-follow and stream-follow snaps — briefly sample as "scrolling & not
 *  at bottom" while the keyboard resizes the viewport, which used to permanently unpin after the first
 *  keyboard open. Desktop passes false: mouse-wheel scrolls emit no DragInteraction, and with no ime
 *  there is no corrupting resize in the first place. */
@Composable
internal fun rememberBottomPinned(
    listState: LazyListState,
    vararg resetKeys: Any?,
    userGesturesOnly: Boolean = true,
): MutableState<Boolean> {
    val pinned = remember(*resetKeys) { mutableStateOf(true) }
    // keyed on the pinned INSTANCE too: resetKeys mint a fresh state, and a collector keyed only on
    // the list would keep writing the previous one (desktop resets per selected session)
    LaunchedEffect(listState, userGesturesOnly, pinned) {
        var userDriven = !userGesturesOnly
        if (userGesturesOnly) launch {
            listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) userDriven = true }
        }
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canFwd) ->
                if (scrolling && userDriven) pinned.value = !canFwd
                if (!scrolling && userGesturesOnly) userDriven = false // gesture + fling fully settled
            }
    }
    return pinned
}

/** Leaf recomposition scope for the keyboard-follow: reads the ime inset in composition (required on
 *  iOS) so the per-frame invalidation during the keyboard animation stays inside this empty leaf
 *  instead of re-executing the whole ChatScreen. ONE collector scrolls (no restart per frame). */
@Composable
private fun ImeFollower(listState: LazyListState, repo: PocketRepository, pinned: () -> Boolean) {
    val imeBottom = rememberUpdatedState(WindowInsets.ime.getBottom(LocalDensity.current))
    LaunchedEffect(listState) {
        snapshotFlow { imeBottom.value }.collect { bottom ->
            if (pinned() && bottom > 0 && repo.messages.isNotEmpty()) listState.scrollToItem(repo.messages.lastIndex, Int.MAX_VALUE)
        }
    }
}

/** Tap a project: jump straight into its live session when one is running, else open its session list.
 *  The resume pins the session's OWN backend (liveAgent) — the default-agent preference must not decide
 *  how someone else's live session is re-opened. */
private fun PocketRepository.openProject(e: DirectoryEntry) {
    val sid = e.activeSessionId
    if (e.open && sid != null) openSession(e.path, sid, title = e.activeSessionTitle, agent = liveAgent(e)) else listSessions(e.path)
}

/** A project row: jumps into the live session (when [direct] and running) or opens its session list. */
@Composable
private fun ProjectCell(repo: PocketRepository, e: DirectoryEntry, showPath: Boolean, direct: Boolean, onLongPress: (() -> Unit)? = null) {
    val sid = e.activeSessionId
    val pinned = repo.isPinned(e.path)
    if (direct && e.open && sid != null) {
        // the 历史 badge lists this project's sessions (issue #49) — the row itself keeps auto-resuming
        LiveProjectCell(e, pinned, onLongPress, onBrowse = { repo.listSessions(e.path) }) { repo.openProject(e) }
    }
    else DirCell(e.name.ifBlank { e.path }, if (showPath) tilde(e.path) else null, indent = false, pinned = pinned, onLongPress = onLongPress) { repo.listSessions(e.path) }
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
private fun HistoryBadge(onClick: (() -> Unit)? = null) {
    val base = Modifier.clip(RoundedCornerShape(999.dp)).background(Tok.accent.copy(alpha = 0.14f))
    Text(
        stringResource(Res.string.history_badge), color = Tok.accent, fontSize = 10.5.sp,
        modifier = (if (onClick != null) base.clickable(onClick = onClick) else base).padding(horizontal = 8.dp, vertical = 3.dp),
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
private fun DirCell(name: String, path: String?, indent: Boolean, pinned: Boolean = false, onLongPress: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (indent) 16.dp else 0.dp)
            .clip(RoundedCornerShape(10.dp)).background(Tok.surface).combinedClickable(onClick = onClick, onLongClick = onLongPress).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, color = Tok.tx, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1)
            if (path != null) Text(path, color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1)
        }
        if (pinned) PinGlyph()
    }
}

/** A live session row: the session title leads, the folder + branch demote to metadata — tap resumes it.
 *  [onBrowse] is the secondary affordance (issue #49): open the project's session LIST instead of the
 *  live session, so a running project's history stays reachable — the row tap only ever auto-resumes. */
@Composable
private fun LiveProjectCell(e: DirectoryEntry, pinned: Boolean, onLongPress: (() -> Unit)?, onBrowse: (() -> Unit)? = null, onClick: () -> Unit) {
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
            if (onBrowse != null && e.hasSessions) { Spacer(Modifier.width(8.dp)); HistoryBadge(onClick = onBrowse) }
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

/** Removable filter chip pinned atop the Sessions list when a single agent is selected (issue #31). */
@Composable
private fun AgentFilterChip(filter: String, onClear: () -> Unit) {
    val color = if (filter == "codex") Tok.codex else Tok.accent
    val label = stringResource(if (filter == "codex") Res.string.af_codex_only else Res.string.af_claude_only)
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(999.dp))
            .clickable(onClick = onClear).padding(start = 11.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(androidx.compose.foundation.shape.CircleShape).background(color))
        Spacer(Modifier.width(7.dp))
        Text(label, color = color, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(6.dp))
        Text("✕", color = color, fontSize = 12.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class) // PullToRefreshBox
@Composable
internal fun SessionsScreen(repo: PocketRepository) { // internal: driven end-to-end by MobileNewSessionUiTest (demo mode)
    val dir = repo.sessionsDir.value ?: return
    var pickMode by remember { mutableStateOf(false) }
    // an open is in flight (the screen only switches once the daemon answers with the live convo).
    // Repo-owned so every entry point is guarded: entries disable — a double-tap can't open two fresh
    // sessions — and the repo clears it on SessionLive/PocketError (8s safety net).
    val starting = repo.opening.value
    var showSettings by remember { mutableStateOf(false) }
    if (showSettings) { SettingsScreen(repo, onBack = { showSettings = false }); return } // full-screen, replaces this screen
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().background(Tok.surface).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ repo.backToDirectories() }) { Text("←", color = Tok.tx2, fontSize = 18.sp) }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.sessions_title), color = Tok.tx, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) { // connection bar: honest dot (green only when Ready) + workdir
                        PulseDot(if (repo.phase.value == ConnPhase.Ready) Tok.ok else Tok.warn)
                        Spacer(Modifier.width(5.dp))
                        TailPathText(dir)
                    }
                }
                IconButton({ showSettings = true }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Settings, stringResource(Res.string.settings_open), tint = Tok.tx2, modifier = Modifier.size(20.dp))
                }
            }
            val af = repo.agentFilter.value
            val filtered = repo.sessions.filter {
                when (af) {
                    "claude" -> (it.agent ?: AgentKind.CLAUDE) == AgentKind.CLAUDE
                    "codex" -> it.agent == AgentKind.CODEX
                    else -> true
                }
            }
            PullToRefreshBox(isRefreshing = repo.sessionsRefreshing.value, onRefresh = { repo.refreshSessions() }, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (af != "both") item { AgentFilterChip(af) { repo.setAgentFilter("both") } }
                item {
                    // one tap starts right away with the persisted defaults (openSession's own fallbacks);
                    // the trailing chip shows those defaults and opens the full agent+mode picker instead
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.accent.copy(alpha = 0.16f))
                            .clickable(enabled = !starting) { repo.openSession(dir) }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(Res.string.new_session_cta), color = Tok.accent, fontWeight = FontWeight.SemiBold)
                                if (starting) {
                                    Spacer(Modifier.width(8.dp))
                                    CircularProgressIndicator(Modifier.size(12.dp), color = Tok.accent, strokeWidth = 1.5.dp)
                                }
                            }
                            Text(
                                stringResource(Res.string.start_agent_in, agentName(repo.defaultAgent.value), tilde(dir)),
                                color = Tok.muted, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        SessionDefaultsChip(repo.defaultAgent.value, repo.defaultMode.value, enabled = !starting) { pickMode = true }
                    }
                }
                items(filtered) { s ->
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
private fun ChatScreen(repo: PocketRepository, onOpenFleet: () -> Unit = {}, onOpenInbox: () -> Unit = {}) {
    // Restore the composer draft (keyed per conversation, workdir for a brand-new session). Re-inits on a
    // REAL switch only — keyed off composerEpoch, NOT draftKey (#29 semantics kept): the key chain flips in
    // place mid-typing (brand-new session materializing, forked resume corrected by SessionLive), and
    // re-reading the ≤400ms-stale draft then yanked the live text out from under the IME — on the iOS pinyin
    // keyboard that committed the space-segmented marked text as raw letters, "claude"→"c l a u d e" (#108,
    // #93's wild signature). The debounced saver below re-homes the text under the flipped key.
    val draftKey = repo.composerKey()
    var input by remember(repo.composerEpoch.value) { mutableStateOf(repo.draftFor(draftKey)) }
    var viewer by remember { mutableStateOf<Pair<List<ByteArray>, Int>?>(null) } // tapped sent images → full-screen
    var showSwitcher by remember { mutableStateOf(false) } // machine name in the connection bar → switch computer
    var showModeSheet by remember { mutableStateOf(false) }
    var showSessionInfo by remember { mutableStateOf(false) }
    var showQuickActions by remember { mutableStateOf(false) }
    var showBgJobs by remember { mutableStateOf(false) }
    var showTerminal by remember { mutableStateOf(false) }
    var showChangedFiles by remember { mutableStateOf(false) }
    if (showTerminal) { TerminalScreen(repo) { showTerminal = false }; return } // full-screen, replaces chat (issue #3)
    if (repo.viewedFilePath.value != null) { // changed-file viewer (issue #36); back → the still-open files list, ✕ → chat (issue #53)
        FileViewerScreen(repo, onExit = if (showChangedFiles) ({ repo.closeFileViewer(); showChangedFiles = false }) else null) { repo.closeFileViewer() }
        return
    }
    // platform picker resizes/compresses on-device; the repo budgets the picked photos against the 256 KiB frame
    val launchPicker = rememberImageAttacher { added -> repo.attachImages(added) }
    val listState = rememberLazyListState()
    // stick to the bottom only while the user is there ("pinned"); scrolling up unpins and shows
    // the Jump-to-latest pill instead of yanking the viewport down on every streamed chunk.
    var pinned by rememberBottomPinned(listState)
    // keep the message list hidden until it's first parked at the bottom, so opening a session with
    // history doesn't flash the top then visibly scroll down. Resets per session (convoId); a short
    // grace reveals an empty/new session that has no history to position on.
    var landed by remember(repo.convoId.value) { mutableStateOf(false) }
    LaunchedEffect(repo.convoId.value) { delay(180); landed = true }
    // a just-created session opens on an empty chat — focus the composer and raise the keyboard
    // right away instead of making the user tap the field first. openSession arms the flag only
    // for resumeId == null (never on resume/reattach/fleet-follow); consumed here exactly once.
    val composerFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(repo.convoId.value) {
        if (repo.convoId.value != null && repo.autoFocusComposer.value && !repo.observing.value) {
            repo.autoFocusComposer.value = false
            delay(250) // let the screen land (180ms grace above) before the IME animates in
            composerFocus.requestFocus()
            keyboard?.show()
        }
    }
    // persist the composer draft per project (debounced) so leaving mid-message doesn't lose it
    LaunchedEffect(input, draftKey) { delay(400); repo.saveDraft(draftKey, input) }
    // a huge scrollOffset lands at the bottom even when the last message is taller than the viewport
    LaunchedEffect(repo.messages.size, repo.messages.lastOrNull(), repo.streaming.value) {
        if (pinned && repo.messages.isNotEmpty()) { listState.scrollToItem(repo.messages.lastIndex, Int.MAX_VALUE); landed = true }
    }
    // keyboard-follow lives in its own leaf composable: the ime inset must be a COMPOSITION read
    // (iOS misses the animation otherwise), and reading it here would re-execute all of ChatScreen
    // every animation frame — the leaf confines that per-frame invalidation to itself.
    ImeFollower(listState, repo) { pinned }
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
                    Row(verticalAlignment = Alignment.CenterVertically) { // connection bar: honest dot (green only when Ready) + machine · folder · model
                        PulseDot(if (repo.phase.value == ConnPhase.Ready) Tok.ok else Tok.warn)
                        Spacer(Modifier.width(5.dp))
                        // ONE style for every segment — the machine name used to force lineHeight=11 while
                        // the path/model kept the font's default, so the three never shared a baseline
                        val metaStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
                        // which computer this conversation lives on — its own tap target: switch machines
                        // without leaving the chat (the surrounding column still opens session info)
                        repo.paired.value?.let { d ->
                            Text(
                                d.displayName(), color = Tok.tx2, style = metaStyle, maxLines = 1,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable { showSwitcher = true }
                                    .padding(horizontal = 2.dp),
                            )
                            Text("·", color = Tok.muted, style = metaStyle, modifier = Modifier.padding(horizontal = 3.dp))
                        }
                        // just the project FOLDER: the full path routinely ate the whole line on a phone,
                        // and it's one tap away in the session-info sheet (this row opens it)
                        Text(
                            folderName(repo.workdir.value), color = Tok.tx2, style = metaStyle,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                        )
                        // the effective model: the real alias once known, else an "account default"
                        // placeholder — never a blank gap. A pre-first-turn session (lazy start #61) whose
                        // model the daemon couldn't eager-resolve shows the placeholder until the first turn's
                        // init names the CLI/account default (issue #96)
                        val modelLabel = modelAlias(repo.model.value).ifBlank { stringResource(Res.string.value_model_default) }
                        Text("·", color = Tok.muted, style = metaStyle, modifier = Modifier.padding(horizontal = 3.dp))
                        Text(modelLabel, color = Tok.muted, style = metaStyle, maxLines = 1)
                        AgentBadge(repo.sessionAgent.value) // shows only for Codex; Claude stays quiet
                    }
                }
                if (!repo.observing.value) {
                    // execution state gets its own persistent header chip (issue #52): re-entering a mid-turn
                    // session showed nothing alive — the connection dot only says "linked", not "working",
                    // and the composer stays enabled (queueing), which read as "disconnected".
                    if (repo.streaming.value) Row(
                        Modifier.padding(end = 6.dp).clip(RoundedCornerShape(10.dp)).background(Tok.raised)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        PulseDot(Tok.accent)
                        Text(stringResource(Res.string.chat_running), color = Tok.accent, fontSize = 11.sp)
                    }
                    // mode switching moved into the ⋯ quick-actions sheet — the persistent badge was one
                    // more thing crowding the header for a setting touched a few times per session
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).clickable { showQuickActions = true },
                        contentAlignment = Alignment.Center,
                    ) { Text("⋯", color = Tok.tx2, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                }
            }
            Box(Modifier.weight(1f)) {
                // reserve a gutter below the last message so the floating context pill sits in empty
                // space (with a gap) instead of covering the last line + its copy button (issue #15).
                // The pill's MEASURED height drives the reserve (issue #81): a fixed 30.dp only cleared
                // it at font-scale 1 — the pill grows with the system/app text size while a hardcoded
                // gutter doesn't, so a long reply's tail slid under the pill on larger text. Measuring
                // keeps the pill floating (no layout footprint → never pushes the composer) yet always
                // leaves the last line above it. Falls back to the old gutter until the pill measures.
                val density = LocalDensity.current
                var pillHeightPx by remember { mutableStateOf(0) }
                val bottomGutter = (with(density) { pillHeightPx.toDp() } + 16.dp).coerceAtLeast(36.dp)
                LazyColumn(
                    Modifier.fillMaxSize().padding(16.dp).graphicsLayer { alpha = if (landed) 1f else 0f }
                        .pointerInput(Unit) { detectTapGestures { focus.clearFocus() } },
                    state = listState, verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = bottomGutter),
                ) {
                    items(repo.messages) { m ->
                        // a prompt the daemon hasn't acknowledged while the link is down — or while the link
                        // CLAIMS up but receipts stalled past the deadline (issue #78, multi-computer links):
                        // say so under the bubble instead of letting it look sent (issue #41 — frames queue
                        // silently offline)
                        val undelivered = m is ChatItem.User && m.pending && (repo.phase.value != ConnPhase.Ready || repo.sendStalled.value)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            MessageItem(m) { imgs, i -> viewer = imgs to i }
                            when {
                                undelivered -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    PulseDot(Tok.warn, size = 5.dp)
                                    Text(stringResource(Res.string.msg_pending_undelivered), color = Tok.warn, fontSize = 11.sp)
                                }
                                // link is up but the daemon hasn't receipted yet (issue #66): quiet "sending…"
                                // after a short grace so a normal instant ack never flashes it
                                m is ChatItem.User && m.pending -> {
                                    var slow by remember(m) { mutableStateOf(false) }
                                    LaunchedEffect(m) { delay(1200); slow = true }
                                    if (slow) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                        PulseDot(Tok.muted, size = 5.dp)
                                        Text(stringResource(Res.string.msg_sending), color = Tok.muted, fontSize = 11.sp)
                                    }
                                }
                                // receipted (issue #66) — shows until the reply starts streaming (this bubble
                                // stops being the last item), so a slow agent start still reads as "it got there"
                                m is ChatItem.User && m.delivered && m == repo.messages.lastOrNull() ->
                                    Text("✓ " + stringResource(Res.string.msg_delivered), color = Tok.muted, fontSize = 11.sp)
                            }
                        }
                    }
                    // a running turn ALWAYS ends the stream with something alive (issue #52 — desktop's
                    // blinking tail cursor equivalent): the full "Thinking…" row when nothing live is on
                    // screen yet, else just a pulsing dot — a replayed Assistant tail (re-entered mid-turn
                    // session) doesn't move on its own, so without this the screen looks dead.
                    val last = repo.messages.lastOrNull()
                    val liveContent = (last is ChatItem.Thinking && last.seconds == null) || last is ChatItem.Assistant
                    if (repo.streaming.value) item { if (liveContent) PulseDot(Tok.accent) else WorkingRow() }
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
                // context usage floats over the message tail's bottom-right — no layout footprint (issue #15).
                // Its measured height feeds the list's bottom gutter above (issue #81) so the pill never
                // covers the last line; onSizeChanged only fires once it renders (skipped while hidden).
                ContextStatusline(
                    repo.contextUsed.value, repo.contextWindow.value,
                    Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 12.dp)
                        .onSizeChanged { pillHeightPx = it.height },
                )
                // approvals waiting on OTHER machines pull you over without reflowing this stream —
                // floats under the connection bar; this machine's own ask keeps its sheet (Fleet ⑤)
                dev.ccpocket.app.ui.fleet.CrossMachineBanner(
                    repo.crossMachineAttention(),
                    onReview = onOpenInbox,
                    modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 12.dp).padding(top = 8.dp),
                )
            }
            // session health (issue #65): a degraded session (recent turns all API failures) or a nearly
            // full context window gets a persistent strip right above the composer — warn BEFORE the next
            // prompt goes in, not after it fails
            if (repo.sessionDegraded.value) {
                StatusBanner(Tok.danger, stringResource(Res.string.session_degraded_banner))
            } else {
                val used = repo.contextUsed.value
                val window = repo.contextWindow.value
                if (used != null && window != null && used.toFloat() / window >= 0.9f) {
                    StatusBanner(Tok.warn, stringResource(Res.string.context_high_banner, "${(used.toFloat() / window * 100).toInt()}%"))
                }
            }
            // Claude paused its turn to ask questions — the card docks above the composer; the
            // stream above stays scrollable so the user can re-read context before answering.
            // While one of the card's text fields owns input, the composer hides (design ③).
            var cardOwnsInput by remember(repo.pendingAsk.value?.askId) { mutableStateOf(false) }
            val questionAsk = repo.pendingAsk.value?.takeIf { it.isQuestion }
            questionAsk?.let { ask ->
                val skipMessage = stringResource(Res.string.question_skip_message)
                QuestionCard(
                    ask,
                    onAnswer = { answers, response -> repo.answerQuestions(answers, response) },
                    onSkip = { repo.resolve(Decision.DENY, message = skipMessage) },
                    onOwnsInput = { cardOwnsInput = it },
                )
            }
            if (questionAsk != null && cardOwnsInput) {
                // composer yields while the card's field has the keyboard
            } else if (repo.observing.value) {
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
                val slashQuery = slashQueryOf(input)
                val suggestions = remember(slashQuery, repo.slashCommands.toList()) {
                    slashSuggestions(slashQuery, repo.slashCommands)
                }
                // "@file" completion (issue #75): tap-only and cursor-at-end (the common mobile case) — the
                // daemon browses the session cwd, a folder tap drills in, a file tap inserts its path. Yields
                // to the slash menu when that's showing. sep is the daemon host's separator (Windows-safe).
                val atSep = repo.workdir.value?.let { if (it.contains('\\')) '\\' else '/' } ?: '/'
                val atToken = if (suggestions.isEmpty()) atTokenAt(input, input.length) else null
                val atDir = atToken?.let { atDirOf(it.query, atSep) } ?: ""
                val atLeaf = atToken?.let { atLeafOf(it.query, atSep) } ?: ""
                LaunchedEffect(atToken != null, atDir) { if (atToken != null) repo.browseFiles(atDir) }
                val atListing = repo.pathListing.value
                val atFileMatches = remember(atListing, atToken, atDir, atLeaf) {
                    if (atToken == null || atListing?.subPath != atDir) emptyList() else atMatches(atListing.entries, atLeaf)
                }
                Column(Modifier.fillMaxWidth().background(Tok.surface)) {
                    BackgroundJobsStrip(repo.backgroundJobs) { showBgJobs = true } // ≥1 running bg task → tap to expand
                    val capturing = voiceState is VoiceState.Recording || voiceState is VoiceState.Transcribing
                    if (suggestions.isNotEmpty() && !capturing) {
                        SlashCommandMenu(suggestions) { cmd -> input = cmd.completion() }
                    } else if (atFileMatches.isNotEmpty() && !capturing) {
                        FileCompletionMenu(atFileMatches, atDir, atSep) { entry ->
                            atToken?.let { input = input.substring(0, it.at + 1) + atInsertText(atDir, entry, atSep) + input.substring(it.end) }
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
                                // mid-turn the field stays enabled (sends queue into the running turn) — say so,
                                // or an editable composer under a "running" session reads as disconnected (issue #52)
                                placeholder = stringResource(
                                    when {
                                        repo.pendingImages.isNotEmpty() -> Res.string.add_message_hint
                                        repo.streaming.value -> Res.string.message_queued_hint
                                        else -> Res.string.message_claude_hint
                                    },
                                ),
                                modifier = Modifier.weight(1f),
                                focusRequester = composerFocus,
                            )
                            Spacer(Modifier.width(8.dp))
                            // while a turn runs the ■ stays put; typed text adds Send NEXT TO it instead of
                            // replacing it — mirrors Claude Code, where interrupt (Esc) and queue-a-message
                            // (Enter) coexist. Claude's stream-json input queues a mid-turn user message and
                            // weaves it into the running turn at the next tool boundary (verified on 2.1.201).
                            if (repo.streaming.value && (input.isNotBlank() || hasReady)) {
                                StopButton { repo.cancelTurn() }
                                Spacer(Modifier.width(8.dp))
                            }
                            when {
                                // text/image staged -> SEND, even mid-turn (claude queues it; see above)
                                input.isNotBlank() || hasReady -> {
                                    val sendLabel = stringResource(Res.string.send)
                                    RoundActionButton(
                                        onClick = {
                                            val t = input.trim()
                                            // a gated send (degraded session, issue #65) returns false — keep the text for the retry
                                            if ((t.isNotBlank() || hasReady) && repo.sendPrompt(t)) { input = ""; repo.clearDraft(draftKey) }
                                        },
                                        filled = true, contentDescription = sendLabel,
                                    ) { Icon(SendArrowIcon, sendLabel, tint = Tok.base, modifier = Modifier.size(18.dp)) }
                                }
                                // generating with an empty composer -> the slot is Stop (interrupts the turn, session stays)
                                repo.streaming.value -> StopButton { repo.cancelTurn() }
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
        if (showQuickActions) {
            QuickActionsSheet(
                repo,
                onTerminal = { showTerminal = true },
                onMode = { showModeSheet = true },
                onFiles = { repo.fetchChangedFiles(); showChangedFiles = true },
            ) { showQuickActions = false }
        }
        if (showChangedFiles) ChangedFilesSheet(repo, onOpen = { repo.openChangedFile(it) }) { showChangedFiles = false }
        if (showBgJobs) BackgroundJobsSheet(repo.backgroundJobs, onStop = { repo.stopBackgroundJob(it.id) }) { showBgJobs = false }
        if (showSwitcher) dev.ccpocket.app.ui.fleet.MachineSwitcherSheet(repo, onDismiss = { showSwitcher = false }, onManage = onOpenFleet)
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

/** The composer's "@file" completion panel (issue #75): tap a row to insert its relative path — a folder
 *  drills in (trailing separator, the daemon re-lists it), a file completes the reference. */
@Composable
private fun FileCompletionMenu(
    entries: List<dev.ccpocket.protocol.PathEntry>,
    dir: String,
    sep: Char,
    onPick: (dev.ccpocket.protocol.PathEntry) -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Tok.raised)) {
        Text(
            "@ " + dir.ifEmpty { "." },
            color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
        )
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(bottom = 4.dp)) {
            items(entries) { entry ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(entry) }.padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (entry.isDir) "▸" else " ", color = Tok.muted,
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.width(16.dp),
                    )
                    Text(
                        entry.name + if (entry.isDir) sep.toString() else "",
                        color = if (entry.isDir) Tok.tx else Tok.tx2,
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                        fontWeight = if (entry.isDir) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
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
        is ChatItem.Tool -> if (isSubagentTool(m.tool)) SubagentCard(m) else {
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
        // the quiet residue of a question exchange: an expandable answered row / a muted withdrawn note
        is ChatItem.QuestionsAnswered -> QuestionsAnsweredRow(m.items)
        is ChatItem.QuestionsWithdrawn -> QuestionsWithdrawnRow()
        // a live turn's end: quiet ✓ line so "finished" stays visible in the transcript
        is ChatItem.TurnEnded -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text(
                "✓ " + stringResource(Res.string.turn_done_marker) + (m.seconds?.let { " · ${turnDurLabel(it)}" } ?: ""),
                color = Tok.ok, fontSize = 11.sp,
            )
        }
    }
}

/** A turn's wall-clock as "42s" / "1m 3s". `internal` so the desktop ChatPane renderer shares it. */
internal fun turnDurLabel(s: Int) = if (s >= 60) "${s / 60}m ${s % 60}s" else "${s}s"

/** The ■ interrupt button in the composer action slot — same glyph whether it rides beside Send or stands alone. */
@Composable
private fun StopButton(onClick: () -> Unit) {
    val stopLabel = stringResource(Res.string.stop)
    RoundActionButton(onClick = onClick, filled = false, contentDescription = stopLabel) {
        Box(Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(Tok.accent))
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
    // no known denominator (Codex — gpt-* windows aren't in our table): show raw occupancy, not a fake %
    val label = if (window == null) {
        "${stringResource(Res.string.label_context)} ~${if (used >= 1000) "${used / 1000}k" else "$used"}"
    } else {
        "${stringResource(Res.string.label_context)} ${(used.toFloat() / window).coerceIn(0f, 1f).times(100).toInt()}%"
    }
    val frac = if (window == null) 0f else (used.toFloat() / window).coerceIn(0f, 1f)
    Text(
        label,
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
