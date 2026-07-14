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
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.WarningAmber
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.PluginInfo
import dev.ccpocket.protocol.SkillInfo
import dev.ccpocket.protocol.SkillScope

// ════════════════════════════════════════════════════════════════════
//  Skills — the desktop installed skills/plugins browser (issue #132).
//  Left: the machine's skills (user + open project) and plugins;
//  right: a man-page-style detail — mono headline + kind badge, lede,
//  aligned facts table, then the SKILL.md/README excerpt with inline
//  `code` chips. Same overlay language as Changes (⌘K-style modal).
//  Visuals track docs/design/claude-design-handoff/0714-batch/.
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
            .background(Tok.base).border(1.dp, Tok.hair, RoundedCornerShape(14.dp))
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
        // header — 52pt bar, count only when the catalog is populated
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(start = 20.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Skills & plugins", color = Tok.tx, fontFamily = Dk.ui, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            catalog?.takeIf { rows.isNotEmpty() }?.let {
                Text(
                    "${it.skills.size} skills · ${it.plugins.size} plugins",
                    color = Tok.muted, fontFamily = Dk.mono, fontSize = 11.5.sp,
                )
            }
            Box(Modifier.weight(1f))
            Icon(
                Icons.Rounded.Close, null, tint = Tok.tx2,
                modifier = Modifier.size(24.dp).clip(RoundedCornerShape(999.dp)).clickable(onClick = onDismiss).padding(3.dp),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                model.skillCatalogLoading && catalog == null -> LoadingPane(model.activeComputer?.name)
                catalog == null && model.skillCatalogStale -> CenterStatePane(
                    icon = Icons.Rounded.WarningAmber, tint = Tok.warn,
                    title = "Can't browse skills",
                    sub = "The cc-pocket daemon on this computer is too old. Update it to read installed skills and plugins.",
                )
                rows.isEmpty() -> CenterStatePane(
                    icon = Icons.Rounded.Bolt, tint = Tok.muted,
                    title = "Nothing installed",
                    sub = "No skills or plugins found on this computer. Add a SKILL.md under .claude/skills or install a plugin from a marketplace.",
                )
                else -> Row(Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.width(280.dp).fillMaxHeight().background(Tok.surface)) {
                        val skills = rows.filterIsInstance<SkillRow.Skill>()
                        val plugins = rows.filterIsInstance<SkillRow.Plugin>()
                        if (skills.isNotEmpty()) item(key = "hdr:skills") { RailHeader("SKILLS") }
                        items(skills, key = { it.key }) { row ->
                            RailRow(
                                name = row.info.name,
                                detail = row.info.description,
                                tag = if (row.info.scope == SkillScope.PROJECT) "project" else null,
                                tagAccent = true,
                                selected = row.key == selectedKey,
                            ) { selectedKey = row.key }
                        }
                        if (plugins.isNotEmpty()) item(key = "hdr:plugins") { RailHeader("PLUGINS") }
                        items(plugins, key = { it.key }) { row ->
                            RailRow(
                                name = row.info.name,
                                detail = row.info.description,
                                tag = row.info.version?.let { "v$it" },
                                tagAccent = false,
                                selected = row.key == selectedKey,
                            ) { selectedKey = row.key }
                        }
                        item(key = "rail:tail") { Box(Modifier.height(10.dp)) }
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
        }

        // footer: keyboard hints — 40pt surface bar
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Row(
            Modifier.fillMaxWidth().height(40.dp).background(Tok.surface).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FootHintText("↑↓", "switch entry")
            Text("·", color = Tok.hair, fontFamily = Dk.ui, fontSize = 12.sp)
            FootHintText("esc", "close")
        }
    }
}

// ── left rail ────────────────────────────────────────────────────────

@Composable
private fun RailHeader(label: String) {
    Text(
        label, color = Tok.muted, fontFamily = Dk.ui, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.4.sp,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 7.dp),
    )
}

