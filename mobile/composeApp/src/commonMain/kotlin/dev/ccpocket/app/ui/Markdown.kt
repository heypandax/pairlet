package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
                is MdBlock.Lines -> block.lines.forEach { MdLine(it, color) }
            }
        }
    }
}

/** Fenced block per the design: hairline container, surface header (language + copy), mono body. */
@Composable
private fun CodeBlock(code: String, lang: String?) {
    val shape = RoundedCornerShape(10.dp)
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    Column(Modifier.fillMaxWidth().clip(shape).background(Tok.base).border(1.dp, Tok.hair, shape)) {
        Row(
            Modifier.fillMaxWidth().background(Tok.surface).padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(lang ?: "code", color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, modifier = Modifier.weight(1f))
            Text(
                stringResource(if (copied) Res.string.code_copied else Res.string.code_copy),
                color = if (copied) Tok.ok else Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp,
                modifier = Modifier.clip(RoundedCornerShape(5.dp))
                    .clickable { clipboard.setText(AnnotatedString(code)); copied = true }
                    .padding(horizontal = 7.dp, vertical = 4.dp),
            )
        }
        Text(
            code, color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(10.dp),
        )
    }
}

@Composable
private fun MdLine(raw: String, color: Color) {
    val line = raw.trimEnd()
    val trimmed = line.trimStart()
    when {
        line.isBlank() -> Spacer(Modifier.height(3.dp))
        line.startsWith("#") -> {
            val level = line.takeWhile { it == '#' }.length
            Text(
                inline(line.drop(level).trim()),
                color = color,
                fontWeight = FontWeight.Bold,
                fontSize = when (level) { 1 -> 19.sp; 2 -> 17.sp; else -> 15.sp },
            )
        }
        trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") -> {
            val indent = (line.length - trimmed.length).coerceAtMost(8)
            Row(Modifier.padding(start = (indent * 3).dp)) {
                Text("•  ", color = color)
                Text(inline(trimmed.drop(2)), color = color)
            }
        }
        Regex("^\\d+\\. ").containsMatchIn(trimmed) -> Text(inline(line), color = color)
        else -> Text(inline(line), color = color)
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
    data class Lines(val lines: List<String>) : MdBlock
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
        } else {
            buf += lines[i]; i++
        }
    }
    flush()
    return blocks
}
