package dev.ccpocket.daemon.execution

import dev.ccpocket.daemon.bridge.BridgeGrant
import dev.ccpocket.daemon.bridge.PathScope
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.protocol.PermissionMode
import java.io.File

/**
 * Issue #367 G1, design §4 "远程会话隔离" — the extra walls a session driven by a REMOTE machine gets on
 * top of the guest/bridge clean room. SECURITY SENSITIVE: everything here is the difference between
 * "someone else's agent works inside the folder I shared" and "someone else's agent owns my machine".
 *
 * Three separate walls, because they fail differently:
 *
 *  1. [forbidden] — a path deny-list applied INSIDE the workspace, on top of (never instead of) the
 *     pathScope containment [dev.ccpocket.daemon.agent.PermissionBridge] already enforces. pathScope
 *     answers "is this file in the shared folder"; this answers "is this file one that would hand the
 *     caller the machine even though it IS in the shared folder" — a worktree of the daemon's own repo,
 *     a project `.claude/settings.json`, a git hook.
 *  2. [stripChildEnv] — a ONE-HOP limit. The child must not inherit anything that would let it drive the
 *     daemon it is running under (the local control API's identity dir, a CLI binary override). It is a
 *     limit, NOT an isolation boundary: same OS user, so a determined process can still read the 0600
 *     token file by path. That is why (1) hard-denies the daemon state directory to the tools the daemon
 *     DOES mediate, and why the honest threat-model statement stays in the handoff doc.
 *  3. The permission CEILING itself, enforced in [RunService] — never BYPASS_PERMISSIONS.
 */
object ExecutionSandbox {

    /** Why a target is refused. Stable, log-safe codes; never the path itself. */
    const val DENY_DAEMON_STATE = "daemon_state"
    const val DENY_AGENT_CONFIG = "agent_config"
    const val DENY_OWNER_EXECUTION = "owner_execution"

    /**
     * #367 security review HIGH-1 — the mode a remote run's CLI is actually LAUNCHED with, given the
     * grant's [ceiling]. This is a WALL, which is why it lives here with the other two rather than inline
     * in [dev.ccpocket.daemon.conversation.Conversation]: it is testable on its own, and a change to it is
     * a change to the sandbox.
     *
     * Claude's native `acceptEdits` and Codex's `approvalPolicy=never` apply in-workspace Edit/Write
     * THEMSELVES, emitting no ControlRequest — and [forbidden] plus pathScope containment are only ever
     * consulted from a ControlRequest. Launching a remote run natively at that ceiling therefore disables
     * both walls completely: the caller could write `.claude/settings.json`, a git hook or `CLAUDE.md` and
     * own the owner's machine, with every wall unit test still passing.
     *
     * So an acceptEdits ceiling is launched as DEFAULT and re-created behind the walls by
     * [dev.ccpocket.daemon.agent.PermissionBridge.executionCeiling]. Same authority, walls unavoidable.
     * BYPASS_PERMISSIONS can never reach here ([ExecutionPolicy.ALLOWED_CEILINGS]); it is mapped anyway so
     * that a future widening cannot make it a native launch by omission.
     */
    fun launchMode(ceiling: PermissionMode, remoteExecution: Boolean): PermissionMode = when {
        !remoteExecution -> ceiling
        ceiling == PermissionMode.ACCEPT_EDITS || ceiling == PermissionMode.BYPASS_PERMISSIONS -> PermissionMode.DEFAULT
        else -> ceiling
    }

    /** `~/.cc-pocket` — identity, the local-control token, pairing state, grants and the run journal. */
    fun defaultStateDir(): String? = runCatching { Identity.defaultPath().absoluteFile.parentFile?.path }.getOrNull()

    /**
     * Tools whose targets the daemon can actually resolve. Bash is deliberately absent: its targets are not
     * statically knowable, so it gets the (weaker, honest) command screen in [bashMentionsDaemonState] plus
     * the ordinary owner card — never a claim of containment.
     */
    private val WRITE_TOOLS = setOf(
        "Write", "Edit", "MultiEdit", "NotebookEdit", "apply_patch", "ApplyPatch", "str_replace_editor",
    )

    /** Directories whose contents configure or extend an agent — settings, hooks, skills, MCP servers. */
    private val AGENT_CONFIG_DIRS = setOf(".claude", ".codex", ".opencode", ".cc-pocket", ".pairlet")