@Composable
private fun RailRow(name: String, detail: String, tag: String?, tagAccent: Boolean, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .background(if (selected) Tok.accent.copy(alpha = 0.12f) else Tok.surface)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // the selected row's 2pt accent bar, vertically inset from the row's edges
        Box(
            Modifier.width(2.dp).fillMaxHeight().padding(vertical = 6.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (selected) Tok.accent else Color.Transparent),
        )
        Row(
            Modifier.weight(1f).padding(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    name, color = Tok.tx, fontFamily = Dk.mono, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (detail.isNotBlank()) {
                    Text(
                        detail, color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.5.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            tag?.let {
                // project tag reads terracotta; version tags stay quiet
                val tint = if (tagAccent) Tok.accent else Tok.tx2
                val borderTint = if (tagAccent) Tok.accent.copy(alpha = 0.33f) else Tok.hair
                Text(
                    it, color = tint, fontFamily = Dk.mono, fontSize = 10.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp))
                        .border(1.dp, borderTint, RoundedCornerShape(999.dp))
                        .padding(horizontal = 7.dp, vertical = 1.dp),
                )
            }
        }
    }
}

// ── right detail pane (man-page) ─────────────────────────────────────

@Composable
private fun SkillDetail(s: SkillInfo) {
    DetailScaffold(
        title = s.name,
        kind = "skill",
        lede = s.description,
        facts = buildList {
            add(Fact("scope", if (s.scope == SkillScope.PROJECT) "project" else "user", FactKind.UI))
            add(Fact("path", s.path ?: "—", if (s.path != null) FactKind.MONO else FactKind.MUTED))
            s.meta.forEach { (k, v) -> add(Fact(k, v, FactKind.MONO)) }
        },
        commands = emptyList(),
        bodyTitle = "SKILL.md",
        body = s.excerpt,
        truncated = s.truncated,
    )
}

@Composable
private fun PluginDetail(p: PluginInfo) {
    DetailScaffold(
        title = p.name,
        kind = "plugin",
        lede = p.description,
        facts = buildList {
            add(Fact("scope", p.scope ?: "—", if (p.scope != null) FactKind.UI else FactKind.MUTED))
            add(Fact("path", p.path ?: "—", if (p.path != null) FactKind.MONO else FactKind.MUTED))
            add(Fact("version", p.version ?: "—", if (p.version != null) FactKind.MONO else FactKind.MUTED))
            add(Fact("marketplace", p.marketplace ?: "—", if (p.marketplace != null) FactKind.MONO else FactKind.MUTED))
            add(Fact("author", p.author ?: "—", if (p.author != null) FactKind.UI else FactKind.MUTED))
            if (p.commands.isNotEmpty()) add(Fact("commands", "", FactKind.CMDS))
            p.homepage?.let { add(Fact("homepage", it, FactKind.MONO)) }
        },
        commands = p.commands.map { "/$it" },
        bodyTitle = "README.md",
        body = p.excerpt,
        truncated = p.truncated,
    )
}

private enum class FactKind { UI, MONO, MUTED, CMDS }
private data class Fact(val key: String, val value: String, val kind: FactKind)

/** The shared right pane: mono headline + kind badge, lede, hairline-bracketed facts table, then
 *  the capped body excerpt set as real paragraphs with inline-code chips. */
