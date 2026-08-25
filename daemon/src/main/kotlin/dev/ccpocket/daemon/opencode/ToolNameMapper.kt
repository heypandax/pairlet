package dev.ccpocket.daemon.opencode

/**
 * Maps OpenCode's lowercase tool names to Claude-shaped tool names so the shared [ToolMetadata] and
 * [PermissionBridge] layers work unchanged. OpenCode uses: bash, read, write, edit, glob, grep,
 * webfetch, websearch, task. Codex uses similar lowercase names — the mapping is identical.
 * Unknown tools are normalized to first-letter-uppercased form so they render legibly in the UI.
 */
object ToolNameMapper {
    fun map(tool: String): String = when (val name = tool.lowercase()) {
        // kimi ACP (issue #206) passes a tool `kind` ∈ execute/read/edit/delete/move/search/fetch/…;
        // `execute` and legacy `shell` are the command tool → Claude's Bash danger regex.
        "bash", "shell", "execute" -> "Bash"
        "read" -> "Read"
        "write" -> "Write"
        "edit", "delete", "move" -> "Edit"
        "glob" -> "Glob"
        "grep", "search" -> "Grep"
        "webfetch", "fetch" -> "WebFetch"
        "websearch" -> "WebSearch"
        "task" -> "Task"
        else -> name.replaceFirstChar { it.uppercase() }
    }
}
