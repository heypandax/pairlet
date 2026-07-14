package dev.ccpocket.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.DiffEmptyState
import dev.ccpocket.protocol.PluginInfo
import dev.ccpocket.protocol.SkillInfo
import dev.ccpocket.protocol.SkillScope

// ════════════════════════════════════════════════════════════════════
//  Skills — the desktop installed skills/plugins browser (issue #132).
//  Left: the machine's skills (user + open project) and plugins;
//  right: the selected entry's description, frontmatter and body
//  excerpt. Same overlay language as Changes (⌘K-style modal).
// ════════════════════════════════════════════════════════════════════

/** One flat row of the left rail — a skill or a plugin under its section header. */
private sealed interface SkillRow {
    val key: String
    data class Skill(val info: SkillInfo) : SkillRow {
        override val key = "s:${info.scope}:${info.name}"
    }
    data class Plugin(val info: PluginInfo) : SkillRow {
        override val key = "p:${info.marketplace ?: ""}:${info.name}"
    }
}

@Composable
fun SkillsOverlay(model: DesktopModel, onDismiss: () -> Unit) {
    val catalog = model.skillCatalog
    val rows = remember(catalog) {
        (catalog?.skills?.map { SkillRow.Skill(it) } ?: emptyList()) +
            (catalog?.plugins?.map { SkillRow.Plugin(it) } ?: emptyList())
    }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    // first load (or the selection left the list): land on the first entry
    LaunchedEffect(rows) {
        if (rows.isNotEmpty() && rows.none { it.key == selectedKey }) selectedKey = rows.first().key
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(
        Modifier.widthIn(max = 960.dp).fillMaxWidth(0.88f).heightIn(max = 620.dp).fillMaxHeight(0.86f)
            .shadow(30.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
            .background(Tok.raised).border(1.dp, Tok.hair, RoundedCornerShape(14.dp))
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val idx = rows.indexOfFirst { it.key == selectedKey }
                when (e.key) {
                    Key.DirectionDown -> { rows.getOrNull(idx + 1)?.let { selectedKey = it.key }; true }
                    Key.DirectionUp -> { rows.getOrNull(idx - 1)?.let { selectedKey = it.key }; true }
                    else -> false
                }
            },
    ) {
        // header
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Skills & plugins", color = Tok.tx, fontFamily = Dk.ui, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            catalog?.let {
                Text(
                    "${it.skills.size} skills · ${it.plugins.size} plugins",
                    color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.sp,
                )
            }
            Box(Modifier.weight(1f))
            Icon(
                Icons.Rounded.Close, null, tint = Tok.tx2,
                modifier = Modifier.size(24.dp).clip(RoundedCornerShape(999.dp)).clickable(onClick = onDismiss).padding(3.dp),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))

        when {
            model.skillCatalogLoading && catalog == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Tok.accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            }
            catalog == null && model.skillCatalogStale -> Box(Modifier.weight(1f).fillMaxWidth()) {
                // no reply at all — the daemon predates pocket/skills.* (mirrors Changes' stale banner)
                DiffEmptyState(
                    glyph = ">_",
                    title = "Update the daemon",
                    caption = "This computer's daemon doesn't know how to list skills yet — update it to browse them here.",
                )
            }
            rows.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth()) {
                DiffEmptyState(glyph = "◇", title = "Nothing installed", caption = "No skills or plugins found under ~/.claude on this computer.")
            }
            else -> Row(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.width(280.dp).fillMaxHeight().background(Tok.raised)) {
                    val skills = rows.filterIsInstance<SkillRow.Skill>()
                    val plugins = rows.filterIsInstance<SkillRow.Plugin>()
                    if (skills.isNotEmpty()) item(key = "hdr:skills") { SectionHeader("SKILLS") }
                    items(skills, key = { it.key }) { row ->
                        EntryRow(
                            name = row.info.name,
                            detail = row.info.description,
                            tag = if (row.info.scope == SkillScope.PROJECT) "project" else null,
                            selected = row.key == selectedKey,
                        ) { selectedKey = row.key }
                    }
                    if (plugins.isNotEmpty()) item(key = "hdr:plugins") { SectionHeader("PLUGINS") }
                    items(plugins, key = { it.key }) { row ->
                        EntryRow(
                            name = row.info.name,
                            detail = row.info.description,
                            tag = row.info.version?.let { "v$it" },
                            selected = row.key == selectedKey,
                        ) { selectedKey = row.key }
                    }
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(Tok.hair))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (val sel = rows.firstOrNull { it.key == selectedKey }) {
                        is SkillRow.Skill -> SkillDetail(sel.info)
                        is SkillRow.Plugin -> PluginDetail(sel.info)
                        null -> {}
                    }
                }
            }
        }

        // footer: keyboard hints
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Row(
            Modifier.fillMaxWidth().height(32.dp).background(Tok.raised).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FootHintText("↑↓", "switch entry")
            FootHintText("esc", "close")
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        label, color = Tok.muted, fontFamily = Dk.ui, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun EntryRow(name: String, detail: String, tag: String?, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(if (selected) Tok.accent.copy(alpha = 0.10f) else Tok.raised)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                name, color = if (selected) Tok.tx else Tok.tx2, fontFamily = Dk.mono, fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            tag?.let { TagChip(it) }
        }
        if (detail.isNotBlank()) {
            Text(
                detail, color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TagChip(label: String) {
    Text(
        label, color = Tok.muted, fontFamily = Dk.mono, fontSize = 9.sp,
        modifier = Modifier.clip(RoundedCornerShape(999.dp))
            .background(Tok.hair.copy(alpha = 0.5f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun SkillDetail(s: SkillInfo) {
    DetailScaffold(
        title = s.name,
        subtitle = s.description,
        facts = buildList {
            add("scope" to if (s.scope == SkillScope.PROJECT) "project" else "user")
            s.path?.let { add("path" to it) }
            s.meta.forEach { (k, v) -> add(k to v) }
        },
        body = s.excerpt,
        truncated = s.truncated,
    )
}

@Composable
private fun PluginDetail(p: PluginInfo) {
    DetailScaffold(
        title = p.name,
        subtitle = p.description,
        facts = buildList {
            p.version?.let { add("version" to it) }
            p.marketplace?.let { add("marketplace" to it) }
            p.scope?.let { add("scope" to it) }
            p.author?.let { add("author" to it) }
            p.homepage?.let { add("homepage" to it) }
            if (p.commands.isNotEmpty()) add("commands" to p.commands.joinToString(", ") { "/$it" })
            p.path?.let { add("path" to it) }
        },
        body = p.excerpt,
        truncated = p.truncated,
    )
}

/** The shared right pane: title, description, key-value facts, then the capped body excerpt. */
@Composable
private fun DetailScaffold(title: String, subtitle: String, facts: List<Pair<String, String>>, body: String, truncated: Boolean) {
    SelectionContainer {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
            Text(title, color = Tok.tx, fontFamily = Dk.mono, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
            if (facts.isNotEmpty()) {
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    facts.forEach { (k, v) ->
                        Row {
                            Text(
                                k, color = Tok.muted, fontFamily = Dk.mono, fontSize = 11.sp,
                                modifier = Modifier.width(110.dp),
                            )
                            Text(v, color = Tok.tx2, fontFamily = Dk.mono, fontSize = 11.sp)
                        }
                    }
                }
            }
            if (body.isNotBlank()) {
                Box(Modifier.fillMaxWidth().padding(vertical = 14.dp).height(1.dp).background(Tok.hair))
                Text(body, color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.sp, lineHeight = 19.sp)
                if (truncated) {
                    Text(
                        "… trimmed — open the file on the computer for the rest", color = Tok.muted,
                        fontFamily = Dk.ui, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FootHintText(keycap: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            keycap, color = Tok.tx2, fontFamily = Dk.mono, fontSize = 10.sp,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Tok.hair.copy(alpha = 0.5f)).padding(horizontal = 5.dp, vertical = 1.dp),
        )
        Text(label, color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp)
    }
}
