package dev.ccpocket.app.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import dev.ccpocket.app.data.ChatItem
import dev.ccpocket.app.ui.chat.LineageBanner
import dev.ccpocket.app.ui.chat.RewindErrorBar
import dev.ccpocket.app.data.FileUpState
import dev.ccpocket.app.data.ImgState
import dev.ccpocket.app.data.PendingFile
import dev.ccpocket.app.data.SentFile
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.split_pane_close
import dev.ccpocket.app.resources.split_pane_focus
import dev.ccpocket.app.resources.ho_continuing
import dev.ccpocket.app.resources.ho_copy_invite
import dev.ccpocket.app.resources.ho_finish_return
import dev.ccpocket.app.resources.ho_recall
import dev.ccpocket.app.resources.ho_spectating
import dev.ccpocket.app.resources.ho_waiting_title
import dev.ccpocket.app.resources.action_launch
import dev.ccpocket.app.resources.allow_chip_prefix
import dev.ccpocket.app.resources.autorun_basis_session
import dev.ccpocket.app.resources.autorun_basis_task
import dev.ccpocket.app.resources.autorun_label
import dev.ccpocket.app.resources.autorun_tighten
import dev.ccpocket.app.resources.attach_menu
import dev.ccpocket.app.resources.cancel
import dev.ccpocket.app.resources.cancel_upload
import dev.ccpocket.app.resources.action_retry
import dev.ccpocket.app.resources.chat_no_session
import dev.ccpocket.app.resources.chat_no_session_hint
import dev.ccpocket.app.resources.chat_open_failed
import dev.ccpocket.app.resources.chat_open_failed_hint
import dev.ccpocket.app.resources.chat_open_failed_named
import dev.ccpocket.app.resources.chat_opening
import dev.ccpocket.app.resources.chat_opening_named
import dev.ccpocket.app.resources.chat_start_choose
import dev.ccpocket.app.resources.chat_start_defaults_note
import dev.ccpocket.app.resources.chat_start_failed
import dev.ccpocket.app.resources.chat_start_in
import dev.ccpocket.app.resources.chat_start_opening
import dev.ccpocket.app.resources.chat_start_pick_project
import dev.ccpocket.app.resources.chat_start_placeholder
import dev.ccpocket.app.resources.chat_start_send_failed
import dev.ccpocket.app.resources.chat_start_timeout
import dev.ccpocket.app.resources.chat_you
import dev.ccpocket.app.resources.cmd_source_builtin
import dev.ccpocket.app.resources.cmd_source_project
import dev.ccpocket.app.resources.cmd_source_skill
import dev.ccpocket.app.resources.cmd_source_user
import dev.ccpocket.app.resources.composer_uploading
import dev.ccpocket.app.resources.continue_here
import dev.ccpocket.app.resources.ctx_nearly_full
import dev.ccpocket.app.resources.device_remove
import dev.ccpocket.app.resources.dia_confirm_body
import dev.ccpocket.app.resources.dia_confirm_title
import dev.ccpocket.app.resources.dia_launch
import dev.ccpocket.app.resources.dia_restarting
import dev.ccpocket.app.resources.drop_sub
import dev.ccpocket.app.resources.drop_title
import dev.ccpocket.app.resources.file_failed_retry
import dev.ccpocket.app.resources.file_queued
import dev.ccpocket.app.resources.key_newline
import dev.ccpocket.app.resources.key_send
import dev.ccpocket.app.resources.key_stop
import dev.ccpocket.app.resources.menu_copy_path
import dev.ccpocket.app.resources.menu_more
import dev.ccpocket.app.resources.message_agent_hint
import dev.ccpocket.app.resources.msg_delivered_short
import dev.ccpocket.app.resources.msg_no_response_click
import dev.ccpocket.app.resources.msg_queued
import dev.ccpocket.app.resources.msg_sending
import dev.ccpocket.app.resources.msg_undelivered_reconnecting
import dev.ccpocket.app.resources.observe_readonly
import dev.ccpocket.app.resources.qa_model
import dev.ccpocket.app.resources.question_answered_label
import dev.ccpocket.app.resources.questions_withdrawn
import dev.ccpocket.app.resources.session_degraded_banner
import dev.ccpocket.app.resources.thinking_streaming
import dev.ccpocket.app.resources.thought_for
import dev.ccpocket.app.resources.turn_done_marker
import dev.ccpocket.app.resources.value_default
import dev.ccpocket.app.resources.value_unknown
import org.jetbrains.compose.resources.stringResource
import dev.ccpocket.app.share.previewFile
import dev.ccpocket.app.ui.CheckMiniGlyph
import dev.ccpocket.app.ui.ModelChip
import dev.ccpocket.app.ui.modelChipLabel
import dev.ccpocket.app.diaCdpSupported
import dev.ccpocket.app.launchDiaCdp
import dev.ccpocket.app.ui.RetryGlyph
import dev.ccpocket.app.ui.SpinnerRing
import dev.ccpocket.app.ui.VideoPoster
import dev.ccpocket.app.ui.fileGlyphKind
import dev.ccpocket.app.ui.fmtSize
import dev.ccpocket.app.ui.glyphFor
import dev.ccpocket.app.ui.isVideoAttachment
import java.awt.datatransfer.DataFlavor
import java.io.File
import dev.ccpocket.app.theme.Tok
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.ccpocket.app.ui.AgentBadge
import dev.ccpocket.app.ui.AgentTag
import dev.ccpocket.app.ui.agentColor
import dev.ccpocket.app.ui.agentName
import dev.ccpocket.app.ui.tilde
import dev.ccpocket.app.resources.new_task_agent
import dev.ccpocket.app.resources.new_task_browse_other
import dev.ccpocket.app.resources.new_task_project
import dev.ccpocket.app.resources.new_task_recent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.ccpocket.app.ui.AttachImageIcon
import dev.ccpocket.app.ui.EarlierMessagesSeam
import dev.ccpocket.app.ui.LoadEarlierRow
import dev.ccpocket.app.ui.rememberEarlierLoaderVisible
import dev.ccpocket.app.ui.rememberHistorySeam
import dev.ccpocket.app.ui.LocalPathCwd
import dev.ccpocket.app.ui.LocalPathOpener
import dev.ccpocket.app.ui.MarkdownText
import dev.ccpocket.app.ui.QuestionCard
import dev.ccpocket.app.ui.TruncatedNote
import dev.ccpocket.app.ui.renderClip
import dev.ccpocket.app.ui.SentImages
import dev.ccpocket.app.ui.SubagentCard
import dev.ccpocket.app.ui.WorkflowCard
import dev.ccpocket.app.ui.folderName
import dev.ccpocket.app.ui.pathLinked
import dev.ccpocket.app.ui.rememberBottomPinned
import dev.ccpocket.app.ui.rememberCopied
import dev.ccpocket.app.ui.completion
import dev.ccpocket.app.ui.atTokenAt
import dev.ccpocket.app.ui.atDirOf
import dev.ccpocket.app.ui.atLeafOf
import dev.ccpocket.app.ui.atMatches
import dev.ccpocket.app.ui.atInsertText
import dev.ccpocket.app.ui.slashQueryOf
import dev.ccpocket.app.ui.slashSuggestions
import dev.ccpocket.app.ui.turnDurLabel
import dev.ccpocket.app.ui.handoff.HandoffAvatar
import dev.ccpocket.app.ui.handoff.HandoffAvatarPair
import dev.ccpocket.app.ui.handoff.HandoffLockedComposer
import dev.ccpocket.app.ui.handoff.HandoffResultCard
import dev.ccpocket.app.ui.handoff.HandoffWatchBar
import dev.ccpocket.app.ui.handoff.elapsedLabel
import dev.ccpocket.app.ui.handoff.expiresCountdown
import dev.ccpocket.app.ui.handoff.inviteBlob
import dev.ccpocket.app.ui.handoff.shortCode
import dev.ccpocket.protocol.HandoffStatus
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.CommandSource
import dev.ccpocket.protocol.isQuestion
import dev.ccpocket.protocol.isSubagentTool
import dev.ccpocket.protocol.isWorkflowTool
import dev.ccpocket.protocol.SlashCommand

