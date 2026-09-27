package dev.ccpocket.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.LocalFontScale
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.theme.tightCenter
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

/**
 * The mobile [PathOpener] (read-doc-inline handoff): a phone has no local disk to stat, so [exists] is
 * OPTIMISTIC — every regex-matched path lights up as openable, and [open] hands it to the daemon read
 * ([onOpen] → PocketRepository.openChangedFile). A path that isn't really reachable (a typo, or a Bash
 * file outside the synced workspace) fails inside the file viewer's own error state — which carries the
 * export / copy-path escape — instead of dead-clicking in the transcript. The design deliberately accepts
 * this: the link is styled as a calm "tap to try", not a guaranteed-valid button, so an occasional miss
 * that lands on a clear error is the intended trade for making documents reachable at all.
 */
class RemotePathOpener(private val onOpen: (String) -> Unit) : PathOpener {
    override fun exists(path: String): Boolean = true
    override fun open(path: String) = onOpen(path)
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
    // Terracotta for openable-DOCUMENT paths (read-doc-inline handoff) — deliberately distinct from the
    // blue "open URL" treatment below, so the two affordances read as different destinations.
    return withLinks(hits, "path", Tok.accent, opener::open)
}

/** The one link-span builder both passes share: [hits] are (startOffset, matchedText) pairs. [color]
 *  tints the underlined span — terracotta for file paths, info-blue for URLs. */
private fun AnnotatedString.withLinks(hits: List<Pair<Int, String>>, tag: String, color: Color = Tok.info, open: (String) -> Unit): AnnotatedString {
    if (hits.isEmpty()) return this
    return buildAnnotatedString {
        append(this@withLinks)
        for ((start, s) in hits) {
            addLink(
                LinkAnnotation.Clickable(
                    tag,
                    TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline)),
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
 * copy), GFM tables, `>` blockquotes (with their own copy), headers, bullet lists, inline code and
 * bold. Fully themed via [Tok].
 */
@Composable
fun MarkdownText(text: String, color: Color) {
    val shown = renderClip(text)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        MdBlocks(parseBlocks(shown), color)
        if (shown.length < text.length) TruncatedNote(text.length)
    }
}

/** Emits [blocks] into the caller's column — the message body and every [QuoteBlock] body alike.
 *  [quoteDepth] is how many quotes enclose them, so a nested quote knows it is one. */
@Composable
private fun MdBlocks(blocks: List<MdBlock>, color: Color, quoteDepth: Int = 0) {
    blocks.forEach { block ->
        when (block) {
            is MdBlock.Code -> CodeBlock(block.code, block.lang, block.closed)
            is MdBlock.Table -> TableBlock(block, color)
            is MdBlock.Quote -> QuoteBlock(block, quoteDepth)
            is MdBlock.Lines -> block.lines.forEach { MdLine(it, color) }
        }
    }
}

/** The blank line between paragraphs. With the body's 3dp block spacing on both sides the default reads as 9dp;
 *  the phone transcript provides 4dp for Chat Rhythm v1's 10dp paragraph step. Desktop keeps the default. */
val LocalMdParagraphGap = staticCompositionLocalOf { 3.dp }

/** Test handle for a quote's copy glyph, which has no text for a matcher to find. */
internal const val QUOTE_COPY_TAG = "quote-copy"

/**
 * A `>` blockquote — Chat Quote v1, direction 1a. The quote is part of the Agent's prose, not a card: no
 * fill, border or radius, just a 2dp muted rule and a 12dp indent, the text in tx2 at body size. The first
 * version was a filled panel with a bottom-right 复制 — the user turn's own grammar — so a quoted draft read
 * as a second, louder message. Agents quote the text meant to go elsewhere (a drafted reply), so the quote
 * keeps a one-tap copy of its own: a glyph floated on its first line, a different form and place from the
 * reply-level 复制 below. Nested levels get a hair rule, a 10dp indent and no glyph; the outer glyph copies
 * them along with the rest.
 */
@Composable
private fun QuoteBlock(quote: MdBlock.Quote, depth: Int) {
    val copyText = remember(quote.text) { mdPlainText(quote.blocks) }
    val hasGlyph = depth == 0 && copyText.isNotBlank()
    val (copied, copy) = rememberCopied()
    // the rule steps up to tx2 while "copied" holds — it marks what was copied — then eases back
    val ruleColor by animateColorAsState(
        when { depth > 0 -> Tok.hair; copied -> Tok.tx2; else -> Tok.muted },
        animationSpec = tween(150),
    )
    val indent = if (depth > 0) 10.dp else 12.dp
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val label = when {
        !hasGlyph -> null
        copied -> stringResource(Res.string.code_copied)
        hovered -> stringResource(Res.string.quote_copy)
        else -> null
    }
    val glyph = quoteGlyphMetrics()
    val labelStyle = tightCenter(12.5.sp * LocalFontScale.current)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelWidth = label?.let { with(density) { measurer.measure(it, labelStyle.copy(fontSize = 12.5.sp * LocalFontScale.current)).size.width.toDp() } }
    // Room the first line leaves for the float: 8dp from the text, the glyph slot, and the label while
    // it shows. Compose can't wrap text around a float, so the quote's whole first paragraph narrows,
    // not only its first visual line.
    val reserve = if (!hasGlyph) 0.dp else 8.dp + glyph.slot + (labelWidth?.let { it + 6.dp } ?: 0.dp)
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth()
                .drawBehind { drawRect(ruleColor, size = Size(2.dp.toPx(), size.height)) }
                .padding(start = 2.dp + indent),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            val first = quote.blocks.firstOrNull()
            when {
                reserve == 0.dp || first == null -> MdBlocks(quote.blocks, Tok.tx2, depth + 1)
                first is MdBlock.Lines -> {
                    Box(Modifier.padding(end = reserve)) { MdLine(first.lines.first(), Tok.tx2) }
                    first.lines.drop(1).forEach { MdLine(it, Tok.tx2) }
                    MdBlocks(quote.blocks.drop(1), Tok.tx2, depth + 1)
                }
                else -> {
                    Box(Modifier.padding(end = reserve)) { MdBlocks(listOf(first), Tok.tx2, depth + 1) }
                    MdBlocks(quote.blocks.drop(1), Tok.tx2, depth + 1)
                }
            }
        }
        if (hasGlyph) {
            val float = Modifier.align(Alignment.TopEnd)
            // DisableSelection: a drag-selected turn must not pick up the float's label
            DisableSelection {
                QuoteCopyFloat(float, glyph, label, labelStyle, copied, interaction) { copy(copyText) }
            }
        }
    }
}

