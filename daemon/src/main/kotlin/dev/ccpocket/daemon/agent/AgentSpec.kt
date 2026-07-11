package dev.ccpocket.daemon.agent

import dev.ccpocket.protocol.PermissionMode
import java.nio.file.Path

/**
 * What to launch: working directory + optional resume/model/effort/mode. The union of every backend's
 * launch knobs; a backend ignores fields that don't apply to it (Codex ignores [forkSession] /
 * [appendSystemPrompt]; it sets model/mode/effort per turn instead of at launch).
 */
data class AgentSpec(
    val workdir: Path,
    val resumeId: String? = null,
    val model: String? = null,
    val mode: PermissionMode = PermissionMode.DEFAULT,
    val appendSystemPrompt: String? = null,
    val effort: String? = null, // reasoning effort: low|medium|high|xhigh|max
    // Claude only: fork the resumed session into a fresh id (--fork-session) instead of appending to the
    // original transcript. Set when the phone takes over / cold-resumes a session another writer may hold.
    val forkSession: Boolean = false,
    // GUEST folder-share clean-room (issue #115): launch the agent WITHOUT the owner's private, machine-wide
    // context, so a scoped collaborator can't siphon it through the agent (the issue's "context & capability
    // overflow" threat). Claude honours it (Codex ignores it; v1 guests are Claude-only in practice) via:
    //   • --strict-mcp-config + empty --mcp-config  → NO MCP servers (the "biggest hole": no acting through
    //     the owner's already-authenticated Feishu / email / calendar / internal integrations),
    //   • --setting-sources project,local            → drop the `user` source (~/.claude global CLAUDE.md,
    //     user skills / commands / settings) — only the shared folder's own project config loads,
    //   • --exclude-dynamic-system-prompt-sections   → drop auto-memory paths, env info, git status from the
    //     system prompt (vectors #2/#5 — the owner's private memory + shell env don't bleed into replies).
    // Credentials/auth are NOT a setting source, so the guest still bills the owner's account (billing intact).
    val cleanRoom: Boolean = false,
)
