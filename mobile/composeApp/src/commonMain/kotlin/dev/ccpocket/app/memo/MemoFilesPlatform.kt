package dev.ccpocket.app.memo

/** The platform's private, backup-excluded `voice-memos/` directory (see each actual for where that is). */
expect fun platformMemoFiles(): MemoFiles

/**
 * Path rules shared by every [MemoFiles] actual. A [MemoFiles] `dir` is one or more internal tokens joined by `/`
 * (the store's layout is `<scope-token>/<memoId>`); a `name` is exactly one token. A token is `[A-Za-z0-9._-]+`
 * and never `.` or `..`, so nothing a caller passes can climb out of the memo root or name an absolute path —
 * user text and model output never reach a file name.
 */
internal object MemoPaths {
    private val token = Regex("^[A-Za-z0-9._-]+$")

    fun isToken(value: String): Boolean = value != "." && value != ".." && token.matches(value)

    /** [allowRoot]: "" names the memo root itself (only listings and nothing else may use it). */
    fun isDir(value: String, allowRoot: Boolean = false): Boolean =
        if (value.isEmpty()) allowRoot else value.split('/').all(::isToken)

    fun isName(value: String): Boolean = isToken(value)

    /** In-progress temp files are hidden (`.name.<random>.tmp`) and never listed as content. */
    fun isVisible(entry: String): Boolean = !entry.startsWith(".")
}
