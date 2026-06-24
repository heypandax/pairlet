package dev.ccpocket.app.desktop

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.ChatItem
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.AgentTag
import dev.ccpocket.app.ui.AttachImageIcon
import dev.ccpocket.app.ui.MarkdownText
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.PermissionMode

@Composable
fun ChatPane(model: DesktopModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(Tok.base)) {
        if (!model.hasChat) {
            EmptyChat()
            return@Column
        }
        ChatSubHeader(model)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = Dk.maxStreamWidth).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    model.messages.forEach { MessageRow(it) }
                    val ask = model.ask
                    if (ask != null) {
                        InlinePermCard(
                            ask, model.chatAgent, model.chatWorkdir, model.chatBranch,
                            onAllow = { model.resolve(allow = true, remember = false) },
                            onDeny = { model.resolve(allow = false, remember = false) },
                        )
                    } else if (model.streaming) {
                        Box(Modifier.size(width = 7.dp, height = 15.dp).clip(RoundedCornerShape(1.dp)).blinkAccent())
                    }
                }
            }
        }
        Composer(model)
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

private fun modeLabel(m: PermissionMode): String = when (m) {
    PermissionMode.DEFAULT -> "default"
    PermissionMode.ACCEPT_EDITS -> "acceptEdits"
    PermissionMode.PLAN -> "plan"
    PermissionMode.BYPASS_PERMISSIONS -> "bypass"
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
            Row(
                Modifier.clip(RoundedCornerShape(999.dp)).border(1.dp, Tok.hair, RoundedCornerShape(999.dp))
                    .padding(start = 9.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(modeLabel(model.chatMode), color = Tok.tx2, fontFamily = Dk.mono, fontSize = 11.sp)
                Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Tok.muted, modifier = Modifier.size(12.dp))
            }
            Icon(Icons.Rounded.MoreHoriz, null, tint = Tok.tx2, modifier = Modifier.size(18.dp))
        }
        val branch = model.chatBranch?.let { "  ·  ⑂ $it" } ?: ""
        Text(
            "${model.chatWorkdir}$branch  ·  ${model.chatModel}",
            color = Tok.tx2, fontFamily = Dk.mono, fontSize = 11.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 10.dp),
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
    }
}

@Composable
private fun MessageRow(item: ChatItem) {
    when (item) {
        is ChatItem.User -> Column {
            Text("You", color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
            Spacer(Modifier.height(7.dp))
            Text(item.text, color = Tok.tx, fontFamily = Dk.ui, fontSize = 14.5.sp, lineHeight = 22.sp)
        }
        is ChatItem.Assistant -> MarkdownText(item.text, Tok.tx)
        is ChatItem.Thinking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("💭", fontSize = 12.sp)
            Text(
                item.seconds?.let { "Thought for ${it}s" } ?: "Thinking…",
                color = Tok.muted, fontFamily = Dk.ui, fontSize = 12.5.sp,
            )
        }
        is ChatItem.Tool -> ToolRow(item.tool, item.preview, ToolStatus.OK)
        is ChatItem.Sys -> Text(
            item.text, color = Tok.tx2, fontFamily = Dk.mono, fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Tok.surface)
                .border(1.dp, Tok.hair, RoundedCornerShape(8.dp)).padding(horizontal = 11.dp, vertical = 8.dp),
        )
        is ChatItem.RuleChip -> Text(
            "Always allowing  ${item.rule}", color = Tok.accent, fontFamily = Dk.mono, fontSize = 11.sp,
            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Tok.accent.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

enum class ToolStatus { RUN, OK, FAIL }

@Composable
fun ToolRow(name: String, cmd: String, status: ToolStatus) {
    val col = when (status) { ToolStatus.OK -> Tok.ok; ToolStatus.FAIL -> Tok.danger; ToolStatus.RUN -> Tok.accent }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Tok.surface)
            .border(1.dp, Tok.hair, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Dot(col, 7.dp)
        Text(name, color = Tok.tx, fontFamily = Dk.ui, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        Text(
            cmd, color = Tok.tx2, fontFamily = Dk.mono, fontSize = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        when (status) {
            ToolStatus.OK -> Icon(Icons.Rounded.Check, null, tint = Tok.ok, modifier = Modifier.size(14.dp))
            ToolStatus.FAIL -> Icon(Icons.Rounded.Close, null, tint = Tok.danger, modifier = Modifier.size(14.dp))
            ToolStatus.RUN -> {}
        }
    }
}

private fun Modifier.blinkAccent(): Modifier = composed {
    val a by rememberInfiniteTransition().animateFloat(
        initialValue = 1f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(530), RepeatMode.Reverse),
    )
    background(Tok.accent.copy(alpha = a))
}

@Composable
private fun Composer(model: DesktopModel) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Column(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = Dk.maxStreamWidth).fillMaxWidth()) {
                val submit = { if (model.composer.isNotBlank()) model.send(model.composer) }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface)
                        .border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Icon(AttachImageIcon, null, tint = Tok.tx2, modifier = Modifier.size(17.dp).padding(bottom = 5.dp))
                    Box(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        if (model.composer.isEmpty()) {
                            Text("Message ${if (model.chatAgent == AgentKind.CODEX) "Codex" else "Claude"}…", color = Tok.muted, fontFamily = Dk.ui, fontSize = 14.sp)
                        }
                        BasicTextField(
                            value = model.composer,
                            onValueChange = { model.composer = it },
                            textStyle = TextStyle(color = Tok.tx, fontFamily = Dk.ui, fontSize = 14.sp),
                            cursorBrush = SolidColor(Tok.accent),
                            modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
                                if (e.key == Key.Enter && e.type == KeyEventType.KeyDown && !e.isShiftPressed) { submit(); true } else false
                            },
                        )
                    }
                    Box(
                        Modifier.size(34.dp).clip(RoundedCornerShape(999.dp)).background(Tok.accent).clickable { submit() },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.ArrowUpward, null, tint = Tok.base, modifier = Modifier.size(16.dp)) }
                }
                Row(Modifier.fillMaxWidth().padding(top = 7.dp, start = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Key("⏎"); Text("send", color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp)
                    Key("⇧⏎"); Text("newline", color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp)
                }
            }
        }
    }
}
