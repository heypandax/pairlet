package dev.ccpocket.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.AgentTag
import dev.ccpocket.app.ui.fleet.AttentionBadge
import dev.ccpocket.app.ui.fmtMmSs
import dev.ccpocket.app.ui.tilde
import dev.ccpocket.protocol.AgentKind

private enum class PKind(val tag: String) { MACHINE("machine"), ACTION("action"), PROJECT("project"), SESSION("session") }

private class PItem(
    val kind: PKind,
    val label: String,
    val detail: String,
    val icon: ImageVector,
    val codex: Boolean,
    val hint: String? = null,   // right-aligned keycap ("⌘2")
    val badge: Int = 0,         // AttentionBadge count (approvals waiting on that machine)
    val accent: Boolean = false, // terracotta label — the "needs you" verbs
    val activate: () -> Unit,
)

/** Flatten the model into palette rows: machine verbs lead (Fleet ⑧), then projects and sessions. */
private fun buildItems(model: DesktopModel): List<PItem> = buildList {
    fun projectItem(p: DkProject) = PItem(PKind.PROJECT, p.name, tilde(p.path), Icons.Outlined.Folder, false) { model.openProject(p) }
    // scoped mode ("All projects…"): the full project list plus the type-any-path entry, nothing else
    if (model.palette == PaletteScope.PROJECTS) {
        // type any path — the daemon creates a missing leaf folder
        add(PItem(PKind.ACTION, "New session at path…", "~/", Icons.Outlined.Folder, false) { model.openNewSession("~/") })
        model.projects.forEach { add(projectItem(it)) }
        return@buildList
    }
    // MACHINES — reachable via the switcher (⌘0, then the digit); badges surface approvals waiting over there
    model.machines.forEachIndexed { i, m ->
        val c = m.computer
        val detail = when {
            m.thisMachine -> "this Mac"
            m.active -> "current"
            else -> c.meta.ifBlank { c.accountId }
        }
        add(
            PItem(
                PKind.MACHINE, "Switch to ${c.name}", detail, osIcon(c.os), false,
                hint = if (i < 9) "⌘0 ${i + 1}" else null, badge = m.pending,
            ) { model.selectComputer(c) },
        )
    }
    // ACTIONS — start work on a machine / clear what's waiting, straight from the keyboard
    model.activeComputer?.let { c ->
        val where = model.newSessionDir?.let { tilde(it) } ?: "" // the dir it will actually start in
        add(PItem(PKind.ACTION, "New session on ${c.name}…", where, Icons.Outlined.Folder, false) { model.openNewSession() })
    }
    model.attention.forEach { a ->
        add(
            PItem(
                PKind.ACTION, "Approve pending on ${a.machine}", "${a.tool} · ${a.preview}", Icons.Outlined.Shield, false,
                hint = a.seconds?.let(::fmtMmSs), accent = true,
            ) { model.showAttention = true },
        )
    }
    model.projects.forEach { add(projectItem(it)) }
    model.sessions.forEach { s -> add(PItem(PKind.SESSION, s.title, tilde(s.cwd), Icons.Outlined.ChatBubbleOutline, s.agent == AgentKind.CODEX) { model.selectSession(s) }) }
}

/** Case-insensitive rank: label-prefix > label-substring > detail-substring; 0 filters the row out. */
private fun PItem.score(q: String): Int {
    if (q.isBlank()) return 1
    val ql = q.lowercase()
    val l = label.lowercase()
    return when {
        l.startsWith(ql) -> 3
        l.contains(ql) -> 2
        detail.lowercase().contains(ql) -> 1
        else -> 0
    }
}

/**
 * ⌘K command palette — a centered fuzzy jumper over computers / projects / sessions. Opens from the title-bar
 * Search chip or ⌘K (Ctrl+K); ↑/↓ move, ⏎ opens, Esc closes. The list is flat and ranked (a per-row type tag
 * replaces group headers) so keyboard nav maps 1:1 to the rendered index.
 */