@Composable
fun ChatPane(model: DesktopModel, modifier: Modifier = Modifier, focused: Boolean = false) {
    if (!model.hasChat) {
        // During an open (messages already cleared, convoId nulled, awaiting SessionLive) show a loading
        // transition for the TARGET session instead of the blank "No session open" state: that empty state
        // read as "the newly-opened session didn't respond" when ⌘K-switching (issue #82). `opening` clears
        // atomically with convoId on SessionLive, so this hands straight off to the live transcript — no
        // EmptyChat flash in between. And when the open never lands at all, say THAT (issue #235) instead
        // of falling back to the same empty state a user who clicked nothing would see.
        Column(modifier.fillMaxSize().background(Tok.base)) {
            // These branches skip [ChatSubHeader] — and with the title bar gone, that used to leave the
            // window with NO drag surface at all (and, sidebar collapsed, no way to bring it back). The
            // sub-header's chrome duties therefore ride this slim row, minus its session content.
            EmptyChatChromeRow(model)
            when {
                model.opening -> OpeningChat(model.chatTitle)
                // the open that never landed (issue #235) — named by the session the user actually asked for
                model.openFailed -> ChatNotice(
                    title = model.chatTitle.takeIf { it.isNotBlank() }
                        ?.let { stringResource(Res.string.chat_open_failed_named, it) }
                        ?: stringResource(Res.string.chat_open_failed),
                    hint = stringResource(Res.string.chat_open_failed_hint),
                    actionLabel = stringResource(Res.string.action_retry),
                    onAction = { model.retryOpen() },
                )
                else -> EmptyChat(model)
            }
        }
        return
    }
    // Linkify transcript file paths against THIS session's cwd (issue #74): a relative path like
    // "10_Notes/会议/材料.md" resolves under chatWorkdir, so clicking it opens the same file the CLI
    // wrote about. remember(chatWorkdir) keeps one opener per cwd so pathLinked()'s per-text memo (and
    // the opener's own exists() cache) survive recomposition. A remote session's cwd isn't local, so
    // those relative paths fail exists() and stay plain — no dead links.
    val pathOpener = remember(model.chatWorkdir) { DesktopPathOpener(model.chatWorkdir) }
    CompositionLocalProvider(LocalPathOpener provides pathOpener, LocalPathCwd provides model.chatWorkdir) {
    // Drag a file anywhere over THIS pane and it arms as a drop target (issue #90, design:
    // desktop-attach.jsx) — the sidebar deliberately does not participate. Dropped images join the
    // inline-image pipeline; every other file chunk-streams into the session's workspace inbox.
    val dragOver = remember { mutableStateOf(false) }
    val dropTarget = remember(model) {
        @OptIn(ExperimentalComposeUiApi::class)
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { dragOver.value = true }
            override fun onExited(event: DragAndDropEvent) { dragOver.value = false }
            override fun onEnded(event: DragAndDropEvent) { dragOver.value = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                dragOver.value = false
                val files = runCatching {
                    @Suppress("UNCHECKED_CAST")
                    event.awtTransferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                }.getOrNull() ?: return false
                val picked = pickedFromDisk(files)
                if (picked.images.isNotEmpty()) model.attachImages(picked.images)
                if (picked.files.isNotEmpty()) model.attachFiles(picked.files)
                return picked.images.isNotEmpty() || picked.files.isNotEmpty()
            }
        }
    }
    // embedded terminal (issue #153): the open-mode menu's anchor (null = closed); drag math and
    // the PANEL menu anchor read the panel's own measured height off the controller.
    var termMenuFrom by remember { mutableStateOf<TermMenuAnchor?>(null) }
    // the recipient's "Finish & return" dialog (design Frame 12) — pane-scoped scrim + centered card
    var showHandoffReturn by remember { mutableStateOf(false) }
    @OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
    Box(
        modifier.fillMaxSize().dragAndDropTarget(
            shouldStartDragAndDrop = { e ->
                runCatching { e.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) }.getOrDefault(false)
            },
            target = dropTarget,
        ),
    ) {
    Column(Modifier.fillMaxSize().background(Tok.base)) {
        // split view marks the pane that owns the keyboard with a 2px terracotta top hairline (Fleet ⑥)
        if (focused) Box(Modifier.fillMaxWidth().height(2.dp).background(Tok.accent))
        // While a QuestionCard text field (its "Other…" / freeform box) owns the keyboard, the composer
        // must not yank focus back with its land-ready requestFocus loop, or the box goes unresponsive (#76).
        // Reset per question so a fresh ask doesn't inherit the last card's ownership.
        var questionOwnsInput by remember(model.ask?.askId) { mutableStateOf(false) }
        ChatSubHeader(model, onTerminalMenu = { termMenuFrom = TermMenuAnchor.HEADER })
        HandoffPaneRibbon(model, onFinishReturn = { showHandoffReturn = true })
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // the QuestionCard docks inside the LazyColumn's unbounded tail item — hand it a bound from the
            // pane's real viewport so its #125 cap+inner-scroll works instead of falling back to full natural
            // height on a very tall question (#150; the card also self-defends against unbounded hosts).
            // Full viewport height, not *0.62f: the card applies its own 0.62 cap to a bounded host.
            val chatViewportHeight = maxHeight
            // VIRTUALIZED, like the phone: a long live transcript (hundreds of markdown messages,
            // still streaming) previously composed in full on the EDT via Column(verticalScroll) and
            // froze the window on every appended chunk. LazyColumn renders the viewport only; while
            // "pinned" the list follows the stream, scrolling up unpins (mirrors mobile ChatScreen).
            val listState = rememberLazyListState()
            val pinned by rememberBottomPinned(listState, model.selectedSessionId, userGesturesOnly = false)
            LaunchedEffect(model.messages.size, model.streaming, model.ask?.askId) {
                if (pinned && model.messages.isNotEmpty()) listState.scrollToItem(model.messages.lastIndex + 1, Int.MAX_VALUE)
            }
            // older-history lazy load (issue #147): a prepended page shifts every index — scroll by the
            // prepend count (+ the loader row when it stays) so the viewport keeps the row being read.
            // Visuals per the 0714 chat-components handoff (B): the loader lingers through its silent-
            // failure fade, and a landed page marks the seam above the re-anchored row for a beat.
            val historyLoaderVisible = rememberEarlierLoaderVisible(model.historyHasMore)
            val historySeamAt = rememberHistorySeam(model.historyPrependGen, model.lastHistoryPrependCount)
            LaunchedEffect(model.historyPrependGen) {
                val n = model.lastHistoryPrependCount
                if (model.historyPrependGen > 0 && n > 0 && !pinned) {
                    listState.scrollToItem(n + (if (historyLoaderVisible) 1 else 0))
                }
            }
            // one SelectionContainer around the whole stream: desktop text is expected to mouse-drag-select,
            // and Compose Text is inert by default — a single container (not per-message) keeps a drag
            // flowing across message boundaries. Buttons/toggles inside stay clickable (selection only
            // claims drags that start on text).
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    // scroll-to-top loader (issue #147): composes only once scrolled into view — exactly
                    // "reached the top of the loaded window" — and then asks for one older page. An
                    // ambient status line, never a button (0714 handoff B1); a dead request fades it
                    // out silently instead of snapping it away (B2).
                    if (historyLoaderVisible) item(key = "history-loader") {
                        if (model.historyHasMore) LaunchedEffect(Unit) { model.loadOlderHistory() }
                        CenteredStreamRow {
                            LoadEarlierRow(fading = !model.historyHasMore, fontFamily = Dk.ui)
                        }
                    }
                    itemsIndexed(model.messages) { i, m ->
                        CenteredStreamRow {
                            Column(Modifier.fillMaxWidth()) {
                                // seam (0714 handoff B3): for a beat after a page of older history lands,
                                // mark where the old window began so the reader keeps their place
                                if (i == historySeamAt) EarlierMessagesSeam(model.historyPrependGen, monoFamily = Dk.mono)
                                MessageRow(
                                    m, isLast = i == model.messages.lastIndex, undelivered = model.sendUndelivered,
                                    bubbles = model.chatAlignment == ChatStreamAlignment.BUBBLES,
                                    workflowRun = (m as? ChatItem.Tool)?.let(model::workflowRunFor),
                                    onOpenWorkflow = model::openWorkflowPanel,
                                    onOpenVideo = { model.openWorkspaceFile(it.path) },
                                    onTightenAutoRun = model::tightenAutoRun,
                                    // rewind/fork (issue #282): built only for a user turn the daemon
                                    // gave transcript coordinates to — see DesktopModel.canRewind
                                    onRewind = (m as? ChatItem.User)?.takeIf(model::canRewind)?.let {
                                        RewindEntries(!model.rewindBlockedByTurn) { turn, mode -> model.startRewind(turn, mode) }
                                    },
                                )
                            }
                        }
                    }
                    item(key = "tail") {
                        CenteredStreamRow {
                            val ask = model.ask
                            if (ask?.isQuestion == true) {
                                // AskUserQuestion is conversation, not a safety gate — render the shared
                                // multiple-choice card (answers ride an ALLOW verdict) instead of a bare
                                // Allow/Deny, which would tell the CLI the user "did not answer" (#57).
                                // DisableSelection: the card's editable "Other…"/freeform BasicTextFields
                                // live INSIDE the stream-wide SelectionContainer, whose drag-select gesture
                                // swallows their click-to-focus/cursor pointer input — the box looked dead to
                                // typing (#76). Carving the card out of selection hands the fields their taps.
                                DisableSelection {
                                    // heightIn(max) turns the item's infinite height into a bounded constraint,
                                    // so the card keeps its #125 cap + inner scroll on desktop too (#150).
                                    Box(Modifier.heightIn(max = chatViewportHeight)) {
                                        QuestionCard(
                                            ask,
                                            onAnswer = { answers, response -> model.answerQuestions(answers, response) },
                                            onSkip = { model.skipQuestions("User skipped the questions") },
                                            onOwnsInput = { questionOwnsInput = it },
                                        )
                                    }
                                }
                            } else if (ask != null) {
                                // approval design M1: several asks can queue behind one another — surface the
                                // burst position so the user knows more cards follow this one
                                model.askQueuePosition?.let { (pos, total) ->
                                    Text(
                                        "$pos / $total", color = Tok.muted,
                                        fontFamily = Dk.mono, fontSize = 10.sp,
                                        modifier = Modifier.padding(bottom = 3.dp),
                                    )
                                }
                                // M2 AttentionLease: a 30s heartbeat while THIS card is on screen pauses the
                                // daemon's no-response budget. Gated on grantOptions (a grant-aware daemon's
                                // ask) — ALSO the reason no loop runs under the Compose TEST clock: an
                                // unconditional infinite delay-loop keeps the virtual frame clock busy and
                                // waitForIdle never returns (desktopTest hang, 08-02).
                                if (ask.grantOptions != null) {
                                    // §18.2 P2-1: only a FOCUSED window with the card composed counts as
                                    // "the user is looking" — unfocus/minimize/dispose releases the lease
                                    // explicitly instead of pausing the daemon budget forever
                                    val focused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
                                    LaunchedEffect(ask.askId, focused) {
                                        if (!focused) {
                                            model.askHeartbeatRelease()
                                            return@LaunchedEffect
                                        }
                                        while (true) {
                                            model.askHeartbeat()
                                            kotlinx.coroutines.delay(30_000)
                                        }
                                    }
                                    androidx.compose.runtime.DisposableEffect(ask.askId) {
                                        onDispose { model.askHeartbeatRelease() }
                                    }
                                }
                                // issue #100: on the daemon's TIMED_OUT signal the card flips to its terminal
                                // "auto-denied" state (greyed + Dismiss) rather than staying actionable — the
                                // repo keeps the pendingAsk and stamps timedOutAskId, so ask is still non-null here.
                                InlinePermCard(
                                    ask, model.chatAgent, model.chatWorkdir, model.chatBranch,
                                    onAllow = { rem -> model.resolve(allow = true, remember = rem) },
                                    onDeny = { model.resolve(allow = false, remember = false) },
                                    timedOut = model.askTimedOut,
                                    onDismiss = { model.dismissAsk() },
                                    risk = model.askRisk,
                                    onAllowTask = { model.resolveTaskGrant() },
                                    onRetrySafer = { model.retrySafer(it) },
                                )
                            } else if (model.turnStalled) {
                                // delivered but the agent started no turn within the deadline (issue #104) —
                                // replace the blinking caret with a restrained, clickable resend cue.
                                Text(
                                    stringResource(Res.string.msg_no_response_click), color = Tok.warn,
                                    fontFamily = Dk.mono, fontSize = 11.sp, style = tightCenter(11.sp),
                                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { model.resendStalled() }
                                        .padding(vertical = 3.dp, horizontal = 6.dp),
                                )
                            } else if (model.turnQueued) {
                                // sent mid-turn and the running turn went quiet: queued (healthy), not swallowed —
                                // status only, no resend affordance (the queued original would double-run).
                                Text(
                                    stringResource(Res.string.msg_queued), color = Tok.muted,
                                    fontFamily = Dk.mono, fontSize = 11.sp, style = tightCenter(11.sp),
                                    modifier = Modifier.padding(vertical = 3.dp, horizontal = 6.dp),
                                )
                            } else if (model.streaming) {
                                Box(Modifier.size(width = 7.dp, height = 15.dp).clip(RoundedCornerShape(1.dp)).blinkAccent())
                            }
                        }
                    }
                }
            }
        }
        HandoffReturnedCard(model)
        SessionHealthStrip(model)
        HandoffComposerZone(model, questionOwnsInput)
        // embedded terminal dock (issue #153): divider + panel / collapsed strip at the pane bottom.
        // The heavyweight Swing terminal swaps out for a flat stand-in while any overlay, this
        // pane's own open-mode menu, or the file-drop scrim is up — SwingPanel would otherwise
        // paint OVER those Compose layers (the DropOverlay's lower edge included).
        TerminalDock(
            model,
            interopHidden = model.anyOverlayOpen || termMenuFrom != null || dragOver.value,
            onOpenMenu = { termMenuFrom = it },
            menuAnchor = termMenuFrom,
        )
    }
    if (dragOver.value) DropOverlay()
    termMenuFrom?.let { anchor ->
        TerminalMenuOverlay(model, anchor) { termMenuFrom = null }
    }
    if (showHandoffReturn) {
        Box(
            Modifier.fillMaxSize().background(Dk.backdrop.copy(alpha = 0.72f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { showHandoffReturn = false },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
                HandoffReturnModal(model) { showHandoffReturn = false }
            }
        }
    }
    }
    }
    // rewind/fork confirmation (issue #282) — a popup over the whole pane, so the numbers land in the
    // middle of the window rather than tucked beside the turn they refer to
    RewindConfirmPopup(model)
}

/**
 * Role ribbon (design Frames 11/12): terracotta only when THIS device is the acting recipient —
 * it carries the persistent "Finish & return"; the spectating side is neutral with the elapsed readout.
 */
