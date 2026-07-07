package dev.ccpocket.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import dev.ccpocket.app.theme.Tok
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxTheme

/**
 * Syntax highlighting for fenced code blocks in the transcript (issue #51).
 *
 * Real languages go through `dev.snipme:highlights` (pure-KMP analyzer, runs on all three targets).
 * The library has no SQL/JSON/YAML grammars — and "生成文件的 sql" is the issue's headline case — so
 * those get a tiny in-house pass ([fallbackHighlight]) covering the four token classes that matter
 * at reading distance: keywords, strings, comments, numbers.
 *
 * Both paths are pure functions of (code, lang): callers MUST wrap in `remember(code, lang)` —
 * streamed assistant turns recompose the code block on every appended chunk.
 */

/** Above this we skip highlighting entirely: the analyzer is O(n·keywords) on the UI thread and a
 *  pathological paste (minified bundle, giant SQL dump) must not hitch the transcript. */
private const val HIGHLIGHT_MAX_CHARS = 20_000

/**
 * [highlightCode] that returns null when it has nothing to add (no/unknown language, oversize code,
 * analyzer failure) — lets callers keep their richer plain-text pipeline (e.g. [pathLinked]) for
 * exactly the blocks that stay un-highlighted.
 */
fun highlightCodeOrNull(code: String, lang: String?): AnnotatedString? {
    if (lang.isNullOrBlank() || code.isBlank() || code.length > HIGHLIGHT_MAX_CHARS) return null
    val key = lang.trim().lowercase()
    fallbackSpec(key)?.let { spec -> return runCatching { fallbackHighlight(code, spec) }.getOrNull() }
    val language = libraryLanguage(key) ?: return null
    // runCatching: the analyzer sees half-streamed code (unterminated strings/comments) constantly;
    // any parse hiccup must degrade to plain text, never take the transcript down.
    return runCatching {
        val spans = Highlights.Builder()
            .code(code)
            .language(language)
            .theme(pocketSyntaxTheme())
            .build()
            .getHighlights()
        buildAnnotatedString {
            append(code)
            for (h in spans) {
                val start = h.location.start.coerceIn(0, code.length)
                val end = h.location.end.coerceIn(start, code.length)
                if (start >= end) continue
                when (h) {
                    is ColorHighlight -> addStyle(SpanStyle(color = Color(0xFF000000.toInt() or h.rgb)), start, end)
                    is BoldHighlight -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
                }
            }
        }
    }.getOrNull()
}

/** Highlight [code] as [lang]; unknown/absent languages come back as the plain single-color string. */
fun highlightCode(code: String, lang: String?): AnnotatedString =
    highlightCodeOrNull(code, lang) ?: AnnotatedString(code)

// ── colors ─────────────────────────────────────────────────────────────────────────────

private fun rgb(c: Color) = c.toArgb() and 0xFFFFFF

/** The library theme derived from [Tok] so tokens sit inside the app palette:
 *  keywords = accent (like inline code), strings = ok/green, literals = warn/amber, comments = muted,
 *  annotations = info/blue; punctuation & operators stay body-colored so blocks don't turn confetti.
 *  A function (not a cached val) so light/dark switches recolor: callers already remember(code, lang),
 *  so this is only rebuilt when a block's code/lang changes — cheap. (#63) */
private fun pocketSyntaxTheme() = SyntaxTheme(
    key = if (Tok.current.dark) "pocket-dark" else "pocket-light",
    code = rgb(Tok.tx2),
    keyword = rgb(Tok.accent),
    string = rgb(Tok.ok),
    literal = rgb(Tok.warn),
    comment = rgb(Tok.muted),
    metadata = rgb(Tok.info),
    multilineComment = rgb(Tok.muted),
    punctuation = rgb(Tok.tx2),
    mark = rgb(Tok.tx2),
)

// ── language routing ───────────────────────────────────────────────────────────────────

/** Fence-info / extension aliases → library grammar. Keep in sync with [fallbackSpec] (checked first). */
private fun libraryLanguage(key: String): SyntaxLanguage? = when (key) {
    "c", "h" -> SyntaxLanguage.C
    "cpp", "c++", "cc", "cxx", "hpp", "objc", "objective-c", "m" -> SyntaxLanguage.CPP
    "dart" -> SyntaxLanguage.DART
    "java" -> SyntaxLanguage.JAVA
    "kotlin", "kt", "kts" -> SyntaxLanguage.KOTLIN
    "rust", "rs" -> SyntaxLanguage.RUST
    "csharp", "cs", "c#" -> SyntaxLanguage.CSHARP
    "coffeescript", "coffee" -> SyntaxLanguage.COFFEESCRIPT
    "javascript", "js", "jsx", "mjs", "cjs" -> SyntaxLanguage.JAVASCRIPT
    "perl", "pl" -> SyntaxLanguage.PERL
    "python", "py", "python3" -> SyntaxLanguage.PYTHON
    "ruby", "rb" -> SyntaxLanguage.RUBY
    "shell", "sh", "bash", "zsh", "shellscript", "console" -> SyntaxLanguage.SHELL
    "swift" -> SyntaxLanguage.SWIFT
    "typescript", "ts", "tsx" -> SyntaxLanguage.TYPESCRIPT
    "go", "golang" -> SyntaxLanguage.GO
    "php" -> SyntaxLanguage.PHP
    else -> null
}

