package dev.ccpocket.app.desktop

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import dev.ccpocket.app.data.FileUpState
import dev.ccpocket.app.data.ImgState
import dev.ccpocket.app.data.PendingFile
import dev.ccpocket.app.data.SentFile
import dev.ccpocket.app.share.previewFile
import dev.ccpocket.app.ui.CheckMiniGlyph
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
import dev.ccpocket.app.ui.AgentTag
import dev.ccpocket.app.ui.AttachImageIcon
import dev.ccpocket.app.ui.LocalPathCwd
import dev.ccpocket.app.ui.LocalPathOpener
import dev.ccpocket.app.ui.MarkdownText
import dev.ccpocket.app.ui.QuestionCard
import dev.ccpocket.app.ui.TruncatedNote
import dev.ccpocket.app.ui.renderClip
import dev.ccpocket.app.ui.SentImages
import dev.ccpocket.app.ui.SubagentCard
import dev.ccpocket.app.ui.WorkflowCard
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
        // EmptyChat flash in between.
        Column(modifier.fillMaxSize().background(Tok.base)) {
            if (model.opening) OpeningChat(model.chatTitle) else EmptyChat()
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
        ChatSubHeader(model)
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
                    itemsIndexed(model.messages) { i, m ->
                        CenteredStreamRow {
                            MessageRow(
                                m, isLast = i == model.messages.lastIndex, undelivered = model.sendUndelivered,
                                workflowRun = (m as? ChatItem.Tool)?.let(model::workflowRunFor),
                                onOpenWorkflow = model::openWorkflowPanel,
                                onOpenVideo = { model.openWorkspaceFile(it.path) },
                            )
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
                                // issue #100: on the daemon's TIMED_OUT signal the card flips to its terminal
                                // "auto-denied" state (greyed + Dismiss) rather than staying actionable — the
                                // repo keeps the pendingAsk and stamps timedOutAskId, so ask is still non-null here.
                                InlinePermCard(
                                    ask, model.chatAgent, model.chatWorkdir, model.chatBranch,
                                    onAllow = { rem -> model.resolve(allow = true, remember = rem) },
                                    onDeny = { model.resolve(allow = false, remember = false) },
                                    timedOut = model.askTimedOut,
                                    onDismiss = { model.dismissAsk() },
                                )
                            } else if (model.turnStalled) {
                                // delivered but the agent started no turn within the deadline (issue #104) —
                                // replace the blinking caret with a restrained, clickable resend cue.
                                Text(
                                    "no response yet — click to resend", color = Tok.warn,
                                    fontFamily = Dk.mono, fontSize = 11.sp,
                                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { model.resendStalled() }
                                        .padding(vertical = 3.dp, horizontal = 6.dp),
                                )
                            } else if (model.turnQueued) {
                                // sent mid-turn and the running turn went quiet: queued (healthy), not swallowed —
                                // status only, no resend affordance (the queued original would double-run).
                                Text(
                                    "queued — the current turn is still running", color = Tok.muted,
                                    fontFamily = Dk.mono, fontSize = 11.sp,
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
        SessionHealthStrip(model)
        if (model.observing) ObserveBar(model) else Composer(model, suppressAutoFocus = questionOwnsInput)
    }
    if (dragOver.value) DropOverlay()
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
        degraded -> Tok.danger to
            "Session looks over its context limit — recent replies were API failures. Start a new session or send /clear."
        used != null && window != null && used.toFloat() / window >= 0.9f ->
            Tok.warn to "Context ${(used * 100 / window)}% full — consider a new session or /compact soon"
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

@Composable
private fun EmptyChat() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("No session open", color = Tok.tx, fontFamily = Dk.ui, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("Pick a project on the left, then open or start a session.", color = Tok.muted, fontFamily = Dk.ui, fontSize = 13.sp, textAlign = TextAlign.Center)
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
                if (title.isBlank()) "Opening session…" else "Opening $title…",
                color = Tok.muted, fontFamily = Dk.ui, fontSize = 13.sp,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ChatSubHeader(model: DesktopModel) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                model.chatTitle, color = Tok.tx, fontFamily = Dk.ui, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (model.chatAgent == AgentKind.CODEX) AgentTag(AgentKind.CODEX)
            // quick terminal at the session's cwd — only when that directory exists on THIS machine, so a
            // remote machine's session never shows it (same locality contract as DesktopPathOpener). #44
            // canOpen() stats the filesystem — key it on the workdir so it isn't re-run every recomposition.
            val canOpenTerminal = remember(model.chatWorkdir) { TerminalLauncher.canOpen(model.chatWorkdir) }
            if (canOpenTerminal) {
                Text(
                    ">_", color = Tok.tx2, fontFamily = Dk.mono, fontSize = 11.sp,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).border(1.dp, Tok.hair, RoundedCornerShape(999.dp))
                        .clickable { TerminalLauncher.open(model.terminalApp, model.chatWorkdir) }
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                )
            }
            ChangesPill(model) // "± N" — the session's changed files, opens the Changes browser
            // the permission-mode switch lives in the ⋯ popover now, mirroring mobile's quick-actions
            // sheet (the old header pill was display-only and read as a broken control)
            Icon(
                Icons.Rounded.MoreHoriz, null, tint = Tok.tx2,
                modifier = Modifier.size(26.dp).clip(RoundedCornerShape(999.dp))
                    .clickable { model.showQuickActions = true }.padding(4.dp),
            )
        }
        val branch = model.chatBranch?.let { "  ·  ⑂ $it" } ?: ""
        // machine-first line: which computer this session lives on leads the mono meta (fleet language)
        val machine = model.activeComputer?.name?.let { "$it  ·  " } ?: ""
        // context occupancy readout (issue #65/#73): % when the window is known, raw tokens otherwise
        val ctx = model.contextUsed?.let { u ->
            model.contextWindow?.let { w -> "  ·  ctx ${(u * 100 / w)}%" } ?: "  ·  ctx ~${u / 1000}k"
        } ?: ""
        // model segment falls back to "default" (never a dangling " · ") for a pre-first-turn session the
        // daemon couldn't eager-resolve — mirrors mobile's placeholder + the ⋯ Model row (issue #96)
        val modelLabel = model.chatModel.ifBlank { "default" }
        Text(
            pathLinked("$machine${model.chatWorkdir}$branch  ·  $modelLabel$ctx"),
            color = Tok.tx2, fontFamily = Dk.mono, fontSize = 11.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 10.dp),
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
    }
}