@Composable
private fun HandoffPaneRibbon(model: DesktopModel, onFinishReturn: () -> Unit) {
    val ho = model.activeHandoff ?: return
    if (ho.status != HandoffStatus.IN_PROGRESS) return
    val recipient = model.handoffIsRecipient()
    val ownerLabel = ho.initiatorLabel ?: model.activeComputer?.name ?: "?"
    val recLabel = ho.recipientLabel ?: "?"
    val fg = if (recipient) Tok.accent else Tok.tx2
    Row(
        Modifier.fillMaxWidth().background(if (recipient) Tok.accent.copy(alpha = 0.10f) else Tok.surface)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        if (recipient) HandoffAvatarPair(ownerLabel, recLabel, Tok.accent) else HandoffAvatar(recLabel, accent = true)
        Text(
            stringResource(if (recipient) Res.string.ho_continuing else Res.string.ho_spectating, if (recipient) ownerLabel else recLabel),
            color = fg, fontFamily = Dk.ui, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        elapsedLabel(ho.acceptedAt)?.let { Text(it, color = fg, fontFamily = Dk.mono, fontSize = 11.sp) }
        if (recipient) {
            Text(
                stringResource(Res.string.ho_finish_return), color = Tok.accent, fontFamily = Dk.ui, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                style = tightCenter(12.sp),
                modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(Tok.accent.copy(alpha = 0.10f))
                    .border(1.dp, Tok.accent, RoundedCornerShape(9.dp))
                    .clickable(onClick = onFinishReturn).padding(horizontal = 11.dp, vertical = 6.dp),
            )
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
}

/** The RETURNED result card (design Frame 12): docks above the composer at the 820dp cap, findings
 *  two-up, until the initiator's "Mark reviewed" completes the handoff. */
@Composable
private fun HandoffReturnedCard(model: DesktopModel) {
    val ho = model.activeHandoff ?: return
    if (ho.status != HandoffStatus.RETURNED) return
    val ui = desktopHandoffResultUi(model) ?: return
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
        HandoffResultCard(
            ui, twoColumn = true,
            onMarkReviewed = { model.handoffComplete() },
            onOpenFull = { },
            modifier = Modifier.widthIn(max = 820.dp),
        )
    }
}

/** The composer zone with the handoff states folded in (design Frames 11/7): WAITING = lock bar +
 *  dimmed composer stand-in; spectating IN_PROGRESS = watch bar; otherwise the normal chain. */
@Composable
private fun HandoffComposerZone(model: DesktopModel, questionOwnsInput: Boolean) {
    val ho = model.activeHandoff
    when {
        ho != null && ho.status == HandoffStatus.WAITING -> Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
            Row(
                Modifier.padding(horizontal = 18.dp, vertical = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Tok.raised).border(1.dp, Tok.hair, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(999.dp)).background(Tok.tx2))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(Res.string.ho_waiting_title, ho.recipientLabel ?: "?"),
                        color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        // §6: rendered from the daemon's grant, never hardcoded to REVIEW / READ ONLY
                        "${dev.ccpocket.app.ui.handoff.kindChip(ho.kind)} · ${dev.ccpocket.app.ui.handoff.accessChip(ho.access)} · ${ho.shortCode()} · ${ho.expiresCountdown()}",
                        color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.5.sp, modifier = Modifier.padding(top = 2.dp),
                    )
                }
                val clipboard = LocalClipboardManager.current
                Text(
                    stringResource(Res.string.ho_copy_invite), color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    style = tightCenter(12.sp),
                    modifier = Modifier.clip(RoundedCornerShape(9.dp)).border(1.dp, Tok.hair, RoundedCornerShape(9.dp))
                        .clickable { clipboard.setText(AnnotatedString(ho.inviteBlob())) }
                        .padding(horizontal = 11.dp, vertical = 7.dp),
                )
                Text(
                    stringResource(Res.string.ho_recall), color = Tok.danger, fontFamily = Dk.ui, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    style = tightCenter(12.sp),
                    modifier = Modifier.clip(RoundedCornerShape(9.dp)).border(1.dp, Tok.danger.copy(alpha = 0.45f), RoundedCornerShape(9.dp))
                        .clickable { model.handoffCancel() }.padding(horizontal = 11.dp, vertical = 7.dp),
                )
            }
            HandoffLockedComposer()
        }
        ho != null && ho.status == HandoffStatus.IN_PROGRESS && !model.handoffIsRecipient() -> Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
            HandoffWatchBar(onRecall = { model.handoffRecall() })
        }
        model.observing -> ObserveBar(model)
        else -> Column(Modifier.fillMaxWidth()) {
            // A refused rewind (issue #282) docks above the composer, like every other transient chat
            // notice, and self-dismisses — the commonest refusal is a stale anchor, which is information
            // ("reload and point again"), not a decision to make.
            model.rewindError?.let { reason ->
                RewindErrorBar(
                    reason, onDismiss = { model.dismissRewindError() },
                    modifier = Modifier.padding(horizontal = 14.dp).padding(bottom = 8.dp),
                )
            }
            Composer(model, suppressAutoFocus = questionOwnsInput)
        }
    }
}

/** Session health (issue #65): a degraded session (recent turns all API failures) or a ≥90% context
 *  window gets a slim strip above the composer — the warning lands BEFORE the next prompt goes in. */
@Composable
private fun SessionHealthStrip(model: DesktopModel) {
    val degraded = model.sessionDegraded
    val used = model.contextUsed
    val window = model.contextWindow
    val (color, text) = when {
        degraded -> Tok.danger to stringResource(Res.string.session_degraded_banner)
        used != null && window != null && used.toFloat() / window >= 0.9f ->
            Tok.warn to stringResource(Res.string.ctx_nearly_full, (used * 100 / window).toInt())
        else -> return
    }
    Row(
        Modifier.fillMaxWidth().background(color.copy(alpha = 0.12f)).padding(horizontal = 18.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(text, color = color, fontFamily = Dk.ui, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Keeps every stream row centered at the readable column cap inside the full-width lazy viewport. */
@Composable
private fun CenteredStreamRow(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = Dk.maxStreamWidth).fillMaxWidth()) { content() }
    }
}

/**
 * The empty pane (issue #256). It used to state a fact and offer nothing — "No session open" over a hint to
 * go click something else — which made the largest surface in the window the one place you couldn't work.
 * It is now the session starter: type the first prompt here and ⏎ opens a session in the current project
 * (default agent + default mode, the same ladder ⌘N uses) with that prompt queued as turn one.
 *
 * Deliberately NOT a second composer: it writes to [DesktopModel.newSessionPrompt] — model-owned precisely so
 * a queue that fails can hand the text back — and it has no attachments, no model chip and no slash/@ menus,
 * none of which have a session to resolve against yet.
 *
 * With no project in context ([DesktopModel.newSessionDir] null — nothing opened yet on this machine) the
 * field still takes text; submitting says so inline and the full popover is one click away. Refusing to
 * accept keystrokes would be the dead end this replaces.
 */
@Composable
private fun EmptyChat(model: DesktopModel) {
    // Issue #260: the two decisions creating a session actually makes — WHICH project, WHICH agent — are
    // shown here as chips instead of being implied by "the current project, the default agent". They start
    // at exactly those defaults, so the pre-#260 one-keystroke path is unchanged; they are just legible now,
    // and changeable without leaving the pane. Mode and model deliberately stay implicit (the line beside
    // the chips says so) — those belong to the full new-session popover.
    var pickedDir by remember { mutableStateOf<String?>(null) }
    var pickedAgent by remember { mutableStateOf<AgentKind?>(null) }
    var projectPop by remember { mutableStateOf(false) }
    var agentPop by remember { mutableStateOf(false) }
    val dir = pickedDir ?: model.newSessionDir
    val agent = pickedAgent ?: model.defaultAgent
    val busy = model.startingSession
    var pickHint by remember { mutableStateOf(false) } // "select a project first", shown only after a submit
    val submit = {
        val text = model.newSessionPrompt
        if (text.isNotBlank() && !model.startingSession) {
            if (dir == null) pickHint = true else { pickHint = false; model.startSessionWithPrompt(dir, text, agent) }
        }
    }
    val fieldFocus = remember { FocusRequester() }
    // Land ready-to-type. One best-effort attempt, not the composer's retry loop: nothing else claims the
    // keyboard in an empty pane, and a delay-driven loop is exactly what stalls the UI test clock.
    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(Res.string.chat_no_session), color = Tok.tx, fontFamily = Dk.ui, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (dir != null) stringResource(Res.string.chat_start_in, folderName(dir)) else stringResource(Res.string.chat_no_session_hint),
                color = Tok.muted, fontFamily = Dk.ui, fontSize = 13.sp, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            // the Composer's own shape (surface + hairline + 12dp radius + 34dp send circle), so this reads
            // as the same input the session will hand over to. The card is a COLUMN since #260: the field
            // row on top, the attachment row (project · agent · defaults note) beneath it.
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface)
                    .border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Box(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    val fieldStyle = TextStyle(color = Tok.tx, fontFamily = Dk.ui, fontSize = 14.sp, lineHeight = 20.sp)
                    if (model.newSessionPrompt.isEmpty()) {
                        Text(stringResource(Res.string.chat_start_placeholder), style = fieldStyle.copy(color = Tok.muted))
                    }
                    BasicTextField(
                        value = model.newSessionPrompt,
                        onValueChange = {
                            model.newSessionPrompt = it
                            pickHint = false
                            model.dismissNewSessionPromptError() // editing retracts the last failure line
                        },
                        enabled = !busy, // locked while the session it belongs to is opening
                        textStyle = fieldStyle,
                        cursorBrush = SolidColor(Tok.accent),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 132.dp).focusRequester(fieldFocus)
                            .testTag("new-session-prompt")
                            .onPreviewKeyEvent { e ->
                                // ⏎ starts the session; ⇧⏎ falls through to the field's own newline
                                val send = e.type == KeyEventType.KeyDown && e.key == Key.Enter && !e.isShiftPressed
                                if (send) submit()
                                send
                            },
                    )
                }
                if (busy) {
                    Box(
                        Modifier.size(34.dp).clip(RoundedCornerShape(999.dp)).background(Tok.base)
                            .border(1.dp, Tok.hair, RoundedCornerShape(999.dp)),
                        contentAlignment = Alignment.Center,
                    ) { SpinnerRing(24.dp, 2.dp) }
                } else {
                    val armed = model.newSessionPrompt.isNotBlank()
                    Box(
                        Modifier.size(34.dp).clip(RoundedCornerShape(999.dp)).background(Tok.accent)
                            .alpha(if (armed) 1f else 0.45f).testTag("new-session-send").clickable { submit() },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.ArrowUpward, null, tint = Tok.base, modifier = Modifier.size(16.dp)) }
                }
            }
            // ── attachment row (issue #260): the two picks, then what stays implicit ──
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box {
                    StarterChip(
                        label = dir?.let { folderName(it) } ?: stringResource(Res.string.chat_start_pick_project),
                        contentDescription = stringResource(Res.string.new_task_project),
                        open = projectPop, mono = true,
                        leading = { Icon(Icons.Outlined.Folder, null, tint = it, modifier = Modifier.size(13.dp)) },
                    ) { projectPop = !projectPop }
                    if (projectPop) {
                        val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
                        Popup(
                            popupPositionProvider = remember(gap) { AboveAnchorEndPopupPositionProvider(gap) },
                            onDismissRequest = { projectPop = false },
                            properties = PopupProperties(focusable = true),
                        ) {
                            StarterProjectPopover(
                                projects = model.projects,
                                selected = dir,
                                onPick = { pickedDir = it; projectPop = false },
                                // the existing typed-path popover — one implementation of "some other folder"
                                onBrowse = { projectPop = false; model.openNewSession("~/") },
                            )
                        }
                    }
                }
                Box {
                    StarterChip(
                        label = agentName(agent),
                        contentDescription = stringResource(Res.string.new_task_agent),
                        open = agentPop, mono = false,
                        leading = { Box(Modifier.size(9.dp).clip(RoundedCornerShape(999.dp)).background(agentColor(agent))) },
                    ) { agentPop = !agentPop }
                    if (agentPop) {
                        val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
                        Popup(
                            popupPositionProvider = remember(gap) { AboveAnchorEndPopupPositionProvider(gap) },
                            onDismissRequest = { agentPop = false },
                            properties = PopupProperties(focusable = true),
                        ) {
                            StarterAgentPopover(model.availableAgents, agent) { pickedAgent = it; agentPop = false }
                        }
                    }
                }
                // says out loud what the quick path decides FOR you, so nothing here is a silent default
                Text(
                    stringResource(Res.string.chat_start_defaults_note),
                    color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.5.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
            }
            }
            Row(Modifier.fillMaxWidth().padding(start = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Key("⏎"); Text(stringResource(Res.string.key_send), color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp)
                Key("⇧⏎"); Text(stringResource(Res.string.key_newline), color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp)
            }
            // ONE inline status line — never a dialog: whatever went wrong, the text is still in the field
            // above it and the fix is to press ⏎ again.
            val status: Pair<Color, String>? = when {
                busy -> Tok.tx2 to stringResource(Res.string.chat_start_opening)
                pickHint -> Tok.warn to stringResource(Res.string.chat_start_pick_project)
                model.newSessionPromptError == NewSessionPromptError.TIMEOUT -> Tok.danger to stringResource(Res.string.chat_start_timeout)
                model.newSessionPromptError == NewSessionPromptError.SEND_REFUSED -> Tok.danger to stringResource(Res.string.chat_start_send_failed)
                model.newSessionPromptError != null -> Tok.danger to stringResource(Res.string.chat_start_failed)
                else -> null
            }
            status?.let { (color, text) ->
                Text(text, color = color, fontFamily = Dk.ui, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
            // no project in context: the typed-path popover, the affordance that already exists
            if (dir == null) {
                Text(
                    stringResource(Res.string.chat_start_choose),
                    color = Tok.accent, fontFamily = Dk.ui, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
                    style = tightCenter(12.5.sp),
                    modifier = Modifier.padding(top = 2.dp).clip(RoundedCornerShape(7.dp))
                        .hoverFill(RoundedCornerShape(7.dp))
                        .clickable { model.openNewSession() }.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}

/**
 * The empty pane's project / agent chip (issue #260) — the #157 model-chip language with a leading mark.
 *
 * Same hairline pill that warms to accent while its popover is open, so the chips a user meets in the empty
 * pane and in a live composer read as one control. Never accent-FILLED: send stays the loudest thing here.
 */
@Composable
private fun StarterChip(
    label: String,
    contentDescription: String,
    open: Boolean,
    mono: Boolean,
    leading: @Composable (Color) -> Unit,
    onClick: () -> Unit,
) {
    val ink = if (open) Tok.accent else Tok.tx2
    val cd = contentDescription
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.height(30.dp).clip(shape).background(Tok.raised)
            .border(1.dp, if (open) Tok.accent else Tok.hair, shape)
            .clickable(onClick = onClick)
            .semantics { this.contentDescription = cd }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        leading(ink)
        Text(
            label, color = ink, fontFamily = if (mono) Dk.mono else Dk.ui, fontSize = 12.sp,
            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = tightCenter(12.sp), modifier = Modifier.widthIn(max = 132.dp),
        )
        Text(if (open) "▴" else "▾", color = ink, fontFamily = Dk.ui, fontSize = 10.sp, style = tightCenter(10.sp))
    }
}

/** Recent projects, the current one ticked, over the existing typed-path popover for anything else. */
@Composable
private fun StarterProjectPopover(
    projects: List<DkProject>,
    selected: String?,
    onPick: (String) -> Unit,
    onBrowse: () -> Unit,
) {
    // "recent" on the desktop shell is the sidebar's own project order (running first) — the same list the
    // user is looking at, capped so the popover stays a shortcut rather than a second project browser.
    val rows = remember(projects) { projects.take(5) }
    Column(
        Modifier.width(328.dp).clip(RoundedCornerShape(12.dp)).background(Tok.raised)
            .border(1.dp, Tok.hair, RoundedCornerShape(12.dp)),
    ) {
        Text(
            stringResource(Res.string.new_task_recent).uppercase(), color = Tok.muted, fontFamily = Dk.ui,
            fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 11.dp, bottom = 9.dp),
        )
        rows.forEach { p ->
            val checked = p.path == selected
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { onPick(p.path) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        p.name, color = if (checked) Tok.tx else Tok.tx2, fontFamily = Dk.ui, fontSize = 14.5.sp,
                        fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        tilde(p.path), color = Tok.muted, fontFamily = Dk.mono, fontSize = 11.5.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp),
                    )
                }
                if (checked) Text("✓", color = Tok.accent, fontFamily = Dk.ui, fontSize = 14.sp)
            }
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).background(Tok.surface).clickable { onBrowse() }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(Res.string.new_task_browse_other), color = Tok.tx, fontFamily = Dk.ui,
                fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f),
            )
            Text("›", color = Tok.muted, fontFamily = Dk.ui, fontSize = 17.sp)
        }
    }
}