@Composable
private fun DetailScaffold(
    title: String, kind: String, lede: String,
    facts: List<Fact>, commands: List<String>,
    bodyTitle: String, body: String, truncated: Boolean,
) {
    SelectionContainer {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 32.dp, end = 32.dp, top = 26.dp, bottom = 30.dp)
                .widthIn(max = 600.dp),
        ) {
            // headline
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, color = Tok.tx, fontFamily = Dk.mono, fontSize = 25.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp)
                Text(
                    kind.uppercase(), color = Tok.tx2, fontFamily = Dk.ui, fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp))
                        .border(1.dp, Tok.hair, RoundedCornerShape(999.dp))
                        .padding(horizontal = 9.dp, vertical = 2.dp),
                )
            }
            // lede
            if (lede.isNotBlank()) {
                Text(
                    lede, color = Tok.tx2, fontFamily = Dk.ui, fontSize = 15.sp, lineHeight = 24.sp,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            // facts table, bracketed by hairlines
            if (facts.isNotEmpty()) {
                Column(Modifier.padding(top = 22.dp)) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
                    Column(Modifier.padding(vertical = 10.dp)) {
                        facts.forEach { f ->
                            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text(
                                    f.key, color = Tok.muted, fontFamily = Dk.mono, fontSize = 12.sp,
                                    textAlign = TextAlign.End, modifier = Modifier.width(96.dp),
                                )
                                if (f.kind == FactKind.CMDS) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        commands.forEach { c ->
                                            Text(
                                                c, color = Tok.accent, fontFamily = Dk.mono, fontSize = 12.sp,
                                                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                                    .background(Tok.accent.copy(alpha = 0.10f))
                                                    .border(1.dp, Tok.accent.copy(alpha = 0.27f), RoundedCornerShape(6.dp))
                                                    .padding(horizontal = 7.dp, vertical = 1.dp),
                                            )
                                        }
                                    }
                                } else {
                                    Text(
                                        f.value,
                                        color = if (f.kind == FactKind.MUTED) Tok.muted else Tok.tx,
                                        fontFamily = if (f.kind == FactKind.MONO) Dk.mono else Dk.ui,
                                        fontSize = if (f.kind == FactKind.MONO) 12.5.sp else 13.sp,
                                    )
                                }
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
                }
            }
            // body excerpt — "SKILL.md ————" rule, then real paragraphs
            if (body.isNotBlank()) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(bodyTitle, color = Tok.muted, fontFamily = Dk.mono, fontSize = 11.sp, letterSpacing = 0.6.sp)
                    Box(Modifier.weight(1f).height(1.dp).background(Tok.hair))
                }
                body.split(Regex("\n{2,}")).map { it.trim() }.filter { it.isNotEmpty() }.forEach { para ->
                    Text(
                        inlineCodeStyled(para.replace('\n', ' ')),
                        color = Tok.tx, fontFamily = Dk.ui, fontSize = 14.sp, lineHeight = 23.sp,
                        modifier = Modifier.padding(bottom = 14.dp),
                    )
                }
                if (truncated) {
                    Text(
                        "trimmed — open the file on the computer for the rest", color = Tok.muted,
                        fontFamily = Dk.ui, fontSize = 12.5.sp, fontStyle = FontStyle.Italic,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** Inline `code` spans → mono on a slightly raised chip (AnnotatedString background — the closest
 *  Compose gets to the spec's bordered chip without per-span layout). */
@Composable
private fun inlineCodeStyled(text: String) = buildAnnotatedString {
    val codeStyle = SpanStyle(fontFamily = Dk.mono, fontSize = 12.sp, color = Tok.tx, background = Tok.raised)
    var i = 0
    for (m in Regex("`([^`]+)`").findAll(text)) {
        append(text.substring(i, m.range.first))
        pushStyle(codeStyle)
        append(" ${m.groupValues[1]} ")
        pop()
        i = m.range.last + 1
    }
    append(text.substring(i))
}

// ── transient / empty states (header + footer stay, only the body swaps) ──

@Composable
private fun LoadingPane(machine: String?) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = Tok.accent, trackColor = Tok.hair, strokeWidth = 2.6.dp, modifier = Modifier.size(30.dp))
        Text(
            machine?.let { "Reading skills from $it…" } ?: "Reading skills…",
            color = Tok.muted, fontFamily = Dk.ui, fontSize = 13.5.sp,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun CenterStatePane(icon: ImageVector, tint: Color, title: String, sub: String) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(15.dp)).background(Tok.surface)
                .border(1.dp, Tok.hair, RoundedCornerShape(15.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = tint, modifier = Modifier.size(26.dp)) }
        Text(title, color = Tok.tx2, fontFamily = Dk.ui, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
        Text(
            sub, color = Tok.muted, fontFamily = Dk.ui, fontSize = 13.5.sp, lineHeight = 21.sp,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp).widthIn(max = 340.dp),
        )
    }
}

@Composable
private fun FootHintText(keycap: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            keycap, color = Tok.tx2, fontFamily = Dk.mono, fontSize = 10.sp,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Tok.hair.copy(alpha = 0.5f)).padding(horizontal = 5.dp, vertical = 1.dp),
        )
        Text(label, color = Tok.muted, fontFamily = Dk.ui, fontSize = 12.sp)
    }
}
