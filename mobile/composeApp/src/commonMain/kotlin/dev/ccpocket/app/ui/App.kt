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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Share
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import dev.ccpocket.app.media.rememberFileAttacher
import dev.ccpocket.app.media.rememberImageAttacher
import dev.ccpocket.app.media.rememberVideoAttacher
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import dev.ccpocket.app.epochMillis
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.ccpocket.app.APP_VERSION
import dev.ccpocket.app.SupportContext
import dev.ccpocket.app.supportPlatformLabel
import dev.ccpocket.app.data.ChatItem
import dev.ccpocket.app.data.ConnPhase
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.data.StatusMsg
import dev.ccpocket.app.data.VoiceState
import dev.ccpocket.app.pairing.displayName
import dev.ccpocket.app.ui.chat.ChatHeader
import dev.ccpocket.app.ui.chat.ChatStateBlock
import dev.ccpocket.app.ui.chat.ContextLine
import dev.ccpocket.app.ui.chat.ToolTurnBand
import dev.ccpocket.app.ui.chat.TurnSourceLabel
import dev.ccpocket.app.ui.chat.chatStateUi
import dev.ccpocket.app.ui.fleet.attentionAsk
import dev.ccpocket.app.ui.fleet.crossMachineAttention
import dev.ccpocket.app.ui.fleet.ApprovalQueueFab
import dev.ccpocket.app.ui.fleet.fleetAttention
import dev.ccpocket.app.ui.fleet.fleetMachines
import dev.ccpocket.app.ui.fleet.MachineStatus
import dev.ccpocket.app.ui.entry.ComputersSurface
import dev.ccpocket.app.ui.entry.EntrySecondaryButton
import dev.ccpocket.app.ui.entry.connRecovery
import dev.ccpocket.app.ui.session.ConnBadge
import dev.ccpocket.app.ui.session.Hairline
import dev.ccpocket.app.ui.session.NewSessionDock
import dev.ccpocket.app.ui.session.SessionAttention
import dev.ccpocket.app.ui.session.SessionListRow
import dev.ccpocket.app.ui.session.SessionRowUi
import dev.ccpocket.app.ui.session.SessionSectionLabel
import dev.ccpocket.app.ui.session.SessionsContextHeader
import dev.ccpocket.app.ui.session.SessionsEmptyState
import dev.ccpocket.app.ui.session.StateMark
import dev.ccpocket.app.ui.session.StateMarkGlyph
import dev.ccpocket.app.ui.session.SurfaceState
import dev.ccpocket.app.ui.session.stateColor
import dev.ccpocket.app.ui.session.sessionRows
import dev.ccpocket.app.ui.session.splitSessions
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.ui.handoff.ConnectColleagueFlow
import dev.ccpocket.app.ui.handoff.HandoffAcceptScreen
import dev.ccpocket.app.ui.handoff.IncomingHandoffScreen
import dev.ccpocket.app.ui.handoff.HandoffAvatar
import dev.ccpocket.app.ui.handoff.HandoffAvatarPair
import dev.ccpocket.app.ui.handoff.HandoffDraftSheet
import dev.ccpocket.app.ui.handoff.HandoffFinishReturnButton
import dev.ccpocket.app.ui.handoff.HandoffInviteSheet
import dev.ccpocket.app.ui.handoff.HandoffLockBanner
import dev.ccpocket.app.ui.handoff.HandoffLockedComposer
import dev.ccpocket.app.ui.handoff.HandoffResultCard
import dev.ccpocket.app.ui.handoff.HandoffReturnSheet
import dev.ccpocket.app.ui.handoff.HandoffRibbon
import dev.ccpocket.app.ui.handoff.HandoffStatusChip
import dev.ccpocket.app.ui.handoff.HandoffUiStatus
import dev.ccpocket.app.ui.handoff.HandoffWatchBar
import dev.ccpocket.app.ui.handoff.elapsedLabel
import dev.ccpocket.app.ui.handoff.expiresCountdown
import dev.ccpocket.app.ui.handoff.inviteBlob
import dev.ccpocket.app.ui.handoff.shortCode
import dev.ccpocket.app.ui.handoff.toSections
import dev.ccpocket.app.ui.handoff.toUi
import dev.ccpocket.protocol.HandoffResult
import dev.ccpocket.protocol.HandoffStatus
import dev.ccpocket.app.ui.share.GuestEnding
import dev.ccpocket.app.ui.share.ShareFolderScreen
import dev.ccpocket.app.ui.share.SharedPill
import dev.ccpocket.app.ui.share.expiryLeft
import dev.ccpocket.app.ui.share.expiryLeftText
import dev.ccpocket.app.theme.LocalFontScale
import dev.ccpocket.app.theme.Metric
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.theme.TypeRole
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import dev.ccpocket.app.voice.openAppSettings
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.SessionGroup
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.CommandSource
import dev.ccpocket.protocol.DEFAULT_CONTEXT_WINDOW
import dev.ccpocket.protocol.Decision
import dev.ccpocket.protocol.DirectoryEntry
import dev.ccpocket.protocol.isQuestion
import dev.ccpocket.protocol.isSubagentTool
import dev.ccpocket.protocol.isWorkflowTool
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.ShareEnded
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
    // the ReviewRequest centre (REVIEW-REQUEST.md §12) — a full-screen route on the same overlay footing
    // as the fleet surfaces, because it is about the ACTIVE machine's ledger and follows the same switch
    var reviewsOpen by remember { mutableStateOf(false) }
    var appForeground by remember { mutableStateOf(true) }
    // Collaborator Links are contacts, not computers (SESSION-HANDOFF.md §4.1): their links live in their
    // own always-on inbox rather than the fleet, and they carry exactly one thing — Handoff offers.
    val collabInbox = remember { dev.ccpocket.app.data.CollaboratorInbox(scope).also { it.start() } }
    // a fresh redeem shouldn't wait for the next launch to start listening — the offer that prompted the QR
    // is usually already sitting on the colleague's daemon
    remember { repo.onCollaboratorLinkAdded = { binding, ticket -> collabInbox.add(binding, ticket) } }
    LaunchedEffect(Unit) {
        dev.ccpocket.app.telemetry.Telemetry.track(dev.ccpocket.app.telemetry.TelEvent.AppLaunch)
        if (repo.paired.value != null) repo.startRelay() // already paired -> straight to the list
    }
    val pendingLink by dev.ccpocket.app.DeepLink.pending.collectAsState()
    // §7: ONE parse for every entry point. A collaborator link parks in pendingCollabInvite for the
    // fingerprint confirm screen below — a deep link must never redeem on sight.
    LaunchedEffect(pendingLink) { pendingLink?.let { repo.handleIncomingLink(it); dev.ccpocket.app.DeepLink.pending.value = null } }
    // …and a review-contact link is addressed to the Review Center rather than the pairing door
    // (REVIEW-REQUEST.md §13.3): open it so the Center's join page can show the fingerprint. The ticket
    // is still redeemed by the DAEMON, and only after the human accepts these words.
    LaunchedEffect(repo.pendingReviewInvite.value) { if (repo.pendingReviewInvite.value != null) reviewsOpen = true }
    // a tapped task-complete push deep-links straight into its session (connecting first if needed)
    val pushOpen by dev.ccpocket.app.PushRoute.pending.collectAsState()
    LaunchedEffect(pushOpen) { pushOpen?.let { repo.requestOpenSession(it.workdir, it.sessionId); dev.ccpocket.app.PushRoute.pending.value = null } }
    // a tapped OFFER push (§3.4) names only the handoff — it selects that offer in the doorway below, which
    // still runs the ordinary confirm → accept flow
    val offerOpen by dev.ccpocket.app.PushRoute.pendingHandoff.collectAsState()
    LaunchedEffect(offerOpen) { offerOpen?.let { repo.pendingOfferId.value = it; dev.ccpocket.app.PushRoute.pendingHandoff.value = null } }
    // the notifications toggle lives on the primary link but governs the whole device: fan it out so a
    // Collaborator Link inbox de-registers (and re-registers) its own token with it (§3.4)
    remember { repo.onNotificationsChanged = { on -> collabInbox.onNotificationsChanged(on) } }
    val appLock = repo.appLock
    dev.ccpocket.app.OnAppForeground { // iOS kills sockets in background — reconnect the whole fleet on return
        appForeground = true
        repo.onAppForeground()
        dev.ccpocket.app.data.FleetRuntime.coordinator?.onAppForeground()
        collabInbox.onAppForeground() // §3.2.3: and re-pull each contact's offers (a missed push heals here)
        (dev.ccpocket.app.data.FleetRuntime.coordinator?.repos() ?: listOf(repo)).forEach { it.refreshPendingApprovals() }
        appLock.onForeground() // App Lock (issue #109): re-lock per policy / drop the cover on return
    }
    // App Lock: arm auto-lock when fully backgrounded; draw the opaque privacy cover the instant the app is
    // obscured (before the OS app-switcher snapshot) so a session is never visible in the task switcher.
    dev.ccpocket.app.OnAppBackground { appForeground = false; appLock.onBackground() }
    dev.ccpocket.app.OnAppObscured { appLock.onWillObscure() }
    // Push is alert-only: while the app is visible, pull each live daemon's authoritative queue. The first
    // pull is immediate; a missed APNs notification therefore becomes visible within one foreground sync.
    LaunchedEffect(appForeground, repo.sessionActive.value) {
        if (!appForeground || !repo.sessionActive.value) return@LaunchedEffect
        while (true) {
            (dev.ccpocket.app.data.FleetRuntime.coordinator?.repos() ?: listOf(repo)).forEach { it.refreshPendingApprovals() }
            delay(3_000)
        }
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
    PocketTheme(mode = repo.themeMode.value, accent = repo.accentTheme.value, fontScale = repo.fontScale.value) {
      Box(Modifier.fillMaxSize()) {
        val approvalAsk = repo.pendingAsk.value?.takeIf { !it.isQuestion }
        Surface(Modifier.fillMaxSize(), color = Tok.base) {
            // Secure Approval is pointer-modal by itself. Clearing the covered tree here also makes it
            // modal to TalkBack/VoiceOver: focus cannot traverse into chat/navigation under the scrim.
            val coveredContent = if (approvalAsk != null) Modifier.clearAndSetSemantics { } else Modifier
            Column(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding()
                    .then(coveredContent),
            ) {
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
                            ConnectionGate(
                                repo,
                                onOpenComputers = { fleetOpen = true },
                                onOpenReviews = { reviewsOpen = true },
                            ) {
                                when {
                                    // switchingSession keeps the chat mounted across a chat→chat switch:
                                    // openSession nulls convoId while it waits for the daemon, and without
                                    // this the switcher bounced you out to a session list for a beat (#165)
                                    repo.convoId.value != null || repo.switchingSession.value ->
                                        ChatScreen(repo, onOpenFleet = { fleetOpen = true }, onOpenInbox = { inboxOpen = true })
                                    repo.sessionsDir.value != null -> SessionsScreen(repo, onOpenInbox = { inboxOpen = true })
                                    else -> DirectoryScreen(
                                        repo, onOpenFleet = { fleetOpen = true }, onOpenInbox = { inboxOpen = true },
                                        onOpenReviews = { reviewsOpen = true },
                                    )
                                }
                            }
                            // fleet overlays ride ABOVE the gate: the fleet view is exactly where you
                            // want to be while this machine is reconnecting or another one has news
                            if (fleetOpen) dev.ccpocket.app.ui.fleet.FleetHomeScreen(repo, onBack = { fleetOpen = false }, onOpenInbox = { inboxOpen = true })
                            if (inboxOpen) dev.ccpocket.app.ui.fleet.AttentionInboxScreen(repo) { inboxOpen = false }
                            // registered after the fleet surfaces, so its back handler wins while it is up
                            if (reviewsOpen) dev.ccpocket.app.ui.review.ReviewCenterRoute(repo) { reviewsOpen = false }
                        }
                    }
                }
            }
            // a permission decision never needs typing — drop the keyboard so the sheet isn't cramped
            val rootFocus = LocalFocusManager.current
            LaunchedEffect(repo.pendingAsk.value?.convoId, repo.pendingAsk.value?.askId) {
                if (repo.pendingAsk.value != null) rootFocus.clearFocus()
            }
            // AskUserQuestion (ask.questions != null) renders as the docked QuestionCard inside
            // ChatScreen instead — questions are conversation, not a safety gate, and the user
            // should be able to scroll the chat for context while answering.
            approvalAsk?.let { ask ->
                // M2 AttentionLease: while THIS card is on screen AND the app is foregrounded, a 30s
                // heartbeat pauses the daemon's no-response budget (grant-aware asks only). Backgrounding
                // releases the lease explicitly, so an Android process idling behind the launcher can't
                // pause the budget forever (design §10.4). The grantOptions gate sits OUTSIDE the effect:
                // under the Compose test clock an unconditional infinite delay-loop would keep the virtual
                // frame clock busy forever (desktopTest hang, 08-02).
                if (ask.grantOptions != null) {
                    LaunchedEffect(ask.convoId, ask.askId, appForeground) {
                        if (!appForeground) {
                            repo.sendAskHeartbeat(visible = false)
                            return@LaunchedEffect
                        }
                        while (true) {
                            repo.sendAskHeartbeat(visible = true)
                            kotlinx.coroutines.delay(30_000)
                        }
                    }
                }
                // route adapter: the repository is read HERE and collapsed into one immutable value, so the
                // Secure Approval renderer stays a pure function of what the daemon actually said
                val approvalUi = dev.ccpocket.app.ui.approval.approvalUi(
                    ask = ask,
                    workdir = repo.workdir.value,
                    risk = repo.riskDetailFor(ask), // M3: the FULL event (level + reason + codes + assessed)
                    queueProgress = repo.askQueueProgress.value, // "n of m" while a burst is queued (design M1)
                    // §2.2/§4.3: during a REVIEW handoff a shell command is the one way a "read-only"
                    // review can still touch files — so it's confirmed each time (no standing rule) and
                    // the card says it leaves a record
                    handoffReview = repo.activeHandoff.value?.let {
                        it.status == HandoffStatus.IN_PROGRESS && repo.isHandoffRecipient(it)
                    } == true,
                    timedOutSignal = repo.askTimedOut(ask), // issue #100 (composite-matched, P1-3)
                )
                dev.ccpocket.app.ui.approval.SecureApprovalSheet(
                    approvalUi,
                    onDeny = { repo.resolve(Decision.DENY) },
                    onAllowOnce = { repo.resolve(Decision.ALLOW) },
                    onAllowTask = { repo.resolve(Decision.ALLOW, grantScope = "task") },
                    // legacy "Always allow" and the V2 session scope are the same effect: remember for old
                    // daemons, the M2 session grant for new ones
                    onAllowSession = { repo.resolve(Decision.ALLOW, remember = true, grantScope = "session") },
                    onAlwaysAllow = { repo.resolve(Decision.ALLOW, remember = true, grantScope = "session") },
                    onRetrySafer = { constraints -> repo.resolve(Decision.DENY, retrySafer = true, constraints = constraints) },
                    onDismiss = { repo.dismissAsk() },
                )
            }
        }
        // ── root-level trust screens (implementation review §3.2.5 / §7) ──────────────────────────────
        // Both ride ABOVE everything except App Lock, and neither depends on a session, a workdir or even
        // a connected computer: a collaborator's very first interaction with this app is one of these.
        repo.pendingCollabInvite.value?.let { invite ->
            dev.ccpocket.app.SystemBackHandler(enabled = true) { repo.pendingCollabInvite.value = null }
            dev.ccpocket.app.ui.handoff.ConfirmConnectionScreen(
                invite,
                confirming = repo.collabRedeeming.value,
                onConfirm = { repo.redeemCollaboratorInvite(invite) },
                onCancel = { repo.pendingCollabInvite.value = null },
            )
        }
        repo.pendingShareInvite.value?.let { invite ->
            dev.ccpocket.app.SystemBackHandler(enabled = true) { repo.pendingShareInvite.value = null }
            dev.ccpocket.app.ui.share.AcceptPreview(
                invite,
                onJoin = { repo.redeemShareInvite(invite); repo.pendingShareInvite.value = null },
                onDecline = { repo.pendingShareInvite.value = null },
            )
        }
        IncomingHandoffRoot(repo, collabInbox)
        // App Lock (issue #109): the gate blocks ALL content (incl. the permission sheet) until biometrics
        // pass; the cover masks the app-switcher snapshot while briefly backgrounded. Both reuse the same
        // branded lockup. Desktop never reaches App(), so this overlay is Android/iOS-only by construction.
        if (appLock.locked.value) AppLockGate(appLock)
        else if (appLock.covered.value) AppLockCover()
      }
    }
}

