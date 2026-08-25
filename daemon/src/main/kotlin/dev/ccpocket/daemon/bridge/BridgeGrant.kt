package dev.ccpocket.daemon.bridge

/**
 * How much authority ONE externally submitted bridge request carries into its turn. A per-turn grant, armed
 * at hand-off and revoked at TurnResult / process end — never a standing rule (issue #91's whole point:
 * an approval must not become a blank cheque for later, attacker-supplied prompts).
 *
 * Full authority comes only from a machine-owner decision: the owner's own bridge turn, a request the owner
 * read and approved, or the owner's durable TRUSTED grant for an exact chat/project. REVIEWED remains the
 * independent Guardian path and keeps a restricted ceiling because no human approved that individual turn.
 *
 *  - [NONE] — the default. Every tool ask routes to the owner's phone (Bash first passing
 *    BridgeCommandPolicy's deny/allow walls). This is what an unapproved request gets.
 *  - [OWNER_BYPASS] (issue #91 / #233 rule ①) — one turn in the configured owner's dedicated bridge
 *    session. It is armed for the prompt rather than inferred from the standing session flag, so cancelling
 *    that turn revokes it before any buffered tool request can be answered.
 *  - [OWNER_APPROVED] (issue #190 / #233 rule ②) — the owner READ this exact request and accepted a
 *    full turn. Ordinary tool asks, including classifier-ASK Bash, are skipped for this turn. This is
 *    deliberately broad authority; the shell deny list remains defense-in-depth, not a complete sandbox.
 *  - [AUTO_TRUSTED] (issue #233 rule ③) — the owner durably trusted this exact chat/project. Every request
 *    receives a fresh one-turn broad grant, with no Guardian or per-tool cards; it never becomes session-wide.
 *  - [REVIEWER_APPROVED] — a Guardian-passed REVIEWED request. It retains the closed [autoRunnable] ceiling:
 *    unknown/MCP/network/Task and classifier-ASK Bash still ask the owner.
 */
enum class BridgeGrant {
    NONE,
    OWNER_BYPASS,
    OWNER_APPROVED,
    REVIEWER_APPROVED,
    AUTO_TRUSTED,
    ;

    /** Only Guardian-reviewed turns use the closed-ceiling judgement. */
    val machineConfined: Boolean get() = this == REVIEWER_APPROVED

    /** True when the grant authorizes ordinary execution tools without a per-tool ask. */
    val authorizes: Boolean get() = this != NONE

    companion object {
        /**
         * The tools a [REVIEWER_APPROVED] grant may run without an owner card. This stays CLOSED because the
         * Guardian is a classifier, not the machine owner.
         */
        fun autoRunnable(toolName: String): Boolean = toolName in AUTO_RUNNABLE_TOOLS

        /** Tools that act on ONE named file, so "no resolved target" means the workdir wall proved nothing. */
        val SPECIFIC_FILE_TOOLS = setOf("Read", "Write", "Edit", "MultiEdit", "NotebookEdit")

        private val AUTO_RUNNABLE_TOOLS = SPECIFIC_FILE_TOOLS + setOf("Glob", "Grep", "TodoWrite")

        /**
         * True for an in-project file that EXECUTES on the owner's next interaction, so writing it unattended is
         * a persistence primitive rather than a code change: git's own config and hooks (`core.pager`,
         * `diff.external`, `pre-commit`), the agent config directories whose contents shape the owner's next
         * Claude/Codex/OpenCode session (hooks, MCP servers, instruction loading), and the standing instruction
         * files those sessions ingest as prompt. The bridge's own agent runs clean-room, but the OWNER's
         * sessions in that project do not — nor does their terminal.
         *
         * Matched on path SEGMENTS of the resolved target, so `src/dotgit-notes.md` is unaffected while
         * `.git/hooks/pre-commit`, `a/.claude/settings.json` and `.envrc` all match. Segment comparison is
         * case-INsensitive because the macOS filesystem is: `agents.MD` and `AGENTS.md` are the same on-disk
         * file, so a case-sensitive wall would be a one-character bypass. It stays a WHOLE-segment match on
         * purpose — `docs/agents-notes.md` and `src/claude.md.backup` are ordinary files no later session
         * loads, and widening to substrings would turn the wall into a nuisance filter. These do not become
         * forbidden — they become "ask the owner", which is the one tap this whole feature is trading away.
         */
        fun executesForTheOwner(target: String): Boolean {
            val parts = target.replace('\\', '/').split('/').filter { it.isNotEmpty() }.map { it.lowercase() }
            if (parts.any { it in ON_ACCESS_EXEC_DIRS }) return true
            return parts.lastOrNull() in ON_ACCESS_EXEC_FILES
        }

        // Directories whose CONTENTS become the owner's execution: .git carries hooks and exec-shaped config,
        // and .claude / .codex / .opencode carry the settings, hooks, MCP servers and instruction files the
        // owner's next Claude/Codex/OpenCode session in that project loads without anyone reading them first
        // (§21.5 P1-4). Lowercase entries; executesForTheOwner lowercases before comparing.
        private val ON_ACCESS_EXEC_DIRS = setOf(".git", ".claude", ".codex", ".opencode")

        // direnv runs .envrc on the owner's next `cd`; .mcp.json / .claude.json configure MCP servers the
        // owner's next session in that project offers to load — all the same class as a git hook: bytes that
        // turn into the OWNER's execution later, so an unattended write gets a card (reviewed-trust Low-1).
        // AGENTS.md / CLAUDE.md / CLAUDE.local.md are that same class in prose: agents auto-load them as
        // standing instructions, so an unattended write is a persistent prompt-injection seat, not a doc edit
        // (§21.5 P1-4). Lowercase entries; executesForTheOwner lowercases before comparing.
        private val ON_ACCESS_EXEC_FILES = setOf(
            ".envrc", ".mcp.json", ".claude.json",
            "agents.md", "claude.md", "claude.local.md",
        )
    }
}