@Composable
fun CommandPalette(model: DesktopModel, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var active by remember { mutableStateOf(0) }
    val all = remember(model.machines, model.attention, model.projects, model.sessions, model.palette) { buildItems(model) }
    val items = remember(all, query) {
        if (query.isBlank()) all.take(60) // blank query keeps source order — skip the score/sort/strip pass
        else all.mapNotNull { it.score(query).takeIf { s -> s > 0 }?.let { s -> it to s } }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(60)
    }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query) { active = 0; listState.scrollToItem(0) }
    LaunchedEffect(active, items.size) { if (active in items.indices) listState.animateScrollToItem(active) }

    fun open(i: Int) { items.getOrNull(i)?.let { it.activate(); onDismiss() } }

    Column(
        Modifier.width(560.dp).shadow(30.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(Tok.raised).border(1.dp, Tok.hair, RoundedCornerShape(14.dp)),
    ) {
        // search row
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Icon(Icons.Rounded.Search, null, tint = Tok.muted, modifier = Modifier.size(17.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        if (model.palette == PaletteScope.PROJECTS) "Open a project…" else "Jump to a project, session, or computer…",
                        color = Tok.muted, fontFamily = Dk.ui, fontSize = 14.5.sp,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Tok.tx, fontFamily = Dk.ui, fontSize = 14.5.sp),
                    cursorBrush = SolidColor(Tok.accent),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionDown -> { if (items.isNotEmpty()) active = (active + 1).coerceAtMost(items.lastIndex); true }
                            Key.DirectionUp -> { active = (active - 1).coerceAtLeast(0); true }
                            Key.Enter -> { open(active); true }
                            Key.Escape -> { onDismiss(); true }
                            else -> false
                        }
                    },
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        // results
        if (items.isEmpty()) {
            Text("No matches", color = Tok.muted, fontFamily = Dk.ui, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp))
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp).padding(6.dp)) {
                itemsIndexed(items, key = { _, it -> it.kind.name + it.label + it.detail }) { i, it ->
                    PaletteRow(it, query, selected = i == active, onClick = { open(i) })
                }
            }
        }
        // footer hint
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Key("↑"); Key("↓"); Text("navigate", color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp)
            Spacer(Modifier.width(6.dp))
            Key("⏎"); Text("open", color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Text("${items.size} result${if (items.size == 1) "" else "s"}", color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.5.sp)
        }
    }
}

@Composable
private fun PaletteRow(item: PItem, query: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(42.dp)
            .then(if (selected) Modifier.background(Tok.raised) else Modifier.hoverFill())
            .clickable(onClick = onClick),
    ) {
        if (selected) Box(Modifier.align(Alignment.CenterStart).padding(vertical = 6.dp).width(2.dp).fillMaxHeight().background(Tok.accent, RoundedCornerShape(2.dp)))
        Row(
            Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Box(Modifier.width(16.dp), contentAlignment = Alignment.Center) {
                Icon(item.icon, null, tint = if (selected) Tok.tx else Tok.tx2, modifier = Modifier.size(16.dp))
            }
            Text(
                highlight(item.label, query),
                color = if (item.accent) Tok.accent else Tok.tx, fontFamily = Dk.ui, fontSize = 13.5.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (item.codex) AgentTag(AgentKind.CODEX)
            Text(
                item.detail, color = Tok.muted, fontFamily = Dk.mono, fontSize = 11.5.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (item.badge > 0) AttentionBadge(item.badge)
            when {
                item.hint != null -> Key(item.hint)
                else -> Text(item.kind.tag, color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.5.sp)
            }
        }
    }
}

/** Wrap the first case-insensitive [query] match inside [name] in the accent color (palette emphasis). */
private fun highlight(name: String, query: String) = buildAnnotatedString {
    val q = query.trim()
    val idx = if (q.isEmpty()) -1 else name.lowercase().indexOf(q.lowercase())
    if (idx < 0) {
        append(name)
    } else {
        append(name.substring(0, idx))
        withStyle(SpanStyle(color = Tok.accent)) { append(name.substring(idx, idx + q.length)) }
        append(name.substring(idx + q.length))
    }
}