/**
 * The root-level incoming-handoff doorway (implementation review §3.2.5). Independent of convoId, workdir
 * and sessionKey by construction: it aggregates the WAITING offers addressed to this device across the
 * PRIMARY link (another of your own devices handing you work) and every Collaborator Link inbox, and it
 * shows itself the moment one exists — that is the whole "the first offer has to be able to arrive"
 * requirement. Dismissing it is per-offer-set: a NEW offer re-opens it.
 */
@Composable
private fun IncomingHandoffRoot(repo: PocketRepository, inbox: dev.ccpocket.app.data.CollaboratorInbox) {
    // a trust screen the user opened deliberately owns the screen first
    if (repo.pendingCollabInvite.value != null || repo.pendingShareInvite.value != null) return
    // (owning repo, offer) — the repo is who can answer it; an inbox offer must be accepted over the
    // collaborator link that received it, never over the primary
    val all: List<Pair<PocketRepository, dev.ccpocket.protocol.SessionHandoff>> =
        repo.incomingOffers().map { repo to it } + inbox.offers().map { it.repo to it.handoff }
    var dismissedFor by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    // a deep link / push tap names an offer directly (§3.4): honour it even if the sheet was dismissed.
    // Keyed on the offer IDS too, not just the routed id: a push routinely wakes the app BEFORE the inbox
    // link has reconnected and pulled the listing, so the named offer usually shows up a moment later.
    val routed = repo.pendingOfferId.value
    LaunchedEffect(routed, all.map { it.second.id }) {
        if (routed != null && all.any { it.second.id == routed }) {
            dismissedFor = dismissedFor - routed
            selectedId = routed
            repo.pendingOfferId.value = null
        }
    }
    val live = all.filterNot { it.second.id in dismissedFor }
    if (live.isEmpty()) { if (selectedId != null) selectedId = null; return }
    val owning = live.firstOrNull { it.second.id == selectedId }
    val answerRepo = owning?.first ?: live.first().first
    dev.ccpocket.app.SystemBackHandler(enabled = true) {
        if (selectedId != null) selectedId = null else dismissedFor = dismissedFor + live.map { it.second.id }
    }
    IncomingHandoffScreen(
        offers = live.map { it.second },
        selected = owning?.second,
        ownerLabelOf = { it.initiatorLabel ?: "?" },
        accepting = answerRepo.handoffAccepting.value,
        errorNote = answerRepo.handoffAcceptError.value?.let { stringResource(it) }
            ?: answerRepo.handoffUnsupported.value,
        onSelect = { selectedId = it.id },
        onAccept = { h -> live.firstOrNull { it.second.id == h.id }?.first?.acceptHandoff(h.id) },
        onDecline = { h ->
            live.firstOrNull { it.second.id == h.id }?.first?.declineHandoff(h.id)
            selectedId = null
        },
        onBack = { selectedId = null },
        onClose = { dismissedFor = dismissedFor + live.map { it.second.id }; selectedId = null },
    )
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
private fun ConnectionGate(
    repo: PocketRepository,
    // the skeleton wears the real Projects header, so it needs the same two routes the list has. Passed
    // through rather than invented here: these are the app root's existing overlays, not new destinations.
    onOpenComputers: () -> Unit = {},
    onOpenReviews: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val recovery = connRecovery(repo.phase.value)
    when (repo.phase.value) {
        ConnPhase.PairingInvalid -> {
            val ended = repo.shareEnded.value
            when {
                // guest share revoked MID-SESSION (design 4c): keep the transcript readable under the slim
                // danger banner instead of yanking the user to a full-screen card; the ended card waits at browse
                ended != null && ended.reason == ShareEnded.REASON_REVOKED && repo.convoId.value != null ->
                    Column(Modifier.fillMaxSize()) {
                        dev.ccpocket.app.ui.share.ShareRevokedBanner()
                        Box(Modifier.weight(1f)) { content() }
                    }
                // guest share ended (design 4b): the precise, calm terminal card — revoked vs expired
                ended != null -> dev.ccpocket.app.ui.share.GuestEndedCard(
                    ownerLabel = ended.ownerLabel,
                    ending = if (ended.reason == ShareEnded.REASON_EXPIRED) GuestEnding.EXPIRED else GuestEnding.REVOKED,
                    onRemove = { repo.unpairActive() },
                    onAskNew = { repo.unpairActive() }, // drops the dead binding → lands on Connect to paste a fresh invite
                )
                else -> RecoverySurface(repo, recovery)
            }
        }
        ConnPhase.RelayUnreachable -> RecoverySurface(repo, recovery)
        ConnPhase.ComputerOffline ->
            // mid-chat: keep the history readable under a slim banner instead of a takeover
            if (repo.convoId.value != null) { StatusBanner(Tok.warn, stringResource(Res.string.conn_computer_offline_banner)); content() }
            else RecoverySurface(repo, recovery)
        ConnPhase.Connecting ->
            if (repo.directoriesLoaded.value || repo.convoId.value != null) content()
            else DirectorySkeleton(repo, onOpenComputers, onOpenReviews)
        ConnPhase.Reconnecting, ConnPhase.Ready -> content()
    }
}

/** A failing phase lands on Computers: its own recovery region, with the paired list flat underneath —
 *  so switching machines reads as an ordinary choice rather than a second alarm (Entry Flow frames 09-11). */
@Composable
private fun RecoverySurface(repo: PocketRepository, recovery: dev.ccpocket.app.ui.entry.ConnRecoveryUi) {
    ComputersSurface(
        repo, recovery = recovery,
        onSwitch = { repo.switchDaemon(it) },
        onAdd = { repo.beginAddDevice() },
    )
}

/**
 * The Projects header (Entry Flow UI 2.0 · Master **v2** — the confirmed header amendment).
 *
 * Two rows, and BOTH of them are two-sided, so the top-right of the 402 pt release frame carries page-level
 * actions instead of the empty band the first pass left there:
 *
 * ```
 *   Projects                                          [🖵] [⋯]
 *   ● alex-macbook · online                       ◆ Review 2
 * ```
 *
 * Row 1 is the screen's name plus exactly two 48 dp controls: the computer doorway (an outlined display,
 * not a plus — it SWITCHES machines rather than creating anything) carrying the fleet's real waiting count,
 * and the overflow carrying the version-update dot. Row 2 keeps the state MARK beside the state WORD, so
 * the connection is legible in greyscale, and puts the review queue within one tap.
 *
 * Help and Settings moved into the overflow: they are doorways to specialist surfaces, and as a leading
 * text band they competed with the machine state and the work below it.
 *
 * ONE implementation, shared by [DirectoryScreen] and its connecting skeleton, so the skeleton → list swap
 * can never shift the geometry. [body] is the rest of the screen — the header owns the column so the
 * overflow can be an ordinary in-tree overlay above it, the same grammar [PocketSheet] already uses.
 */
@Composable
private fun ProjectsHeader(
    title: String,
    phase: ConnPhase,
    machine: String?,
    reviewCount: Int,
    fleetWaiting: Int,
    updateAvailable: Boolean,
    onOpenComputers: () -> Unit,
    onReviews: () -> Unit,
    onHelp: () -> Unit,
    onSettings: () -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    val recovery = connRecovery(phase)
    val state = stringResource(
        when (phase) {
            ConnPhase.Ready -> Res.string.ses_conn_online
            ConnPhase.Connecting -> Res.string.ses_conn_connecting
            ConnPhase.Reconnecting -> Res.string.proj_state_reconnecting
            else -> Res.string.ses_conn_offline
        },
    )
    var menuOpen by remember { mutableStateOf(false) }
    var titleRowPx by remember { mutableStateOf(0) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = Metric.gutter).padding(top = Metric.gapS)) {
                // ── row 1: the screen's name, and the two page-level controls it leaves room for ──
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Metric.touch).onSizeChanged { titleRowPx = it.height },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title, color = Tok.tx, style = TypeRole.screenTitle,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    HeaderIconAction(
                        Icons.Outlined.Computer, stringResource(Res.string.proj_open_computers),
                        onClick = onOpenComputers,
                    ) {
                        // the fleet's real waiting count rides its own doorway: one number, one target
                        if (fleetWaiting > 0) dev.ccpocket.app.ui.fleet.AttentionBadge(
                            fleetWaiting, Modifier.align(Alignment.TopEnd).padding(top = 4.dp),
                        )
                    }
                    HeaderIconAction(
                        Icons.Rounded.MoreHoriz, stringResource(Res.string.proj_more),
                        expanded = menuOpen, onClick = { menuOpen = !menuOpen },
                    ) {
                        // issue #200: the version nudge rides the entry to Settings, where the update
                        // instructions live. A DOT, not the review diamond — "there is an update" and
                        // "somebody is waiting" are different facts; the menu row below spells this one out.
                        if (updateAvailable) Box(
                            Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp)
                                .size(8.dp).clip(CircleShape).background(Tok.accent),
                        )
                    }
                }
                // ── row 2: the written machine state, and the review queue on the trailing edge ──
                ReflowRow(
                    Modifier.fillMaxWidth().padding(bottom = Metric.gapXs),
                    gap = Metric.gapS,
                    leading = {
                        Row(verticalAlignment = Alignment.Top) {
                            // the mark rides the FIRST line of the sentence: centred against a wrapped
                            // block it drifts into the gutter between lines and stops reading as its mark
                            Box(
                                Modifier.height(with(LocalDensity.current) { TypeRole.preview.lineHeight.toDp() }),
                                contentAlignment = Alignment.Center,
                            ) { StateMarkGlyph(recovery.mark, stateColor(recovery.tone)) }
                            Spacer(Modifier.width(Metric.gapS))
                            Text(
                                machine?.takeIf { it.isNotBlank() }
                                    ?.let { stringResource(Res.string.proj_machine_line, it, state) } ?: state,
                                color = Tok.tx2, style = TypeRole.preview,
                                maxLines = 3, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                ) { ReviewAction(reviewCount, onReviews) }
            }
            body()
        }
        if (menuOpen) ProjectsOverflowMenu(
            below = Metric.gapS + with(LocalDensity.current) { titleRowPx.toDp() },
            updateAvailable = updateAvailable,
            onHelp = { menuOpen = false; onHelp() },
            onSettings = { menuOpen = false; onSettings() },
            onDismiss = { menuOpen = false },
        )
    }
}

/** A 48 dp page-level control in the header: an icon, its localized name, and room for one real mark. */
@Composable
private fun HeaderIconAction(
    icon: ImageVector,
    label: String,
    expanded: Boolean? = null,
    onClick: () -> Unit,
    badge: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        Modifier.size(Metric.touch).clip(RoundedCornerShape(Metric.radiusS))
            .clickable(role = Role.Button, onClick = onClick)
            // a disclosure announces whether it is already open, so a screen reader is never told to
            // "activate" a menu that is on screen in front of it
            .semantics { if (expanded == true) collapse { onClick(); true } else if (expanded == false) expand { onClick(); true } },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Tok.tx2, modifier = Modifier.size(21.dp))
        badge()
    }
}

/**
 * The review queue, on row 2's trailing edge. The DIAMOND says somebody is waiting and the count is
 * written rather than merely tinted — but a zero is never printed: "Review 0" reads as a broken badge.
 */
@Composable
private fun ReviewAction(count: Int, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = Metric.touch).widthIn(min = Metric.touch)
            .clip(RoundedCornerShape(Metric.radiusS))
            .clickable(role = Role.Button, onClick = onClick)
            // the ink lands on the same optical line as the icon controls above it, which sit 13 dp inside
            // their own 48 dp boxes — a target wider than its label must not ragged-edge the gutter
            .padding(start = Metric.gapS, end = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        if (count > 0) {
            StateMarkGlyph(StateMark.DIAMOND, Tok.warn, size = 7.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            if (count > 0) stringResource(Res.string.proj_review_n, count) else stringResource(Res.string.proj_review),
            color = Tok.tx2, style = TypeRole.body,
        )
    }
}

/**
 * The overflow: Help and Settings, one tap under the title row.
 *
 * A low container — hairline and raised fill, no glass and no card stack — with 48 dp rows that grow with
 * their labels. Opening it decides nothing: an outside tap anywhere, a second tap on the trigger, system
 * back, or choosing a row all close it.
 */
@Composable
private fun ProjectsOverflowMenu(
    below: Dp,
    updateAvailable: Boolean,
    onHelp: () -> Unit,
    onSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    dev.ccpocket.app.SystemBackHandler(enabled = true) { onDismiss() }
    val shape = RoundedCornerShape(Metric.radius)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onDismiss() } })
        Column(
            Modifier.align(Alignment.TopEnd).padding(top = below, end = Metric.gap)
                .widthIn(min = 196.dp, max = 280.dp)
                .clip(shape).background(Tok.raised).border(Metric.hairline, Tok.hair, shape),
        ) {
            OverflowMenuRow(stringResource(Res.string.proj_help), onClick = onHelp)
            Hairline()
            // the version nudge is repeated here in WORDS — the dot on the trigger only points at it
            OverflowMenuRow(
                stringResource(Res.string.proj_settings),
                note = stringResource(Res.string.update_available).takeIf { updateAvailable },
                onClick = onSettings,
            )
        }
    }
}