/** The agents this daemon actually advertises — the desktop's picker has always been availability-filtered
 *  (DesktopAgentChoicesTest pins that it reads the shared projection), so it stays a list of real choices. */
@Composable
private fun StarterAgentPopover(available: List<AgentKind>, selected: AgentKind, onPick: (AgentKind) -> Unit) {
    Column(
        Modifier.width(220.dp).clip(RoundedCornerShape(12.dp)).background(Tok.raised)
            .border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).padding(vertical = 6.dp),
    ) {
        available.forEach { a ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable { onPick(a) }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(9.dp).clip(RoundedCornerShape(999.dp)).background(agentColor(a)))
                Text(
                    agentName(a), color = if (a == selected) Tok.tx else Tok.tx2, fontFamily = Dk.ui,
                    fontSize = 13.5.sp, fontWeight = if (a == selected) FontWeight.SemiBold else FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                if (a == selected) Text("✓", color = Tok.accent, fontFamily = Dk.ui, fontSize = 13.sp)
            }
        }
    }
}

/** Shown in the main pane while an OpenSession is in flight (issue #82): a spinner + the target session's
 *  title, so ⌘K-switching reads as "opening this session…" instead of the blank empty state. chatTitle is
 *  set on the target the instant openSession runs (resumed sessions carry their list title). */
@Composable
private fun OpeningChat(title: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CircularProgressIndicator(color = Tok.accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            Text(
                if (title.isBlank()) stringResource(Res.string.chat_opening) else stringResource(Res.string.chat_opening_named, title),
                color = Tok.muted, fontFamily = Dk.ui, fontSize = 13.sp,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The centred "this chat has nothing to show, and here is why" state: a headline, an optional line of
 * explanation, and the one action that can help.
 *
 * Two states share it — the open that never landed (issue #235) and the column whose session ended under
 * it (issue #311). The column used to hand-rebuild this shape a file away and got it subtly wrong: no
 * hover feedback on the only clickable thing on screen, and a capsule label without [tightCenter], which
 * is the alignment rule this project has now re-learned four times. One composable, one set of answers.
 *
 * Sticky by design: the desktop has nowhere else to put this, so it stays until the user acts or opens
 * something else.
 */
@Composable
internal fun ChatNotice(
    title: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                color = Tok.tx, fontFamily = Dk.ui, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            hint?.let {
                Text(it, color = Tok.muted, fontFamily = Dk.ui, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            if (actionLabel != null && onAction != null) {
                Text(
                    actionLabel,
                    color = Tok.accent, fontFamily = Dk.ui, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
                    // a capsule's height is its 5dp padding plus the line box, so the label must not sit
                    // wherever the platform's fallback font's metrics put it (see [tightCenter]'s KDoc)
                    style = tightCenter(12.5.sp),
                    modifier = Modifier.padding(top = 2.dp).clip(RoundedCornerShape(7.dp))
                        .hoverFill(RoundedCornerShape(7.dp))
                        .clickable(onClick = onAction).padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}

/**
 * The window chrome for a chat column with NOTHING open ([DesktopModel.hasChat] false): the empty state,
 * an open in flight, a failed open. [ChatSubHeader] isn't composed on those paths, so this row carries
 * its chrome duties — the whole width drags (there are no session controls to protect), the collapsed
 * sidebar's cluster keeps its home on the leftmost column, and the rightmost edge keeps the connection
 * dot + the Windows/Linux window buttons. Without it, launching into "no session open" left the window
 * undraggable — and, collapsed, with no way to reopen the sidebar.
 */
@Composable
internal fun EmptyChatChromeRow(model: DesktopModel) {
    val chrome = LocalWindowChrome.current
    val edge = LocalPaneEdge.current
    Row(
        Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // same choreography as [ChatSubHeader]'s cluster — and this row already sits at the sidebar
        // row's exact geometry (start 12, 38dp tall), so the late fade is a swap in place
        CollapsedChromeCluster(model, chrome, visible = model.sidebarCollapsed && edge.leftmost)
        Box(Modifier.weight(1f).height(38.dp).then(chrome.dragAndZoomModifier))
        if (edge.rightmost) TrailingWindowControls(model, chrome)
    }
}

// The hand-off choreography, defined ONCE for both cluster hosts (the sub-header and the empty row):
// the SPACE expands in step with the sidebar's slide — one continuous motion for whatever sits after
// it — while the CONTROLS fade in late, after the sidebar's own copy has been wiped past, so the swap
// reads as one cluster changing owners rather than two coexisting. Retuning is a one-place edit.
private val CLUSTER_ENTER = expandHorizontally(tween(SIDEBAR_ANIM_MS)) +
    fadeIn(tween(100, delayMillis = SIDEBAR_ANIM_MS - 80))
private val CLUSTER_EXIT = shrinkHorizontally(tween(SIDEBAR_ANIM_MS)) + fadeOut(tween(80))

/** The collapsed sidebar's controls, re-homed (mock frames 2 / 4b) — leftmost column only: the cluster
 *  describes the window, and one window has one of it. One definition for both hosts, so they cannot
 *  drift out of pixel alignment (the hand-off depends on it). */
@Composable
private fun CollapsedChromeCluster(
    model: DesktopModel,
    chrome: DesktopWindowChrome,
    visible: Boolean,
    divider: Boolean = false,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible = visible, enter = CLUSTER_ENTER, exit = CLUSTER_EXIT) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (chrome.mac && !chrome.fullscreen) TrafficLights(chrome.onClose, chrome.onMinimize, chrome.onToggleFullscreen)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                SidebarToggleButton(model)
                SessionNavButtons(model)
            }
            // the rule that keeps a sub-header reading title-first despite the cluster (mock:211)
            if (divider) ChromeDivider()
        }
    }
}

/** The rightmost column's window-level tail: the connection dot (opens the tray) and, on Win/Linux,
 *  min · max · close. One definition — the dot must read identical wherever it lands. */
@Composable
private fun TrailingWindowControls(model: DesktopModel, chrome: DesktopWindowChrome) {
    Box(
        Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).hoverFill(RoundedCornerShape(6.dp))
            .clickable { model.showTray = !model.showTray },
        contentAlignment = Alignment.Center,
    ) { PulseDot(Tok.ok, 7.dp) }
    if (!chrome.mac && !chrome.fullscreen) WinControls(chrome.onMinimize, chrome.onToggleMax, chrome.onClose)
}

/**
 * The chat column's own header — and, since the desktop chrome v2 redesign, the window's top edge.
 *
 * With the title bar gone there is nothing above this row, so it takes over what the bar used to do:
 * it is the drag handle (double-click zooms), the LEFTMOST column adopts the sidebar's control cluster
 * whenever the sidebar is collapsed, and the RIGHTMOST one carries the connection dot plus the
 * Windows/Linux window buttons. Everything that was already here — title, agent badge, terminal chip,
 * Changes/Git/⋯, the mono meta line, the lineage banner — is untouched; the chrome is strictly added
 * around it.
 */
@Composable
private fun ChatSubHeader(model: DesktopModel, onTerminalMenu: () -> Unit = {}) {
    val chrome = LocalWindowChrome.current
    val edge = LocalPaneEdge.current
    val clusterHere = model.sidebarCollapsed && edge.leftmost
    // Start 12 while the cluster is home (the sidebar row's own inset) so the lights land at the same x
    // they left. The row's HEIGHT deliberately never changes — an earlier cut animated the vertical
    // padding too and the whole header (title, meta line and all) bounced 10dp on every toggle; the
    // remaining 5dp y-difference is closed by drawing the cluster into the top padding instead (offset,
    // a draw-time shift, so the layout stays put).
    val padStart by animateDpAsState(if (clusterHere) 12.dp else 18.dp, tween(SIDEBAR_ANIM_MS), label = "subheader-pad-s")
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = padStart, end = 18.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CollapsedChromeCluster(
                model, chrome, visible = clusterHere, divider = true,
                // −5dp: center the 28dp controls at y19 — the sidebar control row's centerline
                // (38dp tall) — inside this row's 28+20dp geometry, without touching its height
                modifier = Modifier.offset(y = (-5).dp),
            )
            Text(
                model.chatTitle, color = Tok.tx, fontFamily = Dk.ui, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                style = tightCenter(15.sp),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                // the title region is the window's drag handle, exactly as the title bar's label region
                // was — and for the same reason it is the ONLY thing wearing it: this row's own buttons
                // must keep their clicks, so the gesture goes on the flexible, non-interactive middle.
                modifier = Modifier.weight(1f).then(chrome.dragAndZoomModifier),
            )
            AgentBadge(model.chatAgent)
            // quick terminal at the session's cwd — only when that directory exists on THIS machine, so a
            // remote machine's session never shows it (same locality contract as DesktopPathOpener). #44
            // canOpen() stats the filesystem — key it on the workdir so it isn't re-run every recomposition.
            // Since issue #153 the chip anchors the open-mode menu (embedded ⌘J default / external app)
            // instead of jumping straight to the external window.
            val canOpenTerminal = remember(model.chatWorkdir) { TerminalLauncher.canOpen(model.chatWorkdir) }
            if (canOpenTerminal) {
                Text(
                    ">_", color = Tok.tx2, fontFamily = Dk.mono, fontSize = 11.sp, style = tightCenter(11.sp),
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).border(1.dp, Tok.hair, RoundedCornerShape(999.dp))
                        .clickable(onClick = onTerminalMenu)
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                )
            }
            if (model.paneScoped) {
                // split column (issue #311): Changes / Git / ⋯ all drive the FOCUSED conversation, so from
                // here they would act on a session the user is not looking at. A column carries its own two
                // verbs instead — take the focus (which is what unlocks the full set), or close the column.
                Text(
                    stringResource(Res.string.split_pane_focus), color = Tok.tx2, fontFamily = Dk.ui, fontSize = 11.sp,
                    style = tightCenter(11.sp),
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).border(1.dp, Tok.hair, RoundedCornerShape(999.dp))
                        .clickable { model.focusThisPane() }.padding(horizontal = 9.dp, vertical = 3.dp),
                )
                Icon(
                    Icons.Rounded.Close, stringResource(Res.string.split_pane_close), tint = Tok.tx2,
                    modifier = Modifier.size(26.dp).clip(RoundedCornerShape(999.dp))
                        .clickable { model.closeThisPane() }.padding(5.dp),
                )
            } else {
                ChangesPill(model) // "± N" — the session's changed files, opens the Changes browser
                GitPill(model) // "⎇ branch ↑2" — the repository's state, opens the Git panel (issue #280)
                // the permission-mode switch lives in the ⋯ popover now, mirroring mobile's quick-actions
                // sheet (the old header pill was display-only and read as a broken control)
                Icon(
                    Icons.Rounded.MoreHoriz, null, tint = Tok.tx2,
                    modifier = Modifier.size(26.dp).clip(RoundedCornerShape(999.dp))
                        .clickable { model.showQuickActions = true }.padding(4.dp),
                )
            }
            // ONCE per window, at the right end of the RIGHTMOST column (design frame 4): the link is a
            // window-level fact, and a dot per column would read as a per-conversation one. Opens the tray.
            if (edge.rightmost) TrailingWindowControls(model, chrome)
        }
        val branch = model.chatBranch?.let { "  ·  ⑂ $it" } ?: ""
        // machine-first line: which computer this session lives on leads the mono meta (fleet language)
        val machine = model.activeComputer?.name?.let { "$it  ·  " } ?: ""
        // context occupancy readout (issue #65/#73): % when the window is known, raw tokens otherwise
        val ctx = model.contextUsed?.let { u ->
            model.contextWindow?.let { w -> "  ·  ctx ${(u * 100 / w)}%" } ?: "  ·  ctx ~${u / 1000}k"
        } ?: ""
        // model segment always says SOMETHING (never a dangling " · ") for a pre-first-turn session the
        // daemon couldn't eager-resolve — mirrors mobile's placeholder + the ⋯ Model row (issue #96).
        // "default" is a Claude FACT (the account's default model when the user pinned none); for every
        // other agent it was a lie the header told for the whole session — a dsh chat running
        // deepseek-v4-flash read "default" (issue #320). If the backend hasn't named its model, say so.
        val unknownModel = stringResource(Res.string.value_unknown)
        val defaultModel = stringResource(Res.string.value_default)
        val modelLabel = model.chatModel.ifBlank {
            if (model.chatAgent == AgentKind.CLAUDE) defaultModel else unknownModel
        }
        // pathLinked left-clicks OPEN the workdir (when it's local); right-click adds "Copy path" so the
        // cwd is grabbable even on a remote session where it isn't a link at all. Copies the bare workdir,
        // not the whole machine·branch·model meta line.
        // Branch lineage (issue #282, design frame C) TAKES THE PLACE of the context meta line while a
        // rewound/forked conversation is on screen: right after a rewind, "which conversation is this"
        // outranks machine·path·model, and stacking both would say two different things about identity.
        val lineage = model.sessionLineage
        if (lineage != null) {
            LineageBanner(lineage.mode, lineage.fromTitle, Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
            return@Column
        }
        val clipboard = LocalClipboardManager.current
        val copyPath = stringResource(Res.string.menu_copy_path)
        ContextMenuArea(items = {
            listOf(ContextMenuItem(copyPath) { clipboard.setText(AnnotatedString(model.chatWorkdir)) })
        }) {
            Text(
                pathLinked("$machine${model.chatWorkdir}$branch  ·  $modelLabel$ctx"),
                color = Tok.tx2, fontFamily = Dk.mono, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 10.dp),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
    }
}

// chat-stream alignment (issue #213): in LEFT (default) this is a pass-through, so the user turn renders
// exactly as before (byte-for-byte, no wrapper). In BUBBLES it hugs the content to the right inside a raised
// bubble — only presentation moves; the inner Column (label, attachments, text, delivery state) is untouched.
@Composable
private fun UserTurn(bubbles: Boolean, content: @Composable () -> Unit) {
    if (!bubbles) {
        content()
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(14.dp))
                .background(Tok.raised).padding(horizontal = 14.dp, vertical = 11.dp),
        ) { content() }
    }
}