/** The quote glyph's geometry; everything steps up once the text is 1.3× or larger. [line] is the body's
 *  line box, which the float centers on so the glyph sits on the quote's first line. */
private class QuoteGlyphMetrics(val slot: Dp, val icon: Dp, val disc: Dp, val line: Dp)

@Composable
private fun quoteGlyphMetrics(): QuoteGlyphMetrics {
    val density = LocalDensity.current
    val scale = LocalFontScale.current
    val large = scale * density.fontScale >= 1.3f
    // MdLine sets only fontSize, so the body's line box is the ambient style's lineHeight
    val line = with(density) {
        LocalTextStyle.current.lineHeight.takeIf { it.isSpecified }?.toDp() ?: (14.sp * scale * 1.5f).toDp()
    }
    return if (large) QuoteGlyphMetrics(28.dp, 20.dp, 34.dp, line) else QuoteGlyphMetrics(24.dp, 16.dp, 28.dp, line)
}

/**
 * The first-line float: an optional label ("已复制" after a copy, "复制引用" on desktop hover) 6dp before the
 * glyph slot, both centered on the first line box. The glyph rests in muted and goes tx2 when pressed,
 * hovered or copied; pressed and hover add a raised disc behind it. The target is 44dp around the glyph
 * and may reach 10dp into the page gutter. No focus ring (the design's 2dp accent one): a desktop mouse
 * click focuses the glyph too, so the ring would stay up after every copy — and no other control in the
 * app draws one.
 */
@Composable
private fun QuoteCopyFloat(
    modifier: Modifier,
    glyph: QuoteGlyphMetrics,
    label: String?,
    labelStyle: TextStyle,
    copied: Boolean,
    interaction: MutableInteractionSource,
    onCopy: () -> Unit,
) {
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val active = pressed || hovered
    val tint = if (copied || active) Tok.tx2 else Tok.muted
    val description = stringResource(if (copied) Res.string.quote_copied else Res.string.quote_copy)
    Row(modifier.height(glyph.line), verticalAlignment = Alignment.CenterVertically) {
        if (label != null) {
            Text(
                label, color = Tok.tx2, style = labelStyle, fontSize = 12.5.sp * LocalFontScale.current,
                maxLines = 1, softWrap = false,
                modifier = Modifier.padding(end = 6.dp).semantics { if (copied) liveRegion = LiveRegionMode.Polite },
            )
        }
        Box(
            Modifier.touchTarget(glyph.slot, 44.dp)
                .testTag(QUOTE_COPY_TAG)
                .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onCopy)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(glyph.disc).clip(CircleShape).background(if (active) Tok.raised else Color.Transparent))
            Icon(if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy, null, tint = tint, modifier = Modifier.size(glyph.icon))
        }
    }
}

/** Lays the content out at [hit]×[hit] but reports [slot]×[slot], centering the overflow: a small glyph
 *  keeps its visual slot in a row while its touch target meets the minimum. */