@Composable
private fun OverflowMenuRow(label: String, note: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Metric.touch)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Metric.gapL, vertical = Metric.gapS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Tok.tx, style = TypeRole.body, modifier = Modifier.weight(1f))
        if (note != null) {
            Spacer(Modifier.width(Metric.gapS))
            Box(Modifier.size(7.dp).clip(CircleShape).background(Tok.accent))
            Spacer(Modifier.width(6.dp))
            Text(note, color = Tok.tx2, style = TypeRole.caption)
        }
    }
}

/**
 * A two-sided row that REFLOWS instead of shrinking.
 *
 * At the release frame the state sentence and the review queue share one line. The moment the sentence can
 * no longer sit on ONE line beside the action — 200% Dynamic Type, a long localization, a long machine
 * name, a narrow window — they stop competing for it: the leading block takes the whole row and the action
 * drops beneath it, still trailing. The trailing action is always measured first and keeps its own width,
 * so neither side is ever clipped to make the other fit. Requires a bounded width.
 */
@Composable
private fun ReflowRow(
    modifier: Modifier = Modifier,
    gap: Dp,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Layout({ leading(); trailing() }, modifier) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val width = constraints.maxWidth
        val trail = measurables[1].measure(Constraints(maxWidth = width))
        val beside = width - trail.width - gapPx
        val stacked = beside <= 0 || measurables[0].maxIntrinsicWidth(constraints.maxHeight) > beside
        val lead = measurables[0].measure(Constraints(maxWidth = if (stacked) width else beside))
        val height = if (stacked) lead.height + trail.height else maxOf(lead.height, trail.height)
        layout(width, height) {
            if (stacked) {
                lead.place(0, 0)
                trail.place(width - trail.width, lead.height)
            } else {
                lead.place(0, (height - lead.height) / 2)
                trail.place(width - trail.width, (height - trail.height) / 2)
            }
        }
    }
}

/** The single canonical "open any folder" entry — the one doorway to the picker, in the content hierarchy. */
@Composable
private fun OpenAnyFolderRow(onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Hairline()
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = Metric.gutter, vertical = Metric.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Folder, null, tint = Tok.tx2, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Metric.gap))
            Text(stringResource(Res.string.proj_open_any), color = Tok.tx, style = TypeRole.body, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Tok.muted, modifier = Modifier.size(18.dp))
        }
        Hairline()
    }
}

/** Connect/switch placeholder: the REAL Projects header over shimmering rows — so landing on a machine
 *  only swaps skeleton→list, instead of flashing a differently-shaped screen first. It claims nothing:
 *  the sentence below the bars says exactly what is still missing. The header's controls are REAL here
 *  too — waiting for a directory list is no reason to lose the way back to the computers or to Help. */
@Composable
internal fun DirectorySkeleton( // internal: EntryFlowUiTest pins its header against the Ready list's
    repo: PocketRepository,
    onOpenComputers: () -> Unit = {},
    onOpenReviews: () -> Unit = {},
) {
    var showHelp by remember { mutableStateOf(false) }
    if (showHelp) { HelpCenterScreen(HelpEntryPoint.PROJECTS, onBack = { showHelp = false }); return }
    var showSettings by remember { mutableStateOf(false) }
    if (showSettings) { SettingsScreen(repo, onBack = { showSettings = false }); return }
    val shimmer by rememberInfiniteTransition().animateFloat(
        initialValue = 0.25f, targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
    )
    ProjectsHeader(
        title = stringResource(Res.string.dir_projects),
        phase = repo.phase.value,
        machine = repo.paired.value?.displayName(),
        // this machine has told us NOTHING yet: the labels without counts, rather than a stale number
        reviewCount = 0,
        fleetWaiting = 0,
        updateAvailable = repo.versionStatus.value.anyBehind, // a local fact — true with or without a link
        onOpenComputers = onOpenComputers,
        onReviews = onOpenReviews,
        onHelp = { showHelp = true },
        onSettings = { showSettings = true },
    ) {
        Column(Modifier.fillMaxSize().padding(Metric.gutter), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(4) {
                Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(10.dp)).graphicsLayer { alpha = shimmer }.background(Tok.surface))
            }
            Text(
                stringResource(Res.string.conn_connecting_wait), color = Tok.tx2, style = TypeRole.caption,
                modifier = Modifier.padding(top = Metric.gapS),
            )
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

/**
 * No project matched the filter. Not an illustration: it names the query, says what was actually searched,
 * and offers the only two things that can help — clear the filter, or take the one open-folder doorway.
 */
@Composable
private fun NoMatches(query: String, onClear: () -> Unit, onOpenFolder: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = Metric.gutter).padding(top = 32.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metric.gapS)) {
            StateMarkGlyph(StateMark.RING, Tok.muted)
            Text(
                stringResource(Res.string.proj_no_match_title, query), color = Tok.tx, style = TypeRole.rowTitle,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            stringResource(Res.string.proj_no_match_body), color = Tok.tx2, style = TypeRole.preview,
            modifier = Modifier.padding(top = Metric.gapS),
        )
        EntrySecondaryButton(stringResource(Res.string.proj_clear_filter), Modifier.padding(top = Metric.gapL), onClick = onClear)
        EntrySecondaryButton(stringResource(Res.string.proj_open_any), Modifier.padding(top = Metric.gap), onClick = onOpenFolder)
    }
}

