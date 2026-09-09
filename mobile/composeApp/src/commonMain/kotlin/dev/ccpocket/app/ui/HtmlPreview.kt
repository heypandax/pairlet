package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.FileContent
import org.jetbrains.compose.resources.stringResource

internal fun isHtmlExtension(ext: String): Boolean = ext.lowercase() in setOf("html", "htm")

internal fun isHtmlPath(path: String): Boolean = isHtmlExtension(fileNameOf(path).substringAfterLast('.', ""))

/** A document preview, never a bridge into the client or the daemon's filesystem. srcdoc preserves
 * the original document (including its doctype, head and scripts), while an opaque sandbox origin
 * prevents it from reaching the host page. No local file/content URLs or native JS objects are exposed.
 * Only this file's bytes are available; relative workspace assets are not fetched implicitly. */
internal fun htmlPreviewDocument(html: String): String {
    val escaped = html.replace("&", "&amp;").replace("\"", "&quot;")
        .replace("<", "&lt;").replace(">", "&gt;")
    return """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' https: http: data: blob:; style-src 'unsafe-inline' https: http:; img-src https: http: data: blob:; font-src https: http: data:; media-src https: http: data: blob:; connect-src https: http:; frame-src about: https: http: data: blob:; base-uri 'none'; form-action 'none'; object-src 'none'">
<style>html,body{margin:0;width:100%;height:100%;overflow:hidden;background:white}iframe{display:block;border:0;width:100%;height:100%}</style>
</head><body><iframe title="HTML preview" sandbox="allow-scripts" srcdoc="$escaped"></iframe></body></html>"""
}

@Composable
internal expect fun HtmlPreview(document: String, modifier: Modifier, onError: () -> Unit)

/** Shared by mobile and desktop. Source remains available even when the native engine cannot start. */
@Composable
internal fun HtmlFileBody(content: FileContent, dense: Boolean) {
    val html = content.text.orEmpty()
    var source by remember(content.path) { mutableStateOf(false) }
    var failed by remember(content.path, html) { mutableStateOf(false) }
    val document = remember(html) { htmlPreviewDocument(html) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { source = false; failed = false }, enabled = source || failed) {
                Text(stringResource(Res.string.html_preview), color = if (!source) Tok.accent else Tok.tx2)
            }
            TextButton(onClick = { source = true }, enabled = !source) {
                Text(stringResource(Res.string.html_source), color = if (source) Tok.accent else Tok.tx2)
            }
        }
        if (content.truncated) Text(
            stringResource(Res.string.file_truncated, html.length / 1024, (content.totalBytes / 1024).toInt()),
            color = Tok.muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
        if (failed && !source) Text(
            stringResource(Res.string.html_preview_failed), color = Tok.muted, fontSize = 13.sp,
            modifier = Modifier.padding(14.dp),
        )
        if (source || failed) {
            SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp)) {
                Text(
                    remember(html) { highlightCode(html, "html") }, color = Tok.tx2, fontFamily = FontFamily.Monospace,
                    fontSize = if (dense) 12.sp else 12.5.sp, lineHeight = if (dense) 19.sp else 21.sp,
                )
            }
        } else {
            HtmlPreview(document, Modifier.weight(1f).fillMaxWidth(), onError = { failed = true })
        }
    }
}