@Composable
private fun MessageRow(
    item: ChatItem,
    isLast: Boolean = false,
    undelivered: Boolean = false,
    // chat-stream alignment (issue #213): true = user turns render as a right-hugging bubble; false (default)
    // keeps the all-left document flow byte-for-byte
    bubbles: Boolean = false,
    // Workflow run bound to a Workflow tool card (issue #106); clicking docks the right panel
    workflowRun: dev.ccpocket.protocol.WorkflowRun? = null,
    onOpenWorkflow: (String) -> Unit = {},
    onOpenVideo: (SentFile) -> Unit = {},
    onTightenAutoRun: (ChatItem.AutoRun) -> Unit = {},
    // rewind/fork (issue #282): null = this row has no entries (no coordinates / not Claude). The
    // Boolean is `enabled` — a disabled entry is still SHOWN, with the reason, because "not right now"
    // and "this build can't" must not look the same.
    onRewind: RewindEntries? = null,
) {
    when (item) {
        // rewind/fork (issue #282): right-click is the desktop's long-press, matching the sidebar row's
        // own ContextMenuArea. Entries appear ONLY when the row carries transcript coordinates — with a
        // pre-#282 daemon or a non-Claude backend the menu isn't built at all, so the user turn keeps
        // exactly its previous behaviour (no empty menu on right-click either).
        is ChatItem.User -> RewindMenuArea(item, onRewind) { CopyableBlock(item.text) {
            UserTurn(bubbles) {
                Column {
                Text(stringResource(Res.string.chat_you), color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                Spacer(Modifier.height(7.dp))
                // sent attachments (issue #85): the compressed JPEG bytes ride ChatItem.User.images from
                // send (sendPrompt), so an image-only prompt no longer renders as a blank turn. Reuses the
                // shared SentImages tile (phone parity, Calm-Terminal tokens; widthIn caps keep it bounded
                // on desktop). DisableSelection lets a click reach the tile through the stream-wide
                // SelectionContainer — same carve-out as the QuestionCard fields (#76); previewFile drops
                // the bytes to a temp file and opens the OS default viewer (the desktop preview gesture, #79).
                // …and since issue #254 they also ride a REPLAYED turn, so a prompt composed at another
                // client (or in this computer's own terminal) shows its attachments here too.
                if (item.images.isNotEmpty() || item.imagesTruncated) {
                    DisableSelection {
                        SentImages(item.images, item.imagesTruncated) { i -> previewFile("image-${i + 1}.jpg", item.images[i], "image/jpeg") }
                    }
                    if (item.text.isNotBlank() || item.files.isNotEmpty()) Spacer(Modifier.height(8.dp))
                }
                // uploaded files (issue #90): dense single-line chip with the @inbox landing path; videos
                // (issue #98) render as a 16:9 thumb that opens the landed clip in the OS player
                if (item.files.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        item.files.forEach { f ->
                            if (isVideoAttachment(f.mediaType, f.name)) DesktopSentVideoThumb(f) { onOpenVideo(f) }
                            else DesktopSentFileChip(f)
                        }
                    }
                    if (item.text.isNotBlank()) Spacer(Modifier.height(8.dp))
                }
                if (item.text.isNotBlank()) {
                    // renderClip: one pathological row (~800 KB skill injection) shouldn't hit the
                    // pathLinked regex + single-paragraph layout whole; CopyableBlock keeps full text
                    val shown = renderClip(item.text)
                    Text(pathLinked(shown), color = Tok.tx, fontFamily = Dk.ui, fontSize = 14.5.sp, lineHeight = 22.sp)
                    if (shown.length < item.text.length) TruncatedNote(item.text.length)
                }
                // delivery state (issue #66): "sending…" after a short grace while the daemon hasn't
                // receipted; "✓ delivered" once the PromptAck lands, until the reply starts (stops being last).
                // A pending bubble whose delivery can't be confirmed (link down / receipts stalled — issue #78)
                // warns honestly instead of pulsing "sending…" forever.
                if (item.pending && undelivered) {
                    Text(
                        stringResource(Res.string.msg_undelivered_reconnecting), color = Tok.warn,
                        fontFamily = Dk.mono, fontSize = 10.5.sp, modifier = Modifier.padding(top = 5.dp),
                    )
                } else if (item.pending) {
                    var slow by remember(item) { mutableStateOf(false) }
                    LaunchedEffect(item) { kotlinx.coroutines.delay(1200); slow = true }
                    if (slow) Text(stringResource(Res.string.msg_sending), color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.5.sp, modifier = Modifier.padding(top = 5.dp))
                } else if (item.delivered && isLast) {
                    Text(stringResource(Res.string.msg_delivered_short), color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.5.sp, modifier = Modifier.padding(top = 5.dp))
                }
                }
            }
        } }
        is ChatItem.Assistant -> CopyableBlock(item.text) { MarkdownText(item.text, Tok.tx) }
        is ChatItem.Thinking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("💭", fontSize = 12.sp)
            Text(
                item.seconds?.let { stringResource(Res.string.thought_for, it) } ?: stringResource(Res.string.thinking_streaming),
                color = Tok.muted, fontFamily = Dk.ui, fontSize = 12.5.sp,
            )
        }
        // sub-agent (Task/Agent) runs get the shared dense card (issue #77 / chat-cards handoff):
        // status tile + live progress line + hover-revealed report. Plain tools keep the flat ✓ row.
        is ChatItem.Tool ->
            // a Workflow run gets the dense orchestration card that docks the right panel (issue #106);
            // without a bound run (old daemon) it stays the flat tool row
            if (isWorkflowTool(item.tool) && workflowRun != null) {
                WorkflowCard(workflowRun, dense = true) { onOpenWorkflow(workflowRun.runId) }
            } else if (isSubagentTool(item.tool)) SubagentCard(item, dense = true)
            else ToolRow(
                item.tool,
                item.preview,
                when (item.ok) {
                    true -> ToolStatus.OK
                    false -> ToolStatus.FAIL
                    null -> if (item.taskId != null) ToolStatus.RUN else ToolStatus.UNKNOWN
                },
                output = item.output,
            )
        is ChatItem.Sys -> Text(
            pathLinked(item.text), color = Tok.tx2, fontFamily = Dk.mono, fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Tok.surface)
                .border(1.dp, Tok.hair, RoundedCornerShape(8.dp)).padding(horizontal = 11.dp, vertical = 8.dp),
        )
        is ChatItem.RuleChip -> Text(
            stringResource(Res.string.allow_chip_prefix) + "  ${item.rule}", color = Tok.accent, fontFamily = Dk.mono, fontSize = 11.sp,
            style = tightCenter(11.sp),
            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Tok.accent.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
        )
        // approval design M2 §9.6 (design frame 3 `.achip`): grant-covered auto-run audit chip + 收紧 link
        is ChatItem.AutoRun -> {
            Row(
                Modifier.clip(RoundedCornerShape(999.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(999.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text("⚡ " + stringResource(Res.string.autorun_label), color = Tok.tx2, fontFamily = Dk.ui, fontSize = 11.sp, style = tightCenter(11.sp))
                Text(item.summary, color = Tok.tx, fontFamily = Dk.mono, fontSize = 11.sp, style = tightCenter(11.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(if (item.basis == "task-grant") Res.string.autorun_basis_task else Res.string.autorun_basis_session),
                    color = Tok.accent, fontFamily = Dk.ui, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, style = tightCenter(10.sp),
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Tok.accent.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 1.dp),
                )
                if (item.tightening) {
                    Text("…", color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.5.sp, style = tightCenter(10.5.sp))
                } else if (!item.tightened) {
                    Text(
                        stringResource(Res.string.autorun_tighten), color = Tok.muted, fontFamily = Dk.ui, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
                        style = tightCenter(10.5.sp),
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onTightenAutoRun(item) }.padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                } else {
                    Text("✓", color = Tok.ok, fontFamily = Dk.mono, fontSize = 10.5.sp, style = tightCenter(10.5.sp))
                }
            }
        }
        // question-exchange residue (AskUserQuestion); desktop still answers via the generic flow for now
        is ChatItem.QuestionsAnswered -> Text(
            "?  " + stringResource(Res.string.question_answered_label) + if (item.items.isEmpty()) "" else "  ·  ${item.items.joinToString(" · ") { it.second }}",
            color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        is ChatItem.QuestionsWithdrawn -> Text(
            stringResource(Res.string.questions_withdrawn), color = Tok.muted, fontFamily = Dk.ui, fontSize = 12.sp,
        )
        // asked but never answered (issue #321) — the read-only counterpart of the live card the tail
        // item docks. It used to fall through to a plain tool row named "AskUserQuestion", which is why
        // it read as a question you could see and not complete.
        is ChatItem.QuestionsUnanswered -> dev.ccpocket.app.ui.QuestionsUnansweredRow(item.text)
        // OpenCode asked a question — read-only card (no answer channel yet, issue #210)
        is ChatItem.OpenCodeQuestion -> dev.ccpocket.app.ui.OpenCodeQuestionCard(item.questions)
        // a live turn's end: quiet ✓ divider so "finished" stays visible after the caret stops blinking
        is ChatItem.TurnEnded -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.weight(1f).height(1.dp).background(Tok.hair))
            Text("✓ " + stringResource(Res.string.turn_done_marker) + (item.seconds?.let { "  ·  ${turnDurLabel(it)}" } ?: ""), color = Tok.ok, fontFamily = Dk.mono, fontSize = 11.sp)
            Box(Modifier.weight(1f).height(1.dp).background(Tok.hair))
        }
    }
}