/** Disconnected, with at least one bound computer: the device picker. Tap one to connect, or add another. */
@Composable
private fun ConnectScreen(repo: PocketRepository) = ComputersSurface(
    repo,
    onSwitch = { repo.switchDaemon(it) },
    onAdd = { repo.beginAddDevice() },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DirectoryScreen( // internal: the Entry Flow hierarchy is asserted by EntryFlowUiTest (demo mode)
    repo: PocketRepository,
    onOpenFleet: () -> Unit = {},
    onOpenInbox: () -> Unit = {},
    onOpenReviews: () -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    var showHelp by remember { mutableStateOf(false) }
    if (showHelp) {
        HelpCenterScreen(HelpEntryPoint.PROJECTS, onBack = { showHelp = false })
        return
    }
    var showSettings by remember { mutableStateOf(false) }
    if (showSettings) { SettingsScreen(repo, onBack = { showSettings = false }); return } // full-screen, replaces this screen
    // long-press a project → "Share this folder…" opens the owner invite flow full-screen (issue #115)
    var shareTarget by remember { mutableStateOf<DirectoryEntry?>(null) }
    shareTarget?.let { ShareFolderScreen(repo, it, onBack = { shareTarget = null }); return }
    // pull-only list: refresh NOW — entering (and RE-entering, back from a session) shows fresh state
    // instead of the pre-session snapshot — then keep re-pulling quietly
    LaunchedEffect(Unit) { while (true) { repo.refreshDirectoriesSilently(); delay(10_000) } }

    val tree = repo.treeView.value
    val dirsSnapshot = repo.directories.toList()
    val agentFilter = repo.agentFilter.value
    val visibleDirs = remember(dirsSnapshot, agentFilter) { filterDirectoriesByAgent(dirsSnapshot, agentFilter) }
    val root = remember(visibleDirs) { treeRoot(visibleDirs) }
    val browse = repo.browsePath.value
    // a browse path the daemon no longer has (dirs changed) falls back to root
    val base = remember(visibleDirs, browse, root) {
        browse?.takeIf { b -> visibleDirs.any { it.path == b || it.path.startsWith(b + sepOf(b)) } } ?: root // sep-aware: a Windows daemon's paths use '\' (issue #19/#22 — tree drill-in)
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
    val flatRows = remember(visibleDirs, query, pinnedSnapshot, openSessionsLabel, projectsLabel) {
        buildDirRows(visibleDirs, query, pinnedSnapshot, pinnedLabel, openSessionsLabel, projectsLabel)
    }
    // at the root, also surface projects OUTSIDE it (other drives / off-home) as plain leaves
    val treeRows = remember(visibleDirs, base, root) { buildTree(visibleDirs, base, includeOrphans = base == root) }
    // when drilled into a folder that is itself a project, buildTree leads with its own leaf — split it out
    // as the "current project" row (the rest are its subfolders). Computed once here, not per recomposition.
    val currentLeaf = remember(treeRows, base, root) {
        (treeRows.firstOrNull() as? TreeRow.Leaf)?.takeIf { base != root && it.entry.path == base }
    }
    val childRows = remember(treeRows, currentLeaf) { if (currentLeaf != null) treeRows.drop(1) else treeRows }
    val live = remember(visibleDirs) { visibleDirs.filter { it.open || it.busy }.flatMap(::expandLiveSessions) } // ACTIVE: one row per live session
    // pinned projects shown at the tree root (in pin order, present-only) — mirrors the flat Pinned section
    val pinned = remember(visibleDirs, pinnedSnapshot) { pinnedEntries(visibleDirs, pinnedSnapshot) }
    // long-press a project → a small sheet to pin/unpin it
    var actionTarget by remember { mutableStateOf<DirectoryEntry?>(null) }
    // "+" → type an arbitrary path to start a session in a folder with no prior history (issue #7)
    var showNewPath by remember { mutableStateOf(false) }
    var newPathTarget by remember { mutableStateOf<String?>(null) }
    // "open a project folder" browser (issue #152): the "+" entries land here for an OWNER; a guest
    // keeps the manual path sheet (its browse anchor "~" is outside the share and daemon-denied anyway)
    var showDirPicker by remember { mutableStateOf(false) }
    val openFolderEntry = { if (isGuestDirView(dirsSnapshot)) showNewPath = true else showDirPicker = true }

    // typing in the filter then scrolling the list dismisses the keyboard (fires once per scroll gesture)
    val focus = LocalFocusManager.current
    val listState = rememberLazyListState()
    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) focus.clearFocus() }
    val approvalCount = repo.fleetAttention().size
    val approvalsRefreshing = repo.fleetMachines().any { it.pending > 0 && it.status != MachineStatus.ONLINE }
    val approvalClearance = if (approvalCount > 0) 72.dp else 0.dp

    Box(Modifier.fillMaxSize()) {
    // ── the header (Master v2): title + Computers/overflow, then the machine state + Review ──
    // The machine line is no longer its own tap target: the Computers control beside the title is THE
    // fleet doorway now, and it carries the same real waiting count the old chevron did.
    ProjectsHeader(
        title = headerTitle,
        phase = repo.phase.value,
        machine = repo.paired.value?.displayName(),
        reviewCount = repo.reviewPendingCount, // the Review Center (REVIEW-REQUEST.md §12)
        fleetWaiting = approvalCount,
        updateAvailable = repo.versionStatus.value.anyBehind,
        onOpenComputers = onOpenFleet,
        onReviews = onOpenReviews,
        onHelp = { showHelp = true },
        onSettings = { showSettings = true },
    ) {
        // ── the search row: filter beside the view mode ──
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Metric.gutter).padding(top = Metric.gapS, bottom = Metric.gapS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                query, { query = it }, placeholder = { Text(stringResource(Res.string.filter_hint)) }, singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Metric.gap))
            ViewToggle(tree) { repo.setTreeView(!tree) }
        }
        // THE open-folder entry. Exactly one, in the content hierarchy — the old top-bar "+" duplicated it
        // at equal weight, which left two controls competing to mean the same thing (#32/#152 route intact:
        // owners land in the browser, guests keep the manual path sheet).
        OpenAnyFolderRow(openFolderEntry)
        // NOTE: the "was ready and dropped → keep the list under a slim warning" banner is NOT repeated
        // here. The root already renders exactly one Reconnecting strip above every content screen, and a
        // second copy on Projects would be the same sentence twice. Computer offline never reaches this
        // list outside Chat — ConnectionGate routes it to the recovery surface instead.
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
                visibleDirs.isEmpty() && repo.directoriesLoaded.value ->
                    NoMatches(query, onClear = { query = "" }, onOpenFolder = openFolderEntry)
                !treeMode && flatRows.isEmpty() && repo.directoriesLoaded.value ->
                    NoMatches(query, onClear = { query = "" }, onOpenFolder = openFolderEntry)
                treeMode -> LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    state = listState,
                    contentPadding = PaddingValues(bottom = approvalClearance),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (base == root) { // PINNED + ACTIVE pinned on top at root
                        if (pinned.isNotEmpty()) {
                            item { Label(pinnedLabel) }
                            items(pinned, key = { "p:" + it.path }) { e -> ProjectCell(repo, e, showPath = true, direct = true, onLongPress = { actionTarget = e }, onNewSession = { newPathTarget = e.path }) }
                        }
                        if (live.isNotEmpty()) {
                            item { Label(activeLabel) }
                            // key carries the session too — expansion can put the same project here several times
                            items(live, key = { "a:" + it.path + ":" + (it.activeSessionId ?: "") }) { e -> ProjectCell(repo, e, showPath = true, direct = true, onLongPress = { actionTarget = e }, onNewSession = { newPathTarget = e.path }) }
                        }
                        if (pinned.isNotEmpty() || live.isNotEmpty()) item { Label(projectsLabel) }
                    }
                    // drilled into a folder that is itself a project → its own sessions lead as "current project"
                    if (currentLeaf != null) {
                        item { Label(currentProjectLabel) }
                        item(key = "cur:" + currentLeaf.entry.path) {
                            val e = currentLeaf.entry
                            LeafRow(e, pinned = repo.isPinned(e.path), onLongPress = { actionTarget = e }, onNewSession = { newPathTarget = e.path }) { repo.openProject(e) }
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
                                LeafRow(e, pinned = repo.isPinned(e.path), onLongPress = { actionTarget = e }, onNewSession = { newPathTarget = e.path }) { repo.openProject(e) }
                            }
                        }
                    }
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    state = listState,
                    contentPadding = PaddingValues(bottom = approvalClearance),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(flatRows) { row ->
                        when (row) {
                            is DirRow.Header -> Label(row.label)
                            is DirRow.Dir -> ProjectCell(
                                repo, row.entry, showPath = row.showPath, direct = row.direct,
                                onLongPress = { actionTarget = row.entry },
                                onNewSession = { newPathTarget = row.entry.path },
                            )
                        }
                    }
                }
            }
        }
    }
        ApprovalQueueFab(
            count = approvalCount,
            refreshing = approvalsRefreshing,
            onClick = onOpenInbox,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp),
        )
        actionTarget?.let { t -> ProjectActionsSheet(repo, t, onShare = { shareTarget = t }) { actionTarget = null } }
        if (showNewPath) NewPathSheet(
            // drilled into a folder → seed it as the parent so the user types only the new project's name (issue #7)
            parent = base.takeIf { it.length > 1 }, // seed the current location (root prefix or a drilled folder) so "type the rest of the path" is obvious (#32/#7)
            agent = repo.defaultAgent.value,
            mode = repo.defaultMode.value,
            onDismiss = { showNewPath = false },
            onOptions = { p -> showNewPath = false; newPathTarget = p },
        ) { p -> showNewPath = false; repo.openSession(p) } // one tap: start with the defaults right away
        // "open a project folder" (issue #152): browse the computer's home and start a session in ANY
        // existing directory — same two bottom actions as NewPathSheet (defaults chip → picker; primary →
        // open right away), and the manual sheet stays one tap away for off-home paths
        if (showDirPicker) DirectoryPickerSheet(
            repo,
            onDismiss = { showDirPicker = false },
            onTypePath = { showDirPicker = false; showNewPath = true },
            onOptions = { p -> showDirPicker = false; newPathTarget = p },
            onStart = { p -> showDirPicker = false; repo.openSession(p) },
        )
        // wants a different agent/mode for the new path → the standard picker, then open the session there
        newPathTarget?.let { path ->
            LaunchedEffect(path) { repo.fetchModels(AgentKind.CLAUDE) }
            StartSessionModeSheet(
                workdir = path,
                selected = repo.defaultMode.value,
                selectedNativeMode = repo.defaultPermissionMode.value,
                agent = repo.defaultAgent.value,
                computer = repo.paired.value?.displayName(),
                autoAvailable = repo.supportsPermissionMode(dev.ccpocket.protocol.CLAUDE_PERMISSION_MODE_AUTO),
                modelsFor = { a -> repo.newSessionModelChoices(a) },
                defaultModelFor = { a -> repo.defaultModelFor(a) },
                onAgentPicked = { a -> repo.fetchModels(a) },
                onPick = { m, a, native, model ->
                    newPathTarget = null
                    repo.setDefaultAgent(a)
                    repo.openSession(path, startMode = m, agent = a, startPermissionMode = native, startModel = model)
                },
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
    // ONE instance for the life of the screen, RESET BY VALUE per key — never re-minted. Keying the
    // remember itself handed every effect that had already captured the old instance a dead object: it
    // kept reading (and writing) a state nothing rendered from. That is what broke switching between
    // sessions (issue #165) — the transcript arrives via clear()+addAll(), which clamps the list back to
    // index 0, and the follow-the-stream effect that should have re-landed it was consulting the previous
    // conversation's pin. Entering a chat from the session list never hit it: that path remounts, so
    // there was only ever one instance.
    val pinned = remember { mutableStateOf(true) }
    LaunchedEffect(*resetKeys) { pinned.value = true }
    LaunchedEffect(listState, userGesturesOnly) {
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

/**
 * Scroll the transcript to its true end.
 *
 * Deliberately targets the last LIST item rather than `messages.lastIndex`: the chat list is not just the
 * messages. A paginated session puts an "earlier messages" loader at index 0, and a live turn appends
 * status rows (working / queued / no-response) after the last message — so the message index is short by
 * however many of those exist right now, and aiming at it parks the view a whole message above the end.
 * With a tall final message (a Bash block, a long reply) that reads as "it didn't scroll down" — which is
 * exactly how switching into a session with history looked (issue #165).
 *
 * The huge scrollOffset then lands at the bottom even when that last item is taller than the viewport.
 */
private suspend fun LazyListState.scrollToEnd() {
    val last = layoutInfo.totalItemsCount - 1
    if (last >= 0) scrollToItem(last, Int.MAX_VALUE)
}

/** Leaf recomposition scope for the keyboard-follow: reads the ime inset in composition (required on
 *  iOS) so the per-frame invalidation during the keyboard animation stays inside this empty leaf
 *  instead of re-executing the whole ChatScreen. ONE collector scrolls (no restart per frame). */
@Composable
private fun ImeFollower(listState: LazyListState, repo: PocketRepository, pinned: () -> Boolean) {
    val imeBottom = rememberUpdatedState(WindowInsets.ime.getBottom(LocalDensity.current))
    LaunchedEffect(listState) {
        snapshotFlow { imeBottom.value }.collect { bottom ->
            if (pinned() && bottom > 0 && repo.messages.isNotEmpty()) listState.scrollToEnd()
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

/** A project row: jumps into the live session (when [direct] and running) or opens its session list.
 *  [onNewSession] is the trailing ＋ (issue #199) — start a session in THIS project without first
 *  walking into its session list. Null hides it (a guest's shared row keeps its own layout). */
@Composable
private fun ProjectCell(
    repo: PocketRepository,
    e: DirectoryEntry,
    showPath: Boolean,
    direct: Boolean,
    onLongPress: (() -> Unit)? = null,
    onNewSession: (() -> Unit)? = null,
) {
    val sid = e.activeSessionId
    val pinned = repo.isPinned(e.path)
    when {
        // a guest's shared folder (issue #115) — neutral "Shared" pill + origin + "6d left"
        e.sharedBy != null -> SharedProjectCell(repo, e, onLongPress, onNewSession)
        direct && e.open && sid != null ->
            // the 历史 badge lists this project's sessions (issue #49) — the row itself keeps auto-resuming
            LiveProjectCell(e, pinned, onLongPress, onBrowse = { repo.listSessions(e.path) }, onNewSession = onNewSession) { repo.openProject(e) }
        else -> DirCell(e.name.ifBlank { e.path }, if (showPath) tilde(e.path) else null, indent = false, pinned = pinned, onLongPress = onLongPress, onNewSession = onNewSession) { repo.listSessions(e.path) }
    }
}

/** The ＋ that starts a session right where the project name is (issue #199): same terracotta as every
 *  other "new session" call to action, sized as a real 32dp touch target inside a dense list row. */
@Composable
private fun NewSessionGlyph(onClick: () -> Unit) {
    Icon(
        Icons.Rounded.Add, stringResource(Res.string.new_session_here), tint = Tok.accent,
        modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(7.dp),
    )
}

/** A guest's shared-folder row (issue #115): folder (mono) + the neutral hairline "Shared" pill,
 *  the "shared by <owner>" origin, and the remaining validity ("6d left"). Tap opens its sessions. */
@Composable
private fun SharedProjectCell(repo: PocketRepository, e: DirectoryEntry, onLongPress: (() -> Unit)?, onNewSession: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Tok.surface)
            .combinedClickable(onClick = { repo.openProject(e) }, onLongClick = onLongPress)
            .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = if (onNewSession != null) 6.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(e.name.ifBlank { e.path }, color = Tok.tx, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                SharedPill()
            }
            e.sharedBy?.let {
                Text(stringResource(Res.string.shared_by_caption, it), color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
            }
        }
        e.shareExpiresAt?.let { exp ->
            Spacer(Modifier.width(8.dp))
            Text(expiryLeftText(expiryLeft(exp, dev.ccpocket.app.epochMillis())), color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, maxLines = 1)
        }
        // a guest can start work in the folder they were given — same ＋ as any other project row
        onNewSession?.let { Spacer(Modifier.width(2.dp)); NewSessionGlyph(it) }
    }
}

/** Long-press a project → pin it to the top, or unpin it, or share it. Small sheet, mirrors the app's other actions. */
@Composable
private fun ProjectActionsSheet(repo: PocketRepository, e: DirectoryEntry, onShare: () -> Unit, onDismiss: () -> Unit) {
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
            // Share this folder… — owners only; a guest's shared row (sharedBy set) can't re-share the owner's machine.
            if (e.sharedBy == null) {
                Row(
                    Modifier.padding(top = 9.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface)
                        .clickable { onShare(); onDismiss() }.padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Rounded.Share, null, tint = Tok.accent, modifier = Modifier.size(18.dp))
                    Text(stringResource(Res.string.share_this_folder), color = Tok.accent, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** A small filled pin marking a project that's pinned to the top. */
@Composable
private fun PinGlyph() = Icon(Icons.Filled.PushPin, null, tint = Tok.accent, modifier = Modifier.size(13.dp))

/** The terracotta "history" pill shown on a dir/folder/leaf that has Claude history. (internal: the
 *  #152 DirectoryPicker stamps the same pill on browsed folders that are already projects.) */
@Composable
internal fun HistoryBadge(onClick: (() -> Unit)? = null) {
    val base = Modifier.clip(RoundedCornerShape(999.dp)).background(Tok.accent.copy(alpha = 0.14f))
    Text(
        stringResource(Res.string.history_badge), color = Tok.accent, fontSize = 10.5.sp,
        modifier = (if (onClick != null) base.clickable(onClick = onClick) else base).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * The gear, with an accent dot when this app or the connected daemon is behind (issue #200). Settings is
 * where the versions and the update instructions live, so the nudge rides the entry to them rather than
 * interrupting with a banner — the whole point of the issue is that people don't KNOW they're stale, not
 * that they need blocking. Nothing to show until a version-reporting daemon has said so.
 */
@Composable
private fun SettingsIconButton(repo: PocketRepository, size: Dp, onClick: () -> Unit) {
    IconButton(onClick, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Settings, stringResource(Res.string.settings_open), tint = Tok.tx2, modifier = Modifier.size(20.dp))
            if (repo.versionStatus.value.anyBehind) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-2).dp)
                        .size(7.dp).clip(CircleShape).background(Tok.accent),
                )
            }
        }
    }
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

/** Path breadcrumb shown when drilled into a subfolder: back ‹ + tappable mono segments (current bolded).
 *  (internal: the #152 DirectoryPicker renders the same crumb over its home-anchored browse.) */
@Composable
internal fun Breadcrumb(segs: List<String>, onUp: () -> Unit, onSegment: (Int) -> Unit) {
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
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Tok.muted, modifier = Modifier.size(18.dp))
    }
}

/** A project-leaf row in the tree — opens its session list (or jumps into the live session). */
@Composable
private fun LeafRow(e: DirectoryEntry, pinned: Boolean, onLongPress: (() -> Unit)?, onNewSession: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(start = 4.dp, end = if (onNewSession != null) 0.dp else 4.dp, top = 13.dp, bottom = 13.dp),
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
        onNewSession?.let { NewSessionGlyph(it) }
    }
}

@Composable
private fun DirCell(
    name: String,
    path: String?,
    indent: Boolean,
    pinned: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    onNewSession: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (indent) 16.dp else 0.dp)
            .clip(RoundedCornerShape(10.dp)).background(Tok.surface).combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = if (onNewSession != null) 6.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, color = Tok.tx, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1)
            if (path != null) Text(path, color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1)
        }
        if (pinned) PinGlyph()
        onNewSession?.let { Spacer(Modifier.width(4.dp)); NewSessionGlyph(it) }
    }
}

/** A live session row: the session title leads, the folder + branch demote to metadata — tap resumes it.
 *  [onBrowse] is the secondary affordance (issue #49): open the project's session LIST instead of the
 *  live session, so a running project's history stays reachable — the row tap only ever auto-resumes. */