// ── fallback tokenizer (languages the library lacks) ───────────────────────────────────

private class FallbackSpec(
    keywords: Set<String>,
    val caseInsensitive: Boolean,
    val lineComments: List<String>,
    val blockComment: Pair<String, String>?,
) {
    val keywords: Set<String> = if (caseInsensitive) keywords.map { it.lowercase() }.toSet() else keywords
    fun isKeyword(word: String) = (if (caseInsensitive) word.lowercase() else word) in keywords
}

private val SQL_SPEC by lazy {
    FallbackSpec(
        keywords = setOf(
            "select", "from", "where", "insert", "into", "values", "update", "set", "delete", "create",
            "table", "index", "view", "trigger", "drop", "alter", "add", "column", "primary", "key",
            "foreign", "references", "join", "left", "right", "inner", "outer", "full", "cross", "on",
            "as", "order", "by", "group", "having", "limit", "offset", "union", "all", "distinct",
            "case", "when", "then", "else", "end", "if", "exists", "not", "null", "and", "or", "in",
            "is", "like", "between", "asc", "desc", "default", "unique", "constraint", "check",
            "cascade", "truncate", "begin", "commit", "rollback", "transaction", "with", "returning",
            "grant", "revoke", "using", "true", "false",
            // common column types read as keywords too — they are half of any CREATE TABLE
            "int", "integer", "smallint", "bigint", "serial", "text", "varchar", "char", "boolean",
            "date", "time", "timestamp", "datetime", "numeric", "decimal", "float", "real", "double",
            "blob", "json", "jsonb", "uuid",
        ),
        caseInsensitive = true,
        lineComments = listOf("--"),
        blockComment = "/*" to "*/",
    )
}

private val JSON_SPEC by lazy {
    // line/block comments cover JSONC; pure JSON simply never hits them
    FallbackSpec(setOf("true", "false", "null"), caseInsensitive = false, lineComments = listOf("//"), blockComment = "/*" to "*/")
}

private val YAML_SPEC by lazy {
    FallbackSpec(setOf("true", "false", "null", "yes", "no"), caseInsensitive = true, lineComments = listOf("#"), blockComment = null)
}

private val TOML_SPEC by lazy {
    FallbackSpec(setOf("true", "false"), caseInsensitive = false, lineComments = listOf("#"), blockComment = null)
}

private fun fallbackSpec(key: String): FallbackSpec? = when (key) {
    "sql", "mysql", "postgres", "postgresql", "sqlite", "plsql", "tsql" -> SQL_SPEC
    "json", "jsonc", "json5" -> JSON_SPEC
    "yaml", "yml" -> YAML_SPEC
    "toml", "ini", "properties", "conf" -> TOML_SPEC
    else -> null
}

/** One linear pass, four token classes: comments (muted) > strings (ok) > numbers (warn) > keywords (accent). */
private fun fallbackHighlight(code: String, spec: FallbackSpec): AnnotatedString = buildAnnotatedString {
    append(code)
    val n = code.length
    var i = 0
    while (i < n) {
        val c = code[i]
        val lineComment = spec.lineComments.firstOrNull { code.startsWith(it, i) }
        when {
            lineComment != null -> {
                val end = code.indexOf('\n', i).let { if (it < 0) n else it }
                addStyle(SpanStyle(color = Tok.muted), i, end)
                i = end
            }
            spec.blockComment != null && code.startsWith(spec.blockComment.first, i) -> {
                val close = code.indexOf(spec.blockComment.second, i + spec.blockComment.first.length)
                val end = if (close < 0) n else close + spec.blockComment.second.length
                addStyle(SpanStyle(color = Tok.muted), i, end)
                i = end
            }
            c == '"' || c == '\'' -> {
                var j = i + 1
                while (j < n && code[j] != c) {
                    if (code[j] == '\\') j++ // escape consumes the next char; SQL's '' doubling reads as two adjacent strings, visually identical
                    j++
                }
                val end = (j + 1).coerceAtMost(n)
                addStyle(SpanStyle(color = Tok.ok), i, end)
                i = end
            }
            c.isDigit() -> {
                var j = i + 1
                while (j < n && (code[j].isLetterOrDigit() || code[j] == '.' || code[j] == '_')) j++ // digits + hex/exponent letters + separators
                addStyle(SpanStyle(color = Tok.warn), i, j)
                i = j
            }
            c.isLetter() || c == '_' -> {
                var j = i + 1
                while (j < n && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                if (spec.isKeyword(code.substring(i, j))) addStyle(SpanStyle(color = Tok.accent), i, j)
                i = j
            }
            else -> i++
        }
    }
}
