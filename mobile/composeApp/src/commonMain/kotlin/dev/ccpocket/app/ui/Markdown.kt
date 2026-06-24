package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.LocalFontScale
import dev.ccpocket.app.theme.Tok
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * A focused Markdown renderer for assistant output — covers fenced code blocks (language label +
 * copy), inline code, bold, headers, and bullet/numbered lists. Fully themed via [Tok].
 */
@Composable
fun MarkdownText(text: String, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        parseBlocks(text).forEach { block ->
            when (block) {
                is MdBlock.Code -> CodeBlock(block.code, block.lang)
                is MdBlock.Table -> TableBlock(block, color)
                is MdBlock.Lines -> block.lines.forEach { MdLine(it, color) }
            }
        }
    }
}

/** A small "copy/copied" affordance that copies [text] to the clipboard and flashes confirmation. */
@Composable
fun CopyChip(text: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    Text(
        stringResource(if (copied) Res.string.code_copied else Res.string.code_copy),
        color = if (copied) Tok.ok else Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp * LocalFontScale.current,
        modifier = modifier.clip(RoundedCornerShape(5.dp))
            .clickable { clipboard.setText(AnnotatedString(text)); copied = true }
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/** Fenced block per the design: hairline container, surface header (language + copy), mono body. */
@Composable
private fun CodeBlock(code: String, lang: String?) {
    val shape = RoundedCornerShape(10.dp)
    val scale = LocalFontScale.current
    Column(Modifier.fillMaxWidth().clip(shape).background(Tok.base).border(1.dp, Tok.hair, shape)) {
        Row(
            Modifier.fillMaxWidth().background(Tok.surface).padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(lang ?: "code", color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp * scale, modifier = Modifier.weight(1f))
            CopyChip(code)
        }
        Text(
            code, color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 12.sp * scale,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(10.dp),
        )
    }
}

@Composable
private fun MdLine(raw: String, color: Color) {
    val line = raw.trimEnd()
    val trimmed = line.trimStart()
    val scale = LocalFontScale.current
    val body = 14.sp * scale // explicit so the chat text scale (issue #8) reaches plain body/list lines too
    when {
        line.isBlank() -> Spacer(Modifier.height(3.dp))
        line.startsWith("#") -> {
            val level = line.takeWhile { it == '#' }.length
            Text(
                inline(line.drop(level).trim()),
                color = color,
                fontWeight = FontWeight.Bold,
                fontSize = (when (level) { 1 -> 19.sp; 2 -> 17.sp; else -> 15.sp }) * scale,
            )
        }
        trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") -> {
            val indent = (line.length - trimmed.length).coerceAtMost(8)
            Row(Modifier.padding(start = (indent * 3).dp)) {
                Text("•  ", color = color, fontSize = body)
                Text(inline(trimmed.drop(2)), color = color, fontSize = body)
            }
        }
        Regex("^\\d+\\. ").containsMatchIn(trimmed) -> Text(inline(line), color = color, fontSize = body)
        else -> Text(inline(line), color = color, fontSize = body)
    }
}

/** A GFM table: hairline grid, surface header row (bold), zebra body rows; cells reuse [inline] styling. */
@Composable
private fun TableBlock(table: MdBlock.Table, color: Color) {
    val shape = RoundedCornerShape(10.dp)
    val cols = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, Tok.hair, shape)) {
        TableRow(table.header, cols, color, header = true)
        table.rows.forEachIndexed { idx, row ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
            TableRow(row, cols, color, header = false, zebra = idx % 2 == 1)
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, cols: Int, color: Color, header: Boolean, zebra: Boolean = false) {
    val bg = when { header -> Tok.surface; zebra -> Tok.base; else -> Color.Transparent }
    Row(Modifier.fillMaxWidth().background(bg).height(IntrinsicSize.Min)) {
        for (c in 0 until cols) {
            if (c > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(Tok.hair))
            Text(
                inline(cells.getOrElse(c) { "" }.trim()),
                color = color,
                fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
                fontSize = 13.sp * LocalFontScale.current,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

/** Inline **bold** and `code`. */
private fun inline(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        when {
            s.startsWith("**", i) -> {
                val e = s.indexOf("**", i + 2)
                if (e >= 0) { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(i + 2, e)) }; i = e + 2 }
                else { append("**"); i += 2 }
            }
            s[i] == '`' -> {
                val e = s.indexOf('`', i + 1)
                if (e >= 0) { withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = Tok.accent)) { append(s.substring(i + 1, e)) }; i = e + 1 }
                else { append('`'); i++ }
            }
            else -> { append(s[i]); i++ }
        }
    }
}

private sealed interface MdBlock {
    data class Code(val code: String, val lang: String?) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data class Lines(val lines: List<String>) : MdBlock
}

/** Split a GFM table row into trimmed cells, dropping the optional outer pipes and honoring `\|` escapes. */
private fun tableCells(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|")
        .split(Regex("""(?<!\\)\|""")).map { it.replace("\\|", "|").trim() }

/** A GFM delimiter row: every cell is dashes with optional `:` alignment markers (e.g. `|:---|---:|`). */
private fun isTableDelim(line: String): Boolean {
    if (!line.contains('|') && !line.contains('-')) return false
    val cells = tableCells(line)
    return cells.isNotEmpty() && cells.all { it.isNotEmpty() && it.matches(Regex("""^:?-+:?$""")) }
}

private fun parseBlocks(text: String): List<MdBlock> {
    val blocks = ArrayList<MdBlock>()
    val lines = text.split("\n")
    val buf = ArrayList<String>()
    fun flush() { if (buf.isNotEmpty()) { blocks += MdBlock.Lines(buf.toList()); buf.clear() } }
    var i = 0
    while (i < lines.size) {
        if (lines[i].trimStart().startsWith("```")) {
            flush()
            // the fence info string ("```kotlin") names the language; blank => null
            val lang = lines[i].trimStart().drop(3).trim().takeWhile { !it.isWhitespace() }.ifBlank { null }
            val code = ArrayList<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) { code += lines[i]; i++ }
            i++ // skip the closing fence
            blocks += MdBlock.Code(code.joinToString("\n"), lang)
        } else if (lines[i].contains('|') && i + 1 < lines.size && isTableDelim(lines[i + 1])) {
            // GFM table: a header row followed by a delimiter row (the delimiter guards against false positives).
            flush()
            val header = tableCells(lines[i])
            i += 2 // header + delimiter
            val rows = ArrayList<List<String>>()
            while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) { rows += tableCells(lines[i]); i++ }
            blocks += MdBlock.Table(header, rows)
        } else {
            buf += lines[i]; i++
        }
    }
    flush()
    return blocks
}
