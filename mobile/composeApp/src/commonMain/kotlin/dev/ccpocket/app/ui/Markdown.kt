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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.LocalFontScale
import dev.ccpocket.app.theme.Tok
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * Linkifies filesystem paths in transcript text when the host platform can open them locally —
 * the desktop shell provides an opener (Finder / Explorer); mobile leaves this null so text
 * renders plain there. [exists] gates linkification, so only paths real on THIS machine become
 * links (a remote session's paths stay inert instead of dead-clicking).
 */
interface PathOpener {
    fun exists(path: String): Boolean
    fun open(path: String)
}

val LocalPathOpener = staticCompositionLocalOf<PathOpener?> { null }

/** The open session's working directory, so a cwd-relative path can be normalized to an absolute one
 *  for copy/share (issue #116). Set on both platforms (desktop: chatWorkdir; mobile: repo.workdir);
 *  null before a session is open, when relative paths simply copy verbatim. */
val LocalPathCwd = staticCompositionLocalOf<String?> { null }

// Four path shapes, all with the lookbehind that keeps URL tails ("https://host/a/b") and word/word
// compounds from matching:
//   • unix `/a/b`  (≥2 segments so prose like "/help" rarely trips it)
//   • home `~/a`
//   • windows drive `C:\a\b`
//   • RELATIVE `dir/…/file.ext` (issue #74): a session's cwd-relative path, e.g.
//     "10_Notes/会议/2026-07-09_对齐材料.md". Anchorless, so it's deliberately conservative — it must
//     have ≥1 slash AND end in a short extension. That keeps "and/or", "TCP/IP", "src/main" prose from
//     linkifying, while the exists() gate (resolved against cwd) drops anything not real on this disk.
// Segments use \p{L} + 0-9, not [A-Za-z0-9] — CJK filenames are routine here ("~/…/设计提示词.md")
// and an ASCII-only class truncated them mid-path, so exists() failed and the link never formed.
//
// LAZY + FAULT-TOLERANT, never a top-level `val`: Kotlin/Native's regex engine rejects constructs
// the JVM accepts (\p{N} → "No such character class"), and a throwing top-level initializer took
// down the whole FILE on iOS — first Markdown render (= tapping any session) died with
// FileFailedToInitializeException. Null here just disables path links; the transcript must render.
internal val pathRx: Regex? by lazy {
    runCatching {
        Regex("""(?<![\w:/])(?:~(?:/[\p{L}0-9._+@%-]+)+|/[\p{L}0-9._+@%-]+(?:/[\p{L}0-9._+@%-]+)+|[A-Za-z]:[\\/][\p{L}0-9._+@%\\/-]+|(?:[\p{L}0-9._+@%-]+/)+[\p{L}0-9._+@%-]*\.[\p{L}0-9]{1,8})/?""")
    }.getOrNull()
}

/** Adds clickable link spans over every path in [this] that [opener] confirms exists locally. */
fun AnnotatedString.withPathLinks(opener: PathOpener?): AnnotatedString {
    if (opener == null || (!text.contains('/') && !text.contains('\\'))) return this
    val rx = pathRx ?: return this
    // verify matches BEFORE paying for an AnnotatedString copy: this runs per streamed chunk on the
    // desktop transcript, and most lines have no live local path (URLs, remote-machine paths)
    val hits = rx.findAll(text).mapNotNull { m ->
        val path = m.value.trimEnd('.') // sentence-final period is punctuation, not the path
        if (opener.exists(path)) m.range.first to path else null
    }.toList()
    return withLinks(hits, "path", opener::open)
}

/** The one link-span builder both passes share: [hits] are (startOffset, matchedText) pairs. */
private fun AnnotatedString.withLinks(hits: List<Pair<Int, String>>, tag: String, open: (String) -> Unit): AnnotatedString {
    if (hits.isEmpty()) return this
    return buildAnnotatedString {
        append(this@withLinks)
        for ((start, s) in hits) {
            addLink(
                LinkAnnotation.Clickable(
                    tag,
                    TextLinkStyles(SpanStyle(color = Tok.info, textDecoration = TextDecoration.Underline)),
                ) { open(s) },
                start,
                start + s.length,
            )
        }
    }
}

// http(s) only (matches what the platform viewers accept). The char class is an ALLOWLIST of
// RFC 3986-ish URL characters (quotes/backticks stay out on purpose: they are prose wrappers) —
// full-width/CJK punctuation ends a URL by simply not being in the set. The old blocklist
// enumerated the full-width closers it knew (）】」，。…) and any it forgot let the match run on:
// the full-width OPEN paren in "[文本](https://…/X)（X）" made it swallow ")（X" whole, burying
// the markdown link's `)` mid-match where the trailing trim can't reach it (issue #154).
internal val urlRx: Regex? by lazy {
    runCatching { Regex("""https?://[A-Za-z0-9._~:/?#@!$&()*+,;=%\[\]-]+""") }.getOrNull()
}
internal const val URL_TRAIL = ".,;:!?)]}>"

/**
 * The one URL end-boundary rule (issue #154), shared by [withUrlLinks] and [recognizeEntities].
 * [m] is a raw [urlRx] match inside [text]; returns the URL with structural/sentence tails dropped,
 * or null when nothing meaningful is left.
 *  • Paren wrapper: a match directly preceded by `(` is the markdown form `[文本](https://…)` or
 *    prose `(https://…)` — the URL ends at the first `)`, which closes the wrapper, not the URL.
 *    (URLs with their own parens à la Wikipedia are the long tail this deliberately ignores.)
 *  • Trailing trim: sentence punctuation prose glues onto a URL ("see https://x.dev/docs.") stays out.
 */
internal fun cleanUrl(text: String, m: MatchResult): String? {
    var url = m.value
    if (m.range.first > 0 && text[m.range.first - 1] == '(') {
        val close = url.indexOf(')')
        if (close >= 0) url = url.take(close)
    }
    url = url.trimEnd { it in URL_TRAIL }
    return url.takeIf { it.length > "https://".length }
}

/** Adds browser link spans over every http(s) URL — phones open an in-app browser, desktop the system
 *  one (see [dev.ccpocket.app.openWebUrl]). Unlike path links this needs no host gate: a URL is
 *  openable from any machine. */
fun AnnotatedString.withUrlLinks(): AnnotatedString {
    if (!text.contains("http")) return this
    val rx = urlRx ?: return this
    val hits = rx.findAll(text).mapNotNull { m ->
        cleanUrl(text, m)?.let { m.range.first to it }
    }.toList()
    return withLinks(hits, "url") { dev.ccpocket.app.openWebUrl(it) }
}

/** URL + path link pass against the ambient [LocalPathOpener], memoized per text (exists() hits disk). */
@Composable
fun pathLinked(text: AnnotatedString): AnnotatedString {
    val opener = LocalPathOpener.current
    return remember(text, opener) { text.withPathLinks(opener).withUrlLinks() }
}

@Composable
fun pathLinked(text: String): AnnotatedString = pathLinked(AnnotatedString(text))

/**
 * Render-size guard for transcript text. One replayed row can be hundreds of KB — e.g. a skill
 * injection replayed whole now that replay budgets the *frame* rather than clipping each message
 * (#81) — and both the plain-Text user row and this file's composable-per-line markdown body kill
 * the iOS app well before that (an ~800 KB row OOM'd it on open, in a loop). Clip what we RENDER,
 * never what we copy: every copy affordance keeps the full string. 40k chars is several times the
 * longest real streamed reply, and ~20× under the observed killer.
 */
const val MAX_RENDER_CHARS = 40_000

/** The renderable prefix of [text]: the whole string when within [MAX_RENDER_CHARS]; else cut at the
 *  last line break under the cap — hard cut mid-line only when a single line exceeds it, stepping off
 *  a straddled surrogate pair. Pair with [TruncatedNote] whenever the result came back shorter. */
fun renderClip(text: String): String {
    if (text.length <= MAX_RENDER_CHARS) return text
    val nl = text.lastIndexOf('\n', MAX_RENDER_CHARS)
    if (nl > 0) return text.substring(0, nl)
    val end = if (text[MAX_RENDER_CHARS - 1].isHighSurrogate()) MAX_RENDER_CHARS - 1 else MAX_RENDER_CHARS
    return text.substring(0, end)
}

/** The muted "clipped for display" footer under a [renderClip]-truncated row. [fullChars] is the
 *  untrimmed length, surfaced as "812K" so the reader knows what copy still carries. */
@Composable
fun TruncatedNote(fullChars: Int) {
    Text(
        stringResource(Res.string.text_render_truncated, "${fullChars / 1000}K"),
        color = Tok.muted, fontSize = 11.sp * LocalFontScale.current,
    )
}

/**
 * A focused Markdown renderer for assistant output — covers fenced code blocks (language label +
 * copy), inline code, bold, headers, and bullet/numbered lists. Fully themed via [Tok].
 */
@Composable
fun MarkdownText(text: String, color: Color) {
    val shown = renderClip(text)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        parseBlocks(shown).forEach { block ->
            when (block) {
                is MdBlock.Code -> CodeBlock(block.code, block.lang, block.closed)
                is MdBlock.Table -> TableBlock(block, color)
                is MdBlock.Lines -> block.lines.forEach { MdLine(it, color) }
            }
        }
        if (shown.length < text.length) TruncatedNote(text.length)
    }
}