    /** Files that configure an agent or the daemon wherever they sit. */
    private val AGENT_CONFIG_FILES = setOf("settings.json", "settings.local.json", ".mcp.json", ".claude.json")

    /**
     * Is [canonicalTarget] refused for [tool] on a remote-run session? Null = allowed here (the ordinary
     * pathScope wall and the permission card still apply).
     *
     *  - the daemon's own state directory and any `.cc-pocket`/`.pairlet` segment: refused for EVERY tool,
     *    read included. Reading `local-control-token` is not "a file in the workspace", it is the key to
     *    this daemon's control API;
     *  - agent-config directories/files and `.git/hooks`: refused for EVERY tool. A remote caller that can
     *    write `.claude/settings.json` writes its own auto-approve rules; one that can READ it gets the
     *    owner's private endpoints and hook commands;
     *  - the wider "executes for the owner later" class ([BridgeGrant.executesForTheOwner]: `AGENTS.md`,
     *    `CLAUDE.md`, `.envrc`, anything under `.git`): refused for WRITE tools only. Reading a project's
     *    own instruction file is ordinary work; writing one is a standing prompt-injection seat in the
     *    owner's next interactive session, and nobody is watching this run.
     *
     * Segments are compared case-INsensitively for the same reason [BridgeGrant.executesForTheOwner] does:
     * the macOS filesystem is, so a case-sensitive wall is a one-character bypass.
     */
    fun forbidden(tool: String, canonicalTarget: String, stateDir: String? = defaultStateDir()): String? {
        val normalized = canonicalTarget.replace('\\', '/')
        val parts = normalized.split('/').filter { it.isNotEmpty() }.map { it.lowercase() }
        if (stateDir != null) {
            val root = PathScope.canonical(stateDir) ?: stateDir
            val target = PathScope.canonical(canonicalTarget) ?: canonicalTarget
            if (PathScope.underRoot(target, root)) return DENY_DAEMON_STATE
        }
        if (parts.any { it == ".cc-pocket" || it == ".pairlet" }) return DENY_DAEMON_STATE
        if (parts.any { it in AGENT_CONFIG_DIRS }) return DENY_AGENT_CONFIG
        if (parts.lastOrNull() in AGENT_CONFIG_FILES) return DENY_AGENT_CONFIG
        for (i in 0 until parts.size - 1) if (parts[i] == ".git" && parts[i + 1] == "hooks") return DENY_AGENT_CONFIG
        if (tool in WRITE_TOOLS && BridgeGrant.executesForTheOwner(normalized)) return DENY_OWNER_EXECUTION
        return null
    }

    /**
     * Defense in depth for Bash, NOT a sandbox: a shell command that literally names the daemon's state
     * directory is refused before the card. It is trivially evadable by an attacker (variables, globs,
     * base64) and must never be described as containment — the real limits on Bash are the permission card
     * and the ceiling, both of which stay in force.
     */
    fun bashMentionsDaemonState(command: String?, stateDir: String? = defaultStateDir()): Boolean {
        val c = command?.lowercase() ?: return false
        if (c.contains(".cc-pocket") || c.contains("local-control-token")) return true
        val dir = stateDir?.lowercase() ?: return false
        return dir.isNotEmpty() && c.contains(dir)
    }

    /**
     * ONE HOP: strip everything from a remote run's child environment that would let it drive a Pairlet
     * daemon — this one or any other. `CC_POCKET_IDENTITY` alone would repoint the CLI's local-control
     * token lookup at the owner's real state directory; the `*_BIN` overrides would let a run swap the
     * agent binary the daemon launches next.
     *
     * Deliberately a PREFIX sweep rather than a name list: a variable added next release is stripped
     * without anyone remembering to come back here, which is the failure mode an allow-list has.
     */
    fun stripChildEnv(env: MutableMap<String, String>) {
        env.keys.removeAll { key ->
            val k = key.uppercase()
            k.startsWith("CC_POCKET_") || k.startsWith("CCPOCKET_") || k.startsWith("PAIRLET_")
        }
    }

    /** Resolve a tool target the way the permission wall does: relative to the session cwd, canonical. */
    fun canonicalTarget(target: String, workdir: String?): String {
        val absolute = if (File(target).isAbsolute || workdir == null) target else File(workdir, target).path
        return PathScope.canonical(absolute) ?: absolute
    }
}
