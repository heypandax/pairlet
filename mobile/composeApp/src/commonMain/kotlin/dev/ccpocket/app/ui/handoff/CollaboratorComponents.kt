package dev.ccpocket.app.ui.handoff

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.epochMillis
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.Collaborator
import dev.ccpocket.protocol.CollaboratorDirection
import org.jetbrains.compose.resources.stringResource

// ── direction glyph: muted mono, never accent (design red line) ──

fun directionGlyph(d: CollaboratorDirection): String = when (d) {
    CollaboratorDirection.MUTUAL -> "⇄"
    CollaboratorDirection.OUTBOUND -> "→"
    CollaboratorDirection.INBOUND -> "←"
    CollaboratorDirection.UNKNOWN -> ""
}

@Composable
fun DirGlyph(d: CollaboratorDirection) {
    val g = directionGlyph(d)
    if (g.isNotEmpty()) Text(g, color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
}

/** "connected 12d ago" style relative label from an epoch-ms stamp. */
fun connectedAgo(sinceMs: Long, nowMs: Long = epochMillis()): String {
    val sec = ((nowMs - sinceMs) / 1000).coerceAtLeast(0)
    return when {
        sec < 3600 -> "${sec / 60}m"
        sec < 86_400 -> "${sec / 3600}h"
        sec < 30 * 86_400 -> "${sec / 86_400}d"
        else -> "${sec / (30 * 86_400)}mo"
    }
}

/** The contact row's mono fact line: "connected 12d ago · 3 handoffs" — facts only, never presence. */
@Composable
fun collaboratorSubLine(c: Collaborator): String {
    val ago = stringResource(Res.string.co_connected_ago, connectedAgo(c.connectedAt))
    return when {
        c.removed -> ago
        c.handoffCount > 0 -> "$ago · ${stringResource(Res.string.co_handoffs_n, c.handoffCount)}"
        else -> "$ago · ${stringResource(Res.string.co_no_handoffs)}"
    }
}

/**
 * One collaborator row (design crow): 60pt, avatar + label + direction glyph + mono sub.
 * Removed rows dim to 45% and wear the terminal chip. [trailing] defaults to the chevron/no-daemon tag.
 */
@Composable
fun CollaboratorRow(c: Collaborator, onTap: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .then(if (onTap != null && !c.removed) Modifier.clickable(onClick = onTap) else Modifier)
            .alpha(if (c.removed) 0.45f else 1f)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        HandoffAvatar(c.label, accent = false, size = 32.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(c.label.ifBlank { "?" }, color = Tok.tx, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (!c.removed) DirGlyph(c.direction)
            }
            Text(
                collaboratorSubLine(c), color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                maxLines = 1, modifier = Modifier.padding(top = 4.dp),
            )
        }
        when {
            trailing != null -> trailing()
            c.removed -> Text(
                stringResource(Res.string.co_removed_chip), color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Tok.raised).border(1.dp, Tok.hair, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
            c.hasDaemon == false -> Text(
                stringResource(Res.string.co_no_daemon), color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, Tok.hair, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
            else -> Text("›", color = Tok.muted, fontSize = 14.sp)
        }
    }
}

/** Search field (design srch) — plain hairline row; filtering happens in the caller. */
@Composable
fun CollaboratorSearchField(query: String, onQuery: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
            .background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.Search, null, tint = Tok.muted, modifier = Modifier.size(16.dp))
        BasicTextField(
            query, onQuery,
            textStyle = TextStyle(color = Tok.tx, fontSize = 14.sp),
            cursorBrush = SolidColor(Tok.accent), singleLine = true,
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) Text(stringResource(Res.string.co_search), color = Tok.muted, fontSize = 14.sp)
                    inner()
                }
            },
        )
    }
}

/** Uppercase group label with optional count chip (design grpl). */
@Composable
fun CollaboratorGroupLabel(text: String, count: Int? = null) {
    Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text.uppercase(), color = Tok.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
        if (count != null) Text(count.toString(), color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp)
    }
}

/** The bordered rows container (design grpbox). */
@Composable
fun CollaboratorGroupBox(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(12.dp)),
    ) { content() }
}

@Composable
fun CollaboratorRowDivider() = Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))

/** "Connect a new colleague…" row — the ONLY surface in the flow that still leads to a QR. */
@Composable
fun ConnectNewRow(onClick: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp))
                .background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(12.dp))
                .clickable(onClick = onClick).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Icon(Icons.Rounded.QrCode2, null, tint = Tok.tx2, modifier = Modifier.size(20.dp))
            Text(stringResource(Res.string.co_connect_new), color = Tok.tx, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("›", color = Tok.muted, fontSize = 15.sp)
        }
        Text(
            stringResource(Res.string.co_connect_note), color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp,
            modifier = Modifier.padding(top = 6.dp).fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** The safety-fingerprint block (design fpr): the largest type on its screen, raised card, mono words. */
@Composable
fun FingerprintBlock(fingerprint: String, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Tok.raised).border(1.dp, Tok.hair, RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // "a-b-c-d · e-f-g-h" renders as two lines of four word-groups
        fingerprint.split("·").map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            Text(
                line.replace("-", " — "), color = Tok.tx, fontFamily = FontFamily.Monospace,
                fontSize = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp,
            )
        }
        action?.invoke()
    }
}

/** The picked-contact chip inside the draft's recipient row (design cchip): avatar + label + glyph. */
@Composable
fun ContactChip(c: Collaborator) {
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(Tok.raised).border(1.dp, Tok.hair, RoundedCornerShape(999.dp))
            .padding(start = 5.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HandoffAvatar(c.label, accent = true, size = 28.dp)
        Text(c.label.ifBlank { "?" }, color = Tok.tx, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        DirGlyph(c.direction)
    }
}

/** Filter + split helpers shared by mobile picker and desktop dropdown. */
fun filterCollaborators(all: List<Collaborator>, query: String): List<Collaborator> =
    all.filter { !it.removed && (query.isBlank() || it.label.contains(query.trim(), ignoreCase = true)) }

fun recentCollaborators(all: List<Collaborator>): List<Collaborator> =
    all.filter { !it.removed && it.lastHandoffAt != null }.sortedByDescending { it.lastHandoffAt }.take(3)
