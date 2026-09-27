package dev.ccpocket.app.data

/**
 * The one line a process fold shows for a tool call — its live line and a settled single-step header (Tool
 * Process Live v1). The daemon's START preview for an ordinary tool is the call's raw input JSON
 * (`{"command":"pnpm test"}`, cut at 280 chars); only file writes, plans, sub-agents and workflows arrive as
 * clean text. This reads the argument out of that JSON — tolerating the cut — so the fold names what a call did
 * whatever daemon it is talking to. The transcript's own tool rows keep showing the literal payload.
 */
object ToolTarget {
    /** Argument keys, most telling first: what a search looks for (`pattern`) says more on one line than where
     *  it looks (`path`) — the daemon's approval sheet reads them the other way round, by design. */
    private val KEYS = listOf(
        "command", "file_path", "notebook_path", "pattern", "path", "filename", "url", "query", "prompt",
        "description", "content",
    )

    private val HOME = Regex("^/(?:Users|home)/[^/]+/")

    /** The call's target on one line; [cwd] (the session's working directory) shortens paths under it. */
    fun of(preview: String, cwd: String? = null): String {
        val raw = preview.trimStart()
        val argument = if (raw.startsWith("{")) KEYS.firstNotNullOfOrNull { key -> jsonString(raw, key)?.takeIf { it.isNotBlank() } } else null
        return shortenPath(firstLine(argument ?: preview), cwd)
    }

    /** What a parallel call shows beside its siblings: a path's last segment, anything else its target. */
    fun shortName(preview: String, cwd: String? = null): String {
        val target = of(preview, cwd)
        return if (isPathLike(target)) target.trimEnd('/').substringAfterLast('/') else target
    }

    /** One token with a slash — a path or URL, whose END is the informative part (middle-ellipsize it). */
    fun isPathLike(s: String): Boolean = '/' in s && s.none(Char::isWhitespace)

    private fun firstLine(text: String): String = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    /** `/Users/x/code/app/src/a.ts` → `src/a.ts` under [cwd], else `~/code/app/src/a.ts` under a home dir. */
    private fun shortenPath(value: String, cwd: String?): String {
        if (!value.startsWith("/")) return value
        val base = cwd?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        if (base != null && value.startsWith("$base/")) return value.removePrefix("$base/")
        val home = HOME.find(value) ?: return value
        return "~/" + value.substring(home.value.length)
    }

    /**
     * The string value of top-level [key] in compact JSON [json], read by scanning rather than parsing so a
     * preview cut mid-value still yields the head of that value. Null when the key is absent or not a string.
     */
    private fun jsonString(json: String, key: String): String? {
        val marker = "\"$key\":"
        var at = json.indexOf(marker)
        while (at >= 0) {
            var i = at + marker.length
            while (i < json.length && json[i].isWhitespace()) i++
            if (i < json.length && json[i] == '"') {
                val out = StringBuilder()
                i++
                while (i < json.length) {
                    val c = json[i]
                    if (c == '"') return out.toString()
                    if (c == '\\' && i + 1 < json.length) {
                        when (val e = json[i + 1]) {
                            'n' -> out.append('\n')
                            'r' -> Unit
                            't' -> out.append(' ')
                            'u' -> {
                                json.substring(i + 2, minOf(i + 6, json.length)).toIntOrNull(16)?.let { out.append(it.toChar()) }
                                i += 4
                            }
                            else -> out.append(e)
                        }
                        i += 2
                        continue
                    }
                    out.append(c)
                    i++
                }
                return out.toString() // the preview was cut inside this value: its head is still the target
            }
            at = json.indexOf(marker, at + marker.length)
        }
        return null
    }
}