/** Copy-with-feedback state shared by every copy affordance (mobile [CopyChip], desktop chat's button):
 *  `copied` flashes true for the same beat after `copy(text)` writes the clipboard, so confirmations
 *  read identically everywhere. */
@Composable
fun rememberCopied(): Pair<Boolean, (String) -> Unit> {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    return copied to { s: String -> clipboard.setText(AnnotatedString(s)); copied = true }
}

/** A small "copy/copied" affordance that copies [text] to the clipboard and flashes confirmation. */
@Composable
fun CopyChip(text: String, modifier: Modifier = Modifier) {
    val (copied, copy) = rememberCopied()
    Text(
        stringResource(if (copied) Res.string.code_copied else Res.string.code_copy),
        color = if (copied) Tok.ok else Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp * LocalFontScale.current,
        modifier = modifier.clip(RoundedCornerShape(5.dp))
            .clickable { copy(text) }
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/** Fenced block per the design: hairline container, surface header (language + copy), mono body. */
@Composable
private fun CodeBlock(code: String, lang: String?, closed: Boolean = true) {
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
        // Syntax-highlighted body when the fence names a language we know (issue #51). A still-OPEN
        // fence is the block being streamed: its `code` grows every chunk, so remember() can't cache
        // it and each append would re-tokenize the whole tail from scratch (O(n²) over the block's
        // growth, on the composition thread) — stay plain until the closing fence lands.
        // Trade-off: a highlighted block drops pathLinked()'s clickable file paths/URLs — token spans
        // and link spans would fight over the same ranges, and a clickable path inside real code is
        // rarely worth the collision. Blocks that stay plain (no/unknown lang) keep the linkified pipeline.
        val highlighted = remember(code, lang, closed) { if (closed) highlightCodeOrNull(code, lang) else null }
        Text(
            highlighted ?: pathLinked(code), color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 12.sp * scale,
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
            LinkifiedText(
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
                LinkifiedText(inline(trimmed.drop(2)), color = color, fontSize = body)
            }
        }
        else -> LinkifiedText(inline(line), color = color, fontSize = body)
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
                pathLinked(inline(cells.getOrElse(c) { "" }.trim())),
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
    data class Code(val code: String, val lang: String?, val closed: Boolean = true) : MdBlock
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
            // an unterminated fence = the block still being streamed — CodeBlock defers highlighting on it
            val closed = i < lines.size
            if (closed) i++ // skip the closing fence
            blocks += MdBlock.Code(code.joinToString("\n"), lang, closed)
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
