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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import dev.ccpocket.app.data.ChatItem
import dev.ccpocket.app.data.ImgState
import dev.ccpocket.app.theme.Tok
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.ccpocket.app.ui.AgentTag
import dev.ccpocket.app.ui.AttachImageIcon
import dev.ccpocket.app.ui.MarkdownText
import dev.ccpocket.app.ui.QuestionCard
import dev.ccpocket.app.ui.pathLinked
import dev.ccpocket.app.ui.rememberBottomPinned
import dev.ccpocket.app.ui.rememberCopied
import dev.ccpocket.app.ui.completion
import dev.ccpocket.app.ui.slashQueryOf
import dev.ccpocket.app.ui.slashSuggestions
import dev.ccpocket.app.ui.turnDurLabel
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.CommandSource
import dev.ccpocket.protocol.isQuestion
import dev.ccpocket.protocol.SlashCommand

@Composable
fun ChatPane(model: DesktopModel, modifier: Modifier = Modifier, focused: Boolean = false) {
    Column(modifier.fillMaxSize().background(Tok.base)) {
        if (!model.hasChat) {
            EmptyChat()
            return@Column
        }
        // split view marks the pane that owns the keyboard with a 2px terracotta top hairline (Fleet ⑥)
        if (focused) Box(Modifier.fillMaxWidth().height(2.dp).background(Tok.accent))
        // While a QuestionCard text field (its "Other…" / freeform box) owns the keyboard, the composer
        // must not yank focus back with its land-ready requestFocus loop, or the box goes unresponsive (#76).
        // Reset per question so a fresh ask doesn't inherit the last card's ownership.
        var questionOwnsInput by remember(model.ask?.askId) { mutableStateOf(false) }
        ChatSubHeader(model)
        Box(Modifier.weight(1f).fillMaxWidth()) {
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
                    itemsIndexed(model.messages) { i, m -> CenteredStreamRow { MessageRow(m, isLast = i == model.messages.lastIndex) } }
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
                                    QuestionCard(
                                        ask,
                                        onAnswer = { answers, response -> model.answerQuestions(answers, response) },
                                        onSkip = { model.skipQuestions("User skipped the questions") },
                                        onOwnsInput = { questionOwnsInput = it },
                                    )
                                }
                            } else if (ask != null) {
                                InlinePermCard(
                                    ask, model.chatAgent, model.chatWorkdir, model.chatBranch,
                                    onAllow = { rem -> model.resolve(allow = true, remember = rem) },
                                    onDeny = { model.resolve(allow = false, remember = false) },
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
        Text(
            pathLinked("$machine${model.chatWorkdir}$branch  ·  ${model.chatModel}$ctx"),
            color = Tok.tx2, fontFamily = Dk.mono, fontSize = 11.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 10.dp),
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
    }
}

@Composable
private fun MessageRow(item: ChatItem, isLast: Boolean = false) {
    when (item) {
        is ChatItem.User -> CopyableBlock(item.text) {
            Column {
                Text("You", color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                Spacer(Modifier.height(7.dp))
                Text(pathLinked(item.text), color = Tok.tx, fontFamily = Dk.ui, fontSize = 14.5.sp, lineHeight = 22.sp)
                // delivery state (issue #66): "sending…" after a short grace while the daemon hasn't
                // receipted; "✓ delivered" once the PromptAck lands, until the reply starts (stops being last)
                if (item.pending) {
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
        is ChatItem.Tool -> ToolRow(item.tool, item.preview, ToolStatus.OK)
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
                val submit = { if (model.composer.isNotBlank() || model.hasReadyImages()) model.send(model.composer) }
                val composerFocus = remember { FocusRequester() }
                // Land ready-to-type: focus the composer whenever a session becomes current — a brand-new
                // session (#72) or a pin-jump / palette / sidebar switch (#46). Only the keyboard-owning pane
                // renders a Composer (the read-only WatchPane has none), so there's no split gate here —
                // `focused` stays purely the accent-bar cue at the top of ChatPane. openSession clears convoId
                // before every open, so hasChat cycles false→true on each land and this fires once per session.
                // Retry briefly: the field may not be attached on the first tick after the fresh mount.
                // suppressAutoFocus holds the loop off while a QuestionCard field owns the keyboard (#76) —
                // otherwise this land-ready grab races the card's "Other…"/freeform box for focus.
                LaunchedEffect(model.hasChat, suppressAutoFocus) {
                    if (model.hasChat && !suppressAutoFocus) {
                        repeat(6) {
                            if (runCatching { composerFocus.requestFocus() }.isSuccess) return@LaunchedEffect
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
                // the field mirror below moves the cursor to the end of the completed text
                val completeSlash = { cmd: SlashCommand -> model.composer = cmd.completion() }
                if (model.pendingImages.isNotEmpty()) PendingImagesRow(model)
                if (slashOpen) SlashMenu(slashCmds, slashSel, onPick = completeSlash)
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
                            // FileDialog blocks its thread — keep the UI free, then hand results back
                            scope.launch { withContext(Dispatchers.IO) { pickImages() }.takeIf { it.isNotEmpty() }?.let(model::attachImages) }
                        },
                        contentAlignment = Alignment.Center,
                    ) { Icon(AttachImageIcon, "attach images", tint = Tok.tx2, modifier = Modifier.size(18.dp)) }
                    Box(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        // ONE explicit style for the field AND its placeholder: material3 Text otherwise
                        // merges bodyLarge's line height + letter spacing into the placeholder while
                        // BasicTextField renders the raw style — the two never sat on the same baseline
                        val fieldStyle = TextStyle(color = Tok.tx, fontFamily = Dk.ui, fontSize = 14.sp, lineHeight = 20.sp)
                        if (model.composer.isEmpty()) {
                            Text("Message ${if (model.chatAgent == AgentKind.CODEX) "Codex" else "Claude"}…", style = fieldStyle.copy(color = Tok.muted))
                        }
                        // TextFieldValue (not the model's plain String) because shift+Enter must insert the
                        // newline AT THE CURSOR ourselves: Compose desktop's key mapping has no shift+Enter
                        // binding at all (bare Enter is its only newline key, and we've claimed that for send),
                        // so the field never inserts one — and a String state carries no selection to do it with.
                        var field by remember { mutableStateOf(TextFieldValue(model.composer)) }
                        // reconcile external writes (send() clears the composer; palette/model seeding)
                        if (field.text != model.composer) field = TextFieldValue(model.composer, TextRange(model.composer.length))
                        BasicTextField(
                            value = field,
                            onValueChange = { field = it; model.composer = it.text },
                            textStyle = fieldStyle,
                            cursorBrush = SolidColor(Tok.accent),
                            modifier = Modifier.fillMaxWidth().focusRequester(composerFocus).onPreviewKeyEvent { e ->
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
                                        val sel = field.selection
                                        val text = field.text.replaceRange(sel.min, sel.max, "\n")
                                        field = TextFieldValue(text, TextRange(sel.min + 1))
                                        model.composer = text
                                        true
                                    }
                                    else -> { submit(); true }
                                }
                            },
                        )
                    }
                    // ■ interrupt rides BESIDE send while a turn runs (send itself never morphs) — the
                    // interrupted prompt returns to the composer via stopTurn (#48); Esc does the same
                    if (model.streaming) {
                        Box(
                            Modifier.size(34.dp).clip(RoundedCornerShape(999.dp))
                                .border(1.dp, Tok.hair, RoundedCornerShape(999.dp)).clickable { model.stopTurn() },
                            contentAlignment = Alignment.Center,
                        ) { Box(Modifier.size(11.dp).clip(RoundedCornerShape(2.dp)).background(Tok.danger)) }
                    }
                    Box(
                        Modifier.size(34.dp).clip(RoundedCornerShape(999.dp)).background(Tok.accent).clickable { submit() },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.ArrowUpward, null, tint = Tok.base, modifier = Modifier.size(16.dp)) }
                }
                Row(Modifier.fillMaxWidth().padding(top = 7.dp, start = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

/** Staged composer attachments: thumbnails with a remove ✕; Rejected (over the frame budget) shows dimmed
 *  with a warning border. They ride the next send (the repo folds Ready images into SendPrompt). */
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