@Composable
private fun LiveProjectCell(
    e: DirectoryEntry,
    pinned: Boolean,
    onLongPress: (() -> Unit)?,
    onBrowse: (() -> Unit)? = null,
    onNewSession: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Tok.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = if (onNewSession != null) 6.dp else 12.dp),
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
            // a running project still gets the ＋: "another session in here", not "resume that one"
            onNewSession?.let { Spacer(Modifier.width(2.dp)); NewSessionGlyph(it) }
        }
        Text(
            buildString {
                append(e.name)
                e.gitBranch?.let { append(" · ⑂ ").append(it) }
                // a bridge-opened session says so in the list (issue #91): the owner sees at a glance
                // that an IM bot, not a person, is driving it
                liveOrigin(e)?.let { append(" · via ").append(it) }
            },
            color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Removable filter chip pinned atop the Sessions list when a single agent is selected (issue #31). */
@Composable
private fun AgentFilterChip(filter: String, onClear: () -> Unit) {
    // one arm per non-"both" filter: opencode used to fall through to the Claude label + accent (mislabeled)
    val color = when (filter) {
        "codex" -> Tok.codex
        "opencode" -> Tok.opencode
        else -> Tok.accent
    }
    val label = stringResource(
        when (filter) {
            "codex" -> Res.string.af_codex_only
            "opencode" -> Res.string.af_opencode_only
            else -> Res.string.af_claude_only
        }
    )
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
internal fun SessionsScreen(repo: PocketRepository, onOpenInbox: () -> Unit = {}) { // internal: driven end-to-end by MobileNewSessionUiTest (demo mode)
    val dir = repo.sessionsDir.value ?: return
    var pickMode by remember { mutableStateOf(false) }
    // an open is in flight (the screen only switches once the daemon answers with the live convo).
    // Repo-owned so every entry point is guarded: entries disable — a double-tap can't open two fresh
    // sessions — and the repo clears it on SessionLive/PocketError (8s safety net).
    val starting = repo.opening.value
    var showHelp by remember { mutableStateOf(false) }
    if (showHelp) {
        HelpCenterScreen(HelpEntryPoint.SESSIONS, onBack = { showHelp = false })
        return
    }
    var showSettings by remember { mutableStateOf(false) }
    if (showSettings) { SettingsScreen(repo, onBack = { showSettings = false }); return } // full-screen, replaces this screen
    // the cross-project archive (issue #202) — a full-screen route like Help/Settings. It hangs off THIS
    // screen because archiveSupported arrives on the Sessions frame, so the capability is already known
    // here; there is no gating-order problem to solve.
    var showArchived by remember { mutableStateOf(false) }
    if (showArchived) { ArchivedSessionsScreen(repo, onBack = { showArchived = false }); return }
    // Session groups (issue #119). Membership + the group list are daemon-owned; these hold only the
    // transient UI: which manage-sheet/dialog is open, and (client-only) which sections are collapsed —
    // kept per group id and reset per project (keyed on [dir]), so folding a group doesn't leak across projects.
    var showNewGroup by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<SessionGroup?>(null) }
    var deleteTarget by remember { mutableStateOf<SessionGroup?>(null) }
    var manageTarget by remember { mutableStateOf<SessionGroup?>(null) }
    var moveTarget by remember { mutableStateOf<SessionSummary?>(null) }
    val collapsed = remember(dir) { mutableStateMapOf<String, Boolean>() }
    val approvalCount = repo.fleetAttention().size
    val approvalsRefreshing = repo.fleetMachines().any { it.pending > 0 && it.status != MachineStatus.ONLINE }
    // Mobile UI 2.0: the state a row may claim is decided once, by the pure mapper, from real facts only —
    // a fleet attention row's own sessionId/workdir plus its real ask's isQuestion. Sessions and Chat read
    // the same ladder, which is what keeps them from naming one session two different things in one frame.
    val attention = repo.fleetAttention().map { e ->
        SessionAttention(e.sessionId, e.workdir, isQuestion = repo.attentionAsk(e)?.isQuestion == true)
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // quiet utility row: everything stays reachable at 48dp, nothing competes with the title below
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton({ repo.backToDirectories() }, modifier = Modifier.size(Metric.touch)) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack, stringResource(Res.string.ses_projects),
                        tint = Tok.tx2, modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (repo.archiveSupported.value) {
                    IconButton({ showArchived = true }, modifier = Modifier.size(Metric.touch)) {
                        Icon(Icons.Outlined.Inventory2, stringResource(Res.string.archive_title), tint = Tok.tx2, modifier = Modifier.size(20.dp))
                    }
                }
                IconButton({ showHelp = true }, modifier = Modifier.size(Metric.touch)) {
                    Icon(Icons.AutoMirrored.Outlined.HelpOutline, stringResource(Res.string.support_title), tint = Tok.tx2, modifier = Modifier.size(20.dp))
                }
                SettingsIconButton(repo, size = Metric.touch) { showSettings = true }
            }
            SessionsContextHeader(
                machine = repo.paired.value?.displayName(),
                conn = when (repo.phase.value) {
                    ConnPhase.Ready -> ConnBadge.ONLINE
                    ConnPhase.Connecting, ConnPhase.Reconnecting -> ConnBadge.CONNECTING
                    else -> ConnBadge.OFFLINE
                },
                workdir = dir,
                modifier = Modifier.padding(top = 4.dp),
            )
            val af = repo.agentFilter.value
            val filtered = repo.sessions.filter {
                when (af) {
                    "claude" -> (it.agent ?: AgentKind.CLAUDE) == AgentKind.CLAUDE
                    "codex" -> it.agent == AgentKind.CODEX
                    "opencode" -> it.agent == AgentKind.OPENCODE
                    "kimi" -> it.agent == AgentKind.KIMI
                    else -> true
                }
            }
            val split = splitSessions(sessionRows(filtered, attention))
            // #202: the row menu is no longer gated on the project having groups — archive is available
            // regardless, so a project with no groups still long-presses.
            val grouped = repo.sessionGroups.isNotEmpty()
            val hasRowMenu = grouped || repo.archiveSupported.value
            Box(Modifier.weight(1f)) {
            PullToRefreshBox(isRefreshing = repo.sessionsRefreshing.value, onRefresh = { repo.refreshSessions() }, modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = Metric.gutter),
                contentPadding = PaddingValues(top = Metric.gapL, bottom = if (approvalCount > 0) 88.dp else Metric.gapL),
            ) {
                if (af != "both") item { Box(Modifier.padding(bottom = Metric.gap)) { AgentFilterChip(af) { repo.setAgentFilter("both") } } }
                if (filtered.isEmpty()) item { SessionsEmptyState() }
                // ── Active: everything that is not finished, flat. Group HEADERS are deliberately absent
                // here (a session that needs a decision is not filed away first), but each row keeps its
                // group membership — the long-press move/archive sheet is unchanged.
                if (split.active.isNotEmpty()) {
                    item(key = "hdr:active") { SessionSectionLabel(stringResource(Res.string.ses_active), Modifier.padding(bottom = 10.dp)) }
                    items(split.active, key = { "act:" + it.session.sessionId }) { row ->
                        Column {
                            Hairline()
                            SessionListRow(
                                row,
                                onOpen = { repo.openSession(dir, row.session.sessionId, title = row.session.title, agent = row.session.agent ?: AgentKind.CLAUDE) },
                                onLongPress = if (hasRowMenu) ({ moveTarget = row.session }) else null,
                            )
                        }
                    }
                }
                // ── Recent: the organisation half. Groups keep their headers here, including empty ones,
                // so a freshly created group stays visible and manageable (issue #119).
                if (split.recent.isNotEmpty() || repo.groupsSupported.value) {
                    item(key = "hdr:recent") {
                        SessionSectionLabel(
                            stringResource(Res.string.ses_recent),
                            Modifier.padding(top = if (split.active.isEmpty()) 0.dp else 22.dp, bottom = 10.dp),
                        )
                    }
                }
                // The "+ New group" affordance shows whenever the daemon is group-aware (groupsSupported) —
                // including zero groups yet, so the FIRST group is creatable — but hides on an older daemon /
                // guest connection that omits groups entirely (sessionSections then returns one flat section).
                if (repo.groupsSupported.value) item { NewGroupRow { showNewGroup = true } }
                for (section in sessionSections(split.recent.map { it.session }, repo.sessionGroups)) {
                    val g = section.group
                    val key = g?.id ?: UNGROUPED_KEY
                    val isCollapsed = collapsed[key] == true
                    if (grouped) {
                        item(key = "grp:$key") {
                            GroupHeader(
                                name = g?.name ?: stringResource(Res.string.group_ungrouped),
                                // rows filed HERE, not the group's whole membership: this header is a
                                // collapse control, so its number has to match what folding it hides —
                                // a member currently in Active is listed above, under its own state
                                count = section.sessions.size,
                                collapsed = isCollapsed,
                                onToggle = { collapsed[key] = !isCollapsed },
                                onManage = g?.let { { manageTarget = it } }, // ungrouped bucket: nothing to manage
                            )
                        }
                    }
                    if (!isCollapsed) {
                        items(section.sessions, key = { it.sessionId }) { s ->
                            Column {
                                Hairline()
                                SessionListRow(
                                    SessionRowUi(s, SurfaceState.COMPLETE), // by construction: this half IS the settled one
                                    onOpen = { repo.openSession(dir, s.sessionId, title = s.title, agent = s.agent ?: AgentKind.CLAUDE) },
                                    onLongPress = if (hasRowMenu) ({ moveTarget = s }) else null,
                                )
                            }
                        }
                    }
                }
            }
            }
            ApprovalQueueFab(
                count = approvalCount,
                refreshing = approvalsRefreshing,
                onClick = onOpenInbox,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp),
            )
            // #202: the archive receipt. The row it refers to has already vanished from this list, so the
            // toast is the only thing that says where it went — and its action is the reverse verb, not Undo.
            ArchiveToastBar(repo, Modifier.align(Alignment.BottomCenter).padding(bottom = if (approvalCount > 0) 88.dp else 12.dp))
            }
            // one tap starts right away with the persisted defaults (openSession's own fallbacks); the
            // trailing chip shows those defaults and opens the full agent+mode picker instead
            NewSessionDock(starting = starting, onStart = { repo.openSession(dir) }) {
                SessionDefaultsChip(repo.defaultAgent.value, repo.defaultMode.value, enabled = !starting) { pickMode = true }
            }
        }
        if (pickMode) {
            LaunchedEffect(Unit) { repo.fetchModels(AgentKind.CLAUDE) }
            StartSessionModeSheet(
                workdir = dir,
                selected = repo.defaultMode.value,
                selectedNativeMode = repo.defaultPermissionMode.value,
                agent = repo.defaultAgent.value,
                computer = repo.paired.value?.displayName(),
                autoAvailable = repo.supportsPermissionMode(dev.ccpocket.protocol.CLAUDE_PERMISSION_MODE_AUTO),
                modelsFor = { a -> repo.newSessionModelChoices(a) },
                defaultModelFor = { a -> repo.defaultModelFor(a) },
                onAgentPicked = { a -> repo.fetchModels(a) },
                onPick = { m, a, native, model ->
                    pickMode = false
                    repo.setDefaultAgent(a)
                    repo.openSession(dir, startMode = m, agent = a, startPermissionMode = native, startModel = model)
                },
                onDismiss = { pickMode = false },
            )
        }
        // issue #119 group management — the daemon re-pushes the Sessions frame after every mutation, so the
        // list/headers refresh themselves (no optimistic edit here).
        if (showNewGroup) NewGroupDialog(onConfirm = { repo.createGroup(it) }, onDismiss = { showNewGroup = false })
        manageTarget?.let { g ->
            GroupActionsSheet(
                group = g,
                onRename = { renameTarget = g },
                onDelete = { deleteTarget = g },
                onDismiss = { manageTarget = null },
            )
        }
        renameTarget?.let { g ->
            RenameGroupDialog(group = g, onConfirm = { repo.renameGroup(g.id, it) }, onDismiss = { renameTarget = null })
        }
        deleteTarget?.let { g ->
            DeleteGroupConfirm(group = g, onConfirm = { repo.deleteGroup(g.id) }, onDismiss = { deleteTarget = null })
        }
        moveTarget?.let { s ->
            MoveSessionSheet(
                session = s,
                groups = repo.sessionGroups,
                onAssign = { repo.assignGroup(s.sessionId, it) },
                onArchive = if (repo.archiveSupported.value) ({
                    repo.setSessionArchived(
                        dir, s.sessionId, archived = true,
                        title = s.title, running = s.live || s.busy,
                    )
                }) else null,
                onDismiss = { moveTarget = null },
            )
        }
    }
}