enum class ToolStatus { RUN, OK, FAIL, UNKNOWN }
internal const val TOOL_ROW_TAG = "plain-tool-row"

@Composable
fun ToolRow(name: String, cmd: String, status: ToolStatus, output: String? = null) {
    val col = when (status) {
        ToolStatus.OK -> Tok.ok
        ToolStatus.FAIL -> Tok.danger
        ToolStatus.RUN -> Tok.accent
        ToolStatus.UNKNOWN -> Tok.muted
    }
    var expanded by remember { mutableStateOf(false) }
    // one visual line at 12sp mono inside the stream column holds ~70 chars — beyond that (or any
    // newline) the ellipsis hides content. A replayed tool result also makes the row expandable: before
    // #306 desktop threw that output field away even though the daemon had preserved it.
    val expandable = cmd.length > 70 || '\n' in cmd || !output.isNullOrBlank()
    val details = remember(cmd, output) {
        buildString {
            append(cmd)
            output?.takeIf { it.isNotBlank() }?.let {
                if (isNotEmpty()) append("\n\n")
                append("— output —\n")
                append(it)
            }
        }
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Tok.surface)
            .border(1.dp, Tok.hair, RoundedCornerShape(10.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().testTag(TOOL_ROW_TAG)
                .then(if (expandable) Modifier.clickable { expanded = !expanded } else Modifier)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Dot(col, 7.dp)
            Text(name, color = Tok.tx, fontFamily = Dk.ui, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            Text(
                cmd.lineSequence().first(), color = Tok.tx2, fontFamily = Dk.mono, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (expandable) {
                Icon(
                    Icons.Rounded.KeyboardArrowDown, null, tint = Tok.muted,
                    modifier = Modifier.size(14.dp).rotate(if (expanded) 180f else 0f),
                )
            }
            when (status) {
                ToolStatus.OK -> Icon(Icons.Rounded.Check, null, tint = Tok.ok, modifier = Modifier.size(14.dp))
                ToolStatus.FAIL -> Icon(Icons.Rounded.Close, null, tint = Tok.danger, modifier = Modifier.size(14.dp))
                ToolStatus.RUN, ToolStatus.UNKNOWN -> {}
            }
        }
        if (expanded) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
            Box(Modifier.fillMaxWidth().background(Tok.base.copy(alpha = 0.45f))) {
                SelectionContainer {
                    Text(
                        pathLinked(details), color = Tok.tx2, fontFamily = Dk.mono, fontSize = 12.sp, lineHeight = 18.sp,
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 40.dp, top = 9.dp, bottom = 9.dp),
                    )
                }
                CopyButton(details, Modifier.align(Alignment.TopEnd).padding(top = 5.dp, end = 6.dp))
            }
        }
    }
}

/**
 * Wraps a transcript block and floats a hover-revealed copy chip at its top-right — the desktop
 * affordance for "copy this prompt / response" (kept out of layout so rows never shift).
 */
@Composable
private fun CopyableBlock(text: String, content: @Composable () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Box(Modifier.fillMaxWidth().hoverable(src)) {
        content()
        CopyButton(text, Modifier.align(Alignment.TopEnd), visible = hovered)
    }
}

/** A small copy-to-clipboard chip; flips to a green check for a beat after copying. */
@Composable
private fun CopyButton(text: String, modifier: Modifier = Modifier, visible: Boolean = true) {
    val (copied, copy) = rememberCopied()
    Box(
        modifier.alpha(if (visible || copied) 1f else 0f)
            .clip(RoundedCornerShape(6.dp))
            .background(Tok.raised)
            .border(1.dp, Tok.hair, RoundedCornerShape(6.dp))
            .clickable(enabled = visible || copied) { copy(text) }
            .padding(4.dp),
    ) {
        if (copied) Icon(Icons.Rounded.Check, null, tint = Tok.ok, modifier = Modifier.size(13.dp))
        else Icon(Icons.Rounded.ContentCopy, null, tint = Tok.muted, modifier = Modifier.size(13.dp))
    }
}

private fun Modifier.blinkAccent(): Modifier = composed {
    val a by rememberInfiniteTransition().animateFloat(
        initialValue = 1f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(530), RepeatMode.Reverse),
    )
    background(Tok.accent.copy(alpha = a))
}

/** Replaces the composer while the session is a read-only OBSERVE view (owned by a terminal/VS Code
 *  on the computer). Take-over forks a branch the app can drive — same gesture as mobile. */
