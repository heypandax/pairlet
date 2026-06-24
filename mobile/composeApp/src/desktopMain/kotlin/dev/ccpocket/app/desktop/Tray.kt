package dev.ccpocket.app.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.AgentGlyph
import dev.ccpocket.protocol.AgentKind

@Composable
fun TrayPopover(empty: Boolean = false, modifier: Modifier = Modifier) {
    Column(modifier.width(360.dp)) {
        // little pointer up toward the menu-bar icon
        Canvas(Modifier.width(360.dp).height(7.dp)) {
            val w = 14f; val cx = size.width / 2f
            val p = Path().apply { moveTo(cx - w, size.height); lineTo(cx, 0f); lineTo(cx + w, size.height); close() }
            drawPath(p, Tok.raised)
            drawLine(Tok.hair, Offset(cx - w, size.height), Offset(cx, 0f), strokeWidth = 1f, cap = StrokeCap.Round)
            drawLine(Tok.hair, Offset(cx, 0f), Offset(cx + w, size.height), strokeWidth = 1f, cap = StrokeCap.Round)
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Tok.raised).border(1.dp, Tok.hair, RoundedCornerShape(14.dp))) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("cc-pocket", color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                PulseDot(Tok.ok, 6.dp)
                Spacer(Modifier.weight(1f))
                Text("2 computers · 3 sessions", color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.5.sp)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
            Column(Modifier.padding(14.dp)) {
                TrayGroupLabel("Pending approvals")
                if (empty) {
                    Row(Modifier.padding(vertical = 10.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Check, null, tint = Tok.ok, modifier = Modifier.size(15.dp))
                        Text("No pending approvals", color = Tok.tx2, fontFamily = Dk.ui, fontSize = 13.sp)
                    }
                } else {
                    TrayApprovalRow(AgentKind.CLAUDE, "Lidapeng-MBP", "rm -rf ./build && ./gradlew clean")
                    TrayApprovalRow(AgentKind.CODEX, "devbox-linux", "edit src/relay/WsClient.kt  +5 −1")
                }
                Spacer(Modifier.height(14.dp))
                TrayGroupLabel("Running sessions")
                TrayRunning("Fix relay reconnect", "Lidapeng-MBP", true)
                TrayRunning("Port parser to Rust", "devbox-linux", true)
                TrayRunning("Tidy CI workflow", "Lidapeng-MBP", false)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Open cc-pocket", color = Tok.tx, fontFamily = Dk.ui, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).border(1.dp, Tok.hair, RoundedCornerShape(9.dp)).clickable { }.padding(vertical = 8.dp),
                )
                Icon(Icons.Outlined.Settings, null, tint = Tok.tx2, modifier = Modifier.size(16.dp).clip(RoundedCornerShape(6.dp)).clickable { }.padding(2.dp))
            }
        }
    }
}

@Composable
private fun TrayGroupLabel(text: String) {
    Text(text.uppercase(), color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp, modifier = Modifier.padding(bottom = 10.dp))
}

@Composable
private fun TrayApprovalRow(agent: AgentKind, computer: String, cmd: String) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(10.dp)).background(Tok.surface)
            .border(1.dp, Tok.hair, RoundedCornerShape(10.dp)).padding(11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            AgentGlyph(agent, size = 14)
            Text(computer, color = Tok.tx2, fontFamily = Dk.mono, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Text("0:22", color = Tok.warn, fontFamily = Dk.mono, fontSize = 10.5.sp)
        }
        Text(
            cmd, color = Tok.tx, fontFamily = Dk.mono, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 9.dp).clip(RoundedCornerShape(7.dp)).background(Tok.base)
                .border(1.dp, Tok.hair, RoundedCornerShape(7.dp)).padding(horizontal = 9.dp, vertical = 7.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Deny", color = Tok.danger, fontFamily = Dk.ui, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).border(1.dp, Tok.danger.copy(alpha = 0.4f), RoundedCornerShape(8.dp)).clickable { }.padding(vertical = 7.dp),
            )
            Text(
                "Allow", color = Tok.base, fontFamily = Dk.ui, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(Tok.accent).clickable { }.padding(vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun TrayRunning(title: String, computer: String, running: Boolean) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).hoverFill(RoundedCornerShape(7.dp)).clickable { }.padding(horizontal = 6.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (running) PulseDot(Tok.ok, 6.dp) else Dot(Tok.muted, 6.dp)
        Text(title, color = Tok.tx, fontFamily = Dk.ui, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(computer, color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.sp)
    }
}

/** The menu-bar / tray icon with a count badge when an approval is waiting. */
@Composable
fun TrayIcon(badge: Int = 1) {
    Box {
        Box(
            Modifier.clip(RoundedCornerShape(8.dp)).background(Tok.base).border(1.dp, Tok.hair, RoundedCornerShape(8.dp)).padding(horizontal = 9.dp, vertical = 6.dp),
        ) { AgentGlyph(AgentKind.CLAUDE, size = 17) }
        if (badge > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = 5.dp, y = (-5).dp).size(16.dp).clip(RoundedCornerShape(999.dp)).background(Tok.accent),
                contentAlignment = Alignment.Center,
            ) { Text("$badge", color = Tok.base, fontFamily = Dk.mono, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
        }
    }
}