@Composable
internal fun ChatScreen( // internal: rendered offscreen by ShowcaseRender (marketing frames), same precedent as SessionsScreen
    repo: PocketRepository,
    onOpenFleet: () -> Unit = {},
    onOpenInbox: () -> Unit = {},
    // injectable so a test can assert where the transcript actually PARKED — "is the last line on screen"
    // is not a usable proxy (a roomy test scene shows the whole transcript either way), and that blind
    // spot is how the switcher shipped opening mid-transcript twice (issue #165)
    listStateForTest: LazyListState? = null,
) {
    // Restore the composer draft (keyed per conversation, workdir for a brand-new session). Re-inits on a
    // REAL switch only — keyed off composerEpoch, NOT draftKey (#29 semantics kept): the key chain flips in
    // place mid-typing (brand-new session materializing, forked resume corrected by SessionLive), and
    // re-reading the ≤400ms-stale draft then yanked the live text out from under the IME — on the iOS pinyin
    // keyboard that committed the space-segmented marked text as raw letters, "claude"→"c l a u d e" (#108,
    // #93's wild signature). The debounced saver below re-homes the text under the flipped key.
    val draftKey = repo.composerKey()
    val composer = remember(repo.composerEpoch.value) { ComposerState(repo.draftFor(draftKey)) }
    val input = composer.text // reads track the field; writes go through composer's explicit methods
    var viewer by remember { mutableStateOf<Pair<List<ByteArray>, Int>?>(null) } // tapped sent images → full-screen
    var videoViewer by remember { mutableStateOf<dev.ccpocket.app.data.SentFile?>(null) } // tapped sent video → player (issue #98)
    var showSwitcher by remember { mutableStateOf(false) } // machine name in the connection bar → switch computer
    var showSessions by remember { mutableStateOf(false) } // stack chip → cross-project session switcher (issue #165)
    var showModeSheet by remember { mutableStateOf(false) }
    var showSessionInfo by remember { mutableStateOf(false) }
    // header context disclosure (Mobile UI 2.0): collapsed by default so the stream keeps the viewport;
    // per conversation, so switching sessions never lands you inside the previous one's expanded context
    var contextExpanded by remember(repo.convoId.value) { mutableStateOf(false) }
    var showQuickActions by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) } // composer model chip → the picker, one tap (issue #157)
    var showBgJobs by remember { mutableStateOf(false) }
    var showTerminal by remember { mutableStateOf(false) }
    var showChangedFiles by remember { mutableStateOf(false) }
    var showScheduleSheet by remember { mutableStateOf(false) } // send long-press → schedule send (issue #137)
    // ── session handoff (SESSION-HANDOFF.md; design session-handoff/) ──
    var showHandoffDraft by remember { mutableStateOf(false) }
    var showHandoffReturn by remember { mutableStateOf(false) }
    var showHandoffAccept by remember { mutableStateOf(false) }
    var showConnectColleague by remember { mutableStateOf(false) } // contacts Frame 3: reached from the picker
    val activeHandoff = repo.activeHandoff.value
    val hoStatus = activeHandoff?.status
    val hoIsRecipient = activeHandoff?.let { repo.isHandoffRecipient(it) } == true
    LaunchedEffect(repo.convoId.value) { if (repo.convoId.value != null) repo.listHandoffs() }
    // a fresh invite closes the draft sheet and opens the invite sheet (lastHandoffInvite drives it)
    LaunchedEffect(repo.lastHandoffInvite.value) { if (repo.lastHandoffInvite.value != null) showHandoffDraft = false }
    // the recipient's return lands → their sheet closes on the daemon's state flip
    LaunchedEffect(hoStatus) { if (hoStatus != HandoffStatus.IN_PROGRESS) showHandoffReturn = false }
    // live second ticker only while a handoff needs a countdown/elapsed readout on screen
    var hoNow by remember { mutableStateOf(epochMillis()) }
    LaunchedEffect(hoStatus) {
        if (hoStatus == HandoffStatus.WAITING || hoStatus == HandoffStatus.IN_PROGRESS) {
            while (true) { delay(1000); hoNow = epochMillis() }
        }
    }
    var showHelp by remember { mutableStateOf(false) }
    if (showHelp) {
        HelpCenterScreen(
            entryPoint = HelpEntryPoint.CHAT,
            onBack = { showHelp = false },
            onOpenChanges = {
                showHelp = false
                repo.fetchChangedFiles()
                showChangedFiles = true
            },
            supportContext = SupportContext(
                screen = "chat",
                platform = supportPlatformLabel(),
                appVersion = APP_VERSION,
                agent = (repo.sessionAgent.value ?: AgentKind.CLAUDE).name.lowercase(),
                model = repo.model.value?.take(96),
                state = when {
                    !repo.connected.value -> "disconnected"
                    repo.streaming.value -> "generating"
                    repo.observing.value -> "observing"
                    else -> "idle"
                },
                controls = listOf("composer", "quick_actions", "changed_files", "terminal", "model_picker"),
            ),
        )
        return
    }
    // Connect a colleague (contacts Frame 3): full-screen; success returns to the interrupted draft
    if (showConnectColleague) {
        ConnectColleagueFlow(
            repo, fromDraft = true,
            onBackToHandoff = { showConnectColleague = false; showHandoffDraft = true },
            onClose = { showConnectColleague = false },
        )
        return
    }
    // Recipient trust screen (design Frames 4/4b): full-screen over the chat, same early-return pattern
    if (showHandoffAccept && activeHandoff != null) {
        val expired = activeHandoff.status != HandoffStatus.WAITING
        HandoffAcceptScreen(
            ownerLabel = activeHandoff.initiatorLabel ?: repo.paired.value?.displayName() ?: "?",
            sessionTitle = repo.chatTitle.value ?: stringResource(Res.string.chat_title),
            path = activeHandoff.workdir.ifBlank { repo.workdir.value ?: "" },
            branch = null,
            returnsIn = "2h",
            roots = listOf(activeHandoff.workdir.ifBlank { repo.workdir.value ?: "" }),
            briefSections = activeHandoff.brief.toSections(),
            expiredNote = if (expired) activeHandoff.shortCode() else null,
            // §3.2.7: the screen closes on DAEMON truth (the handoff leaves WAITING), not on the tap —
            // an accept that loses the race or hits an old daemon states so instead of vanishing
            accepting = repo.handoffAccepting.value == activeHandoff.id,
            onAccept = { repo.acceptHandoff(activeHandoff.id) },
            onDecline = { repo.declineHandoff(activeHandoff.id); showHandoffAccept = false },
            onClose = { showHandoffAccept = false },
            kind = activeHandoff.kind,     // §6: rendered from the daemon's grant, never hardcoded
            access = activeHandoff.access,
            errorNote = repo.handoffAcceptError.value?.let { stringResource(it) } ?: repo.handoffUnsupported.value,
        )
        LaunchedEffect(activeHandoff.status) {
            if (activeHandoff.status == HandoffStatus.IN_PROGRESS) showHandoffAccept = false
        }
        return
    }
    if (showTerminal) { TerminalScreen(repo) { showTerminal = false }; return } // full-screen, replaces chat (issue #3)
    if (repo.viewedFilePath.value != null) { // changed-file viewer (issue #36); back → the still-open files list, ✕ → chat (issue #53)
        FileViewerScreen(repo, onExit = if (showChangedFiles) ({ repo.closeFileViewer(); showChangedFiles = false }) else null) { repo.closeFileViewer() }
        return
    }
    if (repo.viewedWorkflowRunId.value != null) { // workflow run view (issue #106): full-screen tree/journal over the chat
        WorkflowRunScreen(repo) { repo.closeWorkflow() }
        return
    }
    // platform picker resizes/compresses on-device; the repo budgets the picked photos against the 256 KiB frame
    val launchPicker = rememberImageAttacher { added -> repo.attachImages(added) }
    val launchFilePicker = rememberFileAttacher { picked -> repo.attachFiles(picked) } // issue #90
    val launchVideoPicker = rememberVideoAttacher { picked -> repo.attachFiles(picked) } // issue #98 — same upload path, movie-filtered
    var attachSheet by remember { mutableStateOf(false) } // Photo/File/Video chooser anchored above the composer
    val listState = listStateForTest ?: rememberLazyListState()
    // stick to the bottom only while the user is there ("pinned"); scrolling up unpins and shows
    // the Jump-to-latest pill instead of yanking the viewport down on every streamed chunk.
    // Keyed on the conversation (as the desktop pane always was): the switcher (#165) made chat→chat
    // possible without remounting, so an un-keyed pinned carried the PREVIOUS session's "user scrolled
    // up" over to the next one — which then opened parked mid-transcript instead of at the latest.
    var pinned by rememberBottomPinned(listState, repo.convoId.value)
    // the Jump-to-latest scroll must survive the pill leaving composition. The pill's onClick sets
    // pinned=true, and that same recomposition removes the `if (!pinned)` block below — a
    // rememberCoroutineScope declared INSIDE that block is cancelled the instant it's forgotten,
    // killing the launched animateScrollToItem before it can run (tap → pill vanishes, list never
    // reaches the bottom). Hoisting the scope to ChatScreen lets the animation complete.
    val jumpScope = rememberCoroutineScope()
    // keep the message list hidden until it's first parked at the bottom, so opening a session with
    // history doesn't flash the top then visibly scroll down. Resets per session (convoId); a short
    // grace reveals an empty/new session that has no history to position on.
    var landed by remember(repo.convoId.value) { mutableStateOf(false) }
    LaunchedEffect(repo.convoId.value) { delay(180); landed = true }
    // A conversation you switch INTO opens at its latest message (issue #165). Resetting `pinned` per
    // conversation is not enough on its own: the follow-the-stream effect below keys on the MESSAGE list,
    // so whether it re-runs late enough to observe the freshly reset pin is a race — and when it lost,
    // the session opened parked wherever the previous one had been scrolled to. This lands it outright,
    // keyed on the conversation and waiting for its transcript, so it cannot depend on that ordering.
    LaunchedEffect(repo.convoId.value) {
        if (repo.convoId.value == null) return@LaunchedEffect
        // wait for the transcript AND for the list to have measured it — the target below is read from
        // layout, so scrolling before the new content is laid out would aim at the previous session's
        snapshotFlow { repo.messages.size to listState.layoutInfo.totalItemsCount }.first { (m, t) -> m > 0 && t > 0 }
        listState.scrollToEnd()
        pinned = true // …and it follows the stream from here, as a freshly opened session always has
        landed = true
    }
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
    // Voice result review (issue #221): a finished transcript no longer auto-sends — it lands in the
    // composer for the user to confirm/fix first. Append it AFTER any existing draft (one space between
    // when the draft doesn't already end on whitespace), route through ComposerState.setText so the caret
    // lands at the end and a live IME composition is respected (#93/#118), then focus + raise the keyboard
    // for immediate edits. Consume the slot so the same phrase spoken twice still re-fires.
    val pendingVoice = repo.pendingVoiceText.value
    LaunchedEffect(pendingVoice) {
        if (pendingVoice != null) {
            repo.pendingVoiceText.value = null
            val existing = composer.text
            composer.setText(
                when {
                    existing.isEmpty() -> pendingVoice
                    existing.last().isWhitespace() -> existing + pendingVoice
                    else -> "$existing $pendingVoice"
                },
            )
            runCatching { composerFocus.requestFocus() }
            keyboard?.show()
        }
    }
    // Page in older history when the reader is genuinely parked at the top of the loaded window — NOT
    // when the loader row merely composes. Every transcript lands via clear()+addAll(), which clamps the
    // list to index 0, so composition fired on each history frame; each page prepended rows and clamped
    // again, paging the entire session in while the view fought to stay at the bottom (issue #165).
    // "Parked at the top" means: at index 0, and either the reader scrolled away from the bottom to get
    // there, or the window is too short to scroll at all (where there is no other way to ask).
    LaunchedEffect(repo.convoId.value) {
        snapshotFlow {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 &&
                (!pinned || !listState.canScrollForward)
        }.collect { atTop ->
            if (atTop && landed && repo.historyHasMore.value) repo.loadOlderHistory()
        }
    }
    // persist the composer draft per project (debounced) so leaving mid-message doesn't lose it
    LaunchedEffect(input, draftKey) { delay(400); repo.saveDraft(draftKey, input) }
    // a huge scrollOffset lands at the bottom even when the last message is taller than the viewport
    LaunchedEffect(repo.messages.size, repo.messages.lastOrNull(), repo.streaming.value) {
        if (pinned && repo.messages.isNotEmpty()) { listState.scrollToEnd(); landed = true }
    }
    // keyboard-follow lives in its own leaf composable: the ime inset must be a COMPOSITION read
    // (iOS misses the animation otherwise), and reading it here would re-execute all of ChatScreen
    // every animation frame — the leaf confines that per-frame invalidation to itself.
    ImeFollower(listState, repo) { pinned }
    val focus = LocalFocusManager.current
    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) focus.clearFocus() } // scrolling dismisses the keyboard
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // ── header (Mobile UI 2.0 · Core frame 02) ──────────────────────────────────────────────
            // The title leads and may wrap; everything that used to be crammed onto one mono line is now a
            // collapsible context region. Only facts the daemon actually supplied appear — no branch (it is
            // per-session truth, not a project fact), no timestamp, no duration.
            val machineName = repo.paired.value?.displayName()
            val modelLabel = modelLabelForAgent(repo.sessionAgent.value, repo.model.value)
            val switchLabel = stringResource(Res.string.switcher_open)
            val contextLines = buildList {
                add(ContextLine(agentName(repo.sessionAgent.value ?: AgentKind.CLAUDE)))
                add(
                    ContextLine(
                        stringResource(
                            if (repo.permissionMode.value == dev.ccpocket.protocol.CLAUDE_PERMISSION_MODE_AUTO) AUTO_MODE.short
                            else MODE_BY[repo.mode.value]?.short ?: MODES[0].short,
                        ),
                    ),
                )
                // which computer this conversation lives on — still its own control: switch machines
                // without leaving the chat
                machineName?.let { add(ContextLine(it, onClick = { showSwitcher = true }, clickLabel = switchLabel)) }
                repo.workdir.value?.let { add(ContextLine(folderName(it))) }
                // the effective model, only once it is known. A pre-first-turn session (lazy start #61)
                // simply has no model line until the first turn's init names it (issue #96).
                modelLabel.takeIf { it.isNotBlank() }?.let { add(ContextLine(it)) }
                // external trigger source (issue #91): a bridge-opened session says so — the owner should
                // know an IM bot, not a person, is driving this conversation
                repo.sessionOrigin.value?.let { add(ContextLine("via $it")) }
            }
            ChatHeader(
                title = repo.chatTitle.value ?: stringResource(Res.string.chat_title),
                summary = contextLines,
                workdir = repo.workdir.value,
                expanded = contextExpanded,
                onToggleContext = { contextExpanded = !contextExpanded },
                onBack = { repo.saveDraft(repo.workdir.value, composer.text); repo.backToBrowse() },
                onSessionInfo = { showSessionInfo = true },
            ) {
                if (!repo.observing.value) {
                    // handoff status chip (design 3b/7/9): WAITING mute · IN PROGRESS pulse · RETURNED green.
                    // On a device that ISN'T the initiator, tapping a WAITING chip opens the accept preview.
                    activeHandoff?.status?.toUi()?.let { st ->
                        Box(
                            Modifier.padding(end = 6.dp).clip(RoundedCornerShape(6.dp)).clickable {
                                if (st == HandoffUiStatus.WAITING && !repo.isHandoffInitiator(activeHandoff)) showHandoffAccept = true
                                else showSessionInfo = true
                            },
                        ) { HandoffStatusChip(st) }
                    }
                    // the streaming chip is gone from here: execution state (issue #52) is now the pinned
                    // state block below, which states it in words instead of as one more header badge
                    Box(
                        Modifier.size(Metric.touch).clip(CircleShape).clickable { showQuickActions = true },
                        contentAlignment = Alignment.Center,
                    ) { Text("⋯", color = Tok.tx2, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                }
            }
            // ── the one pinned state, chosen by the shared ladder from real facts only ────────────────
            // Approval/Answer lead; a streaming turn under them is demoted to a qualifying line. The block
            // is actionless by design — Secure Approval (modal) and QuestionCard own their decisions.
            chatStateUi(repo.pendingAsk.value, repo.sessionDegraded.value, repo.streaming.value)
                ?.let { ChatStateBlock(it) }
            // Role ribbon (design Frames 6/7): terracotta only when THIS device is the acting recipient;
            // the spectating initiator gets the neutral strip. The trailing readout is elapsed-in-control.
            if (hoStatus == HandoffStatus.IN_PROGRESS && activeHandoff != null) {
                val elapsed = elapsedLabel(activeHandoff.acceptedAt, hoNow)
                val ownerLabel = activeHandoff.initiatorLabel ?: repo.paired.value?.displayName() ?: "?"
                val recipientLabel = activeHandoff.recipientLabel ?: "?"
                if (hoIsRecipient) HandoffRibbon(
                    accent = true,
                    text = stringResource(Res.string.ho_continuing, ownerLabel),
                    countdown = elapsed,
                    avatars = { HandoffAvatarPair(ownerLabel, recipientLabel, Tok.accent) },
                ) else HandoffRibbon(
                    accent = false,
                    text = stringResource(Res.string.ho_spectating, recipientLabel),
                    countdown = elapsed,
                    avatars = { HandoffAvatar(recipientLabel, accent = true) },
                )
                // §2.2: the execution page states the REAL boundary next to the ribbon. A bare "read-only
                // tools" note here would contradict the Bash approvals this same screen is about to show.
                if (activeHandoff.access == dev.ccpocket.protocol.HandoffAccess.REVIEW_READ_ONLY) {
                    Text(
                        stringResource(Res.string.ho_exec_note), color = Tok.muted, fontSize = 11.sp, lineHeight = 15.sp,
                        modifier = Modifier.fillMaxWidth().background(if (hoIsRecipient) Tok.accent.copy(alpha = 0.06f) else Tok.surface)
                            .padding(horizontal = 14.dp, vertical = 5.dp),
                    )
                }
            }
            Box(Modifier.weight(1f)) {
                // plain breathing room under the last message. This used to be a MEASURED reserve for the
                // floating context pill, which covered the last line + its copy button (issues #15, #81);
                // the pill now lives inline on the accessory row, so nothing hovers here and the gutter
                // goes back to being a constant.
                val bottomGutter = 24.dp
                // older-history lazy load (issue #147): a prepended page shifts every index — scroll by
                // the prepend count (+ the loader row when it stays) so the viewport keeps the row the
                // user was reading instead of jumping to the newly loaded region. Visuals per the 0714
                // chat-components handoff (B): the loader lingers through its silent-failure fade, and
                // a landed page marks the seam above the re-anchored row for a beat.
                val historyLoaderVisible = rememberEarlierLoaderVisible(repo.historyHasMore.value)
                val historySeamAt = rememberHistorySeam(repo.historyPrependGen.value, repo.lastHistoryPrependCount)
                LaunchedEffect(repo.historyPrependGen.value) {
                    val n = repo.lastHistoryPrependCount
                    if (repo.historyPrependGen.value > 0 && n > 0) {
                        listState.scrollToItem(n + (if (historyLoaderVisible) 1 else 0))
                    }
                }
                // read-doc-inline handoff: give the mobile transcript a PathOpener so file paths in
                // assistant markdown / tool cards become tappable — the phone can't stat a local disk, so
                // this opener is optimistic (every regex-matched path lights up) and hands taps to the
                // daemon read, which opens the same full-screen viewer the changed-files list uses.
                val pathOpener = remember(repo) { RemotePathOpener { repo.openChangedFile(it) } }
                CompositionLocalProvider(LocalPathCwd provides repo.workdir.value, LocalPathOpener provides pathOpener) {
                LazyColumn(
                    Modifier.fillMaxSize().padding(16.dp).graphicsLayer { alpha = if (landed) 1f else 0f }
                        .pointerInput(Unit) { detectTapGestures { focus.clearFocus() } },
                    state = listState, verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = bottomGutter),
                ) {
                    // scroll-to-top loader (issue #147). The REQUEST no longer rides this row's composition
                    // (see the effect above ChatScreen's list): a transcript lands through clear()+addAll(),
                    // which clamps the list to index 0 for a beat, so "the row composed" fired on every
                    // history frame and not on reaching the top. Each spurious page prepended more rows,
                    // clamped again, and paged again — a self-driving loop that walked the whole history
                    // while the view fought to stay at the bottom, which is what "switching doesn't land at
                    // the latest message" actually was (issue #165). The row is now purely the indicator:
                    // an ambient status line, never a button (0714 handoff B1); a dead request fades out
                    // silently instead of snapping away (B2).
                    if (historyLoaderVisible) item(key = "history-loader") {
                        LoadEarlierRow(fading = !repo.historyHasMore.value)
                    }
                    itemsIndexed(repo.messages) { mi, m ->
                        // a prompt the daemon hasn't acknowledged while the link is down — or while the link
                        // CLAIMS up but receipts stalled past the deadline (issue #78, multi-computer links):
                        // say so under the bubble instead of letting it look sent (issue #41 — frames queue
                        // silently offline)
                        val undelivered = m is ChatItem.User && m.pending && (repo.phase.value != ConnPhase.Ready || repo.sendStalled.value)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            // seam (0714 handoff B3): for a beat after a page of older history lands,
                            // mark where the old window began so the reader keeps their place
                            if (mi == historySeamAt) EarlierMessagesSeam(repo.historyPrependGen.value)
                            MessageItem(
                                m,
                                workflowRun = (m as? ChatItem.Tool)?.let(repo::workflowFor),
                                agent = repo.sessionAgent.value,
                                onOpenWorkflow = repo::openWorkflow,
                                onOpenImages = { imgs, i -> viewer = imgs to i },
                                onOpenVideo = { videoViewer = it },
                                onTightenAutoRun = repo::tightenAutoRun,
                            )
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
                    when {
                        // delivered, but the agent produced no turn within the deadline (issue #104): the prompt
                        // was swallowed (wedged / mid-relaunch). Offer a resend instead of an endless spinner.
                        repo.turnStalled.value -> item { NoResponseRow { repo.resendStalledPrompt() } }
                        // sent mid-turn and the running turn has gone quiet: the prompt is queued, not swallowed
                        repo.turnQueued.value -> item { QueuedRow() }
                        repo.streaming.value -> item { if (liveContent) PulseDot(Tok.accent) else WorkingRow() }
                    }
                }
                }
                if (!pinned) {
                    JumpToLatestPill(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)) {
                        pinned = true
                        jumpScope.launch {
                            if (repo.messages.isNotEmpty()) listState.animateScrollToItem(repo.messages.lastIndex, Int.MAX_VALUE)
                        }
                    }
                }
                // approvals waiting on OTHER machines pull you over without reflowing this stream —
                // floats under the connection bar; this machine's own ask keeps its sheet (Fleet ⑤)
                dev.ccpocket.app.ui.fleet.CrossMachineBanner(
                    repo.crossMachineAttention(),
                    onReview = onOpenInbox,
                    modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 12.dp).padding(top = 8.dp),
                )
            }
            // session health (issue #65) no longer gets its own strip here: `sessionDegraded` is the
            // Failure rung of the shared state ladder, so the pinned block above states it once, in the
            // same grammar as every other state, and stays on screen for the whole session — the warning
            // still precedes the next prompt without the same fact being drawn twice.
            // RETURNED result card (design Frame 9): docks above the composer until the initiator
            // acknowledges — "Mark reviewed" is the only transition to COMPLETED.
            val hoResult = activeHandoff?.result
            if (hoStatus == HandoffStatus.RETURNED && activeHandoff != null && hoResult != null) {
                HandoffResultCard(
                    hoResult.toUi(activeHandoff.recipientLabel ?: "?", elapsedLabel(activeHandoff.returnedAt, hoNow)),
                    onMarkReviewed = { repo.completeHandoff(activeHandoff.id) },
                    onOpenFull = { showSessionInfo = true },
                    modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp),
                )
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
            } else if (hoStatus == HandoffStatus.WAITING && activeHandoff != null) {
                // WAITING lock (design Frame 3b): a pinned banner + the composer dimmed-but-present,
                // so the return to normal is obvious. Neutral — nothing is running yet.
                val hoClipboard = LocalClipboardManager.current
                Column(Modifier.fillMaxWidth().background(Tok.base)) {
                    // Contacts Frame 8 delta: a recipient-bound offer has no code to show — the mono
                    // line states delivery honestly ("notified", never "seen"/"online").
                    val direct = activeHandoff.recipientDeviceId != null
                    HandoffLockBanner(
                        recipientLabel = activeHandoff.recipientLabel ?: "?",
                        metaLine = if (direct)
                            stringResource(Res.string.ho_notified_line, activeHandoff.recipientLabel ?: "?", activeHandoff.expiresCountdown(hoNow))
                        else
                            // §6: the recap names THIS grant, not a hardcoded "Review · Read-only"
                            "${dev.ccpocket.app.ui.handoff.kindChip(activeHandoff.kind)} · ${dev.ccpocket.app.ui.handoff.accessChip(activeHandoff.access)} · ${activeHandoff.shortCode()} · ${activeHandoff.expiresCountdown(hoNow)}",
                        onCopyInvite = { hoClipboard.setText(AnnotatedString(activeHandoff.inviteBlob())) },
                        onRecall = { repo.cancelHandoff(activeHandoff.id) },
                        onViewInvite = if (repo.isHandoffInitiator(activeHandoff)) null else ({ showHandoffAccept = true }),
                        directDelivery = direct,
                        modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp),
                    )
                    HandoffLockedComposer()
                }
            } else if (hoStatus == HandoffStatus.IN_PROGRESS && activeHandoff != null && !hoIsRecipient) {
                // spectating (design Frame 7): the composer is REPLACED — no draft to preserve, and the
                // bar carries the one escape hatch
                HandoffWatchBar(onRecall = { repo.recallHandoff(activeHandoff.id) })
            } else if (repo.observing.value) {
                Row(Modifier.fillMaxWidth().background(Tok.surface).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.observing_notice), color = Tok.tx2, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Button({ repo.takeOver() }) { Text(stringResource(Res.string.continue_here)) }
                }
            } else {
                val hasReady = repo.hasReadyImages()
                val hasLanded = repo.hasLandedFiles()      // files already in the workspace inbox (issue #90)
                val uploadsBusy = repo.uploadsBusy()       // uploads still moving → send waits
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
                // Recipient in control (design Frame 6): the prominent return affordance rides above
                // the (fully active) composer — finishing is the recipient's primary next action.
                if (hoStatus == HandoffStatus.IN_PROGRESS && hoIsRecipient) {
                    Box(Modifier.fillMaxWidth().background(Tok.base).padding(horizontal = 12.dp).padding(bottom = 10.dp)) {
                        HandoffFinishReturnButton({ showHandoffReturn = true })
                    }
                }
                Column(Modifier.fillMaxWidth().background(Tok.surface)) {
                    LimitResetBanner(repo) // usage-limit hit → one-tap "auto-continue after reset" (issue #137)
                    BackgroundJobsStrip(repo.backgroundJobs) { showBgJobs = true } // ≥1 running bg task → tap to expand
                    val capturing = voiceState is VoiceState.Recording || voiceState is VoiceState.Transcribing
                    LaunchedEffect(capturing) { if (capturing) attachSheet = false }
                    if (suggestions.isNotEmpty() && !capturing) {
                        SlashCommandMenu(suggestions) { cmd -> composer.setText(cmd.completion()) }
                    } else if (atFileMatches.isNotEmpty() && !capturing) {
                        FileCompletionMenu(
                            atFileMatches, atDir, atSep,
                            // issue #133: the quiet eye on a file row opens it in the viewer (the daemon
                            // now serves any path inside the session's project tree, not just changed ones)
                            onView = { entry -> repo.openChangedFile((if (atDir.isEmpty()) "" else atDir + atSep) + entry.name) },
                        ) { entry ->
                            atToken?.let { composer.setText(input.substring(0, it.at + 1) + atInsertText(atDir, entry, atSep) + input.substring(it.end)) }
                        }
                    }
                    // attach sheet (issue #90/#98): Photo keeps the image flow, File opens the document
                    // picker, Video opens the movie-filtered picker (same chunk-upload into the workspace)
                    if (attachSheet && !capturing) {
                        AttachSheet(
                            onPhoto = { attachSheet = false; launchPicker() },
                            onFile = { attachSheet = false; launchFilePicker() },
                            onVideo = { attachSheet = false; launchVideoPicker() },
                        )
                    }
                    PendingFilesStrip(repo.pendingFiles, onCancel = repo::removePendingFile, onRetry = repo::retryPendingFile)
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
                        // Two-layer composer (issue #157 follow-up, design: mobile-composer.jsx): the field
                        // owns the full width on top; attach + model chip + the action slot live on an
                        // accessory row below — the chip no longer squeezes what you type on narrow phones.
                        Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp)) {
                            // all that survives of the old full-width amber strip: one slim line, and only
                            // once turns are actually about to drop (design: context-occupancy.jsx)
                            val ctxUsed = repo.contextUsed.value
                            val ctxWindow = repo.contextWindow.value
                            if (ctxUsed != null && ctxWindow != null && ctxWindow > 0L &&
                                ctxUsed.toFloat() / ctxWindow >= CONTEXT_CRITICAL_AT
                            ) {
                                ContextCriticalCaption()
                            }
                            ComposerField(
                                composer,
                                // mid-turn the field stays enabled (sends queue into the running turn) — say so,
                                // or an editable composer under a "running" session reads as disconnected (issue #52)
                                placeholder = stringResource(
                                    when {
                                        repo.pendingImages.isNotEmpty() || repo.pendingFiles.isNotEmpty() -> Res.string.add_message_hint
                                        repo.streaming.value -> Res.string.message_queued_hint
                                        else -> Res.string.message_claude_hint
                                    },
                                ),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                focusRequester = composerFocus,
                            )
                            Row(
                                // start 8 / end 10: the 44dp targets carry their own inner padding, so the
                                // glyphs sit optically on the field's 16dp edge (design values)
                                Modifier.fillMaxWidth().padding(start = 8.dp, end = 10.dp, top = 6.dp).heightIn(min = 44.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val attachInteraction = remember { MutableInteractionSource() }
                                val attachPressed by attachInteraction.collectIsPressedAsState()
                                // "+" now opens the attach sheet (Photo · File) and rotates into "×" while
                                // it's up (issue #90, design: file-attach.jsx); the image flow is one tap
                                // deeper but unchanged.
                                IconButton(onClick = { attachSheet = !attachSheet }, interactionSource = attachInteraction, modifier = Modifier.size(44.dp)) {
                                    AttachPlusGlyph(
                                        open = attachSheet,
                                        tint = if (attachSheet || repo.pendingImages.isNotEmpty() || repo.pendingFiles.isNotEmpty() || attachPressed) Tok.accent else Tok.tx2,
                                    )
                                }
                                Spacer(Modifier.width(6.dp))
                                // model chip (issue #157): the high-frequency switch rides the composer — one tap
                                // straight to the picker (the ⋯ → Model path stays; this is the shallow entrance).
                                // Dimmed mid-turn: the running turn keeps its model, so the entrance rests until
                                // the next turn can take a switch.
                                ModelChip(
                                    label = modelChipLabel(repo.model.value).ifBlank { stringResource(Res.string.value_model_default) },
                                    open = showModelSheet,
                                    enabled = !repo.streaming.value,
                                    contentDescription = stringResource(Res.string.qa_model),
                                    labelMax = 120.dp, // relaxed on the accessory row (mobile-composer.jsx); desktop keeps 82
                                ) { showModelSheet = true }
                                // one tap to any other session you're juggling, across projects (issue #165).
                                // Came DOWN here from the header, which had no width left to give and made a
                                // bare count square read as a badge — same cure the model chip got, so the two
                                // shallow entrances now share a lane. The flexible gap below absorbs it, so the
                                // chip's 120dp label and the 44dp action button are never squeezed.
                                val workingSet = repo.workingSet()
                                if (workingSet.otherCount > 0) Spacer(Modifier.width(6.dp))
                                SessionStackChip(workingSet.otherCount, workingSet.attention) { showSessions = true }
                                // context occupancy came IN here from a pill that floated over the message
                                // tail — unreadable as a control, and it covered the last line (design:
                                // context-occupancy.jsx, Option C). Last in the left cluster, against the
                                // elastic gap, so it is the natural thing to shed when width runs out.
                                val stopShowing = repo.streaming.value && (input.isNotBlank() || hasReady || hasLanded)
                                Spacer(Modifier.width(6.dp))
                                ContextGauge(
                                    repo.contextUsed.value,
                                    repo.contextWindow.value,
                                    // the action slot Row can't see: one 44dp button, plus the ■ and its gap mid-turn
                                    reserveEnd = if (stopShowing) 96.dp else 44.dp,
                                ) { showSessionInfo = true }
                                Spacer(Modifier.weight(1f))
                                // while a turn runs the ■ stays put; typed text adds Send NEXT TO it instead of
                                // replacing it — mirrors Claude Code, where interrupt (Esc) and queue-a-message
                                // (Enter) coexist. Claude's stream-json input queues a mid-turn user message and
                                // weaves it into the running turn at the next tool boundary (verified on 2.1.201).
                                if (stopShowing) {
                                    StopButton { repo.cancelTurn() }
                                    Spacer(Modifier.width(8.dp))
                                }
                                when {
                                    // uploads still moving → send WAITS (spinner ring around a muted arrow,
                                    // design: file-attach.jsx) — landing must finish before the @-refs exist
                                    uploadsBusy -> {
                                        Box(
                                            Modifier.size(44.dp).clip(CircleShape).background(Tok.base).border(1.dp, Tok.hair, CircleShape),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            SpinnerRing(30.dp, 2.dp)
                                            Icon(SendArrowIcon, null, tint = Tok.muted, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                    // text/image/file staged -> SEND, even mid-turn (claude queues it; see above)
                                    input.isNotBlank() || hasReady || hasLanded -> {
                                        val sendLabel = stringResource(Res.string.send)
                                        RoundActionButton(
                                            onClick = {
                                                // read the state at TAP time (composer.text), not the composition-captured
                                                // `input` — a same-frame IME commit racing the tap must still be sent
                                                val t = composer.text.trim()
                                                // a gated send (degraded session, issue #65) returns false — keep the text for the retry
                                                if ((t.isNotBlank() || hasReady || hasLanded) && repo.sendPrompt(t)) { composer.clear(); repo.clearDraft(draftKey) }
                                            },
                                            filled = true, contentDescription = sendLabel,
                                            // long-press → schedule this message for later (issue #137). Text-only:
                                            // images/files can't ride a schedule (nothing is uploaded at fire time).
                                            onLongClick = { if (composer.text.isNotBlank()) showScheduleSheet = true },
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
        }
        viewer?.let { (imgs, idx) -> ImageViewer(imgs, idx) { viewer = null } }
        videoViewer?.let { VideoPlayerOverlay(it) { videoViewer = null } } // issue #98
        if (repo.micPermissionSheet.value) {
            MicPermissionSheet(
                onOpenSettings = { openAppSettings(); repo.dismissMicSheet() },
                onDismiss = repo::dismissMicSheet,
            )
        }
        if (showModeSheet) {
            ModeSheet(
                current = repo.mode.value, rules = repo.allowRules, switching = repo.switching.value, workdir = repo.workdir.value,
                agent = repo.sessionAgent.value, // OpenCode renders the immutable full-access notice, not a ladder
                nativeMode = repo.permissionMode.value,
                autoAvailable = repo.supportsPermissionMode(dev.ccpocket.protocol.CLAUDE_PERMISSION_MODE_AUTO),
                onSelect = { mode, native -> repo.switchMode(mode, native) }, // keep the sheet open so the "switching" state shows
                onClearRule = { repo.clearRule(it) }, onClearAll = { repo.clearAllRules() },
                onDismiss = { showModeSheet = false },
            )
        }
        if (showScheduleSheet) {
            // schedule send (issue #137): fires the composer text into THIS session later
            val scheduledNote = stringResource(Res.string.schedule_created_note)
            ScheduleSendSheet(
                text = composer.text.trim(),
                onSchedule = { runAtMs, repeat ->
                    val t = composer.text.trim()
                    if (t.isNotBlank() && repo.createSchedule(t, runAtMs, repeat = repeat)) {
                        composer.clear(); repo.clearDraft(draftKey)
                        repo.messages.add(dev.ccpocket.app.data.ChatItem.Sys(scheduledNote))
                    }
                },
                onDismiss = { showScheduleSheet = false },
            )
        }
        if (showSessionInfo) SessionInfoSheet(repo, onDismiss = { showSessionInfo = false }, onHandoff = { showHandoffDraft = true })
        if (showQuickActions) {
            QuickActionsSheet(
                repo,
                onTerminal = { showTerminal = true },
                onMode = { showModeSheet = true },
                onFiles = { repo.fetchChangedFiles(); showChangedFiles = true },
                onHelp = { showHelp = true },
                // entry only while the session is handoff-free — one non-terminal handoff per session
                onHandoff = if (activeHandoff == null) ({ showHandoffDraft = true }) else null,
            ) { showQuickActions = false }
        }
        // ── session handoff sheets (design Frames 2 / 3a / 8; contacts increment Frames 1/2) ──
        if (showHandoffDraft) {
            val hoDefaultRequest = stringResource(Res.string.ho_default_request)
            LaunchedEffect(Unit) { repo.listCollaborators() }
            HandoffDraftSheet(
                sessionTitle = repo.chatTitle.value ?: stringResource(Res.string.chat_title),
                path = repo.workdir.value ?: "",
                branch = null,
                agentLabel = (repo.sessionAgent.value ?: AgentKind.CLAUDE).name.lowercase().replaceFirstChar { it.uppercase() },
                roots = listOfNotNull(repo.workdir.value),
                briefSections = emptyList(),
                creating = repo.handoffCreating.value,
                error = repo.handoffError.value,
                contacts = repo.collaborators.toList(),
                onConnectNew = { showConnectColleague = true },
                onCreate = { contact, hours ->
                    repo.createHandoff(contact.label, hours, request = hoDefaultRequest, recipientDeviceId = contact.deviceId)
                },
                onDismiss = { showHandoffDraft = false; repo.handoffError.value = null },
            )
        }
        // A contact-bound offer is delivered over the link — there is no invite artefact to show,
        // so the QR sheet only opens for the legacy open-invite path (recipientDeviceId == null).
        repo.lastHandoffInvite.value?.takeIf { it.recipientDeviceId == null }?.let { inv ->
            val hoInviteClipboard = LocalClipboardManager.current
            HandoffInviteSheet(
                qrBlob = inv.inviteBlob(),
                shortCode = inv.shortCode(),
                countdown = inv.expiresCountdown(hoNow),
                // §6: the recap names THIS grant, not a hardcoded "Review · Read-only"
                recapLine = "${inv.recipientLabel ?: "?"} · ${dev.ccpocket.app.ui.handoff.kindChip(inv.kind)} · ${dev.ccpocket.app.ui.handoff.accessChip(inv.access)}",
                onShare = { hoInviteClipboard.setText(AnnotatedString(inv.inviteBlob())) },
                onCopyLink = { hoInviteClipboard.setText(AnnotatedString(inv.inviteBlob())) },
                onDismiss = { repo.lastHandoffInvite.value = null },
            )
        }
        if (showHandoffReturn && activeHandoff != null) {
            var verdict by remember(activeHandoff.id) { mutableStateOf<String?>(null) }
            val verdictOptions = listOf(
                stringResource(Res.string.ho_verdict_approve),
                stringResource(Res.string.ho_verdict_fixes),
                stringResource(Res.string.ho_verdict_changes),
            )
            HandoffReturnSheet(
                ownerLabel = activeHandoff.initiatorLabel ?: repo.paired.value?.displayName() ?: "?",
                result = dev.ccpocket.app.ui.handoff.HandoffResultUi(verdict = null, returnedByLabel = activeHandoff.recipientLabel ?: "?"),
                verdictOptions = verdictOptions,
                selectedVerdict = verdict,
                onPickVerdict = { verdict = it },
                returning = false,
                onReturn = { repo.returnHandoff(activeHandoff.id, HandoffResult(summary = verdict ?: "", verdict = verdict)) },
                onDismiss = { showHandoffReturn = false },
            )
        }
        // the composer chip's direct model sheet (issue #157) — same picker, no quick-actions detour
        if (showModelSheet) ModelSheet(repo) { showModelSheet = false }
        if (showChangedFiles) ChangedFilesSheet(repo, onOpen = { repo.openChangedFile(it) }) { showChangedFiles = false }
        if (showBgJobs) BackgroundJobsSheet(repo.backgroundJobs, onStop = { repo.stopBackgroundJob(it.id) }) { showBgJobs = false }
        if (showSwitcher) dev.ccpocket.app.ui.fleet.MachineSwitcherSheet(repo, onDismiss = { showSwitcher = false }, onManage = onOpenFleet)
        // #165: leaving for another session saves this one's draft first — same contract as the back button,
        // which is the only other way out of a chat
        if (showSessions) SessionSwitcherSheet(
            set = repo.workingSet(),
            onSelect = { repo.saveDraft(draftKey, input); repo.switchToSession(it) },
            onAllProjects = { repo.saveDraft(draftKey, input); repo.backToDirectories() },
            onDismiss = { showSessions = false },
        )
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
 *  drills in (trailing separator, the daemon re-lists it), a file completes the reference. [onView]
 *  (issue #133) docks a quiet eye at a file row's end that opens the file in the viewer instead. */
@Composable
private fun FileCompletionMenu(
    entries: List<dev.ccpocket.protocol.PathEntry>,
    dir: String,
    sep: Char,
    onView: ((dev.ccpocket.protocol.PathEntry) -> Unit)? = null,
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
                    Modifier.fillMaxWidth().clickable { onPick(entry) }.padding(start = 16.dp, end = 4.dp),
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
                        modifier = Modifier.weight(1f).padding(vertical = 9.dp),
                    )
                    if (!entry.isDir && onView != null) {
                        IconButton(onClick = { onView(entry) }, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Rounded.Visibility, stringResource(Res.string.file_view),
                                tint = Tok.muted, modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageItem(
    m: ChatItem,
    // Workflow run bound to a Workflow tool card (issue #106) — null with an old daemon or for other tools
    workflowRun: dev.ccpocket.protocol.WorkflowRun? = null,
    // which backend is speaking — the Agent turn is labeled with its real name, never a generic "Assistant"
    agent: AgentKind? = null,
    onOpenWorkflow: (String) -> Unit = {},
    onOpenImages: (List<ByteArray>, Int) -> Unit = { _, _ -> },
    onOpenVideo: (dev.ccpocket.app.data.SentFile) -> Unit = {},
    onTightenAutoRun: (ChatItem.AutoRun) -> Unit = {},
) {
    when (m) {
        // Mobile UI 2.0: a quiet uppercase source label above each ordinary turn is all the structure the
        // stream needs to tell User / Agent / Tool apart — no permanent timeline rail, no card stack.
        is ChatItem.User -> Column(Modifier.fillMaxWidth()) {
            TurnSourceLabel(stringResource(Res.string.chat_you), alignEnd = true)
            Row(Modifier.fillMaxWidth().padding(top = 7.dp), horizontalArrangement = Arrangement.End) {
                Column(
                    Modifier.widthIn(max = 300.dp) // a contained bubble, not the full column: "what I said"
                        .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomEnd = 4.dp, bottomStart = 14.dp))
                        .background(Tok.raised).padding(horizontal = 15.dp, vertical = 11.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (m.images.isNotEmpty()) SentImages(m.images) { i -> onOpenImages(m.images, i) }
                    // uploaded files (issue #90): chip per file with its @inbox landing path. Videos (issue
                    // #98) render as a 16:9 card that opens the player; both share the "in workspace" grammar.
                    m.files.forEach { f ->
                        if (isVideoAttachment(f.mediaType, f.name)) SentVideoCard(f) { onOpenVideo(f) } else SentFileChip(f)
                    }
                    if (m.text.isNotBlank()) {
                        // renderClip: this row is a single Text paragraph — an ~800 KB replayed prompt
                        // (skill injection) OOM'd iOS on open; render a prefix, copy keeps the whole thing
                        val shown = renderClip(m.text)
                        SelectionContainer { Text(shown, color = Tok.tx, fontSize = 14.sp * LocalFontScale.current) } // drag-select to copy (no native toolbar on iOS)
                        if (shown.length < m.text.length) TruncatedNote(m.text.length)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            CopyChip(m.text) // one-tap copy — the reliable path on iOS where select-to-copy has no menu (issue #5)
                        }
                    }
                }
            }
        }
        // the agent flows in the base surface: no container at all, so long prose reads as prose
        is ChatItem.Assistant -> Column {
            TurnSourceLabel(agentName(agent ?: AgentKind.CLAUDE), Modifier.padding(bottom = 7.dp))
            SelectionContainer { MarkdownText(m.text, Tok.tx) } // drag-select any span to copy
            if (m.text.isNotBlank()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                CopyChip(m.text) // one-tap copy of the whole turn
            }
        }
        is ChatItem.Thinking -> ThinkingRow(m)
        // a Workflow tool call with a bound run renders the orchestration card (issue #106);
        // without one (old daemon / run trimmed) it falls through to the plain tool row
        is ChatItem.Tool -> if (isWorkflowTool(m.tool) && workflowRun != null) {
            WorkflowCard(workflowRun) { onOpenWorkflow(workflowRun.runId) }
        } else if (isSubagentTool(m.tool)) SubagentCard(m) else {
            val isPlan = m.tool == "ExitPlanMode" || m.tool == "exit_plan_mode"
            var expanded by remember(m) { mutableStateOf(isPlan) } // plans read open by default (issue #10)
            // read-doc-inline handoff (Component 2): a Write/Edit tool card carries the written file's path as
            // its preview (ToolMeta tilde-abbreviates it) — render that path as an openable chip so a tap opens
            // the file in the viewer, while the rest of the card keeps its expand/collapse. Only for file-path
            // tools and only when the preview truly reads as a path (Bash/other previews stay plain text).
            val opener = LocalPathOpener.current
            val openablePath = m.tool in TOOL_FILE_PATH_TOOLS && opener != null && looksLikePath(m.preview)
            Column {
                TurnSourceLabel(stringResource(Res.string.chat_src_tool), Modifier.padding(bottom = 7.dp))
                ToolTurnBand(
                    // the real tool token, verbatim — "Plan" is the one rename, because that is what
                    // ExitPlanMode's payload actually is
                    tool = if (isPlan) "Plan" else m.tool,
                    preview = m.preview,
                    // ONLY a real outcome. `ok == null` means running or unknown, and neither is a result
                    // worth claiming — the row simply carries no status rather than inventing one.
                    status = m.ok,
                    expanded = expanded,
                    onToggle = { expanded = !expanded },
                    previewSlot = when {
                        m.preview.isBlank() -> null
                        openablePath -> ({ OpenablePathChip(m.preview) { opener!!.open(m.preview) } })
                        isPlan && expanded -> ({ MarkdownText(m.preview, Tok.tx2) }) // plan rendered as markdown
                        else -> null
                    },
                )
            }
        }
        is ChatItem.Sys -> Text(stringResource(Res.string.error_prefix, m.text), color = Tok.danger, fontSize = 12.sp)
        is ChatItem.RuleChip -> AllowChip(m.rule)
        // approval design M2 §9.6: grant-covered auto-run — light audit chip with the 收紧 affordance
        is ChatItem.AutoRun -> AutoRunChip(m) { onTightenAutoRun(m) }
        // the quiet residue of a question exchange: an expandable answered row / a muted withdrawn note
        is ChatItem.QuestionsAnswered -> QuestionsAnsweredRow(m.items)
        is ChatItem.QuestionsWithdrawn -> QuestionsWithdrawnRow()
        // OpenCode asked a question — read-only card (no answer channel yet, issue #210)
        is ChatItem.OpenCodeQuestion -> OpenCodeQuestionCard(m.questions)
        // a live turn's end: quiet ✓ line so "finished" stays visible in the transcript
        is ChatItem.TurnEnded -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text(
                "✓ " + stringResource(Res.string.turn_done_marker) + (m.seconds?.let { " · ${turnDurLabel(it)}" } ?: ""),
                color = Tok.ok, fontSize = 11.sp,
            )
        }
    }
}

/** The built-in tools whose ToolMeta preview is a file path (ToolMeta.kt) — the ones whose transcript
 *  card can turn its path into an openable chip (read-doc-inline handoff, Component 2). */
private val TOOL_FILE_PATH_TOOLS = setOf("Write", "Edit", "MultiEdit", "NotebookEdit")

/** A conservative "does this preview read as a single filesystem path?" guard, so only a real path gets
 *  the openable chip — a multi-line or slash-less preview stays plain text. The `{`/`"` reject is the
 *  mixed-version guard: an OLD daemon still sends a file-write preview as raw input JSON (which contains a
 *  slash), and that must NOT be mistaken for a path — a new daemon sends the clean tilde path instead. */
private fun looksLikePath(s: String): Boolean =
    s.isNotBlank() && '\n' !in s && '{' !in s && '"' !in s &&
        (s.startsWith("~/") || s.startsWith("/") || (s.length >= 2 && s[1] == ':') || '/' in s || '\\' in s)

/** read-doc-inline handoff (Component 2): the openable file-path chip on a Write/Edit tool card — a
 *  terracotta-tinted bordered pill that reads as "tap to open", its own hit target so tapping it opens
 *  the file while the rest of the card still toggles expand. Monospace path + a trailing open glyph. */
@Composable
private fun OpenablePathChip(path: String, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    Row(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Tok.accent.copy(alpha = 0.10f))
            .border(1.dp, Tok.accent.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            path, color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(7.dp))
        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = Tok.accent, modifier = Modifier.size(13.dp))
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

/** Queued-behind-a-running-turn cue: the ack'd prompt sits in the CLI's queue while the in-flight turn
 *  stays silent past the deadline. Calm status (the queued case is healthy) in [WorkingRow]'s visual family,
 *  and deliberately NOT tappable — the original is still queued, so a resend would run it twice. */
@Composable
private fun QueuedRow() {
    Row(
        Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PulseDot(Tok.muted, size = 5.dp)
        Text(stringResource(Res.string.msg_queued), color = Tok.muted, fontSize = 12.5.sp, fontStyle = FontStyle.Italic)
    }
}

/** Delivered-but-no-turn cue (issue #104): a restrained, tappable row that replaces the "thinking" tail
 *  when a prompt was acked but produced nothing. Warn-toned (not an alarming error), one tap re-runs it. */
@Composable
private fun NoResponseRow(onResend: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onResend).padding(vertical = 3.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PulseDot(Tok.warn, size = 5.dp)
        Text(stringResource(Res.string.msg_no_response), color = Tok.warn, fontSize = 12.5.sp)
    }
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