@Composable
private fun ObserveBar(model: DesktopModel) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Dot(Tok.warn, 7.dp)
            Text(
                stringResource(Res.string.observe_readonly),
                color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.5.sp, style = tightCenter(12.5.sp), modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(Res.string.continue_here), color = Tok.base, fontFamily = Dk.ui, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                style = tightCenter(12.5.sp),
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Tok.accent)
                    .clickable { model.takeOver() }.padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun Composer(model: DesktopModel, suppressAutoFocus: Boolean = false) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Column(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = Dk.maxStreamWidth).fillMaxWidth()) {
                val scope = rememberCoroutineScope()
                val uploadsBusy = model.uploadsBusy()
                val submit = {
                    if (!model.uploadsBusy() && (model.composer.isNotBlank() || model.hasReadyImages() || model.hasLandedFiles())) {
                        model.send(model.composer)
                    }
                }
                val composerFocus = remember { FocusRequester() }
                var composerFocused by remember { mutableStateOf(false) }
                // CDP: relaunch Dia with the debug port so an agent can drive the browser (docs-dia-cdp-launch-需求.md).
                // Killing the running Dia is the price of reusing its logged-in profile, so gate the tap behind a confirm.
                val diaSupported = remember { diaCdpSupported() }
                var diaConfirm by remember { mutableStateOf(false) }
                var diaBusy by remember { mutableStateOf(false) }
                var diaStatus by remember { mutableStateOf<String?>(null) } // last launch result → transient line under the hints
                // Land ready-to-type: focus the composer whenever a session becomes current — a brand-new
                // session (#72) or a pin-jump / palette / sidebar switch (#46). Only the keyboard-owning pane
                // renders a Composer (the read-only WatchPane has none), so there's no split gate here —
                // `focused` stays purely the accent-bar cue at the top of ChatPane. openSession clears convoId
                // before every open, so hasChat cycles false→true on each land and this fires once per session.
                // Retry until the field REPORTS focus (onFocusChanged), not merely until requestFocus() stops
                // throwing: right after the fresh mount the node is attached (no throw) but not yet placed, so
                // the request silently no-ops — the old `isSuccess` check bailed on that first no-op and focus
                // never actually landed (#72 still broken). Keep re-requesting across a short window instead.
                // suppressAutoFocus holds the loop off while a QuestionCard field owns the keyboard (#76) —
                // otherwise this land-ready grab races the card's "Other…"/freeform box for focus.
                LaunchedEffect(model.hasChat, suppressAutoFocus) {
                    if (model.hasChat && !suppressAutoFocus) {
                        repeat(20) {
                            if (composerFocused) return@LaunchedEffect
                            runCatching { composerFocus.requestFocus() }
                            delay(40)
                        }
                    }
                }
                // "/" autocomplete — query/filter/rank shared with the mobile composer (one ranking to tune)
                val slashQuery = slashQueryOf(model.composer)
                val slashCmds = remember(slashQuery, model.slashCommands) {
                    slashSuggestions(slashQuery, model.slashCommands)
                }
                var slashSel by remember(slashQuery) { mutableStateOf(0) }          // keyed: retyping resets to the top hit
                var slashDismissed by remember(slashQuery) { mutableStateOf(false) } // Esc hides until the query changes
                val slashOpen = slashCmds.isNotEmpty() && !slashDismissed
                // an explicit write through the String facade — ComposerState lands the caret at the end
                val completeSlash = { cmd: SlashCommand -> model.composer = cmd.completion() }
                // The model OWNS the field (ComposerState — drafts follow sessions in the model layer, #88);
                // the pane reads its caret for the "@file" menu and writes caret-precise edits itself
                // (shift+Enter newline — Compose desktop has no binding for it — and @-completion).
                // IME-composition safety for whole-text writes (#86, same root as mobile #93) lives in
                // ComposerState.setText; there is no per-frame reconcile against a String copy anymore.
                val composer = model.composerState
                // "@file" completion (issue #75): browse the session cwd via the daemon, filter by the typed
                // leaf, drill into folders. sep is the daemon host's separator (Windows-safe, #19/#22).
                val sep = model.pathSep
                val atToken = remember(composer.field.text, composer.field.selection) { atTokenAt(composer.field.text, composer.field.selection.min) }
                val atDir = atToken?.let { atDirOf(it.query, sep) } ?: ""
                val atLeaf = atToken?.let { atLeafOf(it.query, sep) } ?: ""
                // re-list only when the directory part changes — typing the leaf just filters client-side
                LaunchedEffect(atToken != null, atDir) { if (atToken != null) model.browsePath(atDir) }
                val atListing = model.pathListing
                val atEntries = remember(atListing, atToken, atDir, atLeaf) {
                    if (atToken == null || atListing?.subPath != atDir) emptyList()
                    else atMatches(atListing.entries, atLeaf)
                }
                var atSel by remember(atDir, atLeaf) { mutableStateOf(0) } // keyed: a new dir/filter resets to the top hit
                var atClosedAt by remember { mutableStateOf<String?>(null) } // Esc / file-pick hides until the query changes
                val atOpen = atToken != null && atEntries.isNotEmpty() && atToken.query != atClosedAt && !slashOpen
                // pick: a folder appends the separator and keeps the menu open (drill-in); a file inserts its
                // path and closes the menu until the query changes again.
                val applyEntry = fun(entry: dev.ccpocket.protocol.PathEntry) {
                    val token = atToken ?: return
                    val insert = atInsertText(atDir, entry, sep)
                    val from = token.at + 1
                    val newText = composer.field.text.replaceRange(from, token.end, insert)
                    composer.update(TextFieldValue(newText, TextRange(from + insert.length)))
                    if (!entry.isDir) atClosedAt = insert // the just-completed query — don't reopen on this exact value
                }
                if (model.pendingFiles.isNotEmpty()) PendingFilesRow(model)
                if (model.pendingImages.isNotEmpty()) PendingImagesRow(model)
                if (slashOpen) SlashMenu(slashCmds, slashSel, onPick = completeSlash)
                if (atOpen) FileMenu(atEntries, atSel, atDir, sep, atListing?.truncated == true, onPick = applyEntry)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface)
                        .border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    // 24dp round hit target, 18dp glyph, raised 5dp: its center lands at 17dp from the row
                    // bottom — the send circle's center — so the pair reads centered on a single-line field.
                    // (The old size→padding order carved the padding OUT of the 17dp glyph: a 12dp icon
                    // pinned to the row's bottom edge.)
                    Box(
                        Modifier.padding(bottom = 5.dp).size(24.dp).clip(RoundedCornerShape(999.dp)).clickable {
                            // ONE dialog for any attachment (issue #90): images join the inline pipeline,
                            // everything else uploads to the workspace inbox. FileDialog blocks its
                            // thread — keep the UI free, then hand results back
                            scope.launch {
                                val picked = withContext(Dispatchers.IO) { pickAttachments() }
                                if (picked.images.isNotEmpty()) model.attachImages(picked.images)
                                if (picked.files.isNotEmpty()) model.attachFiles(picked.files)
                            }
                        },
                        contentAlignment = Alignment.Center,
                    ) { Icon(AttachImageIcon, stringResource(Res.string.attach_menu), tint = Tok.tx2, modifier = Modifier.size(18.dp)) }
                    Box(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        // ONE explicit style for the field AND its placeholder: material3 Text otherwise
                        // merges bodyLarge's line height + letter spacing into the placeholder while
                        // BasicTextField renders the raw style — the two never sat on the same baseline
                        val fieldStyle = TextStyle(color = Tok.tx, fontFamily = Dk.ui, fontSize = 14.sp, lineHeight = 20.sp)
                        if (model.composer.isEmpty()) {
                            Text(stringResource(Res.string.message_agent_hint, agentName(model.chatAgent)), style = fieldStyle.copy(color = Tok.muted))
                        }
                        // the model's ComposerState is the ONE source of truth: onValueChange is the only
                        // path user/IME edits take, and external writes call its explicit methods directly.
                        BasicTextField(
                            value = composer.field,
                            onValueChange = composer::onValueChange,
                            textStyle = fieldStyle,
                            cursorBrush = SolidColor(Tok.accent),
                            modifier = Modifier.fillMaxWidth().focusRequester(composerFocus)
                                .onFocusChanged { composerFocused = it.isFocused }.onPreviewKeyEvent { e ->
                                when {
                                    // ⌘V/Ctrl+V with an image on the clipboard attaches it; plain text
                                    // falls through (return false) to the field's normal paste
                                    e.type == KeyEventType.KeyDown && e.key == Key.V && (e.isMetaPressed || e.isCtrlPressed) -> {
                                        val imgs = clipboardImages()
                                        if (imgs.isEmpty()) false else { model.attachImages(imgs); true }
                                    }
                                    // the slash menu claims ↑↓/Esc/Tab/⏎ while open (shift+Enter stays a newline)
                                    slashOpen && e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown -> {
                                        slashSel = (slashSel + 1) % slashCmds.size; true
                                    }
                                    slashOpen && e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp -> {
                                        slashSel = (slashSel - 1 + slashCmds.size) % slashCmds.size; true
                                    }
                                    slashOpen && e.type == KeyEventType.KeyDown && e.key == Key.Escape -> {
                                        slashDismissed = true; true
                                    }
                                    // the @-file menu claims ↑↓/Esc/Tab/⏎ while open (shift+Enter stays a newline)
                                    atOpen && e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown -> {
                                        atSel = (atSel + 1) % atEntries.size; true
                                    }
                                    atOpen && e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp -> {
                                        atSel = (atSel - 1 + atEntries.size) % atEntries.size; true
                                    }
                                    atOpen && e.type == KeyEventType.KeyDown && e.key == Key.Escape -> {
                                        atClosedAt = atToken?.query; true
                                    }
                                    atOpen && e.type == KeyEventType.KeyDown && !e.isShiftPressed &&
                                        (e.key == Key.Tab || e.key == Key.Enter) -> {
                                        applyEntry(atEntries[atSel.coerceIn(0, atEntries.lastIndex)]); true
                                    }
                                    // CLI muscle memory: Esc interrupts the running turn (slash menu already handled above)
                                    e.type == KeyEventType.KeyDown && e.key == Key.Escape && model.streaming -> {
                                        model.stopTurn(); true
                                    }
                                    slashOpen && e.type == KeyEventType.KeyDown && !e.isShiftPressed &&
                                        (e.key == Key.Tab || e.key == Key.Enter) -> {
                                        val cmd = slashCmds[slashSel.coerceIn(0, slashCmds.lastIndex)]
                                        // Enter on a name that's already typed in full SENDS it — otherwise a
                                        // hint-less command like /help could be completed but never submitted
                                        if (e.key == Key.Enter && model.composer == "/${cmd.name}") submit()
                                        else completeSlash(cmd)
                                        true
                                    }
                                    e.key != Key.Enter || e.type != KeyEventType.KeyDown -> false
                                    e.isShiftPressed -> { // ⇧⏎ newline, as the hint row below promises
                                        val cur = composer.field
                                        val sel = cur.selection
                                        composer.update(TextFieldValue(cur.text.replaceRange(sel.min, sel.max, "\n"), TextRange(sel.min + 1)))
                                        true
                                    }
                                    else -> { submit(); true }
                                }
                            },
                        )
                    }
                    // model chip (issue #157): the current model, one click from its picker — the popover
                    // anchors right here so the entrance rides the composer, same as mobile's. Boxed to the
                    // send circle's 34dp so the pill centers against the round buttons (the row bottom-aligns
                    // while the field grows). Dimmed mid-turn — a switch lands on the next turn anyway.
                    Box(Modifier.height(34.dp), contentAlignment = Alignment.Center) {
                        ModelChip(
                            label = modelChipLabel(model.chatModelId).ifBlank { stringResource(Res.string.value_default) },
                            open = model.showModelPopover,
                            enabled = !model.streaming,
                            contentDescription = stringResource(Res.string.qa_model),
                        ) { model.showModelPopover = true }
                        if (model.showModelPopover) {
                            val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
                            Popup(
                                popupPositionProvider = remember(gap) { AboveAnchorEndPopupPositionProvider(gap) },
                                onDismissRequest = { model.showModelPopover = false },
                                properties = PopupProperties(focusable = true),
                            ) { ModelPopover(model) { model.showModelPopover = false } }
                        }
                    }
                    // ■ interrupt rides BESIDE send while a turn runs (send itself never morphs) — a
                    // just-sent prompt returns to the composer via stopTurn (#48, quick-regret window
                    // only); Esc does the same
                    if (model.streaming) {
                        Box(
                            Modifier.size(34.dp).clip(RoundedCornerShape(999.dp))
                                .border(1.dp, Tok.hair, RoundedCornerShape(999.dp)).clickable { model.stopTurn() },
                            contentAlignment = Alignment.Center,
                        ) { Box(Modifier.size(11.dp).clip(RoundedCornerShape(2.dp)).background(Tok.danger)) }
                    }
                    if (uploadsBusy) {
                        // send WAITS while uploads run (design: desktop-attach.jsx) — the landed
                        // @-references don't exist until the daemon's receipt lands
                        Box(
                            Modifier.size(34.dp).clip(RoundedCornerShape(999.dp)).background(Tok.base)
                                .border(1.dp, Tok.hair, RoundedCornerShape(999.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            SpinnerRing(24.dp, 2.dp)
                            Icon(Icons.Rounded.ArrowUpward, null, tint = Tok.muted, modifier = Modifier.size(14.dp))
                        }
                    } else {
                        Box(
                            Modifier.size(34.dp).clip(RoundedCornerShape(999.dp)).background(Tok.accent).clickable { submit() },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.ArrowUpward, null, tint = Tok.base, modifier = Modifier.size(16.dp)) }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 7.dp, start = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (uploadsBusy) {
                        val active = model.pendingFiles.count { it.state == FileUpState.Uploading || it.state == FileUpState.Queued }
                        Box(Modifier.size(5.dp).clip(RoundedCornerShape(999.dp)).background(Tok.accent))
                        Text(
                            stringResource(Res.string.composer_uploading, active, model.pendingFiles.size),
                            color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.5.sp,
                        )
                        Spacer(Modifier.weight(1f))
                    }
                    Key("⏎"); Text(stringResource(Res.string.key_send), color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp)
                    Key("⇧⏎"); Text(stringResource(Res.string.key_newline), color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp)
                    if (model.streaming) { Key("esc"); Text(stringResource(Res.string.key_stop), color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp) }
                    // CDP: relaunch Dia with the debug port — a small pill at the far right of the hint row,
                    // directly under the send button (outside the input box). Confirm popover anchors above it.
                    if (diaSupported) {
                        Spacer(Modifier.weight(1f))
                        diaStatus?.let { msg ->
                            LaunchedEffect(msg) { delay(3500); diaStatus = null } // auto-dismiss the last result
                            Text(msg, color = Tok.tx2, fontFamily = Dk.ui, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box {
                            Row(
                                Modifier.clip(RoundedCornerShape(7.dp)).border(1.dp, Tok.hair, RoundedCornerShape(7.dp))
                                    .let { if (diaBusy) it else it.clickable { diaConfirm = true } }
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                if (diaBusy) SpinnerRing(11.dp, 1.5.dp)
                                else Icon(Icons.Outlined.Language, null, tint = Tok.tx2, modifier = Modifier.size(12.dp))
                                // tightCenter 把行盒交给字号（#293），让文字视觉中心与图标（12dp 对称盒）在 CenterVertically 下真正对齐
                                Text(
                                    if (diaBusy) stringResource(Res.string.dia_restarting) else stringResource(Res.string.dia_launch),
                                    color = Tok.tx2, fontFamily = Dk.ui, fontSize = 11.sp, style = tightCenter(11.sp),
                                )
                            }
                            if (diaConfirm) {
                                val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
                                Popup(
                                    popupPositionProvider = remember(gap) { AboveAnchorEndPopupPositionProvider(gap) },
                                    onDismissRequest = { diaConfirm = false },
                                    properties = PopupProperties(focusable = true),
                                ) {
                                    Column(
                                        Modifier.width(248.dp).clip(RoundedCornerShape(12.dp)).background(Tok.raised)
                                            .border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Text(stringResource(Res.string.dia_confirm_title), color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            stringResource(Res.string.dia_confirm_body),
                                            color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.sp, lineHeight = 17.sp,
                                        )
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Box(
                                                Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                                                    .border(1.dp, Tok.hair, RoundedCornerShape(8.dp))
                                                    .clickable { diaConfirm = false }.padding(vertical = 7.dp),
                                                contentAlignment = Alignment.Center,
                                            ) { Text(stringResource(Res.string.cancel), color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.sp, style = tightCenter(12.sp)) }
                                            Box(
                                                Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(Tok.accent)
                                                    .clickable {
                                                        diaConfirm = false; diaBusy = true; diaStatus = null
                                                        scope.launch { val r = launchDiaCdp(); diaBusy = false; diaStatus = r.message }
                                                    }.padding(vertical = 7.dp),
                                                contentAlignment = Alignment.Center,
                                            ) { Text(stringResource(Res.string.action_launch), color = Tok.base, fontFamily = Dk.ui, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, style = tightCenter(12.sp)) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The composer's "/" autocomplete: ↑↓ navigate, ⏎/Tab complete, Esc dismiss — clicking a row completes too.
 *  Sits above the input inside the stream column (same anchoring as mobile's SlashCommandMenu). */
@Composable
private fun SlashMenu(commands: List<SlashCommand>, selected: Int, onPick: (SlashCommand) -> Unit) {
    val listState = rememberLazyListState()
    // keyboard selection can walk past the 240dp viewport — keep the highlighted row visible
    LaunchedEffect(selected, commands.size) {
        if (commands.isNotEmpty()) listState.scrollToItem(selected.coerceIn(0, commands.lastIndex))
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().heightIn(max = 238.dp).padding(bottom = 8.dp)
            .clip(RoundedCornerShape(10.dp)).background(Tok.raised)
            .border(1.dp, Tok.hair, RoundedCornerShape(10.dp)),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        itemsIndexed(commands) { i, cmd ->
            Row(
                Modifier.fillMaxWidth()
                    .background(if (i == selected) Tok.surface else Color.Transparent)
                    .clickable { onPick(cmd) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("/${cmd.name}", color = Tok.accent, fontFamily = Dk.mono, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                cmd.argumentHint?.let { Text(it, color = Tok.muted, fontFamily = Dk.mono, fontSize = 11.5.sp, maxLines = 1) }
                Text(
                    cmd.description, color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(
                        when (cmd.source) {
                            CommandSource.BUILTIN -> Res.string.cmd_source_builtin
                            CommandSource.USER -> Res.string.cmd_source_user
                            CommandSource.PROJECT -> Res.string.cmd_source_project
                            CommandSource.SKILL -> Res.string.cmd_source_skill
                        },
                    ),
                    color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.sp,
                )
            }
        }
    }
}

/** The composer's "@file" completion (issue #75): ↑↓ navigate, ⏎/Tab pick, Esc dismiss — a folder pick
 *  drills in (menu stays), a file pick inserts its relative path. Mirrors [SlashMenu]'s anchoring/keys. */
@Composable
private fun FileMenu(
    entries: List<dev.ccpocket.protocol.PathEntry>,
    selected: Int,
    dir: String,
    sep: Char,
    truncated: Boolean,
    onPick: (dev.ccpocket.protocol.PathEntry) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selected, entries.size) {
        if (entries.isNotEmpty()) listState.scrollToItem(selected.coerceIn(0, entries.lastIndex))
    }
    Column(
        Modifier.fillMaxWidth().padding(bottom = 8.dp)
            .clip(RoundedCornerShape(10.dp)).background(Tok.raised)
            .border(1.dp, Tok.hair, RoundedCornerShape(10.dp)),
    ) {
        // which directory (relative to the cwd) these entries live in — "@ ." at the project root
        Text(
            "@ " + (dir.ifEmpty { "." }) + if (truncated) "   " + stringResource(Res.string.menu_more) else "",
            color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 7.dp, bottom = 3.dp),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().heightIn(max = 212.dp),
            contentPadding = PaddingValues(bottom = 4.dp),
        ) {
            itemsIndexed(entries) { i, entry ->
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (i == selected) Tok.surface else Color.Transparent)
                        .clickable { onPick(entry) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // folders lead with a caret + trailing separator, so "drill in" reads before the tap
                    Text(if (entry.isDir) "▸" else " ", color = Tok.muted, fontFamily = Dk.mono, fontSize = 12.sp)
                    Text(
                        entry.name + if (entry.isDir) sep.toString() else "",
                        color = if (entry.isDir) Tok.tx else Tok.tx2,
                        fontFamily = Dk.mono, fontSize = 12.5.sp,
                        fontWeight = if (entry.isDir) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Staged composer attachments: thumbnails with a remove ✕; Rejected (over the frame budget) shows dimmed
 *  with a warning border. They ride the next send (the repo folds Ready images into SendPrompt). */
@Composable
private fun PendingFilesRow(model: DesktopModel) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        model.pendingFiles.forEach { f -> DesktopPendingFileChip(f, model) }
    }
}

/** Dense composer chip (design: desktop-attach.jsx PendingChip): 26dp glyph tile, mono name,
 *  "64% · 6.1 MB" caption, a 2.5dp linear progress bar along the base, and a hover-revealed
 *  action button — ✕ cancels while moving, ↻ retries when failed. */
@Composable
private fun DesktopPendingFileChip(f: PendingFile, model: DesktopModel) {
    val failed = f.state == FileUpState.Failed
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val shape = RoundedCornerShape(9.dp)
    Box(
        Modifier.widthIn(min = 190.dp, max = 230.dp).hoverable(src).clip(shape)
            .background(if (failed) Tok.danger.copy(alpha = 0.07f) else Tok.surface)
            .border(1.dp, if (failed) Tok.danger else Tok.hair, shape)
            .clickable(enabled = failed) { model.retryPendingFile(f.id) },
    ) {
        Row(
            Modifier.padding(start = 8.dp, top = 7.dp, bottom = 8.dp, end = 30.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(
                Modifier.size(26.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (failed) Tok.danger.copy(alpha = 0.12f) else Tok.base)
                    .border(1.dp, Tok.hair, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (failed) Icon(RetryGlyph, null, tint = Tok.danger, modifier = Modifier.size(15.dp))
                else Icon(glyphFor(fileGlyphKind(f.name)), null, tint = Tok.tx2, modifier = Modifier.size(15.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    f.name, color = if (failed) Tok.danger else Tok.tx, fontFamily = Dk.mono, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when (f.state) {
                        FileUpState.Failed -> stringResource(Res.string.file_failed_retry)
                        FileUpState.Queued -> stringResource(Res.string.file_queued) + "  ·  ${fmtSize(f.size)}"
                        FileUpState.Landed -> "✓  ·  ${fmtSize(f.size)}"
                        FileUpState.Uploading -> "${(f.progress * 100).toInt()}%  ·  ${fmtSize(f.size)}"
                    },
                    color = if (failed) Tok.danger else Tok.muted, fontFamily = Dk.mono, fontSize = 10.5.sp,
                    modifier = Modifier.padding(top = 1.dp), maxLines = 1,
                )
            }
        }
        // hover-revealed action: retry when failed, cancel/remove otherwise
        if (hovered) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).clip(RoundedCornerShape(6.dp))
                    .background(Tok.raised).border(1.dp, Tok.hair, RoundedCornerShape(6.dp))
                    .clickable { if (failed) model.retryPendingFile(f.id) else model.removePendingFile(f.id) },
                contentAlignment = Alignment.Center,
            ) {
                if (failed) Icon(RetryGlyph, null, tint = Tok.danger, modifier = Modifier.size(12.dp))
                else Icon(Icons.Rounded.Close, stringResource(Res.string.cancel_upload), tint = Tok.tx2, modifier = Modifier.size(12.dp))
            }
        }
        // thin linear progress along the chip base
        if (!failed) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.5.dp).background(Tok.hair)) {
                Box(Modifier.fillMaxWidth(f.progress.coerceIn(0f, 1f)).height(2.5.dp).background(Tok.accent))
            }
        }
    }
}

/** Delivered file in the stream (design: desktop-attach.jsx SentFileChip): one dense line —
 *  glyph tile · mono name · size · terracotta @inbox path. */
@Composable
private fun DesktopSentFileChip(f: SentFile) {
    val shape = RoundedCornerShape(9.dp)
    Row(
        Modifier.widthIn(max = 420.dp).clip(shape).background(Tok.surface).border(1.dp, Tok.hair, shape)
            .padding(start = 8.dp, top = 7.dp, bottom = 7.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)).background(Tok.base).border(1.dp, Tok.hair, RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(glyphFor(fileGlyphKind(f.name)), null, tint = Tok.tx2, modifier = Modifier.size(16.dp)) }
        Text(f.name, color = Tok.tx, fontFamily = Dk.mono, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(fmtSize(f.size), color = Tok.muted, fontFamily = Dk.mono, fontSize = 11.5.sp, maxLines = 1)
        Text(
            "@${f.path}", color = Tok.accent, fontFamily = Dk.mono, fontSize = 11.5.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/** Delivered VIDEO in the stream (design: desktop-attach.jsx SentVideoThumb): a 220px 16:9 poster
 *  (placeholder + play glyph + optional duration), then mono name · size · terracotta @inbox path.
 *  Clicking plays the landed clip in the OS default player — the desktop app is co-located with the
 *  daemon, so the inbox path is a real local file. */
@Composable
private fun DesktopSentVideoThumb(f: SentFile, onOpen: () -> Unit) {
    Column(Modifier.width(220.dp)) {
        Box(Modifier.width(220.dp).clip(RoundedCornerShape(9.dp)).border(1.dp, Tok.hair, RoundedCornerShape(9.dp)).clickable { onOpen() }) {
            VideoPoster(durationSecs = f.durationSecs, buttonSize = 40.dp, glyphSize = 17.dp, cornerRadius = 9.dp)
        }
        Row(
            Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(f.name, color = Tok.tx, fontFamily = Dk.mono, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(fmtSize(f.size), color = Tok.muted, fontFamily = Dk.mono, fontSize = 11.5.sp, maxLines = 1)
            Text(
                "@${f.path}", color = Tok.accent, fontFamily = Dk.mono, fontSize = 11.5.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/** Full-pane drop overlay (design: desktop-attach-app.jsx DropOverlay): dimmed scrim, dashed
 *  terracotta boundary inset 12dp, upload glyph tile + the workspace-framed prompt. */
@Composable
private fun DropOverlay() {
    Box(Modifier.fillMaxSize().background(Color(0x8C08090A))) {
        Box(
            Modifier.fillMaxSize().padding(12.dp)
                .dashedBorder(Tok.accent, radius = 14.dp, stroke = 2.dp)
                .clip(RoundedCornerShape(14.dp)).background(Tok.accent.copy(alpha = 0.06f)),
        )
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier.size(58.dp).clip(RoundedCornerShape(16.dp)).background(Tok.accent.copy(alpha = 0.14f))
                    .border(1.dp, Tok.accent.copy(alpha = 0.4f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.ArrowUpward, null, tint = Tok.accent, modifier = Modifier.size(28.dp)) }
            Text(
                stringResource(Res.string.drop_title),
                color = Tok.tx, fontFamily = Dk.ui, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(Res.string.drop_sub),
                color = Tok.tx2, fontFamily = Dk.mono, fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun PendingImagesRow(model: DesktopModel) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        model.pendingImages.forEach { img ->
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Tok.surface)
                    .border(1.dp, if (img.state == ImgState.Rejected) Tok.danger else Tok.hair, RoundedCornerShape(8.dp)),
            ) {
                if (img.state == ImgState.Compressing) {
                    Text("…", color = Tok.muted, fontSize = 16.sp, modifier = Modifier.align(Alignment.Center))
                } else {
                    // decode AFTER compression only (Ready/Rejected bytes are bounded; originals may be huge)
                    val thumb = remember(img.id, img.state) {
                        runCatching { org.jetbrains.skia.Image.makeFromEncoded(img.bytes).toComposeImageBitmap() }.getOrNull()
                    }
                    thumb?.let {
                        Image(
                            it, null, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(56.dp).alpha(if (img.state == ImgState.Rejected) 0.4f else 1f),
                        )
                    }
                }
                Box(
                    Modifier.align(Alignment.TopEnd).padding(2.dp).size(16.dp).clip(RoundedCornerShape(999.dp))
                        .background(Tok.base.copy(alpha = 0.75f)).clickable { model.removePendingImage(img.id) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Close, stringResource(Res.string.device_remove), tint = Tok.tx, modifier = Modifier.size(10.dp)) }
            }
        }
    }
}
