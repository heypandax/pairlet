package dev.ccpocket.app.data

import dev.ccpocket.app.epochMillis
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.CommandSource
import dev.ccpocket.protocol.DirectoryEntry
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.SlashCommand

/**
 * Self-contained sample data for the no-pairing Demo mode (App Store review + first-run preview).
 *
 * Pure content only: [PocketRepository.demoRespond] wraps these into protocol frames (adding the
 * live convoId / askId / seq) and feeds them through the normal `handle()` path, so the demo drives
 * the real UI state machine without any relay or daemon connection.
 */
object DemoData {
    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private const val DAY = 24 * HOUR
    private fun ago(ms: Long) = epochMillis() - ms

    const val LIVE_SESSION_ID = "demo-live-0001"
    const val LIVE_DIR = "/Users/alex/code/cc-pocket"

    /** The project list shown on the directory browser — one live row + several with history. */
    fun dirs(): List<DirectoryEntry> = listOf(
        DirectoryEntry(
            path = LIVE_DIR, name = "cc-pocket", isDir = true,
            hasSessions = true, recent = true, lastModified = ago(2 * MIN),
            open = true, executing = true,
            activeSessionId = LIVE_SESSION_ID, activeSessionTitle = "Add demo mode for App Review",
            gitBranch = "main",
        ),
        DirectoryEntry(
            path = "/Users/alex/code/cc-pocket-site", name = "cc-pocket-site", isDir = true,
            hasSessions = true, recent = true, lastModified = ago(3 * HOUR), gitBranch = "main",
        ),
        DirectoryEntry(
            path = "/Users/alex/code/relay-server", name = "relay-server", isDir = true,
            hasSessions = true, recent = true, lastModified = ago(28 * HOUR), gitBranch = "deploy",
        ),
        DirectoryEntry(
            path = "/Users/alex/code/notes-cli", name = "notes-cli", isDir = true,
            hasSessions = true, lastModified = ago(3 * DAY), gitBranch = "main",
        ),
        DirectoryEntry(
            path = "/Users/alex/dotfiles", name = "dotfiles", isDir = true,
            hasSessions = true, lastModified = ago(9 * DAY), gitBranch = "main",
        ),
    )

    /** Session history for a tapped project. The live project leads with the running session. */
    fun sessions(workdir: String): List<SessionSummary> = listOf(
        SessionSummary(
            sessionId = LIVE_SESSION_ID, title = "Add demo mode for App Review",
            firstPrompt = "Add a no-pairing demo mode so reviewers can see the app",
            messageCount = 12, cwd = workdir, lastModified = ago(2 * MIN),
            gitBranch = "main", live = workdir == LIVE_DIR,
        ),
        SessionSummary(
            sessionId = "demo-s2", title = "Fix reconnect banner flicker",
            firstPrompt = "The reconnecting banner flickers when opening a session",
            messageCount = 8, cwd = workdir, lastModified = ago(5 * HOUR), gitBranch = "main",
        ),
        SessionSummary(
            sessionId = "demo-s3", title = "Add push-to-talk voice input",
            firstPrompt = "Can we add dictation to the composer?",
            messageCount = 21, cwd = workdir, lastModified = ago(2 * DAY), gitBranch = "feature/voice",
        ),
        SessionSummary(
            sessionId = "demo-s4", title = "Localize all strings to Chinese",
            firstPrompt = "Add zh localization across every screen",
            messageCount = 15, cwd = workdir, lastModified = ago(6 * DAY), gitBranch = "main",
        ),
    )

    /** A preloaded transcript so an opened session looks real immediately (user / assistant / tool turns). */
    fun history(): List<HistoryMessage> = listOf(
        HistoryMessage(ChatRole.USER, "Add a dark mode toggle to the settings screen"),
        HistoryMessage(ChatRole.ASSISTANT, "I'll add a dark mode toggle. First let me look at the current settings screen and how the theme is set up."),
        HistoryMessage(ChatRole.TOOL, "SettingsScreen.kt", tool = "Read"),
        HistoryMessage(ChatRole.TOOL, "theme/Theme.kt", tool = "Read"),
        HistoryMessage(
            ChatRole.ASSISTANT,
            "The theme is a single light palette in `Theme.kt`. Here's the plan:\n\n" +
                "1. Add a `darkColors` palette next to the existing one\n" +
                "2. Persist a `darkMode` flag and switch palettes from it\n" +
                "3. Add a `Switch` row in **Settings → Appearance**\n\n" +
                "Starting on the theme change now.",
        ),
        HistoryMessage(ChatRole.TOOL, "theme/Theme.kt", tool = "Edit"),
        HistoryMessage(ChatRole.ASSISTANT, "Done — dark palette added and wired to a persisted flag, with a toggle in Settings. Want me to run the build to confirm it compiles?"),
    )

    /** Composer "/" autocomplete entries. */
    fun commands(): List<SlashCommand> = listOf(
        SlashCommand("help", "Show available commands"),
        SlashCommand("review", "Review the current diff", source = CommandSource.PROJECT),
        SlashCommand("test", "Run the test suite"),
        SlashCommand("commit", "Create a git commit", argumentHint = "[message]", source = CommandSource.USER),
    )

    // ── streamed-reply content for a freshly sent prompt ──────────────────────
    const val THINKING = "Let me look at the project layout and the most recent changes before answering."

    /** The first user turn demonstrates a tool call + permission prompt; these are its bits. */
    const val ASK_TOOL = "Bash"
    const val ASK_PREVIEW = "git status"
    const val ASK_TITLE = "Run command"
    const val ASK_RULE = "git status"

    /** Reply body, split into chunks so it visibly streams in. */
    val REPLY_CHUNKS = listOf(
        "Here's a quick read on where things stand:\n\n",
        "- The working tree is clean on `main`\n- The demo flow is wired through the normal message path\n\n",
        "Want me to start on the next change, or open a specific file first?",
    )

    /** Reply used when no permission step is involved (later turns). */
    val PLAIN_REPLY_CHUNKS = listOf(
        "Got it. ",
        "Here's what I'd do:\n\n1. Make the change in the relevant module\n2. Add a small test\n3. Run the build to confirm\n\n",
        "Should I go ahead?",
    )
}