@Composable
private fun MessageRow(
    item: ChatItem,
    isLast: Boolean = false,
    undelivered: Boolean = false,
    // Workflow run bound to a Workflow tool card (issue #106); clicking docks the right panel
    workflowRun: dev.ccpocket.protocol.WorkflowRun? = null,
    onOpenWorkflow: (String) -> Unit = {},
    onOpenVideo: (SentFile) -> Unit = {},
) {
    when (item) {
        is ChatItem.User -> CopyableBlock(item.text) {
            Column {
                Text("You", color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                Spacer(Modifier.height(7.dp))
                // sent attachments (issue #85): the compressed JPEG bytes ride ChatItem.User.images from
                // send (sendPrompt), so an image-only prompt no longer renders as a blank turn. Reuses the
                // shared SentImages tile (phone parity, Calm-Terminal tokens; widthIn caps keep it bounded
                // on desktop). DisableSelection lets a click reach the tile through the stream-wide
                // SelectionContainer — same carve-out as the QuestionCard fields (#76); previewFile drops
                // the bytes to a temp file and opens the OS default viewer (the desktop preview gesture, #79).
                if (item.images.isNotEmpty()) {
                    DisableSelection {
                        SentImages(item.images) { i -> previewFile("image-${i + 1}.jpg", item.images[i], "image/jpeg") }
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
                        "not delivered yet — reconnecting…", color = Tok.warn,
                        fontFamily = Dk.mono, fontSize = 10.5.sp, modifier = Modifier.padding(top = 5.dp),
                    )
                } else if (item.pending) {
                    var slow by remember(item) { mutableStateOf(false) }
                    LaunchedEffect(item) { kotlinx.coroutines.delay(1200); slow = true }
                    if (slow) Text("sending…", color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.5.sp, modifier = Modifier.padding(top = 5.dp))
                } else if (item.delivered && isLast) {
                    Text("✓ delivered", color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.5.sp, modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
        is ChatItem.Assistant -> CopyableBlock(item.text) { MarkdownText(item.text, Tok.tx) }
        is ChatItem.Thinking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("💭", fontSize = 12.sp)
            Text(
                item.seconds?.let { "Thought for ${it}s" } ?: "Thinking…",
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
            else ToolRow(item.tool, item.preview, if (item.ok == false) ToolStatus.FAIL else ToolStatus.OK)
        is ChatItem.Sys -> Text(
            pathLinked(item.text), color = Tok.tx2, fontFamily = Dk.mono, fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Tok.surface)
                .border(1.dp, Tok.hair, RoundedCornerShape(8.dp)).padding(horizontal = 11.dp, vertical = 8.dp),
        )
        is ChatItem.RuleChip -> Text(
            "Always allowing  ${item.rule}", color = Tok.accent, fontFamily = Dk.mono, fontSize = 11.sp,
            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Tok.accent.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
        )
        // question-exchange residue (AskUserQuestion); desktop still answers via the generic flow for now
        is ChatItem.QuestionsAnswered -> Text(
            "?  Answered" + if (item.items.isEmpty()) "" else "  ·  ${item.items.joinToString(" · ") { it.second }}",
            color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        is ChatItem.QuestionsWithdrawn -> Text(
            "Claude moved on — answers no longer needed", color = Tok.muted, fontFamily = Dk.ui, fontSize = 12.sp,
        )
        // a live turn's end: quiet ✓ divider so "finished" stays visible after the caret stops blinking
        is ChatItem.TurnEnded -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.weight(1f).height(1.dp).background(Tok.hair))
            Text("✓ done" + (item.seconds?.let { "  ·  ${turnDurLabel(it)}" } ?: ""), color = Tok.ok, fontFamily = Dk.mono, fontSize = 11.sp)
            Box(Modifier.weight(1f).height(1.dp).background(Tok.hair))
        }
    }
}

enum class ToolStatus { RUN, OK, FAIL }

@Composable
fun ToolRow(name: String, cmd: String, status: ToolStatus) {
    val col = when (status) { ToolStatus.OK -> Tok.ok; ToolStatus.FAIL -> Tok.danger; ToolStatus.RUN -> Tok.accent }
    var expanded by remember { mutableStateOf(false) }
    // one visual line at 12sp mono inside the stream column holds ~70 chars — beyond that (or any
    // newline) the ellipsis hides content, so the row becomes a disclosure toggling the full text
    val expandable = cmd.length > 70 || '\n' in cmd
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Tok.surface)
            .border(1.dp, Tok.hair, RoundedCornerShape(10.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth()
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
                ToolStatus.RUN -> {}
            }
        }
        if (expanded) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
            Box(Modifier.fillMaxWidth().background(Tok.base.copy(alpha = 0.45f))) {
                SelectionContainer {
                    Text(
                        pathLinked(cmd), color = Tok.tx2, fontFamily = Dk.mono, fontSize = 12.sp, lineHeight = 18.sp,
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 40.dp, top = 9.dp, bottom = 9.dp),
                    )
                }
                CopyButton(cmd, Modifier.align(Alignment.TopEnd).padding(top = 5.dp, end = 6.dp))
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
                "Read-only — this session is running in a terminal on the computer",
                color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.5.sp, modifier = Modifier.weight(1f),
            )
            Text(
                "Continue here", color = Tok.base, fontFamily = Dk.ui, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
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
                    ) { Icon(AttachImageIcon, "attach files", tint = Tok.tx2, modifier = Modifier.size(18.dp)) }
                    Box(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        // ONE explicit style for the field AND its placeholder: material3 Text otherwise
                        // merges bodyLarge's line height + letter spacing into the placeholder while
                        // BasicTextField renders the raw style — the two never sat on the same baseline
                        val fieldStyle = TextStyle(color = Tok.tx, fontFamily = Dk.ui, fontSize = 14.sp, lineHeight = 20.sp)
                        if (model.composer.isEmpty()) {
                            Text("Message ${if (model.chatAgent == AgentKind.CODEX) "Codex" else "Claude"}…", style = fieldStyle.copy(color = Tok.muted))
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
                            "uploading $active of ${model.pendingFiles.size} — send waits",
                            color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.5.sp,
                        )
                        Spacer(Modifier.weight(1f))
                    }
                    Key("⏎"); Text("send", color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp)
                    Key("⇧⏎"); Text("newline", color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp)
                    if (model.streaming) { Key("esc"); Text("stop", color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp) }
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
                    when (cmd.source) {
                        CommandSource.BUILTIN -> "built-in"
                        CommandSource.USER -> "user"
                        CommandSource.PROJECT -> "project"
                        CommandSource.SKILL -> "skill"
                    },
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
            "@ " + (dir.ifEmpty { "." }) + if (truncated) "   · more…" else "",
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
                        FileUpState.Failed -> "upload failed · retry"
                        FileUpState.Queued -> "queued  ·  ${fmtSize(f.size)}"
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
                else Icon(Icons.Rounded.Close, "cancel upload", tint = Tok.tx2, modifier = Modifier.size(12.dp))
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
                "Drop to add to this session's workspace",
                color = Tok.tx, fontFamily = Dk.ui, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(
                "images · files · videos — up to 200 MB",
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
                ) { Icon(Icons.Rounded.Close, "remove", tint = Tok.tx, modifier = Modifier.size(10.dp)) }
            }
        }
    }
}