private fun Modifier.touchTarget(slot: Dp, hit: Dp) = layout { measurable, _ ->
    val s = slot.roundToPx()
    val h = hit.roundToPx()
    val placeable = measurable.measure(Constraints.fixed(h, h))
    layout(s, s) { placeable.place((s - h) / 2, (s - h) / 2) }
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

/** What the last copy did, for affordances that must never claim a write that did not happen. */
enum class CopyOutcome { IDLE, COPIED, FAILED }

/**
 * [rememberCopied] with an honest failure arm (Chat Rhythm v1's whole-turn copy): the confirmation shows only
 * when the clipboard write returned; a write that throws reports [CopyOutcome.FAILED] for the same beat. Nothing
 * is read back — reading the pasteboard on iOS raises the system paste banner.
 */
@Composable
fun rememberCopyOutcome(): Pair<CopyOutcome, (String) -> Unit> {
    val clipboard = LocalClipboardManager.current
    var outcome by remember { mutableStateOf(CopyOutcome.IDLE) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) { if (attempt > 0) { delay(1500); outcome = CopyOutcome.IDLE } }
    return outcome to { s: String ->
        outcome = if (runCatching { clipboard.setText(AnnotatedString(s)) }.isSuccess) CopyOutcome.COPIED else CopyOutcome.FAILED
        attempt++
    }
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
    val horizontal = rememberScrollState()
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
            modifier = Modifier.fillMaxWidth().horizontalScroll(horizontal).padding(10.dp),
        )
        // A wheel/trackpad could already pan this state, but a mouse-only Windows user had no visible
        // indication that clipped columns existed and no thumb to drag (#307). The platform actual is
        // emitted only when maxValue > 0, so short blocks and touch platforms keep the old compact shape.
        CodeHorizontalScrollbar(horizontal, Modifier.fillMaxWidth().height(9.dp).padding(horizontal = 4.dp))
    }
}

/**
 * ATX heading level of [line] (1–6), or null when the line is ordinary text. CommonMark's rule: 1–6 `#`
 * then a space/tab or the end of the line. Issue #355: the old check was `startsWith("#")`, so a
 * paragraph that happened to open with `#5 已修复…` (an item reference) or `#include` rendered as an
 * H1 — big, bold, with a heading's air around it — which is exactly the "one paragraph suddenly huge"
 * DSH report. Seven-plus `#` is also not a heading (CommonMark).
 */
internal fun mdHeadingLevel(line: String): Int? {
    if (!line.startsWith("#")) return null
    val level = line.takeWhile { it == '#' }.length
    if (level > 6) return null
    val next = line.getOrNull(level) ?: return level // "##" alone = empty heading
    return if (next == ' ' || next == '\t') level else null
}

/**
 * Body of a blockquote line — [line] minus its `>` marker and one following space — or null when the
 * line isn't quoted. Only a `>` that opens the line counts (`a > b` stays prose). Any indentation is
 * accepted, unlike CommonMark's three spaces: there a deeper `>` would be an indented code block, which
 * this renderer doesn't have, while agents do indent a quote under a list item.
 */
internal fun mdQuoteBody(line: String): String? {
    val marker = line.indexOfFirst { it != ' ' && it != '\t' }
    if (marker < 0 || line[marker] != '>') return null
    val next = line.getOrNull(marker + 1)
    return line.substring(if (next == ' ' || next == '\t') marker + 2 else marker + 1)
}

@Composable
private fun MdLine(raw: String, color: Color) {
    val line = raw.trimEnd()
    val trimmed = line.trimStart()
    val scale = LocalFontScale.current
    val body = 14.sp * scale // explicit so the chat text scale (issue #8) reaches plain body/list lines too
    when {
        line.isBlank() -> Spacer(Modifier.height(LocalMdParagraphGap.current))
        mdHeadingLevel(line) != null -> {
            val level = mdHeadingLevel(line)!!
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
            LinkifiedText(
                inline(cells.getOrElse(c) { "" }.trim()),
                color = color,
                fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
                fontSize = 13.sp * LocalFontScale.current,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

/** Inline emphasis/code and labelled links. Destinations remain separate from selectable labels. */
internal fun inline(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        val link = if (s[i] == '[' && (i == 0 || s[i - 1] != '!')) markdownLinkAt(s, i) else null
        when {
            link != null -> {
                val start = length
                append(inline(link.label))
                addStringAnnotation(MARKDOWN_LINK_TARGET, link.target, start, length)
                i = link.end
            }
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

private data class InlineLink(val label: String, val target: String, val end: Int)

/** Balanced destinations preserve parentheses, query strings and fragments; <...> permits spaces. */
private fun markdownLinkAt(s: String, start: Int): InlineLink? {
    var p = start + 1
    var brackets = 1
    while (p < s.length && brackets > 0) {
        when (s[p]) {
            '\\' -> { p += 2; continue }
            '[' -> brackets++
            ']' -> brackets--
        }
        if (brackets > 0) p++
    }
    if (p >= s.length || p == start + 1 || s.getOrNull(p + 1) != '(') return null
    val label = s.substring(start + 1, p)
    p += 2
    while (s.getOrNull(p)?.isWhitespace() == true) p++
    val angle = s.getOrNull(p) == '<'
    if (angle) p++
    val targetStart = p
    var parens = 0
    while (p < s.length) {
        val ch = s[p]
        if (ch == '\\' && s.getOrNull(p + 1) in listOf('(', ')', '<', '>')) { p += 2; continue }
        if (angle && ch == '>') break
        if (!angle) {
            if (ch == '(') parens++
            if (ch == ')') { if (parens == 0) break else parens-- }
            if (ch.isWhitespace() && parens == 0) break
        }
        p++
    }
    if (p >= s.length || p == targetStart) return null
    val target = s.substring(targetStart, p)
        .replace("\\(", "(").replace("\\)", ")").replace("\\<", "<").replace("\\>", ">")
    if (angle) p++
    while (s.getOrNull(p)?.isWhitespace() == true) p++
    // Optional Markdown title is presentation metadata, never part of the copied destination.
    if (s.getOrNull(p) == '"' || s.getOrNull(p) == '\'') {
        val quote = s[p++]
        while (p < s.length && s[p] != quote) { if (s[p] == '\\') p++; p++ }
        if (p >= s.length) return null
        p++
        while (s.getOrNull(p)?.isWhitespace() == true) p++
    }
    if (s.getOrNull(p) != ')') return null
    // Only the destinations the app can handle: web URLs and filesystem paths, not custom schemes.
    val web = target.startsWith("http://") || target.startsWith("https://")
    val drive = target.length >= 3 && target[0].isLetter() && target[1] == ':' && target[2] in "/\\"
    if (!web && !drive && (':' in target.substringBefore('/') || target.startsWith('#'))) return null
    return InlineLink(label, target, p + 1)
}

private sealed interface MdBlock {
    data class Code(val code: String, val lang: String?, val closed: Boolean = true) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    /** [text] is the quote's body with its markers stripped; [blocks] is that body parsed. */
    data class Quote(val text: String, val blocks: List<MdBlock>) : MdBlock
    data class Lines(val lines: List<String>) : MdBlock
}

/**
 * What a quote's copy chip takes: the body as the panel reads it — no `>`, heading `#` or inline
 * `**`/`` ` `` markers, code verbatim without its fences, table cells tab-separated. A drafted message
 * then pastes clean into a chat app instead of carrying Markdown syntax along. List markers stay:
 * `- ` still reads as a list in plain text.
 */
private fun mdPlainText(blocks: List<MdBlock>): String = blocks.joinToString("\n") { block ->
    when (block) {
        is MdBlock.Code -> block.code
        is MdBlock.Quote -> mdPlainText(block.blocks)
        is MdBlock.Table -> (listOf(block.header) + block.rows).joinToString("\n") { row -> row.joinToString("\t") { inline(it).text } }
        is MdBlock.Lines -> block.lines.joinToString("\n") { line ->
            val level = mdHeadingLevel(line)
            inline(if (level != null) line.drop(level).trim() else line.trimEnd()).text
        }
    }
}

/** Quotes nest at most this deep; a deeper `>` stays literal text. Bounds the parse and composition
 *  recursion — a line of thousands of `>` can't overflow the stack — and keeps nested rules readable. */
internal const val MAX_QUOTE_DEPTH = 6

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

private fun parseBlocks(text: String, quoteDepth: Int = 0): List<MdBlock> {
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
        } else if (quoteDepth < MAX_QUOTE_DEPTH && mdQuoteBody(lines[i]) != null) {
            // Blockquote: the run of `>` lines, parsed again as a document of its own so a quote holds
            // lists, code and further quotes. It ends at the first unmarked line (no CommonMark lazy
            // continuation — text right after a quote stays outside it). Checked before tables, so
            // `> a | b` over a `---` line can't be taken for a table header.
            flush()
            val body = ArrayList<String>()
            while (i < lines.size) { val b = mdQuoteBody(lines[i]) ?: break; body += b; i++ }
            // blank `>` lines at either edge render as nothing in CommonMark; dropping them keeps the
            // panel's padding even while a streamed quote briefly ends on its `>` separator line
            val quoted = body.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }.joinToString("\n")
            blocks += MdBlock.Quote(quoted, parseBlocks(quoted, quoteDepth + 1))
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
